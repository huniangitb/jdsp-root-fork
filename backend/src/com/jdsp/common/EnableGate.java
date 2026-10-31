package com.jdsp.common;

/**
 * The rules that decide what the daemon does with a session's effect handle.
 *
 * <p>Three independent rules, in this order:
 * <ol>
 *   <li>the app is excluded by policy: the handle is released (the effect leaves the chain);</li>
 *   <li>the global master switch is off: the same unload semantics as the exclusion &mdash; an
 *       already held handle is released (the instances are destroyed, nothing is left in the
 *       chain) and no new session is attached;</li>
 *   <li>otherwise the session is attached when the policy allows it.</li>
 * </ol>
 *
 * <p>Pure Java on purpose: the rules are the part that is easy to get wrong, so they are
 * unit tested on the host (see backend/test/java/com/jdsp/common/EnableGateTest.java).
 */
public final class EnableGate {
    /** What the daemon does with one session. */
    public static final int ACTION_ATTACH = 0;
    /** Nothing to do: the switch is off and no handle is held (never attach while off). */
    public static final int ACTION_BYPASS = 1;
    /** Release the handle (policy exclude, or the global master switch is off). */
    public static final int ACTION_RELEASE = 2;
    /**
     * Keep the handle and only bypass it (master switch off, bypass-first).
     *
     * <p>Disabling a live instance is one framework call (~0.25 ms measured) and
     * never touches the effect chain, while releasing + re-creating it costs
     * 28 ms (release) plus 150-420 ms (create + parameter replay) and rebuilds
     * the chain - the operation that was measured to crash the vendor HAL under
     * churn. {@link com.jdsp.backend.CoreApi} therefore bypasses first and only
     * releases the handles after {@code IDLE_UNLOAD_MS} of silence.
     */
    public static final int ACTION_BYPASS_HELD = 3;

    private EnableGate() {
    }

    /**
     * @param excluded      true when the app is excluded by policy
     * @param globallyOff   true when the global master switch is off
     * @param policyAllows  true when the policy wants the session to have an effect
     * @param alreadyHeld   true when jdspd already holds a handle for the session
     */
    public static int action(boolean excluded, boolean globallyOff, boolean policyAllows,
                             boolean alreadyHeld) {
        if (excluded) {
            return ACTION_RELEASE;
        }
        if (globallyOff) {
            // Unload semantics, identical to the exclusion path: while the switch is off no
            // instance may stay in the chain, so a held handle is released (the watcher
            // detaches it, AudioFlinger destroys the instance) and a new one is never
            // attached. Turning the switch back on re-attaches via the watcher.
            return alreadyHeld ? ACTION_BYPASS_HELD : ACTION_BYPASS;
        }
        if (policyAllows) {
            return ACTION_ATTACH;
        }
        // The policy does not want an effect here: release whatever we hold.
        return alreadyHeld ? ACTION_RELEASE : ACTION_RELEASE;
    }

    /** Human readable name, used in logs. */
    public static String name(int action) {
        switch (action) {
            case ACTION_ATTACH:
                return "attach";
            case ACTION_BYPASS:
                return "bypass";
            case ACTION_RELEASE:
                return "release";
            case ACTION_BYPASS_HELD:
                return "bypass-held";
            default:
                return "unknown";
        }
    }
}
