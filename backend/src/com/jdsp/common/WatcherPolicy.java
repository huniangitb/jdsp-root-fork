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

    /** True when the non hot maintenance work is due. */
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
