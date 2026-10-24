package com.jdsp.selftest.probe;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Binder;
import android.os.Parcel;
import android.system.Os;
import android.system.OsConstants;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.Charset;

/**
 * P5 传输自测的"根侧"服务端（在设备上以 root 通过 app_process 运行）。
 *
 * 用法：CLASSPATH=probe.dex app_process /system/bin com.jdsp.selftest.probe.ProbeServer \
 *          <socketPath> <appUid> <seconds>
 *
 * 做两件事：
 *  1) 注册 binder 服务 jdsp.probe（验证 A 方案：untrusted_app 能否 find/call）
 *  2) 在 socketPath 上监听 unix socket，JSON lines 协议（验证 C 方案：SELinux + DAC）
 */
public final class ProbeServer {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static void main(String[] args) throws Exception {
        String socketPath = args.length > 0 ? args[0] : "/data/local/tmp/jdsp.sock";
        int appUid = args.length > 1 ? Integer.parseInt(args[1]) : -1;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 120;

        // ---- binder 探针 ----
        try {
            Binder probe = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                        throws android.os.RemoteException {
                    try {
                        data.enforceInterface("com.jdsp.probe.IPing");
                        if (code == 1) {
                            reply.writeNoException();
                            reply.writeInt(1);
                            return true;
                        }
                        return false;
                    } catch (Throwable t) {
                        return false;
                    }
                }
            };
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method add = sm.getMethod("addService", String.class, Class.forName("android.os.IBinder"));
            add.invoke(null, "jdsp.probe", probe);
            System.out.println("READY binder=jdsp.probe");
        } catch (Throwable t) {
            System.out.println("BINDER_FAIL " + t);
        }

        // ---- socket 探针 ----
        LocalServerSocket server = null;
        try {
            // 必须用 FILESYSTEM 命名空间：LocalServerSocket(String) 默认是 ABSTRACT，不会落盘。
            // 而 LocalServerSocket(LocalSocketAddress) 是隐藏 API，SDK stub 里没有 → 反射拿构造器。
            java.lang.reflect.Constructor<?> ctor = LocalServerSocket.class
                    .getDeclaredConstructor(android.net.LocalSocketAddress.class);
            ctor.setAccessible(true);
            server = (LocalServerSocket) ctor.newInstance(new android.net.LocalSocketAddress(
                    socketPath, android.net.LocalSocketAddress.Namespace.FILESYSTEM));
            try {
                Os.chmod(socketPath, 0660);
                if (appUid > 0) Os.chown(socketPath, appUid, appUid);
            } catch (Throwable t) {
                System.out.println("CHMOD_FAIL " + t);
            }
            System.out.println("READY socket=" + socketPath);
        } catch (Throwable t) {
            System.out.println("SOCKET_FAIL " + t);
        }
        System.out.flush();

        final LocalServerSocket srv = server;
        Thread acceptor = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    try {
                        LocalSocket client = srv.accept();
                        handle(client);
                    } catch (Throwable t) {
                        System.out.println("ACCEPT_END " + t);
                        return;
                    }
                }
            }
        });
        if (server != null) { acceptor.setDaemon(true); acceptor.start(); }

        long deadline = System.currentTimeMillis() + seconds * 1000L;
        int counter = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000);
            counter++;
            if (counter % 10 == 0) System.out.println("alive " + counter);
        }
        System.out.println("DONE");
        System.exit(0);
    }

    private static void handle(LocalSocket client) throws Exception {
        InputStream in = client.getInputStream();
        OutputStream out = client.getOutputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF8));
        String line;
        while ((line = reader.readLine()) != null) {
            String reply = "{\"ok\":false,\"e\":\"bad request\"}";
            try {
                JSONObject req = new JSONObject(line);
                String op = req.optString("op", "");
                int id = req.optInt("id", 0);
                JSONObject r = new JSONObject();
                r.put("id", id);
                r.put("ok", true);
                if ("ping".equals(op)) {
                    r.put("r", 1);
                } else if ("setParam".equals(op)) {
                    System.out.println("setParam s=" + req.optInt("s") + " p=" + req.optInt("p")
                            + " v=" + req.optString("v"));
                    r.put("r", 0);
                } else if ("getParam".equals(op)) {
                    r.put("v", "00000000");
                } else if ("attach".equals(op)) {
                    r.put("r", 0);
                } else if ("commit".equals(op)) {
                    r.put("r", 0);
                } else {
                    r.put("ok", false);
                    r.put("e", "unknown op " + op);
                }
                reply = r.toString();
            } catch (Throwable t) {
                reply = "{\"ok\":false,\"e\":\"" + t + "\"}";
            }
            out.write(reply.getBytes(UTF8));
            out.write('\n');
            out.flush();
        }
        client.close();
    }
}
