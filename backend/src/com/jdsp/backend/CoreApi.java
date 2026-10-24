package com.jdsp.backend;

import com.jdsp.common.JdspConst;

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
    public static final String VERSION = "jdspd 2.0.0";
    /** Returned by ping(). */
    public static final int PING_OK = 1;

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
    private volatile String instanceLockFile = "";
    private volatile long instanceStartedAt;
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
        int rc = effects.attach(sessionId, -1);
        if (rc != EffectManager.OK) {
            return rc;
        }
        SessionView v = getSession(sessionId);
        String pkg = v == null ? "" : v.pkg;
        int uid = v == null ? -1 : v.uid;
        effects.setOwner(sessionId, uid, pkg, v == null ? -1 : v.pid);
        int applied = bus.applyAll(sessionId, config.paramsFor(pkg));
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
        if (!effects.isAttached(sessionId)) {
            // The App expects setParam to work without an explicit attach first.
            int rc = attach(sessionId);
            if (rc != EffectManager.OK) {
                return rc;
            }
        }
        int status = bus.setParam(sessionId, paramId, value);
        if (status == 0 && isPersistable(paramId, value)) {
            SessionView v = getSession(sessionId);
            String pkg = v == null ? "" : v.pkg;
            if (pkg != null && pkg.length() > 0) {
                config.setPerAppParam(pkg, paramId, value);
            } else {
                config.setGlobalParam(paramId, value);
            }
        }
        return status;
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
        if (rc == 0) {
            return reply;
        }
        // Nothing from the engine (the clean engine has no GET_PARAM) and no cached write:
        // fall back to the configured value of the session's package.
        SessionView v = getSession(sessionId);
        byte[] configured = v == null ? null : config.paramsFor(v.pkg).get(Integer.valueOf(paramId));
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

    /** Chunked transfers and huge values are forwarded but never written to config.json. */
    private static boolean isPersistable(int paramId, byte[] value) {
        if (value.length > 64 * 1024) {
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
