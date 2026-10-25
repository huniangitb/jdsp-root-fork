package com.jdsp.backend;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;

import java.util.List;

/**
 * Host tests for the thread-section filtering of {@link DumpsysParser}: only output threads
 * (Output thread / type 0 (MIXER) and friends) contribute attachable sessions. Input/record
 * thread track tables (e.g. the mic stream's session) and "Global session refs" rows of
 * sessions without an output track must never become attach targets - the watcher used to
 * retry a doomed attach for them on every 500 ms tick.
 *
 * <p>The samples mirror the real dump of the target device (xaga / Android 15, verified
 * 2026-10-25): "Output thread 0xb400..., name AudioOut_D, tid 2502, type 0 (MIXER):" with the
 * track table below, "Global session refs:" before the first thread.
 */
public final class DumpsysParserTest {
    private DumpsysParserTest() {}

    /** One output thread of the real device dump (tracks 145, uid/pkg resolvable via refs). */
    private static final String OUTPUT_THREAD =
            "Output thread 0xb400007995874700, name AudioOut_D, tid 2502, type 0 (MIXER):\n"
            + "  I/O handle: 13\n"
            + "  Standby: yes\n"
            + "  RT thread: yes\n"
            + "  Sample rate: 48000 Hz\n"
            + "  Thread throttle time (msecs): 99\n"
            + "  No FastMixer\n"
            + "  1 Tracks of which 0 are active\n"
            + "    Type     Id Active Client Session Port Id S  Flags   Format Chn mask  SRate\n"
            + "             59     no  14218     145      33 I  0x000 00000005 00000003  48000\n"
            + "  2 Effect Chains\n"
            + "    1 effects for session 145\n"
            + "\tEffect ID 83:\n"
            + "\t\tSession State Registered Internal Enabled Suspended:\n"
            + "\t\t00145   002   y          n        y       n\n"
            + "\t\tDescriptor:\n"
            + "\t\t- UUID: f27317f4-c984-4de6-9a90-545759495bf2\n";

    /** An input (record) thread: same track table shape, but a mic session. */
    private static final String INPUT_THREAD_A15 =
            "Input thread 0xb4000079958b8fe8, name AudioIn_D, tid 5958, type 3 (RECORD):\n"
            + "  I/O handle: 45\n"
            + "  Standby: no\n"
            + "  Sample rate: 48000 Hz\n"
            + "  1 Tracks of which 1 are active\n"
            + "    Type     Id Active Client Session Port Id S  Flags   Format Chn mask  SRate\n"
            + "           518    yes    4818    8273       8 R  0x000 00000005 00000003  48000\n";

    /** The variant the field report described: input thread, bare "type 1" (no label). */
    private static final String INPUT_THREAD_TYPE1 =
            "Input thread 0xb4000079958b8fe8, name AudioIn_D, tid 5958, type 1:\n"
            + "  1 Tracks of which 1 are active\n"
            + "    Type     Id Active Client Session Port Id S  Flags   Format Chn mask  SRate\n"
            + "           518    yes    4818    8273       8 R  0x000 00000005 00000003  48000\n";

    /** An Mmap thread: neither "Output" nor "Input" in the header, not an attach target. */
    private static final String MMAP_THREAD =
            "Mmap thread 0xb4000079958b8c00, tid 5960, type 4 (MMAP_IO):\n"
            + "  1 Tracks of which 0 are active\n"
            + "    Type     Id Active Client Session Port Id S  Flags   Format Chn mask  SRate\n"
            + "           519     no    4818    8274       9 A  0x000 00000005 00000003  48000\n";

    private static final String GLOBAL_REFS =
            "Global session refs:\n"
            + "  session  cnt     pid    uid  name\n"
            + "      145    1    2338      0  root\n"
            + "      145    1   14218  10243  com.salt.music\n"
            + "     8273    1    4818  10243  com.recorder.app\n";

    private static int indexOf(List<SessionInfo> list, int sessionId) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).sessionId == sessionId) {
                return i;
            }
        }
        return -1;
    }

    public static void run(Suite suite) {
        suite.test("dumpsys: output thread sessions are parsed and enriched by refs",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                List<SessionInfo> out = DumpsysParser.parse(
                        GLOBAL_REFS + "\n" + OUTPUT_THREAD);
                int at = indexOf(out, 145);
                Assert.isTrue(at >= 0, "session 145 present");
                SessionInfo s = out.get(at);
                Assert.eq(10243, s.uid, "uid from the non-audioserver ref");
                Assert.eq("com.salt.music", s.refName);
                Assert.isTrue(s.jdspPresent, "JamesDSP chain of the output session detected");
                Assert.eq(83, s.effectId);
            }
        });
        suite.test("dumpsys: input thread (type 3 (RECORD)) sessions are not attach targets",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                List<SessionInfo> out = DumpsysParser.parse(
                        GLOBAL_REFS + "\n" + OUTPUT_THREAD + "\n" + INPUT_THREAD_A15);
                Assert.eq(-1, indexOf(out, 8273),
                        "record session 8273 must not be parsed");
                Assert.isTrue(indexOf(out, 145) >= 0, "output session still present");
            }
        });
        suite.test("dumpsys: input thread with a bare 'type 1' header is not an attach target",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                List<SessionInfo> out = DumpsysParser.parse(INPUT_THREAD_TYPE1);
                Assert.eq(-1, indexOf(out, 8273), "record session 8273 must not be parsed");
            }
        });
        suite.test("dumpsys: a ref-only session (no output track) is never attachable",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                // 8273 appears ONLY in the global refs table: previously this created a
                // SessionInfo and the watcher tried to attach it every tick.
                List<SessionInfo> out = DumpsysParser.parse(GLOBAL_REFS + "\n" + OUTPUT_THREAD);
                Assert.eq(-1, indexOf(out, 8273), "ref-only session must not be created");
            }
        });
        suite.test("dumpsys: mmap thread sessions are not attach targets", new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                List<SessionInfo> out = DumpsysParser.parse(MMAP_THREAD);
                Assert.eq(-1, indexOf(out, 8274), "mmap session must not be parsed");
            }
        });
        suite.test("dumpsys: sections alternate - output after input is parsed again",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                List<SessionInfo> out = DumpsysParser.parse(INPUT_THREAD_A15 + "\n"
                        + "Output thread 0xb40000799506f700, name AudioOut_15, tid 2505, type 0 (MIXER):\n"
                        + "  1 Tracks of which 0 are active\n"
                        + "    Type     Id Active Client Session Port Id S  Flags   Format Chn mask  SRate\n"
                        + "             60     no  14218     146      34 I  0x000 00000005 00000003  48000\n");
                Assert.eq(-1, indexOf(out, 8273), "input session of the first section skipped");
                Assert.isTrue(indexOf(out, 146) >= 0, "second output thread still parsed");
            }
        });
        suite.test("dumpsys: legacy dump without thread headers still parses every track",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                // A ROM whose dump has no "Output thread" header at all: the track table of
                // the unknown format must keep working (never silently produce zero sessions).
                String legacy = "Global session refs:\n"
                        + "  session  cnt     pid    uid  name\n"
                        + "      145    1   14218  10243  com.salt.music\n"
                        + "\n"
                        + "  1 Tracks of which 0 are active\n"
                        + "    Type     Id Active Client Session Port Id S  Flags   Format Chn mask  SRate\n"
                        + "             59     no  14218     145      33 I  0x000 00000005 00000003  48000\n";
                List<SessionInfo> out = DumpsysParser.parse(legacy);
                Assert.isTrue(indexOf(out, 145) >= 0, "legacy track row still parsed");
            }
        });
        suite.test("dumpsys: old AOSP '- Output thread 0x...:' header is an output thread",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                String old = "- Output thread 0xf4483800:\n"
                        + "  - Standby: yes\n"
                        + "  1 Tracks of which 0 are active\n"
                        + "    Type     Id Active Client Session Port Id S  Flags   Format Chn mask  SRate\n"
                        + "             59     no  14218     145      33 I  0x000 00000005 00000003  48000\n";
                List<SessionInfo> out = DumpsysParser.parse(old);
                Assert.isTrue(indexOf(out, 145) >= 0, "old-style output thread parsed");
            }
        });
        suite.test("dumpsys: never throws, null/empty/garbage yield an empty list",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                Assert.isTrue(DumpsysParser.parse(null).isEmpty(), "null dump -> empty");
                Assert.isTrue(DumpsysParser.parse("").isEmpty(), "empty dump -> empty");
                List<SessionInfo> out = DumpsysParser.parse(
                        "Input thread 0xb4000079958b8fe8, name AudioIn_D, tid 5958, type 3 (RECORD):\n"
                        + "  1 Tracks of which 1 are active\n"
                        + "           518    yes    4818    8273       8 R  0x000\n");
                Assert.isTrue(out.isEmpty() || indexOf(out, 8273) < 0,
                        "a truncated input dump must not produce the record session");
            }
        });
    }
}
