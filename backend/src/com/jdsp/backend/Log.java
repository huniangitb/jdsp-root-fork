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
 * <p>Two rotation sources have to be handled:
 * <ul>
 *   <li>the daemon's own size based rotation (1 MiB -&gt; {@code jdspd.log.1});</li>
 *   <li>the module's boot time rotation ({@code mv jdspd.log jdspd.log.prev}), which replaces the
 *       inode <em>behind our open file descriptor</em>. Without a check the daemon would keep
 *       appending to the renamed file forever, so the inode (and, as a fallback, the file size)
 *       is verified before every write and the file is reopened when it changed.</li>
 * </ul>
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
    /** inode of the file the current fd points at; used to detect an external rotation. */
    private static long openInode;
    private static long openDev;
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
                // Somebody (the module's boot script) may have rotated the file behind our back;
                // in that case our fd would keep appending to the renamed file.
                if (rotatedExternallyLocked()) {
                    closeLocked();
                    openLocked();
                }
                if (out == null) {
                    return;
                }
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
            rememberIdentityLocked();
        } catch (Throwable t) {
            out = null;
            System.err.println("jdspd: cannot open log file " + logFile + ": " + t);
        }
    }

    /** Remembers which inode the current fd points at (best effort). */
    private static void rememberIdentityLocked() {
        openInode = 0L;
        openDev = 0L;
        try {
            android.system.StructStat st = android.system.Os.stat(logFile.getPath());
            openInode = st.st_ino;
            openDev = st.st_dev;
        } catch (Throwable ignored) {
            // identity check unavailable; the size based rotation still works
        }
    }

    /**
     * True when the file on disk is no longer the one our fd points at, i.e. somebody else
     * rotated it (the module's boot time {@code mv jdspd.log jdspd.log.prev} does exactly that).
     * In that case our fd would keep writing into the renamed file, so we have to reopen.
     */
    private static boolean rotatedExternallyLocked() {
        if (out == null || logFile == null) {
            return false;
        }
        if (openInode != 0L) {
            try {
                android.system.StructStat st = android.system.Os.stat(logFile.getPath());
                if (st.st_ino != openInode || st.st_dev != openDev) {
                    return true; // replaced or renamed away
                }
            } catch (Throwable t) {
                // the file is gone (or unreadable): reopen, which recreates it
                return true;
            }
        }
        // Truncation (": > jdspd.log" keeps the inode but empties the file): our fd would keep
        // writing at the old offset and produce a sparse file, so reopen as well.
        try {
            long size = logFile.length();
            return size < written;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Reopens the log file when it was rotated by somebody else (the write path does this
     * automatically; this is the explicit variant for callers that want to force it).
     */
    public static void reopenIfRotated() {
        synchronized (LOCK) {
            if (rotatedExternallyLocked()) {
                try {
                    closeLocked();
                    openLocked();
                } catch (Throwable ignored) {
                    // logging must never throw
                }
            }
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
            rememberIdentityLocked();
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
