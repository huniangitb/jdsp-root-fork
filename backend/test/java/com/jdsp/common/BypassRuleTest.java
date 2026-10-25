package com.jdsp.common;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;

/**
 * Pure Java regression tests for the idempotence of the effect enable state.
 *
 * <p>Regression for a release blocker: while the global master switch was off, the watcher
 * called {@code AudioEffect.setEnabled(false)} on every session on every 1 s tick. The calls
 * reached the audio HAL (about 28 per second on a busy device) and system_server crashed twice
 * in that window. The framework must only be called when the state really changes.
 */
public final class BypassRuleTest {
    private BypassRuleTest() {}

    public static void run(Suite suite) {
        suite.test("bypass: an instance that is on has to be switched off", new com.jdsp.cli.Body() {
            public void run() {
                Assert.eq(BypassRule.CALL_DISABLE,
                        BypassRule.whileDisabled(true, true));
            }
        });

        suite.test("bypass: an instance that is already off is left alone", new com.jdsp.cli.Body() {
            public void run() {
                // The regression: this must not produce another framework call.
                Assert.eq(BypassRule.NO_CALL,
                        BypassRule.whileDisabled(true, false));
            }
        });

        suite.test("bypass: nothing is called without a handle", new com.jdsp.cli.Body() {
            public void run() {
                Assert.eq(BypassRule.NO_CALL, BypassRule.whileDisabled(false, false));
                Assert.eq(BypassRule.NO_CALL, BypassRule.whileDisabled(false, true));
            }
        });

        suite.test("bypass: the enable direction is symmetric", new com.jdsp.cli.Body() {
            public void run() {
                Assert.eq(BypassRule.CALL_ENABLE, BypassRule.whileEnabled(false));
                Assert.eq(BypassRule.NO_CALL, BypassRule.whileEnabled(true));
            }
        });

        suite.test("bypass: a full tick changes the state at most once", new com.jdsp.cli.Body() {
            public void run() {
                // Simulate 10 watcher ticks with the switch off: the first one switches the
                // instance off, the other nine must not call the framework again.
                int calls = 0;
                boolean enabled = true;
                for (int tick = 0; tick < 10; tick++) {
                    if (BypassRule.whileDisabled(true, enabled) == BypassRule.CALL_DISABLE) {
                        calls++;
                        enabled = false;
                    }
                }
                Assert.eq(1, calls);
            }
        });
    }
}
