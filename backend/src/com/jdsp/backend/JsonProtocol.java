package com.jdsp.backend;

import org.json.JSONArray;
import org.json.JSONObject;

import com.jdsp.common.EnableErrors;

/**
 * Transport agnostic JSON-line protocol shared by the loopback TCP control channel.
 *
 * <p>Two request dialects are accepted; the response always follows the request's dialect:
 * <ul>
 *   <li>{@code cmd} (jdspcli): {@code {"cmd":"setParam","session":161,"param":1500,"value":"cdcc.."}}
 *       -&gt; {@code {"ok":true,"status":0}}, failures
 *       {@code {"ok":false,"error":"..","code":-1001}}</li>
 *   <li>{@code op} (P5 App): {@code {"id":3,"op":"setParam","s":161,"p":1500,"v":"cdcc.."}}
 *       -&gt; {@code {"id":3,"ok":true,"r":0}}, failures
 *       {@code {"id":N,"ok":false,"e":".."}}</li>
 * </ul>
 * Field aliases: session|s, param|p, value|v, vsize|n, mode|m. Unknown commands answer with an
 * error but never close the connection. Every method is safe against malformed input.
 */
public final class JsonProtocol {
    private final CoreApi api;

    public JsonProtocol(CoreApi api) {
        this.api = api;
    }

    /** One dispatch result, moulded into the request's dialect when serialising. */
    static final class Reply {
        boolean ok = true;
        boolean hasStatus;
        int status;
        byte[] value;
        String error = "";
        int code;
        JSONObject data;

        static Reply ok() {
            return new Reply();
        }

        static Reply status(int status) {
            Reply r = new Reply();
            r.hasStatus = true;
            r.status = status;
            r.ok = status == 0;
            if (!r.ok) {
                r.error = "status " + status;
                r.code = status;
            }
            return r;
        }

        static Reply value(byte[] value) {
            Reply r = new Reply();
            r.value = value;
            return r;
        }

        static Reply error(String message, int code) {
            Reply r = new Reply();
            r.ok = false;
            r.error = message == null ? "error" : message;
            r.code = code;
            return r;
        }

        Reply with(String key, Object value) {
            try {
                if (data == null) {
                    data = new JSONObject();
                }
                data.put(key, value);
            } catch (Throwable ignored) {
                // only simple value types are used
            }
            return this;
        }
    }

    /** Parses one request line and produces the response line (never throws). */
    public String handleLine(String line) {
        JSONObject req;
        try {
            req = new JSONObject(line);
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"bad json\",\"code\":-1003}";
        }
        boolean opDialect = req.has("op");
        boolean hasId = req.has("id");
        long id = req.optLong("id", 0L);
        String cmd = firstString(req, new String[] {"op", "cmd", "command"});
        Reply reply;
        if (cmd == null || cmd.length() == 0) {
            reply = Reply.error("missing 'cmd'/'op'", EffectManager.ERR_BAD_ARG);
        } else {
            String lower = cmd.trim().toLowerCase(java.util.Locale.ROOT);
            try {
                reply = dispatch(lower, req);
            } catch (Throwable t) {
                Log.e("dispatch " + lower + " failed", t);
                reply = Reply.error("internal: " + EffectManager.describe(t),
                        EffectManager.ERR_INTERNAL);
            }
        }
        JSONObject o = new JSONObject();
        try {
            if (hasId) {
                o.put("id", id);
            }
            o.put("ok", reply.ok);
            if (reply.ok) {
                if (reply.hasStatus) {
                    o.put(opDialect ? "r" : "status", reply.status);
                }
                if (reply.value != null) {
                    o.put(opDialect ? "v" : "value", ConfigStore.hex(reply.value));
                    if (!opDialect) {
                        o.put("vsize", reply.value.length);
                    }
                }
            } else {
                if (opDialect) {
                    o.put("e", reply.error);
                } else {
                    o.put("error", reply.error);
                }
                // The code is part of the contract in both dialects: the client has to be able
                // to tell "the backend is switched off" (-1006) from a real failure.
                o.put("code", reply.code);
                if (reply.hasStatus) {
                    o.put("r", reply.status);
                }
            }
            if (reply.data != null) {
                java.util.Iterator<String> keys = reply.data.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    o.put(key, reply.data.get(key));
                }
            }
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"serialisation failed\",\"code\":-1004}";
        }
        return o.toString();
    }

    private Reply dispatch(String cmd, JSONObject req) {
        if ("ping".equals(cmd)) {
            // ok=true and r=1: "the daemon answers" (the P5 dialect expects r as an int).
            Reply reply = Reply.ok();
            reply.hasStatus = true;
            reply.status = CoreApi.PING_OK;
            return reply
                    .with("version", CoreApi.VERSION)
                    .with("r", CoreApi.PING_OK)
                    .with("pid", android.os.Process.myPid())
                    .with("uptimeMs", api.uptimeMs());
        }
        if ("version".equals(cmd) || "getversion".equals(cmd)) {
            return Reply.ok().with("version", CoreApi.VERSION);
        }
        if ("sessions".equals(cmd) || "listsessions".equals(cmd)) {
            JSONArray arr = new JSONArray();
            java.util.List<CoreApi.SessionView> views = api.listSessions();
            for (int i = 0; i < views.size(); i++) {
                arr.put(views.get(i).toJson());
            }
            return Reply.ok().with("sessions", arr);
        }
        if ("getsession".equals(cmd)) {
            int session = intField(req, new String[] {"session", "s"}, -1);
            CoreApi.SessionView v = session <= 0 ? null : api.getSession(session);
            if (v == null) {
                return Reply.error("unknown session", EffectManager.ERR_NO_SESSION);
            }
            return Reply.ok().with("session", v.toJson());
        }
        if ("attach".equals(cmd)) {
            int session = intField(req, new String[] {"session", "s"}, -1);
            if (session <= 0) {
                return Reply.error("missing 'session'", EffectManager.ERR_BAD_ARG);
            }
            int rc = api.attach(session);
            if (rc == CoreApi.ERR_DISABLED) {
                return Reply.error("backend is globally disabled", rc);
            }
            return rc == 0 ? Reply.status(0) : Reply.error("attach failed", rc);
        }
        if ("detach".equals(cmd)) {
            int session = intField(req, new String[] {"session", "s"}, -1);
            if (session <= 0) {
                return Reply.error("missing 'session'", EffectManager.ERR_BAD_ARG);
            }
            int rc = api.detach(session);
            return rc == 0 ? Reply.status(0) : Reply.error("detach failed", rc);
        }
        if ("setparam".equals(cmd)) {
            int session = intField(req, new String[] {"session", "s"}, -1);
            int param = intField(req, new String[] {"param", "p"}, -1);
            String hex = firstString(req, new String[] {"value", "v", "hex"});
            if (session <= 0 || param < 0 || hex == null) {
                return Reply.error("missing 'session'/'param'/'value'", EffectManager.ERR_BAD_ARG);
            }
            byte[] value = ConfigStore.unhex(hex);
            if (value == null || value.length == 0) {
                return Reply.error("value is not a valid hex string", EffectManager.ERR_BAD_ARG);
            }
            int rc = api.setParam(session, param, value);
            if (rc == CoreApi.ERR_DISABLED) {
                return Reply.error("backend is globally disabled", rc);
            }
            return rc == 0 ? Reply.status(0) : Reply.error("setParam status " + rc, rc);
        }
        if ("getparam".equals(cmd)) {
            int session = intField(req, new String[] {"session", "s"}, -1);
            int param = intField(req, new String[] {"param", "p"}, -1);
            int vsize = intField(req, new String[] {"vsize", "n"}, 4);
            if (session <= 0 || param < 0) {
                return Reply.error("missing 'session'/'param'", EffectManager.ERR_BAD_ARG);
            }
            byte[] value = api.getParam(session, param, vsize);
            if (value == null || value.length == 0) {
                return Reply.error("getParam returned no value", EffectManager.ERR_NO_HANDLE);
            }
            return Reply.value(value);
        }
        if ("commit".equals(cmd)) {
            int session = intField(req, new String[] {"session", "s"}, 0);
            int rc = api.commit(session);
            return rc == 0 ? Reply.status(0) : Reply.error("commit failed", rc);
        }
        if ("mode".equals(cmd) || "getmode".equals(cmd) || "setmode".equals(cmd)) {
            Object raw = firstValue(req, new String[] {"mode", "m", "value"});
            boolean changed = false;
            if (raw != null) {
                int m = parseMode(raw);
                if (m < 0) {
                    return Reply.error("bad mode (0=auto 1=backend)", EffectManager.ERR_BAD_ARG);
                }
                int rc = api.setMode(m);
                if (rc < 0) {
                    return Reply.error("setMode failed", rc);
                }
                changed = true;
            }
            int mode = api.getMode();
            Reply reply = Reply.ok().with("mode", mode)
                    .with("modeName", mode == 1 ? "backend" : "auto");
            if (changed) {
                // audio_effects.xml is mounted read only from an image snapshot at boot, so the
                // XML side of a mode change can only be applied by the module on the next boot.
                reply.with("reboot_required", true).with("rebootRequired", true);
            }
            return reply;
        }
        if ("policy".equals(cmd) || "getpolicy".equals(cmd) || "setpolicy".equals(cmd)) {
            String pkg = firstString(req, new String[] {"pkg", "package", "app"});
            if (pkg == null || pkg.length() == 0) {
                return Reply.error("missing 'pkg'", EffectManager.ERR_BAD_ARG);
            }
            Object raw = firstValue(req, new String[] {"mode", "m", "policy"});
            if (raw != null) {
                int m = parsePolicy(raw);
                if (m < 0) {
                    return Reply.error("bad policy (0=follow 1=on 2=exclude)",
                            EffectManager.ERR_BAD_ARG);
                }
                int rc = api.setAppPolicy(pkg, m);
                if (rc < 0) {
                    return Reply.error("setAppPolicy failed", rc);
                }
            }
            return Reply.ok().with("pkg", pkg).with("mode", api.getAppPolicy(pkg));
        }
        if ("setenabled".equals(cmd)) {
            Object raw = firstValue(req, new String[] {"enabled", "value", "on"});
            if (raw == null) {
                return Reply.error("missing 'enabled'", EffectManager.ERR_BAD_ARG);
            }
            Boolean on = parseBoolean(raw);
            if (on == null) {
                return Reply.error("'enabled' must be a boolean", EffectManager.ERR_BAD_ARG);
            }
            int rc = api.setEnabled(on.booleanValue());
            if (rc != 0) {
                // The switch command must never report "disabled" (-1006): the client would
                // read it as "the user switched the backend off" and wait for a re-enable.
                return Reply.error("setEnabled failed", EnableErrors.forEnableCommand(rc));
            }
            Reply reply = Reply.status(0);
            reply.with("enabled", api.getEnabled());
            return reply;
        }
        if ("getenabled".equals(cmd)) {
            boolean on = api.getEnabled();
            Reply reply = Reply.status(on ? 1 : 0);
            reply.with("enabled", on);
            return reply;
        }
        if ("policies".equals(cmd) || "listpolicies".equals(cmd)) {
            try {
                JSONObject parsed = new JSONObject(api.listPolicies());
                Reply reply = Reply.ok();
                java.util.Iterator<String> keys = parsed.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    reply.with(key, parsed.get(key));
                }
                return reply;
            } catch (Throwable t) {
                return Reply.error("listPolicies failed", EffectManager.ERR_INTERNAL);
            }
        }
        if ("dumpconfig".equals(cmd) || "config".equals(cmd)) {
            try {
                return Reply.ok().with("config", new JSONObject(api.dumpConfig()));
            } catch (Throwable t) {
                return Reply.error("dumpConfig failed", EffectManager.ERR_INTERNAL);
            }
        }
        if ("dumpsession".equals(cmd)) {
            int session = intField(req, new String[] {"session", "s"}, -1);
            if (session <= 0) {
                return Reply.error("missing 'session'", EffectManager.ERR_BAD_ARG);
            }
            try {
                JSONObject dump = new JSONObject(api.dumpSession(session));
                Reply reply = Reply.ok();
                java.util.Iterator<String> keys = dump.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    reply.with(key, dump.get(key));
                }
                return reply;
            } catch (Throwable t) {
                return Reply.error("dumpSession failed", EffectManager.ERR_INTERNAL);
            }
        }
        if ("stats".equals(cmd)) {
            try {
                return Reply.ok().with("stats", new JSONObject(api.stats()));
            } catch (Throwable t) {
                return Reply.error("stats failed", EffectManager.ERR_INTERNAL);
            }
        }
        if ("loadpreset".equals(cmd) || "savepreset".equals(cmd)) {
            String path = firstString(req, new String[] {"path", "file"});
            if (path == null || path.length() == 0) {
                return Reply.error("missing 'path'", EffectManager.ERR_BAD_ARG);
            }
            int rc = "loadpreset".equals(cmd) ? api.loadPreset(path) : api.savePreset(path);
            return rc == 0 ? Reply.status(0) : Reply.error(cmd + " failed", rc);
        }
        if ("applybaseline".equals(cmd) || "baseline".equals(cmd)) {
            // "all" (jdspcli baseline --all) additionally clears every perAppParams scope.
            boolean all = req.optBoolean("all", false) || req.optBoolean("clearPerApp", false);
            int applied = api.applyBaseline(all);
            return Reply.ok().with("applied", applied).with("clearPerApp", all);
        }
        if ("shutdown".equals(cmd) || "stop".equals(cmd)) {
            // Extra command (not in the AIDL contract): graceful stop used by "Daemon --stop"
            // and by the module's supervisor, because ART does not run shutdown hooks on SIGTERM.
            if (!req.optBoolean("confirm", false)) {
                return Reply.error("shutdown needs \"confirm\":true", EffectManager.ERR_BAD_ARG);
            }
            int rc = api.requestShutdown("remote shutdown");
            return rc == 0 ? Reply.status(0) : Reply.error("shutdown failed", rc);
        }
        return Reply.error("unknown cmd: " + cmd, EffectManager.ERR_BAD_ARG);
    }

    // ---- field helpers -------------------------------------------------------

    private static Object firstValue(JSONObject req, String[] names) {
        for (int i = 0; i < names.length; i++) {
            if (req.has(names[i])) {
                try {
                    Object v = req.get(names[i]);
                    if (v == null || JSONObject.NULL.equals(v)) {
                        return null;
                    }
                    return v;
                } catch (Throwable t) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String firstString(JSONObject req, String[] names) {
        Object v = firstValue(req, names);
        if (v == null) {
            return null;
        }
        return v instanceof String ? (String) v : String.valueOf(v);
    }

    private static int intField(JSONObject req, String[] names, int def) {
        Object v = firstValue(req, names);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (Throwable t) {
                return def;
            }
        }
        return def;
    }

    /** 0=auto 1=backend; accepts int or "auto"/"backend". -1 when invalid. */
    private static int parseMode(Object raw) {
        if (raw instanceof Number) {
            int m = ((Number) raw).intValue();
            return (m == 0 || m == 1) ? m : -1;
        }
        String s = String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT);
        if ("auto".equals(s)) {
            return 0;
        }
        if ("backend".equals(s)) {
            return 1;
        }
        try {
            int m = Integer.parseInt(s);
            return (m == 0 || m == 1) ? m : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Boolean-ish input: true/false, "true"/"false", "on"/"off", 1/0. null when invalid. */
    private static Boolean parseBoolean(Object raw) {
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        if (raw instanceof Number) {
            int n = ((Number) raw).intValue();
            if (n == 1) {
                return Boolean.TRUE;
            }
            if (n == 0) {
                return Boolean.FALSE;
            }
            return null;
        }
        String s = String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(s) || "on".equals(s) || "yes".equals(s) || "enable".equals(s)
                || "enabled".equals(s)) {
            return Boolean.TRUE;
        }
        if ("false".equals(s) || "off".equals(s) || "no".equals(s) || "disable".equals(s)
                || "disabled".equals(s)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 0=follow 1=on 2=exclude; accepts int or names. -1 when invalid. */
    private static int parsePolicy(Object raw) {
        if (raw instanceof Number) {
            int m = ((Number) raw).intValue();
            return (m >= 0 && m <= 2) ? m : -1;
        }
        String s = String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT);
        if ("follow".equals(s) || "auto".equals(s) || "global".equals(s)) {
            return 0;
        }
        if ("on".equals(s) || "enable".equals(s) || "enabled".equals(s)) {
            return 1;
        }
        if ("exclude".equals(s) || "excluded".equals(s) || "off".equals(s)) {
            return 2;
        }
        try {
            int m = Integer.parseInt(s);
            return (m >= 0 && m <= 2) ? m : -1;
        } catch (Throwable t) {
            return -1;
        }
    }
}
