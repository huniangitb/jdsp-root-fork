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
        com.jdsp.common.EngineWriteLockTest.run(suite);
        com.jdsp.common.ParamBaselineTest.run(suite);
        com.jdsp.common.ParamReplyTest.run(suite);
        com.jdsp.common.ProbeScheduleTest.run(suite);
        com.jdsp.common.WatcherPolicyTest.run(suite);
        com.jdsp.backend.EffectManagerDetachTest.run(suite);
        com.jdsp.backend.AttachDebounceTest.run(suite);
        com.jdsp.backend.EffectManagerParallelTest.run(suite);
        com.jdsp.backend.EffectManagerConcurrencyTest.run(suite);
        com.jdsp.backend.DumpsysParserTest.run(suite);
        com.jdsp.backend.ApplyAllDedupTest.run(suite);
        com.jdsp.backend.AsyncPersisterTest.run(suite);
        com.jdsp.backend.DiagnosticParamPersistenceTest.run(suite);
        com.jdsp.backend.AutoModeDecisionTest.run(suite);
        com.jdsp.backend.ContentReplayTest.run(suite);
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