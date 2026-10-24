package com.jdsp.selftest.probe;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Binder;
import android.os.Parcel;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * P5 传输自测的"根侧"服务端（设备上以 root 通过 app_process 运行）。
 *
 * 用法：
 *   CLASSPATH=probe.dex app_process /system/bin com.jdsp.selftest.probe.ProbeServer \
 *       <appFilesDir> <appUid> <seconds> [tcpPort]
 *
 * 提供三样东西，供自测 App 在 untrusted_app 域里验证：
 *   1) binder 服务 jdsp.probe              （A 方案：ServiceManager find + transact）
 *   2) unix socket <appFilesDir>/jdsp.sock （C 方案：filesystem 命名空间）
 *   3) loopback TCP 127.0.0.1:<port>       （C' 方案：token 握手 + JSON lines）
 */
public final class ProbeServer {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String TOKEN = "p5-selftest-token";

    public static void main(String[] args) throws Exception {
        String filesDir = args.length > 0 ? args[0] : "/data/local/tmp";
        int appUid = args.length > 1 ? Integer.parseInt(args[1]) : -1;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 120;
        int port = args.length > 3 ? Integer.parseInt(args[3]) : 37501;

        // ---- binder 探针 ----
        try {
            Binder probe = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                    try {
                        data.enforceInterface("com.jdsp.probe.IPing");
                        if (code == 1) { reply.writeNoException(); reply.writeInt(1); return true; }
                        return false;
                    } catch (Throwable t) { return false; }
                }
            };
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method add = sm.getMethod("addService", String.class, Class.forName("android.os.IBinder"));
            add.invoke(null, "jdsp.probe", probe);
            System.out.println("READY binder=jdsp.probe");
        } catch (Throwable t) {
            System.out.println("BINDER_FAIL " + t);
        }

        // ---- loopback TCP 探针（C'）----
        ServerSocket tcp = null;
        try {
            tcp = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
            writeOwned(new File(filesDir, "jdsp.port"), String.valueOf(port).getBytes(UTF8), appUid);
            writeOwned(new File(filesDir, "jdsp.token"), TOKEN.getBytes(UTF8), appUid);
            System.out.println("READY tcp=127.0.0.1:" + port);
        } catch (Throwable t) {
            System.out.println("TCP_FAIL " + t);
        }

        // ---- unix socket 探针（C）----
        LocalServerSocket unix = null;
        try {
            unix = new LocalServerSocket(new File(filesDir, "jdsp.sock").getAbsolutePath());
            System.out.println("READY unix(abstract)=" + new File(filesDir, "jdsp.sock").getAbsolutePath());
        } catch (Throwable t) {
            System.out.println("UNIX_FAIL " + t);
        }
        System.out.flush();

        final ServerSocket tcpRef = tcp;
        final LocalServerSocket unixRef = unix;
        if (tcpRef != null) {
            Thread th = new Thread(new Runnable() {
                @Override public void run() {
                    while (true) {
                        try {
                            final Socket s = tcpRef.accept();
                            Thread worker = new Thread(new Runnable() {
                                @Override public void run() {
                                    try {
                                        s.setSoTimeout(0);
                                        dispatch(s.getInputStream(), s.getOutputStream(), true);
                                    } catch (Throwable t) {
                                        System.out.println("TCP_CONN_END " + t);
                                    } finally {
                                        try { s.close(); } catch (Throwable ignored) {}
                                    }
                                }
                            });
                            worker.setDaemon(true);
                            worker.start();
                        } catch (Throwable t) {
                            System.out.println("TCP_ACCEPT_END " + t);
                            return;
                        }
                    }
                }
            });
            th.setDaemon(true);
            th.start();
        }
        if (unixRef != null) {
            Thread th = new Thread(new Runnable() {
                @Override public void run() {
                    while (true) {
                        try {
                            final LocalSocket c = unixRef.accept();
                            Thread worker = new Thread(new Runnable() {
                                @Override public void run() {
                                    try { dispatch(c.getInputStream(), c.getOutputStream(), false); }
                                    catch (Throwable t) { System.out.println("UNIX_CONN_END " + t); }
                                    finally { try { c.close(); } catch (Throwable ignored) {} }
                                }
                            });
                            worker.setDaemon(true);
                            worker.start();
                        } catch (Throwable t) { return; }
                    }
                }
            });
            th.setDaemon(true);
            th.start();
        }

        long deadline = System.currentTimeMillis() + seconds * 1000L;
        int n = 0;
        while (System.currentTimeMillis() < deadline) { Thread.sleep(1000); if (++n % 20 == 0) System.out.println("alive " + n); }
        System.out.println("DONE");
        System.exit(0);
    }

    private static void writeOwned(File f, byte[] data, int uid) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        out.write(data);
        out.close();
        if (uid > 0) {
            Class<?> os = Class.forName("android.system.Os");
            os.getMethod("chown", String.class, int.class, int.class).invoke(null, f.getAbsolutePath(), uid, uid);
        }
        f.setReadable(false, false);
        f.setReadable(true, true);
        f.setWritable(false, false);
    }

    private static void dispatch(InputStream rawIn, OutputStream rawOut, boolean expectToken) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(rawIn, UTF8));
        OutputStream out = new BufferedOutputStream(rawOut);
        boolean authed = !expectToken;
        String line;
        while ((line = reader.readLine()) != null) {
            String reply;
            try {
                JSONObject req = new JSONObject(line);
                int id = req.optInt("id", 0);
                String op = req.optString("op", req.optString("cmd", ""));
                JSONObject r = new JSONObject();
                r.put("id", id);
                if (!authed) {
                    if ("hello".equals(op) && TOKEN.equals(req.optString("token", ""))) {
                        authed = true;
                        r.put("ok", true);
                    } else {
                        r.put("ok", false);
                        r.put("e", "auth required");
                    }
                } else if ("ping".equals(op)) {
                    r.put("ok", true); r.put("r", 1);
                } else if ("setParam".equals(op)) {
                    System.out.println("setParam s=" + req.optInt("s") + " p=" + req.optInt("p") + " v=" + req.optString("v"));
                    r.put("ok", true); r.put("r", 0);
                } else if ("attach".equals(op) || "commit".equals(op) || "detach".equals(op)) {
                    r.put("ok", true); r.put("r", 0);
                } else if ("getParam".equals(op)) {
                    r.put("ok", true); r.put("v", "00000000");
                } else {
                    r.put("ok", false); r.put("e", "unknown op " + op);
                }
                reply = r.toString();
            } catch (Throwable t) {
                reply = "{\"ok\":false,\"e\":\"bad json: " + t + "\"}";
            }
            out.write(reply.getBytes(UTF8));
            out.write('\n');
            out.flush();
        }
    }
}
