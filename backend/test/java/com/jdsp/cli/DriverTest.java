package com.jdsp.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/** Command dispatch against a fake backend: output formats and exit-code mapping. */
public final class DriverTest {
    private DriverTest() {}

    // ------------------------------------------------------------------
    // fake backend
    // ------------------------------------------------------------------

    static final class FakeBackend implements Backend {
        final List<SessionRow> sessions = new ArrayList<SessionRow>();
        String version = "jdspd 2.0.0-test";
        String config = "{\"mode\":\"auto\"}";
        String sessionDump = "{\"session\":7,\"enabled\":true}";
        String statsText = "{\"uptimeMs\":1}";
        String policies = "{\"policies\":{\"com.x\":2},\"count\":1}";

        int pingStatus;
        int attachStatus;
        int detachStatus;
        int setStatus;
        int commitStatus;
        int getModeValue;
        int setModeStatus;
        boolean enabledValue = true;
        int setEnabledStatus;
        int getPolicyValue;
        int setPolicyStatus;
        byte[] getReply;

        int lastSession = -1;
        int lastParam = -1;
        int lastVsize = -1;
        int lastMode = -1;
        int lastPolicyMode = -1;
        String lastPkg;
        byte[] lastValue;
        int setParamCalls;
        int getParamCalls;

        @Override
        public String getVersion() {
            return version;
        }

        @Override
        public int ping() {
            return pingStatus;
        }

        @Override
        public List<SessionRow> listSessions() {
            return sessions;
        }

        @Override
        public SessionRow getSession(int sessionId) {
            for (SessionRow row : sessions) {
                if (row.sessionId() == sessionId) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public int attach(int sessionId) {
            lastSession = sessionId;
            return attachStatus;
        }

        @Override
        public int detach(int sessionId) {
            lastSession = sessionId;
            return detachStatus;
        }

        @Override
        public int setParam(int sessionId, int paramId, byte[] value) {
            lastSession = sessionId;
            lastParam = paramId;
            lastValue = value;
            setParamCalls++;
            return setStatus;
        }

        @Override
        public byte[] getParam(int sessionId, int paramId, int vsize) {
            lastSession = sessionId;
            lastParam = paramId;
            lastVsize = vsize;
            getParamCalls++;
            if (getReply != null) {
                return getReply;
            }
            return lastValue;
        }

        @Override
        public int commit(int sessionId) {
            lastSession = sessionId;
            return commitStatus;
        }

        @Override
        public String dumpConfig() {
            return config;
        }

        @Override
        public String dumpSession(int sessionId) {
            lastSession = sessionId;
            return sessionDump;
        }

        @Override
        public int loadPreset(String path) {
            return 0;
        }

        @Override
        public int savePreset(String path) {
            return 0;
        }

        @Override
        public int setAppPolicy(String pkg, int mode) {
            lastPkg = pkg;
            lastPolicyMode = mode;
            return setPolicyStatus;
        }

        @Override
        public int getAppPolicy(String pkg) {
            lastPkg = pkg;
            return getPolicyValue;
        }

        @Override
        public String listPolicies() {
            return policies;
        }

        @Override
        public int setMode(int mode) {
            lastMode = mode;
            return setModeStatus;
        }

        @Override
        public int getMode() {
            return getModeValue;
        }

        @Override
        public int setEnabled(boolean enabled) {
            enabledValue = enabled;
            return setEnabledStatus;
        }

        @Override
        public boolean getEnabled() {
            return enabledValue;
        }

        @Override
        public String stats() {
            return statsText;
        }
    }

    // ------------------------------------------------------------------
    // helpers (shared with TransportTest)
    // ------------------------------------------------------------------

    static final class Result {
        final int code;
        final String out;
        final String err;

        Result(int code, String out, String err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }
    }

    static Result run(TransportFactory transports, String... argv) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true);
        PrintStream err = new PrintStream(errBytes, true);
        int code = CliDriver.execute(argv, transports, out, err);
        out.flush();
        err.flush();
        return new Result(code, new String(outBytes.toByteArray()),
                new String(errBytes.toByteArray()));
    }

    static TransportFactory of(final Backend backend) {
        return new TransportFactory() {
            @Override
            public Backend open(String tcpEndpoint, boolean forceTcp) {
                return backend;
            }
        };
    }

    static TransportFactory failing(final int code, final String message) {
        return new TransportFactory() {
            @Override
            public Backend open(String tcpEndpoint, boolean forceTcp) throws CliException {
                throw new CliException(code, message);
            }
        };
    }

    static SessionRow sampleRow() {
        return new SessionRow(161, 10123, "com.google.uid.shared", true, true, 4, 35, 0, 4242);
    }

    // ------------------------------------------------------------------
    // tests
    // ------------------------------------------------------------------

    public static void run(Suite suite) {
        suite.test("driver: version plain", () -> {
            Result r = run(of(new FakeBackend()), "version");
            Assert.eq(Exit.OK, r.code);
            Assert.eq("jdspd 2.0.0-test\n", r.out);
            Assert.eq("", r.err);
        });

        suite.test("driver: version json", () -> {
            Result r = run(of(new FakeBackend()), "--json", "version");
            Assert.eq(Exit.OK, r.code);
            Assert.contains(r.out, "\"version\":\"jdspd 2.0.0-test\"");
        });

        suite.test("driver: ping ok", () -> {
            Result r = run(of(new FakeBackend()), "ping");
            Assert.eq(Exit.OK, r.code);
            Assert.eq("ok\n", r.out);
        });

        suite.test("driver: ping positive status proves liveness", () -> {
            FakeBackend backend = new FakeBackend();
            backend.pingStatus = 1; // jdspd TcpBackendTest: PING_OK = 1
            Result r = run(of(backend), "ping", "--json");
            Assert.eq(Exit.OK, r.code);
            Assert.contains(r.out, "\"status\":1");
        });

        suite.test("driver: ping negative status exits 4", () -> {
            FakeBackend backend = new FakeBackend();
            backend.pingStatus = -1004;
            Result r = run(of(backend), "ping");
            Assert.eq(Exit.REMOTE, r.code);
            Assert.contains(r.err, "remote status -1004");
        });

        suite.test("driver: sessions table", () -> {
            FakeBackend backend = new FakeBackend();
            backend.sessions.add(sampleRow());
            Result r = run(of(backend), "sessions");
            Assert.eq(Exit.OK, r.code);
            String header = r.out.split("\n")[0];
            Assert.contains(header, "session");
            Assert.contains(header, "pkg");
            Assert.contains(header, "effectId");
            Assert.contains(r.out, "com.google.uid.shared");
            Assert.contains(r.out, "follow");
        });

        suite.test("driver: sessions json", () -> {
            FakeBackend backend = new FakeBackend();
            backend.sessions.add(sampleRow());
            Result r = run(of(backend), "sessions", "--json");
            Assert.eq(Exit.OK, r.code);
            Assert.contains(r.out, "\"session\":161");
            Assert.contains(r.out, "\"attached\":true");
            Assert.contains(r.out, "\"params\":4");
        });

        suite.test("driver: two sessions in json", () -> {
            FakeBackend backend = new FakeBackend();
            backend.sessions.add(sampleRow());
            backend.sessions.add(new SessionRow(162, 2000, "", false, false, 0, -1, 2, 1));
            Result r = run(of(backend), "sessions", "--json");
            Assert.contains(r.out, "\"session\":162");
            Assert.contains(r.out, "\"policy\":\"exclude\"");
        });

        suite.test("driver: get default vsize 4 and hex output", () -> {
            FakeBackend backend = new FakeBackend();
            backend.getReply = new byte[] {0x00, 0x00, (byte) 0x80, 0x3f};
            Result r = run(of(backend), "get", "161", "1500");
            Assert.eq(Exit.OK, r.code);
            Assert.eq(4, backend.lastVsize);
            Assert.eq("0000803f\n", r.out);
        });

        suite.test("driver: get explicit vsize 12", () -> {
            FakeBackend backend = new FakeBackend();
            backend.getReply = new byte[12];
            Result r = run(of(backend), "get", "161", "1500", "12", "--json");
            Assert.eq(Exit.OK, r.code);
            Assert.eq(12, backend.lastVsize);
            Assert.contains(r.out, "\"vsize\":12");
            Assert.contains(r.out, "\"len\":12");
        });

        suite.test("driver: get empty reply exits 4", () -> {
            FakeBackend backend = new FakeBackend();
            backend.getReply = new byte[0];
            Result r = run(of(backend), "get", "161", "1500");
            Assert.eq(Exit.REMOTE, r.code);
            Assert.contains(r.err, "returned no data");
        });

        suite.test("driver: set encodes float32 and reports bytes", () -> {
            FakeBackend backend = new FakeBackend();
            Result r = run(of(backend), "set", "161", "1500", "-0.1", "60", "3.0");
            Assert.eq(Exit.OK, r.code);
            Assert.hex(backend.lastValue, "cdccccbd0000704200004040");
            Assert.eq(161, backend.lastSession);
            Assert.eq(1500, backend.lastParam);
            Assert.contains(r.out, "len=12");
            Assert.contains(r.out, "cdccccbd0000704200004040");
        });

        suite.test("driver: set remote failure exits 4", () -> {
            FakeBackend backend = new FakeBackend();
            backend.setStatus = -1001;
            Result r = run(of(backend), "set", "161", "1500", "1.0");
            Assert.eq(Exit.REMOTE, r.code);
            Assert.contains(r.err, "remote status -1001");
        });

        suite.test("driver: set json", () -> {
            FakeBackend backend = new FakeBackend();
            Result r = run(of(backend), "set", "161", "1500", "--int", "5", "--json");
            Assert.eq(Exit.OK, r.code);
            Assert.hex(backend.lastValue, "05000000");
            Assert.contains(r.out, "\"ok\":true");
            Assert.contains(r.out, "\"hex\":\"05000000\"");
        });

        suite.test("driver: attach and detach", () -> {
            FakeBackend backend = new FakeBackend();
            Result attached = run(of(backend), "attach", "161");
            Assert.eq(Exit.OK, attached.code);
            Assert.eq(161, backend.lastSession);
            Result detached = run(of(backend), "detach", "161", "--json");
            Assert.eq(Exit.OK, detached.code);
            Assert.contains(detached.out, "\"session\":161");
        });

        suite.test("driver: attach remote failure exits 4", () -> {
            FakeBackend backend = new FakeBackend();
            backend.attachStatus = -1002;
            Result r = run(of(backend), "attach", "999");
            Assert.eq(Exit.REMOTE, r.code);
        });

        suite.test("driver: commit", () -> {
            FakeBackend backend = new FakeBackend();
            Result r = run(of(backend), "commit", "161");
            Assert.eq(Exit.OK, r.code);
            Assert.contains(r.out, "commit session=161");
        });

        suite.test("driver: dump-config pass-through", () -> {
            Result r = run(of(new FakeBackend()), "dump-config");
            Assert.eq(Exit.OK, r.code);
            Assert.eq("{\"mode\":\"auto\"}\n", r.out);
        });

        suite.test("driver: stats pass-through", () -> {
            Result r = run(of(new FakeBackend()), "stats", "--json");
            Assert.eq(Exit.OK, r.code);
            Assert.contains(r.out, "uptimeMs");
        });

        suite.test("driver: dump-session pass-through", () -> {
            FakeBackend backend = new FakeBackend();
            Result r = run(of(backend), "dump-session", "7");
            Assert.eq(Exit.OK, r.code);
            Assert.eq(7, backend.lastSession);
            Assert.contains(r.out, "session");
        });

        suite.test("driver: mode show", () -> {
            Result r = run(of(new FakeBackend()), "mode");
            Assert.eq(Exit.OK, r.code);
            Assert.eq("auto\n", r.out);
        });

        suite.test("driver: mode set", () -> {
            FakeBackend backend = new FakeBackend();
            Result r = run(of(backend), "mode", "backend");
            Assert.eq(Exit.OK, r.code);
            Assert.eq(1, backend.lastMode);
            Assert.contains(r.out, "mode=backend");
        });

        suite.test("driver: invalid mode is a usage error", () ->
                Assert.eq(Exit.USAGE, run(of(new FakeBackend()), "mode", "bogus").code));

        suite.test("driver: enabled show", () -> {
            FakeBackend backend = new FakeBackend();
            backend.enabledValue = true;
            Result r = run(of(backend), "enabled");
            Assert.eq(Exit.OK, r.code);
            Assert.eq("on\n", r.out);
            backend.enabledValue = false;
            r = run(of(backend), "enabled");
            Assert.eq("off\n", r.out);
        });

        suite.test("driver: enabled set on and off", () -> {
            FakeBackend backend = new FakeBackend();
            Result r = run(of(backend), "enabled", "off");
            Assert.eq(Exit.OK, r.code);
            Assert.isTrue(!backend.enabledValue, "setEnabled(false) reached the backend");
            Assert.contains(r.out, "enabled=off");
            r = run(of(backend), "enabled", "on");
            Assert.isTrue(backend.enabledValue, "setEnabled(true) reached the backend");
            Assert.contains(r.out, "enabled=on");
        });

        suite.test("driver: enabled json output", () -> {
            FakeBackend backend = new FakeBackend();
            backend.enabledValue = false;
            Result r = run(of(backend), "enabled", "--json");
            Assert.eq(Exit.OK, r.code);
            Assert.contains(r.out, "\"enabled\":false");
        });

        suite.test("driver: invalid enabled value is a usage error", () ->
                Assert.eq(Exit.USAGE, run(of(new FakeBackend()), "enabled", "bogus").code));

        suite.test("driver: enabled remote failure exits 4", () -> {
            FakeBackend backend = new FakeBackend();
            backend.setEnabledStatus = -1006;
            Result r = run(of(backend), "enabled", "off");
            Assert.eq(Exit.REMOTE, r.code);
        });

        suite.test("driver: policy list", () -> {
            Result r = run(of(new FakeBackend()), "policy", "list");
            Assert.eq(Exit.OK, r.code);
            Assert.contains(r.out, "com.x");
        });

        suite.test("driver: policy get plain and json", () -> {
            FakeBackend backend = new FakeBackend();
            backend.getPolicyValue = 2;
            Result plain = run(of(backend), "policy", "get", "com.example.music");
            Assert.eq(Exit.OK, plain.code);
            Assert.eq("com.example.music exclude\n", plain.out);
            Result json = run(of(backend), "--json", "policy", "get", "com.example.music");
            Assert.contains(json.out, "\"policy\":\"exclude\"");
            Assert.contains(json.out, "\"policyMode\":2");
        });

        suite.test("driver: policy set records pkg and mode", () -> {
            FakeBackend backend = new FakeBackend();
            Result r = run(of(backend), "policy", "set", "com.example.music", "exclude");
            Assert.eq(Exit.OK, r.code);
            Assert.eq("com.example.music", backend.lastPkg);
            Assert.eq(2, backend.lastPolicyMode);
        });

        suite.test("driver: policy set remote failure exits 4", () -> {
            FakeBackend backend = new FakeBackend();
            backend.setPolicyStatus = -1003;
            Result r = run(of(backend), "policy", "set", "com.example.music", "on");
            Assert.eq(Exit.REMOTE, r.code);
        });

        suite.test("driver: bench echoes values", () -> {
            FakeBackend backend = new FakeBackend();
            Result r = run(of(backend), "bench", "161", "1500", "0.5", "--iters", "5");
            Assert.eq(Exit.OK, r.code);
            Assert.eq(5, backend.setParamCalls);
            Assert.eq(5, backend.getParamCalls);
            Assert.contains(r.out, "iters=5");
            Assert.contains(r.out, "mismatches=0");
        });

        suite.test("driver: bench mismatch exits 4", () -> {
            FakeBackend backend = new FakeBackend();
            backend.getReply = new byte[] {0x00, 0x00, 0x00, 0x00};
            Result r = run(of(backend), "bench", "161", "1500", "0.5", "--iters", "3");
            Assert.eq(Exit.REMOTE, r.code);
            Assert.contains(r.err, "did not match");
        });

        suite.test("driver: unknown command is a usage error", () -> {
            Result r = run(failing(Exit.UNAVAILABLE, "no daemon"), "bogus");
            Assert.eq(Exit.USAGE, r.code);
        });

        suite.test("driver: usage error does not open the backend", () -> {
            final boolean[] opened = {false};
            TransportFactory factory = new TransportFactory() {
                @Override
                public Backend open(String tcpEndpoint, boolean forceTcp) {
                    opened[0] = true;
                    return new FakeBackend();
                }
            };
            Result r = run(factory, "set", "161");
            Assert.eq(Exit.USAGE, r.code);
            Assert.isTrue(!opened[0], "backend must not be opened for a usage error");
        });

        suite.test("driver: service unavailable exits 2", () -> {
            Result r = run(failing(Exit.UNAVAILABLE,
                    "jdspd is not running (service jdsp.service not found)"), "sessions");
            Assert.eq(Exit.UNAVAILABLE, r.code);
            Assert.contains(r.err, "jdspd is not running");
            Assert.contains(r.err, "jdsp.service");
        });

        suite.test("driver: help command works without a daemon", () -> {
            Result r = run(failing(Exit.UNAVAILABLE, "no daemon"), "help");
            Assert.eq(Exit.OK, r.code);
            Assert.contains(r.out, "Usage:");
            Assert.contains(r.out, "Exit codes:");
            Assert.contains(r.out, "--tcp");
        });

        suite.test("driver: --help flag works without a daemon", () -> {
            Result r = run(failing(Exit.UNAVAILABLE, "no daemon"), "ping", "--help");
            Assert.eq(Exit.OK, r.code);
            Assert.contains(r.out, "jdspcli");
        });

        suite.test("driver: exit code constants are the documented ones", () -> {
            Assert.eq(0, Exit.OK);
            Assert.eq(1, Exit.FAIL);
            Assert.eq(2, Exit.UNAVAILABLE);
            Assert.eq(3, Exit.USAGE);
            Assert.eq(4, Exit.REMOTE);
        });
    }
}
