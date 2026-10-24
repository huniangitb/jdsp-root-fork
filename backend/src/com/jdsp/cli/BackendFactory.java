package com.jdsp.cli;

/**
 * Opens a connection to jdspd.
 *
 * Production uses {@link Client#FACTORY}; host unit tests inject a fake.
 * Opening is lazy so usage errors (exit 3) never need a running daemon and
 * never report "service unavailable" (exit 2).
 */
public interface BackendFactory {
    Backend open() throws CliException;
}
