package com.jdsp.common;

/**
 * Pure decision helpers for the session watcher's event-driven loop.
 *
 * <p>The daemon used to poll {@code dumpsys media.audio_flinger} every {@value #LEGACY_POLL_MS} ms
 * unconditionally: one 78 KB dump plus a {@code sh -c} fork every tick, parsed and reconciled
 * against the effect handles. Measured on the target device that is 28-150 ms of work per tick,
 * i.e. a noticeable slice of one core, and it is the reason a new playback session took up to
 * ~1 s to be attached.
 *
 * <p>The loop now runs on three triggers:
 * <ol>
 *   <li><b>poke</b> - an explicit request from the daemon (policy change, mode change, attach /
 *       detach command): run a pass immediately;</li>
 *   <li><b>cheap binder signature change</b> - a {@code getActivePlaybackConfigurations()} query
 *       costs 64-93 us on the target device (measured) and tells us that the set of active players
 *       changed, so a dumpsys pass is worth it; the query itself sleeps
 *       {@value #PROBE_MS} ms between calls;</li>
 *   <li><b>fallback timer</b> - a full pass every {@value #FALLBACK_MS} ms regardless, so a change
 *       the cheap query cannot see (the query has no session id, and a session can be replaced
 *       with the same uid/pid/state) is still reconciled. It also carries the non hot work:
 *       flushing the config and the deferred global switch safety net.</li>
 * </ol>
 *
 * <p>The cheap query can also be unavailable (ROM without the hidden
 * {@code IAudioService.getActivePlaybackConfigurations} transaction, or a SELinux denial). Then
 * the loop degrades to the timed {@value #LEGACY_POLL_MS} ms dump, i.e. exactly the old
 * behaviour - event driven is an optimisation, never a requirement.
 *
 * <p>Pure Java on purpose: the decision table is host tested
 * (see {@code backend/test/java/com/jdsp/common/WatcherPolicyTest.java}).
 */
public final class WatcherPolicy {
    /** How often the cheap binder change detector runs while nothing else happens. */
    public static final long PROBE_MS = 100L;
    /** Full dumpsys pass period, the safety net behind the change detector. */
    public static final long FALLBACK_MS = 3000L;
    /** Coalescing window after a detected change (a burst of player events is one pass). */
    public static final long CHANGE_DEBOUNCE_MS = 100L;
    /** Timed dumpsys period used when the cheap change detector is unavailable. */
    public static final long LEGACY_POLL_MS = 500L;

    private WatcherPolicy() {
    }

    /** True when this iteration has to run a full pass. */
    public static boolean shouldRunPass(boolean poke, boolean fallbackDue, boolean changed) {
        return poke || fallbackDue || changed;
    }

    /** True when the fallback pass is due (also true when no pass ran yet). */
    public static boolean fallbackDue(long now, long lastPassAt) {
        return lastPassAt <= 0L || now - lastPassAt >= FALLBACK_MS;
    }

    /** How long the loop may sleep before it re-evaluates its triggers. */
    public static long nextSleepMs(boolean probeAvailable) {
        return probeAvailable ? PROBE_MS : LEGACY_POLL_MS;
    }
}
