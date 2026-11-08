package com.jdsp.backend;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Answers "which mode is the <em>running</em> audioserver in?" by reading the audio_effects.xml
 * copies that are actually mounted.
 *
 * <p><b>Why this exists.</b> {@code mode} (kept in {@code /data/adb/jdspd/mode} and
 * {@code config.json}) is what the daemon is doing right now: {@code setmode} applies it to the
 * daemon immediately. The XML side cannot follow at runtime - {@code audio_effects.xml} is read
 * once by audioserver at boot and mounted read-only from an image snapshot - so between
 * "the user picked a mode" and "the next boot" the two disagree. The App has to show that
 * difference (selected vs effective) instead of pretending the switch already took effect.
 *
 * <p><b>Judgement.</b> The module's postprocess injection is the single line
 * {@code <apply effect="jamesdsp"/>}; {@code jdsp_mode.sh} counts exactly the same marker
 * ({@code grep -c 'effect="jamesdsp"'}). One or more markers in any mounted file means the
 * framework builds the effect per stream (auto); none means the daemon builds it per session
 * (backend). A mount by another module (the module script warns about a competing
 * {@code /vendor/etc/audio_effects.xml}) is invisible here, which is the honest answer: the
 * count is read from the file that is actually visible in the filesystem namespace.
 *
 * <p>The scan is cached for {@link #TTL_MS} so the App can poll it without turning a control
 * channel request into a file read.
 */
public final class ModeEffective {
    /** Mount points the module patches, in the order the module script checks them. */
    static final String[] MOUNTED = {
            "/odm/etc/audio_effects.xml",
            "/vendor/etc/audio_effects.xml",
            "/system/etc/audio_effects.xml",
    };

    /** Vendor ships per-SKU copies on some devices; they win over the plain file. */
    static final String SKU_DIR = "/vendor/etc/audio";

    /** Marker the module injects: {@code <apply effect="jamesdsp"/>}. */
    static final String MARKER = "effect=\"jamesdsp\"";

    private static final long TTL_MS = 5000L;
    private static final int MAX_BYTES = 1024 * 1024;

    private static String cached = "";
    private static long cachedAt;

    private ModeEffective() {
    }

    /** "auto", "backend", or "" when no mounted file could be read. */
    public static synchronized String detect() {
        long now = System.currentTimeMillis();
        if (cachedAt != 0L && now - cachedAt < TTL_MS) {
            return cached;
        }
        cachedAt = now;
        cached = scan();
        return cached;
    }

    /** Drops the cache (tests, and after a mount change). */
    static synchronized void reset() {
        cachedAt = 0L;
        cached = "";
    }

    static String scan() {
        return scanFiles(mountedFiles());
    }

    /** Same decision over an explicit file list (host tests). */
    static String scanFiles(List<File> files) {
        boolean readable = false;
        for (int i = 0; i < files.size(); i++) {
            String text = read(files.get(i));
            if (text == null) {
                continue;
            }
            readable = true;
            if (count(text) > 0) {
                return "auto";
            }
        }
        return readable ? "backend" : "";
    }

    /** The XML files that exist on this device, plain mount points first, then SKU variants. */
    static List<File> mountedFiles() {
        List<File> out = new ArrayList<File>();
        for (int i = 0; i < MOUNTED.length; i++) {
            File f = new File(MOUNTED[i]);
            if (f.isFile()) {
                out.add(f);
            }
        }
        File dir = new File(SKU_DIR);
        File[] skus = dir.listFiles();
        if (skus != null) {
            for (int i = 0; i < skus.length; i++) {
                if (!skus[i].isDirectory()) {
                    continue;
                }
                File f = new File(skus[i], "audio_effects.xml");
                if (f.isFile()) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    /** Occurrences of the module's postprocess marker. */
    static int count(String text) {
        if (text == null) {
            return 0;
        }
        int n = 0;
        int at = 0;
        while (true) {
            int hit = text.indexOf(MARKER, at);
            if (hit < 0) {
                return n;
            }
            n++;
            at = hit + MARKER.length();
        }
    }

    private static String read(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[MAX_BYTES];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }
}
