package me.timschneeberger.rootlessjamesdsp.p5;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * 传输 C'（首选）：loopback TCP + JSON lines。
 *
 * 为什么不是 unix socket：Android 15 上 java 层创建"文件系统命名空间"的监听 socket
 * 需要 LocalServerSocket(LocalSocketAddress)，而这个构造器在 AOSP 运行时不公开
 * （实测 NoSuchMethodException）；LocalServerSocket(String) 只会建 abstract 命名空间
 * （不落盘、跨进程靠 socket SID 做 SELinux 校验 → 又回到 su 域问题）。
 * loopback TCP 没有 SELinux 对端校验，双方都用标准 java.net / ServerSocket 即可。
 *
 * 安全模型：
 *   - jdspd 只 bind 127.0.0.1（不对外）；
 *   - 端口与一次性 token 由 jdspd 写进 App 私有目录（0700，只有本 App 能读）；
 *   - 握手第一行必须是 {"op":"hello","token":"<token>"}，错误 token 直接断开。
 *   其它 App 即使猜到端口，没有 token 也用不了。
 *
 * 协议与 SocketTransport 完全一致（JSON lines，op/id/r/v/e）。
 */
public final class TcpTransport implements JdspTransport {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    public static final int DEFAULT_PORT = 37501;

    private final String host;
    private final int port;
    private final String token;

    private Socket socket;
    private BufferedReader in;
    private OutputStream out;
    private int seq = 0;

    public TcpTransport(String host, int port, String token) {
        this.host = host == null ? "127.0.0.1" : host;
        this.port = port;
        this.token = token;
    }

    @Override
    public String name() {
        return "tcp(" + host + ":" + port + ")";
    }

    @Override
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    @Override
    public boolean connect() {
        close();
        try {
            Socket s = new Socket();
            s.connect(new java.net.InetSocketAddress(InetAddress.getByName(host), port), 500);
            s.setSoTimeout(1500);
            s.setTcpNoDelay(true);
            socket = s;
            out = s.getOutputStream();
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), UTF8));
            if (token != null) {
                JSONObject hello = new JSONObject();
                hello.put("op", "hello");
                hello.put("token", token);
                String reply = roundTrip(hello);
                if (reply == null) { close(); return false; }
                JSONObject o = new JSONObject(reply);
                if (!o.optBoolean("ok", false)) { close(); return false; }
            }
            return ping() >= 0;
        } catch (Throwable t) {
            close();
            return false;
        }
    }

    @Override
    public synchronized void close() {
        try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
        socket = null;
        in = null;
        out = null;
    }

    private String roundTrip(JSONObject request) {
        if (socket == null) return null;
        try {
            synchronized (this) {
                if (!request.has("id")) request.put("id", ++seq);
                out.write(request.toString().getBytes(UTF8));
                out.write('\n');
                out.flush();
                return in.readLine();
            }
        } catch (Throwable t) {
            close();
            return null;
        }
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
}
