package com.jdsp.common;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;

/**
 * Host tests for the probe retry schedule (v2.1.8). The daemon starts before the audio service is
 * registered on a normal boot, so the first setup attempt fails with "audio service not found";
 * the schedule must therefore retry instead of caching the failure (v2.1.7 cached it and spent the
 * whole boot in the 3 s fallback, measured as a 2.5-3.1 s attach latency).
 */
public final class ProbeScheduleTest {
    private ProbeScheduleTest() {}

    public static void run(Suite suite) {
        suite.test("probe schedule: the first setup attempt is always due", () -> {
            Assert.isTrue(ProbeSchedule.attemptDue(1000L, 0L), "no attempt yet");
            Assert.isTrue(ProbeSchedule.attemptDue(1000L, -1L), "negative marker treated as none");
        });

        suite.test("probe schedule: a failure is retried after RETRY_MS, not before", () -> {
            long last = 5000L;
            Assert.isTrue(!ProbeSchedule.attemptDue(last + ProbeSchedule.RETRY_MS - 1L, last),
                    "just before the retry delay: no new attempt");
            Assert.isTrue(ProbeSchedule.attemptDue(last + ProbeSchedule.RETRY_MS, last),
                    "at the retry delay: attempt again");
            Assert.isTrue(ProbeSchedule.attemptDue(last + 10L * ProbeSchedule.RETRY_MS, last),
                    "long after: still retrying");
            Assert.isTrue(ProbeSchedule.RETRY_MS >= 5000L && ProbeSchedule.RETRY_MS <= 10000L,
                    "the retry delay sits in the agreed 5-10 s window");
        });

        suite.test("probe schedule: the success log names the number of retries", () -> {
            Assert.eq("", ProbeSchedule.retryNote(0), "first try: no suffix");
            Assert.eq("", ProbeSchedule.retryNote(-1), "garbage in, no suffix");
            Assert.eq(" after 1 retry", ProbeSchedule.retryNote(1), "singular");
            Assert.eq(" after 3 retries", ProbeSchedule.retryNote(3), "plural");
        });
    }
}
