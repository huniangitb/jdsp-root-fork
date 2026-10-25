package com.jdsp.cli;

/** Host-side test entry point: no JUnit, no device, plain Java. */
public final class AllTests {
    private AllTests() {}

    public static void main(String[] args) {
        Suite suite = new Suite(System.out);
        com.jdsp.common.BackendCommonTest.run(suite);
        com.jdsp.common.EnableGateTest.run(suite);
        com.jdsp.common.EnableErrorsTest.run(suite);
        com.jdsp.common.WritePolicyTest.run(suite);
        com.jdsp.common.BypassRuleTest.run(suite);
        com.jdsp.common.EnableSwitchTest.run(suite);
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