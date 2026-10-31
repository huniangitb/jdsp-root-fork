package com.jdsp.backend;

import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The daemon's cheap view of the active playback sessions.
 *
 * <p><b>Why this exists.</b> Session discovery used to be a full {@code dumpsys
 * media.audio_flinger} (28-150 ms, ~47-78 KB of text, one {@code sh -c} fork). The daemon only
 * needs one thing from that dump: the set of playback session ids. {@code
 * IAudioService.getActivePlaybackConfigurations()} returns exactly that - one {@code
 * AudioPlaybackConfiguration} per registered player - and on a privileged caller (this daemon
 * runs as uid 0, and {@code PermissionChecker} grants {@code MODIFY_AUDIO_ROUTING} to root)
 * {@code AudioPlaybackConfiguration.getSessionId()} is the <b>real</b> session id; {@code
 * AudioService} only substitutes {@code AUDIO_SESSION_ALLOCATE} (0) for unprivileged callers.
 * Both accessors are {@code @hide}/{@code @SystemApi} and present since Android 12. Measured on
 * the target device (OnePlus OPD2404, Android 16): <b>64-350 us per call</b> depending on the
 * number of players (first call 549 us) - four orders of magnitude cheaper than the dump.
 *
 * <p>So this class is both the change detector and the discovery source: {@link #poll()} refreshes
 * {@link #rows()} and reports whether the active player set changed. The watcher turns the rows
 * into {@code SessionInfo}s and only falls back to a dumpsys when this query is unusable, when the
 * session ids are anonymised (all 0 / all -1), or on the rare cross-check.
 *
 * <p><b>Why not a callback.</b> {@code IAudioService.registerPlaybackCallback(IPlaybackConfigDispatcher)}
 * would remove even the 100-250 ms poll (see docs/DAEMON-PROBE-COST.md, "not done"); it needs a
 * reflective {@code Binder} subclass speaking an unversioned @hide transaction, which is more
 * risk than the ~0.1 % CPU it would save, so it is documented rather than implemented.
 *
 * <p>Everything is reflection because both {@code ServiceManager} and {@code IAudioService} are
 * hidden APIs; a plain root {@code app_process} may use them (the daemon already reflects into
 * the hidden {@code AudioEffect} constructor). A non-root caller would get anonymised session ids
 * and the watcher would degrade to the dumpsys path - the fallback is exercised by tests, not
 * hoped for.
 *
 * <p><b>A failure is retried, not remembered</b> (v2.1.8): the daemon starts before the audio
 * service is registered on a normal boot, so the first setup attempt legitimately fails with
 * "audio service not found". v2.1.7 cached that forever and spent the whole boot in the timed
 * fallback; now a new setup attempt is made every {@link com.jdsp.common.ProbeSchedule#RETRY_MS}
 * ms until it succeeds, and the watcher keeps its cheap cadence from then on.
 */
final class AudioServiceProbe {
    /** One registered player as reported by {@code AudioPlaybackConfiguration}. */
    static final class Row {
        /** Real audio session id, 0 = default/global session or anonymised caller. */
        final int sessionId;
        final int uid;
        final int pid;
        /** {@code AudioPlaybackConfiguration.PLAYER_STATE_*}. */
        final int state;
        /** True when the player is actually playing (not paused/idle). */
        final boolean active;

        Row(int sessionId, int uid, int pid, int state, boolean active) {
            this.sessionId = sessionId;
            this.uid = uid;
            this.pid = pid;
            this.state = state;
            this.active = active;
        }

        @Override
        public String toString() {
            return "sess=" + sessionId + " uid=" + uid + " pid=" + pid + " state=" + state
                    + " active=" + active;
        }
    }

    private Object service;
    private Method getConfigurations;
    private Method getClientUid;
    private Method getClientPid;
    private Method getPlayerState;
    private Method getSessionId;
    private Method isActive;
    /** {@code android.media.AudioSystem.isStreamActive(int, int)} - the cheap "music playing" gate. */
    private Method isStreamActive;
    /** {@code AudioManager.STREAM_MUSIC}. */
    private static final int STREAM_MUSIC = 3;
    private long musicPolls;
    private long lastMusicCostUs;
    private boolean lastMusicActive;
    /** Last setup attempt (System.currentTimeMillis), 0 = never tried. */
    private long lastAttemptAt;
    private int setupAttempts;
    private int setupFailures;
    private String lastError = "";
    private String lastSignature = "";
    private long polls;
    private long changes;
    private long lastCostUs;
    /** Last successfully read player set (never null, may be empty). */
    private List<Row> rows = Collections.emptyList();
    /** True once the query answered at least once. */
    private boolean polledOnce;

    /**
     * True when the cheap query is usable. Sets it up on first use and retries a failed setup
     * every {@link com.jdsp.common.ProbeSchedule#RETRY_MS} ms (the audio service may simply not
     * exist yet when the daemon starts during boot).
     */
    synchronized boolean available() {
        if (service != null) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (!com.jdsp.common.ProbeSchedule.attemptDue(now, lastAttemptAt)) {
            return false;
        }
        lastAttemptAt = now;
        setupAttempts++;
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Object binder = serviceManager.getMethod("getService", String.class)
                    .invoke(null, "audio");
            if (!(binder instanceof IBinder)) {
                throw new IllegalStateException("audio service not found");
            }
            Class<?> stub = Class.forName("android.media.IAudioService$Stub");
            Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
            asInterface.setAccessible(true);
            Object svc = asInterface.invoke(null, binder);
            if (svc == null) {
                throw new IllegalStateException("asInterface returned null");
            }
            getConfigurations = svc.getClass()
                    .getMethod("getActivePlaybackConfigurations");
            getConfigurations.setAccessible(true);
            getClientUid = configGetter("getClientUid");
            getClientPid = configGetter("getClientPid");
            getPlayerState = configGetter("getPlayerState");
            getSessionId = configGetter("getSessionId");
            isActive = configGetter("isActive");
            service = svc;
            Log.i("session watcher: cheap playback probe available"
                    + com.jdsp.common.ProbeSchedule.retryNote(setupFailures)
                    + " (getActivePlaybackConfigurations, sessionIds="
                    + (getSessionId != null ? "yes" : "no") + ")");
            setupFailures = 0;
            return true;
        } catch (Throwable t) {
            setupFailures++;
            lastError = EffectManager.describe(t);
            Log.w("session watcher: cheap probe unavailable (" + lastError + "), legacy "
                    + com.jdsp.common.WatcherPolicy.LEGACY_POLL_MS + " ms polling, retrying every "
                    + com.jdsp.common.ProbeSchedule.RETRY_MS + " ms");
            return false;
        }
    }

    private static Method configGetter(String name) {
        try {
            Class<?> cls = Class.forName("android.media.AudioPlaybackConfiguration");
            Method m = cls.getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null; /* this field drops out of the signature and the row */
        }
    }

    /**
     * Runs one query. Never throws.
     *
     * @return true when the active playback signature changed since the previous poll
     */
    synchronized boolean poll() {
        if (!available()) {
            return false;
        }
        long t0 = System.nanoTime();
        try {
            Object listObj = getConfigurations.invoke(service);
            List<?> list = listObj instanceof List ? (List<?>) listObj : Collections.emptyList();
            List<Row> parsed = new ArrayList<Row>(list.size());
            for (int i = 0; i < list.size(); i++) {
                parsed.add(toRow(list.get(i)));
            }
            String signature = signature(parsed);
            rows = Collections.unmodifiableList(parsed);
            polls++;
            polledOnce = true;
            lastCostUs = (System.nanoTime() - t0) / 1000L;
            if (!signature.equals(lastSignature)) {
                lastSignature = signature;
                changes++;
                return true;
            }
            return false;
        } catch (Throwable t) {
            // A runtime failure costs one fallback period, then the setup is retried (the binder
            // may have died with a restarted audioserver, which is exactly the case where the
            // probe must come back on its own).
            service = null;
            lastAttemptAt = System.currentTimeMillis();
            setupFailures++;
            lastError = EffectManager.describe(t);
            rows = Collections.emptyList();
            Log.w("session watcher: cheap probe failed (" + lastError + "), legacy "
                    + com.jdsp.common.WatcherPolicy.LEGACY_POLL_MS + " ms polling, retrying every "
                    + com.jdsp.common.ProbeSchedule.RETRY_MS + " ms");
            return false;
        }
    }

    private Row toRow(Object cfg) {
        int sessionId = intOf(getSessionId, cfg, 0);
        int uid = intOf(getClientUid, cfg, -1);
        int pid = intOf(getClientPid, cfg, 0);
        int state = intOf(getPlayerState, cfg, 0);
        boolean active = boolOf(isActive, cfg);
        return new Row(sessionId, uid, pid, state, active);
    }

    private static int intOf(Method m, Object target, int fallback) {
        if (m == null) {
            return fallback;
        }
        try {
            Object v = m.invoke(target);
            return v instanceof Integer ? ((Integer) v).intValue() : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static boolean boolOf(Method m, Object target) {
        if (m == null) {
            return false;
        }
        try {
            Object v = m.invoke(target);
            return v instanceof Boolean && ((Boolean) v).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Stable fingerprint of the active player set: count + sorted per-player tuples. */
    private String signature(List<Row> list) {
        int n = list.size();
        List<String> entries = new ArrayList<String>(n);
        for (int i = 0; i < n; i++) {
            Row row = list.get(i);
            entries.add(row.sessionId + ":" + row.uid + ":" + row.pid + ":" + row.state);
        }
        Collections.sort(entries);
        StringBuilder sb = new StringBuilder(n * 20 + 8);
        sb.append(n).append('|');
        for (int i = 0; i < entries.size(); i++) {
            sb.append(entries.get(i)).append(',');
        }
        return sb.toString();
    }

    /** Last player set (immutable, empty when the probe is down). */
    synchronized List<Row> rows() {
        return rows;
    }

    /**
     * True when this query can be used as the discovery source: the accessor exists, it answered
     * at least once, and the session ids are real (a privileged caller) or there is nothing
     * playing at all. A non-root caller gets {@code AUDIO_SESSION_ALLOCATE} (0) for every player,
     * which is exactly the case the dumpsys fallback exists for.
     */
    synchronized boolean sessionIdsUsable() {
        // "Usable" means the query works at all, not that some player happens to own a session
        // right now: an idle system player legitimately reports session id 0 (no track allocated),
        // and treating that as "the API is anonymised" made the watcher fall back to the timed
        // dump for the whole life of the daemon (measured: 3.1 s tick, 19 dumps/min while idle).
        // The anonymised case is handled by anySessionId() together with the isStreamActive gate.
        return service != null && getSessionId != null && polledOnce;
    }

    /** True when at least one row carries a real (non-zero) session id. */
    synchronized boolean anySessionId() {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).sessionId > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * True while the music stream is active anywhere in the system.
     *
     * <p>This is the one signal the playback-configuration query cannot give: it only knows
     * players that registered with {@code AudioService} (Java {@code AudioTrack}/{@code MediaPlayer}).
     * A native player (AAudio / OpenSL ES, i.e. most games) opens its track straight in
     * AudioFlinger and never registers, so the probe reports "nothing playing" while audio
     * clearly is. {@code AudioSystem.isStreamActive(STREAM_MUSIC, 0)} answers exactly that
     * question with one cheap native call (no binder round trip, no parcel). When it says
     * "playing" while the probe says "no player", the watcher treats its own view as blind and
     * falls back to the dump until the two agree again.
     */
    synchronized boolean musicActive() {
        if (isStreamActive == null) {
            try {
                Method m = Class.forName("android.media.AudioSystem")
                        .getMethod("isStreamActive", int.class, int.class);
                m.setAccessible(true);
                isStreamActive = m;
            } catch (Throwable t) {
                lastError = "isStreamActive: " + EffectManager.describe(t);
                return false;
            }
        }
        long t0 = System.nanoTime();
        try {
            Object v = isStreamActive.invoke(null,
                    Integer.valueOf(STREAM_MUSIC), Integer.valueOf(0));
            musicPolls++;
            lastMusicCostUs = (System.nanoTime() - t0) / 1000L;
            lastMusicActive = v instanceof Boolean && ((Boolean) v).booleanValue();
            return lastMusicActive;
        } catch (Throwable t) {
            lastError = "isStreamActive: " + EffectManager.describe(t);
            isStreamActive = null;
            return false;
        }
    }

    /** Result of the last {@link #musicActive()} call (diagnostics). */
    synchronized boolean lastMusicActive() {
        return lastMusicActive;
    }

    /** Cheap "is anything playing at all" queries since start (diagnostics). */
    synchronized long musicPolls() {
        return musicPolls;
    }

    /** Cost of the last {@link #musicActive()} call in microseconds (diagnostics). */
    synchronized long lastMusicCostUs() {
        return lastMusicCostUs;
    }

    /** True while at least one player reports "playing" (drives the cheap cadence). */
    synchronized boolean playing() {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).active) {
                return true;
            }
        }
        return false;
    }

    synchronized boolean isUnavailable() {
        return service == null;
    }

    /** Setup attempts so far (1 + retries), for the watcher's status line. */
    synchronized int setupAttempts() {
        return setupAttempts;
    }

    /** Failed setup attempts so far (reset to 0 by a success). */
    synchronized int setupFailures() {
        return setupFailures;
    }

    synchronized long polls() {
        return polls;
    }

    synchronized long changes() {
        return changes;
    }

    synchronized long lastCostUs() {
        return lastCostUs;
    }

    synchronized String lastError() {
        return lastError;
    }
}
