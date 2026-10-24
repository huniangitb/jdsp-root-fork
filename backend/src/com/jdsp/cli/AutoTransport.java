package com.jdsp.cli;

/**
 * Binder-first transport with a loopback-TCP fallback.
 *
 * Only "unavailable" failures (exit 2) fall back; any other local error is
 * propagated unchanged.
 */
public final class AutoTransport implements TransportFactory {

    /** Opens the binder transport (usually {@code Client.connect}). */
    public interface BinderOpener {
        Backend open() throws CliException;
    }

    /** Opens the loopback TCP transport (endpoint may be null = default). */
    public interface TcpOpener {
        Backend open(String endpoint) throws CliException;
    }

    private final BinderOpener binder;
    private final TcpOpener tcp;

    public AutoTransport(BinderOpener binder, TcpOpener tcp) {
        this.binder = binder;
        this.tcp = tcp;
    }

    @Override
    public Backend open(String tcpEndpoint, boolean forceTcp) throws CliException {
        if (forceTcp) {
            return tcp.open(tcpEndpoint);
        }
        CliException binderFailure;
        try {
            return binder.open();
        } catch (CliException e) {
            if (e.exitCode() != Exit.UNAVAILABLE) {
                throw e;
            }
            binderFailure = e;
        }
        try {
            return tcp.open(null);
        } catch (CliException tcpFailure) {
            if (tcpFailure.exitCode() != Exit.UNAVAILABLE) {
                throw tcpFailure;
            }
            throw new CliException(Exit.UNAVAILABLE,
                    binderFailure.getMessage() + " and " + tcpFailure.getMessage(),
                    binderFailure);
        }
    }
}
