package com.jdsp.cli;

/** Transport selection: binder-first auto fallback, forced TCP, failure mapping. */
public final class TransportTest {
    private TransportTest() {}

    private static AutoTransport.BinderOpener binderFailure(final int code, final String message) {
        return new AutoTransport.BinderOpener() {
            @Override
            public Backend open() throws CliException {
                throw new CliException(code, message);
            }
        };
    }

    public static void run(Suite suite) {
        suite.test("transport: auto falls back to the default tcp endpoint", () -> {
            final DriverTest.FakeBackend tcpBackend = new DriverTest.FakeBackend();
            final String[] seenEndpoint = {"unset"};
            AutoTransport transport = new AutoTransport(
                    binderFailure(Exit.UNAVAILABLE,
                            "jdspd is not running (service jdsp.service not found)"),
                    new AutoTransport.TcpOpener() {
                        @Override
                        public Backend open(String endpoint) {
                            seenEndpoint[0] = endpoint;
                            return tcpBackend;
                        }
                    });
            Backend backend = transport.open(null, false);
            Assert.eq(tcpBackend, backend);
            Assert.eq(null, seenEndpoint[0]);
        });

        suite.test("transport: both channels missing reports both in exit 2", () -> {
            AutoTransport transport = new AutoTransport(
                    binderFailure(Exit.UNAVAILABLE,
                            "jdspd is not running (service jdsp.service not found)"),
                    new AutoTransport.TcpOpener() {
                        @Override
                        public Backend open(String endpoint) throws CliException {
                            throw new CliException(Exit.UNAVAILABLE,
                                    "tcp endpoint files missing (/data/data/james.dsp/files/jdsp.{port,token})");
                        }
                    });
            CliException error = Assert.cliCode(Exit.UNAVAILABLE,
                    () -> transport.open(null, false));
            Assert.contains(error.getMessage(), "jdsp.service");
            Assert.contains(error.getMessage(), "tcp endpoint files missing");
        });

        suite.test("transport: force tcp bypasses the binder", () -> {
            final boolean[] binderCalled = {false};
            final String[] seenEndpoint = {"unset"};
            AutoTransport transport = new AutoTransport(
                    new AutoTransport.BinderOpener() {
                        @Override
                        public Backend open() {
                            binderCalled[0] = true;
                            return new DriverTest.FakeBackend();
                        }
                    },
                    new AutoTransport.TcpOpener() {
                        @Override
                        public Backend open(String endpoint) {
                            seenEndpoint[0] = endpoint;
                            return new DriverTest.FakeBackend();
                        }
                    });
            transport.open("127.0.0.1:4321", true);
            Assert.isTrue(!binderCalled[0], "binder must not be touched with --tcp");
            Assert.eq("127.0.0.1:4321", seenEndpoint[0]);
        });

        suite.test("transport: non-unavailable binder error propagates", () -> {
            final boolean[] tcpCalled = {false};
            AutoTransport transport = new AutoTransport(
                    binderFailure(Exit.FAIL, "boom"),
                    new AutoTransport.TcpOpener() {
                        @Override
                        public Backend open(String endpoint) {
                            tcpCalled[0] = true;
                            return new DriverTest.FakeBackend();
                        }
                    });
            Assert.cliCode(Exit.FAIL, () -> transport.open(null, false));
            Assert.isTrue(!tcpCalled[0], "tcp must not be tried after a non-unavailable error");
        });

        suite.test("transport: tcp error propagates when it is not unavailable", () -> {
            AutoTransport transport = new AutoTransport(
                    binderFailure(Exit.UNAVAILABLE,
                            "jdspd is not running (service jdsp.service not found)"),
                    new AutoTransport.TcpOpener() {
                        @Override
                        public Backend open(String endpoint) throws CliException {
                            throw new CliException(Exit.USAGE, "bad tcp endpoint");
                        }
                    });
            Assert.cliCode(Exit.USAGE, () -> transport.open(null, false));
        });

        suite.test("transport: cli --tcp forces the default endpoint", () -> {
            final boolean[] binderCalled = {false};
            final String[] seenEndpoint = {"unset"};
            AutoTransport transport = new AutoTransport(
                    new AutoTransport.BinderOpener() {
                        @Override
                        public Backend open() {
                            binderCalled[0] = true;
                            throw new AssertionError("binder must not be used with --tcp");
                        }
                    },
                    new AutoTransport.TcpOpener() {
                        @Override
                        public Backend open(String endpoint) {
                            seenEndpoint[0] = endpoint;
                            return new DriverTest.FakeBackend();
                        }
                    });
            DriverTest.Result result = DriverTest.run(transport, "--tcp", "ping");
            Assert.eq(Exit.OK, result.code);
            Assert.isTrue(!binderCalled[0], "binder must not be used with --tcp");
            Assert.eq(null, seenEndpoint[0]);
        });

        suite.test("transport: cli --tcp=host:port passes the endpoint", () -> {
            final String[] seenEndpoint = {"unset"};
            AutoTransport transport = new AutoTransport(
                    binderFailure(Exit.UNAVAILABLE, "unused"),
                    new AutoTransport.TcpOpener() {
                        @Override
                        public Backend open(String endpoint) {
                            seenEndpoint[0] = endpoint;
                            return new DriverTest.FakeBackend();
                        }
                    });
            DriverTest.Result result = DriverTest.run(transport,
                    "--tcp=127.0.0.1:4321", "version");
            Assert.eq(Exit.OK, result.code);
            Assert.eq("127.0.0.1:4321", seenEndpoint[0]);
        });

        suite.test("transport: cli auto fallback serves a command", () -> {
            DriverTest.FakeBackend tcpBackend = new DriverTest.FakeBackend();
            tcpBackend.sessions.add(DriverTest.sampleRow());
            AutoTransport transport = new AutoTransport(
                    binderFailure(Exit.UNAVAILABLE,
                            "jdspd is not running (service jdsp.service not found)"),
                    new AutoTransport.TcpOpener() {
                        @Override
                        public Backend open(String endpoint) {
                            return tcpBackend;
                        }
                    });
            DriverTest.Result result = DriverTest.run(transport, "sessions");
            Assert.eq(Exit.OK, result.code);
            Assert.contains(result.out, "com.google.uid.shared");
        });

        suite.test("transport: cli reports exit 2 when both channels are missing", () -> {
            AutoTransport transport = new AutoTransport(
                    binderFailure(Exit.UNAVAILABLE,
                            "jdspd is not running (service jdsp.service not found)"),
                    new AutoTransport.TcpOpener() {
                        @Override
                        public Backend open(String endpoint) throws CliException {
                            throw new CliException(Exit.UNAVAILABLE,
                                    "tcp endpoint unreachable (127.0.0.1:1234: Connection refused)");
                        }
                    });
            DriverTest.Result result = DriverTest.run(transport, "sessions");
            Assert.eq(Exit.UNAVAILABLE, result.code);
            Assert.contains(result.err, "jdsp.service");
            Assert.contains(result.err, "Connection refused");
        });
    }
}
