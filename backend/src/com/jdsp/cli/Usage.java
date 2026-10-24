package com.jdsp.cli;

/** Help text and per-command synopsis for jdspcli. */
public final class Usage {
    private Usage() {}

    public static final String TEXT =
            "jdspcli \u2014 JamesDSP backend control CLI (jdspd over binder or unix socket)\n"
            + "\n"
            + "Usage:\n"
            + "  jdspcli [--json] [--socket [path]] <command> [args...]\n"
            + "\n"
            + "Commands:\n"
            + "  sessions                       List playback sessions\n"
            + "  get <session> <param> [vsize]  Read a parameter as hex (default vsize 4)\n"
            + "  set <session> <param> <v...>   Write a parameter; default is float32 LE per value\n"
            + "                                 --hex <hex>  exact bytes from a hex string\n"
            + "                                 --int <n>    int32 LE (extra positionals allowed)\n"
            + "                                 --short <n>  int16 LE (extra positionals allowed)\n"
            + "                                 --raw        positional args are hex chunks\n"
            + "  attach <session>               Create or keep an effect handle for the session\n"
            + "  detach <session>               Release the effect handle\n"
            + "  commit <session>               Flush deferred parameters\n"
            + "  dump-config                    Full configuration as JSON\n"
            + "  dump-session <session>         Per-session engine summary as JSON\n"
            + "  stats                          Runtime statistics as JSON\n"
            + "  ping                           Check that jdspd answers\n"
            + "  version                        Print the daemon version\n"
            + "  mode [auto|backend]            Show or set the architecture mode\n"
            + "  policy list                    All per-app policies (JSON)\n"
            + "  policy get <pkg>               Show one app policy\n"
            + "  policy set <pkg> <follow|on|exclude>\n"
            + "  bench <session> <param> <v...> [--iters N]\n"
            + "                                 set/get round-trip latency probe (default 100)\n"
            + "  help                           This text\n"
            + "\n"
            + "Global flags:\n"
            + "  --json                         Machine readable output\n"
            + "  --tcp [host:port]              Force the loopback TCP transport. Without --tcp\n"
            + "                                 the binder service is tried first and TCP is the\n"
            + "                                 fallback. Default endpoint: " + TransportFactory.DEFAULT_TCP_HOST + "\n"
            + "                                 + the port in " + TransportFactory.PORT_FILE + "\n"
            + "                                 authenticated with " + TransportFactory.TOKEN_FILE + "\n"
            + "  --help, -h                     This text\n"
            + "\n"
            + "Exit codes:\n"
            + "  0 ok   1 internal error   2 jdspd unavailable   3 usage   4 remote status != 0\n"
            + "\n"
            + "Examples:\n"
            + "  jdspcli sessions --json\n"
            + "  jdspcli set 161 1500 -0.1 60 3.0\n"
            + "  jdspcli get 161 1500 12\n"
            + "  jdspcli --tcp policy list\n"
            + "  jdspcli policy set com.example.music exclude\n";

    public static String synopsis(String command) {
        if ("sessions".equals(command)) {
            return "jdspcli sessions [--json]";
        }
        if ("get".equals(command)) {
            return "jdspcli get <session> <param> [vsize]";
        }
        if ("set".equals(command)) {
            return "jdspcli set <session> <param> <value...> [--hex <hex>|--int <n>|--short <n>|--raw]";
        }
        if ("attach".equals(command)) {
            return "jdspcli attach <session>";
        }
        if ("detach".equals(command)) {
            return "jdspcli detach <session>";
        }
        if ("commit".equals(command)) {
            return "jdspcli commit <session>";
        }
        if ("dump-config".equals(command)) {
            return "jdspcli dump-config";
        }
        if ("dump-session".equals(command)) {
            return "jdspcli dump-session <session>";
        }
        if ("stats".equals(command)) {
            return "jdspcli stats";
        }
        if ("ping".equals(command)) {
            return "jdspcli ping";
        }
        if ("version".equals(command)) {
            return "jdspcli version";
        }
        if ("mode".equals(command)) {
            return "jdspcli mode [auto|backend]";
        }
        if ("policy".equals(command)) {
            return "jdspcli policy list|get <pkg>|set <pkg> <follow|on|exclude>";
        }
        if ("bench".equals(command)) {
            return "jdspcli bench <session> <param> <value...> [--iters N]";
        }
        return "jdspcli " + command;
    }
}
