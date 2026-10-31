package com.jdsp.common;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;

/**
 * Host tests for the session watcher's event-driven decision table: a full (expensive) dumpsys
 * pass may only run when something asked for it - a poke, a detected change of the active
 * playback set, or the fallback deadline - never on a fixed tick.
 */
public final class WatcherPolicyTest {
    private WatcherPolicyTest() {}

    public static void run(Suite suite) {
        suite.test("watcher policy: a pass runs on poke, change or fallback only",
                new com.jdsp.cli.Body() {
            public void run() {
                Assert.isTrue(WatcherPolicy.shouldRunPass(true, false, false), "poke");
                Assert.isTrue(WatcherPolicy.shouldRunPass(false, true, false), "fallback");
                Assert.isTrue(WatcherPolicy.shouldRunPass(false, false, true), "change");
                Assert.isTrue(WatcherPolicy.shouldRunPass(true, true, true), "all triggers");
                Assert.isTrue(!WatcherPolicy.shouldRunPass(false, false, false),
                        "idle iteration must not run a dumpsys pass");
            }
        });
        suite.test("watcher policy: the fallback deadline is due immediately at start",
                new com.jdsp.cli.Body() {
            public void run() {
                long now = 1000000L;
                Assert.isTrue(WatcherPolicy.fallbackDue(now, 0L),
                        "no pass yet -> the first pass is due");
                Assert.isTrue(!WatcherPolicy.fallbackDue(now, now),
                        "a pass that just ran is not due again");
                Assert.isTrue(!WatcherPolicy.fallbackDue(now,
                                now - WatcherPolicy.FALLBACK_MS + 1L),
                        "just before the deadline: not due");
                Assert.isTrue(WatcherPolicy.fallbackDue(now,
                                now - WatcherPolicy.FALLBACK_MS),
                        "at the deadline: due");
            }
        });
        suite.test("watcher policy: the sleep respects the probe availability",
                new com.jdsp.cli.Body() {
            public void run() {
                Assert.eq(WatcherPolicy.PROBE_MS, WatcherPolicy.nextSleepMs(true),
                        "with the cheap probe the loop wakes every PROBE_MS");
                Assert.eq(WatcherPolicy.LEGACY_POLL_MS, WatcherPolicy.nextSleepMs(false),
                        "without it the loop degrades to the old timed dump");
                Assert.isTrue(WatcherPolicy.PROBE_MS < WatcherPolicy.LEGACY_POLL_MS,
                        "the probe cadence is the cheaper one");
                Assert.isTrue(WatcherPolicy.FALLBACK_MS > WatcherPolicy.PROBE_MS,
                        "the fallback is slower than the probe");
                Assert.isTrue(WatcherPolicy.CHANGE_DEBOUNCE_MS > 0L,
                        "a detected change is coalesced before the pass");
            }
        });
    }
}
