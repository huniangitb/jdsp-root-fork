package com.jdsp.selftest.probe;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * 不装 App 也能验证「App 侧客户端 ↔ 真 jdspd」的连通性与握手：
 * 完全按 TcpTransport 的协议走（hello 握手 + JSON lines）。
 *
 * 用法（在设备上以任意 uid 运行都可以，只读 token 文件需要 james.dsp 权限）：
 *   CLASSPATH=client.dex app_process /system/bin com.jdsp.selftest.probe.ClientCheck \
 *       <filesDir> [iterations]
 */
public final class ClientCheck {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "/data/data/james.dsp/files";
        int iters = args.length > 1 ? Integer.parseInt(args[1]) : 200;

        int port = Integer.parseInt(read(new File(dir, "jdsp.port")).trim());
        String token = read(new File(dir, "jdsp.token")).trim();
        System.out.println("port=" + port + " tokenLen=" + token.length());

        Socket socket = new Socket();
        socket.connect(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1000);
        socket.setTcpNoDelay(true);
        OutputStream out = socket.getOutputStream();
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF8));

        // ---- hello 握手（与 TcpTransport 一致）----
        JSONObject hello = new JSONObject();
        hello.put("op", "hello");
        hello.put("token", token);
        hello.put("id", 1);
        out.write(hello.toString().getBytes(UTF8));
        out.write('\n');
        out.flush();
        String reply = in.readLine();
        System.out.println("hello reply=" + reply);
        JSONObject h = new JSONObject(reply);
        if (!h.optBoolean("ok", false)) {
            System.out.println("HANDSHAKE_FAILED");
            System.exit(2);
        }

        // ---- ping ----
        int seq = 2;
        JSONObject ping = new JSONObject();
        ping.put("op", "ping");
        ping.put("id", seq++);
        out.write(ping.toString().getBytes(UTF8));
        out.write('\n');
        out.flush();
        System.out.println("ping reply=" + in.readLine());

        // ---- 200 次 setParam 延迟基准 ----
        long[] nanos = new long[iters];
        int fail = 0;
        long start = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            float gain = -12f + (i % 24);
            byte[] value = new byte[4];
            int bits = Float.floatToIntBits(gain);
            value[0] = (byte) (bits & 0xFF);
            value[1] = (byte) ((bits >>> 8) & 0xFF);
            value[2] = (byte) ((bits >>> 16) & 0xFF);
            value[3] = (byte) ((bits >>> 24) & 0xFF);
            JSONObject req = new JSONObject();
            req.put("op", "setParam");
            req.put("s", 113);
            req.put("p", 1303);
            req.put("v", hex(value));
            req.put("id", seq++);
            long t0 = System.nanoTime();
            out.write(req.toString().getBytes(UTF8));
            out.write('\n');
            out.flush();
            String line = in.readLine();
            nanos[i] = System.nanoTime() - t0;
            if (line == null || !line.contains("\"ok\":true")) fail++;
        }
        long total = System.nanoTime() - start;
        java.util.Arrays.sort(nanos);
        System.out.println("bench.total_ms=" + fmt(total / 1e6));
        System.out.println("bench.avg_ms=" + fmt(total / 1e6 / iters));
        System.out.println("bench.p50_ms=" + fmt(nanos[iters / 2] / 1e6));
        System.out.println("bench.p95_ms=" + fmt(nanos[(int) (iters * 0.95)] / 1e6));
        System.out.println("bench.max_ms=" + fmt(nanos[iters - 1] / 1e6));
        System.out.println("bench.fail=" + fail + "/" + iters);
        System.out.println("bench.throughput_per_s=" + fmt(iters / (total / 1e9)));
        socket.close();
        System.out.println("OK");
    }

    private static String hex(byte[] data) {
        char[] HEX = "0123456789abcdef".toCharArray();
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    private static String fmt(double v) { return String.format(java.util.Locale.US, "%.2f", v); }

    private static String read(File f) throws Exception {
        BufferedReader r = new BufferedReader(new FileReader(f));
        String s = r.readLine();
        r.close();
        return s == null ? "" : s;
    }
}
