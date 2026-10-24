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
