package com.jdsp.cli;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loopback TCP transport: token handshake, endpoint files, request framing,
 * response parsing and error mapping (protocol tests use in-memory streams).
 */
public final class TcpBackendTest {
    private TcpBackendTest() {}

    private static final Charset UTF8 = Charset.forName("UTF-8");

    // ------------------------------------------------------------------
    // in-memory protocol tests (no handshake)
    // ------------------------------------------------------------------

    static final class Exchange {
        final ByteArrayOutputStream requests = new ByteArrayOutputStream();
        final TcpBackend backend;

        Exchange(String responses) {
            byte[] bytes = (responses.isEmpty() ? "" : responses + "\n").getBytes(UTF8);
            backend = new TcpBackend(new ByteArrayInputStream(bytes), requests, "test");
        }

        String requests() {
            return new String(requests.toByteArray(), UTF8);
        }
    }

    // ------------------------------------------------------------------
    // real loopback TCP tests
    // ------------------------------------------------------------------

    static final class FakeServer implements Closeable {
        final ServerSocket server;
        final String helloReply;
        final List<String> replies;
        final List<String> captured = Collections.synchronizedList(new ArrayList<String>());
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        private final Thread thread;
        private volatile Socket client;

        FakeServer(String helloReply, String... replies) throws IOException {
            this.server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            this.helloReply = helloReply;
            this.replies = Arrays.asList(replies);
            this.thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    serve();
                }
            });
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            try {
                client = server.accept();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(client.getInputStream(), UTF8));
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(client.getOutputStream(), UTF8));
                String line = reader.readLine();
                if (line == null) {
                    return;
                }
                captured.add(line);
                writeLine(writer, helloReply);
                int index = 0;
                String request;
                while ((request = reader.readLine()) != null) {
                    captured.add(request);
                    String reply = replies.get(Math.min(index, replies.size() - 1));
                    index++;
                    writeLine(writer, reply);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        }

        private static void writeLine(BufferedWriter writer, String line) throws IOException {
            writer.write(line);
            writer.write("\n");
            writer.flush();
        }

        List<String> captured() {
            return new ArrayList<String>(captured);
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (IOException ignored) {
                // best effort
            }
            Socket socket = client;
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static final class EndpointFiles {
        final Path dir;
        final String portFile;
        final String tokenFile;

        EndpointFiles(int port, String token) throws IOException {
            dir = Files.createTempDirectory("jdsp-tcp-test");
            portFile = dir.resolve("jdsp.port").toString();
            tokenFile = dir.resolve("jdsp.token").toString();
            Files.write(dir.resolve("jdsp.port"), (port + "\n").getBytes(UTF8));
            Files.write(dir.resolve("jdsp.token"), (token + "\n").getBytes(UTF8));
        }
    }

    public static void run(Suite suite) {
        // ---- protocol (in-memory streams) --------------------------------
        suite.test("tcp: ping ok", () -> {
            Exchange ex = new Exchange(
                    "{\"ok\":true,\"version\":\"jdspd 2.0.0\",\"pid\":1,\"uptimeMs\":2}");
            Assert.eq(0, ex.backend.ping());
            Assert.eq("{\"cmd\":\"ping\"}\n", ex.requests());
        });

        suite.test("tcp: ping error returns the wire code", () -> {
            Exchange ex = new Exchange("{\"ok\":false,\"error\":\"bad\",\"code\":-1003}");
            Assert.eq(-1003, ex.backend.ping());
        });

        suite.test("tcp: version", () -> {
            Exchange ex = new Exchange("{\"ok\":true,\"version\":\"jdspd 2.0.0\"}");
            Assert.eq("jdspd 2.0.0", ex.backend.getVersion());
            Assert.eq("{\"cmd\":\"version\"}\n", ex.requests());
        });

        suite.test("tcp: sessions parsed into rows", () -> {
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

        suite.test("tcp: empty sessions array", () -> {
            Exchange ex = new Exchange("{\"ok\":true,\"sessions\":[]}");
            Assert.eq(0, ex.backend.listSessions().size());
        });

        suite.test("tcp: sessions error is exit 4", () -> {
            Exchange ex = new Exchange("{\"ok\":false,\"error\":\"internal\",\"code\":-1004}");
            CliException error = Assert.cliCode(Exit.REMOTE, () -> ex.backend.listSessions());
            Assert.contains(error.getMessage(), "internal");
            Assert.contains(error.getMessage(), "-1004");
        });

        suite.test("tcp: getParam request and reply", () -> {
            Exchange ex = new Exchange("{\"ok\":true,\"value\":\"cdccccbd00007042\",\"vsize\":8}");
            byte[] value = ex.backend.getParam(161, 1500, 8);
            Assert.hex(value, "cdccccbd00007042");
            Assert.eq("{\"cmd\":\"getParam\",\"session\":161,\"param\":1500,\"vsize\":8}\n",
                    ex.requests());
        });

        suite.test("tcp: getParam empty reply", () -> {
            Exchange ex = new Exchange("{\"ok\":true,\"value\":\"\",\"vsize\":4}");
            Assert.hex(ex.backend.getParam(161, 1500, 4), "");
        });

        suite.test("tcp: getParam error is exit 4 with the daemon text", () -> {
            Exchange ex = new Exchange(
                    "{\"ok\":false,\"value\":\"\",\"error\":\"no handle\",\"code\":-1001}");
            CliException error = Assert.cliCode(Exit.REMOTE,
                    () -> ex.backend.getParam(161, 1500, 4));
            Assert.contains(error.getMessage(), "no handle");
            Assert.contains(error.getMessage(), "-1001");
        });

        suite.test("tcp: getParam bad hex reply is exit 2", () -> {
            Exchange ex = new Exchange("{\"ok\":true,\"value\":\"zz\",\"vsize\":1}");
            Assert.cliCode(Exit.UNAVAILABLE, () -> ex.backend.getParam(161, 1500, 1));
        });

        suite.test("tcp: setParam sends hex and returns status", () -> {
            Exchange ex = new Exchange("{\"ok\":true,\"status\":0}");
            byte[] value = ValueCodec.parseHex("cdccccbd", "test");
            Assert.eq(0, ex.backend.setParam(161, 1500, value));
            Assert.eq("{\"cmd\":\"setParam\",\"session\":161,\"param\":1500,"
                    + "\"value\":\"cdccccbd\"}\n", ex.requests());
        });

        suite.test("tcp: setParam failure returns the status code", () -> {
            Exchange ex = new Exchange(
                    "{\"ok\":false,\"status\":-1001,\"error\":\"no handle\",\"code\":-1001}");
            Assert.eq(-1001, ex.backend.setParam(161, 1500, new byte[] {1}));
        });

        suite.test("tcp: attach/detach/commit statuses", () -> {
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

        suite.test("tcp: mode get and set", () -> {
            Exchange get = new Exchange("{\"ok\":true,\"mode\":1,\"modeName\":\"backend\"}");
            Assert.eq(1, get.backend.getMode());
            Assert.eq("{\"cmd\":\"mode\"}\n", get.requests());
            Exchange set = new Exchange("{\"ok\":true,\"mode\":1}");
            Assert.eq(0, set.backend.setMode(1));
            Assert.eq("{\"cmd\":\"mode\",\"mode\":1}\n", set.requests());
        });

        suite.test("tcp: policy get and set", () -> {
            Exchange get = new Exchange("{\"ok\":true,\"pkg\":\"com.foo\",\"mode\":2}");
            Assert.eq(2, get.backend.getAppPolicy("com.foo"));
            Assert.eq("{\"cmd\":\"policy\",\"pkg\":\"com.foo\"}\n", get.requests());
            Exchange set = new Exchange("{\"ok\":true,\"pkg\":\"com.foo\",\"mode\":2}");
            Assert.eq(0, set.backend.setAppPolicy("com.foo", 2));
            Assert.eq("{\"cmd\":\"policy\",\"pkg\":\"com.foo\",\"mode\":2}\n", set.requests());
        });

        suite.test("tcp: policies JSON", () -> {
            Exchange ex = new Exchange(
                    "{\"ok\":true,\"policies\":{\"com.foo\":2},\"count\":1}");
            Assert.eq("{\"policies\":{\"com.foo\":2},\"count\":1}", ex.backend.listPolicies());
            Assert.eq("{\"cmd\":\"policies\"}\n", ex.requests());
        });

        suite.test("tcp: dumpConfig returns the config object", () -> {
            Exchange ex = new Exchange(
                    "{\"ok\":true,\"config\":{\"version\":1,\"mode\":0}}");
            Assert.eq("{\"version\":1,\"mode\":0}", ex.backend.dumpConfig());
            Assert.eq("{\"cmd\":\"dumpConfig\"}\n", ex.requests());
        });

        suite.test("tcp: dumpSession combines session and params", () -> {
            Exchange ex = new Exchange("{\"ok\":true,\"session\":{\"sessionId\":161},"
                    + "\"params\":{\"1500\":\"cdccccbd\"}}");
            Assert.eq("{\"session\":{\"sessionId\":161},\"params\":{\"1500\":\"cdccccbd\"}}",
                    ex.backend.dumpSession(161));
            Assert.eq("{\"cmd\":\"dumpSession\",\"session\":161}\n", ex.requests());
        });

        suite.test("tcp: stats returns the stats object", () -> {
            Exchange ex = new Exchange("{\"ok\":true,\"stats\":{\"uptimeMs\":5}}");
            Assert.eq("{\"uptimeMs\":5}", ex.backend.stats());
        });

        suite.test("tcp: getSession unknown returns null", () -> {
            Exchange ex = new Exchange("{\"ok\":false,\"error\":\"unknown session\",\"code\":-1002}");
            Assert.isTrue(ex.backend.getSession(99) == null, "expected null for unknown session");
        });

        suite.test("tcp: malformed reply is exit 2", () -> {
            Exchange ex = new Exchange("not json");
            Assert.cliCode(Exit.UNAVAILABLE, () -> ex.backend.getVersion());
        });

        suite.test("tcp: closed connection is exit 2", () -> {
            Exchange ex = new Exchange("");
            CliException error = Assert.cliCode(Exit.UNAVAILABLE, () -> ex.backend.getVersion());
            Assert.contains(error.getMessage(), "closed the connection");
        });

        suite.test("tcp: io error is exit 2 with the endpoint label", () -> {
            TcpBackend backend = new TcpBackend(new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("Connection reset");
                }
            }, new ByteArrayOutputStream(), "test");
            CliException error = Assert.cliCode(Exit.UNAVAILABLE, () -> backend.getVersion());
            Assert.contains(error.getMessage(), "test");
            Assert.contains(error.getMessage(), "Connection reset");
        });

        suite.test("tcp: escaped pkg is decoded", () -> {
            Exchange ex = new Exchange("{\"ok\":true,\"sessions\":[{"
                    + "\"sessionId\":1,\"uid\":2,\"pkg\":\"a\\\"b\\\\c\","
                    + "\"attached\":false,\"enabled\":false,\"paramCount\":0,"
                    + "\"effectId\":-1,\"policyMode\":0,\"pid\":3}]}");
            List<SessionRow> rows = ex.backend.listSessions();
            Assert.eq("a\"b\\c", rows.get(0).pkg());
        });

        // ---- loopback TCP handshake and endpoint files -------------------
        suite.test("tcp: connects, handshakes and serves commands", () -> {
            FakeServer server = new FakeServer("{\"ok\":true,\"op\":\"hello\",\"version\":\"jdspd 2.0.0\"}",
                    "{\"ok\":true,\"version\":\"jdspd 2.0.0\",\"pid\":1,\"uptimeMs\":2}");
            try {
                EndpointFiles files = new EndpointFiles(server.port(), "DEADBEEF");
                TcpBackend backend = TcpBackend.open(null, files.portFile, files.tokenFile);
                Assert.eq(0, backend.ping());
                List<String> captured = server.captured();
                Assert.eq("{\"op\":\"hello\",\"token\":\"deadbeef\"}", captured.get(0));
                Assert.eq("{\"cmd\":\"ping\"}", captured.get(1));
                Assert.isTrue(server.failure.get() == null,
                        "server failure: " + server.failure.get());
            } finally {
                server.close();
            }
        });

        suite.test("tcp: bad token maps to 'tcp hello rejected (bad token)'", () -> {
            FakeServer server = new FakeServer(
                    "{\"ok\":false,\"error\":\"bad token\",\"code\":-1003}");
            try {
                EndpointFiles files = new EndpointFiles(server.port(), "deadbeef");
                CliException error = Assert.cliCode(Exit.UNAVAILABLE,
                        () -> TcpBackend.open(null, files.portFile, files.tokenFile));
                Assert.contains(error.getMessage(), "tcp hello rejected (bad token)");
            } finally {
                server.close();
            }
        });

        suite.test("tcp: hello rejected for a non-token error", () -> {
            FakeServer server = new FakeServer(
                    "{\"ok\":false,\"error\":\"bad hello json\",\"code\":-1003}");
            try {
                EndpointFiles files = new EndpointFiles(server.port(), "deadbeef");
                CliException error = Assert.cliCode(Exit.UNAVAILABLE,
                        () -> TcpBackend.open(null, files.portFile, files.tokenFile));
                Assert.contains(error.getMessage(), "tcp hello rejected");
                Assert.contains(error.getMessage(), "bad hello json");
            } finally {
                server.close();
            }
        });

        suite.test("tcp: missing endpoint files", () -> {
            CliException error = Assert.cliCode(Exit.UNAVAILABLE,
                    () -> TcpBackend.open(null, "/nonexistent/jdsp.port",
                            "/nonexistent/jdsp.token"));
            Assert.contains(error.getMessage(), "tcp endpoint files missing");
            Assert.contains(error.getMessage(), "jdsp.port");
            Assert.contains(error.getMessage(), "jdsp.token");
        });

        suite.test("tcp: bad port file", () -> {
            Path dir = Files.createTempDirectory("jdsp-tcp-badport");
            Path portFile = dir.resolve("jdsp.port");
            Files.write(portFile, "abc\n".getBytes(UTF8));
            Path tokenFile = dir.resolve("jdsp.token");
            Files.write(tokenFile, "deadbeef\n".getBytes(UTF8));
            CliException error = Assert.cliCode(Exit.UNAVAILABLE,
                    () -> TcpBackend.open(null, portFile.toString(), tokenFile.toString()));
            Assert.contains(error.getMessage(), "bad tcp port");
        });

        suite.test("tcp: connection refused is exit 2", () -> {
            int port;
            ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            port = probe.getLocalPort();
            probe.close();
            EndpointFiles files = new EndpointFiles(port, "deadbeef");
            CliException error = Assert.cliCode(Exit.UNAVAILABLE,
                    () -> TcpBackend.open(null, files.portFile, files.tokenFile));
            Assert.contains(error.getMessage(), "tcp endpoint unreachable");
            Assert.contains(error.getMessage(), Integer.toString(port));
        });

        suite.test("tcp: --tcp host:port endpoint overrides the port file", () -> {
            FakeServer server = new FakeServer("{\"ok\":true,\"op\":\"hello\"}",
                    "{\"ok\":true,\"version\":\"jdspd 2.0.0\"}");
            try {
                EndpointFiles files = new EndpointFiles(1, "deadbeef");
                TcpBackend backend = TcpBackend.open("127.0.0.1:" + server.port(),
                        "/nonexistent/jdsp.port", files.tokenFile);
                Assert.eq("jdspd 2.0.0", backend.getVersion());
                List<String> captured = server.captured();
                Assert.eq("{\"op\":\"hello\",\"token\":\"deadbeef\"}", captured.get(0));
            } finally {
                server.close();
            }
        });

        suite.test("tcp: invalid --tcp endpoint is a usage error", () -> {
            Assert.cliCode(Exit.USAGE, () -> TcpBackend.parseEndpoint("abc"));
            Assert.cliCode(Exit.USAGE, () -> TcpBackend.parseEndpoint("127.0.0.1:0"));
            Assert.cliCode(Exit.USAGE, () -> TcpBackend.parseEndpoint("127.0.0.1:70000"));
        });
    }
}
