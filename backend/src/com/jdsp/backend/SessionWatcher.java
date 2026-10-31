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
                loop();
            }
        }, "jdspd-watcher");
        thread.setDaemon(true);
        thread.start();
        Log.i("session watcher started (event driven: probe " + WatcherPolicy.PROBE_MS
                + "ms, full pass every " + WatcherPolicy.FALLBACK_MS
                + "ms, legacy " + WatcherPolicy.LEGACY_POLL_MS + "ms when the probe is unavailable)");
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
     * The event loop: sleep until a trigger, run a full pass, repeat.
     *
     * <p>Order per iteration: consume a pending poke, check the fallback deadline, otherwise ask
     * the cheap detector whether the active playback set changed. Only then does a dumpsys pass
     * run, so the expensive path is proportional to real audio activity instead of wall clock.
     */
    private void loop() {
        long lastPassAt = 0L;
        while (running) {
            long now = System.currentTimeMillis();
            boolean poke = takeWake();
            // Without the cheap probe a full pass runs every LEGACY_POLL_MS (v2.1.8): the
            // degraded path must never be slower than the pre-event-driven daemon.
            boolean fallback = WatcherPolicy.fallbackDue(probeReady(), now, lastPassAt);
            boolean changed = false;
            if (!poke && !fallback) {
                changed = pollProbe();
                if (changed) {
                    // Coalesce the burst of player events one track switch produces.
                    sleepQuietly(WatcherPolicy.CHANGE_DEBOUNCE_MS);
                }
            }
            if (WatcherPolicy.shouldRunPass(poke, fallback, changed)) {
                lastPassReason = poke ? "poke" : (changed ? "change" : "fallback");
                try {
                    pollOnce();
                } catch (Throwable t) {
                    failures++;
                    lastError = EffectManager.describe(t);
                    Log.e("watcher tick failed: " + lastError, t);
                }
                lastPassAt = System.currentTimeMillis();
                if (fallback) {
                    // Only the slow pass carries the non hot maintenance work.
                    slowMaintenance();
                }
                continue;
            }
            synchronized (wakeLock) {
                if (!running) {
                    break;
                }
                if (!wake) {
                    try {
                        wakeLock.wait(WatcherPolicy.nextSleepMs(probeReady()));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                // Deliberately do NOT clear the wake flag here: takeWake() at the top of the next
                // iteration consumes it and runs the pass. Clearing it here swallowed the poke
                // that interrupted the wait (measured: a new stream then waited for the 3 s
                // fallback pass instead of the 400 ms attach debounce).
            }
        }
        Log.i("session watcher stopped");
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
    public void pollOnce() {
        long t0 = System.currentTimeMillis();
        String dump = runDumpsys();
        ticks++;
        lastTickAt = t0;
        if (dump == null) {
            failures++;
            return;
        }
        fullPasses++;
        lastDumpBytes = dump.length();
        List<SessionInfo> list = DumpsysParser.parse(dump);
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
            Log.i("watcher pass reason=" + lastPassReason + " sessions=" + list.size()
                    + " dumpMs=" + passMs);
        }
        reconcile(list);
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
            Log.i("watcher: passes=" + fullPasses + " probePolls=" + probePolls()
                    + " probeChanges=" + probeChanges() + " probeCostUs=" + probeCostUs()
                    + " probe=" + (probeAvailable() ? "on" : "off")
                    + (probe == null ? "" : " probeAttempts=" + probe.setupAttempts()
                            + " probeSetupFail=" + probe.setupFailures())
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
            while ((line = reader.readLine()) != null) {
                total += line.length() + 1;
                if (total > MAX_DUMP_BYTES) {
                    lastError = "dumpsys output too large";
                    Log.w(lastError);
                    break;
                }
                sb.append(line).append('\n');
            }
            process.waitFor();
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
        long nowMs = System.currentTimeMillis();
        java.util.Iterator<Map.Entry<Integer, Long>> seen = firstSeen.entrySet().iterator();
        while (seen.hasNext()) {
            Map.Entry<Integer, Long> entry = seen.next();
            if (!present.contains(entry.getKey())) {
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
                effects.setOwner(s.sessionId, s.uid, s.pkg, s.pid);
                bus.applyAll(s.sessionId, d.params);
                effects.setEnabled(s.sessionId, true);
                Log.i("attached session=" + s.sessionId + " uid=" + s.uid
                        + " pkg=" + s.pkg + " (" + d.reason + ")");
            } else if (rc != EffectManager.ERR_BUSY) {
                // BUSY means another task is already attaching this session: silent, it will be.
                Log.w("attach session=" + s.sessionId + " failed rc=" + rc);
            }
        } finally {
            // The in-flight marker must go away whatever the outcome, otherwise the reconcile
            // pass would skip this session's re-enable path forever.
            clearAttachInFlight(s.sessionId);
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
