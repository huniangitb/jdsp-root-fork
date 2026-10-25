package com.jdsp.common;

/**
 * The outcome of a parameter write, in the order the daemon evaluates it.
 *
 * <p>Order matters for the client: the global master switch is checked <em>before</em> the
 * "does this session have a handle" question, so a write that arrives while the backend is
 * switched off always reports "disabled" (-1006) — even for a session that has no handle yet.
 * The client can therefore treat -1006 as "the user switched the backend off, keep the intent
 * and retry later" without having to know whether the session was attached.
 *
 * <p>Pure Java so the rule is unit tested on the host.
 */
public final class WritePolicy {
    /** The write is accepted. */
    public static final int OK = 0;
    /** The backend is switched off on purpose (see {@link EnableErrors#DISABLED}). */
    public static final int DISABLED = EnableErrors.DISABLED;
    /** The session has no effect handle (-1001). */
    public static final int NO_HANDLE = -1001;

    private WritePolicy() {
    }

    /**
     * @param globallyDisabled true when the global master switch is off
     * @param hasHandle        true when jdspd holds an effect handle for the session
     * @return {@link #OK}, {@link #DISABLED} or {@link #NO_HANDLE}
     */
    public static int writeOutcome(boolean globallyDisabled, boolean hasHandle) {
        if (globallyDisabled) {
            // Checked first: the client must be able to tell "off on purpose" from "no handle".
            return DISABLED;
        }
        if (!hasHandle) {
            return NO_HANDLE;
        }
        return OK;
    }
}
