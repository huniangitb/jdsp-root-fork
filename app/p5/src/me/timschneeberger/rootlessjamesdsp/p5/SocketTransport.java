package me.timschneeberger.rootlessjamesdsp.p5;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * 传输 C（首选）：Unix domain socket，JSON lines 协议。
 *
 * socket 文件由 jdspd（root）创建在 App 的私有目录里，例如
 * /data/data/james.dsp/files/jdsp.sock。jdspd 创建后 chown 到 App uid 并 chmod 0660，
 * 因此 App 既能遍历也能连；其它 App 进不来（目录 0700）。
 *
 * 协议（每行一个 JSON 对象，UTF-8，\n 结尾）：
 *   请求 {"op":"ping"}
 *   应答 {"ok":true,"r":1}
 *   请求 {"op":"setParam","s":<session>,"p":<param>,"v":"<hex>"}
 *   应答 {"ok":true,"r":<engine status>}  或  {"ok":false,"e":"..."}
 *   请求 {"op":"getParam","s":..,"p":..,"n":<vsize>}
 *   应答 {"ok":true,"v":"<hex>"}
 *   请求 {"op":"commit","s":<session>}
 *   应答 {"ok":true,"r":0}
 */
public final class SocketTransport implements JdspTransport {

    public static final int MAX_LINE = 1 << 20;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final String path;
    /** 最近一次后端返回的错误描述（用于诊断）。 */
    private String lastError = "";
    private LocalSocket socket;
    private InputStream in;
    private OutputStream out;
    private int seq = 0;

    public SocketTransport(String path) {
        this.path = path;
    }

    @Override
    public String name() {
        return "socket(" + path + ")";
    }

    @Override
    public boolean isConnected() {
        return socket != null;
    }

    @Override
    public boolean connect() {
        close();
        try {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM));
            s.setSoTimeout(1500);
            in = s.getInputStream();
            out = s.getOutputStream();
            socket = s;
            return ping() >= 0;
        } catch (Throwable t) {
            close();
            return false;
        }
    }

    @Override
    public synchronized void close() {
        socket = null;
        in = null;
        out = null;
    }

    /** 同步一次请求/应答；失败返回 null。 */
    private String roundTrip(JSONObject request) {
        if (socket == null) return null;
        try {
            synchronized (this) {
                request.put("id", ++seq);
                out.write(request.toString().getBytes(UTF8));
                out.write('\n');
                out.flush();
                return readLine();
            }
        } catch (Throwable t) {
            close();
            return null;
        }
    }

    private String readLine() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        while (true) {
            int b = in.read();
            if (b < 0) throw new IllegalStateException("eof");
            if (b == '\n') break;
            if (buf.size() >= MAX_LINE) throw new IllegalStateException("line too long");
            buf.write(b);
        }
        return new String(buf.toByteArray(), UTF8);
    }

    @Override
    public int ping() {
        try {
            JSONObject req = new JSONObject();
            req.put("op", "ping");
            String reply = roundTrip(req);
            if (reply == null) return -1;
            JSONObject o = new JSONObject(reply);
            return o.optBoolean("ok", false) ? o.optInt("r", 0) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override
    public int setParam(int sessionId, int paramId, byte[] value) {
        try {
            JSONObject req = new JSONObject();
            req.put("op", "setParam");
            req.put("s", sessionId);
            req.put("p", paramId);
            req.put("v", JdspCodec.hex(value));
            String reply = roundTrip(req);
            if (reply == null) return -1;
            JSONObject o = new JSONObject(reply);
            return o.optBoolean("ok", false) ? o.optInt("r", 0) : -2;
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override
    public byte[] getParam(int sessionId, int paramId, int vsize) {
        try {
            JSONObject req = new JSONObject();
            req.put("op", "getParam");
            req.put("s", sessionId);
            req.put("p", paramId);
            req.put("n", vsize);
            String reply = roundTrip(req);
            if (reply == null) return null;
            JSONObject o = new JSONObject(reply);
            if (!o.optBoolean("ok", false)) return null;
            return JdspCodec.unhex(o.optString("v", ""));
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public int attach(int sessionId) {
        try {
            JSONObject req = new JSONObject();
            req.put("op", "attach");
            req.put("s", sessionId);
            String reply = roundTrip(req);
            if (reply == null) return -1;
            JSONObject o = new JSONObject(reply);
            return o.optBoolean("ok", false) ? o.optInt("r", 0) : -2;
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override
    public int[] listAttachedSessions() {
        try {
            JSONObject req = new JSONObject();
            req.put("op", "sessions");
            String reply = roundTrip(req);
            if (reply == null) return null;
            JSONObject o = new JSONObject(reply);
            if (!o.optBoolean("ok", false)) return null;
            org.json.JSONArray arr = o.optJSONArray("sessions");
            if (arr == null) return new int[0];
            java.util.List<Integer> ids = new java.util.ArrayList<Integer>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                if (!s.optBoolean("attached", false)) continue;
                int id = s.optInt("sessionId", 0);
                if (id > 0) ids.add(Integer.valueOf(id));
            }
            int[] out = new int[ids.size()];
            for (int i = 0; i < out.length; i++) out[i] = ids.get(i).intValue();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public int detach(int sessionId) {
        try {
            JSONObject req = new JSONObject();
            req.put("op", "detach");
            req.put("s", sessionId);
            String reply = roundTrip(req);
            if (reply == null) return -1;
            JSONObject o = new JSONObject(reply);
            return o.optBoolean("ok", false) ? o.optInt("r", 0) : -2;
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override
    public int setEnabled(boolean enabled) {
        try {
            JSONObject req = new JSONObject();
            req.put("op", "setEnabled");
            req.put("enabled", enabled);
            String reply = roundTrip(req);
            if (reply == null) return -1;
            JSONObject o = new JSONObject(reply);
            if (o.optBoolean("ok", false)) return o.optInt("r", 0);
            // 失败：把后端的 code 带出来（-1006 = 后端被全局关掉，属用户意图）
            int code = o.optInt("code", o.optInt("r", -1004));
            lastError = "setEnabled: " + o.optString("e", "") + " (code=" + code + ")";
            return code;
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override
    public Boolean getEnabled() {
        try {
            JSONObject req = new JSONObject();
            req.put("op", "getEnabled");
            String reply = roundTrip(req);
            if (reply == null) return null;
            JSONObject o = new JSONObject(reply);
            if (!o.optBoolean("ok", false)) return null;
            // enabled 字段是权威值；没有就退回 r（1=开 0=关）
            if (o.has("enabled")) {
                return Boolean.valueOf(o.optBoolean("enabled", false));
            }
            return Boolean.valueOf(o.optInt("r", 0) != 0);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public String dumpConfig() {
        try {
            JSONObject req = new JSONObject();
            req.put("op", "dumpConfig");
            String reply = roundTrip(req);
            if (reply == null) return null;
            JSONObject o = new JSONObject(reply);
            if (!o.optBoolean("ok", false)) return null;
            JSONObject cfg = o.optJSONObject("config");
            if (cfg != null) return cfg.toString();
            String raw = o.optString("config", "");
            return raw.isEmpty() ? null : raw;
        } catch (Throwable t) {
            lastError = "dumpConfig: " + t.getClass().getSimpleName();
            return null;
        }
    }

    @Override
    public int commit(int sessionId) {
        try {
            JSONObject req = new JSONObject();
            req.put("op", "commit");
            req.put("s", sessionId);
            String reply = roundTrip(req);
            if (reply == null) return -1;
            JSONObject o = new JSONObject(reply);
            return o.optBoolean("ok", false) ? o.optInt("r", 0) : -2;
        } catch (Throwable t) {
            return -1;
        }
    }
}