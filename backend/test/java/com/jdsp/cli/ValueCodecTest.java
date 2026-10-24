package com.jdsp.cli;

/** Value encoding: float32/int32/int16 LE, hex/raw pass-through and errors. */
public final class ValueCodecTest {
    private ValueCodecTest() {}

    private static byte[] encode(String... argv) throws Exception {
        ParsedCommand cmd = CommandParser.parse(argv);
        return ValueCodec.encode(cmd, 2);
    }

    public static void run(Suite suite) {
        suite.test("codec: float 0.5", () -> Assert.hex(encode("set", "1", "7", "0.5"), "0000003f"));
        suite.test("codec: float 1.0", () -> Assert.hex(encode("set", "1", "7", "1.0"), "0000803f"));
        suite.test("codec: float -1.0", () -> Assert.hex(encode("set", "1", "7", "-1.0"), "000080bf"));
        suite.test("codec: float -0.1", () -> Assert.hex(encode("set", "1", "7", "-0.1"), "cdccccbd"));
        suite.test("codec: float 60", () -> Assert.hex(encode("set", "1", "7", "60"), "00007042"));
        suite.test("codec: float 3.0", () -> Assert.hex(encode("set", "1", "7", "3.0"), "00004040"));
        suite.test("codec: float 1e-3", () ->
                Assert.hex(encode("set", "1", "7", "0.001"), "6f12833a"));

        suite.test("codec: three floats (limiter parameter)", () -> Assert.hex(
                encode("set", "161", "1500", "-0.1", "60", "3.0"),
                "cdccccbd0000704200004040"));

        suite.test("codec: int32 LE", () -> {
            Assert.hex(encode("set", "1", "7", "--int", "1"), "01000000");
            Assert.hex(encode("set", "1", "7", "--int", "-1"), "ffffffff");
            Assert.hex(encode("set", "1", "7", "--int", "2147483647"), "ffffff7f");
        });

        suite.test("codec: int option plus extra positionals", () ->
                Assert.hex(encode("set", "1", "7", "--int", "5", "6"), "0500000006000000"));

        suite.test("codec: int16 LE", () -> {
            Assert.hex(encode("set", "1", "7", "--short", "1"), "0100");
            Assert.hex(encode("set", "1", "7", "--short", "-2"), "feff");
            Assert.hex(encode("set", "1", "7", "--short", "32767"), "ff7f");
        });

        suite.test("codec: hex case and separators", () -> {
            Assert.hex(encode("set", "1", "7", "--hex", "0000803F"), "0000803f");
            Assert.hex(encode("set", "1", "7", "--hex", "00:00:80:3f"), "0000803f");
            Assert.hex(encode("set", "1", "7", "--hex", "0x00 0x00 0x80 0x3f"), "0000803f");
            Assert.hex(encode("set", "1", "7", "--hex", "00_00_80_3f"), "0000803f");
        });

        suite.test("codec: raw hex chunks", () -> {
            Assert.hex(encode("set", "1", "7", "--raw", "dead", "beef"), "deadbeef");
            Assert.hex(encode("set", "1", "7", "--raw", "0xDEADBEEF"), "deadbeef");
        });

        suite.test("codec: odd hex digits rejected", () ->
                Assert.cliCode(Exit.USAGE, () -> encode("set", "1", "7", "--hex", "abc")));

        suite.test("codec: non-hex character rejected", () ->
                Assert.cliCode(Exit.USAGE, () -> encode("set", "1", "7", "--hex", "00zz")));

        suite.test("codec: empty hex rejected", () ->
                Assert.cliCode(Exit.USAGE, () -> encode("set", "1", "7", "--hex", "")));

        suite.test("codec: hex plus positional value rejected", () ->
                Assert.cliCode(Exit.USAGE, () ->
                        encode("set", "1", "7", "--hex", "00", "11")));

        suite.test("codec: mutually exclusive value options", () ->
                Assert.cliCode(Exit.USAGE, () ->
                        encode("set", "1", "7", "--hex", "00", "--int", "1")));

        suite.test("codec: set without values rejected", () ->
                Assert.cliCode(Exit.USAGE, () -> encode("set", "1", "7")));

        suite.test("codec: non numeric value rejected", () ->
                Assert.cliCode(Exit.USAGE, () -> encode("set", "1", "7", "abc")));

        suite.test("codec: NaN/Infinity rejected", () -> {
            Assert.cliCode(Exit.USAGE, () -> encode("set", "1", "7", "NaN"));
            Assert.cliCode(Exit.USAGE, () -> encode("set", "1", "7", "Infinity"));
        });

        suite.test("codec: int32 overflow rejected", () ->
                Assert.cliCode(Exit.USAGE, () ->
                        encode("set", "1", "7", "--int", "2147483648")));

        suite.test("codec: int16 overflow rejected", () -> {
            Assert.cliCode(Exit.USAGE, () -> encode("set", "1", "7", "--short", "32768"));
            Assert.cliCode(Exit.USAGE, () -> encode("set", "1", "7", "--short", "-32769"));
        });

        suite.test("codec: toHex formatting", () -> {
            Assert.eq("", ValueCodec.toHex(null));
            Assert.eq("", ValueCodec.toHex(new byte[0]));
            Assert.eq("00ff80", ValueCodec.toHex(new byte[]{0x00, (byte) 0xff, (byte) 0x80}));
        });
    }
}
