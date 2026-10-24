package com.jdsp.backend;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Small file helpers for the daemon: atomic writes (tmp + fsync + rename), bounded reads and
 * best effort permission/ownership fixes.
 *
 * <p>Permission helpers use {@code android.system.Os} and swallow every failure: running under a
 * different SELinux domain or a read only filesystem must not stop the daemon.
 */
public final class FileUtil {
    private FileUtil() {
    }

    /** mkdirs + chmod (0700 for /data/adb/jdspd). Returns true when the directory exists. */
    public static boolean mkdirs(File dir, int mode) {
        if (dir == null) {
            return false;
        }
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            return false;
        }
        chmod(dir, mode);
        return true;
    }

    public static void chmod(File f, int mode) {
        if (f == null) {
            return;
        }
        try {
            android.system.Os.chmod(f.getPath(), mode);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /** Chown a file to the owner of its parent directory. Returns true when it worked. */
    public static boolean chownToParentOwner(File f) {
        if (f == null || f.getParentFile() == null) {
            return false;
        }
        try {
            android.system.StructStat parent =
                    android.system.Os.stat(f.getParentFile().getPath());
            android.system.Os.chown(f.getPath(), parent.st_uid, parent.st_gid);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** uid owning a path, -1 when unknown. */
    public static int ownerUid(File f) {
        if (f == null) {
            return -1;
        }
        try {
            return android.system.Os.stat(f.getPath()).st_uid;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Reads a whole file, refusing anything larger than {@code maxBytes}. Null on error. */
    public static byte[] readAll(File f, int maxBytes) {
        if (f == null || !f.isFile()) {
            return null;
        }
        long len = f.length();
        if (len <= 0 || len > maxBytes) {
            return null;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) len);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
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

    /**
     * Atomically replaces {@code target} with {@code data}: write a sibling tmp file, fsync it,
     * rename over the target, then apply {@code mode}.
     */
    public static boolean writeAtomic(File target, byte[] data, int mode) {
        if (target == null || data == null) {
            return false;
        }
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            return false;
        }
        File tmp = new File(target.getPath() + ".tmp." + android.os.Process.myPid());
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp, false);
            out.write(data);
            out.flush();
            out.getFD().sync();
            out.close();
            out = null;
            if (tmp.renameTo(target)) {
                chmod(target, mode);
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }
}
