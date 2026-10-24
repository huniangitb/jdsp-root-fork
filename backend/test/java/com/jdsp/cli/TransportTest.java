package com.jdsp.cli;

/** Transport selection: binder-first auto fallback, forced socket, failure mapping. */
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
        suite.test("transport: auto falls back to the default socket", () -> {
            final DriverTest.FakeBackend socketBackend = new DriverTest.FakeBackend();
            final String[] seenPath = {null};
            AutoTransport transport = new AutoTransport(
                    binderFailure(Exit.UNAVAILABLE,
                            "jdspd is not running (service jdsp.service not found)"),
                    new AutoTransport.SocketOpener() {
                        @Override
                        public Backend open(String path) {
                            seenPath[0] = path;
                            return socketBackend;
                        }
                    });
            Backend backend = transport.open(null);
            Assert.eq(socketBackend, backend);
            Assert.eq(TransportFactory.DEFAULT_SOCKET_PATH, seenPath[0]);
        });

        suite.test("transport: both transports missing reports both in exit 2", () -> {
            AutoTransport transport = new AutoTransport(
                    binderFailure(Exit.UNAVAILABLE,
                            "jdspd is not running (service jdsp.service not found)"),
                    new AutoTransport.SocketOpener() {
                        @Override
                        public Backend open(String path) throws CliException {
                            throw new CliException(Exit.UNAVAILABLE,
                                    "socket " + path + " unavailable: No such file or directory");
                        }
                    });
            CliException error = Assert.cliCode(Exit.UNAVAILABLE, () -> transport.open(null));
            Assert.contains(error.getMessage(), "jdsp.service");
            Assert.contains(error.getMessage(), "socket " + TransportFactory.DEFAULT_SOCKET_PATH
                    + " unavailable");
            Assert.contains(error.getMessage(), "No such file or directory");
        });

        suite.test("transport: --socket path bypasses the binder", () -> {
            final boolean[] binderCalled = {false};
            final String[] seenPath = {null};
            AutoTransport transport = new AutoTransport(
                    new AutoTransport.BinderOpener() {
                        @Override
                        public Backend open() {
                            binderCalled[0] = true;
                            return new DriverTest.FakeBackend();
                        }
                    },
                    new AutoTransport.SocketOpener() {
                        @Override
                        public Backend open(String path) {
                            seenPath[0] = path;
                            return new DriverTest.FakeBackend();
                        }
                    });
            transport.open("/tmp/custom.sock");
            Assert.isTrue(!binderCalled[0], "binder must not be touched when --socket is given");
            Assert.eq("/tmp/custom.sock", seenPath[0]);
        });

        suite.test("transport: non-unavailable binder error propagates", () -> {
            final boolean[] socketCalled = {false};
            AutoTransport transport = new AutoTransport(
                    binderFailure(Exit.FAIL, "boom"),
                    new AutoTransport.SocketOpener() {
                        @Override
                        public Backend open(String path) {
                            socketCalled[0] = true;
                            return new DriverTest.FakeBackend();
                        }
                    });
            Assert.cliCode(Exit.FAIL, () -> transport.open(null));
            Assert.isTrue(!socketCalled[0], "socket must not be tried after a non-unavailable error");
        });

        suite.test("transport: socket error propagates when it is not unavailable", () -> {
            AutoTransport transport = new AutoTransport(
                    binderFailure(Exit.UNAVAILABLE,
                            "jdspd is not running (service jdsp.service not found)"),
                    new AutoTransport.SocketOpener() {
                        @Override
                        public Backend open(String path) throws CliException {
                            throw new CliException(Exit.USAGE, "bad socket path");
                        }
                    });
            Assert.cliCode(Exit.USAGE, () -> transport.open(null));
        });

        suite.test("transport: cli --socket forces the default socket", () -> {
            final boolean[] binderCalled = {false};
            final String[] seenPath = {null};
            AutoTransport transport = new AutoTransport(
                    new AutoTransport.BinderOpener() {
                        @Override
                        public Backend open() {
                            binderCalled[0] = true;
                            throw new AssertionError("binder must not be used with --socket");
                        }
                    },
                    new AutoTransport.SocketOpener() {
                        @Override
                        public Backend open(String path) {
                            seenPath[0] = path;
                            return new DriverTest.FakeBackend();
                        }
                    });
            DriverTest.Result result = DriverTest.run(transport, "--socket", "ping");
            Assert.eq(Exit.OK, result.code);
            Assert.isTrue(!binderCalled[0], "binder must not be used with --socket");
            Assert.eq(TransportFactory.DEFAULT_SOCKET_PATH, seenPath[0]);
        });

        suite.test("transport: cli --socket=/path passes the path", () -> {
            final String[] seenPath = {null};
            AutoTransport transport = new AutoTransport(
                    binderFailure(Exit.UNAVAILABLE, "unused"),
                    new AutoTransport.SocketOpener() {
                        @Override
                        public Backend open(String path) {
                            seenPath[0] = path;
                            return new DriverTest.FakeBackend();
                        }
                    });
            DriverTest.Result result = DriverTest.run(transport, "--socket=/tmp/custom.sock", "version");
            Assert.eq(Exit.OK, result.code);
            Assert.eq("/tmp/custom.sock", seenPath[0]);
        });

        suite.test("transport: cli auto fallback serves a command", () -> {
            DriverTest.FakeBackend socketBackend = new DriverTest.FakeBackend();
            socketBackend.sessions.add(DriverTest.sampleRow());
            AutoTransport transport = new AutoTransport(
                    binderFailure(Exit.UNAVAILABLE,
                            "jdspd is not running (service jdsp.service not found)"),
                    new AutoTransport.SocketOpener() {
                        @Override
                        public Backend open(String path) {
                            return socketBackend;
                        }
                    });
            DriverTest.Result result = DriverTest.run(transport, "sessions");
            Assert.eq(Exit.OK, result.code);
            Assert.contains(result.out, "com.google.uid.shared");
        });

        suite.test("transport: cli reports exit 2 when both are missing", () -> {
            AutoTransport transport = new AutoTransport(
                    binderFailure(Exit.UNAVAILABLE,
                            "jdspd is not running (service jdsp.service not found)"),
                    new AutoTransport.SocketOpener() {
                        @Override
                        public Backend open(String path) throws CliException {
                            throw new CliException(Exit.UNAVAILABLE,
                                    "socket " + path + " unavailable: Connection refused");
                        }
                    });
            DriverTest.Result result = DriverTest.run(transport, "sessions");
            Assert.eq(Exit.UNAVAILABLE, result.code);
            Assert.contains(result.err, "jdsp.service");
            Assert.contains(result.err, "Connection refused");
        });
    }
}
