package com.jdsp.backend;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jdsp.common.BypassRule;
import com.jdsp.common.EnableGate;
import com.jdsp.common.WatcherPolicy;

/**
 * Resolves playback sessions to (pid, uid, pkg) and reconciles the effect handles: new sessions
 * get attached (and their configured parameters replayed plus {@code setEnabled(true)}), vanished
 * or excluded ones are released.
 *
 * <h3>Event driven, not polled</h3>
 *
 * <p>The session list comes from {@code dumpsys media.audio_flinger} (78 KB, 28-150 ms measured
 * including the {@code sh -c} fork) - far too expensive for a fixed 500 ms timer, which is what
 * the daemon used to do.
 *
 * <p>Three triggers drive the loop instead (see {@link WatcherPolicy}):
 * <ol>
 *   <li>{@link #poke()} - an explicit request from the daemon (policy/mode change, attach or
 *       detach command), runs a pass immediately;</li>
 *   <li>a <b>cheap binder change detector</b> ({@link AudioServiceProbe},
 *       {@code getActivePlaybackConfigurations()}, 64-93 us measured) polled every
 *       {@link WatcherPolicy#PROBE_MS} ms: when the active player set changes, one dumpsys pass
 *       resolves the session ids (with {@link WatcherPolicy#CHANGE_DEBOUNCE_MS} coalescing);</li>
 *   <li>a {@link WatcherPolicy#FALLBACK_MS} ms safety pass that also carries the work that must
 *       not sit on the hot path of a fast pass: the deferred global switch safety net, the App
 *       endpoint file refresh and (through the AsyncPersister) the config flush.</li>
 * </ol>
 *
 * <p>If the cheap detector is unavailable (ROM without that transaction, SELinux denial, or -
 * the common case - the daemon starting before the audio service is registered), the loop
 * degrades to a full pass every {@link WatcherPolicy#LEGACY_POLL_MS} ms, i.e. exactly the
 * pre-event-driven behaviour, and keeps retrying the setup (v2.1.8; v2.1.7 cached the first
 * failure forever and then still waited for the 3 s fallback). The shell command runs through
 * {@code sh -c} with a hard 3 s watchdog; a hung dumpsys can never wedge the daemon (the next
 * pass simply tries again).
 */
public final class SessionWatcher {
    /**
     * Legacy timed poll period, kept for the fallback path and for diagnostics.
     *
     * @deprecated the loop is event driven; use {@link WatcherPolicy#LEGACY_POLL_MS}.
     */
    @Deprecated
    public static final long POLL_MS = WatcherPolicy.LEGACY_POLL_MS;
    /** Hard timeout for one dumpsys invocation. */
    public static final long DUMPSYS_TIMEOUT_MS = 3000L;
    /**
     * Long attach debounce (storm mode + the "known session" fast path): a session that has
     * been observed for at least this long is attached immediately, whatever the storm state.
     * Every effect-chain create/teardown round trip stresses the vendor HAL (on an MTK device a
     * HAL SIGSEGV was observed under chain churn), and most of the churn comes from sessions
     * that live well under two seconds (UI blips, probe apps, decoder warm-ups). Sessions that
     * are already attached are never affected by any of these gates.
     */
    public static final long ATTACH_DEBOUNCE_MS = 2000L;
    /**
     * Default (no storm) attach debounce: a freshly discovered session only has to survive
     * this long before its effect chain is built. Was the full 2000 ms, which the user
     * reported as "a new stream takes seconds until the effect is audible".
     */
    public static final long ATTACH_DEBOUNCE_FAST_MS = 400L;
    /** Sliding window over which short-lived session deaths are counted. */
    public static final long STORM_WINDOW_MS = 30000L;
    /** Short-lived deaths inside the window that switch the watcher to storm mode. */
    public static final int STORM_DEATHS = 5;
    /** A session that died younger than this counts as short-lived (churn source). */
    public static final long STORM_LIFETIME_MS = 2000L;
    private static final int MAX_DUMP_BYTES = 8 * 1024 * 1024;
    private static final String DUMPSYS_CMD = "dumpsys media.audio_flinger";

    private final ConfigStore config;
    private final PolicyEngine policy;
    private final EffectManager effects;
    private final ParamBus bus;
    /** Cheap "did the active playback set change" detector (null in host tests). */
    private final AudioServiceProbe probe;
    /** Optional: keeps the App's endpoint files alive across App reinstalls. */
    private TcpService tcp;
    /** Optional: the core API (used for the deferred global switch transition). */
    private CoreApi api;

    /** How often the App's endpoint files (port/token) are re-checked. */
    public static final long ENDPOINT_REFRESH_MS = 30000L;

    private final Object wakeLock = new Object();
    private volatile boolean running;
    private volatile boolean wake;
    private Thread thread;

    private volatile List<SessionInfo> sessions = new ArrayList<SessionInfo>();
    private volatile long ticks;
    private volatile long fullPasses;
    private volatile long failures;
    private volatile long lastTickAt;
    private volatile int lastDumpBytes;
    private volatile String lastError = "";
    private volatile String lastPassReason = "";
    private long lastEndpointCheck;
    /** Last non hot maintenance pass (config flush, deferred switch, endpoint files). */
    private long lastMaintenanceAt;
    /** Last full dumpsys cross-check of the cheap view (0 = none since start). */
    private volatile long lastCrosscheckAt;
    /** Passes served straight from the probe rows (no dump). */
    private volatile long probePasses;
    /** Full dumpsys cross-checks of the probe view. */
    private volatile long crossChecks;
    /** Rows the probe reported in the last poll (players, including session 0). */
    private volatile int probeRows;
    /** "probe" when the last pass came from the cheap query, "dumpsys" otherwise. */
    private volatile String discoveryMode = "dumpsys";
    /**
     * Session ids that only a dumpsys has ever reported: players that never register with
     * AudioService (native AAudio / OpenSL ES, i.e. most games). A cheap pass must not retire
     * them - only a dump that no longer lists them may - otherwise switching discovery to the
     * probe would silently drop the effect chain of every native player.
     */
    private final java.util.Set<Integer> dumpOnly =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<Integer, Boolean>());
    /** Session list of the last dumpsys pass (retained on cheap passes for {@link #dumpOnly}). */
    private volatile List<SessionInfo> lastDumpSessions = new ArrayList<SessionInfo>();
    /**
     * True when the cheap query reports no playing player while the music stream is active: a
     * native player is playing, so the probe's view is incomplete and the dump drives discovery
     * until the two agree.
     */
    private volatile boolean probeBlind;
    /** Iterations served by the ~10 us isStreamActive gate instead of the config query. */
    private volatile long idleSkips;
    /** Earliest next idle safety query (see {@link WatcherPolicy#IDLE_QUERY_MS}). */
    private long nextIdleQueryAt;
    /** Previous isStreamActive gate value (edge detection: a stream that just started). */
    private boolean lastGateActive;
    /** Last time the isStreamActive gate said "music playing" (blind-mode hysteresis). */
    private volatile long lastGateTrueAt;
    /** blind -> not blind transitions since start (diagnostics). */
    private volatile long blindFlips;
    /** True when the last pass saw a different attachable session set (drives the ladder). */
    private volatile boolean dumpSetChanged;
    /** When "sessions attached but nothing plays" was first seen (0 = not armed). */
    private volatile long confirmArmedAt;
    /** When the last confirmation dump ran (0 = none since start). */
    private volatile long lastConfirmAt;
    /** Current confirmation retry delay (see {@link WatcherPolicy#nextConfirmBackoff(long)}). */
    private long confirmBackoffMs = WatcherPolicy.CONFIRM_BACKOFF_START_MS;
    /** Confirmation dumps that found the session alive (see {@link WatcherPolicy#confirmExhausted}). */
    private int confirmRepeats;
    /** Confirmation dumps since start (diagnostics). */
    private volatile long confirmDumps;
    /** When the last "attach a dump-only session" dump ran (rate limit, 0 = never). */
    private volatile long lastDumpAttachAt;
    /** Consecutive attach dumps that did not produce a handle (loop guard). */
    /** Consecutive failed dumps (see {@link WatcherPolicy#nextDumpRetryMs(int)}). */
    private int dumpFailStreak;
    /** No dump is attempted before this instant, so a failing dump cannot spin the loop. */
    private long dumpRetryAt;
    /** Attach-dump misses per session id (a hopeless id must not park a healthy neighbour). */
    private final Map<Integer, Integer> attachMissById = new HashMap<Integer, Integer>();
    /** The dump-only ids the current dump was asked to attach. */
    private final java.util.Set<Integer> attachCandidates = new java.util.HashSet<Integer>();
    /** sessionId -> end of its attach park: no more attach dumps for this id until then. */
    private final Map<Integer, Long> attachParkedUntil = new HashMap<Integer, Long>();
    /** Current attach park delay (see {@link WatcherPolicy#nextAttachGiveUpMs(long)}). */
    private long attachGiveUpMs = WatcherPolicy.ATTACH_GIVEUP_START_MS;
    /** sessionId -> earliest time the next attach may be attempted (failed-attach back-off). */
    private final Map<Integer, Long> attachRetryAt =
            new java.util.concurrent.ConcurrentHashMap<Integer, Long>();
    /** Current attach retry delay (see {@link WatcherPolicy#nextAttachRetryMs(long)}). */
    private volatile long attachRetryMs = WatcherPolicy.ATTACH_RETRY_START_MS;
    /** A due dump may be postponed for a poke at most until here (see POKE_DEFER_MAX_MS). */
    private long dumpDeferUntil;
    /** Session ids of the last pass. */
    private java.util.Set<Integer> lastDumpIds = java.util.Collections.emptySet();
    /** Diagnostic line period of the watcher counters. */
    private static final long DIAG_MS = 60000L;
    private long lastDiagAt;
    private long lastPassLogAt;
    /** Session count of the last logged pass (diagnostics: log only changes). */
    private int lastLoggedSessionCount = -1;
    /** sessionId -> wall clock of the tick the session was first seen (short-session debounce). */
    private final Map<Integer, Long> firstSeen = new HashMap<Integer, Long>();
    /**
     * Short-lived session deaths inside the storm window: one long[]{diedAt, lifetimeMs} per
     * vanished session whose lifetime was below {@link #STORM_LIFETIME_MS}. Pruned lazily.
     */
    private final java.util.ArrayDeque<long[]> recentDeaths = new java.util.ArrayDeque<long[]>();
    private volatile int stormDeaths;
    /**
     * One-shot timer that re-pokes the loop when a session's attach debounce expires.
     *
     * <p>With a periodic tick the debounce was free: the next tick simply found the session old
     * enough. An event driven loop has no next tick, so the deadline has to be armed explicitly -
     * otherwise a new stream waits for the {@link WatcherPolicy#FALLBACK_MS} safety pass (measured:
     * 3.4 s instead of the expected ~0.6 s).
     */
    private final java.util.concurrent.ScheduledExecutorService debounceTimer =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "jdspd-attach-debounce");
                            t.setDaemon(true);
                            return t;
                        }
                    });
    private final Object debounceLock = new Object();
    private java.util.concurrent.ScheduledFuture<?> debounceTask;

    /** Worker pool for the per-session attach tasks (bounded, shared with EffectManager). */
    private final java.util.concurrent.ExecutorService attachPool = EffectManager.sharedPool();
    /**
     * Sessions whose parallel attach task has been submitted and has not finished yet. While the
     * marker is set, the reconcile pass must not take the "already attached" re-enable path
     * (applyAll + setEnabled): the attach task itself replays the parameters right after
     * publishing the handle, and a watcher tick slipping in between the two otherwise repeats
     * the whole replay (harmless - every write is idempotent - but pointless work and duplicate
     * log lines).
     */
    private final Set<Integer> attachInFlight = new HashSet<Integer>();

    public SessionWatcher(ConfigStore config, PolicyEngine policy, EffectManager effects,
                          ParamBus bus) {
        this(config, policy, effects, bus, new AudioServiceProbe());
    }

    /** Test/diagnostic constructor: {@code probe == null} means "use the timed dump". */
    SessionWatcher(ConfigStore config, PolicyEngine policy, EffectManager effects, ParamBus bus,
                   AudioServiceProbe probe) {
        this.config = config;
        this.policy = policy;
        this.effects = effects;
        this.bus = bus;
        this.probe = probe;
    }

    /** Lets the watcher re-create the App's port/token files when they disappear. */
    public void setTcpService(TcpService tcp) {
        this.tcp = tcp;
    }

    /** Lets the watcher apply a deferred global switch transition. */
    public void setCoreApi(CoreApi api) {
        this.api = api;
    }

    public void start() {
        if (thread != null) {
            return;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                // The loop guards every pass, but a bug in the trigger arithmetic (or in the wake
                // wait) must not kill the watcher thread silently: log it and restart the loop.
                while (running) {
                    try {
                        loop();
                        return;
                    } catch (Throwable t) {
                        failures++;
                        lastError = "watcher loop failed: " + EffectManager.describe(t);
                        Log.e(lastError, t);
                        sleepQuietly(WatcherPolicy.FALLBACK_MS);
                    }
                }
            }
        }, "jdspd-watcher");
        thread.setDaemon(true);
        thread.start();
        Log.i("session watcher started (event driven: query " + WatcherPolicy.PROBE_MS
                + "ms while playing, isStreamActive gate " + WatcherPolicy.PROBE_IDLE_MS
                + "ms / safety query " + WatcherPolicy.IDLE_QUERY_MS
                + "ms while idle; dump only on " + (WatcherPolicy.CROSSCHECK_MS / 1000L)
                + "s crosscheck, blind player or degraded (" + WatcherPolicy.FALLBACK_MS
                + "ms dense rung), legacy " + WatcherPolicy.LEGACY_POLL_MS
                + "ms when the probe is unavailable)");
    }

    public void stop() {
        running = false;
        synchronized (debounceLock) {
            if (debounceTask != null) {
                debounceTask.cancel(false);
                debounceTask = null;
            }
        }
        poke();
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(2000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Makes the watcher run a pass immediately (used after policy/mode/handle changes). */
    public void poke() {
        synchronized (wakeLock) {
            wake = true;
            wakeLock.notifyAll();
        }
    }

    /**
     * The event loop: sleep until a trigger, run a pass, repeat.
     *
     * <p>Three pass kinds, cheapest first:
     * <ol>
     *   <li><b>probe pass</b> - the cheap {@code getActivePlaybackConfigurations} query already
     *       returned the session ids, so the pass is pure Java: build {@code SessionInfo}s, decide
     *       policy, reconcile. Cost: tens of microseconds;</li>
     *   <li><b>cross-check pass</b> - a real dumpsys, once per
     *       {@link WatcherPolicy#CROSSCHECK_MS} ms (and once at startup), so a session the probe
     *       cannot see is still reconciled;</li>
     *   <li><b>degraded pass</b> - a real dumpsys when the probe is unusable, on the ladder
     *       {@link WatcherPolicy#degradedFallbackMs(int)}.</li>
     * </ol>
     * The cheap query is polled every {@link WatcherPolicy#probeSleepMs(boolean)} ms: 100 ms while
     * audio plays, 250 ms while nothing does (no dump is due then, so a slower poll is free).
     */
    private void loop() {
        long lastPassAt = 0L;
        int stableDegradedPasses = 0;
        while (running) {
            long now = System.currentTimeMillis();
            boolean poke = takeWake();
            boolean probeOk = probeReady();
            // One gate call per iteration, reused by the idle path and the blind check.
            boolean music = probeOk && probe.musicActive();
            if (music) {
                lastGateTrueAt = now;
            }
            // Hold the "music is active" verdict for a moment: the gate can dip between two loop
            // iterations (a track that has not been fed yet, a silent AAudio stream), and every
            // dip used to pull the blind ladder back to its dense rung - measured as 16 dumps/min
            // for a whole AAudio stream instead of the intended 3 s -> 15 s ladder.
            boolean musicRecent = music
                    || (now - lastGateTrueAt < WatcherPolicy.GATE_HOLD_MS);
            // Idle gate: with nothing playing and nothing attached the playback-configuration
            // query cannot change, so only the isStreamActive gate is polled and the query itself
            // only every WatcherPolicy.IDLE_QUERY_MS (safety net). The gate edge (a stream that
            // just started) forces an immediate pass instead of waiting for the next timed one.
            boolean idleSkip = false;
            if (!poke && probeOk && sessions.isEmpty()) {
                if (music && !lastGateActive) {
                    lastPassAt = 0L;
                }
                if (!musicRecent) {
                    // nothing plays (and nothing is attached): the next stream starts dense again
                    stableDegradedPasses = 0;
                }
                lastGateActive = music;
                if (!music && !WatcherPolicy.idleQueryDue(now, nextIdleQueryAt)) {
                    idleSkip = true;
                    idleSkips++;
                } else if (!music) {
                    nextIdleQueryAt = now + WatcherPolicy.IDLE_QUERY_MS;
                }
            } else {
                nextIdleQueryAt = now;
            }
            boolean changed = false;
            if (!poke && probeOk && !idleSkip) {
                changed = pollProbe();
            }
            // The probe only knows players that registered with AudioService. If nothing is
            // registered while the music stream is active, a native player (AAudio/OpenSL) is
            // playing and the cheap view is incomplete: dump until the two agree again.
            // Two ways the cheap view can be incomplete while audio is playing:
            //   * nothing registered is playing but the music stream is active -> a native player
            //     (AAudio/OpenSL ES) opened its track straight in AudioFlinger;
            //   * something is playing but no row carries a session id -> this caller is not
            //     privileged enough (AudioService anonymised the ids), so nothing is attachable.
            // Both are handled by letting the dump drive discovery while the condition lasts.
            boolean blind = probeOk && musicRecent
                    && (!probe.playing() || !probe.anySessionId());
            if (blind != probeBlind) {
                blindFlips++;
            }
            probeBlind = blind;
            boolean probeDiscovery = probeOk && probe.sessionIdsUsable() && !blind;
            boolean crossDue = probeDiscovery && WatcherPolicy.crossCheckDue(now, lastCrosscheckAt);
            // While blind the dump runs on the degraded ladder too: an AAudio (MMAP) player has no
            // attachable session id at all (the dump lists it as session 0/-1), so dumping every
            // 3 s for the whole length of a game would buy nothing - measured 20 dumps/min while an
            // AAudio stream played. Three dense passes are enough to catch a session that is
            // attachable but invisible to the probe; after that the ladder backs off.
            boolean degradedDue = !probeDiscovery && WatcherPolicy.fallbackDue(now, lastPassAt,
                    WatcherPolicy.degradedFallbackMs(stableDegradedPasses,
                            blind ? WatcherPolicy.BLIND_MAX_MS
                                    : WatcherPolicy.DEGRADED_MAX_MS));
            // One scan of the dump-only set answers both triggers below: "is a real session still
            // attached?" (a confirmation only makes sense for a handle that exists - an unattached
            // corpse is the attach trigger's business) and "does an unattached dump-only session
            // need one more dump?".
            //
            // A dump-only session (native player) that survived its attach debounce needs exactly
            // one more dump: the cheap pass cannot see it, so without this trigger its handle
            // waited for the next ladder rung (3-15 s). The trigger is rate limited and every
            // give-up parks the id, so it can never turn into a dump loop.
            boolean dumpOnlyAttached = false;
            boolean dumpAttachDue = false;
            attachCandidates.clear();
            if (probeOk && !dumpOnly.isEmpty()) {
                boolean stormNow = inStorm();
                boolean rateOk = now - lastDumpAttachAt >= WatcherPolicy.ATTACH_DUMP_MIN_MS;
                java.util.Iterator<Integer> it = dumpOnly.iterator();
                while (it.hasNext()) {
                    Integer id = it.next();
                    int sid = id.intValue();
                    if (effects.isAttached(sid)) {
                        dumpOnlyAttached = true;
                        attachParkedUntil.remove(id);
                        continue;
                    }
                    if (!rateOk) {
                        continue;
                    }
                    Long parked = attachParkedUntil.get(id);
                    if (parked != null && parked.longValue() > now) {
                        continue;
                    }
                    if (!attachAllowed(sid)) {
                        // Nothing may attach this session right now (excluded app, global switch
                        // off, attach already in flight): a dump cannot change that, so spending
                        // one would only burn 90-120 ms per second for nothing.
                        continue;
                    }
                    Long seenAt = firstSeen.get(id);
                    if (seenAt != null && mayAttach(seenAt, now, stormNow)) {
                        attachCandidates.add(id);
                        dumpAttachDue = true;
                    }
                }
            }
            // Stale-session confirmation. A player that dies without closing its track (SIGTERM,
            // crash, kernel kill, or a player that simply stops feeding) leaves jdspd's own effect
            // handle behind in audioserver: the dump still lists "1 effects for session N" while the
            // cheap query no longer knows the player at all. Because a cheap pass must not retire a
            // dump-only session (that is what keeps native players attached), the dead session used
            // to sit there until the five-minute cross-check - measured 5 min 40 s. Ask for one dump
            // shortly after "attached but nothing plays" appears; if the session survives that dump
            // it is real (a paused native player), so the repeats back off and finally stop until
            // the state changes again (a session that dies later is still caught by the cross-check).
            boolean confirmBase = probeOk && dumpOnlyAttached && !music && !probe.playing();
            if (!confirmBase) {
                confirmArmedAt = 0L;
                lastConfirmAt = 0L;
                confirmBackoffMs = WatcherPolicy.CONFIRM_BACKOFF_START_MS;
                confirmRepeats = 0;
            } else if (confirmArmedAt == 0L) {
                confirmArmedAt = now;
            }
            boolean confirmDue = !WatcherPolicy.confirmExhausted(confirmRepeats)
                    && WatcherPolicy.confirmDue(now, confirmArmedAt, lastConfirmAt,
                            confirmBackoffMs);
            boolean needDump = crossDue || degradedDue || confirmDue || dumpAttachDue;
            // A dump that just failed is not retried at once: the deadline it was meant to answer is
            // still open, so without this gate the scheduler stays "due" forever and the loop forks
            // one dumpsys per iteration - and, worse, skips the maintenance timer and the wake wait.
            boolean dumpDue = needDump && now >= dumpRetryAt;
            if (WatcherPolicy.shouldRunPass(poke, dumpDue, changed)) {
                // A poke is served from the cheap view whenever the probe is usable: the App pokes
                // on every slider frame, and a due dump would add its 90-120 ms to that frame. The
                // deferral is bounded by POKE_DEFER_MAX_MS, so a stream of pokes cannot starve the
                // dump a trigger asked for (and the attach debounce it has to beat is 400 ms).
                boolean deferDump = poke && probeOk && dumpDue && now >= dumpDeferUntil;
                if (deferDump) {
                    dumpDeferUntil = now + WatcherPolicy.POKE_DEFER_MAX_MS;
                }
                boolean full = dumpDue && !deferDump;
                lastPassReason = poke ? "poke"
                        : (changed ? "change"
                                : (crossDue ? "crosscheck"
                                        : (confirmDue ? "confirm"
                                                : (dumpAttachDue ? "attach"
                                                        : (blind ? "blind" : "degraded")))));
                try {
                    if (full) {
                        // Only a dump that really succeeded answers a deadline: a failed one
                        // (timeout, killed, truncated output) must not consume the confirmation
                        // back-off, the attach give-up budget or the cross-check deadline.
                        boolean dumped = pollOnce();
                        if (!dumped) {
                            dumpFailStreak++;
                            long retry = WatcherPolicy.nextDumpRetryMs(dumpFailStreak);
                            dumpRetryAt = System.currentTimeMillis() + retry;
                            Log.w("watcher: dump failed (" + lastError + "), retry in " + retry
                                    + " ms, serving the cheap view meanwhile");
                            // Attach, policy and detach decisions must keep working while the dump
                            // does not: the cheap view is exactly what the steady state uses. Only
                            // when that view is usable, though - a probe that cannot answer would
                            // make every session look gone and release live handles.
                            if (probeOk) {
                                passFromProbe();
                            }
                        } else {
                            dumpFailStreak = 0;
                            dumpRetryAt = 0L;
                        }
                        if (dumped && crossDue) {
                            crossChecks++;
                        }
                        if (dumped && (crossDue || degradedDue)) {
                            // Discovery dumps (five-minute cross-check, degraded ladder) also answer
                            // the cross-check deadline; a confirmation or attach dump must not, or a
                            // busy trigger could starve the periodic probe/dump comparison.
                            lastCrosscheckAt = System.currentTimeMillis();
                        }
                        if (dumped && confirmDue) {
                            // A confirmation only counts when the dump really answered it; the
                            // back-off then grows so a paused native player costs at most a
                            // handful of dumps before it goes quiet.
                            lastConfirmAt = System.currentTimeMillis();
                            confirmBackoffMs = WatcherPolicy.nextConfirmBackoff(confirmBackoffMs);
                            confirmDumps++;
                            // A confirmation that found a different session set answered the
                            // question (something appeared or vanished) and starts the repeats
                            // over; one that found the same set counts towards the ceiling.
                            confirmRepeats = dumpSetChanged ? 0 : confirmRepeats + 1;
                        }
                        if (dumped && dumpAttachDue) {
                            lastDumpAttachAt = System.currentTimeMillis();
                            noteAttachDumpResult();
                        }
                    } else {
                        passFromProbe();
                    }
                } catch (Throwable t) {
                    failures++;
                    lastError = EffectManager.describe(t);
                    Log.e("watcher tick failed: " + lastError, t);
                }
                lastPassAt = System.currentTimeMillis();
                // The degraded ladder only escalates while the attachable session set stays the
                // same. A poke or a full pass that found a different set snaps it back to the dense
                // rung, so a new session is never discovered late; a cheap pass that saw the same
                // set must not, or a flapping player would pin the ladder to 3 s forever.
                stableDegradedPasses = WatcherPolicy.nextStablePasses(full,
                        dumpSetChanged, stableDegradedPasses);
                continue;
            }
            if (WatcherPolicy.maintenanceDue(now, lastMaintenanceAt)) {
                lastMaintenanceAt = now;
                slowMaintenance();
            }
            synchronized (wakeLock) {
                if (!running) {
                    break;
                }
                if (!wake) {
                    try {
                        wakeLock.wait(probeOk
                                ? WatcherPolicy.probeSleepMs(playingNow(musicRecent))
                                : WatcherPolicy.degradedFallbackMs(stableDegradedPasses));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                // Deliberately do NOT clear the wake flag here: takeWake() at the top of the next
                // iteration consumes it and runs the pass. Clearing it here swallowed the poke
                // that interrupted the wait (measured: a new stream then waited for the 3 s
                // cross-check instead of the 400 ms attach debounce).
            }
        }
        Log.i("session watcher stopped");
    }

    /**
     * One cheap pass: the probe already holds the session ids, so no dump is needed. This is the
     * steady state path - the parameter/attach latency of the whole daemon no longer contains a
     * 28-150 ms {@code dumpsys} step.
     */
    void passFromProbe() {
        long t0 = System.currentTimeMillis();
        List<AudioServiceProbe.Row> rows = probe == null ? null : probe.rows();
        int n = rows == null ? 0 : rows.size();
        List<SessionInfo> list = new ArrayList<SessionInfo>(n);
        for (int i = 0; i < n; i++) {
            AudioServiceProbe.Row row = rows.get(i);
            if (row == null || row.sessionId <= 0) {
                // 0 = default/global session (never attached) or an anonymised id: the degraded
                // path exists for the latter.
                continue;
            }
            SessionInfo s = new SessionInfo(row.sessionId);
            s.uid = row.uid;
            s.pid = row.pid;
            s.active = row.active;
            Integer key = Integer.valueOf(s.sessionId);
            if (!firstSeen.containsKey(key)) {
                firstSeen.put(key, Long.valueOf(t0));
            }
            list.add(s);
        }
        policy.refresh();
        for (int i = 0; i < list.size(); i++) {
            SessionInfo s = list.get(i);
            if (s.pkg == null || s.pkg.length() == 0) {
                s.pkg = policy.resolvePkg(s);
            }
        }
        // Keep the sessions only a dump can see (native players) across cheap passes: dropping
        // them here would detach the effect chain of every game that does not register a player
        // with AudioService.
        List<SessionInfo> prev = lastDumpSessions;
        if (!prev.isEmpty() && !dumpOnly.isEmpty()) {
            for (int i = 0; i < prev.size(); i++) {
                SessionInfo s = prev.get(i);
                if (!dumpOnly.contains(Integer.valueOf(s.sessionId))
                        || !effects.isAttached(s.sessionId)) {
                    continue;
                }
                boolean present = false;
                for (int j = 0; j < list.size(); j++) {
                    if (list.get(j).sessionId == s.sessionId) {
                        present = true;
                        break;
                    }
                }
                if (!present) {
                    list.add(s);
                }
            }
        }
        sessions = list;
        ticks++;
        probePasses++;
        probeRows = n;
        discoveryMode = "probe";
        long passMs = System.currentTimeMillis() - t0;
        boolean changedSet = list.size() != lastLoggedSessionCount;
        if ((changedSet || !"change".equals(lastPassReason))
                && System.currentTimeMillis() - lastPassLogAt >= 1000L) {
            lastPassLogAt = System.currentTimeMillis();
            lastLoggedSessionCount = list.size();
            Log.i("watcher pass reason=" + lastPassReason + " mode=probe sessions="
                    + list.size() + " costMs=" + passMs);
        }
        reconcile(list);
    }

    /**
     * Remember whether the set of sessions a <em>dump</em> found changed. The degraded ladder keys
     * on this and not on the probe's session list: while the cheap view is incomplete (blind mode)
     * the probe pass legitimately sees an empty set and the next dump sees the real one, so keying
     * on both views made every pass look like a change and pinned the ladder to the dense rung -
     * measured 22 dumps in 75 s (CPU 2.47 %) for one AAudio stream. A probe pass never resets it;
     * only a dump that finds a different set does, which is exactly the event that means "a new
     * session became attachable".
     */
    private void noteDumpSet(List<SessionInfo> list) {
        java.util.Set<Integer> ids = new java.util.HashSet<Integer>();
        for (int i = 0; i < list.size(); i++) {
            ids.add(Integer.valueOf(list.get(i).sessionId));
        }
        dumpSetChanged = !ids.equals(lastDumpIds);
        lastDumpIds = ids;
    }

    /**
     * True while audio actually plays: a probe row reports "playing", or the system gate said so
     * within the hold window. An attached but silent session deliberately does not count: with
     * handles held the loop used to poll the playback-configuration query at 10 Hz for as long as
     * the session lived, which measured 0.95 % CPU for a paused player (2 Hz costs ~0.2 %). Being
     * attached is not a reason to look ten times a second - attaching is poked directly by the App
     * and by session discovery, and a paused player's set does not change in tenths of a second.
     */
    private boolean playingNow(boolean musicRecent) {
        return musicRecent || (probe != null && probe.playing());
    }

    /** Reads and clears the pending poke flag. */
    private boolean takeWake() {
        synchronized (wakeLock) {
            boolean w = wake;
            wake = false;
            return w;
        }
    }

    /** True when the cheap detector can be used (cached inside the probe). */
    private boolean probeReady() {
        return probe != null && probe.available();
    }

    /** One cheap change-detector query; never throws. */
    private boolean pollProbe() {
        if (probe == null) {
            return false;
        }
        try {
            return probe.poll();
        } catch (Throwable t) {
            lastError = "probe poll failed: " + EffectManager.describe(t);
            return false;
        }
    }

    /**
     * Runs exactly one full watcher pass (dumpsys + reconcile). Also used by {@code --selftest}.
     *
     * <p>Deliberately free of the work that must not delay a pass triggered by a change or a
     * poke: the config flush lives in the AsyncPersister and the deferred switch transition has
     * its own timer, both are only re-checked by {@link #slowMaintenance()}.
     */
    public boolean pollOnce() {
        long t0 = System.currentTimeMillis();
        String dump = runDumpsys();
        ticks++;
        lastTickAt = t0;
        if (dump == null) {
            failures++;
            return false;
        }
        fullPasses++;
        lastDumpBytes = dump.length();
        List<SessionInfo> list = DumpsysParser.parse(dump);
        discoveryMode = "dumpsys";
        if (probe != null && probe.available()) {
            // Cross-check: the dump is the reference, the probe is the fast path. A mismatch is
            // logged (and reconciled) instead of silently trusted either way.
            crossCheckAgainstProbe(list);
        }
        policy.refresh();
        for (int i = 0; i < list.size(); i++) {
            SessionInfo s = list.get(i);
            if (s.uid < 0 && s.pid > 0) {
                s.uid = uidOfPid(s.pid);
            }
            if (s.pkg == null || s.pkg.length() == 0) {
                s.pkg = policy.resolvePkg(s);
            }
            // Short-session debounce: remember when this session first showed up.
            Integer key = Integer.valueOf(s.sessionId);
            if (!firstSeen.containsKey(key)) {
                firstSeen.put(key, Long.valueOf(t0));
            }
        }
        sessions = list;
        long passMs = System.currentTimeMillis() - t0;
        // Only interesting passes are logged: the 3 s fallback pass that found the same session set
        // would otherwise write ~28k lines/day into the daemon log (it rotates three 1 MB files).
        boolean changedSet = list.size() != lastLoggedSessionCount;
        boolean interesting = changedSet || !"fallback".equals(lastPassReason);
        if (interesting && System.currentTimeMillis() - lastPassLogAt >= 1000L) {
            lastPassLogAt = System.currentTimeMillis();
            lastLoggedSessionCount = list.size();
            Log.i("watcher pass reason=" + lastPassReason + " mode=dumpsys sessions="
                    + list.size() + " dumpMs=" + passMs
                    + (probeBlind ? " blind=1" : ""));
        }
        noteDumpSet(list);
        reconcile(list);
        lastDumpSessions = list;
        // Remember which sessions the cheap query cannot see: only a later dump may retire them.
        java.util.Set<Integer> probeIds = new java.util.HashSet<Integer>();
        if (probe != null) {
            List<AudioServiceProbe.Row> rows = probe.rows();
            for (int i = 0; i < rows.size(); i++) {
                AudioServiceProbe.Row row = rows.get(i);
                if (row != null && row.sessionId > 0) {
                    probeIds.add(Integer.valueOf(row.sessionId));
                }
            }
        }
        dumpOnly.clear();
        for (int i = 0; i < list.size(); i++) {
            int sid = list.get(i).sessionId;
            if (sid <= 0) {
                // 0 / -1 are the global mix sessions: never attachable, never a corpse.
                continue;
            }
            Integer key = Integer.valueOf(sid);
            if (!probeIds.contains(key)) {
                dumpOnly.add(key);
            }
        }
        // Park and retry bookkeeping follows the dump-only set: ids that left it are forgotten, and
        // an empty set restarts both ladders (the next native player gets the dense behaviour).
        if (dumpOnly.isEmpty()) {
            attachParkedUntil.clear();
            attachRetryAt.clear();
            attachMissById.clear();
            attachGiveUpMs = WatcherPolicy.ATTACH_GIVEUP_START_MS;
            attachRetryMs = WatcherPolicy.ATTACH_RETRY_START_MS;
        } else {
            attachParkedUntil.keySet().retainAll(dumpOnly);
            attachRetryAt.keySet().retainAll(dumpOnly);
            attachMissById.keySet().retainAll(dumpOnly);
        }
        return true;
    }

    /**
     * Work that must not run on a fast pass: the deferred global switch safety net (normally the
     * {@link CoreApi}-owned timer fires first), the App endpoint file refresh (self throttled to
     * {@link #ENDPOINT_REFRESH_MS}) and the config flush request. Runs at most every
     * {@link WatcherPolicy#FALLBACK_MS} ms.
     */
    void slowMaintenance() {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastDiagAt >= DIAG_MS) {
            lastDiagAt = nowMs;
            Log.i("watcher: passes=" + fullPasses + " mode=" + discoveryMode
                    + " probePasses=" + probePasses + " idleSkips=" + idleSkips
                    + " dumpOnly=" + dumpOnly.size() + " blind=" + probeBlind
                    + " confirmDumps=" + confirmDumps + " confirmAgo=" + lastConfirmAgoMs()
                    + " music=" + (probe != null && probe.lastMusicActive())
                    + " probePolls=" + probePolls()
                    + " probeChanges=" + probeChanges() + " probeCostUs=" + probeCostUs()
                    + " probe=" + (probeAvailable() ? "on" : "off")
                    + (probe == null ? "" : " probeAttempts=" + probe.setupAttempts()
                            + " probeSetupFail=" + probe.setupFailures())
                    + " dumpFails=" + dumpFailStreak
                    + (dumpRetryAt > nowMs ? " dumpRetryIn=" + (dumpRetryAt - nowMs) : "")
                    + " lastReason=" + lastPassReason);
        }
        try {
            if (api != null) {
                api.pollEnableSwitch();
            }
        } catch (Throwable t) {
            Log.w("slow pass: deferred switch check failed: " + EffectManager.describe(t));
        }
        try {
            config.saveIfDirty();
        } catch (Throwable t) {
            Log.w("slow pass: config flush failed: " + EffectManager.describe(t));
        }
        refreshEndpointFiles();
    }

    /**
     * Re-creates the App's endpoint files when they are missing (the App was uninstalled or
     * reinstalled, which wipes /data/data/&lt;pkg&gt;/files). Without this the App could not
     * connect until the daemon restarted.
     */
    private void refreshEndpointFiles() {
        TcpService service = tcp;
        if (service == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastEndpointCheck < ENDPOINT_REFRESH_MS) {
            return;
        }
        lastEndpointCheck = now;
        try {
            service.refreshEndpointFiles();
        } catch (Throwable t) {
            Log.w("endpoint file refresh failed: " + EffectManager.describe(t));
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs the dumpsys command with a watchdog. Returns null when it failed. */
    private String runDumpsys() {
        Process process = null;
        Thread killer = null;
        try {
            process = new ProcessBuilder("sh", "-c", DUMPSYS_CMD)
                    .redirectErrorStream(true).start();
            final Process target = process;
            killer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(DUMPSYS_TIMEOUT_MS);
                        target.destroyForcibly();
                    } catch (InterruptedException ignored) {
                        // finished in time
                    }
                }
            }, "jdspd-dumpsys-watchdog");
            killer.setDaemon(true);
            killer.start();

            StringBuilder sb = new StringBuilder(64 * 1024);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"), 16 * 1024);
            String line;
            int total = 0;
            boolean tooLarge = false;
            while ((line = reader.readLine()) != null) {
                total += line.length() + 1;
                if (total > MAX_DUMP_BYTES) {
                    tooLarge = true;
                    break;
                }
                sb.append(line).append('\n');
            }
            // A partial dump is worse than no dump: the parser cannot tell a truncated table from
            // an empty one, and an empty session list makes the reconcile pass release every handle
            // it holds (audible gap on a playing stream). Every incomplete outcome therefore returns
            // null, and the caller advances its bookkeeping only for a dump that really succeeded.
            if (!process.waitFor(DUMPSYS_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                lastError = "dumpsys did not exit";
                Log.w(lastError);
                return null;
            }
            // audio_flinger always dumps its output threads; without that section the output was cut
            // short (killed mid-write, or a shell wrapper that failed) and must not be parsed.
            String problem = WatcherPolicy.dumpProblem(process.exitValue(), tooLarge, sb.length(),
                    sb.indexOf("Output thread") >= 0);
            if (problem == null && effects.handleCount() > 0
                    && sb.indexOf(DumpsysParser.JDSP_UUID) < 0) {
                // We hold at least one effect handle, so our own chain must appear in the dump: its
                // absence means the text was cut after the output threads but before the effect
                // chains. Parsing it would report "no jdsp effect" for a live session.
                problem = "dumpsys output incomplete (own effect chain missing)";
            }
            if (problem != null) {
                lastError = problem;
                Log.w(lastError);
                return null;
            }
            lastError = "";
            return sb.toString();
        } catch (Throwable t) {
            lastError = "dumpsys failed: " + EffectManager.describe(t);
            Log.w(lastError);
            return null;
        } finally {
            if (killer != null) {
                killer.interrupt();
            }
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
        }
    }

    /**
     * Pure decision helper for the short-session debounce (host-testable, no storm):
     * a session may be attached once it has been observed for at least
     * {@link #ATTACH_DEBOUNCE_FAST_MS}.  A session that was never seen before
     * (firstSeenAt == null) is never attachable - the caller records it first
     * and waits for a later tick.
     */
    static boolean mayAttach(Long firstSeenAt, long now) {
        return mayAttach(firstSeenAt, now, false);
    }

    /**
     * Pure decision helper for the adaptive short-session debounce (host-testable):
     * <ul>
     *   <li>never seen: never attachable (the caller records the session first);</li>
     *   <li>observed for at least {@link #ATTACH_DEBOUNCE_MS}: attach immediately, storm or
     *       not - a session that is already known to be stable (e.g. a re-attach after the
     *       master switch went on) must never wait for the debounce again;</li>
     *   <li>otherwise: attach after {@link #ATTACH_DEBOUNCE_FAST_MS} normally, after
     *       {@link #ATTACH_DEBOUNCE_MS} while a short-lived-session storm is active.</li>
     * </ul>
     */
    static boolean mayAttach(Long firstSeenAt, long now, boolean storm) {
        if (firstSeenAt == null) {
            return false;
        }
        long age = now - firstSeenAt.longValue();
        if (age >= ATTACH_DEBOUNCE_MS) {
            return true;
        }
        return age >= (storm ? ATTACH_DEBOUNCE_MS : ATTACH_DEBOUNCE_FAST_MS);
    }

    /**
     * Pure decision helper for the storm detector (host-testable): the watcher is in storm
     * mode once at least {@link #STORM_DEATHS} short-lived session deaths happened within the
     * last {@link #STORM_WINDOW_MS}.
     */
    static boolean stormActive(int shortLivedDeathsInWindow) {
        return shortLivedDeathsInWindow >= STORM_DEATHS;
    }

    /**
     * Parks every dump-only session that still has no handle: the attach dump gave up on it (see
     * {@link WatcherPolicy#ATTACH_DUMP_MAX_MISSES}), so the id must not cost another dump until the
     * park expires. The park delay doubles per round up to
     * {@link WatcherPolicy#ATTACH_GIVEUP_MAX_MS}, so a permanently unattachable session settles at
     * one dump per five minutes instead of one per second.
     *
     * <p>The {@code firstSeen} stamp is deliberately kept: the session is real (a dump sees it), it
     * just cannot be attached, and dropping the stamp would also feed the short-session storm
     * detector with a bogus lifetime. Parking is per id and cleared as soon as a handle appears.
     */
    /** The dump's view of one session, or null when the last dump did not list it. */
    private SessionInfo dumpSession(int sessionId) {
        List<SessionInfo> prev = lastDumpSessions;
        for (int i = 0; i < prev.size(); i++) {
            if (prev.get(i).sessionId == sessionId) {
                return prev.get(i);
            }
        }
        return null;
    }

    /**
     * True when the reconcile pass would actually build a chain for this session now: the global
     * switch is on, the policy does not exclude it and no attach for it is in flight. The attach
     * dump exists to close the gap between "the dump sees it" and "a dump is due anyway", not to
     * re-attempt what policy forbids.
     */
    private boolean attachAllowed(int sessionId) {
        if (!config.isEnabled() || isAttachInFlight(sessionId)) {
            return false;
        }
        SessionInfo info = dumpSession(sessionId);
        return info != null && policy.decide(info, config).attach;
    }

    /**
     * Per-id bookkeeping after a dump that was supposed to attach something: an id that is still
     * not attached used up one of its {@link WatcherPolicy#ATTACH_DUMP_MAX_MISSES} dumps and is then
     * parked for an escalating window; an id that got its handle forgets its misses. Counting per id
     * keeps one hopeless session (a corpse the probe never saw, or a chain audioserver refuses) from
     * parking a healthy native player that appeared in the same dump.
     */
    private void noteAttachDumpResult() {
        long now = System.currentTimeMillis();
        java.util.Iterator<Integer> it = attachCandidates.iterator();
        while (it.hasNext()) {
            Integer id = it.next();
            int sid = id.intValue();
            if (effects.isAttached(sid)) {
                attachMissById.remove(id);
                continue;
            }
            Integer miss = attachMissById.get(id);
            int misses = (miss == null ? 0 : miss.intValue()) + 1;
            if (misses < WatcherPolicy.ATTACH_DUMP_MAX_MISSES) {
                attachMissById.put(id, Integer.valueOf(misses));
                continue;
            }
            attachMissById.remove(id);
            attachParkedUntil.put(id, Long.valueOf(now + attachGiveUpMs));
            Log.w("watcher: session " + sid + " still unattached after " + misses
                    + " dumps, parked for " + attachGiveUpMs + " ms");
            attachGiveUpMs = WatcherPolicy.nextAttachGiveUpMs(attachGiveUpMs);
        }
        attachCandidates.clear();
    }

    /** Records one vanished session (called from the reconcile pass). */
    private void recordDeath(long now, long lifetimeMs) {
        if (lifetimeMs >= STORM_LIFETIME_MS) {
            return; /* a session that lived long enough is not churn */
        }
        synchronized (recentDeaths) {
            recentDeaths.addLast(new long[]{now, lifetimeMs});
            pruneDeaths(now);
            stormDeaths = recentDeaths.size();
        }
    }

    /** Drops deaths that left the sliding window (caller holds the monitor). */
    private void pruneDeaths(long now) {
        long cutoff = now - STORM_WINDOW_MS;
        while (!recentDeaths.isEmpty() && recentDeaths.peekFirst()[0] < cutoff) {
            recentDeaths.removeFirst();
        }
    }

    /** True while a short-lived-session storm is active (see {@link #stormActive(int)}). */
    private boolean inStorm() {
        long now = System.currentTimeMillis();
        synchronized (recentDeaths) {
            pruneDeaths(now);
            stormDeaths = recentDeaths.size();
            return stormActive(recentDeaths.size());
        }
    }

    /** Deaths currently counted inside the storm window (diagnostics). */
    public int stormDeathCount() {
        synchronized (recentDeaths) {
            return recentDeaths.size();
        }
    }

    /**
     * Attaches / releases handles so that the live set matches policy and config.
     *
     * <p>Three independent rules, in this order:
     * <ol>
     *   <li>policy=exclude: the handle is released (the instance is disabled first, an
     *       immediate bypass, then released; see {@link EffectManager#detach}); moving the app
     *       out of the blacklist re-attaches on a later tick with the configured parameters
     *       replayed;</li>
     *   <li>the global master switch is off: the same unload semantics &mdash; anything still
     *       held is released and no new session is attached while the switch is off (the switch
     *       itself already detached everything via CoreApi/EffectManager.detachAll; this is the
     *       safety net for races, e.g. an attach that was in flight when the switch went
     *       off);</li>
     *   <li>otherwise: attach when the policy allows it, and keep the instance enabled.</li>
     * </ol>
     */
    private void reconcile(List<SessionInfo> list) {
        Set<Integer> present = new HashSet<Integer>();
        boolean globallyDisabled = !config.isEnabled();
        boolean storm = inStorm();
        final List<Runnable> attachTasks = new ArrayList<Runnable>();
        // Earliest moment at which a still-too-young session becomes attachable (armed at the end).
        long nextAttachAt = Long.MAX_VALUE;
        final long debounceMs = storm ? ATTACH_DEBOUNCE_MS : ATTACH_DEBOUNCE_FAST_MS;
        for (int i = 0; i < list.size(); i++) {
            SessionInfo s = list.get(i);
            present.add(Integer.valueOf(s.sessionId));
            PolicyEngine.Decision d = policy.decide(s, config);
            int action = EnableGate.action(d.excluded, globallyDisabled, d.attach,
                    effects.isAttached(s.sessionId));
            if (action == EnableGate.ACTION_RELEASE) {
                if (effects.isAttached(s.sessionId)) {
                    effects.detach(s.sessionId);
                    bus.forgetSession(s.sessionId);
                    Log.i("detached session=" + s.sessionId + " pkg=" + s.pkg + " ("
                            + (globallyDisabled ? "global switch off, " : "")
                            + d.reason + ")");
                }
                continue;
            }
            if (action == EnableGate.ACTION_BYPASS_HELD) {
                // Bypass-first off: the handle stays in the chain but must be
                // disabled (enabled=0 -> zero audio processing). One idempotent
                // framework call at most; no detach, so no chain rebuild.
                if (effects.isEnabled(s.sessionId)) {
                    effects.setEnabled(s.sessionId, false);
                    Log.i("bypassed session=" + s.sessionId + " pkg=" + s.pkg
                            + " (global switch off, handle kept)");
                }
                continue;
            }
            if (action == EnableGate.ACTION_BYPASS) {
                // Nothing is held and nothing may be attached while the switch is off (or the
                // policy does not want the session): do nothing, no attach, no framework call.
                continue;
            }
            if (d.attach) {
                if (!effects.isAttached(s.sessionId)) {
                    // Adaptive short-session debounce: a brand new session must survive the
                    // debounce window (400 ms normally, 2000 ms while a short-lived-session
                    // storm is active) before we build an effect chain for it. A session that
                    // has been observed for >= 2000 ms (a known, stable session - e.g. the
                    // re-attach after the master switch went on) skips the debounce entirely.
                    // Nothing is held yet, so skipping the attach here is free; a still-alive
                    // session is picked up on a later tick with parameters replayed.
                    Integer key = Integer.valueOf(s.sessionId);
                    Long seenAt = firstSeen.get(key);
                    long nowMs0 = System.currentTimeMillis();
                    if (seenAt == null) {
                        firstSeen.put(key, Long.valueOf(nowMs0));
                        nextAttachAt = Math.min(nextAttachAt, nowMs0 + debounceMs);
                        continue;
                    }
                    if (!mayAttach(seenAt, nowMs0, storm)) {
                        nextAttachAt = Math.min(nextAttachAt,
                                seenAt.longValue() + debounceMs);
                        continue;
                    }
                    // A session whose last attach failed waits for its retry deadline: retrying on
                    // every tick meant one 60-230 ms AudioEffect ctor per 100-500 ms against a
                    // session audioserver refuses to build a chain for.
                    Long retryAt = attachRetryAt.get(key);
                    if (retryAt != null && retryAt.longValue() > nowMs0) {
                        nextAttachAt = Math.min(nextAttachAt, retryAt.longValue());
                        continue;
                    }
                    // The per-session attach work (AudioEffect ctor binder call 60-230 ms +
                    // the parameter replay) runs on the bounded shared pool: sessions are
                    // attached in parallel instead of serially, and the watcher tick itself
                    // never blocks on audioserver.
                    final SessionInfo taskSession = s;
                    final PolicyEngine.Decision taskDecision = d;
                    markAttachInFlight(s.sessionId);
                    attachTasks.add(new Runnable() {
                        @Override
                        public void run() {
                            attachSession(taskSession, taskDecision);
                        }
                    });
                } else {
                    // The attach task is still running (handle published, replay pending):
                    // skip the re-enable path entirely, the task applies and enables.
                    if (isAttachInFlight(s.sessionId)) {
                        continue;
                    }
                    effects.setOwner(s.sessionId, s.uid, s.pkg, s.pid);
                    // Idempotence rule: the framework is only called when the enable state
                    // really changes (a redundant setEnabled() per tick reaches the audio HAL
                    // and was measured to crash system_server on a busy device).
                    if (BypassRule.whileEnabled(effects.isEnabled(s.sessionId))
                            == BypassRule.CALL_ENABLE) {
                        // The instance was disabled (e.g. freshly mounted or re-enabled after a
                        // policy change): re-apply and switch it on.
                        bus.applyAll(s.sessionId, d.params);
                        effects.setEnabled(s.sessionId, true);
                        Log.i("re-enabled session=" + s.sessionId + " pkg=" + s.pkg);
                    }
                }
            } else if (effects.isAttached(s.sessionId)) {
                // Unload semantics: disable (immediate bypass) then release; on ROMs where the
                // EffectHandle lingers the leftover instance is at least left disabled.
                effects.detach(s.sessionId);
                bus.forgetSession(s.sessionId);
                Log.i("detached session=" + s.sessionId + " pkg=" + s.pkg + " (" + d.reason + ")");
            }
        }
        List<Integer> held = effects.attachedSessions();
        for (int i = 0; i < held.size(); i++) {
            Integer id = held.get(i);
            if (!present.contains(id)) {
                effects.detach(id.intValue());
                Log.i("released session=" + id + " (no longer present)");
            }
        }
        // Debounce bookkeeping: forget first-seen stamps of sessions that vanished (a session
        // id that re-appears later starts a fresh observation window) and feed the storm
        // detector with the lifetime of every short-lived one.
        //
        // A session the cheap view cannot see must keep its stamp: a cheap pass legitimately has
        // an empty session list (the probe does not know native players), so pruning it there
        // restarted the debounce window on every dump and the session was never attached - the
        // native player cycled dump(seen) -> cheap pass(forgotten) -> dump(seen) forever. The
        // dump-only set is the memory of "this id is real, only a dump may retire it".
        long nowMs = System.currentTimeMillis();
        java.util.Iterator<Map.Entry<Integer, Long>> seen = firstSeen.entrySet().iterator();
        while (seen.hasNext()) {
            Map.Entry<Integer, Long> entry = seen.next();
            if (!WatcherPolicy.keepFirstSeen(present.contains(entry.getKey()),
                    dumpOnly.contains(entry.getKey()))) {
                recordDeath(nowMs, nowMs - entry.getValue().longValue());
                seen.remove();
            }
        }
        // Launch the parallel attach tasks after the bookkeeping, so a session that appeared
        // and vanished within one tick cannot start an attach for a corpse.
        for (int i = 0; i < attachTasks.size(); i++) {
            attachPool.submit(attachTasks.get(i));
        }
        // Event driven debounce: wake the loop exactly when the next session becomes attachable.
        if (nextAttachAt != Long.MAX_VALUE) {
            armDebounce(nextAttachAt - System.currentTimeMillis());
        }
    }

    /** Arms the one-shot debounce wakeup (at most one pending). */
    private void armDebounce(long delayMs) {
        synchronized (debounceLock) {
            if (debounceTask != null && !debounceTask.isDone()) {
                return;
            }
            debounceTask = debounceTimer.schedule(new Runnable() {
                @Override
                public void run() {
                    poke();
                }
            }, Math.max(1L, delayMs), java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    /** Records a successful attach: the retry ladder restarts for the next failure. */
    private void noteAttachSuccess(int sessionId) {
        attachRetryAt.remove(Integer.valueOf(sessionId));
        attachRetryMs = WatcherPolicy.ATTACH_RETRY_START_MS;
    }

    /**
     * Records a failed attach: the session is retried after a delay that doubles up to
     * {@link WatcherPolicy#ATTACH_RETRY_MAX_MS}. Without it a session audioserver refuses to build
     * a chain for was retried on every tick (100-500 ms), each attempt a 60-230 ms binder call.
     */
    private void noteAttachFailure(int sessionId) {
        attachRetryAt.put(Integer.valueOf(sessionId),
                Long.valueOf(System.currentTimeMillis() + attachRetryMs));
        attachRetryMs = WatcherPolicy.nextAttachRetryMs(attachRetryMs);
    }

    /** Marks a session's attach as in flight (package private, host tested). */
    void markAttachInFlight(int sessionId) {
        synchronized (attachInFlight) {
            attachInFlight.add(Integer.valueOf(sessionId));
        }
    }

    /** Clears the in-flight marker when the attach task finished (package private). */
    void clearAttachInFlight(int sessionId) {
        synchronized (attachInFlight) {
            attachInFlight.remove(Integer.valueOf(sessionId));
        }
    }

    /** True while the parallel attach task of this session has not finished yet. */
    boolean isAttachInFlight(int sessionId) {
        synchronized (attachInFlight) {
            return attachInFlight.contains(Integer.valueOf(sessionId));
        }
    }

    /** One session's attach work (runs on the shared pool): create, replay, enable. */
    void attachSession(SessionInfo s, PolicyEngine.Decision d) {
        try {
            int rc = effects.attach(s.sessionId, s.effectId);
            if (rc == EffectManager.OK) {
                noteAttachSuccess(s.sessionId);
                effects.setOwner(s.sessionId, s.uid, s.pkg, s.pid);
                bus.applyAll(s.sessionId, d.params);
                effects.setEnabled(s.sessionId, true);
                Log.i("attached session=" + s.sessionId + " uid=" + s.uid
                        + " pkg=" + s.pkg + " (" + d.reason + ")");
            } else if (rc != EffectManager.ERR_BUSY) {
                // BUSY means another task is already attaching this session: silent, it will be.
                noteAttachFailure(s.sessionId);
                Log.w("attach session=" + s.sessionId + " failed rc=" + rc);
            }
        } catch (Throwable t) {
            // The attach runs on the shared pool: an escaping exception would kill the task
            // silently and leave the session without a chain until the next pass retried it.
            noteAttachFailure(s.sessionId);
            failures++;
            lastError = "attach session=" + s.sessionId + " threw: " + EffectManager.describe(t);
            Log.e(lastError, t);
        } finally {
            // The in-flight marker must go away whatever the outcome, otherwise the reconcile
            // pass would skip this session's re-enable path forever.
            clearAttachInFlight(s.sessionId);
        }
    }

    /**
     * Compares the dump's session set with the probe's view and logs a mismatch. Called only on a
     * full dumpsys pass (startup, five-minute cross-check, degraded path), so the allocation of
     * the two id sets is irrelevant.
     */
    void crossCheckAgainstProbe(List<SessionInfo> dumpList) {
        List<AudioServiceProbe.Row> rows = probe.rows();
        java.util.TreeSet<Integer> probeIds = new java.util.TreeSet<Integer>();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).sessionId > 0) {
                probeIds.add(Integer.valueOf(rows.get(i).sessionId));
            }
        }
        java.util.TreeSet<Integer> dumpIds = new java.util.TreeSet<Integer>();
        for (int i = 0; i < dumpList.size(); i++) {
            SessionInfo s = dumpList.get(i);
            if (s.sessionId > 0) {
                dumpIds.add(Integer.valueOf(s.sessionId));
            }
        }
        probeRows = rows.size();
        if (!probeIds.equals(dumpIds)) {
            Log.w("watcher crosscheck mismatch: probe=" + probeIds + " dumpsys=" + dumpIds);
        }
    }

    /** uid of a pid: /proc/<pid> is owned by the process' real uid. */
    static int uidOfPid(int pid) {
        if (pid <= 0) {
            return -1;
        }
        try {
            return android.system.Os.stat("/proc/" + pid).st_uid;
        } catch (Throwable ignored) {
            // fall through to the status file
        }
        byte[] raw = FileUtil.readAll(new File("/proc/" + pid + "/status"), 64 * 1024);
        if (raw == null) {
            return -1;
        }
        try {
            String text = new String(raw, "UTF-8");
            String[] lines = text.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.startsWith("Uid:")) {
                    String[] parts = line.substring(4).trim().split("\\s+");
                    return Integer.parseInt(parts[0]);
                }
            }
        } catch (Throwable ignored) {
            // unknown
        }
        return -1;
    }

    /** Last parsed session list (a snapshot, never null). */
    public List<SessionInfo> snapshot() {
        return sessions;
    }

    public long ticks() {
        return ticks;
    }

    /** Full dumpsys passes that produced a session list (diagnostics). */
    public long fullPasses() {
        return fullPasses;
    }

    public long failures() {
        return failures;
    }

    public long lastTickAt() {
        return lastTickAt;
    }

    public int lastDumpBytes() {
        return lastDumpBytes;
    }

    public String lastError() {
        return lastError;
    }

    /** Why the last full pass ran: {@code poke}, {@code change} or {@code fallback}. */
    public String lastPassReason() {
        return lastPassReason;
    }

    /** True when the cheap change detector is usable. */
    public boolean probeAvailable() {
        return probeReady();
    }

    /** Cheap detector queries since start (diagnostics). */
    public long probePolls() {
        return probe == null ? 0L : probe.polls();
    }

    /** Active-set changes the cheap detector reported (diagnostics). */
    public long probeChanges() {
        return probe == null ? 0L : probe.changes();
    }

    /** Cost of the last cheap query in microseconds (diagnostics). */
    public long probeCostUs() {
        return probe == null ? 0L : probe.lastCostUs();
    }

    /** Last probe error, empty when it never failed. */
    public String probeError() {
        return probe == null ? "" : probe.lastError();
    }

    /** "probe" when the last pass was served from the cheap query, "dumpsys" otherwise. */
    public String discoveryMode() {
        return discoveryMode;
    }

    /** Passes served straight from the probe rows since start (no dump). */
    public long probePasses() {
        return probePasses;
    }

    /** Sessions only a dump can see (native players) currently retained across cheap passes. */
    public int dumpOnlyCount() {
        return dumpOnly.size();
    }

    /** True while a native player is playing and the dump therefore drives discovery. */
    public boolean probeBlind() {
        return probeBlind;
    }

    /** Loop iterations served by the cheap isStreamActive gate instead of the config query. */
    public long idleSkips() {
        return idleSkips;
    }

    /** blind -> not blind transitions since start (diagnostics: the ladder must stay stable). */
    public long blindFlips() {
        return blindFlips;
    }

    /** Last isStreamActive(MUSIC) gate value (diagnostics). */
    public boolean probeMusicActive() {
        return probe != null && probe.lastMusicActive();
    }

    /** isStreamActive(MUSIC) gate calls since start (diagnostics). */
    public long probeMusicPolls() {
        return probe != null ? probe.musicPolls() : 0L;
    }

    /** Cost of the last isStreamActive(MUSIC) gate call in microseconds (diagnostics). */
    public long probeMusicCostUs() {
        return probe != null ? probe.lastMusicCostUs() : 0L;
    }

    /** True when at least one probed row carries a real session id (diagnostics). */
    public boolean probeAnySessionId() {
        return probe != null && probe.anySessionId();
    }

    /** Full dumpsys cross-checks of the probe view since start. */
    public long crossChecks() {
        return crossChecks;
    }

    /** Rows (registered players) the probe reported in the last poll. */
    public int probeRows() {
        return probeRows;
    }

    /** Confirmation dumps ("attached but nothing plays") since start. */
    public long confirmDumps() {
        return confirmDumps;
    }

    /** Consecutive failed dumps (0 = the last dump was usable). */
    public int dumpFailStreak() {
        return dumpFailStreak;
    }

    /** Milliseconds until the next dump attempt, 0 when nothing is pending. */
    public long dumpRetryInMs() {
        long at = dumpRetryAt;
        if (at <= 0L) {
            return 0L;
        }
        long left = at - System.currentTimeMillis();
        return left > 0L ? left : 0L;
    }

    /**
     * Age of the last confirmation dump in ms, -1 when none ran yet, -2 while the trigger is not
     * armed (nothing attached, or something plays).
     */
    public long lastConfirmAgoMs() {
        if (confirmArmedAt <= 0L) {
            return -2L;
        }
        long at = lastConfirmAt;
        return at <= 0L ? -1L : System.currentTimeMillis() - at;
    }

    /** Age of the last dumpsys cross-check in ms, -1 when none ran yet. */
    public long lastCrosscheckAgoMs() {
        long at = lastCrosscheckAt;
        return at <= 0L ? -1L : System.currentTimeMillis() - at;
    }

    /** True when the probe can act as the discovery source (real session ids). */
    public boolean probeDiscovering() {
        return probeReady() && probe.sessionIdsUsable();
    }

    /** Applies parameters to every attached session (used by loadPreset). */
    public int reapplyAll() {
        int applied = 0;
        List<SessionInfo> list = sessions;
        for (int i = 0; i < list.size(); i++) {
            SessionInfo s = list.get(i);
            if (effects.isAttached(s.sessionId)) {
                applied += bus.applyAll(s.sessionId, config.paramsFor(s.pkg));
            }
        }
        return applied;
    }
}
