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

        suite.test("watcher policy: a detected change runs a pass immediately", () -> {
            long now = 2000000L;
            long justRan = now - 1L;
            Assert.isTrue(!WatcherPolicy.fallbackDue(true, now, justRan),
                    "the 3 s deadline has not passed");
            Assert.isTrue(WatcherPolicy.shouldRunPass(false,
                            WatcherPolicy.fallbackDue(true, now, justRan), true),
                    "a changed probe signature runs the pass without waiting for the deadline");
        });

        suite.test("watcher policy: probe unavailable means a full pass every LEGACY_POLL_MS",
                () -> {
            long now = 3000000L;
            Assert.eq(WatcherPolicy.LEGACY_POLL_MS, WatcherPolicy.fullPassPeriodMs(false),
                    "degraded period is the pre-event-driven 500 ms");
            Assert.eq(WatcherPolicy.FALLBACK_MS, WatcherPolicy.fullPassPeriodMs(true),
                    "with the probe the fallback stays at 3 s");
            long lastPass = now - WatcherPolicy.LEGACY_POLL_MS;
            Assert.isTrue(WatcherPolicy.fallbackDue(false, now, lastPass),
                    "500 ms after the last pass a new one is due while the probe is unavailable");
            Assert.isTrue(!WatcherPolicy.fallbackDue(true, now, lastPass),
                    "the same instant is not due when the probe drives the loop");
            Assert.isTrue(!WatcherPolicy.fallbackDue(false, now,
                            now - WatcherPolicy.LEGACY_POLL_MS + 1L),
                    "degraded polling is not tighter than LEGACY_POLL_MS");
            Assert.isTrue(WatcherPolicy.fullPassPeriodMs(false) < WatcherPolicy.FALLBACK_MS,
                    "degraded must be faster than the event-driven fallback, never slower");
        });

        suite.test("watcher policy: the probe cadence is relaxed while nothing plays", () -> {
            Assert.eq(WatcherPolicy.PROBE_MS, WatcherPolicy.probeSleepMs(true),
                    "playing: the cheap query runs every 100 ms");
            Assert.eq(WatcherPolicy.PROBE_IDLE_MS, WatcherPolicy.probeSleepMs(false),
                    "idle: only the isStreamActive gate runs, no dump is due anyway");
            Assert.isTrue(WatcherPolicy.PROBE_IDLE_MS > WatcherPolicy.PROBE_MS,
                    "idle polling must be the cheaper one");
            Assert.isTrue(WatcherPolicy.PROBE_IDLE_MS <= WatcherPolicy.LEGACY_POLL_MS,
                    "even the idle gate is not slower than the degraded dump");
        });

        suite.test("watcher policy: the dumpsys cross-check runs once per CROSSCHECK_MS", () -> {
            long now = 4000000L;
            Assert.isTrue(WatcherPolicy.crossCheckDue(now, 0L), "no cross-check yet: due");
            Assert.isTrue(!WatcherPolicy.crossCheckDue(now, now), "one just ran");
            Assert.isTrue(!WatcherPolicy.crossCheckDue(now,
                            now - WatcherPolicy.CROSSCHECK_MS + 1L), "just before the deadline");
            Assert.isTrue(WatcherPolicy.crossCheckDue(now,
                            now - WatcherPolicy.CROSSCHECK_MS), "at the deadline");
            Assert.isTrue(WatcherPolicy.CROSSCHECK_MS > WatcherPolicy.FALLBACK_MS,
                    "steady state must dump far less often than the fallback");
        });

        suite.test("watcher policy: maintenance runs on its own timer", () -> {
            long now = 5000000L;
            Assert.isTrue(WatcherPolicy.maintenanceDue(now, 0L), "no maintenance yet: due");
            Assert.isTrue(!WatcherPolicy.maintenanceDue(now,
                            now - WatcherPolicy.MAINTENANCE_MS + 1L), "just before the deadline");
            Assert.isTrue(WatcherPolicy.maintenanceDue(now,
                            now - WatcherPolicy.MAINTENANCE_MS), "at the deadline");
            Assert.isTrue(WatcherPolicy.MAINTENANCE_MS <= WatcherPolicy.FALLBACK_MS,
                    "the config flush must not wait for a full pass");
        });

        suite.test("watcher policy: the degraded ladder backs off, never delays the first session",
                () -> {
            Assert.eq(WatcherPolicy.FALLBACK_MS, WatcherPolicy.degradedFallbackMs(0),
                    "no session known yet: dense rung, a new player is discovered at once");
            Assert.eq(WatcherPolicy.FALLBACK_MS,
                    WatcherPolicy.degradedFallbackMs(WatcherPolicy.DEGRADED_STABLE_PASSES - 1),
                    "fewer than DEGRADED_STABLE_PASSES unchanged passes: still dense");
            Assert.eq(15000L,
                    WatcherPolicy.degradedFallbackMs(WatcherPolicy.DEGRADED_STABLE_PASSES),
                    "first back-off rung");
            Assert.eq(WatcherPolicy.DEGRADED_MAX_MS,
                    WatcherPolicy.degradedFallbackMs(
                            WatcherPolicy.DEGRADED_STABLE_PASSES * 2), "cap rung");
            Assert.eq(WatcherPolicy.DEGRADED_MAX_MS, WatcherPolicy.degradedFallbackMs(10000),
                    "the ladder is clamped at the cap");
            Assert.eq(WatcherPolicy.FALLBACK_MS, WatcherPolicy.degradedFallbackMs(0, 0L),
                    "a cap below the dense rung is floored to FALLBACK_MS");
            Assert.eq(15000L, WatcherPolicy.degradedFallbackMs(
                            WatcherPolicy.DEGRADED_STABLE_PASSES, 20000L), "explicit cap");
            Assert.isTrue(WatcherPolicy.degradedFallbackMs(0)
                            < WatcherPolicy.degradedFallbackMs(10000),
                    "back-off only ever loosens, never tightens");

            long now = 6000000L;
            Assert.isTrue(WatcherPolicy.fallbackDue(now, 0L, 15000L),
                    "no pass yet: due whatever the rung");
            Assert.isTrue(!WatcherPolicy.fallbackDue(now, now - 14000L, 15000L),
                    "before the rung period");
            Assert.isTrue(WatcherPolicy.fallbackDue(now, now - 15000L, 15000L),
                    "at the rung period");
            Assert.isTrue(WatcherPolicy.fallbackDue(now, now - 1L, 0L),
                    "a zero period is clamped, never a busy loop");
        });
        suite.test("watcher policy: the degraded ladder ignores cheap passes", () -> {
            // A cheap pass says nothing about whether a dump is worth running: a native player
            // flapping in and out of the playback-configuration list must not pin it to 3 s.
            Assert.eq(5, WatcherPolicy.nextStablePasses(false, true, 5),
                    "cheap pass: the ladder is left alone");
            Assert.eq(2, WatcherPolicy.nextStablePasses(true, false, 1), "full, same set: +1");
            Assert.eq(0, WatcherPolicy.nextStablePasses(true, true, 7), "full, new set: reset");
        });
        suite.test("watcher policy: the blind ladder is capped tighter than the degraded one", () -> {
            Assert.isTrue(WatcherPolicy.BLIND_MAX_MS <= WatcherPolicy.DEGRADED_MAX_MS,
                    "blind: never looser than degraded");
            Assert.isTrue(WatcherPolicy.BLIND_MAX_MS > WatcherPolicy.FALLBACK_MS,
                    "blind: still backs off from the dense rung");
            Assert.eq(WatcherPolicy.BLIND_MAX_MS, WatcherPolicy.degradedFallbackMs(10000,
                    WatcherPolicy.BLIND_MAX_MS), "blind: clamped at 15 s");
            Assert.isTrue(WatcherPolicy.GATE_HOLD_MS >= WatcherPolicy.PROBE_IDLE_MS,
                    "the gate verdict outlives one idle tick");
        });
        suite.test("watcher policy: the idle gate only re-runs the query every IDLE_QUERY_MS",
                new com.jdsp.cli.Body() {
            public void run() {
                long now = 5000000L;
                Assert.isTrue(WatcherPolicy.idleQueryDue(now, 0L),
                        "never queried since start: due");
                Assert.isTrue(!WatcherPolicy.idleQueryDue(now, now + 1L),
                        "before the safety deadline: the isStreamActive gate is enough");
                Assert.isTrue(WatcherPolicy.idleQueryDue(now, now),
                        "at the safety deadline: due");
                Assert.isTrue(WatcherPolicy.IDLE_QUERY_MS >= WatcherPolicy.PROBE_IDLE_MS,
                        "the safety query is never denser than the idle gate cadence");
                Assert.isTrue(WatcherPolicy.IDLE_QUERY_MS < WatcherPolicy.CROSSCHECK_MS,
                        "the idle safety query is far denser than the dump cross-check");
            }
        });
    }
}
