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

        suite.test("switch: turning off is immediate even inside the rate limit window",
                new com.jdsp.cli.Body() {
                    public void run() {
                        // Unload must be instantaneous: an explicit off is never deferred,
                        // not even 50 ms after the switch was turned on.
                        EnableSwitch s = new EnableSwitch(1000L);
                        s.transitionApplied(true, 1000L);
                        Assert.eq(EnableSwitch.APPLY_NOW, s.request(false, 1050L));
                        s.transitionApplied(false, 1050L);
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

        suite.test("switch: the pending deadline is exposed for the timer",
                new com.jdsp.cli.Body() {
                    public void run() {
                        // 0 = nothing pending, so the daemon arms no timer.
                        EnableSwitch s = new EnableSwitch(300L);
                        Assert.eq(0L, s.pendingDeadlineMs());
                        s.request(false, 1000L);
                        s.transitionApplied(false, 1000L);
                        Assert.eq(0L, s.pendingDeadlineMs(), "still nothing pending");
                        // A deferred "on" reports exactly the instant it becomes due: CoreApi
                        // arms a ScheduledExecutor for it (the watcher tick stays as the
                        // fallback), so a deferred transition no longer waits for the 500 ms
                        // poll on top of the rate limit (measured 612/986 ms before).
                        Assert.eq(EnableSwitch.DEFER, s.request(true, 1100L));
                        // The deadline is "request time + one rate-limit interval".
                        Assert.eq(1400L, s.pendingDeadlineMs());
                        Assert.eq(null, s.poll(1399L), "not due one ms before the deadline");
                        Assert.eq(Boolean.TRUE, s.poll(1400L), "due at the deadline");
                        Assert.eq(0L, s.pendingDeadlineMs(), "nothing pending after the poll");
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

        suite.test("switch: an alternating echo storm cannot mount the instances",
                new com.jdsp.cli.Body() {
                    public void run() {
                        // A client that mirrors the state back and forth every 20 ms: the
                        // storm starts on (echo), the first off unloads at once, and from
                        // then on every off is an echo while every on is deferred and the
                        // deadline keeps being pushed. Not a single on-mount gets through.
                        EnableSwitch s = new EnableSwitch(1000L);
                        int applied = 0;
                        int appliedOn = 0;
                        long now = 1000L;
                        for (int i = 0; i < 50; i++) {
                            boolean target = (i % 2 == 0);
                            int d = s.request(target, now);
                            if (d == EnableSwitch.APPLY_NOW) {
                                applied++;
                                if (target) {
                                    appliedOn++;
                                }
                                s.transitionApplied(target, now);
                            } else if (d == EnableSwitch.DEFER) {
                                Boolean v = s.poll(now);
                                if (v != null) {
                                    applied++;
                                    if (v.booleanValue()) {
                                        appliedOn++;
                                    }
                                    s.transitionApplied(v.booleanValue(), now);
                                }
                            }
                            now += 20L;
                        }
                        Assert.eq(1, applied, "exactly one transition: the first off");
                        Assert.eq(0, appliedOn,
                                "no on transition may be applied inside the storm");
                        Assert.isTrue(!s.applied(), "the storm leaves the switch off");
                        Assert.isTrue(s.pending(),
                                "the user's last intent (on) is still pending");
                        Boolean v = s.poll(now + 2000L);
                        Assert.eq(Boolean.TRUE, v, "the pending on is applied when quiet");
                    }
                });

        suite.test("switch: an alternating storm still mounts at most once per interval",
                new com.jdsp.cli.Body() {
                    public void run() {
                        // A longer storm (6 s): every off is immediate, but a mount ("on")
                        // can happen at most once per rate limit interval.
                        EnableSwitch s = new EnableSwitch(1000L);
                        int appliedOn = 0;
                        long lastOnAt = -1L;
                        long now = 10000L;
                        for (int i = 0; i < 300; i++) {
                            boolean target = (i % 2 == 0);
                            int d = s.request(target, now);
                            boolean doApply = false;
                            boolean value = target;
                            if (d == EnableSwitch.APPLY_NOW) {
                                doApply = true;
                            } else if (d == EnableSwitch.DEFER) {
                                Boolean v = s.poll(now);
                                if (v != null) {
                                    doApply = true;
                                    value = v.booleanValue();
                                }
                            }
                            if (doApply) {
                                if (value) {
                                    if (lastOnAt >= 0L) {
                                        Assert.isTrue(now - lastOnAt >= 1000L,
                                                "two mounts only " + (now - lastOnAt)
                                                        + " ms apart");
                                    }
                                    appliedOn++;
                                    lastOnAt = now;
                                }
                                s.transitionApplied(value, now);
                            }
                            now += 20L;
                        }
                        Assert.isTrue(appliedOn <= 7, "6 s of alternating requests mounted "
                                + appliedOn + " times (at most once per interval allowed)");
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
                        // The old App mirrored the state back and forth every 20 ms. Offs are
                        // immediate (unload must be), but the on direction stays rate limited:
                        // over 6 s the daemon can flip at most twice per interval (one mount +
                        // the immediate unload that follows it).
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
                        Assert.isTrue(transitions <= 15,
                                "6 s of alternating requests caused " + transitions
                                        + " transitions (must stay bounded)");
                    }
                });
    }
}
