package com.jdsp.cli;

/**
 * Process exit codes used by jdspcli.
 *
 * They are part of the contract with the acceptance scripts; keep help text
 * and device scripts in sync when changing them.
 */
public final class Exit {
    private Exit() {}

    /** Command succeeded. */
    public static final int OK = 0;
    /** Unexpected local failure (I/O error, uncaught exception). */
    public static final int FAIL = 1;
    /** jdsp.service is missing or the daemon died (not running). */
    public static final int UNAVAILABLE = 2;
    /** Bad command line (usage error). */
    public static final int USAGE = 3;
    /** The daemon answered, but reported a non-zero status. */
    public static final int REMOTE = 4;
}
