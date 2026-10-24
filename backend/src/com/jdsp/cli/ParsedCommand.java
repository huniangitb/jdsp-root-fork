package com.jdsp.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One parsed command line: command name, positional arguments and options. */
public final class ParsedCommand {
    private final String command;
    private final List<String> args;
    private final boolean json;
    private final boolean help;
    private final boolean raw;
    private final boolean socket;
    private final String socketPath;
    private final String hex;
    private final String intValue;
    private final String shortValue;
    private final String iters;

    ParsedCommand(String command, List<String> args, boolean json, boolean help, boolean raw,
                  boolean socket, String socketPath, String hex, String intValue,
                  String shortValue, String iters) {
        this.command = command;
        this.args = Collections.unmodifiableList(new ArrayList<String>(args));
        this.json = json;
        this.help = help;
        this.raw = raw;
        this.socket = socket;
        this.socketPath = socketPath;
        this.hex = hex;
        this.intValue = intValue;
        this.shortValue = shortValue;
        this.iters = iters;
    }

    public String command() {
        return command;
    }

    public List<String> args() {
        return args;
    }

    public int argCount() {
        return args.size();
    }

    public String arg(int index) {
        return args.get(index);
    }

    public boolean json() {
        return json;
    }

    public boolean help() {
        return help;
    }

    public boolean raw() {
        return raw;
    }

    /** True when --socket was given (force the unix socket transport). */
    public boolean socket() {
        return socket;
    }

    /** Socket path from --socket <path>/--socket=<path>, or null for the default. */
    public String socketPath() {
        return socketPath;
    }

    public String hex() {
        return hex;
    }

    public String intValue() {
        return intValue;
    }

    public String shortValue() {
        return shortValue;
    }

    public String iters() {
        return iters;
    }

    public String option(String name) {
        if ("hex".equals(name)) {
            return hex;
        }
        if ("int".equals(name)) {
            return intValue;
        }
        if ("short".equals(name)) {
            return shortValue;
        }
        if ("iters".equals(name)) {
            return iters;
        }
        return null;
    }

    public boolean hasOption(String name) {
        return option(name) != null;
    }

    public boolean hasValueOptions() {
        return raw || hex != null || intValue != null || shortValue != null || iters != null;
    }

    public int intArg(int index, String what) throws CliException {
        return parseInt(arg(index), what);
    }

    public static int parseInt(String text, String what) throws CliException {
        if (text == null) {
            throw new CliException(Exit.USAGE, what + " is missing");
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new CliException(Exit.USAGE,
                    what + " must be a 32-bit decimal integer, got '" + text + "'");
        }
    }
}
