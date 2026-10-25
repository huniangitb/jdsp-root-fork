package com.jdsp.common;

/**
 * Error code conventions for the global master switch.
 *
 * <p>The client has to be able to tell two very different situations apart:
 * <ul>
 *   <li>{@link #DISABLED} (-1006): the backend is switched off <em>on purpose</em>. A parameter
 *       write is refused, and the client should keep its intent (retry it once the user switches
 *       the backend back on) instead of treating it as a failure.</li>
 *   <li>everything else: a real failure.</li>
 * </ul>
 *
 * <p>One rule matters for the "switch itself" commands: {@code setEnabled} must <em>never</em>
 * report {@link #DISABLED}. Switching the backend on must never look like "the backend is off",
 * and switching it off must never look like a failure. That is why
 * {@link #forEnableCommand} maps the disabled code to a plain failure code.
 *
 * <p>Pure Java so the rule is unit tested on the host.
 */
public final class EnableErrors {
    /** The backend is globally disabled on purpose. */
    public static final int DISABLED = -1006;
    /** A plain internal failure. */
    public static final int INTERNAL = -1004;

    private EnableErrors() {
    }

    /**
     * The code to report for a failed {@code setEnabled} / {@code getEnabled} call.
     *
     * <p>Never {@link #DISABLED}: the client would misinterpret it as "the user switched the
     * backend off" and wait for a re-enable that may never come.
     */
    public static int forEnableCommand(int code) {
        if (code == DISABLED) {
            return INTERNAL;
        }
        return code;
    }

    /** True when the code means "the backend is switched off on purpose". */
    public static boolean isDisabled(int code) {
        return code == DISABLED;
    }
}
