package com.jdsp.common;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;

/**
 * Pure Java regression tests for the error code conventions of the global master switch.
 *
 * <p>The rule that matters for the App: {@code setEnabled} must never report "disabled"
 * (-1006), because the client would read that as "the user switched the backend off" and wait
 * for a re-enable that may never come.
 */
public final class EnableErrorsTest {
    private EnableErrorsTest() {}

    public static void run(Suite suite) {
        suite.test("errors: the disabled code is -1006", new com.jdsp.cli.Body() {
            public void run() {
                Assert.eq(-1006, EnableErrors.DISABLED);
                Assert.isTrue(EnableErrors.isDisabled(-1006), "-1006 is the disabled code");
                Assert.isTrue(!EnableErrors.isDisabled(-1004), "-1004 is not the disabled code");
            }
        });

        suite.test("errors: the enable command never reports disabled", new com.jdsp.cli.Body() {
            public void run() {
                // Even if some path produced the disabled code, the enable command must
                // downgrade it to a plain failure.
                Assert.eq(EnableErrors.INTERNAL, EnableErrors.forEnableCommand(EnableErrors.DISABLED));
                Assert.isTrue(!EnableErrors.isDisabled(EnableErrors.forEnableCommand(-1006)),
                        "the enable command must not look like 'the backend is off'");
            }
        });

        suite.test("errors: other codes are passed through", new com.jdsp.cli.Body() {
            public void run() {
                Assert.eq(-1003, EnableErrors.forEnableCommand(-1003));
                Assert.eq(-1004, EnableErrors.forEnableCommand(-1004));
                Assert.eq(0, EnableErrors.forEnableCommand(0));
            }
        });
    }
}
