package com.jdsp.cli;

/** Host-side test entry point: no JUnit, no device, plain Java. */
public final class AllTests {
    private AllTests() {}

    public static void main(String[] args) {
        Suite suite = new Suite(System.out);
        com.jdsp.common.BackendCommonTest.run(suite);
        ParserTest.run(suite);
        ValueCodecTest.run(suite);
        FormatTest.run(suite);
        JsonTest.run(suite);
        TcpBackendTest.run(suite);
        TransportTest.run(suite);
        DriverTest.run(suite);
        System.out.flush();
        System.exit(suite.report());
    }
}