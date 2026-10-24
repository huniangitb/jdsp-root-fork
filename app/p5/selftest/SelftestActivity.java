package com.jdsp.selftest;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

import me.timschneeberger.rootlessjamesdsp.p5.BinderTransport;
import me.timschneeberger.rootlessjamesdsp.p5.SocketTransport;
import me.timschneeberger.rootlessjamesdsp.p5.TcpTransport;

/**
 * 传输连通性 + 性能自测（包名 com.jdsp.selftest，与被测 App 完全隔离）。
 *
 * 1) C  unix socket（filesystem 命名空间）
 * 2) C' loopback TCP + token（现阶段首选）
 * 3) A  binder：ServiceManager.getService + transact
 * 4) B  root shell：su 是否可用
 * 5) 性能：连续 200 次 setParam（模拟滑条拖动）
 */
public class SelftestActivity extends Activity {

    private static final String TAG = "JdspSelftest";
    private final StringBuilder report = new StringBuilder();
    private TextView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        view = new TextView(this);
        view.setTextSize(11f);
        ScrollView sc = new ScrollView(this);
        sc.addView(view);
        setContentView(sc);
        new Thread(new Runnable() { @Override public void run() { runAll(); } }, "selftest").start();
    }

    private void log(String line) {
        Log.i(TAG, line);
        synchronized (report) { report.append(line).append('\n'); }
        runOnUiThread(new Runnable() { @Override public void run() {
            synchronized (report) { view.setText(report.toString()); }
        }});
    }

    private void runAll() {
        try {
            log("== env");
            log("uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid());
            log("filesDir=" + getFilesDir().getAbsolutePath());
            log("selinux=" + readFile("/proc/self/attr/current").trim());

            testUnixSocket();
            String token = readFile(new File(getFilesDir(), "jdsp.token").getAbsolutePath()).trim();
            int port = parseInt(readFile(new File(getFilesDir(), "jdsp.port").getAbsolutePath()).trim(), 37501);
            testTcp(port, token);
            testBinder();
            testShell();
            benchmarkTcp(port, token);
        } catch (Throwable t) {
            log("FATAL " + t);
        } finally {
            String dump;
            synchronized (report) { dump = report.toString(); }
            try {
                FileWriter w = new FileWriter(new File(getFilesDir(), "selftest.txt"));
                w.write(dump);
                w.close();
            } catch (Throwable ignored) { }
            log("== done");
        }
    }

    private void testUnixSocket() {
        log("== unix socket (C)");
        File sock = new File(getFilesDir(), "jdsp.sock");
        log("sockPath=" + sock.getAbsolutePath() + " exists=" + sock.exists());
        SocketTransport t = new SocketTransport(sock.getAbsolutePath());
        log("unix.connect=" + t.connect());
        t.close();
        try {
            android.net.LocalSocket ls = new android.net.LocalSocket();
            ls.connect(new android.net.LocalSocketAddress(sock.getAbsolutePath(),
                    android.net.LocalSocketAddress.Namespace.FILESYSTEM));
            log("raw LocalSocket connect=ok");
            ls.close();
        } catch (Throwable e) {
            log("raw LocalSocket failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void testTcp(int port, String token) {
        log("== tcp loopback (C')");
        log("port=" + port + " tokenLen=" + token.length());
        TcpTransport t = new TcpTransport("127.0.0.1", port, token);
        boolean ok = t.connect();
        log("tcp.connect=" + ok);
        if (ok) {
            log("tcp.ping=" + t.ping());
            log("tcp.setParam rc=" + t.setParam(4242, 1500, new byte[] { 0x00, 0x00, 0x00, 0x40 }));
            log("tcp.attach rc=" + t.attach(4242));
            t.close();
        } else {
            try {
                java.net.Socket s = new java.net.Socket();
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
                log("raw tcp connect=ok");
                s.close();
            } catch (Throwable e) {
                log("raw tcp failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    private void testBinder() {
        log("== binder (A)");
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object b = sm.getMethod("getService", String.class).invoke(null, "jdsp.probe");
            log("getService(jdsp.probe)=" + (b == null ? "null" : "found"));
            if (b instanceof android.os.IBinder) {
                android.os.Parcel data = android.os.Parcel.obtain();
                android.os.Parcel reply = android.os.Parcel.obtain();
                try {
                    data.writeInterfaceToken("com.jdsp.probe.IPing");
                    boolean handled = ((android.os.IBinder) b).transact(1, data, reply, 0);
                    log("binder.transact handled=" + handled);
                    if (handled) { reply.readException(); log("binder.ping=" + reply.readInt()); }
                } finally { reply.recycle(); data.recycle(); }
            }
        } catch (Throwable t) {
            log("binder failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            Throwable c = t.getCause();
            while (c != null) { log("  cause: " + c.getClass().getSimpleName() + ": " + c.getMessage()); c = c.getCause(); }
        }
        BinderTransport bt = new BinderTransport("jdsp.service");
        log("BinderTransport(jdsp.service).connect=" + bt.connect() + " err=" + bt.lastError());
    }

    private void testShell() {
        log("== shell (B)");
        java.lang.Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[] { "su", "-c", "id" });
            BufferedReader r = new BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            int code = p.waitFor();
            log("su -c id exit=" + code + " out=" + line);
        } catch (Throwable t) {
            log("su failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** 200 次 setParam，模拟滑条拖动：目标 < 20ms/次、零丢包。 */
    private void benchmarkTcp(int port, String token) {
        log("== benchmark: 200 x setParam (tcp)");
        TcpTransport t = new TcpTransport("127.0.0.1", port, token);
        if (!t.connect()) { log("bench.skip (no transport)"); return; }
        int n = 200;
        long[] nanos = new long[n];
        int fail = 0;
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            float gain = -12f + (i % 24) * 1.0f;
            byte[] value = me.timschneeberger.rootlessjamesdsp.p5.JdspCodec.floatBytes(new float[] { gain });
            long t0 = System.nanoTime();
            int rc = t.setParam(4242, 1303, value);
            nanos[i] = System.nanoTime() - t0;
            if (rc < 0) fail++;
        }
        long total = System.nanoTime() - start;
        java.util.Arrays.sort(nanos);
        double totalMs = total / 1e6;
        log("bench.total_ms=" + fmt(totalMs));
        log("bench.avg_ms=" + fmt(totalMs / n));
        log("bench.p50_ms=" + fmt(nanos[n / 2] / 1e6));
        log("bench.p95_ms=" + fmt(nanos[(int) (n * 0.95)] / 1e6));
        log("bench.max_ms=" + fmt(nanos[n - 1] / 1e6));
        log("bench.fail=" + fail + "/" + n);
        log("bench.throughput_per_s=" + fmt(n / (totalMs / 1000.0)));
        t.close();
    }

    private static String fmt(double v) { return String.format(java.util.Locale.US, "%.2f", v); }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }

    private static String readFile(String path) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(path));
            String s = r.readLine();
            r.close();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }
}
