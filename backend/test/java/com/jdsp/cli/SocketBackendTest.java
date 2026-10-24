package com.jdsp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;

/** Unix-socket transport: request framing, response parsing, error mapping. */
public final class SocketBackendTest {
    private SocketBackendTest() {}

    private static final Charset UTF8 = Charset.forName("UTF-8");

    static final class Exchange {
        final ByteArrayOutputStream requests = new ByteArrayOutputStream();
        final SocketBackend backend;

        Exchange(String responses) {
            byte[] bytes = (responses.isEmpty() ? "" : responses + "\n").getBytes(UTF8);
            backend = new SocketBackend(new ByteArrayInputStream(bytes), requests,
                    "/tmp/jdsp-test.sock");
        }

        String requests() {
            return new String(requests.toByteArray(), UTF8);
        }
    }

    public static void run(Suite suite) {
        suite.test("socket: ping ok", () -> {
            Exchange ex = new Exchange(
                    "{\"ok\":true,\"version\":\"jdspd 2.0.0\",\"pid\":1,\"uptimeMs\":2}");
            Assert.eq(0, ex.backend.ping());
            Assert.eq("{\"cmd\":\"ping\"}\n", ex.requests());
        });

        suite.test("socket: ping error returns the wire code", () -> {
            Exchange ex = new Exchange("{\"ok\":false,\"error\":\"bad\",\"code\":-1003}");
            Assert.eq(-1003, ex.backend.ping());
        });

        suite.test("socket: version", () -> {
            Exchange ex = new Exchange("{\"ok\":true,\"version\":\"jdspd 2.0.0\"}");
            Assert.eq("jdspd 2.0.0", ex.backend.getVersion());
            Assert.eq("{\"cmd\":\"version\"}\n", ex.requests());
        });

        suite.test("socket: sessions parsed into rows", () -> {
            String response = "{\"ok\":true,\"sessions\":[{"
                    + "\"sessionId\":161,\"uid\":10123,\"pkg\":\"com.google.uid.shared\","
                    + "\"attached\":true,\"enabled\":false,\"paramCount\":3,"
                    + "\"effectId\":107,\"policyMode\":0,\"pid\":17433,\"active\":true}]}";
            Exchange ex = new Exchange(response);
            List<SessionRow> rows = ex.backend.listSessions();
            Assert.eq(1, rows.size());
            SessionRow row = rows.get(0);
            Assert.eq(161, row.sessionId());
            Assert.eq(10123, row.uid());
            Assert.eq("com.google.uid.shared", row.pkg());
            Assert.eq(Boolean.TRUE, row.attached());
            Assert.eq(Boolean.FALSE, row.enabled());
            Assert.eq(3, row.paramCount());
            Assert.eq(107, row.effectId());
            Assert.eq(0, row.policyMode());
            Assert.eq(17433, row.pid());
            Assert.eq("{\"cmd\":\"sessions\"}\n", ex.requests());
        });

        suite.test("socket: empty sessions array", () -> {
            Exchange ex = new Exchange("{\"ok\":true,\"sessions\":[]}");
            Assert.eq(0, ex.backend.listSessions().size());
        });

        suite.test("socket: sessions error is exit 4", () -> {
            Exchange ex = new Exchange("{\"ok\":false,\"error\":\"internal\",\"code\":-1004}");
            CliException error = Assert.cliCode(Exit.REMOTE, () -> ex.backend.listSessions());
            Assert.contains(error.getMessage(), "internal");
            Assert.contains(error.getMessage(), "-1004");
        });

        suite.test("socket: getParam request and reply", () -> {
            Exchange ex = new Exchange("{\"ok\":true,\"value\":\"cdccccbd00007042\",\"vsize\":8}");
            byte[] value = ex.backend.getParam(161, 1500, 8);
            Assert.hex(value, "cdccccbd00007042");
            Assert.eq("{\"cmd\":\"getParam\",\"session\":161,\"param\":1500,\"vsize\":8}\n",
                    ex.requests());
        });

        suite.test("socket: getParam empty reply", () -> {
            Exchange ex = new Exchange("{\"ok\":true,\"value\":\"\",\"vsize\":4}");
            Assert.hex(ex.backend.getParam(161, 1500, 4), "");
        });

        suite.test("socket: getParam error is exit 4 with the daemon text", () -> {
            Exchange ex = new Exchange(
                    "{\"ok\":false,\"value\":\"\",\"error\":\"no handle\",\"code\":-1001}");
            CliException error = Assert.cliCode(Exit.REMOTE,
                    () -> ex.backend.getParam(161, 1500, 4));
            Assert.contains(error.getMessage(), "no handle");
            Assert.contains(error.getMessage(), "-1001");
        });

        suite.test("socket: getParam bad hex reply is exit 2", () -> {
            Exchange ex = new Exchange("{\"ok\":true,\"value\":\"zz\",\"vsize\":1}");
            Assert.cliCode(Exit.UNAVAILABLE, () -> ex.backend.getParam(161, 1500, 1));
        });

        suite.test("socket: setParam sends hex and returns status", () -> {
            Exchange ex = new Exchange("{\"ok\":true,\"status\":0}");
            byte[] value = ValueCodec.parseHex("cdccccbd", "test");
            Assert.eq(0, ex.backend.setParam(161, 1500, value));
            Assert.eq("{\"cmd\":\"setParam\",\"session\":161,\"param\":1500,"
                    + "\"value\":\"cdccccbd\"}\n", ex.requests());
        });

        suite.test("socket: setParam failure returns the status code", () -> {
            Exchange ex = new Exchange(
                    "{\"ok\":false,\"status\":-1001,\"error\":\"no handle\",\"code\":-1001}");
            Assert.eq(-1001, ex.backend.setParam(161, 1500, new byte[] {1}));
        });

        suite.test("socket: attach/detach/commit statuses", () -> {
            Exchange ex = new Exchange("{\"ok\":true,\"status\":0}\n"
                    + "{\"ok\":true,\"status\":0}\n"
                    + "{\"ok\":true,\"status\":0}");
            Assert.eq(0, ex.backend.attach(161));
            Assert.eq(0, ex.backend.detach(161));
            Assert.eq(0, ex.backend.commit(161));
            String[] lines = ex.requests().split("\n");
            Assert.eq("{\"cmd\":\"attach\",\"session\":161}", lines[0]);
            Assert.eq("{\"cmd\":\"detach\",\"session\":161}", lines[1]);
            Assert.eq("{\"cmd\":\"commit\",\"session\":161}", lines[2]);
        });

        suite.test("socket: mode get and set", () -> {
            Exchange get = new Exchange("{\"ok\":true,\"mode\":1,\"modeName\":\"backend\"}");
            Assert.eq(1, get.backend.getMode());
            Assert.eq("{\"cmd\":\"mode\"}\n", get.requests());
            Exchange set = new Exchange("{\"ok\":true,\"mode\":1}");
            Assert.eq(0, set.backend.setMode(1));
            Assert.eq("{\"cmd\":\"mode\",\"mode\":1}\n", set.requests());
        });

        suite.test("socket: policy get and set", () -> {
            Exchange get = new Exchange("{\"ok\":true,\"pkg\":\"com.foo\",\"mode\":2}");
            Assert.eq(2, get.backend.getAppPolicy("com.foo"));
            Assert.eq("{\"cmd\":\"policy\",\"pkg\":\"com.foo\"}\n", get.requests());
            Exchange set = new Exchange("{\"ok\":true,\"pkg\":\"com.foo\",\"mode\":2}");
            Assert.eq(0, set.backend.setAppPolicy("com.foo", 2));
            Assert.eq("{\"cmd\":\"policy\",\"pkg\":\"com.foo\",\"mode\":2}\n", set.requests());
        });

        suite.test("socket: policies JSON", () -> {
            Exchange ex = new Exchange(
                    "{\"ok\":true,\"policies\":{\"com.foo\":2},\"count\":1}");
            Assert.eq("{\"policies\":{\"com.foo\":2},\"count\":1}", ex.backend.listPolicies());
            Assert.eq("{\"cmd\":\"policies\"}\n", ex.requests());
        });

        suite.test("socket: dumpConfig returns the config object", () -> {
            Exchange ex = new Exchange(
                    "{\"ok\":true,\"config\":{\"version\":1,\"mode\":0}}");
            Assert.eq("{\"version\":1,\"mode\":0}", ex.backend.dumpConfig());
            Assert.eq("{\"cmd\":\"dumpConfig\"}\n", ex.requests());
        });

        suite.test("socket: dumpSession combines session and params", () -> {
            Exchange ex = new Exchange("{\"ok\":true,\"session\":{\"sessionId\":161},"
                    + "\"params\":{\"1500\":\"cdccccbd\"}}");
            Assert.eq("{\"session\":{\"sessionId\":161},\"params\":{\"1500\":\"cdccccbd\"}}",
                    ex.backend.dumpSession(161));
            Assert.eq("{\"cmd\":\"dumpSession\",\"session\":161}\n", ex.requests());
        });

        suite.test("socket: stats returns the stats object", () -> {
            Exchange ex = new Exchange("{\"ok\":true,\"stats\":{\"uptimeMs\":5}}");
            Assert.eq("{\"uptimeMs\":5}", ex.backend.stats());
        });

        suite.test("socket: getSession unknown returns null", () -> {
            Exchange ex = new Exchange("{\"ok\":false,\"error\":\"unknown session\",\"code\":-1002}");
            Assert.isTrue(ex.backend.getSession(99) == null, "expected null for unknown session");
        });

        suite.test("socket: malformed reply is exit 2", () -> {
            Exchange ex = new Exchange("not json");
            Assert.cliCode(Exit.UNAVAILABLE, () -> ex.backend.getVersion());
        });

        suite.test("socket: closed connection is exit 2", () -> {
            Exchange ex = new Exchange("");
            CliException error = Assert.cliCode(Exit.UNAVAILABLE, () -> ex.backend.getVersion());
            Assert.contains(error.getMessage(), "closed the connection");
        });

        suite.test("socket: io error is exit 2 with the socket path", () -> {
            SocketBackend backend = new SocketBackend(new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("Connection refused");
                }
            }, new ByteArrayOutputStream(), "/tmp/jdsp-test.sock");
            CliException error = Assert.cliCode(Exit.UNAVAILABLE, () -> backend.getVersion());
            Assert.contains(error.getMessage(), "/tmp/jdsp-test.sock");
            Assert.contains(error.getMessage(), "Connection refused");
        });

        suite.test("socket: escaped pkg is decoded", () -> {
            Exchange ex = new Exchange("{\"ok\":true,\"sessions\":[{"
                    + "\"sessionId\":1,\"uid\":2,\"pkg\":\"a\\\"b\\\\c\","
                    + "\"attached\":false,\"enabled\":false,\"paramCount\":0,"
                    + "\"effectId\":-1,\"policyMode\":0,\"pid\":3}]}");
            List<SessionRow> rows = ex.backend.listSessions();
            Assert.eq("a\"b\\c", rows.get(0).pkg());
        });
    }
}
