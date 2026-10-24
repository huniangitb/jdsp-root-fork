package com.jdsp.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns argv into a {@link ParsedCommand}.
 *
 * Options may appear anywhere on the command line. Anything starting with
 * {@code --} is an option; a bare {@code -0.1} is a positional value, so
 * negative numbers are safe.
 */
public final class CommandParser {
    private CommandParser() {}

    static final Set<String> COMMANDS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "sessions", "get", "set", "attach", "detach", "commit", "dump-config", "dump-session",
            "stats", "ping", "version", "mode", "policy", "bench", "help")));

    public static ParsedCommand parse(String[] argv) throws CliException {
        if (argv == null || argv.length == 0) {
            throw new CliException(Exit.USAGE, "missing command (try 'jdspcli help')");
        }

        List<String> positional = new ArrayList<String>();
        boolean json = false;
        boolean help = false;
        boolean raw = false;
        boolean socket = false;
        String socketPath = null;
        boolean endOfOptions = false;
        String hex = null;
        String intValue = null;
        String shortValue = null;
        String iters = null;

        for (int i = 0; i < argv.length; i++) {
            String token = argv[i];
            if (endOfOptions || !isOption(token)) {
                positional.add(token);
                continue;
            }
            if ("--".equals(token)) {
                endOfOptions = true;
                continue;
            }

            String name = token;
            String inline = null;
            if (token.startsWith("--")) {
                int eq = token.indexOf('=');
                if (eq > 2) {
                    name = token.substring(0, eq);
                    inline = token.substring(eq + 1);
                }
            }

            if ("--json".equals(name)) {
                requireNoInline(name, inline);
                json = true;
            } else if ("--help".equals(name) || "-h".equals(name)) {
                requireNoInline(name, inline);
                help = true;
            } else if ("--raw".equals(name)) {
                requireNoInline(name, inline);
                raw = true;
            } else if ("--socket".equals(name)) {
                if (socket) {
                    throw new CliException(Exit.USAGE, "option --socket given more than once");
                }
                socket = true;
                if (inline != null) {
                    if (inline.isEmpty()) {
                        throw new CliException(Exit.USAGE,
                                "option --socket needs a non-empty path");
                    }
                    socketPath = inline;
                } else if (i + 1 < argv.length && argv[i + 1].startsWith("/")) {
                    // an absolute path is unambiguous; otherwise it is the command
                    socketPath = argv[i + 1];
                    i++;
                }
            } else if ("--hex".equals(name)) {
                if (hex != null) {
                    throw new CliException(Exit.USAGE, "option --hex given more than once");
                }
                hex = takeValue(argv, i, name, inline);
                if (inline == null) {
                    i++;
                }
            } else if ("--int".equals(name)) {
                if (intValue != null) {
                    throw new CliException(Exit.USAGE, "option --int given more than once");
                }
                intValue = takeValue(argv, i, name, inline);
                if (inline == null) {
                    i++;
                }
            } else if ("--short".equals(name)) {
                if (shortValue != null) {
                    throw new CliException(Exit.USAGE, "option --short given more than once");
                }
                shortValue = takeValue(argv, i, name, inline);
                if (inline == null) {
                    i++;
                }
            } else if ("--iters".equals(name)) {
                if (iters != null) {
                    throw new CliException(Exit.USAGE, "option --iters given more than once");
                }
                iters = takeValue(argv, i, name, inline);
                if (inline == null) {
                    i++;
                }
            } else {
                throw new CliException(Exit.USAGE, "unknown option: " + name);
            }
        }

        if (positional.isEmpty()) {
            if (help) {
                return new ParsedCommand("help", positional, json, true, raw, socket, socketPath,
                        hex, intValue, shortValue, iters);
            }
            throw new CliException(Exit.USAGE, "missing command (try 'jdspcli help')");
        }
        String command = positional.remove(0);
        if (!COMMANDS.contains(command)) {
            throw new CliException(Exit.USAGE,
                    "unknown command: " + command + " (try 'jdspcli help')");
        }
        return new ParsedCommand(command, positional, json, help, raw, socket, socketPath, hex,
                intValue, shortValue, iters);
    }

    private static boolean isOption(String token) {
        return "--".equals(token) || token.startsWith("--") || "-h".equals(token);
    }

    private static void requireNoInline(String name, String inline) throws CliException {
        if (inline != null) {
            throw new CliException(Exit.USAGE, "option " + name + " does not take a value");
        }
    }

    private static String takeValue(String[] argv, int i, String name, String inline)
            throws CliException {
        if (inline != null) {
            if (inline.isEmpty()) {
                throw new CliException(Exit.USAGE, "option " + name + " needs a value");
            }
            return inline;
        }
        if (i + 1 >= argv.length) {
            throw new CliException(Exit.USAGE, "option " + name + " needs a value");
        }
        return argv[i + 1];
    }
}
