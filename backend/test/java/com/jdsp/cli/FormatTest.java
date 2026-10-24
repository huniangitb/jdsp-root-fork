package com.jdsp.cli;

import java.util.ArrayList;
import java.util.List;

/** Table/JSON rendering and JSON escaping. */
public final class FormatTest {
    private FormatTest() {}

    private static SessionRow row() {
        return new SessionRow(161, 10123, "com.google.uid.shared", true, false, 12, 35, 2, 4242);
    }

    public static void run(Suite suite) {
        suite.test("format: policy names", () -> {
            Assert.eq("follow", Format.policyName(0));
            Assert.eq("on", Format.policyName(1));
            Assert.eq("exclude", Format.policyName(2));
            Assert.eq("unknown(9)", Format.policyName(9));
        });

        suite.test("format: mode names", () -> {
            Assert.eq("auto", Format.modeName(0));
            Assert.eq("backend", Format.modeName(1));
            Assert.eq("unknown(7)", Format.modeName(7));
        });

        suite.test("format: empty session table is just the header", () -> Assert.eq(
                "session uid pkg attached enabled params effectId policy\n",
                Format.sessionsTable(new ArrayList<SessionRow>())));

        suite.test("format: session table row columns", () -> {
            List<SessionRow> rows = new ArrayList<SessionRow>();
            rows.add(row());
            String table = Format.sessionsTable(rows);
            String[] lines = table.split("\n");
            Assert.eq(2, lines.length);
            String[] columns = lines[1].trim().split(" +");
            Assert.eq(8, columns.length);
            Assert.eq("161", columns[0]);
            Assert.eq("10123", columns[1]);
            Assert.eq("com.google.uid.shared", columns[2]);
            Assert.eq("1", columns[3]);
            Assert.eq("0", columns[4]);
            Assert.eq("12", columns[5]);
            Assert.eq("35", columns[6]);
            Assert.eq("exclude", columns[7]);
        });

        suite.test("format: session table null pkg", () -> {
            List<SessionRow> rows = new ArrayList<SessionRow>();
            rows.add(new SessionRow(1, 0, null, false, false, 0, -1, 0, 0));
            String table = Format.sessionsTable(rows);
            String[] lines = table.split("\n");
            Assert.eq(2, lines.length);
            Assert.isTrue(lines[1].startsWith("1"),
                    "row must start with the session id: " + lines[1]);
            Assert.contains(lines[1], "follow");
            Assert.contains(lines[1], "-1");
        });

        suite.test("format: sessions json exact", () -> {
            List<SessionRow> rows = new ArrayList<SessionRow>();
            rows.add(row());
            String expected = "[{\"session\":161,\"uid\":10123,"
                    + "\"pkg\":\"com.google.uid.shared\",\"attached\":true,"
                    + "\"enabled\":false,\"params\":12,\"effectId\":35,"
                    + "\"policy\":\"exclude\",\"policyMode\":2}]";
            Assert.eq(expected, Format.sessionsJson(rows));
        });

        suite.test("format: empty and null session json", () -> {
            Assert.eq("[]", Format.sessionsJson(new ArrayList<SessionRow>()));
            Assert.eq("[]", Format.sessionsJson(null));
        });

        suite.test("format: policy json", () -> Assert.eq(
                "{\"pkg\":\"com.example.music\",\"policy\":\"follow\","
                        + "\"policyMode\":0}",
                Format.policyJson("com.example.music", 0)));

        suite.test("format: json escaping", () -> {
            Assert.eq("\"a\\\"b\\\\c\\n\\t\"",
                    Format.jsonString("a\"b\\c\n\t"));
            Assert.eq("\"\\u0001\"", Format.jsonString("\u0001"));
            Assert.eq("null", Format.jsonString(null));
        });

        suite.test("format: session json with null pkg", () -> {
            String json = Format.sessionJson(new SessionRow(1, 2, null, false, true, 0, -1, 0, 0));
            Assert.contains(json, "\"pkg\":\"\"");
            Assert.contains(json, "\"effectId\":-1");
        });
    }
}
