package com.jdsp.common;

import java.util.HashSet;
import java.util.Set;

/**
 * Per session unlock state for the engine write lock, in its <b>optimistic (probe-style,
 * fail-open) form</b>. The engine may or may not carry the write lock
 * (native/patches/0002-engine-write-lock.patch): every freshly created locked engine instance
 * refuses SET_PARAM until the daemon presents the engine key on the reserved parameter id
 * {@link EngineWriteKey#UNLOCK_PARAM_ID}; a no-lock engine build rejects that reserved id
 * itself (measured: engine b6e1fc1f). The unlock reply therefore cannot be trusted as a gate
 * &mdash; it cannot distinguish "locked and refused" from "no lock at all".
 *
 * <p>Three engine states, one algorithm:
 * <ol>
 *   <li>the unlock command is sent once before the first real write of a session, and its
 *       return value is <b>ignored</b>;</li>
 *   <li>the real write always proceeds;</li>
 *   <li>the <em>real write's</em> result decides: success marks the session unlocked (both a
 *       no-lock engine and a successfully unlocked one behave identically from here on, the
 *       next writes go straight through), failure drops the mark so the next write presents
 *       the key again (a locked engine with a wrong key keeps rejecting both, which is the
 *       fail-closed path).</li>
 * </ol>
 *
 * <p>Without a valid key installed nothing is sent at all: every write fails closed with
 * {@link #ERR_NO_KEY}. The unlock state is per session; {@link #forget} (detach, engine
 * rebuild) forces a fresh handshake.
 *
 * <p>Pure Java so the semantics are host testable; {@code Sink} is implemented
 * by the Android side (reflection into AudioEffect.setParameter).
 */
public final class EngineUnlockGate {
    /** The daemon has no (valid) engine key, so no write can be unlocked. */
    public static final int ERR_NO_KEY = -1008;

    /** The raw framework write (no unlock logic inside). */
    public interface Sink {
        int setParamRaw(int sessionId, int paramId, byte[] value);
    }

    private final Sink sink;
    private final Set<Integer> unlocked = new HashSet<Integer>();

    public EngineUnlockGate(Sink sink) {
        this.sink = sink;
    }

    /**
     * Makes sure the real write may proceed, presenting the engine key once if the session is
     * not unlocked yet. The unlock reply is deliberately ignored: a no-lock engine rejects the
     * reserved id, which says nothing about whether the real write will succeed.
     *
     * @return true when the caller must go ahead with the real write; false only when no valid
     *         key is installed (fail closed, the engine was not touched with the unlock)
     */
    public boolean presentKey(int sessionId, byte[] key) {
        if (isUnlocked(sessionId)) {
            return true;
        }
        if (!EngineWriteKey.isValid(key)) {
            return false;
        }
        /* Best effort handshake: the reply is not a gate (see class doc). */
        sink.setParamRaw(sessionId, EngineWriteKey.UNLOCK_PARAM_ID, key);
        return true;
    }

    /**
     * Reports the engine status of one real write: success marks the session unlocked (both
     * engine forms converge here), failure drops the mark so the next write re-presents the
     * key (locked engine with a wrong key stays in the fail-closed probe loop).
     */
    public void onWriteResult(int sessionId, int rc) {
        Integer id = Integer.valueOf(sessionId);
        synchronized (this) {
            if (rc == 0) {
                unlocked.add(id);
            } else {
                /* Do not trust a half success: force a fresh handshake next time. */
                unlocked.remove(id);
            }
        }
    }

    /** The engine instance behind the handle is gone (detach, engine rebuild). */
    public void forget(int sessionId) {
        synchronized (this) {
            unlocked.remove(Integer.valueOf(sessionId));
        }
    }

    public void forgetAll() {
        synchronized (this) {
            unlocked.clear();
        }
    }

    public boolean isUnlocked(int sessionId) {
        synchronized (this) {
            return unlocked.contains(Integer.valueOf(sessionId));
        }
    }
}
