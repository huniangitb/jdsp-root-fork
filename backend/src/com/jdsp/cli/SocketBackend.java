package com.jdsp.cli;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backend over the jdspd unix-socket JSON-lines protocol.
 *
 * Wire format (agreed with jdspd):
 *   request  = one line of JSON {cmd:..., ...}
 *   response = one line of JSON, always has "ok":boolean
 *   errors   = {"ok":false,"error":"...","code":-1001..-1005}
 *   byte[]   = lower-case hex string
 *
 * Pure Java streams in, JSON out: no Android classes, so this class is
 * covered by the host unit tests with in-memory streams.
 */
public final class SocketBackend implements Backend {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final BufferedReader reader;
    private final Writer writer;
    private final String path;

    public SocketBackend(InputStream in, OutputStream out, String path) {
        this.reader = new BufferedReader(new InputStreamReader(in, UTF8));
        this.writer = new BufferedWriter(new OutputStreamWriter(out, UTF8));
        this.path = path;
    }

    @Override
    public String getVersion() throws CliException {
        Map<String, Object> response = call(request("version"));
        requireOk(response);
        String version = Json.string(response, "version");
        return version == null ? "" : version;
    }

    @Override
    public int ping() throws CliException {
        Map<String, Object> response = call(request("ping"));
        if (isOk(response)) {
            return 0;
        }
        return code(response);
    }

    @Override
    public List<SessionRow> listSessions() throws CliException {
        Map<String, Object> response = call(request("sessions"));
        requireOk(response);
        List<SessionRow> rows = new ArrayList<SessionRow>();
        List<Object> sessions = Json.array(response.get("sessions"));
        if (sessions != null) {
            for (Object item : sessions) {
                Map<String, Object> session = Json.object(item);
                if (session != null) {
                    rows.add(toRow(session));
                }
            }
        }
        return rows;
    }

    @Override
    public SessionRow getSession(int sessionId) throws CliException {
        Map<String, Object> response = call(request("getSession", "session", sessionId));
        if (!isOk(response)) {
            if (code(response) == -1002) {
                return null;
            }
            throw remoteError(response);
        }
        Map<String, Object> session = Json.object(response.get("session"));
        return session == null ? null : toRow(session);
    }

    @Override
    public int attach(int sessionId) throws CliException {
        return status(call(request("attach", "session", sessionId)));
    }

    @Override
    public int detach(int sessionId) throws CliException {
        return status(call(request("detach", "session", sessionId)));
    }

    @Override
    public int setParam(int sessionId, int paramId, byte[] value) throws CliException {
        return status(call(request("setParam", "session", sessionId, "param", paramId,
                "value", ValueCodec.toHex(value))));
    }

    @Override
    public byte[] getParam(int sessionId, int paramId, int vsize) throws CliException {
        Map<String, Object> response = call(request("getParam", "session", sessionId,
                "param", paramId, "vsize", vsize));
        requireOk(response);
        String hex = Json.string(response, "value");
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }
        try {
            return ValueCodec.parseHex(hex, "socket getParam reply");
        } catch (CliException e) {
            throw new CliException(Exit.UNAVAILABLE,
                    "invalid hex value in jdspd socket reply: " + hex, e);
        }
    }

    @Override
    public int commit(int sessionId) throws CliException {
        return status(call(request("commit", "session", sessionId)));
    }

    @Override
    public String dumpConfig() throws CliException {
        Map<String, Object> response = call(request("dumpConfig"));
        requireOk(response);
        return Json.write(response.get("config"));
    }

    @Override
    public String dumpSession(int sessionId) throws CliException {
        Map<String, Object> response = call(request("dumpSession", "session", sessionId));
        requireOk(response);
        Map<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("session", response.get("session"));
        document.put("params", response.get("params"));
        return Json.write(document);
    }

    @Override
    public int loadPreset(String path) throws CliException {
        return status(call(request("loadPreset", "path", path)));
    }

    @Override
    public int savePreset(String path) throws CliException {
        return status(call(request("savePreset", "path", path)));
    }

    @Override
    public int setAppPolicy(String pkg, int mode) throws CliException {
        return status(call(request("policy", "pkg", pkg, "mode", mode)));
    }

    @Override
    public int getAppPolicy(String pkg) throws CliException {
        Map<String, Object> response = call(request("policy", "pkg", pkg));
        requireOk(response);
        return Json.intValue(response, "mode", 0);
    }

    @Override
    public String listPolicies() throws CliException {
        Map<String, Object> response = call(request("policies"));
        requireOk(response);
        Map<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("policies", response.get("policies"));
        document.put("count", response.get("count"));
        return Json.write(document);
    }

    @Override
    public int setMode(int mode) throws CliException {
        return status(call(request("mode", "mode", mode)));
    }

    @Override
    public int getMode() throws CliException {
        Map<String, Object> response = call(request("mode"));
        requireOk(response);
        return Json.intValue(response, "mode", 0);
    }

    @Override
    public String stats() throws CliException {
        Map<String, Object> response = call(request("stats"));
        requireOk(response);
        return Json.write(response.get("stats"));
    }

    // ------------------------------------------------------------------
    // protocol helpers
    // ------------------------------------------------------------------

    private static Map<String, Object> request(String cmd, Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("cmd", cmd);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private Map<String, Object> call(Map<String, Object> request) throws CliException {
        String line = Json.write(request);
        try {
            writer.write(line);
            writer.write("\n");
            writer.flush();
            String reply = reader.readLine();
            if (reply == null) {
                throw new CliException(Exit.UNAVAILABLE,
                        "jdspd socket " + path + " closed the connection");
            }
            Map<String, Object> response = Json.object(Json.parse(reply));
            if (response == null) {
                throw new CliException(Exit.UNAVAILABLE,
                        "invalid jdspd socket reply: " + reply);
            }
            return response;
        } catch (IOException e) {
            throw new CliException(Exit.UNAVAILABLE,
                    "jdspd socket " + path + ": " + describe(e), e);
        }
    }

    private static boolean isOk(Map<String, Object> response) {
        return Boolean.TRUE.equals(response.get("ok"));
    }

    private static void requireOk(Map<String, Object> response) throws CliException {
        if (!isOk(response)) {
            throw remoteError(response);
        }
    }

    private static CliException remoteError(Map<String, Object> response) {
        String error = Json.string(response, "error");
        int code = code(response);
        return new CliException(Exit.REMOTE, "jdspd error: "
                + (error == null ? "unknown" : error) + " (code " + code + ")");
    }

    private static int code(Map<String, Object> response) {
        return Json.intValue(response, "code", -1004);
    }

    private static int status(Map<String, Object> response) {
        if (isOk(response)) {
            return Json.intValue(response, "status", 0);
        }
        Object status = response.get("status");
        if (status instanceof Number) {
            return ((Number) status).intValue();
        }
        return code(response);
    }

    private static SessionRow toRow(Map<String, Object> session) {
        return new SessionRow(
                Json.intValue(session, "sessionId", 0),
                Json.intValue(session, "uid", 0),
                Json.string(session, "pkg"),
                Json.booleanValue(session, "attached", false),
                Json.booleanValue(session, "enabled", false),
                Json.intValue(session, "paramCount", 0),
                Json.intValue(session, "effectId", -1),
                Json.intValue(session, "policyMode", 0),
                Json.intValue(session, "pid", 0));
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
