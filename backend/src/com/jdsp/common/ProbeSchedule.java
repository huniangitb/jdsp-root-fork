package com.jdsp.common;

/**
 * Retry schedule for the watcher's cheap playback-set probe (v2.1.8).
 *
 * <p>The probe needs the hidden {@code IAudioService.getActivePlaybackConfigurations()}. v2.1.7
 * cached the first failure forever - and the daemon starts before the audio service is
 * registered during boot, so the very first attempt failed with "audio service not found" and
 * the probe stayed off for the whole boot. The watcher then fell back to its timed dump, where a
 * new playback session waited for the 3 s fallback pass instead of the ~100 ms probe cadence
 * (measured on the target device: 2.5-3.1 s attach after a normal boot, v2.1.6 was 1.3 s).
 *
 * <p>Here a failed setup is retried instead of remembered: a new attempt is made every
 * {@link #RETRY_MS} ms until it succeeds, and the first success logs how many retries it took.
 *
 * <p>Pure Java on purpose - the decision table is host tested (see
 * {@code backend/test/java/com/jdsp/common/ProbeScheduleTest.java}).
 */
public final class ProbeSchedule {
    /** Delay between setup attempts after a failure (also used after a runtime failure). */
    public static final long RETRY_MS = 5000L;

    private ProbeSchedule() {
    }

    /** True when a new setup attempt is due; the first one always is. */
    public static boolean attemptDue(long now, long lastAttemptAt) {
        return lastAttemptAt <= 0L || now - lastAttemptAt >= RETRY_MS;
    }

    /** Log suffix for a successful setup (" after 3 retries"), empty on the first try. */
    public static String retryNote(int failures) {
        if (failures <= 0) {
            return "";
        }
        return " after " + failures + (failures == 1 ? " retry" : " retries");
    }
}
