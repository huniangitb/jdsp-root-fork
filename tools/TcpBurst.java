import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;

/**
 * Device-side burst client: same line-delimited JSON protocol as the App over the daemon's
 * loopback TCP channel, but without adb forward in the path (so the measured latency is the
 * daemon's, not the adb transport's).
 *
 * Usage: CLASSPATH=tcpburst.dex app_process /system/bin TcpBurst <seconds> <rate> [session]
 */
public class TcpBurst {
    static String readTrim(String p) throws IOException {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8).trim();
    }

    public static void main(String[] args) throws Exception {
        double seconds = args.length > 0 ? Double.parseDouble(args[0]) : 20;
        double rate = args.length > 1 ? Double.parseDouble(args[1]) : 100;
        int session = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        String port = readTrim("/data/data/james.dsp/files/jdsp.port");
        String token = readTrim("/data/data/james.dsp/files/jdsp.token");
        int total = (int) Math.round(seconds * rate);
        Socket s = new Socket("127.0.0.1", Integer.parseInt(port));
        s.setTcpNoDelay(true);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        Writer out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);

        String hello = call(out, in, "{\"op\":\"hello\",\"token\":\"" + token + "\"}");
        if (!hello.contains("\"ok\":true")) {
            System.out.println("HELLO_FAIL " + hello);
            System.exit(3);
        }
        int fails = 0;
        long[] lat = new long[total];
        String lastHex = "";
        long t0 = System.nanoTime();
        for (int i = 0; i < total; i++) {
            double gain = 12.0 * Math.sin(2 * Math.PI * i / Math.max(total - 1, 1));
            String hex = float3le(-0.1f, 60.0f, (float) gain);
            long a = System.nanoTime();
            String r = call(out, in, "{\"cmd\":\"setParam\",\"session\":" + session
                    + ",\"param\":1500,\"value\":\"" + hex + "\"}");
            lat[i] = System.nanoTime() - a;
            if (r.contains("\"ok\":true")) lastHex = hex; else fails++;
            double target = t0 / 1e9 + (i + 1) / rate;
            long sleepNs = (long) ((target - System.nanoTime() / 1e9) * 1e9);
            if (sleepNs > 0) Thread.sleep(sleepNs / 1000000L, (int) (sleepNs % 1000000L));
        }
        double elapsed = (System.nanoTime() - t0) / 1e9;
        String cfg = call(out, in, "{\"cmd\":\"dumpConfig\"}");
        String got = "";
        int idx = cfg.indexOf("\"1500\":\"");
        if (idx >= 0) got = cfg.substring(idx + 8, Math.min(cfg.length(), idx + 8 + 24));
        long[] sorted = lat.clone();
        Arrays.sort(sorted);
        long p50 = sorted[sorted.length / 2] / 1000, p95 = sorted[(int) (sorted.length * 0.95)] / 1000,
             p99 = sorted[(int) (sorted.length * 0.99)] / 1000, mx = sorted[sorted.length - 1] / 1000;
        System.out.println("{\"sent\":" + total + ",\"fails\":" + fails
                + ",\"elapsed_s\":" + String.format("%.2f", elapsed)
                + ",\"achieved_rate\":" + String.format("%.1f", total / elapsed)
                + ",\"latency_ms_p50_rounded\":" + p50 + ",\"p95\":" + p95 + ",\"p99\":" + p99
                + ",\"max\":" + mx + ",\"final_match\":" + got.equals(lastHex) + "}");
        try { s.close(); } catch (IOException ignored) {}
        System.exit(fails == 0 && got.equals(lastHex) ? 0 : 1);
    }

    static String call(Writer out, BufferedReader in, String line) throws IOException {
        out.write(line);
        out.write("\n");
        out.flush();
        return in.readLine();
    }

    static String float3le(float a, float b, float c) {
        byte[] buf = new byte[12];
        int o = 0;
        for (float f : new float[]{a, b, c}) {
            int bits = Float.floatToIntBits(f);
            buf[o++] = (byte) (bits & 0xff);
            buf[o++] = (byte) ((bits >> 8) & 0xff);
            buf[o++] = (byte) ((bits >> 16) & 0xff);
            buf[o++] = (byte) ((bits >> 24) & 0xff);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : buf) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
