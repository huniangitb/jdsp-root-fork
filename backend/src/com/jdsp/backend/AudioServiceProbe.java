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
 * the hidden {@code AudioEffect} constructor). Any failure permanently marks the probe
 * unavailable - the watcher then falls back to its timed dump, never to a busy loop.
 */
final class AudioServiceProbe {
    private Object service;
    private Method getConfigurations;
    private Method getClientUid;
    private Method getClientPid;
    private Method getPlayerState;
    private boolean unavailable;
    private String lastError = "";
    private String lastSignature = "";
    private long polls;
    private long changes;
    private long lastCostUs;

    /** True when the cheap query is usable (tries to set it up on first use). */
    synchronized boolean available() {
        if (service != null) {
            return true;
        }
        if (unavailable) {
            return false;
        }
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
            Log.i("session watcher: cheap playback-set probe available ("
                    + "getActivePlaybackConfigurations)");
            return true;
        } catch (Throwable t) {
            unavailable = true;
            lastError = EffectManager.describe(t);
            Log.w("session watcher: cheap probe unavailable (" + lastError
                    + "), falling back to the timed " + com.jdsp.common.WatcherPolicy.LEGACY_POLL_MS
                    + " ms dump");
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
            // A failure here must not cost more than the fallback timer: give up on the probe.
            unavailable = true;
            service = null;
            lastError = EffectManager.describe(t);
            Log.w("session watcher: cheap probe failed (" + lastError
                    + "), falling back to the timed dump");
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
        return unavailable;
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
