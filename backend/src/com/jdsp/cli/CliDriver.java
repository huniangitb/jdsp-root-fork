package com.jdsp.cli;

import java.io.PrintStream;

/** Pure command-line entry point: parse, handle help, run. */
public final class CliDriver {
    private CliDriver() {}

    public static int execute(String[] argv, TransportFactory transports, PrintStream out,
                              PrintStream err) {
        ParsedCommand cmd;
        try {
            cmd = CommandParser.parse(argv);
        } catch (CliException e) {
            err.println("jdspcli: " + e.getMessage());
            if (e.exitCode() == Exit.USAGE) {
                err.println("try 'jdspcli help' for the command list");
            }
            return e.exitCode();
        }
        if (cmd.help() || "help".equals(cmd.command())) {
            out.print(Usage.TEXT);
            out.flush();
            return Exit.OK;
        }
        final TransportFactory selected = transports;
        final String socketPath = cmd.socket()
                ? (cmd.socketPath() != null ? cmd.socketPath() : TransportFactory.DEFAULT_SOCKET_PATH)
                : null;
        BackendFactory factory = new BackendFactory() {
            @Override
            public Backend open() throws CliException {
                return selected.open(socketPath);
            }
        };
        return new CommandRunner(factory, out, err).run(cmd);
    }
}
