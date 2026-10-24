package com.jdsp.cli;

/**
 * Chooses how jdspcli talks to jdspd.
 *
 * jdspd publishes both a binder service (jdsp.service) and a filesystem unix
 * socket. Production uses {@link AutoTransport}: binder first, unix socket as
 * fallback. {@code jdspcli --socket} forces the socket path.
 */
public interface TransportFactory {
    /** Default unix socket created by jdspd. */
    String DEFAULT_SOCKET_PATH = "/data/data/james.dsp/files/jdsp.sock";

    /**
     * @param socketPath null = auto (binder first, then default socket);
     *                   non-null = force the unix socket at this path
     */
    Backend open(String socketPath) throws CliException;
}
