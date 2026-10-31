package com.jdsp.backend;

import com.jdsp.common.DiagnosticParams;
import com.jdsp.common.JdspConst;
import com.jdsp.common.ParamScope;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Authoritative configuration of the daemon: {@code /data/adb/jdspd/config.json}.
 *
 * <pre>
 * {
 *   "version": 1,
 *   "mode": 0,                       // 0=auto (framework postprocess creates, jdspd attaches)
 *                                    // 1=backend (jdspd creates the instance itself)
 *   "enabled": true,
 *   "globalParams": { "1500": "cdccccbd00007042", ... },
 *   "policies": { "com.example.music": 2, ... },
 *   "perAppParams": { "com.example.music": { "1500": "..." } },
 *   "importedFrom": "/data/vendor/audio/jamesdsp_state.bin",
 *   "updatedAt": 1690000000
 * }
 * </pre>
 *
 * <p>On the very first start (no config file) the native engine state file is imported once and
 * persisted immediately, so an existing installation keeps its parameters. Afterwards this file
 * is the only source of truth.
 *
 * <p>All writes are atomic (tmp + fsync + rename, mode 0600).
 */
public final class ConfigStore {
    public static final int VERSION = 1;
    /** Upper bound for a config/preset file (4 MiB is far more than a preset ever needs). */
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private static final int FILE_MODE = 0600;

    private final File file;

    private int mode = JdspConst.MODE_AUTO;
    private boolean enabled = true;
    private final Map<Integer, byte[]> globalParams = new LinkedHashMap<Integer, byte[]>();
    private final Map<String, Integer> policies = new LinkedHashMap<String, Integer>();
    private final Map<String, Map<Integer, byte[]>> perAppParams =
            new LinkedHashMap<String, Map<Integer, byte[]>>();
    private String importedFrom = "";
    private long updatedAt;
    private boolean dirty;
    /** Runtime only override (--mode), never serialised. -1 = use the stored mode. */
    private int modeOverride = -1;
    private long lastSaveAt;

    public ConfigStore(File file) {
        this.file = file;
    }

    public File getFile() {
        return file;
    }

    /** Loads the config, or creates it from the engine state file on first run. */
    public synchronized void load() {
        byte[] raw = FileUtil.readAll(file, MAX_FILE_BYTES);
        if (raw == null) {
            Log.i("config: " + file + " not present, trying engine state import");
            importFromEngineStateLocked();
            dirty = true;
            if (!saveLocked()) {
                Log.w("config: initial save failed (" + file + ")");
            }
            return;
        }
        try {
            int dropped = parseLocked(new JSONObject(new String(raw, "UTF-8")));
            dirty = false;
            Log.i("config loaded: mode=" + mode + " params=" + globalParams.size()
                    + " policies=" + policies.size() + " perApp=" + perAppParams.size()
                    + (dropped > 0 ? " (dropped " + dropped + " diagnostic param(s))" : ""));
            if (dropped > 0) {
                // The file still holds the diagnostic keys; rewrite it so dump-config, the replay
                // on attach and a preset export stay clean from now on.
                if (!saveLocked()) {
                    Log.w("config: rewrite after dropping diagnostics failed (" + file + ")");
                }
            }
        } catch (Throwable t) {
            Log.e("config parse failed, keeping a backup and starting from engine state", t);
            File bad = new File(file.getPath() + ".bad");
            if (bad.exists()) {
                bad.delete();
            }
            file.renameTo(bad);
            importFromEngineStateLocked();
            dirty = true;
            saveLocked();
        }
    }

    /** Imports params from the native engine state file (used on first run). */
    public synchronized boolean importFromEngineState() {
        return importFromEngineStateLocked();
    }

    private boolean importFromEngineStateLocked() {
        File stateFile = new File(JdspConst.ENGINE_STATE_FILE);
        importedFrom = "";
        if (!stateFile.isFile()) {
            Log.i("engine state import: " + stateFile + " not present, starting empty");
            return false;
        }
        StateBinCodec.Result r = StateBinCodec.parseFile(stateFile);
        if (!r.ok()) {
            Log.w("engine state import skipped: " + r.error + " (" + stateFile + ")");
            return false;
        }
        Map<Integer, byte[]> map = r.toMap();
        globalParams.clear();
        globalParams.putAll(map);
        // Same rule as the config file: the diagnostic range never enters the store, so it
        // cannot be replayed into an attach either.
        int dropped = dropDiagnostics(globalParams);
        if (dropped > 0) {
            Log.i("engine state import: dropped " + dropped + " diagnostic param(s)");
        }
        if (r.sawEnableRecord) {
            enabled = r.enabled;
        }
        importedFrom = JdspConst.ENGINE_STATE_FILE;
        updatedAt = System.currentTimeMillis() / 1000L;
        Log.i("engine state imported: " + map.size() + " param(s) from " + r.recordCount
                + " record(s), chunked skipped=" + r.skippedChunked
                + " invalid=" + r.skippedInvalid);
        return !map.isEmpty();
    }

    /**
     * Serialises the file writes. The payload is built under the config monitor,
     * the fsync happens under this lock only, so a save can never make a reader
     * (the hot parameter path calls isEnabled()/setParamFor() per write) wait for
     * the flash: one fsync on /data/adb is 35-50 ms and used to be the burst tail.
     */
    private final Object fileLock = new Object();

    /** Writes the config when something changed. Also called from the watcher tick. */
    public boolean saveIfDirty() {
        return saveThrottled(0L);
    }

    /**
     * Writes the config when dirty, at most once per {@code minIntervalMs}. Used by the hot
     * parameter path so a slider drag does not fsync on every event, while a single set is still
     * on disk almost immediately (the watcher tick flushes anything throttled within 1 s).
     */
    public boolean saveThrottled(long minIntervalMs) {
        byte[] data;
        synchronized (this) {
            if (!dirty) {
                return true;
            }
            if (minIntervalMs > 0L && System.currentTimeMillis() - lastSaveAt < minIntervalMs) {
                return true;
            }
            data = payloadLocked();
            dirty = false;
        }
        return writePayload(data);
    }

    public boolean save() {
        byte[] data;
        synchronized (this) {
            dirty = true;
            data = payloadLocked();
            dirty = false;
        }
        return writePayload(data);
    }

    /** Snapshot of the config as bytes; the caller holds the monitor. */
    private byte[] payloadLocked() {
        try {
            JSONObject o = toJsonLocked();
            o.put("updatedAt", System.currentTimeMillis() / 1000L);
            return o.toString(2).getBytes("UTF-8");
        } catch (Throwable t) {
            Log.e("config serialisation failed", t);
            return null;
        }
    }

    /** Atomic write of a payload snapshot; never called with the config monitor held. */
    private boolean writePayload(byte[] data) {
        if (data == null) {
            return false;
        }
        synchronized (fileLock) {
            try {
                if (!FileUtil.writeAtomic(file, data, FILE_MODE)) {
                    Log.w("config save failed: " + file);
                    return false;
                }
                long now = System.currentTimeMillis();
                updatedAt = now / 1000L;
                lastSaveAt = now;
                writeModeMirrorLocked();
                return true;
            } catch (Throwable t) {
                Log.e("config save failed", t);
                return false;
            }
        }
    }

    /** Save path for the callers that already hold the monitor (load / import). */
    private boolean saveLocked() {
        return writePayload(payloadLocked());
    }

    // ---- mode / enable -------------------------------------------------------

    /** Effective mode: the runtime override (--mode) when present, else the stored mode. */
    public synchronized int getMode() {
        int override = modeOverride;
        return override >= 0 ? override : mode;
    }

    /** Runtime only mode override (used by --mode); cleared by an explicit setMode. */
    public synchronized void setModeOverride(int override) {
        if (override == JdspConst.MODE_AUTO || override == JdspConst.MODE_BACKEND) {
            modeOverride = override;
        } else {
            modeOverride = -1;
        }
    }

    public synchronized void setMode(int newMode) {
        modeOverride = -1;
        int m = newMode == JdspConst.MODE_BACKEND ? JdspConst.MODE_BACKEND : JdspConst.MODE_AUTO;
        if (m != mode) {
            mode = m;
            dirty = true;
            Log.i("mode -> " + (m == JdspConst.MODE_BACKEND ? "backend" : "auto"));
        }
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized void setEnabled(boolean on) {
        if (enabled != on) {
            enabled = on;
            dirty = true;
        }
    }

    // ---- parameters ----------------------------------------------------------

    public synchronized byte[] getGlobalParam(int paramId) {
        byte[] v = globalParams.get(Integer.valueOf(paramId));
        return v == null ? null : v.clone();
    }

    /** Sets (or removes, when value is null/empty) one global parameter. */
    public synchronized void setGlobalParam(int paramId, byte[] value) {
        if (DiagnosticParams.isDiagnostic(paramId)) {
            // 30000-32999 is runtime state (counters, an armed flag, a ring buffer path), never a
            // setting: it reaches the engine but is not stored, so it cannot be replayed later.
            return;
        }
        Integer key = Integer.valueOf(paramId);
        if (value == null || value.length == 0) {
            globalParams.remove(key);
        } else {
            globalParams.put(key, value.clone());
        }
        dirty = true;
    }

    public synchronized void setPerAppParam(String pkg, int paramId, byte[] value) {
        if (DiagnosticParams.isDiagnostic(paramId)) {
            return; // see setGlobalParam: the diagnostic range is never persisted
        }
        if (pkg == null || pkg.length() == 0) {
            setGlobalParam(paramId, value);
            return;
        }
        Map<Integer, byte[]> map = perAppParams.get(pkg);
        if (map == null) {
            map = new LinkedHashMap<Integer, byte[]>();
            perAppParams.put(pkg, map);
        }
        Integer key = Integer.valueOf(paramId);
        if (value == null || value.length == 0) {
            map.remove(key);
            if (map.isEmpty()) {
                perAppParams.remove(pkg);
            }
        } else {
            map.put(key, value.clone());
        }
        dirty = true;
    }

    /**
     * Replaces the global parameters with the canonical baseline and (optionally) clears
     * every perAppParams scope. Used by {@code jdspcli baseline [--all]} to restore a
     * known-good configuration after test runs or garbage pushes; see {@link ParamBaseline}
     * (com.jdsp.common) for the value set and its history.
     */
    public synchronized void applyBaseline(boolean clearPerApp) {
        globalParams.clear();
        globalParams.putAll(com.jdsp.common.ParamBaseline.params());
        if (clearPerApp) {
            perAppParams.clear();
        }
        dirty = true;
        Log.i("baseline applied: " + globalParams.size() + " global param(s)"
                + (clearPerApp ? ", perAppParams cleared" : ", perAppParams kept"));
    }

    /**
     * Removes every diagnostic parameter (30000-32999) from {@code map} in place.
     *
     * <p>Used by both parse paths (config file, engine state import): those ids are runtime
     * state - counters, an armed flag, a ring buffer path - and replaying them into an attach is
     * never useful, while storing them breaks the 26 parameter baseline (v2.1.5 wrote a stray
     * {@code 30025} that came back as a 27th parameter after a restart).
     *
     * @return how many entries were dropped
     */
    static int dropDiagnostics(Map<Integer, byte[]> map) {
        if (map == null || map.isEmpty()) {
            return 0;
        }
        int dropped = 0;
        java.util.Iterator<Integer> it = map.keySet().iterator();
        while (it.hasNext()) {
            Integer id = it.next();
            if (id != null && DiagnosticParams.isDiagnostic(id.intValue())) {
                it.remove();
                dropped++;
            }
        }
        return dropped;
    }

    /** Global parameters overlaid with the per app overrides of {@code pkg}. */
    public synchronized Map<Integer, byte[]> paramsFor(String pkg) {
        Map<Integer, byte[]> out = new LinkedHashMap<Integer, byte[]>();
        for (Map.Entry<Integer, byte[]> e : globalParams.entrySet()) {
            out.put(e.getKey(), e.getValue().clone());
        }
        if (pkg != null && pkg.length() > 0) {
            Map<Integer, byte[]> per = perAppParams.get(pkg);
            if (per != null) {
                for (Map.Entry<Integer, byte[]> e : per.entrySet()) {
                    out.put(e.getKey(), e.getValue().clone());
                }
            }
        }
        return out;
    }

    // ---- parameter scope: one rule for every caller --------------------------

    /** The parameter is a global one (applies to every session). */
    public static final int SCOPE_GLOBAL = ParamScope.GLOBAL;
    /** The parameter belongs to one app (perAppParams[pkg]). */
    public static final int SCOPE_PER_APP = ParamScope.PER_APP;

    /**
     * The single rule that decides where a parameter is stored, used by setParam (write),
     * getParam (read) and the replay on attach.
     *
     * <ol>
     *   <li>no package (unknown owner) -&gt; global;</li>
     *   <li>the package already has per app parameters, or it has an explicit policy entry
     *       (i.e. the user configured something for that app) -&gt; per app;</li>
     *   <li>otherwise -&gt; global.</li>
     * </ol>
     *
     * <p>Consequence: the App's own parameters stay global by default (perAppParams stays
     * empty), and only apps the user configured explicitly get their own copy. This is the
     * same decision for writing and for reading, so {@code get} always returns what {@code set}
     * stored.
     */
    public synchronized int scopeFor(String pkg) {
        boolean hasPerApp = pkg != null && pkg.length() > 0
                && perAppParams.containsKey(pkg);
        boolean hasPolicy = pkg != null && pkg.length() > 0 && policies.containsKey(pkg);
        return ParamScope.decide(pkg, hasPerApp, hasPolicy);
    }

    /** Stores a parameter according to {@link #scopeFor}. */
    public synchronized void setParamFor(String pkg, int paramId, byte[] value) {
        if (scopeFor(pkg) == SCOPE_PER_APP) {
            setPerAppParam(pkg, paramId, value);
        } else {
            setGlobalParam(paramId, value);
        }
    }

    /**
     * Reads a parameter according to {@link #scopeFor}; when the preferred scope has no value the
     * other scope is used, so nothing ever becomes unreadable.
     */
    public synchronized byte[] getParamFor(String pkg, int paramId) {
        if (scopeFor(pkg) == SCOPE_PER_APP) {
            byte[] v = perAppValue(pkg, paramId);
            return v != null ? v : getGlobalParam(paramId);
        }
        byte[] v = getGlobalParam(paramId);
        return v != null ? v : perAppValue(pkg, paramId);
    }

    /** One value of the per app map of {@code pkg} (null when absent). */
    private byte[] perAppValue(String pkg, int paramId) {
        if (pkg == null || pkg.length() == 0) {
            return null;
        }
        Map<Integer, byte[]> map = perAppParams.get(pkg);
        if (map == null) {
            return null;
        }
        byte[] v = map.get(Integer.valueOf(paramId));
        return v == null ? null : v.clone();
    }

    /** Per app parameters only (used by dumpSession). */
    public synchronized Map<Integer, byte[]> perAppParamsFor(String pkg) {
        Map<Integer, byte[]> out = new LinkedHashMap<Integer, byte[]>();
        Map<Integer, byte[]> per = pkg == null ? null : perAppParams.get(pkg);
        if (per != null) {
            for (Map.Entry<Integer, byte[]> e : per.entrySet()) {
                out.put(e.getKey(), e.getValue().clone());
            }
        }
        return out;
    }

    public synchronized int globalParamCount() {
        return globalParams.size();
    }

    // ---- policy --------------------------------------------------------------

    public synchronized int getPolicy(String pkg) {
        if (pkg == null) {
            return JdspConst.POLICY_FOLLOW;
        }
        Integer m = policies.get(pkg);
        return m == null ? JdspConst.POLICY_FOLLOW : m.intValue();
    }

    public synchronized void setPolicy(String pkg, int policyMode) {
        if (pkg == null || pkg.length() == 0) {
            return;
        }
        int m = policyMode;
        if (m != JdspConst.POLICY_ON && m != JdspConst.POLICY_EXCLUDE) {
            m = JdspConst.POLICY_FOLLOW;
        }
        if (m == JdspConst.POLICY_FOLLOW) {
            policies.remove(pkg);
        } else {
            policies.put(pkg, Integer.valueOf(m));
        }
        dirty = true;
    }

    public synchronized Map<String, Integer> policies() {
        return new LinkedHashMap<String, Integer>(policies);
    }

    // ---- serialisation -------------------------------------------------------

    private JSONObject toJsonLocked() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("version", VERSION);
        o.put("mode", mode);
        o.put("modeName", mode == JdspConst.MODE_BACKEND ? "backend" : "auto");
        o.put("enabled", enabled);
        JSONObject gp = new JSONObject();
        for (Map.Entry<Integer, byte[]> e : globalParams.entrySet()) {
            gp.put(String.valueOf(e.getKey().intValue()), hex(e.getValue()));
        }
        o.put("globalParams", gp);
        JSONObject pol = new JSONObject();
        for (Map.Entry<String, Integer> e : policies.entrySet()) {
            pol.put(e.getKey(), e.getValue().intValue());
        }
        o.put("policies", pol);
        JSONObject per = new JSONObject();
        for (Map.Entry<String, Map<Integer, byte[]>> e : perAppParams.entrySet()) {
            JSONObject inner = new JSONObject();
            for (Map.Entry<Integer, byte[]> p : e.getValue().entrySet()) {
                inner.put(String.valueOf(p.getKey().intValue()), hex(p.getValue()));
            }
            per.put(e.getKey(), inner);
        }
        o.put("perAppParams", per);
        o.put("importedFrom", importedFrom);
        o.put("updatedAt", updatedAt);
        o.put("path", file == null ? "" : file.getPath());
        return o;
    }

    /**
     * Parses one config/preset document into this store.
     *
     * @return how many diagnostic parameters (30000-32999) were dropped; they are runtime state
     *         and are neither replayed nor shown by dump-config (v2.1.5 wrote a stray
     *         {@code 30025} into the file, which then replayed as a 27th parameter)
     */
    private int parseLocked(JSONObject o) throws JSONException {
        int dropped = 0;
        mode = o.optInt("mode", JdspConst.MODE_AUTO) == JdspConst.MODE_BACKEND
                ? JdspConst.MODE_BACKEND : JdspConst.MODE_AUTO;
        enabled = o.optBoolean("enabled", true);
        globalParams.clear();
        JSONObject gp = o.optJSONObject("globalParams");
        if (gp != null) {
            java.util.Iterator<String> it = gp.keys();
            while (it.hasNext()) {
                String key = it.next();
                byte[] v = unhex(gp.optString(key, ""));
                Integer id = parseInt(key);
                if (id != null && v != null && v.length > 0) {
                    globalParams.put(id, v);
                }
            }
            dropped += dropDiagnostics(globalParams);
        }
        policies.clear();
        JSONObject pol = o.optJSONObject("policies");
        if (pol != null) {
            java.util.Iterator<String> it = pol.keys();
            while (it.hasNext()) {
                String pkg = it.next();
                int m = pol.optInt(pkg, JdspConst.POLICY_FOLLOW);
                if (m == JdspConst.POLICY_ON || m == JdspConst.POLICY_EXCLUDE) {
                    policies.put(pkg, Integer.valueOf(m));
                }
            }
        }
        perAppParams.clear();
        JSONObject per = o.optJSONObject("perAppParams");
        if (per != null) {
            java.util.Iterator<String> it = per.keys();
            while (it.hasNext()) {
                String pkg = it.next();
                JSONObject inner = per.optJSONObject(pkg);
                if (inner == null) {
                    continue;
                }
                Map<Integer, byte[]> map = new LinkedHashMap<Integer, byte[]>();
                java.util.Iterator<String> i2 = inner.keys();
                while (i2.hasNext()) {
                    String key = i2.next();
                    Integer id = parseInt(key);
                    byte[] v = unhex(inner.optString(key, ""));
                    if (id != null && v != null && v.length > 0) {
                        map.put(id, v);
                    }
                }
                dropped += dropDiagnostics(map);
                if (!map.isEmpty()) {
                    perAppParams.put(pkg, map);
                }
            }
        }
        importedFrom = o.optString("importedFrom", "");
        updatedAt = o.optLong("updatedAt", 0L);
        return dropped;
    }

    /** Full configuration as a pretty printed JSON string (never null). */
    public synchronized String dumpConfig() {
        try {
            return toJsonLocked().toString(2);
        } catch (Throwable t) {
            return "{\"error\":\"config serialisation failed\"}";
        }
    }

    // ---- presets -------------------------------------------------------------

    /** Replaces the whole configuration from a JSON file. 0 = ok. */
    public synchronized int loadPreset(String path) {
        if (path == null || path.length() == 0) {
            return -1003;
        }
        byte[] raw = FileUtil.readAll(new File(path), MAX_FILE_BYTES);
        if (raw == null) {
            Log.w("loadPreset: cannot read " + path);
            return -1003;
        }
        try {
            parseLocked(new JSONObject(new String(raw, "UTF-8")));
            dirty = true;
            saveLocked();
            Log.i("preset loaded from " + path);
            return 0;
        } catch (Throwable t) {
            Log.e("loadPreset failed: " + path, t);
            return -1004;
        }
    }

    /** Writes the current configuration to a JSON file. 0 = ok. */
    public synchronized int savePreset(String path) {
        if (path == null || path.length() == 0) {
            return -1003;
        }
        try {
            byte[] data = toJsonLocked().toString(2).getBytes("UTF-8");
            boolean ok = FileUtil.writeAtomic(new File(path), data, 0640);
            Log.i("preset saved to " + path + " ok=" + ok);
            return ok ? 0 : -1004;
        } catch (Throwable t) {
            Log.e("savePreset failed: " + path, t);
            return -1004;
        }
    }

    /**
     * Plain text mirror of the mode ({@code /data/adb/jdspd/mode}) for shell scripts. The config
     * file stays the single source of truth; this is only a convenience for module hooks.
     */
    private void writeModeMirrorLocked() {
        try {
            File dir = file == null ? null : file.getParentFile();
            if (dir == null || !dir.isDirectory()) {
                return;
            }
            String text = (mode == JdspConst.MODE_BACKEND ? "backend" : "auto") + "\n";
            FileUtil.writeAtomic(new File(dir, "mode"), text.getBytes("UTF-8"), 0640);
        } catch (Throwable ignored) {
            // best effort mirror
        }
    }

    // ---- hex helpers ---------------------------------------------------------

    /** Lower case hex without prefix. */
    public static String hex(byte[] data) {
        if (data == null) {
            return "";
        }
        char[] digits = HEX;
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int b = data[i] & 0xFF;
            out[i * 2] = digits[b >>> 4];
            out[i * 2 + 1] = digits[b & 0x0F];
        }
        return new String(out);
    }

    /** Parses hex (case insensitive). Returns null on malformed input. */
    public static byte[] unhex(String s) {
        if (s == null) {
            return null;
        }
        int n = s.length();
        if ((n & 1) != 0) {
            return null;
        }
        byte[] out = new byte[n / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = hexDigit(s.charAt(i * 2));
            int lo = hexDigit(s.charAt(i * 2 + 1));
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.valueOf(Integer.parseInt(s.trim()));
        } catch (Throwable t) {
            return null;
        }
    }
}
