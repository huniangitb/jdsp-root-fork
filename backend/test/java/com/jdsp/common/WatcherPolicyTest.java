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
        suite.test("watcher policy: a stale session is confirmed shortly after it goes silent", () -> {
            // Attached but nothing plays: jdspd's own effect handle may outlive a player that died
            // without closing its track. The first dump has to come soon (that is the whole point of
            // the trigger), it must not fire before the dwell, and it must not repeat per tick.
            long now = 9000000L;
            long armed = now - WatcherPolicy.CONFIRM_DWELL_MS + 1L;
            Assert.isTrue(!WatcherPolicy.confirmDue(now, armed, 0L, 4000L),
                    "not armed long enough: no dump");
            Assert.isTrue(WatcherPolicy.confirmDue(now, now - WatcherPolicy.CONFIRM_DWELL_MS,
                    0L, 4000L), "dwell elapsed: one dump");
            Assert.isTrue(!WatcherPolicy.confirmDue(now, 0L, 0L, 4000L),
                    "not armed at all: no dump");
            Assert.isTrue(WatcherPolicy.CONFIRM_DWELL_MS < WatcherPolicy.CROSSCHECK_MS,
                    "the confirmation is orders of magnitude tighter than the cross-check");
            // A session that survives its confirmation dump is real (a paused native player), so the
            // repeats must back off instead of dumping on every iteration.
            Assert.isTrue(!WatcherPolicy.confirmDue(now, now - 60000L, now - 1000L, 4000L),
                    "a confirmation that just ran is not due again");
            Assert.isTrue(WatcherPolicy.confirmDue(now, now - 60000L, now - 4000L, 4000L),
                    "back-off elapsed: retry is due");
        });
        suite.test("watcher policy: the confirmation back-off doubles up to its ceiling", () -> {
            Assert.eq(WatcherPolicy.CONFIRM_BACKOFF_START_MS,
                    WatcherPolicy.nextConfirmBackoff(0L), "invalid input: start value");
            long ms = WatcherPolicy.CONFIRM_BACKOFF_START_MS;
            ms = WatcherPolicy.nextConfirmBackoff(ms);
            Assert.eq(WatcherPolicy.CONFIRM_BACKOFF_START_MS * 2L, ms, "first retry doubles");
            for (int i = 0; i < 20; i++) {
                ms = WatcherPolicy.nextConfirmBackoff(ms);
            }
            Assert.eq(WatcherPolicy.CONFIRM_BACKOFF_MAX_MS, ms, "clamped at the ceiling");
            Assert.isTrue(WatcherPolicy.CONFIRM_BACKOFF_MAX_MS < WatcherPolicy.CROSSCHECK_MS,
                    "the ceiling stays far below the five minute cross-check");
            Assert.isTrue(WatcherPolicy.CONFIRM_BACKOFF_START_MS > WatcherPolicy.PROBE_IDLE_MS,
                    "no retry denser than the idle probe cadence");
        });
        suite.test("watcher policy: a failing dump is retried with a back-off, never in a hot loop", () -> {
            // Nothing is recorded when a dump fails, so the pass scheduler stays "due": without a
            // back-off the loop forks one dumpsys per iteration (90-120 ms each).
            Assert.isTrue(WatcherPolicy.nextDumpRetryMs(1) == 3000L, "first retry is 3 s");
            Assert.isTrue(WatcherPolicy.nextDumpRetryMs(2) == 6000L, "second retry doubles");
            Assert.isTrue(WatcherPolicy.nextDumpRetryMs(3) == 12000L, "third retry doubles");
            Assert.isTrue(WatcherPolicy.nextDumpRetryMs(4) == 24000L, "fourth retry doubles");
            Assert.isTrue(WatcherPolicy.nextDumpRetryMs(5) == 48000L, "fifth retry doubles");
            Assert.isTrue(WatcherPolicy.nextDumpRetryMs(6) == 60000L, "capped at a minute");
            Assert.isTrue(WatcherPolicy.nextDumpRetryMs(60) == 60000L, "cap is stable");
            Assert.isTrue(WatcherPolicy.nextDumpRetryMs(0) == 3000L, "a zero streak behaves like one");
        });
        suite.test("watcher policy: a truncated or failed dump is never parsed", () -> {
            // An incomplete dump parses to an empty session list, and an empty list makes reconcile
            // release every handle it holds (audible gap). Every incomplete outcome must be rejected.
            Assert.isTrue(WatcherPolicy.dumpProblem(0, false, 4096, true) == null,
                    "complete dump: usable");
            Assert.isTrue("dumpsys output too large".equals(
                    WatcherPolicy.dumpProblem(0, true, 4096, true)), "size cap: rejected");
            Assert.isTrue(WatcherPolicy.dumpProblem(137, false, 4096, true) != null,
                    "killed by the watchdog: rejected");
            Assert.isTrue(WatcherPolicy.dumpProblem(0, false, 0, false) != null,
                    "no output: rejected");
            Assert.isTrue(WatcherPolicy.dumpProblem(0, false, 4096, false) != null,
                    "no output-thread section: rejected");
            Assert.isTrue("dumpsys output too large".equals(
                    WatcherPolicy.dumpProblem(1, true, 0, false)),
                    "the size cap is reported first (it is the actionable cause)");
        });
        suite.test("watcher policy: an unattachable dump-only session is parked, not retried", () -> {
            // A dump-only session whose attach never produces a handle used to be re-dumped once per
            // second forever: the miss counter was reset on every give-up, so the loop restarted. The
            // park delay doubles up to its ceiling, so the worst case settles at one round per 5 min.
            Assert.eq(WatcherPolicy.ATTACH_GIVEUP_START_MS, WatcherPolicy.nextAttachGiveUpMs(0L),
                    "invalid input: start value");
            long park = WatcherPolicy.nextAttachGiveUpMs(WatcherPolicy.ATTACH_GIVEUP_START_MS);
            Assert.eq(WatcherPolicy.ATTACH_GIVEUP_START_MS * 2L, park, "first park doubles");
            for (int i = 0; i < 20; i++) {
                park = WatcherPolicy.nextAttachGiveUpMs(park);
            }
            Assert.eq(WatcherPolicy.ATTACH_GIVEUP_MAX_MS, park, "clamped at the ceiling");
            Assert.isTrue(WatcherPolicy.ATTACH_GIVEUP_MAX_MS >= WatcherPolicy.CROSSCHECK_MS / 2L,
                    "never more dumps than the cross-check for an unattachable session");
            Assert.isTrue(WatcherPolicy.ATTACH_DUMP_MIN_MS > 0L, "the trigger is rate limited");
            Assert.isTrue(WatcherPolicy.ATTACH_DUMP_MAX_MISSES > 0,
                    "give up after a bounded number of dumps");
        });
        suite.test("watcher policy: a failed attach backs off, the confirmation stops repeating", () -> {
            Assert.eq(WatcherPolicy.ATTACH_RETRY_START_MS, WatcherPolicy.nextAttachRetryMs(0L),
                    "invalid input: start value");
            long retry = WatcherPolicy.ATTACH_RETRY_START_MS;
            for (int i = 0; i < 20; i++) {
                retry = WatcherPolicy.nextAttachRetryMs(retry);
            }
            Assert.eq(WatcherPolicy.ATTACH_RETRY_MAX_MS, retry, "clamped at the ceiling");
            Assert.isTrue(WatcherPolicy.ATTACH_RETRY_START_MS < WatcherPolicy.ATTACH_RETRY_MAX_MS,
                    "the retry ladder really climbs");
            Assert.isTrue(!WatcherPolicy.confirmExhausted(0), "a fresh state still confirms");
            Assert.isTrue(!WatcherPolicy.confirmExhausted(WatcherPolicy.CONFIRM_MAX_REPEATS - 1),
                    "the last repeat is still allowed");
            Assert.isTrue(WatcherPolicy.confirmExhausted(WatcherPolicy.CONFIRM_MAX_REPEATS),
                    "the ceiling stops the repeats");
        });
        suite.test("watcher policy: a dump-only session keeps its attach debounce stamp", () -> {
            // The cheap playback query cannot see a native player, so its session list is empty on
            // every cheap pass. Forgetting the stamp there restarted the debounce window on every
            // dump and the session was never attached (dump sees it, cheap pass forgets it, ...).
            Assert.isTrue(WatcherPolicy.keepFirstSeen(true, false), "seen in this pass: keep");
            Assert.isTrue(WatcherPolicy.keepFirstSeen(true, true), "seen and dump-only: keep");
            Assert.isTrue(WatcherPolicy.keepFirstSeen(false, true),
                    "invisible to the probe but real: keep the stamp");
            Assert.isTrue(!WatcherPolicy.keepFirstSeen(false, false),
                    "vanished and not dump-only: forget (fresh window on re-appearance)");
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
