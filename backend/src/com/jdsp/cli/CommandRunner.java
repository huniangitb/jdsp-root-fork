package com.jdsp.cli;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

/**
 * Dispatches one parsed command against a {@link Backend}.
 *
 * The runner only touches the backend lazily, after the arguments for the
 * command have been validated. That is why a bad command line exits 3 even
 * when jdspd is not running.
 */
public final class CommandRunner {
    public static final int DEFAULT_VSIZE = 4;
    public static final int DEFAULT_BENCH_ITERS = 100;
    public static final int MAX_BENCH_ITERS = 100000;

    private final BackendFactory factory;
    private final PrintStream out;
    private final PrintStream err;
    private Backend backend;

    public CommandRunner(BackendFactory factory, PrintStream out, PrintStream err) {
        this.factory = factory;
        this.out = out;
        this.err = err;
    }

    private Backend backend() throws CliException {
        if (backend == null) {
            backend = factory.open();
        }
        return backend;
    }

    public int run(ParsedCommand cmd) {
        try {
            return dispatch(cmd);
        } catch (CliException e) {
            err.println("jdspcli: " + e.getMessage());
            if (e.exitCode() == Exit.USAGE) {
                err.println("try 'jdspcli help' for the command list");
            }
            return e.exitCode();
        } catch (RuntimeException e) {
            err.println("jdspcli: internal error: " + e);
            return Exit.FAIL;
        }
    }

    private int dispatch(ParsedCommand cmd) throws CliException {
        String command = cmd.command();
        if (cmd.help() || "help".equals(command)) {
            out.print(Usage.TEXT);
            return Exit.OK;
        }
        if ("version".equals(command)) {
            return version(cmd);
        }
        if ("ping".equals(command)) {
            return ping(cmd);
        }
        if ("sessions".equals(command)) {
            return sessions(cmd);
        }
        if ("get".equals(command)) {
            return get(cmd);
        }
        if ("set".equals(command)) {
            return set(cmd);
        }
        if ("attach".equals(command)) {
            return handle(cmd, "attach");
        }
        if ("detach".equals(command)) {
            return handle(cmd, "detach");
        }
        if ("commit".equals(command)) {
            return handle(cmd, "commit");
        }
        if ("dump-config".equals(command)) {
            return dumpText(cmd, "dump-config");
        }
        if ("dump-session".equals(command)) {
            return dumpText(cmd, "dump-session");
        }
        if ("stats".equals(command)) {
            return dumpText(cmd, "stats");
        }
        if ("mode".equals(command)) {
            return mode(cmd);
        }
        if ("enabled".equals(command)) {
            return enabled(cmd);
        }
        if ("policy".equals(command)) {
            return policy(cmd);
        }
        if ("bench".equals(command)) {
            return bench(cmd);
        }
        throw new CliException(Exit.USAGE, "unknown command: " + command);
    }

    private int version(ParsedCommand cmd) throws CliException {
        requireArgs(cmd, 0, 0);
        rejectValueOptions(cmd);
        String version = backend().getVersion();
        if (version == null) {
            version = "";
        }
        if (cmd.json()) {
            out.println("{\"version\":" + Format.jsonString(version) + "}");
        } else {
            out.println(version);
        }
        return Exit.OK;
    }

    private int ping(ParsedCommand cmd) throws CliException {
        requireArgs(cmd, 0, 0);
        rejectValueOptions(cmd);
        int status = backend().ping();
        // AIDL: int ping(); "ok" answers prove the daemon is alive. jdspd uses
        // PING_OK=1, so any non-negative answer is alive; negative codes are errors.
        if (status < 0) {
            throw new CliException(Exit.REMOTE, "ping failed: remote status " + status);
        }
        if (cmd.json()) {
            out.println("{\"ok\":true,\"status\":" + status + "}");
        } else {
            out.println("ok");
        }
        return Exit.OK;
    }

    private int sessions(ParsedCommand cmd) throws CliException {
        requireArgs(cmd, 0, 0);
        rejectValueOptions(cmd);
        List<SessionRow> sessions = backend().listSessions();
        if (cmd.json()) {
            out.println(Format.sessionsJson(sessions));
        } else {
            out.print(Format.sessionsTable(sessions));
        }
        return Exit.OK;
    }

    private int get(ParsedCommand cmd) throws CliException {
        requireArgs(cmd, 2, 3);
        rejectValueOptions(cmd);
        int session = sessionArg(cmd, 0);
        int param = paramArg(cmd, 1);
        int vsize = cmd.argCount() == 3 ? cmd.intArg(2, "vsize") : DEFAULT_VSIZE;
        if (vsize <= 0) {
            throw new CliException(Exit.USAGE, "vsize must be > 0, got " + vsize);
        }
        byte[] data = backend().getParam(session, param, vsize);
        if (data == null || data.length == 0) {
            throw new CliException(Exit.REMOTE, "getParam(session=" + session + ", param=" + param
                    + ", vsize=" + vsize + ") returned no data");
        }
        String hex = ValueCodec.toHex(data);
        if (cmd.json()) {
            out.println("{\"session\":" + session + ",\"param\":" + param
                    + ",\"vsize\":" + vsize + ",\"len\":" + data.length
                    + ",\"hex\":\"" + hex + "\"}");
        } else {
            out.println(hex);
        }
        return Exit.OK;
    }

    private int set(ParsedCommand cmd) throws CliException {
        requireArgs(cmd, 2, -1);
        rejectIters(cmd);
        int session = sessionArg(cmd, 0);
        int param = paramArg(cmd, 1);
        byte[] value = ValueCodec.encode(cmd, 2);
        int status = backend().setParam(session, param, value);
        expectOk("setParam(session=" + session + ", param=" + param + ")", status);
        String hex = ValueCodec.toHex(value);
        if (cmd.json()) {
            out.println("{\"ok\":true,\"session\":" + session + ",\"param\":" + param
                    + ",\"len\":" + value.length + ",\"hex\":\"" + hex + "\"}");
        } else {
            out.println("ok session=" + session + " param=" + param + " len=" + value.length
                    + " hex=" + hex);
        }
        return Exit.OK;
    }

    private int handle(ParsedCommand cmd, String operation) throws CliException {
        requireArgs(cmd, 1, 1);
        rejectValueOptions(cmd);
        int session = sessionArg(cmd, 0);
        int status;
        if ("attach".equals(operation)) {
            status = backend().attach(session);
        } else if ("detach".equals(operation)) {
            status = backend().detach(session);
        } else {
            status = backend().commit(session);
        }
        expectOk(operation, status);
        if (cmd.json()) {
            out.println("{\"ok\":true,\"session\":" + session + "}");
        } else {
            out.println("ok " + operation + " session=" + session);
        }
        return Exit.OK;
    }

    private int dumpText(ParsedCommand cmd, String command) throws CliException {
        rejectValueOptions(cmd);
        String text;
        if ("dump-session".equals(command)) {
            requireArgs(cmd, 1, 1);
            text = backend().dumpSession(sessionArg(cmd, 0));
        } else if ("dump-config".equals(command)) {
            requireArgs(cmd, 0, 0);
            text = backend().dumpConfig();
        } else {
            requireArgs(cmd, 0, 0);
            text = backend().stats();
        }
        if (text == null || text.isEmpty()) {
            throw new CliException(Exit.REMOTE, command + " returned no data");
        }
        out.println(trimTrailingNewlines(text));
        return Exit.OK;
    }

    private int mode(ParsedCommand cmd) throws CliException {
        requireArgs(cmd, 0, 1);
        rejectValueOptions(cmd);
        if (cmd.argCount() == 0) {
            int current = backend().getMode();
            if (cmd.json()) {
                out.println("{\"mode\":" + Format.jsonString(Format.modeName(current))
                        + ",\"modeId\":" + current + "}");
            } else {
                out.println(Format.modeName(current));
            }
            return Exit.OK;
        }
        int requested = parseMode(cmd.arg(0));
        int status = backend().setMode(requested);
        expectOk("setMode(" + Format.modeName(requested) + ")", status);
        if (cmd.json()) {
            out.println("{\"ok\":true,\"mode\":\"" + Format.modeName(requested)
                    + "\",\"modeId\":" + requested + "}");
        } else {
            out.println("mode=" + Format.modeName(requested));
        }
        return Exit.OK;
    }

    /**
     * The global master switch: "jdspcli enabled" shows the state, "jdspcli enabled on|off"
     * changes it. The daemon bypasses every session's effect while it is off.
     */
    private int enabled(ParsedCommand cmd) throws CliException {
        requireArgs(cmd, 0, 1);
        rejectValueOptions(cmd);
        if (cmd.argCount() == 0) {
            boolean current = backend().getEnabled();
            if (cmd.json()) {
                out.println("{\"enabled\":" + current + "}");
            } else {
                out.println(current ? "on" : "off");
            }
            return Exit.OK;
        }
        boolean requested = parseEnabled(cmd.arg(0));
        int status = backend().setEnabled(requested);
        expectOk("setEnabled(" + requested + ")", status);
        if (cmd.json()) {
            out.println("{\"ok\":true,\"enabled\":" + requested + "}");
        } else {
            out.println("enabled=" + (requested ? "on" : "off"));
        }
        return Exit.OK;
    }

    private int policy(ParsedCommand cmd) throws CliException {
        rejectValueOptions(cmd);
        if (cmd.argCount() < 1) {
            throw new CliException(Exit.USAGE, "usage: " + Usage.synopsis("policy"));
        }
        String sub = cmd.arg(0);
        if ("list".equals(sub)) {
            requireArgs(cmd, 1, 1);
            String text = backend().listPolicies();
            if (text == null || text.isEmpty()) {
                throw new CliException(Exit.REMOTE, "listPolicies returned no data");
            }
            out.println(trimTrailingNewlines(text));
            return Exit.OK;
        }
        if ("get".equals(sub)) {
            requireArgs(cmd, 2, 2);
            String pkg = cmd.arg(1);
            int mode = backend().getAppPolicy(pkg);
            if (cmd.json()) {
                out.println(Format.policyJson(pkg, mode));
            } else {
                out.println(pkg + " " + Format.policyName(mode));
            }
            return Exit.OK;
        }
        if ("set".equals(sub)) {
            requireArgs(cmd, 3, 3);
            String pkg = cmd.arg(1);
            int mode = parsePolicy(cmd.arg(2));
            int status = backend().setAppPolicy(pkg, mode);
            expectOk("setAppPolicy(" + pkg + ", " + Format.policyName(mode) + ")", status);
            if (cmd.json()) {
                out.println("{\"ok\":true,\"pkg\":" + Format.jsonString(pkg)
                        + ",\"policy\":" + Format.jsonString(Format.policyName(mode))
                        + ",\"policyMode\":" + mode + "}");
            } else {
                out.println("ok pkg=" + pkg + " policy=" + Format.policyName(mode));
            }
            return Exit.OK;
        }
        throw new CliException(Exit.USAGE, "usage: " + Usage.synopsis("policy"));
    }

    private int bench(ParsedCommand cmd) throws CliException {
        requireArgs(cmd, 2, -1);
        int session = sessionArg(cmd, 0);
        int param = paramArg(cmd, 1);
        byte[] value = ValueCodec.encode(cmd, 2);
        int iters = DEFAULT_BENCH_ITERS;
        if (cmd.iters() != null) {
            iters = ParsedCommand.parseInt(cmd.iters(), "--iters");
            if (iters < 1 || iters > MAX_BENCH_ITERS) {
                throw new CliException(Exit.USAGE,
                        "--iters must be between 1 and " + MAX_BENCH_ITERS);
            }
        }

        long setMin = Long.MAX_VALUE;
        long setMax = 0;
        long setSum = 0;
        long rtMin = Long.MAX_VALUE;
        long rtMax = 0;
        long rtSum = 0;
        int mismatches = 0;

        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            int status = backend().setParam(session, param, value);
            long t1 = System.nanoTime();
            if (status != 0) {
                throw new CliException(Exit.REMOTE, "bench: setParam failed with remote status "
                        + status + " at iteration " + i);
            }
            byte[] got = backend().getParam(session, param, value.length);
            long t2 = System.nanoTime();
            if (got == null || !Arrays.equals(got, value)) {
                mismatches++;
            }
            long setNs = t1 - t0;
            setMin = Math.min(setMin, setNs);
            setMax = Math.max(setMax, setNs);
            setSum += setNs;
            long rtNs = t2 - t0;
            rtMin = Math.min(rtMin, rtNs);
            rtMax = Math.max(rtMax, rtNs);
            rtSum += rtNs;
        }

        long setAvgUs = setSum / iters / 1000L;
        long setMaxUs = setMax / 1000L;
        long rtAvgUs = rtSum / iters / 1000L;
        long rtMaxUs = rtMax / 1000L;
        if (cmd.json()) {
            out.println("{\"session\":" + session + ",\"param\":" + param
                    + ",\"iters\":" + iters + ",\"setAvgUs\":" + setAvgUs
                    + ",\"setMaxUs\":" + setMaxUs + ",\"roundtripAvgUs\":" + rtAvgUs
                    + ",\"roundtripMaxUs\":" + rtMaxUs + ",\"mismatches\":" + mismatches + "}");
        } else {
            out.println("bench iters=" + iters + " set_avg_us=" + setAvgUs
                    + " set_max_us=" + setMaxUs + " roundtrip_avg_us=" + rtAvgUs
                    + " roundtrip_max_us=" + rtMaxUs + " mismatches=" + mismatches);
        }
        if (mismatches > 0) {
            throw new CliException(Exit.REMOTE, "bench: " + mismatches + " of " + iters
                    + " get results did not match the value that was set");
        }
        return Exit.OK;
    }

    private static void expectOk(String operation, int status) throws CliException {
        if (status != 0) {
            throw new CliException(Exit.REMOTE, operation + " failed: remote status " + status);
        }
    }

    private static void requireArgs(ParsedCommand cmd, int min, int max) throws CliException {
        int count = cmd.argCount();
        if (count < min || (max >= 0 && count > max)) {
            throw new CliException(Exit.USAGE,
                    "wrong number of arguments; usage: " + Usage.synopsis(cmd.command()));
        }
    }

    private static void rejectValueOptions(ParsedCommand cmd) throws CliException {
        if (cmd.raw() || cmd.hex() != null || cmd.intValue() != null || cmd.shortValue() != null) {
            throw new CliException(Exit.USAGE, "option not valid for '" + cmd.command()
                    + "'; usage: " + Usage.synopsis(cmd.command()));
        }
        rejectIters(cmd);
    }

    private static void rejectIters(ParsedCommand cmd) throws CliException {
        if (cmd.iters() != null) {
            throw new CliException(Exit.USAGE,
                    "option --iters is only valid for 'bench'");
        }
    }

    private static int sessionArg(ParsedCommand cmd, int index) throws CliException {
        int session = cmd.intArg(index, "session");
        if (session < 0) {
            throw new CliException(Exit.USAGE, "session must be >= 0, got " + session);
        }
        return session;
    }

    private static int paramArg(ParsedCommand cmd, int index) throws CliException {
        int param = cmd.intArg(index, "param");
        if (param < 0) {
            throw new CliException(Exit.USAGE, "param must be >= 0, got " + param);
        }
        return param;
    }

    private static int parseMode(String text) throws CliException {
        if ("auto".equals(text) || "0".equals(text)) {
            return 0;
        }
        if ("backend".equals(text) || "1".equals(text)) {
            return 1;
        }
        throw new CliException(Exit.USAGE, "mode must be auto or backend, got '" + text + "'");
    }

    /** "on"/"true"/"1" and "off"/"false"/"0" for the global master switch. */
    private static boolean parseEnabled(String text) throws CliException {
        String value = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT);
        if ("on".equals(value) || "true".equals(value) || "1".equals(value)
                || "enable".equals(value) || "enabled".equals(value)) {
            return true;
        }
        if ("off".equals(value) || "false".equals(value) || "0".equals(value)
                || "disable".equals(value) || "disabled".equals(value)) {
            return false;
        }
        throw new CliException(Exit.USAGE,
                "enabled must be on or off, got '" + text + "'");
    }

    private static int parsePolicy(String text) throws CliException {
        if ("follow".equals(text) || "0".equals(text)) {
            return 0;
        }
        if ("on".equals(text) || "1".equals(text)) {
            return 1;
        }
        if ("exclude".equals(text) || "2".equals(text)) {
            return 2;
        }
        throw new CliException(Exit.USAGE,
                "policy must be follow, on or exclude, got '" + text + "'");
    }

    private static String trimTrailingNewlines(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        return text.substring(0, end);
    }
}
