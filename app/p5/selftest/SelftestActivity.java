package com.jdsp.selftest;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;

import me.timschneeberger.rootlessjamesdsp.p5.BinderTransport;
import me.timschneeberger.rootlessjamesdsp.p5.SocketTransport;

/**
 * 传输连通性自测（包名 com.jdsp.selftest，与被测 App 完全隔离）。
 *
 * 测三件事：
 *  1) socket：能否连上 root 进程在 App 私有目录里创建的 jdsp.sock（C 方案）
 *  2) binder：能否 find/transact root 进程注册的 jdsp.probe 服务（A 方案）
 *  3) shell ：su 是否可用（B 方案的前置条件）
 * 结果同时写 logcat(tag=JdspSelftest) 和 filesDir/selftest.txt。
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
        setContentView(new ScrollView(this) {{ addView(view); }});
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
            log("uid=" + Process.myUid() + " pid=" + Process.myPid());
            log("filesDir=" + getFilesDir().getAbsolutePath());
            log("selinux=" + readFile("/proc/self/attr/current").trim());
            log("package=" + getPackageName());

            testSocket();
            testBinder();
            testShell();
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

    private void testSocket() {
        log("== socket (C)");
        File sock = new File(getFilesDir(), "jdsp.sock");
        log("sockPath=" + sock.getAbsolutePath() + " exists=" + sock.exists()
                + " canRead=" + sock.canRead() + " canWrite=" + sock.canWrite());
        SocketTransport t = new SocketTransport(sock.getAbsolutePath());
        boolean ok = t.connect();
        log("socket.connect=" + ok);
        if (ok) {
            log("socket.ping=" + t.ping());
            int rc = t.setParam(4242, 1500, new byte[] { 0x00, 0x00, 0x00, 0x40 });
            log("socket.setParam rc=" + rc);
            t.close();
        }
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

    private void testBinder() {
        log("== binder (A)");
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object b = sm.getMethod("getService", String.class).invoke(null, "jdsp.probe");
            log("ServiceManager.getService(jdsp.probe)=" + (b == null ? "null" : b.getClass().getName()));
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
            log("binder failed: " + t.getClass().getName() + ": " + t.getMessage());
            Throwable c = t.getCause();
            while (c != null) { log("  cause: " + c.getClass().getName() + ": " + c.getMessage()); c = c.getCause(); }
        }
        BinderTransport bt = new BinderTransport("jdsp.service");
        log("BinderTransport(jdsp.service).connect=" + bt.connect() + " err=" + bt.lastError());
    }

    private void testShell() {
        log("== shell (B)");
        java.lang.Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[] { "su", "-c", "id" });
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            int code = p.waitFor();
            log("su -c id exit=" + code + " out=" + line);
        } catch (Throwable t) {
            log("su failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static String readFile(String path) {
        try {
            BufferedReader r = new BufferedReader(new java.io.FileReader(path));
            String s = r.readLine();
            r.close();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "(unreadable)";
        }
    }
}
