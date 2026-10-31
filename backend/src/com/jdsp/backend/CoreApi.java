package com.jdsp.backend;

import com.jdsp.common.DiagnosticParams;
import com.jdsp.common.EnableErrors;
import com.jdsp.common.EnableSwitch;
import com.jdsp.common.JdspConst;
import com.jdsp.common.ParamReply;
import com.jdsp.common.WritePolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The single implementation behind both transports (AIDL {@code jdsp.service} and the
 * {@code jdsp.sock} JSON line protocol).
 *
 * <p>{@link BinderService} and {@link SocketService} only unpack their wire format and forward
 * here, so both paths behave identically (same status codes, same JSON payloads).
 */
public final class CoreApi {
    /** Reported by getVersion / ping. */
    public static final String VERSION = "jdspd 2.1.6";
    /** Returned by ping(). */
    public static final int PING_OK = 1;
    /**
     * The backend is globally disabled (master switch off): parameter writes and attaches are
     * refused. Distinct from the other error codes so the client can tell "off on purpose" from
     * "something went wrong".
     */
    public static final int ERR_DISABLED = EnableErrors.DISABLED;

    public static final byte[] EMPTY = new byte[0];

    private final ConfigStore config;
    private final PolicyEngine policy;
    private final EffectManager effects;
    private final ParamBus bus;
    private final SessionWatcher watcher;
    private final long startedAt = System.currentTimeMillis();

    /** Implemented by the daemon so a transport can request a graceful shutdown. */
    public interface ShutdownHook {
        void requestShutdown(String reason);
    }

    private volatile ShutdownHook shutdownHook;
    /** Rate limits the global master switch (see {@link EnableSwitch}). */
    private final EnableSwitch enableSwitch = new EnableSwitch();
    /**
     * Background persist for the hot parameter path. A config save is an atomic write
     * (tmp + fsync + rename, plus the mode mirror) and one fsync on /data/adb costs 35-50 ms
     * on the target device: performed inline by {@link #setParam} it was exactly the burst
     * tail latency (p95 ~50 ms at 100 writes/s - the ~5 saves/s landed on ~5% of the requests).
     * The save still happens at most once per throttle interval (the interval itself is the
     * "落盘去抖" that keeps a slider drag from fsyncing per event), it just no longer blocks
     * the caller; the watcher tick's saveIfDirty stays as the safety net.
     */
    private final AsyncPersister persister = new AsyncPersister(new Runnable() {
        @Override
        public void run() {
            long t0 = System.currentTimeMillis();
            try {
                config.saveThrottled(200L);
            } catch (Throwable t) {
                Log.e("async config persist failed", t);
            }
            long took = System.currentTimeMillis() - t0;
            if (took >= 20L) {
                // One line per slow save (at most 5/s by the throttle): makes the flash
                // cost visible without ever spamming the log.
                Log.i("config saved in background in " + took + " ms");
            }
        }
    });
    /**
     * Bypass-first master switch: after the switch went off the instances stay in
     * the chain (disabled) for this long before they are released. Long enough
     * that a user who flips the switch back does not pay the create/replay path,
     * short enough that an "off" device does not keep a dormant effect forever.
     */
    public static final long IDLE_UNLOAD_MS = 20000L;
    /** Fires the deferred "on" transition at its deadline (no 500 ms poll dependency). */
    private final java.util.concurrent.ScheduledExecutorService switchTimer =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "jdspd-switch");
                            t.setDaemon(true);
                            return t;
                        }
                    });
    /** Pending idle unload of the bypassed handles (cancelled by a re-enable). */
    private volatile java.util.concurrent.ScheduledFuture<?> idleUnload;
    private volatile String instanceLockFile = "";
    private volatile long instanceStartedAt;
    private final java.util.concurrent.atomic.AtomicLong unloadSeq =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean binderPublished;
    private volatile int tcpPort = -1;
    private volatile int tcpClients;
    private volatile String tcpPortFile = "";
    private volatile String tcpTokenFile = "";
    private volatile String tcpError = "";

    public CoreApi(ConfigStore config, PolicyEngine policy, EffectManager effects,
                   ParamBus bus, SessionWatcher watcher) {
        this.config = config;
        this.policy = policy;
        this.effects = effects;
        this.bus = bus;
        this.watcher = watcher;
        // The switch must start in the state the configuration says, otherwise the first
        // setEnabled() call would be treated as a transition even when nothing changes.
        this.enableSwitch.transitionApplied(config.isEnabled(), 0L);
    }

    // ---- session views -------------------------------------------------------

    /** Transport neutral view of one session (JSON and AIDL both derive from it). */
    public static final class SessionView {
        public int sessionId;
        public int pid;
        public int uid = -1;
        public String pkg = "";
        public boolean attached;
        public boolean enabled;
        public int paramCount;
        public int effectId = -1;
        public int policyMode = JdspConst.POLICY_FOLLOW;
        public boolean active;
        public boolean hasControl;
        public long attachedAt;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("sessionId", sessionId);
                o.put("pid", pid);
                o.put("uid", uid);
                o.put("pkg", pkg == null ? "" : pkg);
                o.put("attached", attached);
                o.put("enabled", enabled);
                o.put("paramCount", paramCount);
                o.put("effectId", effectId);
                o.put("policyMode", policyMode);
                o.put("active", active);
                o.put("hasControl", hasControl);
            } catch (JSONException e) {
                // cannot happen with these value types
            }
            return o;
        }
    }

    private static final Comparator<SessionView> VIEW_ORDER = new Comparator<SessionView>() {
        @Override
        public int compare(SessionView a, SessionView b) {
            return a.sessionId < b.sessionId ? -1 : (a.sessionId == b.sessionId ? 0 : 1);
        }
    };

    /** All sessions known to the daemon: live dump sessions plus held handles. */
    public List<SessionView> listSessions() {
        Map<Integer, EffectManager.HandleState> states = effects.states();
        List<SessionInfo> snapshot = watcher.snapshot();
        List<SessionView> out = new ArrayList<SessionView>();
        Set<Integer> seen = new HashSet<Integer>();
        for (int i = 0; i < snapshot.size(); i++) {
            SessionInfo s = snapshot.get(i);
            SessionView v = new SessionView();
            v.sessionId = s.sessionId;
            v.pid = s.pid;
            v.uid = s.uid;
            v.pkg = s.pkg == null ? "" : s.pkg;
            v.active = s.active;
            v.effectId = s.effectId;
            EffectManager.HandleState st = states.get(Integer.valueOf(s.sessionId));
            if (st != null) {
                v.attached = true;
                v.enabled = st.enabled;
                v.hasControl = st.hasControl;
                v.paramCount = st.paramCount;
                v.effectId = st.effectId;
                v.attachedAt = st.attachedAt;
                if (st.uid >= 0) {
                    v.uid = st.uid;
                }
                if (st.pkg != null && st.pkg.length() > 0) {
                    v.pkg = st.pkg;
                }
                // Once the owning App dies the session ref belongs to us (we are an effect
                // client), so the dump reports our pid/uid. Keep the real owner for display,
                // but never hide a newer owner that took the session over.
                int myPid = android.os.Process.myPid();
                boolean ownerLooksLikeUs = s.pid == myPid || s.pid <= 0;
                if (ownerLooksLikeUs) {
                    if (st.pid > 0) {
                        v.pid = st.pid;
                    }
                    if (st.uid >= 0) {
                        v.uid = st.uid;
                    }
                    if (st.pkg != null && st.pkg.length() > 0) {
                        v.pkg = st.pkg;
                    }
                }
            }
            v.policyMode = config.getPolicy(v.pkg);
            seen.add(Integer.valueOf(v.sessionId));
            out.add(v);
        }
        for (Map.Entry<Integer, EffectManager.HandleState> e : states.entrySet()) {
            if (seen.contains(e.getKey())) {
                continue;
            }
            EffectManager.HandleState st = e.getValue();
            SessionView v = new SessionView();
            v.sessionId = st.sessionId;
            v.uid = st.uid;
            v.pkg = st.pkg == null ? "" : st.pkg;
            v.attached = true;
            v.enabled = st.enabled;
            v.hasControl = st.hasControl;
            v.paramCount = st.paramCount;
            v.effectId = st.effectId;
            v.attachedAt = st.attachedAt;
            v.policyMode = config.getPolicy(v.pkg);
            out.add(v);
        }
        Collections.sort(out, VIEW_ORDER);
        return out;
    }

    /** One session, or null when the daemon does not know it. */
    public SessionView getSession(int sessionId) {
        List<SessionView> all = listSessions();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).sessionId == sessionId) {
                return all.get(i);
            }
        }
        return null;
    }

    public String sessionsJson() {
        JSONArray arr = new JSONArray();
        List<SessionView> all = listSessions();
        for (int i = 0; i < all.size(); i++) {
            arr.put(all.get(i).toJson());
        }
        return arr.toString();
    }

    // ---- lifecycle -----------------------------------------------------------

    public String getVersion() {
        return VERSION;
    }

    public int ping() {
        return PING_OK;
    }

    public long uptimeMs() {
        return System.currentTimeMillis() - startedAt;
    }

    public void setShutdownHook(ShutdownHook hook) {
        this.shutdownHook = hook;
    }

    /** Single instance guard bookkeeping for stats. */
    public void setInstanceInfo(String lockFile, long startedAt) {
        this.instanceLockFile = lockFile == null ? "" : lockFile;
        this.instanceStartedAt = startedAt;
    }

    /** Graceful stop requested over a transport (extra command, not part of the AIDL contract). */
    public int requestShutdown(String reason) {
        ShutdownHook hook = shutdownHook;
        if (hook == null) {
            return EffectManager.ERR_INTERNAL;
        }
        Log.i("shutdown requested over the control channel (" + reason + ")");
        hook.requestShutdown("control channel: " + reason);
        return 0;
    }

    // ---- effect handles ------------------------------------------------------

    /** Creates or adopts a handle and replays the session's configured parameters. */
    public int attach(int sessionId) {
        if (sessionId <= 0) {
            Log.w("attach: invalid session " + sessionId);
            return EffectManager.ERR_BAD_ARG;
        }
        // The master switch is checked first: an attach that arrives while the backend is off
        // reports "disabled" (-1006), not "no handle" (-1001).
        if (!config.isEnabled()) {
            // The master switch is off: no session may get an effect until it is turned on.
            Log.w("attach: refused, backend is globally disabled (session " + sessionId + ")");
            return ERR_DISABLED;
        }
        int rc = effects.attach(sessionId, -1);
        if (rc != EffectManager.OK) {
            return rc;
        }
        SessionView v = getSession(sessionId);
        String pkg = v == null ? "" : v.pkg;
        int uid = v == null ? -1 : v.uid;
        effects.setOwner(sessionId, uid, pkg, v == null ? -1 : v.pid);
        int applied = bus.applyAll(sessionId, config.paramsFor(pkg));
        Log.i("attach(" + sessionId + ") pkg=" + pkg + " scope="
                + (config.scopeFor(pkg) == ConfigStore.SCOPE_PER_APP ? "per-app" : "global")
                + " params=" + applied);
        effects.setEnabled(sessionId, true);
        Log.i("attach(" + sessionId + ") pkg=" + pkg + " params=" + applied);
        watcher.poke();
        return EffectManager.OK;
    }

    public int detach(int sessionId) {
        int rc = effects.detach(sessionId);
        bus.forgetSession(sessionId);
        watcher.poke();
        return rc;
    }

    public int setParam(int sessionId, int paramId, byte[] value) {
        if (value == null || value.length == 0) {
            return EffectManager.ERR_BAD_ARG;
        }
        // The master switch is checked BEFORE the handle question (see WritePolicy): a write
        // that arrives while the backend is off always reports "disabled" (-1006), also for a
        // session that has no handle yet, so the client can keep the intent and retry later.
        int outcome = WritePolicy.writeOutcome(!config.isEnabled(),
                effects.isAttached(sessionId));
        if (outcome == WritePolicy.DISABLED) {
            // Writing a parameter while the master switch is off would look like it worked
            // (the value would be stored) but nothing would be applied, so it is refused.
            Log.w("setParam: refused, backend is globally disabled (session "
                    + sessionId + " param " + paramId + ")");
            return outcome;
        }
        if (outcome == WritePolicy.NO_HANDLE) {
            // The App expects setParam to work without an explicit attach first.
            int rc = attach(sessionId);
            if (rc != EffectManager.OK) {
                return rc;
            }
        }
        int status = bus.setParam(sessionId, paramId, value);
        if (status == 0 && isPersistable(paramId, value)) {
            // The package for the scope rule comes from the handle: getSession() would
            // rebuild the whole session view list (snapshot + per-session policy lookup
            // + sort) on every single write.
            String pkg = effects.pkgOf(sessionId);
            // One rule for every caller (ConfigStore.scopeFor): global unless the app has its
            // own per app parameters or an explicit policy entry.
            config.setParamFor(pkg, paramId, value);
            // Visible on disk almost immediately, but throttled so a slider drag does not fsync
            // on every event - and, since the async persist, never on the caller's thread
            // (one fsync on /data/adb is 35-50 ms, which used to be the burst p95). Anything
            // throttled here is flushed by the next watcher tick.
            persister.request();
        }
        return status;
    }

    /**
     * One write that applies to every mounted session (the App's "global instance"
     * semantics: its sink is session 0, the handle set lives here).
     *
     * <p>Why in the daemon: the App used to resolve session 0 itself - one
     * {@code sessions} round trip plus one {@code setParam} round trip per mounted
     * session, per slider event. Here it is one request and one config record, and
     * the per-session writes happen in process.
     *
     * @return 0 when at least one session accepted the write (or the intent was
     *         stored while the backend is off / nothing is mounted),
     *         {@link #ERR_DISABLED} when the master switch is off
     */
    public int setParamGlobal(int paramId, byte[] value) {
        if (value == null || value.length == 0) {
            return EffectManager.ERR_BAD_ARG;
        }
        if (!config.isEnabled()) {
            return ERR_DISABLED;
        }
        List<Integer> targets = effects.attachedSessionsCopy();
        int applied = 0;
        int last = 0;
        for (int i = 0; i < targets.size(); i++) {
            last = bus.setParam(targets.get(i).intValue(), paramId, value);
            if (last == 0) {
                applied++;
            }
        }
        if (isPersistable(paramId, value)) {
            config.setParamFor("", paramId, value);
            persister.request();
        }
        if (applied > 0) {
            return 0;
        }
        // Nothing mounted: keep the value (the config replay applies it on attach).
        return targets.isEmpty() ? 0 : last;
    }

    /** One GET_PARAM; returns the value bytes, or an empty array on failure. */
    public byte[] getParam(int sessionId, int paramId, int vsize) {
        int n = normalizeVsize(vsize);
        if (!effects.isAttached(sessionId)) {
            int rc = attach(sessionId);
            if (rc != EffectManager.OK) {
                return EMPTY;
            }
        }
        byte[] reply = new byte[n];
        int rc = bus.getParam(sessionId, paramId, reply);
        if (ParamReply.isSuccess(rc)) {
            // The engine reports how many bytes it wrote: 0 for the classic DSP parameters (they
            // fill the buffer without reporting a size) and the real length for the diagnostic
            // ones (4 for the mix check counters 30023/30024, 112 for the tap status 32002).
            // Both are success - v2.1.5 treated every rc > 0 as a failure, which is why
            // jdspcli get answered "no data" for the whole 30000-32999 range although the
            // daemon log showed the engine had answered (param=32002 rc=112).
            int len = ParamReply.successLength(rc, reply.length);
            if (len == reply.length) {
                return reply;
            }
            byte[] out = new byte[len];
            System.arraycopy(reply, 0, out, 0, len);
            return out;
        }
        // rc < 0: nothing from the engine for this id (the classic GET-less case or an unknown
        // parameter) and no cached write: fall back to the configured value of the session's
        // package (same scope rule as the write path, so get always returns what set stored).
        SessionView v = getSession(sessionId);
        byte[] configured = v == null
                ? config.getParamFor("", paramId) : config.getParamFor(v.pkg, paramId);
        if (configured != null && configured.length > 0) {
            int len = Math.min(n, configured.length);
            byte[] out = new byte[len];
            System.arraycopy(configured, 0, out, 0, len);
            return out;
        }
        Log.w("getParam session=" + sessionId + " param=" + paramId + " rc=" + rc);
        return EMPTY;
    }

    public int commit(int sessionId) {
        return bus.commit(sessionId);
    }

    /** vsize for a GET_PARAM: default 4, rounded up to 4, capped at 4096. */
    public static int normalizeVsize(int vsize) {
        if (vsize <= 0) {
            return 4;
        }
        if (vsize > 4096) {
            return 4096;
        }
        return (vsize + 3) & ~3;
    }

    /**
     * Chunked transfers, huge values and the whole diagnostic range are forwarded to the engine
     * but never written to config.json.
     */
    private static boolean isPersistable(int paramId, byte[] value) {
        if (value.length > 64 * 1024) {
            return false;
        }
        // 30000-32999 (mix check 30023-30028, capture tap 32001/32002): runtime state, not a
        // setting. Persisting it meant the next daemon start replayed it (a stray "set 30025"
        // showed up as a 27th parameter in dump-config and broke the 26 parameter baseline).
        if (DiagnosticParams.isDiagnostic(paramId)) {
            return false;
        }
        switch (paramId) {
            case 8888:
            case 9999:
            case 12000:
            case 12001:
            case 10004:
            case 10006:
            case 10009:
            case 10010:
            case 25000:
            case 25001:
            case 25002:
            case 25003:
                return false;
            default:
                return true;
        }
    }

    // ---- configuration -------------------------------------------------------

    public String dumpConfig() {
        return config.dumpConfig();
    }

    /** Per session diagnostics as JSON (always valid JSON, even for unknown sessions). */
    public String dumpSession(int sessionId) {
        JSONObject o = new JSONObject();
        try {
            SessionView v = getSession(sessionId);
            if (v == null) {
                o.put("error", "unknown session");
                o.put("sessionId", sessionId);
                return o.toString(2);
            }
            o.put("session", v.toJson());
            EffectManager.HandleState st = effects.state(sessionId);
            if (st != null) {
                o.put("attachedAt", st.attachedAt);
                o.put("attachedAgeMs", System.currentTimeMillis() - st.attachedAt);
            }
            JSONObject params = new JSONObject();
            Map<Integer, byte[]> effective = config.paramsFor(v.pkg);
            for (Map.Entry<Integer, byte[]> e : effective.entrySet()) {
                params.put(String.valueOf(e.getKey().intValue()), ConfigStore.hex(e.getValue()));
            }
            JSONObject perApp = new JSONObject();
            Map<Integer, byte[]> overrides = config.perAppParamsFor(v.pkg);
            for (Map.Entry<Integer, byte[]> e : overrides.entrySet()) {
                perApp.put(String.valueOf(e.getKey().intValue()), ConfigStore.hex(e.getValue()));
            }
            o.put("effectiveParams", params);
            o.put("perAppOverrides", perApp);
            o.put("mode", config.getMode());
            o.put("enabled", config.isEnabled());
        } catch (Throwable t) {
            Log.e("dumpSession failed", t);
            return "{\"error\":\"dumpSession failed\",\"sessionId\":" + sessionId + "}";
        }
        try {
            return o.toString(2);
        } catch (Throwable t) {
            return "{\"error\":\"serialisation failed\"}";
        }
    }

    public int loadPreset(String path) {
        int rc = config.loadPreset(path);
        if (rc == 0) {
            watcher.reapplyAll();
            watcher.poke();
        }
        return rc;
    }

    public int savePreset(String path) {
        return config.savePreset(path);
    }

    /**
     * Restores the canonical parameter baseline (see {@link com.jdsp.common.ParamBaseline}):
     * replaces globalParams with the known-good set, optionally clears every perAppParams
     * scope, persists immediately and broadcasts the new values to every currently mounted
     * session (the same replay path a preset load uses).
     *
     * @param clearPerApp true = also clear perAppParams (all scopes)
     * @return the number of engine writes applied to mounted sessions (0 when none are
     *         attached or the master switch is off - the stored configuration is restored
     *         either way)
     */
    public int applyBaseline(boolean clearPerApp) {
        config.applyBaseline(clearPerApp);
        config.save();
        int applied = watcher.reapplyAll();
        watcher.poke();
        Log.i("baseline broadcast: " + applied + " write(s) to mounted session(s)");
        return applied;
    }

    // ---- policy --------------------------------------------------------------

    public int setAppPolicy(String pkg, int mode) {
        if (pkg == null || pkg.length() == 0) {
            return EffectManager.ERR_BAD_ARG;
        }
        if (mode != JdspConst.POLICY_FOLLOW && mode != JdspConst.POLICY_ON
                && mode != JdspConst.POLICY_EXCLUDE) {
            return EffectManager.ERR_BAD_ARG;
        }
        config.setPolicy(pkg, mode);
        config.saveIfDirty();
        watcher.poke();
        Log.i("policy " + pkg + " -> " + mode);
        return 0;
    }

    public int getAppPolicy(String pkg) {
        return config.getPolicy(pkg);
    }

    public String listPolicies() {
        JSONObject o = new JSONObject();
        try {
            JSONObject pol = new JSONObject();
            Map<String, Integer> map = config.policies();
            for (Map.Entry<String, Integer> e : map.entrySet()) {
                pol.put(e.getKey(), e.getValue().intValue());
            }
            o.put("policies", pol);
            o.put("count", map.size());
        } catch (Throwable t) {
            return "{\"policies\":{},\"count\":0}";
        }
        return o.toString();
    }

    // ---- global master switch ------------------------------------------------

    /**
     * Turns the whole backend on or off.
     *
     * <p>false (unload): the daemon detaches every effect handle, so the instances are
     * disabled (immediate bypass) and released. On ROMs that tear the handles down promptly
     * the instances leave the chain entirely; on ROMs where the handles linger the leftovers
     * are disabled (zero audio processing). Parameter writes are refused either way (see
     * {@link #setParam}). The stored configuration is kept, so turning it back on restores the
     * previous state. No engine parameter is written, so this works with every engine build.
     *
     * <p>true (mount on demand): the watcher re-attaches the sessions it is allowed to touch and
     * replays their parameters on its next tick (at most {@link SessionWatcher#POLL_MS} later).
     *
     * @return 0 = ok, {@link #ERR_DISABLED} never happens here, negative = error
     */
    public int setEnabled(boolean enabled) {
        long now = System.currentTimeMillis();
        int decision = enableSwitch.request(enabled, now);
        if (decision == EnableSwitch.NONE) {
            // The backend already has this state: a pure no-op. No config write, no framework
            // call, no watcher poke. This is what keeps an old App that mirrors the state back
            // from causing any churn at all.
            return 0;
        }
        if (decision == EnableSwitch.DEFER) {
            // The "on" direction is rate limited: a burst of requests collapses into one
            // transition. The timer below applies it at the deadline - the watcher tick
            // stays as a fallback only, so the transition no longer waits for a poll.
            Log.i("global enable deferred (rate limited), the switch is currently "
                    + (enableSwitch.applied() ? "on" : "off"));
            scheduleEnablePoll(enableSwitch.pendingDeadlineMs() - now);
            return 0;
        }
        applyEnabled(enabled, now);
        return 0;
    }

    /** Applies a switch state: writes the config, switches the instances, pokes the watcher. */
    private void applyEnabled(boolean enabled, long now) {
        boolean changed = config.isEnabled() != enabled;
        config.setEnabled(enabled);
        config.saveThrottled(200L);
        enableSwitch.transitionApplied(enabled, now);
        if (changed) {
            Log.i("global " + (enabled ? "enabled" : "disabled")
                    + " (policy on/exclude does not override the master switch)");
        }
        if (!enabled) {
            // Bypass first, unload later. Disabling a live instance is one framework
            // call (0.25 ms p50 measured, 400/400 ok, no chain rebuild); releasing it
            // costs 28 ms and the next "on" pays another 150-420 ms of create +
            // 26-parameter replay - and chain churn is what crashed the vendor HAL.
            int n = effects.setEnabledAll(false);
            Log.i("global disabled: " + n + " instance(s) bypassed (unload in "
                    + IDLE_UNLOAD_MS + " ms)");
            scheduleIdleUnload();
        } else {
            // Fast path: whatever is still mounted is simply un-bypassed (~0.25 ms),
            // only what the idle unload already released goes through attach+replay.
            cancelIdleUnload();
            int n = effects.setEnabledAll(true);
            Log.i("global enabled: " + n + " instance(s) un-bypassed");
        }
        // The watcher reconciles the rest: off -> no new attaches (and it releases anything that
        // is somehow still held), on -> re-attach the active sessions and re-apply their
        // parameters on the next tick.
        watcher.poke();
    }

    /**
     * Releases the bypassed handles after {@link #IDLE_UNLOAD_MS} of "off".
     *
     * <p>Runs on the switch timer, never on a request thread, so a user who flips
     * the switch back within the window only pays the bypass path.
     */
    private void scheduleIdleUnload() {
        cancelIdleUnload();
        final long seq = unloadSeq.incrementAndGet();
        idleUnload = switchTimer.schedule(new Runnable() {
            @Override
            public void run() {
                if (seq != unloadSeq.get() || config.isEnabled()) {
                    return; // the switch came back on meanwhile
                }
                int n = effects.detachAll();
                Log.i("idle unload: " + n + " bypassed instance(s) released");
            }
        }, IDLE_UNLOAD_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void cancelIdleUnload() {
        unloadSeq.incrementAndGet();
        java.util.concurrent.ScheduledFuture<?> f = idleUnload;
        idleUnload = null;
        if (f != null) {
            f.cancel(false);
        }
    }

    /**
     * Arms the deferred "on" transition for exactly its deadline.
     *
     * <p>Why not the watcher poll: the tick runs a 78 KB {@code dumpsys
     * media.audio_flinger} (28-150 ms) plus reconcile before it polls the switch,
     * so a deferred transition could wait 500 ms (poll period) + 300 ms (rate
     * limit) + the dumpsys cost. Measured end to end: 612 ms to "applied", 986 ms
     * until the engine instance was enabled again.
     */
    private void scheduleEnablePoll(long delayMs) {
        final long delay = Math.max(1L, delayMs);
        switchTimer.schedule(new Runnable() {
            @Override
            public void run() {
                pollEnableSwitch();
            }
        }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** Current state of the master switch. */
    public boolean getEnabled() {
        return config.isEnabled();
    }

    /**
     * Applies a deferred "on" transition when its rate limit has expired. Called by the watcher
     * every tick, so a burst of "on" requests ends up as exactly one transition.
     */
    public void pollEnableSwitch() {
        long now = System.currentTimeMillis();
        Boolean value = enableSwitch.poll(now);
        if (value == null) {
            return;
        }
        Log.i("applying the deferred global enable");
        applyEnabled(value.booleanValue(), now);
    }

    // ---- mode ----------------------------------------------------------------

    /** mode: 0=auto, 1=backend. Unknown values are rejected. */
    public int setMode(int mode) {
        if (mode != JdspConst.MODE_AUTO && mode != JdspConst.MODE_BACKEND) {
            return EffectManager.ERR_BAD_ARG;
        }
        config.setMode(mode);
        config.saveIfDirty();
        watcher.poke();
        return config.getMode();
    }

    public int getMode() {
        return config.getMode();
    }

    /** Runtime only mode override (--mode); never persisted. */
    public void setModeOverride(int mode) {
        config.setModeOverride(mode);
    }

    // ---- transports / stats --------------------------------------------------

    public void setBinderPublished(boolean published) {
        this.binderPublished = published;
    }

    public boolean isBinderPublished() {
        return binderPublished;
    }

    /** Transport bookkeeping for stats (called by {@link TcpService}). */
    public void setTcpInfo(int port, int clients, String portFile, String tokenFile,
                           String error) {
        this.tcpPort = port;
        this.tcpClients = clients;
        this.tcpPortFile = portFile == null ? "" : portFile;
        this.tcpTokenFile = tokenFile == null ? "" : tokenFile;
        this.tcpError = error == null ? "" : error;
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public String getTcpPortFile() {
        return tcpPortFile;
    }

    public ConfigStore config() {
        return config;
    }

    public PolicyEngine policy() {
        return policy;
    }

    public EffectManager effects() {
        return effects;
    }

    public ParamBus bus() {
        return bus;
    }

    public SessionWatcher watcher() {
        return watcher;
    }

    /** Runtime statistics as a JSON object (always valid JSON). */
    public String stats() {
        JSONObject o = new JSONObject();
        ParamBus.Stats ps = bus.stats();
        try {
            o.put("version", VERSION);
            o.put("pid", android.os.Process.myPid());
            o.put("uptimeMs", uptimeMs());
            o.put("sessions", watcher.snapshot().size());
            o.put("attached", effects.handleCount());
            o.put("setParamCalls", ps.setCalls);
            o.put("getParamCalls", ps.getCalls);
            o.put("paramErrors", ps.errors);
            o.put("avgLatencyUs", bus.averageLatencyUs());
            o.put("lastLatencyUs", ps.lastLatencyUs);
            o.put("maxLatencyUs", ps.maxLatencyUs);
            o.put("batchFlushes", ps.batchFlushes);
            o.put("batchMerged", ps.batchMerged);
            o.put("bulkApplied", ps.bulkApplied);
            o.put("getParamCacheHits", ps.cacheHits);
            o.put("getParamEngineHits", ps.engineHits);
            o.put("watcherTicks", watcher.ticks());
            o.put("watcherFailures", watcher.failures());
            o.put("watcherLastAgoMs", watcher.lastTickAt() == 0 ? -1
                    : System.currentTimeMillis() - watcher.lastTickAt());
            o.put("dumpsysChars", watcher.lastDumpBytes());
            o.put("watcherError", watcher.lastError());
            o.put("stormDeaths", watcher.stormDeathCount());
            o.put("mode", config.getMode());
            o.put("modeName", config.getMode() == JdspConst.MODE_BACKEND ? "backend" : "auto");
            o.put("enabled", config.isEnabled());
            o.put("globalParams", config.globalParamCount());
            o.put("policies", config.policies().size());
            o.put("packages", policy.packageCount());
            o.put("policyError", policy.lastError());
            o.put("configPath", config.getFile() == null ? "" : config.getFile().getPath());
            o.put("lockFile", instanceLockFile);
            o.put("instanceStartedAt", instanceStartedAt);
            o.put("effectReflection", EffectManager.reflectionStatus());
            JSONObject transport = new JSONObject();
            transport.put("binder", binderPublished);
            JSONObject tcp = new JSONObject();
            tcp.put("port", tcpPort);
            tcp.put("clients", tcpClients);
            tcp.put("listening", tcpPort > 0);
            tcp.put("error", tcpError);
            transport.put("tcp", tcp);
            transport.put("portFile", tcpPortFile);
            transport.put("tokenFile", tcpTokenFile);
            o.put("transport", transport);
        } catch (Throwable t) {
            try {
                return "{\"error\":\"" + EffectManager.describe(t).replace('"', '\'') + "\"}";
            } catch (Throwable ignored) {
                return "{\"error\":\"stats failed\"}";
            }
        }
        return o.toString();
    }
}
