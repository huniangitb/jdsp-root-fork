package com.jdsp.backend;

import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cheap change detector for the set of active playback sessions.
 *
 * <p>Why not an audio callback: a plain root {@code app_process} has no {@code Context}, and
 * {@code ActivityThread.systemMain()} is not usable there (measured: it throws
 * {@code InvocationTargetException} because the process never ran the graphics/system-process
 * init, and it would additionally attach itself to ActivityManager as a system app - a side
 * effect a daemon must not have). {@code IAudioFlingerClient::ioConfigChanged} only reports I/O
 * configuration changes (output/input opened/closed, sample rate, channel mask), never
 * per-track or per-session events, so it cannot drive session discovery either.
 *
 * <p>What does work is one binder query: {@code IAudioService.getActivePlaybackConfigurations()}
 * reached through {@code ServiceManager.getService("audio")}. Measured on the target device
 * (OnePlus OPD2404, Android 16): <b>64-93 us per call</b> (first call 549 us) versus 28-150 ms
 * for one {@code dumpsys media.audio_flinger}. The reply carries uid/pid/state per player but
 * <b>no session id</b> (the {@code getPlayerIId}/{@code getAudioSessionId} accessors are absent on
 * this ROM), so this class is only a change detector: when the signature changes the watcher runs
 * a real dumpsys pass to learn the session ids.
 *
 * <p>Everything is reflection because both {@code ServiceManager} and {@code IAudioService} are
 * hidden APIs; a plain root {@code app_process} may use them (the daemon already reflects into
 * the hidden {@code AudioEffect} constructor).
 *
 * <p><b>A failure is retried, not remembered</b> (v2.1.8): the daemon starts before the audio
 * service is registered on a normal boot, so the first setup attempt legitimately fails with
 * "audio service not found". v2.1.7 cached that forever and spent the whole boot in the timed
 * fallback; now a new setup attempt is made every {@link com.jdsp.common.ProbeSchedule#RETRY_MS}
 * ms until it succeeds, and the watcher keeps its cheap cadence from then on.
 */
final class AudioServiceProbe {
    private Object service;
    private Method getConfigurations;
    private Method getClientUid;
    private Method getClientPid;
    private Method getPlayerState;
    /** Last setup attempt (System.currentTimeMillis), 0 = never tried. */
    private long lastAttemptAt;
    private int setupAttempts;
    private int setupFailures;
    private String lastError = "";
    private String lastSignature = "";
    private long polls;
    private long changes;
    private long lastCostUs;

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
            service = svc;
            Log.i("session watcher: cheap playback-set probe available"
                    + com.jdsp.common.ProbeSchedule.retryNote(setupFailures)
                    + " (getActivePlaybackConfigurations)");
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
            return null; /* signature degrades to the player count */
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
            List<?> list = listObj instanceof List ? (List<?>) listObj : new ArrayList<Object>();
            String signature = signature(list);
            polls++;
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
            Log.w("session watcher: cheap probe failed (" + lastError + "), legacy "
                    + com.jdsp.common.WatcherPolicy.LEGACY_POLL_MS + " ms polling, retrying every "
                    + com.jdsp.common.ProbeSchedule.RETRY_MS + " ms");
            return false;
        }
    }

    /** Stable fingerprint of the active player set: count + sorted uid/pid/state triples. */
    private String signature(List<?> list) {
        int n = list.size();
        if (getClientUid == null || getClientPid == null || getPlayerState == null) {
            return String.valueOf(n);
        }
        List<String> entries = new ArrayList<String>(n);
        for (int i = 0; i < n; i++) {
            Object cfg = list.get(i);
            entries.add(getClientUid(cfg) + ":" + getClientPid(cfg) + ":" + getPlayerState(cfg));
        }
        Collections.sort(entries);
        StringBuilder sb = new StringBuilder(n * 16 + 8);
        sb.append(n).append('|');
        for (int i = 0; i < entries.size(); i++) {
            sb.append(entries.get(i)).append(',');
        }
        return sb.toString();
    }

    private static Object safeInvoke(Method m, Object target) {
        try {
            return m.invoke(target);
        } catch (Throwable t) {
            return "?";
        }
    }

    private Object getClientUid(Object cfg) {
        return safeInvoke(getClientUid, cfg);
    }

    private Object getClientPid(Object cfg) {
        return safeInvoke(getClientPid, cfg);
    }

    private Object getPlayerState(Object cfg) {
        return safeInvoke(getPlayerState, cfg);
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
