package com.jdsp.cli;

/**
 * Binder-first transport with unix-socket fallback.
 *
 * Only "unavailable" failures (exit 2) fall back; any other local error is
 * propagated unchanged.
 */
public final class AutoTransport implements TransportFactory {

    /** Opens the binder transport (usually {@code Client.connect}). */
    public interface BinderOpener {
        Backend open() throws CliException;
    }

    /** Opens the unix-socket transport at an explicit path. */
    public interface SocketOpener {
        Backend open(String path) throws CliException;
    }

    private final BinderOpener binder;
    private final SocketOpener socket;
    private final String defaultSocketPath;

    public AutoTransport(BinderOpener binder, SocketOpener socket) {
        this(binder, socket, TransportFactory.DEFAULT_SOCKET_PATH);
    }

    public AutoTransport(BinderOpener binder, SocketOpener socket, String defaultSocketPath) {
        this.binder = binder;
        this.socket = socket;
        this.defaultSocketPath = defaultSocketPath;
    }

    @Override
    public Backend open(String socketPath) throws CliException {
        if (socketPath != null) {
            return socket.open(socketPath);
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
            return socket.open(defaultSocketPath);
        } catch (CliException socketFailure) {
            if (socketFailure.exitCode() != Exit.UNAVAILABLE) {
                throw socketFailure;
            }
            throw new CliException(Exit.UNAVAILABLE,
                    binderFailure.getMessage() + " and " + socketFailure.getMessage(),
                    binderFailure);
        }
    }
}
