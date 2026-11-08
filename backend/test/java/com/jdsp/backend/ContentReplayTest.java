package com.jdsp.backend;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;

import java.util.ArrayList;
import java.util.List;

/**
 * Host tests for {@link ContentReplay}: the log that makes non-persistable content parameters
 * (LiveProg script, DDC/ArbEQ blobs) survive until a session exists.
 *
 * <p>The bug this guards: {@code setParamGlobal} forwards those ids to the mounted engine and never
 * writes them to {@code config.json}, so a script pushed while nothing was playing had nowhere to
 * go - v2.1.12 answered {@code -1002} and dropped it, which meant "run" only worked if music was
 * already playing. The log is replayed on every attach, so the next session gets the script the
 * user wrote minutes earlier.
 */
public final class ContentReplayTest {
    private ContentReplayTest() {}

    /** Records the replay in order, so the engine's validation order can be asserted. */
    private static final class Recorder implements ContentReplay.Sink {
        final List<String> calls = new ArrayList<String>();
        int failFrom = Integer.MAX_VALUE;

        public int setParam(int sessionId, int paramId, byte[] value) {
            if (calls.size() >= failFrom) {
                return -1;
            }
            calls.add(sessionId + ":" + paramId + ":" + (value == null ? -1 : value.length));
            return 0;
        }
    }

    private static byte[] bytes(int n, int fill) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (fill + i);
        }
        return b;
    }

    public static void run(Suite suite) {
        suite.test("content replay: replayed in arrival order (8888 must precede the chunks)",
                new com.jdsp.cli.Body() {
            public void run() {
                ContentReplay r = ContentReplay.get();
                r.clear();
                r.record(8888, bytes(4, 1));
                r.record(12001, bytes(256, 2));
                r.record(12001, bytes(256, 3));
                r.record(10010, bytes(2, 4));
                Recorder rec = new Recorder();
                r.replay(rec, 145);
                Assert.eq("145:8888:4,145:12001:256,145:12001:256,145:10010:2",
                        join(rec.calls));
                r.clear();
            }
        });

        suite.test("content replay: 8888 starts a new transfer and drops the previous one",
                new com.jdsp.cli.Body() {
            public void run() {
                ContentReplay r = ContentReplay.get();
                r.clear();
                r.record(8888, bytes(4, 1));
                r.record(12001, bytes(256, 2));
                r.record(8888, bytes(4, 9));
                r.record(12001, bytes(64, 5));
                Recorder rec = new Recorder();
                r.replay(rec, 89);
                Assert.eq("89:8888:4,89:12001:64", join(rec.calls));
                Assert.eq(2, r.size());
                r.clear();
            }
        });

        suite.test("content replay: every new session gets the same content",
                new com.jdsp.cli.Body() {
            public void run() {
                ContentReplay r = ContentReplay.get();
                r.clear();
                r.record(8888, bytes(4, 1));
                r.record(12001, bytes(8, 2));
                Recorder a = new Recorder();
                Recorder b = new Recorder();
                long before = r.replays();
                r.replay(a, 89);
                r.replay(b, 145);
                Assert.eq(2, a.calls.size());
                Assert.eq(2, b.calls.size());
                Assert.eq("145:8888:4,145:12001:8", join(b.calls));
                Assert.eq(2L, r.replays() - before);
                r.clear();
            }
        });

        suite.test("content replay: an empty log replays nothing",
                new com.jdsp.cli.Body() {
            public void run() {
                ContentReplay r = ContentReplay.get();
                r.clear();
                Recorder rec = new Recorder();
                r.replay(rec, 89);
                Assert.eq(0, rec.calls.size());
                r.clear();
            }
        });

        suite.test("content replay: a value too large to remember is forwarded only",
                new com.jdsp.cli.Body() {
            public void run() {
                ContentReplay r = ContentReplay.get();
                r.clear();
                r.record(25000, new byte[256 * 1024 + 1]);
                Assert.eq(0, r.size());
                r.record(25000, new byte[1024]);
                Assert.eq(1, r.size());
                Assert.eq(1024, r.bytes());
                r.clear();
            }
        });

        suite.test("content replay: the log is bounded, later writes are not recorded",
                new com.jdsp.cli.Body() {
            public void run() {
                ContentReplay r = ContentReplay.get();
                r.clear();
                byte[] chunk = new byte[256];
                for (int i = 0; i < 20000; i++) {
                    r.record(12001, chunk);
                }
                // The byte budget is reached first: 1 MiB / 256 B = 4096 entries.
                Assert.eq(4096, r.size());
                Assert.eq(1024 * 1024, r.bytes());
                r.clear();
                Assert.eq(0, r.bytes());
            }
        });

        suite.test("content replay: the value is copied, not aliased to the caller's buffer",
                new com.jdsp.cli.Body() {
            public void run() {
                ContentReplay r = ContentReplay.get();
                r.clear();
                byte[] buf = bytes(4, 7);
                r.record(8888, buf);
                buf[0] = 99;
                Recorder rec = new Recorder();
                r.replay(rec, 89);
                Assert.eq("89:8888:4", join(rec.calls));
                r.clear();
            }
        });
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
