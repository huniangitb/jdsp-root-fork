package com.jdsp.backend;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Reader for the native engine state file (/data/vendor/audio/jamesdsp_state.bin).
 *
 * <p>Format (native/README.md and native/patches/0001-state-persistence-and-hot-reload.patch,
 * verified against a real device file):
 * <pre>
 *   header  16 B : "JDSP" | u32 version(=1) | u32 flags | u32 count
 *   record       : u32 cmd | u32 size | u8 payload[size]
 *   trailer  4 B : u32 crc32 (zlib/ISO-HDLC, over everything but these 4 bytes)
 * </pre>
 *
 * <p>A SET_PARAM record (cmd=5) carries a 16 byte parameter header, not a raw effect_param_t:
 * <pre>
 *   u32 unknown | u32 psize(=4) | u32 vsize | u32 paramId | u8 value[vsize]
 * </pre>
 * which is exactly what jdsp_state.c validates (size == 12 + psize + vsize).
 *
 * <p>Plain Java only (no Android, no org.json) so it is host testable.
 */
public final class StateBinCodec {
    /** File magic, big endian read of "JDSP". */
    public static final int MAGIC = 0x4A445350;
    /** Current on-disk version written by the engine. */
    public static final int VERSION = 1;

    /** effect command codes recorded by the engine. */
    public static final int CMD_ENABLE = 1;
    public static final int CMD_DISABLE = 2;
    public static final int CMD_SET_PARAM = 5;

    /** Hard limits: the engine refuses files outside 16+4 .. 9 MiB as well. */
    public static final int MAX_FILE_BYTES = 9 * 1024 * 1024;
    public static final int MAX_VSIZE = 8192;

    /** Parameter ids that belong to chunked transfers (impulse response / string EQ). */
    private static final int[] CHUNK_PARAM_IDS = {
            8888, 9999, 12000, 12001, 10004, 10006, 10009, 10010
    };

    private StateBinCodec() {
    }

    /** One replayed parameter value. */
    public static final class Param {
        public final int paramId;
        public final byte[] value;

        public Param(int paramId, byte[] value) {
            this.paramId = paramId;
            this.value = value;
        }
    }

    /** Parse outcome. {@link #error} is null on success. */
    public static final class Result {
        public int version;
        public int flags;
        public int recordCount;
        public int skippedInvalid;
        public int skippedChunked;
        /** True when the file carried an ENABLE/DISABLE record; see {@link #enabled}. */
        public boolean sawEnableRecord;
        public boolean enabled;
        public final List<Param> params = new ArrayList<Param>();
        public String error;

        public boolean ok() {
            return error == null;
        }

        /** paramId -> value, later records win (replay order). */
        public Map<Integer, byte[]> toMap() {
            Map<Integer, byte[]> map = new LinkedHashMap<Integer, byte[]>();
            for (int i = 0; i < params.size(); i++) {
                map.put(Integer.valueOf(params.get(i).paramId), params.get(i).value);
            }
            return map;
        }
    }

    /** Reads and parses a state file. Never throws; errors end up in {@link Result#error}. */
    public static Result parseFile(File file) {
        Result r = new Result();
        if (file == null || !file.isFile()) {
            r.error = "missing state file";
            return r;
        }
        long len = file.length();
        if (len < 20 || len > MAX_FILE_BYTES) {
            r.error = "implausible size " + len;
            return r;
        }
        byte[] data;
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            data = new byte[(int) len];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n <= 0) {
                    r.error = "short read";
                    return r;
                }
                off += n;
            }
        } catch (IOException e) {
            r.error = "io: " + e;
            return r;
        } catch (OutOfMemoryError e) {
            r.error = "oom";
            return r;
        } finally {
            close(in);
        }
        return parse(data, data.length);
    }

    /** Parses a buffer. Never throws. */
    public static Result parse(byte[] data, int length) {
        Result r = new Result();
        if (data == null || length < 20 || length > MAX_FILE_BYTES) {
            r.error = "too short or too large";
            return r;
        }
        try {
            doParse(data, length, r);
        } catch (Throwable t) {
            r.error = "malformed: " + t;
        }
        return r;
    }

    private static void doParse(byte[] d, int len, Result r) {
        int magic = ((d[0] & 0xFF) << 24) | ((d[1] & 0xFF) << 16)
                | ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
        if (magic != MAGIC) {
            r.error = "bad magic";
            return;
        }
        r.version = u32(d, 4);
        r.flags = u32(d, 8);
        r.recordCount = u32(d, 12);
        if (r.version != VERSION) {
            r.error = "unsupported version " + r.version;
            return;
        }
        int end = len - 4;
        int expected = u32(d, end);
        CRC32 crc = new CRC32();
        crc.update(d, 0, end);
        if ((int) crc.getValue() != expected) {
            r.error = "crc mismatch";
            return;
        }
        int off = 16;
        int records = 0;
        while (off + 8 <= end) {
            int cmd = u32(d, off);
            int size = u32(d, off + 4);
            off += 8;
            if (size < 0 || off + size > end) {
                r.skippedInvalid++;
                r.error = "record " + records + " overruns file";
                return;
            }
            records++;
            handleRecord(d, off, size, cmd, r);
            off += size;
        }
        if (off != end) {
            r.skippedInvalid++;
        }
    }

    private static void handleRecord(byte[] d, int off, int size, int cmd, Result r) {
        if (cmd == CMD_ENABLE || cmd == CMD_DISABLE) {
            if (size != 0) {
                r.skippedInvalid++;
                return;
            }
            r.sawEnableRecord = true;
            r.enabled = (cmd == CMD_ENABLE);
            return;
        }
        if (cmd != CMD_SET_PARAM) {
            r.skippedInvalid++;
            return;
        }
        // u32 unknown | u32 psize | u32 vsize | u32 paramId | value[vsize]
        if (size < 16) {
            r.skippedInvalid++;
            return;
        }
        int psize = u32(d, off + 4);
        int vsize = u32(d, off + 8);
        int paramId = u32(d, off + 12);
        if (psize != 4 || vsize <= 0 || vsize > MAX_VSIZE || size != 12 + psize + vsize) {
            r.skippedInvalid++;
            return;
        }
        if (isChunkParam(paramId)) {
            r.skippedChunked++;
            return;
        }
        byte[] value = new byte[vsize];
        System.arraycopy(d, off + 16, value, 0, vsize);
        r.params.add(new Param(paramId, value));
    }

    private static boolean isChunkParam(int paramId) {
        for (int i = 0; i < CHUNK_PARAM_IDS.length; i++) {
            if (CHUNK_PARAM_IDS[i] == paramId) {
                return true;
            }
        }
        return false;
    }

    private static int u32(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8)
                | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24);
    }

    private static void close(FileInputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }
}
