package com.jdsp.cli;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Backend over jdspd's loopback-TCP JSON-lines protocol.
 *
 * Wire format (agreed with jdspd):
 *   hello    : client sends {"op":"hello","token":"<hex>"} as the first line
 *   request  : one JSON line {cmd:..., ...}
 *   response : one JSON line, always has "ok":boolean
 *   errors   : {"ok":false,"error":"...","code":-1001..-1005}
 *   byte[]   : lower-case hex string
 *
 * Plain java.net.Socket + java.nio.file, no Android classes: the whole class
 * (including the token handshake) is covered by host unit tests with a local
 * ServerSocket.
 */
public final class TcpBackend implements Backend {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final int READ_TIMEOUT_MS = 2000;

    private final BufferedReader reader;
    private final Writer writer;
    private final String label;

    /** Protocol-only view over an already connected stream pair (unit tests). */
    TcpBackend(InputStream in, OutputStream out, String label) {
        this.reader = new BufferedReader(new InputStreamReader(in, UTF8));
        this.writer = new BufferedWriter(new OutputStreamWriter(out, UTF8));
        this.label = label;
    }

    /**
     * Connects to jdspd using the default files
     * ({@link TransportFactory#PORT_FILE}, {@link TransportFactory#TOKEN_FILE}).
     */
    public static TcpBackend open(String endpoint) throws CliException {
        return open(endpoint, TransportFactory.PORT_FILE, TransportFactory.TOKEN_FILE);
    }

    /** Testable variant: explicit endpoint file paths. */
    static TcpBackend open(String endpoint, String portFile, String tokenFile) throws CliException {
        String host = TransportFactory.DEFAULT_TCP_HOST;
        int port;
        if (endpoint != null && !endpoint.isEmpty()) {
            String[] parsed = parseEndpoint(endpoint);
            host = parsed[0];
            port = Integer.parseInt(parsed[1]);
        } else {
            Path portPath = Paths.get(portFile);
            Path tokenPath = Paths.get(tokenFile);
            if (!Files.isRegularFile(portPath) || !Files.isRegularFile(tokenPath)) {
                throw new CliException(Exit.UNAVAILABLE, missingFilesMessage(portFile, tokenFile));
            }
            port = readPort(portPath, portFile);
        }
        String token = readToken(Paths.get(tokenFile), tokenFile);

        Socket socket = new Socket();
        TcpBackend backend;
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            backend = new TcpBackend(socket.getInputStream(), socket.getOutputStream(),
                    host + ":" + port);
        } catch (IOException e) {
            closeQuietly(socket);
            throw new CliException(Exit.UNAVAILABLE,
                    "tcp endpoint unreachable (" + host + ":" + port + ": " + describe(e) + ")",
                    e);
        }
        try {
            backend.handshake(token);
        } catch (CliException e) {
            closeQuietly(socket);
            throw e;
        }
        return backend;
    }

    private void handshake(String token) throws CliException {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("op", "hello");
        request.put("token", token);
        Map<String, Object> response = exchange(request);
        if (isOk(response)) {
            return;
        }
        String error = Json.string(response, "error");
        if (error != null && error.toLowerCase(Locale.ROOT).contains("token")) {
            throw new CliException(Exit.UNAVAILABLE, "tcp hello rejected (bad token)");
        }
        throw new CliException(Exit.UNAVAILABLE, "tcp hello rejected ("
                + (error == null ? "unknown error" : error) + ")");
    }

    // ------------------------------------------------------------------
    // Backend
    // ------------------------------------------------------------------

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
            return ValueCodec.parseHex(hex, "tcp getParam reply");
        } catch (CliException e) {
            throw new CliException(Exit.UNAVAILABLE,
                    "invalid hex value in jdspd tcp reply: " + hex, e);
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
    public int setEnabled(boolean enabled) throws CliException {
        return status(call(request("setEnabled", "enabled", Boolean.valueOf(enabled))));
    }

    @Override
    public boolean getEnabled() throws CliException {
        Map<String, Object> response = call(request("getEnabled"));
        requireOk(response);
        Object value = response.get("enabled");
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        return Json.intValue(response, "r", 0) != 0;
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
        return exchange(request);
    }

    private Map<String, Object> exchange(Map<String, Object> request) throws CliException {
        String line = Json.write(request);
        try {
            writer.write(line);
            writer.write("\n");
            writer.flush();
            String reply = reader.readLine();
            if (reply == null) {
                throw new CliException(Exit.UNAVAILABLE,
                        "tcp " + label + " closed the connection");
            }
            Map<String, Object> response = Json.object(Json.parse(reply));
            if (response == null) {
                throw new CliException(Exit.UNAVAILABLE,
                        "invalid jdspd tcp reply: " + reply);
            }
            return response;
        } catch (IOException e) {
            throw new CliException(Exit.UNAVAILABLE,
                    "tcp " + label + ": " + describe(e), e);
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

    // ------------------------------------------------------------------
    // endpoint files and parsing
    // ------------------------------------------------------------------

    private static String missingFilesMessage(String portFile, String tokenFile) {
        if (TransportFactory.PORT_FILE.equals(portFile)
                && TransportFactory.TOKEN_FILE.equals(tokenFile)) {
            return "tcp endpoint files missing (/data/data/james.dsp/files/jdsp.{port,token})";
        }
        return "tcp endpoint files missing (" + portFile + ", " + tokenFile + ")";
    }

    private static int readPort(Path path, String displayPath) throws CliException {
        String text;
        try {
            text = new String(Files.readAllBytes(path), UTF8).trim();
        } catch (IOException e) {
            throw new CliException(Exit.UNAVAILABLE,
                    "tcp endpoint files unreadable (" + displayPath + ": " + describe(e) + ")", e);
        }
        try {
            int port = Integer.parseInt(text);
            if (port <= 0 || port > 65535) {
                throw new NumberFormatException("out of range");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new CliException(Exit.UNAVAILABLE,
                    "bad tcp port in " + displayPath + ": '" + text + "'");
        }
    }

    private static String readToken(Path path, String displayPath) throws CliException {
        if (!Files.isRegularFile(path)) {
            if (TransportFactory.TOKEN_FILE.equals(displayPath)) {
                throw new CliException(Exit.UNAVAILABLE, missingFilesMessage(
                        TransportFactory.PORT_FILE, TransportFactory.TOKEN_FILE));
            }
            throw new CliException(Exit.UNAVAILABLE,
                    "tcp endpoint files missing (" + displayPath + ")");
        }
        String token;
        try {
            token = new String(Files.readAllBytes(path), UTF8).trim();
        } catch (IOException e) {
            throw new CliException(Exit.UNAVAILABLE,
                    "tcp token file unreadable (" + displayPath + ": " + describe(e) + ")", e);
        }
        if (token.isEmpty()) {
            throw new CliException(Exit.UNAVAILABLE,
                    "tcp token file is empty (" + displayPath + ")");
        }
        return token.toLowerCase(Locale.ROOT);
    }

    static String[] parseEndpoint(String endpoint) throws CliException {
        String text = endpoint.trim();
        String host = TransportFactory.DEFAULT_TCP_HOST;
        String portText = text;
        int colon = text.lastIndexOf(':');
        if (colon >= 0) {
            host = text.substring(0, colon);
            portText = text.substring(colon + 1);
            if (host.isEmpty()) {
                host = TransportFactory.DEFAULT_TCP_HOST;
            }
        }
        try {
            int port = Integer.parseInt(portText);
            if (port <= 0 || port > 65535) {
                throw new NumberFormatException("out of range");
            }
            return new String[]{host, Integer.toString(port)};
        } catch (NumberFormatException e) {
            throw new CliException(Exit.USAGE, "invalid --tcp endpoint '" + endpoint
                    + "': expected [host:]port");
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // reporting the original failure is more useful
        }
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
