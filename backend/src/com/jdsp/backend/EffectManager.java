package com.jdsp.backend;

import android.media.audiofx.AudioEffect;

import com.jdsp.common.JdspConst;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** Creates or adopts a handle for {@code sessionId}. {@code knownEffectId} may be -1. */
    public synchronized int attach(int sessionId, int knownEffectId) {
        if (sessionId <= 0) {
            Log.w("attach: refusing session " + sessionId);
            return ERR_BAD_ARG;
        }
        Handle existing = handles.get(Integer.valueOf(sessionId));
        if (existing != null) {
            return OK;
        }
        if (!initReflection()) {
            return ERR_INTERNAL;
        }
        long t0 = System.nanoTime();
        try {
            AudioEffect fx = ctor.newInstance(JdspConst.EFFECT_TYPE, JdspConst.EFFECT_UUID,
                    Integer.valueOf(0), Integer.valueOf(sessionId));
            int id = safeEffectId(fx);
            Handle h = new Handle(sessionId, fx, id, System.currentTimeMillis());
            handles.put(Integer.valueOf(sessionId), h);
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
        } catch (Throwable t) {
            Log.w("attach session=" + sessionId + " failed: " + describe(t), t);
            return ERR_INTERNAL;
        }
    }

    public synchronized int attach(int sessionId) {
        return attach(sessionId, -1);
    }

    /** Releases the handle. Idempotent: detaching a session without a handle returns OK. */
    public synchronized int detach(int sessionId) {
        Handle h = handles.remove(Integer.valueOf(sessionId));
        if (h == null) {
            return OK;
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

    /** Forwards one SET_PARAM to the session handle and returns the engine status (0 = applied). */
    public synchronized int setParam(int sessionId, int paramId, byte[] value) {
        Handle h = handles.get(Integer.valueOf(sessionId));
        if (h == null) {
            return ERR_NO_HANDLE;
        }
        if (value == null || value.length == 0) {
            return ERR_BAD_ARG;
        }
        if (!initReflection()) {
            return ERR_INTERNAL;
        }
        try {
            Object rc = methodSetParameter.invoke(h.effect, le32(paramId), value);
            int status = rc instanceof Integer ? ((Integer) rc).intValue() : ERR_INTERNAL;
            if (status == OK) {
                h.paramCount++;
            }
            return status;
        } catch (Throwable t) {
            Log.w("setParam session=" + sessionId + " param=" + paramId + " failed: " + describe(t));
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

    /** Enables or disables the held instance. Returns the engine status (0 = ok). */
    public synchronized int setEnabled(int sessionId, boolean on) {
        Handle h = handles.get(Integer.valueOf(sessionId));
        if (h == null) {
            return ERR_NO_HANDLE;
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

    /** Releases every handle (used on shutdown and when switching modes). */
    public synchronized int releaseAll() {
        int n = handles.size();
        List<Integer> ids = new ArrayList<Integer>(handles.keySet());
        for (int i = 0; i < ids.size(); i++) {
            detach(ids.get(i).intValue());
        }
        if (n > 0) {
            Log.i("released " + n + " effect handle(s)");
        }
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
