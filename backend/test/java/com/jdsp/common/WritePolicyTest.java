package com.jdsp.common;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;

/**
 * Pure Java regression tests for the order in which the daemon evaluates a parameter write.
 *
 * <p>The order is part of the client contract: while the backend is switched off, a write must
 * report "disabled" (-1006) even for a session that has no effect handle yet, so the client can
 * keep the user's intent instead of treating it as a transport failure.
 */
public final class WritePolicyTest {
    private WritePolicyTest() {}

    public static void run(Suite suite) {
        suite.test("write: disabled wins over a missing handle", new com.jdsp.cli.Body() {
            public void run() {
                // The order the daemon uses: master switch first, handle second.
                Assert.eq(WritePolicy.DISABLED,
                        WritePolicy.writeOutcome(true, false));
                Assert.eq(WritePolicy.DISABLED,
                        WritePolicy.writeOutcome(true, true));
            }
        });

        suite.test("write: enabled without a handle asks for an attach", new com.jdsp.cli.Body() {
            public void run() {
                Assert.eq(WritePolicy.NO_HANDLE,
                        WritePolicy.writeOutcome(false, false));
            }
        });

        suite.test("write: enabled with a handle is accepted", new com.jdsp.cli.Body() {
            public void run() {
                Assert.eq(WritePolicy.OK, WritePolicy.writeOutcome(false, true));
            }
        });

        suite.test("write: the codes are the documented ones", new com.jdsp.cli.Body() {
            public void run() {
                Assert.eq(-1006, WritePolicy.DISABLED);
                Assert.eq(-1001, WritePolicy.NO_HANDLE);
                Assert.eq(0, WritePolicy.OK);
                // The disabled code is the one the client treats as "the user switched it off".
                Assert.isTrue(EnableErrors.isDisabled(WritePolicy.DISABLED),
                        "the write outcome uses the shared disabled code");
            }
        });
    }
}
