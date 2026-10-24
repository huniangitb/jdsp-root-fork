package com.jdsp.common;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Decision logic for the App's control channel endpoint files
 * ({@code jdsp.port} / {@code jdsp.token}).
 *
 * <p>The App's data directory is wiped on uninstall/reinstall, so the daemon has to re-create
 * these files while it keeps running. This class holds the decision ("must the files be
 * rewritten?") as pure, host testable code; the writing itself stays in the daemon.
 */
public final class EndpointFiles {
    /** Port file name inside the App's files directory. */
    public static final String PORT_FILE = "jdsp.port";
    /** Token file name inside the App's files directory. */
    public static final String TOKEN_FILE = "jdsp.token";

    private EndpointFiles() {
    }

    /** What the endpoint check decided. */
    public static final class Decision {
        /** True when the files have to be (re)written. */
        public final boolean rewrite;
        /** True when the App's data directory does not exist (App not installed). */
        public final boolean appMissing;
        /** True when the files are already in place and match. */
        public final boolean upToDate;

        Decision(boolean rewrite, boolean appMissing, boolean upToDate) {
            this.rewrite = rewrite;
            this.appMissing = appMissing;
            this.upToDate = upToDate;
        }
    }

    /**
     * Decides whether the endpoint files have to be written.
     *
     * @param dir       the App's files directory (may not exist)
     * @param portFile  the port file
     * @param tokenFile the token file
     * @param wantPort  expected content of the port file ("<port>\n")
     * @param wantToken expected content of the token file ("<hex>\n")
     */
    public static Decision decide(File dir, File portFile, File tokenFile,
                                  String wantPort, String wantToken) {
        if (dir == null || !dir.isDirectory()) {
            return new Decision(false, true, false);
        }
        if (hasContent(portFile, wantPort) && hasContent(tokenFile, wantToken)) {
            return new Decision(false, false, true);
        }
        return new Decision(true, false, false);
    }

    /** True when the file exists with exactly the expected content. */
    public static boolean hasContent(File file, String expected) {
        if (file == null || expected == null) {
            return false;
        }
        FileInputStream in = null;
        try {
            if (!file.isFile() || file.length() != expected.getBytes("UTF-8").length) {
                return false;
            }
            in = new FileInputStream(file);
            byte[] buf = new byte[expected.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) {
                    return false;
                }
                off += n;
            }
            return expected.equals(new String(buf, "UTF-8"));
        } catch (IOException e) {
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }
}
