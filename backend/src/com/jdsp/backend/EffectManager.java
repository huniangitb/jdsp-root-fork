package com.jdsp.backend;

import android.media.audiofx.AudioEffect;

import com.jdsp.common.EngineUnlockGate;
import com.jdsp.common.EngineWriteKey;
import com.jdsp.common.JdspConst;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns one {@link AudioEffect} control handle per playback session.
 *
 * <p>Both the four argument constructor and {@code setParameter}/{@code getParameter} are hidden
 * framework APIs (not in android.jar), so they are looked up reflectively once. {@code setEnabled},
 * {@code getEnabled}, {@code hasControl}, {@code getId} and {@code release} are public and called
 * directly.
 *
 * <p>When the session already carries a JamesDSP instance created by the framework postprocess,
 * AudioFlinger returns the <em>existing</em> instance handle (ALREADY_EXISTS semantics, see
 * docs/PLAN-v2-IMPL.md 2.2): jdspd can then drive the framework instance without creating a
 * second one. That is what {@code mode=auto} relies on.
 *
 * <p>All methods are synchronized; every failure is logged and mapped to a negative status code
 * instead of throwing.
 */
public final class EffectManager {
    public static final int OK = 0;
    /** No handle for that session (not attached). */
    public static final int ERR_NO_HANDLE = -1001;
    /** Unknown session. */
    public static final int ERR_NO_SESSION = -1002;
    /** Invalid argument. */
    public static final int ERR_BAD_ARG = -1003;
    /** Reflection / framework failure. */
    public static final int ERR_INTERNAL = -1004;
    /** The held instance does not report control (another client owns it). */
    public static final int ERR_NO_CONTROL = -1005;
    /**
     * An attach for that session is already running on another thread (the watcher's bounded
     * attach pool). Not an error for the caller: the session will be attached by the task that
     * is in flight; the watcher simply skips it this tick.
     */
    public static final int ERR_BUSY = -1007;

    /**
     * Shared bounded worker pool for the framework calls whose latency is dominated by the
     * binder round trip into audioserver (per attach 60-230 ms measured on the target device):
     * the watcher's per-session attach tasks and the parallel disable+release of
     * {@link #detachAll}. Four workers: enough to overlap the per-session costs, few enough to
     * keep the audioserver binder pressure bounded.
     */
    private static final ExecutorService OPS = new ThreadPoolExecutor(
            4, 4, 15L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
            new ThreadFactory() {
                private final AtomicInteger n = new AtomicInteger(1);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "jdspd-ops-" + n.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            });

    /** The shared worker pool (package private; the watcher submits its attach tasks here). */
    static ExecutorService sharedPool() {
        return OPS;
    }

    private static Constructor<AudioEffect> ctor;
    private static Method methodSetParameter;
    private static Method methodGetParameter;
    private static boolean reflectionReady;
    private static String reflectionError = "";

    /** One live handle. */
    public static final class Handle {
        public final int sessionId;
        public final AudioEffect effect;
        public final int effectId;
        public final long attachedAt;
        public int paramCount;
        public int uid = -1;
        public String pkg = "";
        public int pid = -1;

        Handle(int sessionId, AudioEffect effect, int effectId, long attachedAt) {
            this.sessionId = sessionId;
            this.effect = effect;
            this.effectId = effectId;
            this.attachedAt = attachedAt;
        }
    }

    /** Immutable view used by the transports. */
    public static final class HandleState {
        public final int sessionId;
        public final int effectId;
        public final boolean enabled;
        public final boolean hasControl;
        public final int paramCount;
        public final long attachedAt;
        public final int uid;
        public final String pkg;
        public final int pid;

        HandleState(Handle h, boolean enabled, boolean hasControl) {
            this.sessionId = h.sessionId;
            this.effectId = h.effectId;
            this.enabled = enabled;
            this.hasControl = hasControl;
            this.paramCount = h.paramCount;
            this.attachedAt = h.attachedAt;
            this.uid = h.uid;
            this.pkg = h.pkg;
            this.pid = h.pid;
        }
    }

    private final Map<Integer, Handle> handles = new LinkedHashMap<Integer, Handle>();
    /**
     * Sessions with an attach currently in flight (the AudioEffect constructor is the one
     * expensive framework call, 60-230 ms measured; it runs OUTSIDE the monitor so that the
     * watcher's parallel attach tasks really overlap, but its result is published under the
     * monitor). Guards against a double instance when two tasks race on the same session.
     */
    private final Set<Integer> attaching = new HashSet<Integer>();

    /**
     * Engine write lock (jdsp_writelock), optimistic form: some engine builds refuse
     * SET_PARAM from any caller that has not presented the engine key, others (no-lock
     * builds) reject the reserved unlock id itself &mdash; so the unlock reply is not a gate.
     * The first write of a session injects one unlock SET_PARAM (reserved id, key as value,
     * reply ignored) and proceeds with the real write; the real write's result decides
     * ({@link EngineUnlockGate#onWriteResult}): success marks the session unlocked, failure
     * forces a fresh handshake on the next write. Without a key every write fails closed
     * with {@link EngineUnlockGate#ERR_NO_KEY}. Covers ParamBus.setParam/applyAll as well,
     * because they all funnel through {@link #setParam}.
     */
    private final EngineUnlockGate gate = new EngineUnlockGate(new EngineUnlockGate.Sink() {
        @Override
        public int setParamRaw(int sessionId, int paramId, byte[] value) {
            Handle h = handles.get(Integer.valueOf(sessionId));
            if (h == null) {
                return ERR_NO_HANDLE;
            }
            return rawSetParam(h, paramId, value);
        }
    });
    /** Bytes of /data/adb/jdspd/engine.key (mirrored to the HAL-readable path). */
    private volatile byte[] engineKey;

    /** Loads the hidden framework entry points. Returns true when everything was found. */
    public static synchronized boolean initReflection() {
        if (reflectionReady) {
            return true;
        }
        try {
            ctor = AudioEffect.class.getConstructor(
                    java.util.UUID.class, java.util.UUID.class, int.class, int.class);
            ctor.setAccessible(true);
            methodSetParameter = AudioEffect.class.getMethod(
                    "setParameter", byte[].class, byte[].class);
            methodSetParameter.setAccessible(true);
            methodGetParameter = AudioEffect.class.getMethod(
                    "getParameter", byte[].class, byte[].class);
            methodGetParameter.setAccessible(true);
            reflectionReady = true;
            reflectionError = "";
            Log.i("effect reflection ready (ctor + setParameter + getParameter)");
        } catch (Throwable t) {
            reflectionReady = false;
            reflectionError = describe(t);
            Log.e("effect reflection unavailable: " + reflectionError, t);
        }
        return reflectionReady;
    }

    public static synchronized String reflectionStatus() {
        return reflectionReady ? "ok" : ("failed: " + reflectionError);
    }

    /**
     * Creates or adopts a handle for {@code sessionId}. {@code knownEffectId} may be -1.
     *
     * <p>The expensive part (the AudioEffect constructor binder round trip, which includes the
     * HAL EffectCreate) runs OUTSIDE the monitor: the watcher attaches sessions in parallel, and
     * a monitor held across the constructor would serialise them again. Bookkeeping (the
     * in-flight set, publishing the handle) stays under the monitor; the in-flight set makes a
     * same-session double attach return {@link #ERR_BUSY} instead of creating a second instance.
     */
    public int attach(int sessionId, int knownEffectId) {
        if (sessionId <= 0) {
            Log.w("attach: refusing session " + sessionId);
            return ERR_BAD_ARG;
        }
        Integer key = Integer.valueOf(sessionId);
        synchronized (this) {
            if (handles.containsKey(key)) {
                return OK;
            }
            if (attaching.contains(key)) {
                return ERR_BUSY;
            }
            if (!initReflection()) {
                return ERR_INTERNAL;
            }
            attaching.add(key);
        }
        long t0 = System.nanoTime();
        try {
            AudioEffect fx = ctor.newInstance(JdspConst.EFFECT_TYPE, JdspConst.EFFECT_UUID,
                    Integer.valueOf(0), Integer.valueOf(sessionId));
            int id = safeEffectId(fx);
            Handle h = new Handle(sessionId, fx, id, System.currentTimeMillis());
            synchronized (this) {
                if (handles.containsKey(key)) {
                    // Lost a race that the in-flight set should have prevented; keep exactly
                    // one instance and drop ours.
                    try {
                        fx.release();
                    } catch (Throwable ignored) {
                        // best effort
                    }
                    return OK;
                }
                handles.put(key, h);
                long us = (System.nanoTime() - t0) / 1000L;
                Log.i("attach session=" + sessionId + " effectId=" + id
                        + " (dump said " + knownEffectId + ")"
                        + " enabled=" + safeEnabled(fx) + " hasControl=" + safeHasControl(fx)
                        + " in " + us + "us");
                if (knownEffectId > 0 && id != knownEffectId) {
                    Log.w("attach session=" + sessionId + ": effectId " + id
                            + " differs from dumpsys " + knownEffectId);
                }
                return OK;
            }
        } catch (Throwable t) {
            Log.w("attach session=" + sessionId + " failed: " + describe(t), t);
            return ERR_INTERNAL;
        } finally {
            synchronized (this) {
                attaching.remove(key);
            }
        }
    }

    public synchronized int attach(int sessionId) {
        return attach(sessionId, -1);
    }

    /**
     * Releases the handle. Idempotent: detaching a session without a handle returns OK.
     *
     * <p><b>Disable before release (bypass-then-unload)</b>: first a synchronous
     * {@code setEnabled(false)} (an immediate bypass that reaches the HAL), then
     * {@code release()}. On ROMs that tear the EffectHandle down promptly this destroys the
     * instance; on ROMs where the handle lingers (no binder death notification while the daemon
     * is alive, measured on ColorOS 16) the leftover instance keeps its last state, so it must
     * at least be left disabled: enabled=n means zero audio processing. Release is attempted
     * either way.
     */
    public int detach(int sessionId) {
        Handle h;
        synchronized (this) {
            h = handles.remove(Integer.valueOf(sessionId));
            if (h == null) {
                return OK;
            }
            /* The engine instance will be gone (or re-created fresh and locked). */
            gate.forget(sessionId);
        }
        /* The disable (bypass) and the release are binder round trips; they run outside the
         * monitor so a parallel detachAll overlaps them. The handle is already unpublished:
         * no other method can reach it, and a racing setParam correctly reports NO_HANDLE. */
        try {
            if (safeEnabled(h.effect)) {
                h.effect.setEnabled(false);
                Log.i("detach session=" + sessionId + " disabled before release");
            }
        } catch (Throwable t) {
            // Best effort: the release below must happen regardless.
            Log.w("detach session=" + sessionId + " disable before release failed: "
                    + describe(t));
        }
        try {
            h.effect.release();
            Log.i("detach session=" + sessionId + " effectId=" + h.effectId);
        } catch (Throwable t) {
            Log.w("detach session=" + sessionId + " release failed: " + describe(t));
        }
        return OK;
    }

    public synchronized boolean isAttached(int sessionId) {
        return handles.containsKey(Integer.valueOf(sessionId));
    }

    /**
     * Installs the engine write key (called once from Daemon startup). Pass
     * null to disable unlocking: every parameter write then fails closed.
     */
    public synchronized void setEngineKey(byte[] key) {
        engineKey = key == null ? null : key.clone();
    }

    /** The installed engine key (a copy), or null. Diagnostics / tests. */
    public synchronized byte[] engineKey() {
        return engineKey == null ? null : engineKey.clone();
    }

    /** True when the session's engine instance is currently known writable (diagnostics). */
    public synchronized boolean isUnlocked(int sessionId) {
        return gate.isUnlocked(sessionId);
    }

    /** Forwards one SET_PARAM to the session handle and returns the engine status (0 = applied). */
    public synchronized int setParam(int sessionId, int paramId, byte[] value) {
        Handle h = handles.get(Integer.valueOf(sessionId));
        if (h == null) {
            return ERR_NO_HANDLE;
        }
        if (value == null || value.length == 0) {
            return ERR_BAD_ARG;
        }
        boolean unlockWrite = paramId == EngineWriteKey.UNLOCK_PARAM_ID;
        if (!unlockWrite && !gate.presentKey(sessionId, engineKey)) {
            // No valid key installed: fail closed, nothing reaches the engine.
            Log.w("setParam session=" + sessionId + " param=" + paramId
                    + " blocked: no engine key (fail closed)");
            return EngineUnlockGate.ERR_NO_KEY;
        }
        int status = rawSetParam(h, paramId, value);
        if (!unlockWrite) {
            // The real write decides: success = writable (no-lock engine or unlocked one),
            // failure = present the key again on the next write (locked engine, wrong key).
            gate.onWriteResult(sessionId, status);
        }
        if (status == OK) {
            if (!unlockWrite) {
                h.paramCount++;
            }
        } else if (!unlockWrite) {
            // The value length in the log: the engine's SET_PARAM classifier rejects malformed
            // writes with -1 (observed: an App sending 1307 with a payload that is not the 44
            // bytes the handler expects - indistinguishable from a lock rejection without it).
            Log.w("setParam session=" + sessionId + " param=" + paramId + " vsize=" + value.length
                    + " engine status=" + status + " (handshake retried on the next write)");
        }
        return status;
    }

    /** The reflective write, no unlock logic. Returns the engine status. */
    private int rawSetParam(Handle h, int paramId, byte[] value) {
        if (!initReflection()) {
            return ERR_INTERNAL;
        }
        try {
            Object rc = methodSetParameter.invoke(h.effect, le32(paramId), value);
            return rc instanceof Integer ? ((Integer) rc).intValue() : ERR_INTERNAL;
        } catch (Throwable t) {
            Log.w("setParam param=" + paramId + " failed: " + describe(t));
            return ERR_INTERNAL;
        }
    }

    /** One GET_PARAM; the engine reply is written into {@code reply}. Returns the engine status. */
    public synchronized int getParam(int sessionId, int paramId, byte[] reply) {
        Handle h = handles.get(Integer.valueOf(sessionId));
        if (h == null) {
            return ERR_NO_HANDLE;
        }
        if (reply == null || reply.length < 4) {
            return ERR_BAD_ARG;
        }
        if (!initReflection()) {
            return ERR_INTERNAL;
        }
        try {
            Object rc = methodGetParameter.invoke(h.effect, le32(paramId), reply);
            return rc instanceof Integer ? ((Integer) rc).intValue() : ERR_INTERNAL;
        } catch (Throwable t) {
            Log.w("getParam session=" + sessionId + " param=" + paramId + " failed: " + describe(t));
            return ERR_INTERNAL;
        }
    }

    /**
     * Enables or disables the held instance. Returns the engine status (0 = ok).
     *
     * <p><b>Idempotent on purpose</b>: a call that would not change the state is not forwarded to
     * the framework. {@code AudioEffect.setEnabled()} reaches the audio HAL, and a stream of
     * redundant calls (one per watcher tick and session) was measured to crash system_server on
     * a busy device. A redundant call returns 0 without touching the framework.
     */
    public synchronized int setEnabled(int sessionId, boolean on) {
        Handle h = handles.get(Integer.valueOf(sessionId));
        if (h == null) {
            return ERR_NO_HANDLE;
        }
        if (safeEnabled(h.effect) == on) {
            // Already in the requested state: do not call the framework.
            return OK;
        }
        try {
            int rc = h.effect.setEnabled(on);
            Log.i("setEnabled session=" + sessionId + " -> " + on + " rc=" + rc);
            return rc;
        } catch (Throwable t) {
            Log.w("setEnabled session=" + sessionId + " failed: " + describe(t));
            return ERR_INTERNAL;
        }
    }

    public synchronized boolean isEnabled(int sessionId) {
        Handle h = handles.get(Integer.valueOf(sessionId));
        return h != null && safeEnabled(h.effect);
    }

    public synchronized boolean hasControl(int sessionId) {
        Handle h = handles.get(Integer.valueOf(sessionId));
        return h != null && safeHasControl(h.effect);
    }

    public synchronized int effectId(int sessionId) {
        Handle h = handles.get(Integer.valueOf(sessionId));
        return h == null ? -1 : h.effectId;
    }

    public synchronized int paramCount(int sessionId) {
        Handle h = handles.get(Integer.valueOf(sessionId));
        return h == null ? 0 : h.paramCount;
    }

    /** Remembers which app a handle belongs to (for diagnostics and policing). */
    public synchronized void setOwner(int sessionId, int uid, String pkg, int pid) {
        Handle h = handles.get(Integer.valueOf(sessionId));
        if (h != null) {
            h.uid = uid;
            h.pkg = pkg == null ? "" : pkg;
            h.pid = pid;
        }
    }

    /**
     * Enables or disables every held handle (bypass-first master switch).
     *
     * <p>The per-handle framework call runs in parallel on the shared pool: one
     * call is ~0.25 ms p50 (measured, 400 toggles, 0 errors) but a device can
     * have several sessions and the call is a binder round trip that takes the
     * EffectModule lock, so overlapping them keeps the switch under a frame.
     *
     * @return the number of handles that were toggled
     */
    public int setEnabledAll(boolean on) {
        List<Integer> ids;
        synchronized (this) {
            ids = new ArrayList<Integer>(handles.keySet());
        }
        int n = ids.size();
        if (n == 0) {
            return 0;
        }
        if (n == 1) {
            setEnabled(ids.get(0).intValue(), on);
            return 1;
        }
        List<Future<Integer>> futures = new ArrayList<Future<Integer>>(n);
        for (int i = 0; i < n; i++) {
            final int id = ids.get(i).intValue();
            final boolean target = on;
            futures.add(OPS.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    return Integer.valueOf(setEnabled(id, target));
                }
            }));
        }
        for (int i = 0; i < futures.size(); i++) {
            try {
                futures.get(i).get(5L, TimeUnit.SECONDS);
            } catch (Throwable t) {
                Log.w("parallel setEnabled(" + on + ") of session " + ids.get(i)
                        + " did not finish: " + describe(t));
            }
        }
        return n;
    }

    /** Package name the handle was attached for ("" when unknown). */
    public synchronized String pkgOf(int sessionId) {
        Handle h = handles.get(Integer.valueOf(sessionId));
        return h == null || h.pkg == null ? "" : h.pkg;
    }

    /** Every session that currently holds a handle. */
    public synchronized List<Integer> attachedSessionsCopy() {
        return new ArrayList<Integer>(handles.keySet());
    }

    /** Snapshot of one handle, or null. */
    public synchronized HandleState state(int sessionId) {
        Handle h = handles.get(Integer.valueOf(sessionId));
        if (h == null) {
            return null;
        }
        return new HandleState(h, safeEnabled(h.effect), safeHasControl(h.effect));
    }

    /** Snapshot of every live handle, keyed by session id. */
    public synchronized Map<Integer, HandleState> states() {
        Map<Integer, HandleState> out = new LinkedHashMap<Integer, HandleState>();
        for (Map.Entry<Integer, Handle> e : handles.entrySet()) {
            Handle h = e.getValue();
            out.put(e.getKey(), new HandleState(h, safeEnabled(h.effect), safeHasControl(h.effect)));
        }
        return out;
    }

    public synchronized List<Integer> attachedSessions() {
        return new ArrayList<Integer>(handles.keySet());
    }

    public synchronized int handleCount() {
        return handles.size();
    }

    /**
     * Detaches every handle: the global master switch went off, so every session goes
     * through {@link #detach} (disable = immediate bypass, then release; the unlock state
     * is forgotten per session, see {@link EngineUnlockGate#forget}). Returns the number of
     * handles that were held.
     *
     * <p>The per-session disable+release binder calls run in parallel on the shared bounded
     * pool (they were serial before, which made the global "off" grow linearly with the
     * session count); this method returns once every detach has completed, so the caller's
     * "everything is unloaded" contract is unchanged.
     */
    public int detachAll() {
        return detachAllParallel(true);
    }

    /** Releases every handle (used on shutdown and when switching modes). */
    public int releaseAll() {
        return detachAllParallel(true);
    }

    private int detachAllParallel(boolean parallel) {
        List<Integer> ids;
        synchronized (this) {
            ids = new ArrayList<Integer>(handles.keySet());
        }
        int n = ids.size();
        if (n == 0) {
            return 0;
        }
        if (parallel && n > 1) {
            List<Future<Integer>> futures = new ArrayList<Future<Integer>>(n);
            for (int i = 0; i < n; i++) {
                final int id = ids.get(i).intValue();
                futures.add(OPS.submit(new Callable<Integer>() {
                    @Override
                    public Integer call() {
                        return detach(id);
                    }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    futures.get(i).get(5L, TimeUnit.SECONDS);
                } catch (Throwable t) {
                    Log.w("parallel detach of session " + ids.get(i) + " did not finish: "
                            + describe(t));
                }
            }
        } else {
            for (int i = 0; i < n; i++) {
                detach(ids.get(i).intValue());
            }
        }
        Log.i("detached all " + n + " effect handle(s)");
        return n;
    }

    // ---- helpers -------------------------------------------------------------

    private static int safeEffectId(AudioEffect fx) {
        try {
            return fx.getId();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean safeEnabled(AudioEffect fx) {
        try {
            return fx.getEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean safeHasControl(AudioEffect fx) {
        try {
            return fx.hasControl();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Little endian int32, the parameter id encoding used by the effect API. */
    public static byte[] le32(int v) {
        byte[] b = new byte[4];
        b[0] = (byte) (v & 0xFF);
        b[1] = (byte) ((v >> 8) & 0xFF);
        b[2] = (byte) ((v >> 16) & 0xFF);
        b[3] = (byte) ((v >> 24) & 0xFF);
        return b;
    }

    /** Human readable form of a(n invocation) exception, including its cause. */
    static String describe(Throwable t) {
        if (t == null) {
            return "null";
        }
        Throwable cause = t.getCause();
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getSimpleName());
        if (t.getMessage() != null) {
            sb.append(": ").append(t.getMessage());
        }
        if (cause != null && cause != t) {
            sb.append(" <- ").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null) {
                sb.append(": ").append(cause.getMessage());
            }
        }
        return sb.toString();
    }
}
