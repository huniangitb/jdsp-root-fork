package com.jdsp.common;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;

/**
 * Lifecycle of the engine write key (the shared secret of the engine write lock,
 * see native/patches/0002-engine-write-lock.patch / jdsp_writelock.h).
 *
 * <ul>
 *   <li>jdspd generates 32 random bytes once and keeps them in
 *       {@code /data/adb/jdspd/engine.key} (0700 dir, 0600 file, root only).</li>
 *   <li>It mirrors the same bytes to {@link #VENDOR_KEY_FILE} so the audio HAL
 *       (which hosts the effect engine and must verify the token) can read it:
 *       that path lives in the same {@code vendor_audio_data_file} context as the
 *       state file, so the sepolicy rules the module already ships cover it. The
 *       module boot script fixes owner/mode/label of the mirror.</li>
 *   <li>The engine refuses every SET_PARAM until jdspd presents these bytes as
 *       the value of SET_PARAM {@link #UNLOCK_PARAM_ID} (0x7A00, reserved,
 *       unused upstream).</li>
 * </ul>
 *
 * <p>Pure Java on purpose: the decision logic is host tested (no Android API).
 * All methods are static and fail soft (null / false) - a missing key must make
 * the daemon refuse writes, never crash.
 */
public final class EngineWriteKey {
    /** SET_PARAM parameter id the engine reserves for the unlock handshake. */
    public static final int UNLOCK_PARAM_ID = 31232; /* 0x7A00 */
    /** Key length in bytes; the engine rejects any other length. */
    public static final int KEY_LEN = 32;

    /** Canonical key file inside the daemon's root-only runtime dir. */
    public static final String ADB_KEY_FILE = JdspConst.DATA_DIR + "/engine.key";
    /** Mirror the audio HAL reads; prepared/labelled by the module boot script. */
    public static final String VENDOR_KEY_FILE = "/data/vendor/audio/jamesdsp_engine.key";

    private EngineWriteKey() {
    }

    /** Non-zero, not all-zero, exactly {@link #KEY_LEN} bytes long. */
    public static boolean isValid(byte[] key) {
        if (key == null || key.length != KEY_LEN) {
            return false;
        }
        for (int i = 1; i < KEY_LEN; i++) {
            if (key[i] != key[0]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the valid key for this installation, creating the canonical file
     * when it does not exist yet. Returns null when neither reading nor creating
     * is possible (the caller must then fail closed).
     */
    public static byte[] ensureKey(File adbKeyFile) {
        byte[] existing = loadKey(adbKeyFile);
        if (existing != null) {
            return existing;
        }
        byte[] fresh = new byte[KEY_LEN];
        new SecureRandom().nextBytes(fresh);
        try {
            FileOutputStream out = new FileOutputStream(adbKeyFile);
            try {
                out.write(fresh);
            } finally {
                out.close();
            }
        } catch (IOException e) {
            return null;
        }
        // 0600: owner read/write only (the daemon runs as root).
        adbKeyFile.setReadable(false, false);
        adbKeyFile.setWritable(false, false);
        adbKeyFile.setExecutable(false, false);
        adbKeyFile.setReadable(true, true);
        adbKeyFile.setWritable(true, true);
        return loadKey(adbKeyFile);
    }

    /** Reads and validates a key file; null when missing or invalid. */
    public static byte[] loadKey(File file) {
        byte[] raw = readExactly(file, KEY_LEN);
        return isValid(raw) ? raw : null;
    }

    /**
     * Best effort mirror for the audio HAL. Returns true when the file now
     * carries the key. 0644 on purpose: the HAL reads it as uid 1041, and the
     * containing /data/vendor/audio directory is 0770 audio:audio, which is the
     * real gate (untrusted apps cannot traverse it).
     */
    public static boolean mirrorToVendor(byte[] key, File vendorFile) {
        if (!isValid(key)) {
            return false;
        }
        try {
            FileOutputStream out = new FileOutputStream(vendorFile);
            try {
                out.write(key);
            } finally {
                out.close();
            }
        } catch (IOException e) {
            return false;
        }
        vendorFile.setReadable(true, false);
        vendorFile.setWritable(false, false);
        vendorFile.setExecutable(false, false);
        return true;
    }

    private static byte[] readExactly(File file, int len) {
        if (file == null || !file.isFile()) {
            return null;
        }
        byte[] buf = new byte[len];
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            int off = 0;
            while (off < len) {
                int n = in.read(buf, off, len - off);
                if (n < 0) {
                    return null; /* short file */
                }
                off += n;
            }
            // Anything beyond the expected length is invalid by definition.
            if (in.read() >= 0) {
                return null;
            }
            return buf;
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
}
