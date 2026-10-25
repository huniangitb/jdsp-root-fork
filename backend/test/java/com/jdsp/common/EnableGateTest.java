package com.jdsp.common;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;

/**
 * Pure Java regression tests for the daemon's handle rules (attach / bypass / release).
 *
 * <p>These are the rules that decide what happens to a session's effect handle when the global
 * master switch is flipped or a policy changes; the watcher uses the very same class.
 */
public final class EnableGateTest {
    private EnableGateTest() {}

    public static void run(Suite suite) {
        suite.test("gate: excluded app is always released", new com.jdsp.cli.Body() {
            public void run() {
                Assert.eq(EnableGate.ACTION_RELEASE,
                        EnableGate.action(true, false, false, false));
                Assert.eq(EnableGate.ACTION_RELEASE,
                        EnableGate.action(true, false, true, true));
                // Even the global switch off does not change "excluded": the handle is gone.
                Assert.eq(EnableGate.ACTION_RELEASE,
                        EnableGate.action(true, true, false, true));
            }
        });

        suite.test("gate: global switch off unloads held handles and never attaches",
                new com.jdsp.cli.Body() {
                    public void run() {
                        // policy wants the effect, but the master switch is off and a handle
                        // is held: unload semantics, identical to the exclusion path.
                        Assert.eq(EnableGate.ACTION_RELEASE,
                                EnableGate.action(false, true, true, true));
                        // policy=follow in auto mode (no effect wanted) and the switch off:
                        // still released.
                        Assert.eq(EnableGate.ACTION_RELEASE,
                                EnableGate.action(false, true, false, true));
                        // Nothing held while the switch is off: nothing to do, and above all
                        // no attach (that is what keeps the switch off from mounting instances).
                        Assert.eq(EnableGate.ACTION_BYPASS,
                                EnableGate.action(false, true, true, false));
                        Assert.eq(EnableGate.ACTION_BYPASS,
                                EnableGate.action(false, true, false, false));
                    }
                });

        suite.test("gate: normal operation attaches when the policy allows it",
                new com.jdsp.cli.Body() {
                    public void run() {
                        Assert.eq(EnableGate.ACTION_ATTACH,
                                EnableGate.action(false, false, true, false));
                        Assert.eq(EnableGate.ACTION_ATTACH,
                                EnableGate.action(false, false, true, true));
                    }
                });

        suite.test("gate: policy does not want an effect -> release", new com.jdsp.cli.Body() {
            public void run() {
                Assert.eq(EnableGate.ACTION_RELEASE,
                        EnableGate.action(false, false, false, true));
                Assert.eq(EnableGate.ACTION_RELEASE,
                        EnableGate.action(false, false, false, false));
            }
        });

        suite.test("gate: action names", new com.jdsp.cli.Body() {
            public void run() {
                Assert.eq("attach", EnableGate.name(EnableGate.ACTION_ATTACH));
                Assert.eq("bypass", EnableGate.name(EnableGate.ACTION_BYPASS));
                Assert.eq("release", EnableGate.name(EnableGate.ACTION_RELEASE));
            }
        });
    }
}
