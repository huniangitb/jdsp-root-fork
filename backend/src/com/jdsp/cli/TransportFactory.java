package com.jdsp.cli;

/**
 * Chooses how jdspcli talks to jdspd.
 *
 * jdspd exposes two usable channels:
 *  - the binder service "jdsp.service" (reachable from the root/su domain);
 *  - loopback TCP guarded by a token (reachable from the App and from root).
 *
 * Production uses {@link AutoTransport}: binder first, TCP as fallback when the
 * binder service is absent. {@code jdspcli --tcp} forces the TCP channel.
 */
public interface TransportFactory {
    /** Host used when the endpoint has no host part. */
    String DEFAULT_TCP_HOST = "127.0.0.1";
    /** Port file written by jdspd (plain decimal). */
    String PORT_FILE = "/data/data/james.dsp/files/jdsp.port";
    /** Token file written by jdspd (hex). */
    String TOKEN_FILE = "/data/data/james.dsp/files/jdsp.token";

    /**
     * @param tcpEndpoint "host:port" or "port"; null = default (host 127.0.0.1,
     *                    port from PORT_FILE, token from TOKEN_FILE)
     * @param forceTcp    true = skip the binder and use TCP only (--tcp)
     */
    Backend open(String tcpEndpoint, boolean forceTcp) throws CliException;
}
