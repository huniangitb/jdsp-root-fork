package com.jdsp.backend;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Daemon logger: file ({@code /data/adb/jdspd/jdspd.log}, rotated at 1 MiB) plus logcat tag
 * "jdspd".
 *
 * <p>Deliberately tiny and dependency free; every failure is swallowed so that logging can never
 * take the daemon down.
 */
public final class Log {
    public static final String TAG = "jdspd";
    /** Rotate when the file grows past this size. */
    public static final long MAX_BYTES = 1024L * 1024L;

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static File logFile;
    private static FileOutputStream out;
    private static long written;
    private static boolean logcat = true;
    private static boolean echo = false;

    private Log() {
    }

    /** Opens the log file (parent directory must exist). Safe to call once at startup. */
    public static void init(String path) {
        synchronized (LOCK) {
            logFile = path == null ? null : new File(path);
            written = 0L;
            openLocked();
        }
    }

    /** Also echo to stdout (used by --selftest so the output is visible in a terminal). */
    public static void setEcho(boolean on) {
        echo = on;
    }

    /** Disable logcat mirroring (host side tests, or when logcat is unavailable). */
    public static void setLogcat(boolean on) {
        logcat = on;
    }

    public static void i(String msg) {
        write('I', msg, null);
    }

    public static void w(String msg) {
        write('W', msg, null);
    }

    public static void w(String msg, Throwable t) {
        write('W', msg, t);
    }

    public static void e(String msg, Throwable t) {
        write('E', msg, t);
    }

    public static void close() {
        synchronized (LOCK) {
            closeLocked();
        }
    }

    private static void write(char level, String msg, Throwable t) {
        String line;
        synchronized (LOCK) {
            line = TS.format(new Date()) + " " + level + "/" + TAG + ": " + msg;
        }
        if (t != null) {
            line = line + "\n    " + t.toString();
        }
        synchronized (LOCK) {
            if (echo) {
                System.out.println(line);
            }
            if (logcat) {
                try {
                    if (level == 'E') {
                        android.util.Log.e(TAG, line);
                    } else if (level == 'W') {
                        android.util.Log.w(TAG, line);
                    } else {
                        android.util.Log.i(TAG, line);
                    }
                } catch (Throwable ignored) {
                    logcat = false; // no logcat in this context; keep going
                }
            }
            if (out == null) {
                return;
            }
            try {
                byte[] b = (line + "\n").getBytes("UTF-8");
                out.write(b);
                out.flush();
                written += b.length;
                if (written >= MAX_BYTES) {
                    rotateLocked();
                }
            } catch (Throwable ignored) {
                // logging must never throw
            }
        }
    }

    private static void openLocked() {
        closeLocked();
        if (logFile == null) {
            return;
        }
        try {
            File dir = logFile.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            out = new FileOutputStream(logFile, true);
            written = logFile.length();
        } catch (Throwable t) {
            out = null;
            System.err.println("jdspd: cannot open log file " + logFile + ": " + t);
        }
    }

    private static void rotateLocked() {
        try {
            closeLocked();
            File old = new File(logFile.getPath() + ".1");
            if (old.exists()) {
                old.delete();
            }
            logFile.renameTo(old);
            out = new FileOutputStream(logFile, false);
            written = 0L;
        } catch (Throwable t) {
            out = null;
        }
    }

    private static void closeLocked() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // nothing to do
            }
            out = null;
        }
    }
}
