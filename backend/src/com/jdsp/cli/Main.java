package com.jdsp.cli;

/**
 * jdspcli entry point.
 *
 * Run on device as:
 *   CLASSPATH=/data/local/tmp/jdspcli.dex app_process /system/bin com.jdsp.cli.Main sessions
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        int code;
        try {
            code = CliDriver.execute(args, Client.TRANSPORTS, System.out, System.err);
        } catch (Throwable t) {
            System.err.println("jdspcli: fatal: " + t);
            code = Exit.FAIL;
        }
        System.out.flush();
        System.err.flush();
        System.exit(code);
    }
}
