package com.jdsp.common;

/**
 * Idempotence rules for the effect's enable state.
 *
 * <p>Calling {@code AudioEffect.setEnabled()} on every watcher tick is not harmless: the call
 * reaches the audio HAL, and a stream of them (28 per second on a busy device) was enough to
 * crash system_server. The daemon therefore only calls it when the state actually has to
 * change.
 *
 * <p>Pure Java so the rules are unit tested on the host.
 */
public final class BypassRule {
    /** No call to the framework is needed. */
    public static final int NO_CALL = 0;
    /** The effect has to be switched off (bypass). */
    public static final int CALL_DISABLE = 1;
    /** The effect has to be switched on (the master switch came back). */
    public static final int CALL_ENABLE = 2;

    private BypassRule() {
    }

    /**
     * What to do while the global master switch is off (bypass).
     *
     * @param attached  true when jdspd holds an effect handle for the session
     * @param currentlyEnabled the instance's current enable state
     * @return {@link #NO_CALL} when nothing has to change, {@link #CALL_DISABLE} when the
     *         instance has to be switched off
     */
    public static int whileDisabled(boolean attached, boolean currentlyEnabled) {
        if (!attached) {
            // Nothing to switch off (and nothing may be attached while the switch is off).
            return NO_CALL;
        }
        return currentlyEnabled ? CALL_DISABLE : NO_CALL;
    }

    /**
     * What to do while the master switch is on and the session is attached.
     *
     * @param currentlyEnabled the instance's current enable state
     * @return {@link #NO_CALL} when the instance is already on, {@link #CALL_ENABLE} when it has
     *         to be switched back on
     */
    public static int whileEnabled(boolean currentlyEnabled) {
        return currentlyEnabled ? NO_CALL : CALL_ENABLE;
    }
}
