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
import java.util.concurrent.ConcurrentHashMap;
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
 * <p><b>Locking</b>: the handle table is a {@link ConcurrentHashMap} and every {@link Handle}
 * carries its own monitor plus a per-session state machine
 * ({@code ABSENT -> ATTACHING -> LIVE -> CLOSING -> ABSENT}) with an in-flight write counter.
 * A parameter write only blocks writes to the <em>same</em> session's detach; it never blocks
 * another session, so a 100 ms impulse upload on one session no longer delays the slider writes
 * of another (before: one global monitor around every reflective binder call).
 * {@code detach} flips the session to CLOSING (new writes fail fast with
 * {@link #ERR_NO_HANDLE}), waits until the in-flight writes have returned, and only then
 * disables and releases the instance, so a write can never land in a released handle.
 *
 * <p>Every failure is logged and mapped to a negative status code instead of throwing.
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

    /**
     * How long a {@code getEnabled()}/{@code hasControl()} framework read stays valid.
     *
     * <p>The watcher used to do two binder reads per handle per tick (500 ms) through
     * {@link #states()}; with a TTL the same information costs two reads per second per handle
     * instead of two per tick, and the reads no longer happen while any session's write is
     * running. 1 s is short enough for the master switch (its own path reads live, see
     * {@link #setEnabled}) and for the App's status display.
     */
    public static final long STATE_TTL_MS = 1000L;

    /** The session is live: writes and reads may run. */
    static final int ST_LIVE = 0;
    /** The session is being released: new writes fail fast, in-flight writes are drained. */
    static final int ST_CLOSING = 1;

    /** One live handle. */
    public static final class Handle {
        public final int sessionId;
        public final AudioEffect effect;
        public final int effectId;
        public final long attachedAt;
        /** Guards {@link #state} and {@link #inFlight} only (never held across a binder call). */
        final Object lock = new Object();
        /** Live -> closing transition; read without the lock is fine (only 0/1). */
        volatile int state = ST_LIVE;
        /** Writes currently inside the framework call for this session. */
        int inFlight;
        public volatile int paramCount;
        public volatile int uid = -1;
        public volatile String pkg = "";
        public volatile int pid = -1;
        /** Cached getEnabled()/hasControl() reads (see {@link #STATE_TTL_MS}); 0 = unknown. */
        volatile long enabledAtMs;
        volatile boolean enabledCached;
        volatile long controlAtMs;
        volatile boolean controlCached;

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

    private final ConcurrentHashMap<Integer, Handle> handles =
            new ConcurrentHashMap<Integer, Handle>();
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
                // Prime the state cache with the two reads the log line needs anyway, so a
                // freshly attached session costs no extra binder read for the next second.
                boolean en = safeEnabled(fx);
                boolean hc = safeHasControl(fx);
                h.enabledCached = en;
                h.enabledAtMs = System.currentTimeMillis();
                h.controlCached = hc;
                h.controlAtMs = h.enabledAtMs;
                Log.i("attach session=" + sessionId + " effectId=" + id
                        + " (dump said " + knownEffectId + ")"
                        + " enabled=" + en + " hasControl=" + hc
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

    public int attach(int sessionId) {
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
        Handle h = handles.remove(Integer.valueOf(sessionId));
        if (h == null) {
            return OK;
        }
        /* Unpublished: new writes/reads already report NO_HANDLE (they never block on a
         * closing session). Now drain the writes that are still inside the framework call:
         * releasing an instance while a SET_PARAM is in flight is what produces a binder
         * exception (and, on some builds, a zombie instance). */
        synchronized (h.lock) {
            h.state = ST_CLOSING;
            long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
            while (h.inFlight > 0) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    Log.w("detach session=" + sessionId + ": " + h.inFlight
                            + " write(s) still in flight after " + DRAIN_TIMEOUT_MS + "ms");
                    break;
                }
                try {
                    h.lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        /* The disable (bypass) and the release are binder round trips; they run outside every
         * monitor so a parallel detachAll overlaps them, and no in-flight write can reach the
         * instance any more. */
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
        /* The engine instance is gone (or will be re-created fresh and locked): drop the unlock
         * mark AFTER the release, so a concurrent attach that publishes a new handle cannot have
         * its (new) unlock state wiped by this detach. */
        gate.forget(sessionId);
        return OK;
    }

    /** How long a detach waits for in-flight writes before releasing anyway. */
    static final long DRAIN_TIMEOUT_MS = 3000L;

    /**
     * Enters the live section of a session.
     *
     * @return the handle with {@code inFlight} incremented, or null when the session has no
     *         handle / is being released (the caller answers {@link #ERR_NO_HANDLE} at once and
     *         never blocks)
     */
    private Handle acquire(int sessionId) {
        Handle h = handles.get(Integer.valueOf(sessionId));
        if (h == null) {
            return null;
        }
        synchronized (h.lock) {
            if (h.state != ST_LIVE) {
                return null;
            }
            h.inFlight++;
            return h;
        }
    }

    /** Leaves the live section; wakes a detach that is draining this session. */
    private void release(Handle h) {
        synchronized (h.lock) {
            h.inFlight--;
            if (h.inFlight <= 0) {
                h.lock.notifyAll();
            }
        }
    }

    public boolean isAttached(int sessionId) {
        return handles.containsKey(Integer.valueOf(sessionId));
    }

    /**
     * Installs the engine write key (called once from Daemon startup). Pass
     * null to disable unlocking: every parameter write then fails closed.
     */
    public void setEngineKey(byte[] key) {
        engineKey = key == null ? null : key.clone();
    }

    /** The installed engine key (a copy), or null. Diagnostics / tests. */
    public byte[] engineKey() {
        byte[] k = engineKey;
        return k == null ? null : k.clone();
    }

    /** True when the session's engine instance is currently known writable (diagnostics). */
    public boolean isUnlocked(int sessionId) {
        return gate.isUnlocked(sessionId);
    }

    /** Forwards one SET_PARAM to the session handle and returns the engine status (0 = applied). */
    public int setParam(int sessionId, int paramId, byte[] value) {
        Handle h = acquire(sessionId);
        if (h == null) {
            /* No handle, or the session is being released: fail fast, never block (a write that
             * waited here would land in an instance that is already gone). */
            return ERR_NO_HANDLE;
        }
        try {
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
                Log.w("setParam session=" + sessionId + " param=" + paramId + " vsize="
                        + value.length + " engine status=" + status
                        + " (handshake retried on the next write)");
            }
            return status;
        } finally {
            release(h);
        }
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

    /** Cached framework read of {@code getEnabled()} for one handle (TTL see STATE_TTL_MS). */
    private boolean cachedEnabled(Handle h) {
        long at = h.enabledAtMs;
        if (at != 0L && System.currentTimeMillis() - at < STATE_TTL_MS) {
            return h.enabledCached;
        }
        boolean v = safeEnabled(h.effect);
        h.enabledCached = v;
        h.enabledAtMs = System.currentTimeMillis();
        return v;
    }

    /** Cached framework read of {@code hasControl()} for one handle. */
    private boolean cachedControl(Handle h) {
        long at = h.controlAtMs;
        if (at != 0L && System.currentTimeMillis() - at < STATE_TTL_MS) {
            return h.controlCached;
        }
        boolean v = safeHasControl(h.effect);
        h.controlCached = v;
        h.controlAtMs = System.currentTimeMillis();
        return v;
    }

    /** Forces the next isEnabled()/hasControl()/state(s) of this session to read the framework. */
    public void invalidateState(int sessionId) {
        Handle h = handles.get(Integer.valueOf(sessionId));
        if (h != null) {
            h.enabledAtMs = 0L;
            h.controlAtMs = 0L;
        }
    }

    /** One GET_PARAM; the engine reply is written into {@code reply}. Returns the engine status. */
    public int getParam(int sessionId, int paramId, byte[] reply) {
        Handle h = acquire(sessionId);
        if (h == null) {
            return ERR_NO_HANDLE;
        }
        try {
            if (reply == null || reply.length < 4) {
                return ERR_BAD_ARG;
            }
            if (!initReflection()) {
                return ERR_INTERNAL;
            }
            Object rc = methodGetParameter.invoke(h.effect, le32(paramId), reply);
            return rc instanceof Integer ? ((Integer) rc).intValue() : ERR_INTERNAL;
        } catch (Throwable t) {
            Log.w("getParam session=" + sessionId + " param=" + paramId + " failed: "
                    + describe(t));
            return ERR_INTERNAL;
        } finally {
            release(h);
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
    public int setEnabled(int sessionId, boolean on) {
        Handle h = acquire(sessionId);
        if (h == null) {
            return ERR_NO_HANDLE;
        }
        try {
            if (safeEnabled(h.effect) == on) {
                // Already in the requested state: do not call the framework (idempotent on
                // purpose: a stream of redundant setEnabled calls was measured to crash
                // system_server). The cached value is refreshed below either way.
                h.enabledCached = on;
                h.enabledAtMs = System.currentTimeMillis();
                return OK;
            }
            int rc = h.effect.setEnabled(on);
            if (rc == OK) {
                h.enabledCached = on;
                h.enabledAtMs = System.currentTimeMillis();
            } else {
                h.enabledAtMs = 0L;
            }
            Log.i("setEnabled session=" + sessionId + " -> " + on + " rc=" + rc);
            return rc;
        } catch (Throwable t) {
            Log.w("setEnabled session=" + sessionId + " failed: " + describe(t));
            return ERR_INTERNAL;
        } finally {
            release(h);
        }
    }

    public boolean isEnabled(int sessionId) {
        Handle h = acquire(sessionId);
        if (h == null) {
            return false;
        }
        try {
            return cachedEnabled(h);
        } finally {
            release(h);
        }
    }

    public boolean hasControl(int sessionId) {
        Handle h = acquire(sessionId);
        if (h == null) {
            return false;
        }
        try {
            return cachedControl(h);
        } finally {
            release(h);
        }
    }

    public int effectId(int sessionId) {
        Handle h = handles.get(Integer.valueOf(sessionId));
        return h == null ? -1 : h.effectId;
    }

    public int paramCount(int sessionId) {
        Handle h = handles.get(Integer.valueOf(sessionId));
        return h == null ? 0 : h.paramCount;
    }

    /** Remembers which app a handle belongs to (for diagnostics and policing). */
    public void setOwner(int sessionId, int uid, String pkg, int pid) {
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
        List<Integer> ids = new ArrayList<Integer>(handles.keySet());
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
    public String pkgOf(int sessionId) {
        Handle h = handles.get(Integer.valueOf(sessionId));
        return h == null || h.pkg == null ? "" : h.pkg;
    }

    /** Every session that currently holds a handle. */
    public List<Integer> attachedSessionsCopy() {
        return new ArrayList<Integer>(handles.keySet());
    }

    /** Snapshot of one handle, or null (a session that is being released reports null). */
    public HandleState state(int sessionId) {
        Handle h = acquire(sessionId);
        if (h == null) {
            return null;
        }
        try {
            return new HandleState(h, cachedEnabled(h), cachedControl(h));
        } finally {
            release(h);
        }
    }

    /** Snapshot of every live handle, keyed by session id. */
    public Map<Integer, HandleState> states() {
        Map<Integer, HandleState> out = new LinkedHashMap<Integer, HandleState>();
        for (Map.Entry<Integer, Handle> e : handles.entrySet()) {
            HandleState st = state(e.getKey().intValue());
            if (st != null) {
                out.put(e.getKey(), st);
            }
        }
        return out;
    }

    public List<Integer> attachedSessions() {
        return new ArrayList<Integer>(handles.keySet());
    }

    public int handleCount() {
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
        List<Integer> ids = new ArrayList<Integer>(handles.keySet());
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
