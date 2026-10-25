package com.jdsp.common;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;

/**
 * Pure Java regression tests for the global master switch state machine.
 *
 * <p>Two things are tested: an echo of the current value is a pure no-op, and a burst of
 * alternating requests collapses into a single transition (an old App used to mirror the
 * backend state back and forth, which made the daemon flip the effect instances repeatedly and
 * was measured to crash system_server).
 */
public final class EnableSwitchTest {
    private EnableSwitchTest() {}

    public static void run(Suite suite) {
        suite.test("switch: an echo of the current value is a no-op", new com.jdsp.cli.Body() {
            public void run() {
                EnableSwitch s = new EnableSwitch(1000L);
                // The daemon starts "on"; asking for "on" again must change nothing.
                Assert.eq(EnableSwitch.NONE, s.request(true, 1000L));
                Assert.isTrue(s.applied(), "the state is unchanged");
                Assert.isTrue(!s.pending(), "nothing is pending");
            }
        });

        suite.test("switch: turning off is applied immediately", new com.jdsp.cli.Body() {
            public void run() {
                EnableSwitch s = new EnableSwitch(1000L);
                Assert.eq(EnableSwitch.APPLY_NOW, s.request(false, 1000L));
                s.transitionApplied(false, 1000L);
                Assert.isTrue(!s.applied(), "the state is off now");
            }
        });

        suite.test("switch: turning on right after off is rate limited", new com.jdsp.cli.Body() {
            public void run() {
                EnableSwitch s = new EnableSwitch(1000L);
                s.request(false, 1000L);
                s.transitionApplied(false, 1000L);
                // The "on" comes 100 ms later: deferred, not applied.
                Assert.eq(EnableSwitch.DEFER, s.request(true, 1100L));
                Assert.isTrue(s.pending(), "the transition is pending");
                Assert.isTrue(!s.applied(), "the state is still off");
            }
        });

        suite.test("switch: a deferred transition is applied when polled", new com.jdsp.cli.Body() {
            public void run() {
                EnableSwitch s = new EnableSwitch(1000L);
                s.request(false, 1000L);
                s.transitionApplied(false, 1000L);
                s.request(true, 1100L);
                Assert.eq(null, s.poll(1500L), "not yet due");
                Boolean value = s.poll(2100L);
                Assert.eq(Boolean.TRUE, value, "due now");
                Assert.isTrue(!s.pending(), "nothing pending any more");
            }
        });

        suite.test("switch: an echo does not cancel a pending transition", new com.jdsp.cli.Body() {
            public void run() {
                EnableSwitch s = new EnableSwitch(1000L);
                s.request(false, 1000L);
                s.transitionApplied(false, 1000L);
                s.request(true, 1100L);
                // A stale echo of the current value ("off") must not drop the user's intent.
                Assert.eq(EnableSwitch.NONE, s.request(false, 1150L));
                Assert.isTrue(s.pending(), "the pending transition survived the echo");
                Assert.eq(Boolean.TRUE, s.poll(2100L));
            }
        });

        suite.test("switch: an echo never cancels the user's pending intent",
                new com.jdsp.cli.Body() {
                    public void run() {
                        EnableSwitch s = new EnableSwitch(1000L);
                        s.request(false, 1000L);
                        s.transitionApplied(false, 1000L);
                        // An explicit "on" is pending...
                        Assert.eq(EnableSwitch.DEFER, s.request(true, 1100L));
                        Assert.isTrue(s.pending(), "an on is pending");
                        // ...and an echo of the current state ("off") must not drop it.
                        Assert.eq(EnableSwitch.NONE, s.request(false, 1150L));
                        Assert.isTrue(s.pending(), "the pending transition survived the echo");
                        Assert.eq(Boolean.TRUE, s.poll(2100L), "the pending intent is applied");
                    }
                });

        suite.test("switch: a burst of alternating requests collapses into one transition",
                new com.jdsp.cli.Body() {
                    public void run() {
                        EnableSwitch s = new EnableSwitch(1000L);
                        int applied = 0;
                        long now = 1000L;
                        for (int i = 0; i < 50; i++) {
                            boolean target = (i % 2 == 0);
                            int d = s.request(target, now);
                            if (d == EnableSwitch.APPLY_NOW) {
                                applied++;
                                s.transitionApplied(target, now);
                            } else if (d == EnableSwitch.DEFER) {
                                // The watcher applies the deferred transition.
                                Boolean v = s.poll(now);
                                if (v != null) {
                                    applied++;
                                    s.transitionApplied(v.booleanValue(), now);
                                }
                            }
                            now += 20L;
                        }
                        // 50 alternating requests must not cause 50 transitions: the rate limit
                        // collapses them into one per interval.
                        Assert.isTrue(applied <= 3, "at most three transitions, was " + applied);
                    }
                });

        suite.test("switch: a long quiet period allows a new transition", new com.jdsp.cli.Body() {
            public void run() {
                EnableSwitch s = new EnableSwitch(1000L);
                s.request(false, 1000L);
                s.transitionApplied(false, 1000L);
                // Ten seconds later the rate limit has expired: the transition goes through.
                Assert.eq(EnableSwitch.APPLY_NOW, s.request(true, 11000L));
            }
        });

        suite.test("switch: the startup state comes from the configuration", new com.jdsp.cli.Body() {
            public void run() {
                // The daemon aligns the switch with config.json at startup (now = 0 means "no
                // transition happened yet", so the first real transition is not rate limited).
                EnableSwitch s = new EnableSwitch(1000L);
                s.transitionApplied(false, 0L);
                Assert.isTrue(!s.applied(), "the switch starts off");
                Assert.eq(EnableSwitch.NONE, s.request(false, 5000L), "an echo is a no-op");
                Assert.eq(EnableSwitch.APPLY_NOW, s.request(true, 5000L),
                        "the first real transition is not rate limited");
            }
        });

        suite.test("switch: an alternating client cannot make the daemon flip every time",
                new com.jdsp.cli.Body() {
                    public void run() {
                        // The old App mirrored the state back and forth every 20 ms. Without
                        // the rate limit that was ~50 transitions; with it, one per interval.
                        EnableSwitch s = new EnableSwitch(1000L);
                        int transitions = 0;
                        long now = 10000L;
                        for (int i = 0; i < 300; i++) {
                            boolean target = (i % 2 == 0);
                            int d = s.request(target, now);
                            if (d == EnableSwitch.APPLY_NOW) {
                                transitions++;
                                s.transitionApplied(target, now);
                            } else if (d == EnableSwitch.DEFER) {
                                Boolean v = s.poll(now);
                                if (v != null) {
                                    transitions++;
                                    s.transitionApplied(v.booleanValue(), now);
                                }
                            }
                            now += 20L;
                        }
                        Assert.isTrue(transitions <= 8,
                                "6 s of alternating requests caused " + transitions
                                        + " transitions (must stay bounded)");
                    }
                });
    }
}
