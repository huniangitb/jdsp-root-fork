package com.jdsp.common;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;

/**
 * v2.1.6 fixes around one GET_PARAM reply: the engine reports how many bytes it wrote (4 for the
 * mix check counters 30023/30024, 112 for the tap status 32002, 0 for the classic DSP parameters)
 * and the daemon used to accept only 0, so {@code jdspcli get} answered "no data" for the whole
 * diagnostic range although the engine had answered. Also pins the diagnostic range itself
 * (30000-32999), which must never be persisted.
 */
public final class ParamReplyTest {
    private ParamReplyTest() {}

    public static void run(Suite suite) {
        suite.test("reply: rc > 0 is success and the value is the first rc bytes", () -> {
            Assert.isTrue(ParamReply.isSuccess(112), "rc=112 (tap status) is success");
            Assert.isTrue(ParamReply.isSuccess(4), "rc=4 (mix check counter) is success");
            Assert.eq(112, ParamReply.successLength(112, 128), "112 bytes of a 128 byte buffer");
            Assert.eq(4, ParamReply.successLength(4, 4), "4 bytes of a 4 byte buffer");
            Assert.eq(4, ParamReply.successLength(4, 12), "4 bytes of a larger buffer");
        });

        suite.test("reply: rc == 0 keeps the classic behaviour (whole buffer)", () -> {
            Assert.isTrue(ParamReply.isSuccess(0), "rc=0 is success");
            Assert.eq(12, ParamReply.successLength(0, 12), "engine reported no size: whole buffer");
            Assert.eq(4, ParamReply.successLength(0, 4), "engine reported no size: whole buffer");
        });

        suite.test("reply: rc < 0 is a failure and yields no value", () -> {
            Assert.isTrue(!ParamReply.isSuccess(-1), "rc=-1 is a failure");
            Assert.isTrue(!ParamReply.isSuccess(-7), "rc=-7 is a failure");
            Assert.isTrue(!ParamReply.isSuccess(-1006), "rc=-1006 is a failure");
            Assert.eq(-1, ParamReply.successLength(-1, 12), "no value for rc=-1");
            Assert.eq(-1, ParamReply.successLength(-7, 4), "no value for rc=-7");
        });

        suite.test("reply: an rc larger than the buffer is clamped", () -> {
            Assert.isTrue(ParamReply.isSuccess(64), "still success");
            Assert.eq(8, ParamReply.successLength(64, 8), "never report more than was received");
        });

        suite.test("diagnostic range: 30000-32999 inclusive and exact at the boundaries", () -> {
            Assert.isTrue(DiagnosticParams.isDiagnostic(30000), "range start is diagnostic");
            Assert.isTrue(DiagnosticParams.isDiagnostic(32999), "range end is diagnostic");
            Assert.isTrue(!DiagnosticParams.isDiagnostic(29999), "just below the range");
            Assert.isTrue(!DiagnosticParams.isDiagnostic(33000), "just above the range");
            Assert.isTrue(!DiagnosticParams.isDiagnostic(1500), "a real DSP parameter");
            int[] mix = {30023, 30024, 30025, 30026, 30027, 30028};
            for (int i = 0; i < mix.length; i++) {
                Assert.isTrue(DiagnosticParams.isDiagnostic(mix[i]), "mix check " + mix[i]);
            }
            int[] tap = {32001, 32002};
            for (int i = 0; i < tap.length; i++) {
                Assert.isTrue(DiagnosticParams.isDiagnostic(tap[i]), "capture tap " + tap[i]);
            }
        });
    }
}
