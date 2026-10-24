package com.jdsp.cli;

/** A CLI failure that already knows the process exit code it should produce. */
public final class CliException extends Exception {
    private static final long serialVersionUID = 1L;

    private final int exitCode;

    public CliException(int exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    public CliException(int exitCode, String message, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
