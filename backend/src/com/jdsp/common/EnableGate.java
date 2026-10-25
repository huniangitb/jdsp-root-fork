package com.jdsp.common;

/**
 * The rules that decide what the daemon does with a session's effect handle.
 *
 * <p>Three independent rules, in this order:
 * <ol>
 *   <li>the app is excluded by policy: the handle is released (the effect leaves the chain);</li>
 *   <li>the global master switch is off: the handle is kept but the effect is switched off
 *       (bypass), and no new session is attached;</li>
 *   <li>otherwise the session is attached when the policy allows it.</li>
 * </ol>
 *
 * <p>Pure Java on purpose: the rules are the part that is easy to get wrong, so they are
 * unit tested on the host (see backend/test/java/com/jdsp/common/EnableGateTest.java).
 */
public final class EnableGate {
    /** What the daemon does with one session. */
    public static final int ACTION_ATTACH = 0;
    /** Keep the handle, switch the effect off (global bypass). */
    public static final int ACTION_BYPASS = 1;
    /** Release the handle (policy exclude). */
    public static final int ACTION_RELEASE = 2;

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
            // Bypass: keep an existing handle (switched off), never attach a new one.
            return alreadyHeld ? ACTION_BYPASS : ACTION_BYPASS;
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
            default:
                return "unknown";
        }
    }
}
