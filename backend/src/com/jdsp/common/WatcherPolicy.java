package com.jdsp.common;

/**
 * Pure decision helpers for the session watcher's event-driven loop.
 *
 * <p>The daemon used to poll {@code dumpsys media.audio_flinger} every {@value #LEGACY_POLL_MS} ms
 * unconditionally: one 47-78 KB dump plus a {@code sh -c} fork every tick, parsed and reconciled
 * against the effect handles. Measured on the target device that is 28-150 ms of work per tick,
 * i.e. a noticeable slice of one core, and it is the reason a new playback session took up to
 * ~1 s to be attached.
 *
 * <p><b>v2.1.9: the cheap {@code IAudioService} query is now the primary discovery path.</b>
 * {@code getActivePlaybackConfigurations()} returns one {@code AudioPlaybackConfiguration} per
 * registered player and - for a privileged caller such as this uid 0 daemon - the real
 * {@code AudioPlaybackConfiguration.getSessionId()} (both are {@code @hide}/{@code @SystemApi},
 * present since Android 12; {@code AudioService} anonymises the session id for unprivileged
 * callers only). So one binder call yields the attachable (sessionId, uid, pid) set and a full
 * dumpsys is no longer needed to *discover* sessions - only to cross-check the cheap view or to
 * stand in for it when it is unavailable:
 * <ol>
 *   <li><b>poke</b> - an explicit request from the daemon (policy change, mode change, attach /
 *       detach command): run a pass immediately;</li>
 *   <li><b>cheap query</b> - polled every {@value #PROBE_MS} ms while something plays and every
 *       {@value #PROBE_IDLE_MS} ms while nothing does; a changed signature runs a cheap
 *       (probe-sourced) pass right away. While nothing plays and no session is attached the query
 *       itself is only re-run every {@value #IDLE_QUERY_MS} ms (a ~10 us
 *       {@code AudioSystem.isStreamActive(MUSIC)} gate covers the ticks in between) - an idle
 *       daemon must not pay for a binder round trip plus a parcel per tick;</li>
 *   <li><b>cross-check timer</b> - one full dumpsys every {@value #CROSSCHECK_MS} ms, so a
 *       session the cheap view cannot see (no registered player, e.g. an effect chain that
 *       outlived its track) is still reconciled. Steady state: one dumpsys per five minutes
 *       instead of 0.32/s in v2.1.8 and 2/s in v2.1.6;</li>
 *   <li><b>blind player</b> - the query only lists players that registered with AudioService. A
 *       native player (AAudio / OpenSL ES, i.e. most games) never does, so while the music stream
 *       is active and either no registered player reports "playing" or no row carries a real
 *       session id (anonymised caller), discovery is driven by the dump on the dense rung -
 *       otherwise a game would keep its (or lose its) effect chain only at the next cross-check;</li>
 *   <li><b>degraded timer</b> - when the cheap query is unusable (old ROM, SELinux denial,
 *       anonymised session ids) the loop falls back to the timed dump of the pre-event-driven
 *       daemon ({@value #LEGACY_POLL_MS} ms) and escalates to {@value #FALLBACK_MS} /
 *       15000 / {@value #DEGRADED_MAX_MS} ms while the session set stays unchanged;</li>
 *   <li><b>maintenance timer</b> - every {@value #MAINTENANCE_MS} ms the non hot work runs
 *       (config flush request, deferred switch safety net, endpoint refresh), independent of
 *       whether the pass was cheap or expensive.</li>
 * </ol>
 *
 * <p>Pure Java on purpose: the decision table is host tested
 * (see {@code backend/test/java/com/jdsp/common/WatcherPolicyTest.java}).
 */
public final class WatcherPolicy {
    /** Cheap query cadence while something plays (v2.1.8: also while idle). */
    public static final long PROBE_MS = 100L;
    /**
     * Cheap query cadence in auto ("traditional") mode, where the framework creates the effect
     * instance itself at track creation and the engine restores its own parameter state.
     *
     * <p>There is nothing for the daemon to <em>discover</em> at 10 Hz: a new track already runs
     * with the last applied parameters (restored from the engine state file), and jdspd only has
     * to join the instance lazily to apply <em>new</em> user edits and to notice a vanished
     * session. Measured on the target device the playback-time cost of the 10 Hz query is the
     * dominant daemon CPU (1.05 % of one core, of which ~0.9-1.0 % is the query itself); at 2 Hz
     * the same bookkeeping costs a quarter of that, and an App poke (a slider frame) still wakes
     * the loop immediately, so live parameter latency is unchanged.
     */
    public static final long AUTO_PROBE_MS = 500L;
    /**
     * Idle gate cadence. The gate itself is a binder call to AudioFlinger (~0.3-0.4 ms measured on
     * the target device; the playback-configuration query is 1-2.8 ms because it also parcels a
     * config per player and builds a {@code List}), so at 4 Hz the gate alone was 0.16 % of one
     * core - more than the whole budget. 2 Hz keeps a new stream's discovery inside one debounce
     * window while costing half.
     */
    public static final long PROBE_IDLE_MS = 500L;
    /** Degraded full-pass period once the session set has been stable for a while. */
    public static final long FALLBACK_MS = 3000L;
    /** Coalescing window after a detected change (a burst of player events is one pass). */
    public static final long CHANGE_DEBOUNCE_MS = 100L;
    /** Timed dumpsys period used when the cheap query is unavailable (pre-event-driven daemon). */
    public static final long LEGACY_POLL_MS = 500L;
    /** Cross-check period when the cheap query is authoritative (one dump per five minutes). */
    public static final long CROSSCHECK_MS = 300000L;
    /** Non hot maintenance period (config flush, deferred switch, endpoint files). */
    public static final long MAINTENANCE_MS = 3000L;
    /** Hard cap of the degraded ladder. */
    public static final long DEGRADED_MAX_MS = 30000L;
    /** Unchanged degraded passes per ladder rung. */
    public static final int DEGRADED_STABLE_PASSES = 3;
    /**
     * Idle safety query period: while {@code AudioSystem.isStreamActive(STREAM_MUSIC)} is false and
     * no session is attached, nothing can be playing, so the playback-configuration query (one
     * binder round trip plus a parcel and a {@code List} of configs per player, measured 1-2.8 ms
     * on the target device) is only re-run this often. The {@code isStreamActive} gate is one
     * binder call to AudioFlinger with no parcel and no allocation (~0.3-0.4 ms).
     */
    public static final long IDLE_QUERY_MS = 5000L;
    /**
     * How long a "music is playing" verdict from the {@code isStreamActive} gate is held after the
     * gate last returned true. The gate can dip between two loop iterations (a track that has not
     * been fed yet, a silent stream); without the hold every dip restarts the blind ladder on the
     * dense rung.
     */
    public static final long GATE_HOLD_MS = 3000L;
    /**
     * Ladder cap while the cheap view is blind (a native player nothing can attach): 15 s instead of
     * the full 30 s, because a session that is attachable but invisible to the probe should never
     * wait half a minute for its effect chain.
     */
    public static final long BLIND_MAX_MS = 15000L;
    /**
     * Dwell of "sessions are attached but nothing plays" before one dump is spent to confirm it.
     * A player that dies without closing its track (SIGTERM, crash, kernel kill) leaves jdspd's own
     * {@code AudioEffect} handle behind in audioserver: the dump still lists the session while the
     * cheap playback query no longer knows the player at all. The cheap pass deliberately keeps
     * dump-only sessions alive (that is what keeps native players working), so without this trigger
     * the dead session kept its effect until the {@link #CROSSCHECK_MS} cross-check - measured
     * 5 min 40 s of an attached bypassed effect and ~1 % CPU while the probe polled at 10 Hz.
     */
    public static final long CONFIRM_DWELL_MS = 1500L;
    /**
     * First retry delay after a confirmation dump that did <em>not</em> retire the session. That
     * dump is the answer to "is this session real?": a paused native player legitimately keeps its
     * track open while nothing plays, so the session survives and the next check has to back off.
     */
    public static final long CONFIRM_BACKOFF_START_MS = 4000L;
    /**
     * Ceiling of the confirmation back-off. Deliberately generous: the interesting case is decided
     * by the first dump ({@link #CONFIRM_DWELL_MS} after the state appears); the repeats only exist
     * so a session that dies <em>while</em> the state persists is still retired without the
     * five-minute cross-check. At this period a paused native player costs one dump per minute.
     */
    public static final long CONFIRM_BACKOFF_MAX_MS = 60000L;

    /** Degraded ladder: dense first, then two back-off rungs. */
    private static final long[] DEGRADED_TIERS = {3000L, 15000L, 30000L};

    private WatcherPolicy() {
    }

    /** True when this iteration has to run a full pass. */
    public static boolean shouldRunPass(boolean poke, boolean fallbackDue, boolean changed) {
        return poke || fallbackDue || changed;
    }

    /**
     * Period of a full (expensive) pass: {@value #FALLBACK_MS} ms with the cheap probe, but
     * {@value #LEGACY_POLL_MS} ms without it. v2.1.7 kept the 3 s deadline in the degraded case
     * while its log claimed 500 ms, so a normal boot - where the probe setup raced the audio
     * service and the failure was cached forever - polled every three seconds and a new playback
     * session waited 2.5-3.1 s for its attach (v2.1.6: 1.3 s). Degraded must never be slower than
     * the pre-event-driven daemon.
     */
    public static long fullPassPeriodMs(boolean probeAvailable) {
        return probeAvailable ? FALLBACK_MS : LEGACY_POLL_MS;
    }

    /** True when the fallback pass is due (also true when no pass ran yet). */
    public static boolean fallbackDue(boolean probeAvailable, long now, long lastPassAt) {
        return lastPassAt <= 0L || now - lastPassAt >= fullPassPeriodMs(probeAvailable);
    }

    /** Fallback deadline assuming the cheap probe is available. */
    public static boolean fallbackDue(long now, long lastPassAt) {
        return fallbackDue(true, now, lastPassAt);
    }

    /** Fallback deadline with an explicit period (degraded ladder). */
    public static boolean fallbackDue(long now, long lastPassAt, long periodMs) {
        return lastPassAt <= 0L || now - lastPassAt >= Math.max(1L, periodMs);
    }

    /** How long the loop may sleep before it re-evaluates its triggers. */
    public static long nextSleepMs(boolean probeAvailable) {
        return probeAvailable ? PROBE_MS : LEGACY_POLL_MS;
    }

    /** Cheap query cadence: fast while audio plays, relaxed while nothing does. */
    public static long probeSleepMs(boolean playing) {
        return probeSleepMs(playing, false);
    }

    /**
     * Cheap query cadence with the processing mode taken into account. Backend (per-app) mode
     * keeps the dense rung - there the daemon itself builds the chain, so a late discovery is a
     * late effect. Auto mode runs on {@link #AUTO_PROBE_MS}: see there.
     */
    public static long probeSleepMs(boolean playing, boolean autoMode) {
        if (autoMode) {
            return AUTO_PROBE_MS;
        }
        return playing ? PROBE_MS : PROBE_IDLE_MS;
    }

    /**
     * True when the probe-sourced session set has to be cross-checked against a real dump.
     * True before the first cross-check (a boot baseline is worth one dump) and then once per
     * {@value #CROSSCHECK_MS} ms.
     */
    public static boolean crossCheckDue(long now, long lastCrosscheckAt) {
        return lastCrosscheckAt <= 0L || now - lastCrosscheckAt >= CROSSCHECK_MS;
    }

    /**
     * True when a confirmation dump is due.
     *
     * @param armedAt        when "attached but nothing plays" was first seen, 0 = not armed
     * @param lastConfirmAt  when the last confirmation dump ran, 0 = none yet
     * @param backoffMs      current retry delay (only used once a confirmation dump ran)
     */
    public static boolean confirmDue(long now, long armedAt, long lastConfirmAt, long backoffMs) {
        if (armedAt <= 0L) {
            return false;
        }
        if (lastConfirmAt <= 0L) {
            return now - armedAt >= CONFIRM_DWELL_MS;
        }
        return now - lastConfirmAt >= Math.max(1L, backoffMs);
    }

    /**
     * Next retry delay after a confirmation dump: start at {@link #CONFIRM_BACKOFF_START_MS} and
     * double up to {@link #CONFIRM_BACKOFF_MAX_MS}. A zero/invalid input yields the start value, so
     * the ladder can never collapse into a dump loop.
     */
    public static long nextConfirmBackoff(long currentMs) {
        if (currentMs <= 0L) {
            return CONFIRM_BACKOFF_START_MS;
        }
        long next = currentMs * 2L;
        return next > CONFIRM_BACKOFF_MAX_MS ? CONFIRM_BACKOFF_MAX_MS : next;
    }

    /**
     * True when a session's attach-debounce stamp has to survive this pass.
     *
     * @param present   the session was in this pass's session list
     * @param dumpOnly  the cheap playback query cannot see this session at all (native player)
     *
     * <p>A cheap pass has an empty list for every native player (the probe only knows players that
     * registered with AudioService), so forgetting the stamp there restarted the debounce window
     * on every dump: the player was discovered, forgotten, discovered again, and never attached.
     */
    public static boolean keepFirstSeen(boolean present, boolean dumpOnly) {
        return present || dumpOnly;
    }

    /**
     * Minimum spacing of the "attach a dump-only session" dumps. One dumpsys costs 90-120 ms, and
     * the trigger exists to save one wait, not to poll audioserver.
     */
    public static final long ATTACH_DUMP_MIN_MS = 1000L;
    /**
     * Consecutive attach dumps without a handle before the session is parked. An unattachable
     * session (effect limit reached, a chain audioserver refuses to build) must not turn the
     * trigger into a one-dump-per-second loop.
     */
    public static final int ATTACH_DUMP_MAX_MISSES = 5;
    /** First park delay after giving up on a dump-only session's attach. */
    public static final long ATTACH_GIVEUP_START_MS = 30000L;
    /** Ceiling of the park delay: a permanently unattachable session costs one round per 5 min. */
    public static final long ATTACH_GIVEUP_MAX_MS = 300000L;
    /** First retry delay after a failed attach (each attempt is a 60-230 ms AudioEffect ctor). */
    /**
     * Park window for a session the framework did not build an instance for yet (traditional mode).
     *
     * <p>The dump that names a framework instance can legitimately run before audioserver adds the
     * effect to the output chain: a track exists (the probe sees its session) for a few dozen
     * milliseconds before the chain is built. The first park is therefore short - 1 s, 3 s, 10 s,
     * then {@link #ATTACH_GIVEUP_START_MS} and up - and every rung costs exactly one dump. Parking
     * for the full 30 s straight away would leave a freshly started track without parameter control
     * for half a minute even though its instance appears a moment later.
     */
    public static final long ATTACH_AUTO_PARK_START_MS = 1000L;
    public static final long ATTACH_RETRY_START_MS = 400L;
    /** Ceiling of the attach retry delay. */
    public static final long ATTACH_RETRY_MAX_MS = 10000L;
    /**
     * Consecutive confirmation dumps that found the session alive before the trigger sleeps until
     * the state changes. A paused native player legitimately keeps its session, and a session that
     * dies later is still caught by the {@link #CROSSCHECK_MS} cross-check.
     */
    public static final int CONFIRM_MAX_REPEATS = 4;

    /** Ceiling for the "no framework instance yet" park (see {@link #ATTACH_AUTO_PARK_START_MS}). */
    public static final long ATTACH_AUTO_PARK_MAX_MS = 30000L;

    /** Next park delay for a session whose framework instance has not appeared (1 s, 2 s, ... 30 s). */
    public static long nextAutoParkMs(long currentMs) {
        if (currentMs <= 0L) {
            return ATTACH_AUTO_PARK_START_MS;
        }
        long next = currentMs * 2L;
        return next > ATTACH_AUTO_PARK_MAX_MS ? ATTACH_AUTO_PARK_MAX_MS : next;
    }

    /** Next park delay after an attach gave up (doubles up to {@link #ATTACH_GIVEUP_MAX_MS}). */
    public static long nextAttachGiveUpMs(long currentMs) {
        if (currentMs <= 0L) {
            return ATTACH_GIVEUP_START_MS;
        }
        long next = currentMs * 2L;
        return next > ATTACH_GIVEUP_MAX_MS ? ATTACH_GIVEUP_MAX_MS : next;
    }

    /** Next retry delay after a failed attach (doubles up to {@link #ATTACH_RETRY_MAX_MS}). */
    public static long nextAttachRetryMs(long currentMs) {
        if (currentMs <= 0L) {
            return ATTACH_RETRY_START_MS;
        }
        long next = currentMs * 2L;
        return next > ATTACH_RETRY_MAX_MS ? ATTACH_RETRY_MAX_MS : next;
    }

    /** First retry delay after a failed dump (see {@link #nextDumpRetryMs(int)}). */
    public static final long DUMP_RETRY_START_MS = 3000L;

    /** Cap of the dump retry delay: a dump that keeps failing is retried at most once a minute. */
    public static final long DUMP_RETRY_MAX_MS = 60000L;

    /**
     * Retry delay after {@code streak} consecutive failed dumps: 3 s, 6 s, 12 s, ... capped at
     * {@link #DUMP_RETRY_MAX_MS}. A failing dump must not be retried in a hot loop: without a
     * back-off the pass scheduler stays "due" forever (nothing was recorded), and the loop would
     * fork a dumpsys per iteration (90-120 ms, or 3 s when it hits the timeout) while skipping the
     * maintenance timer and the wake wait.
     */
    public static long nextDumpRetryMs(int streak) {
        if (streak <= 1) {
            return DUMP_RETRY_START_MS;
        }
        long delay = DUMP_RETRY_START_MS;
        for (int i = 1; i < streak && delay < DUMP_RETRY_MAX_MS; i++) {
            delay *= 2L;
        }
        return Math.min(delay, DUMP_RETRY_MAX_MS);
    }

    /**
     * Why a dumpsys run is unusable, or {@code null} when it is usable. Extracted so the rule "a
     * partial dump is worse than no dump" is host-testable: the parser cannot tell a truncated table
     * from an empty one, and an empty session list makes the reconcile pass release every handle it
     * holds - an audible gap on a stream that is playing fine.
     *
     * @param exitCode    the dumpsys process exit code
     * @param tooLarge    the reader stopped at the size cap
     * @param length      characters captured
     * @param hasThreads  the captured text contains an "Output thread" section
     * @return the reason to log, or {@code null} when the dump can be parsed
     */
    public static String dumpProblem(int exitCode, boolean tooLarge, int length,
            boolean hasThreads) {
        if (tooLarge) {
            return "dumpsys output too large";
        }
        if (exitCode != 0) {
            return "dumpsys exit " + exitCode;
        }
        if (length <= 0) {
            return "dumpsys produced no output";
        }
        if (!hasThreads) {
            return "dumpsys output incomplete";
        }
        return null;
    }

    /**
     * Upper bound of the "a poke is served from the cheap view" postponement. The App pokes on every
     * slider frame (tens per second), and a due dump would add its 90-120 ms to that frame; bounding
     * the postponement keeps a stream of pokes from starving the dump a trigger asked for.
     */
    public static final long POKE_DEFER_MAX_MS = 250L;

    /** True when the confirmation trigger already used up its repeats for this state. */
    public static boolean confirmExhausted(int repeats) {
        return repeats >= CONFIRM_MAX_REPEATS;
    }

    /**
     * True when the idle safety query is due: nothing is playing and no session is attached, so
     * only the every-{@value #IDLE_QUERY_MS} safety query (and the {@code isStreamActive} gate in
     * between) runs instead of the per-tick playback-configuration query.
     */
    public static boolean idleQueryDue(long now, long nextIdleQueryAt) {
        return now >= nextIdleQueryAt;
    }

    /**
     * Advance the degraded ladder. A full pass that found a different attachable session set
     * restarts it (dense rung); a full pass that found the same set climbs one pass; a cheap pass
     * leaves it alone - it says nothing about whether a dump is worth running.
     *
     * <p>A poke deliberately does not reset it: the App polls the daemon every few seconds, so
     * "poke" is not a discovery event, and letting it reset the ladder pinned the blind/degraded
     * path to the dense rung forever (measured: 16 dumps/min for a whole AAudio stream). A new
     * stream still gets a dense rung, because its first dump finds a new session set.
     */
    public static int nextStablePasses(boolean fullPass, boolean setChanged, int current) {
        if (fullPass && setChanged) {
            return 0;
        }
        if (fullPass) {
            return current + 1;
        }
        return current;
    }

    /** True when the non hot maintenance work (config flush, endpoint refresh) is due. */
    public static boolean maintenanceDue(long now, long lastMaintenanceAt) {
        return lastMaintenanceAt <= 0L || now - lastMaintenanceAt >= MAINTENANCE_MS;
    }

    /**
     * Degraded (no usable cheap query) full-pass period after {@code stablePasses} unchanged
     * passes: rung 0 is {@value #FALLBACK_MS} ms, rung 1 is 15 s, rung 2 is
     * {@value #DEGRADED_MAX_MS} ms. The caller passes 0 while no session is known, so an idle
     * device keeps the dense rung - the back-off must never delay the discovery of the first
     * playback session.
     */
    public static long degradedFallbackMs(int stablePasses) {
        return degradedFallbackMs(stablePasses, DEGRADED_MAX_MS);
    }

    /** Degraded ladder with an explicit cap (clamped to the dense rung at minimum). */
    public static long degradedFallbackMs(int stablePasses, long maxMs) {
        int rung = stablePasses <= 0 ? 0 : stablePasses / DEGRADED_STABLE_PASSES;
        if (rung >= DEGRADED_TIERS.length) {
            rung = DEGRADED_TIERS.length - 1;
        }
        long period = DEGRADED_TIERS[rung];
        long cap = maxMs <= 0L ? DEGRADED_MAX_MS : maxMs;
        if (cap < FALLBACK_MS) {
            cap = FALLBACK_MS;
        }
        return Math.min(period, cap);
    }
}
