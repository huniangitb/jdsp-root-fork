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

/**
 * Polls {@code dumpsys media.audio_flinger} once per second, resolves sessions to (pid, uid, pkg)
 * and reconciles the effect handles: new sessions get attached (and their configured parameters
 * replayed plus {@code setEnabled(true)}), vanished or excluded ones are released.
 *
 * <p>The shell command runs through {@code sh -c} with a hard 3 s watchdog; a hung dumpsys can
 * never wedge the daemon (the next tick simply tries again).
 */
public final class SessionWatcher {
    /**
     * Poll period. Was 1000 ms; 500 ms halves the worst-case discovery latency of a new
     * playback session (dumpsys media.audio_flinger is ~75 KB here and parses in low tens of
     * ms, so two passes per second cost a few percent of one core at most).
     */
    public static final long POLL_MS = 500L;
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
    private volatile long failures;
    private volatile long lastTickAt;
    private volatile int lastDumpBytes;
    private volatile String lastError = "";
    private long lastEndpointCheck;
    /** sessionId -> wall clock of the tick the session was first seen (short-session debounce). */
    private final Map<Integer, Long> firstSeen = new HashMap<Integer, Long>();
    /**
     * Short-lived session deaths inside the storm window: one long[]{diedAt, lifetimeMs} per
     * vanished session whose lifetime was below {@link #STORM_LIFETIME_MS}. Pruned lazily.
     */
    private final java.util.ArrayDeque<long[]> recentDeaths = new java.util.ArrayDeque<long[]>();
    private volatile int stormDeaths;
    /** Worker pool for the per-session attach tasks (bounded, shared with EffectManager). */
    private final java.util.concurrent.ExecutorService attachPool = EffectManager.sharedPool();

    public SessionWatcher(ConfigStore config, PolicyEngine policy, EffectManager effects,
                          ParamBus bus) {
        this.config = config;
        this.policy = policy;
        this.effects = effects;
        this.bus = bus;
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
        Log.i("session watcher started (poll " + POLL_MS + "ms)");
    }

    public void stop() {
        running = false;
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

    /** Makes the watcher run a pass immediately (used after policy/mode changes). */
    public void poke() {
        synchronized (wakeLock) {
            wake = true;
            wakeLock.notifyAll();
        }
    }

    private void loop() {
        while (running) {
            try {
                pollOnce();
            } catch (Throwable t) {
                failures++;
                lastError = EffectManager.describe(t);
                Log.e("watcher tick failed: " + lastError, t);
            }
            synchronized (wakeLock) {
                if (!running) {
                    break;
                }
                if (!wake) {
                    try {
                        wakeLock.wait(POLL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                wake = false;
            }
        }
        Log.i("session watcher stopped");
    }

    /** Runs exactly one watcher pass (also used by --selftest). */
    public void pollOnce() {
        long t0 = System.currentTimeMillis();
        String dump = runDumpsys();
        ticks++;
        lastTickAt = t0;
        if (dump == null) {
            failures++;
            config.saveIfDirty();
            return;
        }
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
        reconcile(list);
        config.saveIfDirty();
        refreshEndpointFiles();
        // Apply a "on" transition that was rate limited (see EnableSwitch).
        if (api != null) {
            api.pollEnableSwitch();
        }
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
                    if (seenAt == null) {
                        firstSeen.put(key, Long.valueOf(System.currentTimeMillis()));
                        continue;
                    }
                    if (!mayAttach(seenAt, System.currentTimeMillis(), storm)) {
                        continue;
                    }
                    // The per-session attach work (AudioEffect ctor binder call 60-230 ms +
                    // the parameter replay) runs on the bounded shared pool: sessions are
                    // attached in parallel instead of serially, and the watcher tick itself
                    // never blocks on audioserver.
                    final SessionInfo taskSession = s;
                    final PolicyEngine.Decision taskDecision = d;
                    attachTasks.add(new Runnable() {
                        @Override
                        public void run() {
                            attachSession(taskSession, taskDecision);
                        }
                    });
                } else {
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
    }

    /** One session's attach work (runs on the shared pool): create, replay, enable. */
    private void attachSession(SessionInfo s, PolicyEngine.Decision d) {
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
