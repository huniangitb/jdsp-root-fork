package com.jdsp.backend;

import com.jdsp.common.EngineWriteKey;
import com.jdsp.common.JdspConst;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * jdspd entry point: a root {@code app_process} daemon that owns one AudioEffect handle per
 * playback session and exposes the control surface over binder ({@code jdsp.service}) and over a
 * token authenticated loopback TCP channel (used by the App).
 *
 * <p>Usage:
 * <pre>
 *   CLASSPATH=/data/adb/jdspd/jdspd.dex app_process /system/bin \\
 *       --nice-name=jdspd com.jdsp.backend.Daemon [--selftest] [--config PATH]
 *       [--data DIR] [--tcp-dir DIR] [--tcp-port N]
 * </pre>
 *
 * <ul>
 *   <li>{@code --selftest}: run one watcher pass, try both transports, print a JSON summary and
 *       exit (no daemon is left behind).</li>
 *   <li>{@code --config PATH}: config file, default {@code /data/adb/jdspd/config.json}.</li>
 *   <li>{@code --data DIR}: runtime directory (log + default config), default
 *       {@code /data/adb/jdspd}.</li>
 *   <li>{@code --tcp-dir DIR}: directory receiving {@code jdsp.port}/{@code jdsp.token},
 *       default {@code /data/data/james.dsp/files}.</li>
 *   <li>{@code --tcp-port N}: fixed control port (0 = system assigned).</li>
 * </ul>
 */
public final class Daemon {
    private static final String TAG = "jdspd";
    /** Fallback runtime directory when /data/adb is not available. */
    private static final String FALLBACK_DATA_DIR = "/data/local/tmp/jdspd";

    private static ConfigStore config;
    private static PolicyEngine policy;
    private static EffectManager effects;
    private static ParamBus bus;
    private static SessionWatcher watcher;
    private static CoreApi api;
    private static BinderService binder;
    private static TcpService tcp;
    private static volatile boolean shuttingDown;
    private static String instanceLockFile;
    private static FileChannel instanceLockChannel;
    private static FileLock instanceLock;

    private Daemon() {
    }

    public static void main(String[] args) {
        String configPath = null;
        String dataDir = JdspConst.DATA_DIR;
        String tcpDir = TcpService.DEFAULT_DIR;
        int tcpPort = 0;
        boolean selftest = false;
        boolean stopRequested = false;
        String modeArg = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--selftest".equals(a) || "-s".equals(a)) {
                selftest = true;
            } else if ("--config".equals(a) && i + 1 < args.length) {
                configPath = args[++i];
            } else if ("--data".equals(a) && i + 1 < args.length) {
                dataDir = args[++i];
            } else if ("--tcp-dir".equals(a) && i + 1 < args.length) {
                tcpDir = args[++i];
            } else if ("--tcp-port".equals(a) && i + 1 < args.length) {
                tcpPort = parseInt(args[++i], 0);
            } else if ("--mode".equals(a) && i + 1 < args.length) {
                modeArg = args[++i];
            } else if ("--stop".equals(a)) {
                stopRequested = true;
            } else if ("--help".equals(a) || "-h".equals(a)) {
                usage();
                return;
            } else if (a.startsWith("--nice-name")) {
                // consumed by app_process itself, ignore if it leaks through
            } else {
                System.err.println("jdspd: ignoring unknown argument: " + a);
            }
        }
        if (configPath == null) {
            configPath = dataDir + "/config.json";
        }

        // ---- runtime directory + logging ------------------------------------
        File dir = new File(dataDir);
        if (!FileUtil.mkdirs(dir, 0700)) {
            dir = new File(FALLBACK_DATA_DIR);
            FileUtil.mkdirs(dir, 0700);
            System.err.println("jdspd: cannot use " + dataDir + ", falling back to " + dir);
        }
        String logPath = new File(dir, "jdspd.log").getPath();
        Log.setEcho(selftest);
        Log.init(logPath);
        Log.i("=== jdspd " + CoreApi.VERSION + " starting (pid "
                + android.os.Process.myPid() + ", selftest=" + selftest + ", args="
                + join(args) + ")");
        FileUtil.chmod(new File(logPath), 0600);

        if (stopRequested) {
            // Client mode: ask the running daemon to release everything and exit.
            boolean ok = requestRemoteStop(tcpDir, tcpPort);
            System.out.println(ok ? "jdspd: shutdown requested"
                    : "jdspd: no running instance answered on the control channel");
            Log.close();
            System.exit(ok ? 0 : 1);
            return;
        }
        if (!selftest && !acquireInstanceLock(dir)) {
            // Another daemon owns the device; exit 0 so a supervisor does not treat it as a
            // crash and restart in a loop.
            Log.close();
            System.exit(0);
        }

        // ---- core wiring -----------------------------------------------------
        EffectManager.initReflection();
        config = new ConfigStore(new File(configPath));
        config.load();
        policy = new PolicyEngine();
        policy.refresh();
        effects = new EffectManager();
        // Engine write lock: generate/load the key, hand it to the manager (every
        // parameter write unlocks the instance with it first) and mirror it to the
        // HAL-readable path. Without a key the engine refuses every write, so a
        // failure here is logged loudly but must not stop the daemon.
        byte[] engineKey = EngineWriteKey.ensureKey(new File(dir, "engine.key"));
        if (engineKey != null) {
            effects.setEngineKey(engineKey);
            boolean mirrored = EngineWriteKey.mirrorToVendor(engineKey,
                    new File(EngineWriteKey.VENDOR_KEY_FILE));
            Log.i("engine write key ready (" + EngineWriteKey.ADB_KEY_FILE
                    + ", mirror " + EngineWriteKey.VENDOR_KEY_FILE + ": "
                    + (mirrored ? "ok" : "FAILED - parameter writes will be refused") + ")");
        } else {
            Log.w("engine write key unavailable: parameter writes will be refused"
                    + " (create " + EngineWriteKey.ADB_KEY_FILE + " and restart)");
        }
        bus = new ParamBus(effects);
        watcher = new SessionWatcher(config, policy, effects, bus);
        api = new CoreApi(config, policy, effects, bus, watcher);

        if (modeArg != null) {
            int m = "backend".equalsIgnoreCase(modeArg) ? JdspConst.MODE_BACKEND
                    : ("auto".equalsIgnoreCase(modeArg) ? JdspConst.MODE_AUTO : -1);
            if (m < 0) {
                Log.w("--mode: invalid value '" + modeArg + "' (auto|backend), ignored");
            } else {
                api.setModeOverride(m);
                Log.i("mode override for this run only: "
                        + (m == JdspConst.MODE_BACKEND ? "backend" : "auto") + " (not persisted)");
            }
        }
        api.setInstanceInfo(instanceLockFile, System.currentTimeMillis());
        api.setShutdownHook(new CoreApi.ShutdownHook() {
            @Override
            public void requestShutdown(String reason) {
                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        shutdown("control channel");
                        System.exit(0);
                    }
                }, "jdspd-remote-shutdown");
                thread.setDaemon(false);
                thread.start();
            }
        });
        Log.i("config: " + configPath + " mode=" + config.getMode()
                + " params=" + config.globalParamCount() + " policies=" + config.policies().size());

        if (selftest) {
            runSelftest(configPath, dir.getPath(), tcpDir, tcpPort);
            return;
        }

        // ---- transports ------------------------------------------------------
        binder = new BinderService(api);
        binder.publish();
        binder.startRetryLoop();

        tcp = new TcpService(api, tcpDir, tcpPort);
        tcp.start();
        // The watcher re-creates the App's port/token files when they disappear (App reinstall).
        watcher.setTcpService(tcp);
        // The watcher applies a rate limited "on" transition of the global switch.
        watcher.setCoreApi(api);

        // ---- watcher ---------------------------------------------------------
        watcher.start();
        installSignalHandler();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                shutdown("shutdown hook");
            }
        }, "jdspd-shutdown"));

        Log.i("jdspd ready: binder=" + binder.isPublished()
                + " tcp=" + (tcp.isRunning() ? String.valueOf(tcp.port()) : "off")
                + " config=" + configPath);

        // Main thread: idle, but honour a stop file so an external supervisor can ask for a
        // graceful release even when signals are not delivered to the JVM.
        File stopFile = new File(dir, "stop");
        while (!shuttingDown) {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (stopFile.exists()) {
                stopFile.delete();
                Log.i("stop file detected, shutting down");
                break;
            }
        }
        shutdown("main loop exit");
    }

    /** One watcher pass + both transports, then a JSON summary on stdout. */
    private static void runSelftest(String configPath, String dataDir, String tcpDir, int tcpPort) {
        long t0 = System.currentTimeMillis();
        Log.i("selftest: one watcher pass");
        watcher.pollOnce();
        long passMs = System.currentTimeMillis() - t0;

        BinderService b = new BinderService(api);
        boolean binderOk = b.publish();

        // Never clobber the port/token files of a running daemon: the selftest uses its own dir.
        File selftestTcpDir = new File(dataDir, "tcp-selftest");
        FileUtil.mkdirs(selftestTcpDir, 0700);
        TcpService t = new TcpService(api, selftestTcpDir.getPath(), tcpPort);
        boolean tcpOk = t.start();

        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
            out.put("version", CoreApi.VERSION);
            out.put("pid", android.os.Process.myPid());
            out.put("dataDir", dataDir);
            out.put("configPath", configPath);
            out.put("configExists", new File(configPath).isFile());
            out.put("logFile", new File(dataDir, "jdspd.log").getPath());
            out.put("mode", config.getMode());
            out.put("modeName", config.getMode() == JdspConst.MODE_BACKEND ? "backend" : "auto");
            out.put("engineStateImport", JdspConst.ENGINE_STATE_FILE);
            out.put("effectReflection", EffectManager.reflectionStatus());
            out.put("watcherPassMs", passMs);
            out.put("dumpsysChars", watcher.lastDumpBytes());
            out.put("watcherError", watcher.lastError());
            out.put("policyPackages", policy.packageCount());
            out.put("binder", binderOk);
            out.put("binderError", b.lastError());
            JSONObject tcpJson = new JSONObject();
            tcpJson.put("listening", tcpOk);
            tcpJson.put("port", tcpOk ? t.port() : -1);
            tcpJson.put("portFile", t.portFile());
            tcpJson.put("tokenFile", t.tokenFile());
            tcpJson.put("error", t.lastError());
            tcpJson.put("dirUsed", selftestTcpDir.getPath());
            tcpJson.put("appDir", tcpDir);
            out.put("tcp", tcpJson);
            JSONArray sessions = new JSONArray();
            java.util.List<CoreApi.SessionView> views = api.listSessions();
            for (int i = 0; i < views.size(); i++) {
                sessions.put(views.get(i).toJson());
            }
            out.put("sessionCount", views.size());
            out.put("sessions", sessions);
            out.put("attached", effects.handleCount());
        } catch (Throwable e) {
            Log.e("selftest: cannot build summary", e);
        }
        System.out.println(out.toString());

        t.stop();
        effects.releaseAll();
        config.saveIfDirty();
        bus.shutdown();
        Log.i("selftest done");
        Log.close();
    }


    // ---- remote stop client --------------------------------------------------

    /**
     * Asks a running daemon to shut down gracefully over its control channel (this process never
     * holds the effect handles, so this is the only way to get a clean release from outside).
     */
    private static boolean requestRemoteStop(String tcpDir, int portOverride) {
        String dir = (tcpDir == null || tcpDir.length() == 0) ? TcpService.DEFAULT_DIR : tcpDir;
        try {
            int port = portOverride;
            if (port <= 0) {
                byte[] raw = FileUtil.readAll(new File(dir, TcpService.PORT_FILE_NAME), 4096);
                if (raw == null) {
                    System.err.println("jdspd: cannot read " + dir + "/"
                            + TcpService.PORT_FILE_NAME);
                    return false;
                }
                port = Integer.parseInt(new String(raw, "UTF-8").trim());
            }
            byte[] tokenRaw = FileUtil.readAll(new File(dir, TcpService.TOKEN_FILE_NAME), 4096);
            if (tokenRaw == null) {
                System.err.println("jdspd: cannot read " + dir + "/" + TcpService.TOKEN_FILE_NAME);
                return false;
            }
            String token = new String(tokenRaw, "UTF-8").trim();
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(5000);
            java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(socket.getInputStream(), "UTF-8"));
            java.io.OutputStream out = socket.getOutputStream();
            out.write(("{\"op\":\"hello\",\"token\":\"" + token + "\"}\n").getBytes("UTF-8"));
            out.flush();
            String hello = in.readLine();
            if (hello == null || hello.indexOf("\"ok\":true") < 0) {
                System.err.println("jdspd: control channel rejected the token: " + hello);
                socket.close();
                return false;
            }
            out.write("{\"cmd\":\"shutdown\",\"confirm\":true}\n".getBytes("UTF-8"));
            out.flush();
            String reply = in.readLine();
            System.out.println("jdspd: " + reply);
            socket.close();
            return reply != null && reply.indexOf("\"ok\":true") >= 0;
        } catch (Throwable t) {
            System.err.println("jdspd: remote stop failed: " + EffectManager.describe(t));
            return false;
        }
    }

    // ---- single instance guard ------------------------------------------------

    /**
     * Takes an exclusive lock on {@code <data>/jdspd.lock} so a second daemon cannot fight the
     * first one over the same effect handles. POSIX fcntl locks are per process, so this works
     * across processes. Returns true when the lock was acquired (or cannot be created at all, in
     * which case the daemon runs anyway and says so).
     */
    private static boolean acquireInstanceLock(File dir) {
        File lockFile = new File(dir, "jdspd.lock");
        try {
            RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
            FileChannel channel = raf.getChannel();
            FileLock fileLock = channel.tryLock();
            if (fileLock == null) {
                long otherPid = readPid(raf);
                String message = "another jdspd is running (pid=" + otherPid + "), exiting";
                Log.w(message);
                System.err.println("jdspd: " + message);
                try {
                    channel.close();
                } catch (Throwable ignored) {
                    // nothing to do
                }
                return false;
            }
            instanceLockFile = lockFile.getPath();
            instanceLockChannel = channel;
            instanceLock = fileLock;
            String info = "pid=" + android.os.Process.myPid()
                    + " started=" + System.currentTimeMillis() + "\n";
            raf.setLength(0);
            raf.write(info.getBytes("UTF-8"));
            raf.getFD().sync();
            Log.i("instance lock acquired: " + instanceLockFile);
            return true;
        } catch (Throwable t) {
            Log.w("instance lock unavailable (" + EffectManager.describe(t)
                    + "), continuing without single instance protection");
            return true;
        }
    }

    private static long readPid(RandomAccessFile raf) {
        try {
            raf.seek(0L);
            byte[] buf = new byte[128];
            int n = raf.read(buf);
            if (n <= 0) {
                return -1L;
            }
            String text = new String(buf, 0, n, "UTF-8").trim();
            int eq = text.indexOf("pid=");
            if (eq < 0) {
                return -1L;
            }
            int end = eq + 4;
            while (end < text.length() && Character.isDigit(text.charAt(end))) {
                end++;
            }
            return Long.parseLong(text.substring(eq + 4, end));
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static void releaseInstanceLock() {
        try {
            if (instanceLock != null) {
                instanceLock.release();
            }
        } catch (Throwable ignored) {
            // nothing to do
        }
        try {
            if (instanceLockChannel != null) {
                instanceLockChannel.close();
            }
        } catch (Throwable ignored) {
            // nothing to do
        }
        instanceLock = null;
        instanceLockChannel = null;
        if (instanceLockFile != null) {
            try {
                new File(instanceLockFile).delete();
            } catch (Throwable ignored) {
                // nothing to do
            }
        }
    }
// __D4__

    /** Best effort SIGTERM/SIGINT handling (Android's libcore has no sun.misc.Signal). */
    private static void installSignalHandler() {
        try {
            final Class<?> signalClass = Class.forName("sun.misc.Signal");
            final Class<?> handlerClass = Class.forName("sun.misc.SignalHandler");
            Object handler = java.lang.reflect.Proxy.newProxyInstance(
                    Daemon.class.getClassLoader(), new Class<?>[] {handlerClass},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method method,
                                             Object[] methodArgs) {
                            if ("handle".equals(method.getName())) {
                                Log.i("signal received: " + (methodArgs == null ? "?"
                                        : String.valueOf(methodArgs[0])));
                                shutdown("signal");
                            }
                            return null;
                        }
                    });
            Object term = signalClass.getConstructor(String.class).newInstance("TERM");
            Object intr = signalClass.getConstructor(String.class).newInstance("INT");
            java.lang.reflect.Method handle =
                    signalClass.getMethod("handle", signalClass, handlerClass);
            handle.invoke(null, term, handler);
            handle.invoke(null, intr, handler);
            Log.i("signal handlers installed (TERM, INT)");
        } catch (Throwable t) {
            // libcore does not ship sun.misc.Signal; rely on the shutdown hook and the stop file.
            Log.i("signal handlers unavailable (" + t.getClass().getSimpleName()
                    + "), using shutdown hook + stop file");
        }
    }

    /** Graceful stop: release every effect handle, stop transports, flush the config. */
    private static synchronized void shutdown(String reason) {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        Log.i("shutdown requested (" + reason + ")");
        try {
            if (watcher != null) {
                watcher.stop();
            }
        } catch (Throwable t) {
            Log.w("shutdown: watcher stop failed", t);
        }
        try {
            if (tcp != null) {
                tcp.stop();
            }
        } catch (Throwable t) {
            Log.w("shutdown: tcp stop failed", t);
        }
        int released = 0;
        try {
            if (effects != null) {
                released = effects.releaseAll();
            }
        } catch (Throwable t) {
            Log.w("shutdown: release failed", t);
        }
        try {
            if (config != null) {
                config.saveIfDirty();
            }
        } catch (Throwable t) {
            Log.w("shutdown: config save failed", t);
        }
        try {
            if (bus != null) {
                bus.shutdown();
            }
        } catch (Throwable t) {
            Log.w("shutdown: bus stop failed", t);
        }
        releaseInstanceLock();
        Log.i("=== jdspd stopped (released " + released + " handle(s))");
        Log.close();
    }

    private static void usage() {
        System.out.println("usage: app_process /system/bin com.jdsp.backend.Daemon [options]");
        System.out.println("  --selftest            run one watcher pass, print JSON, exit");
        System.out.println("  --config PATH         config file (default /data/adb/jdspd/config.json)");
        System.out.println("  --data DIR            runtime dir (log + default config)");
        System.out.println("  --tcp-dir DIR         where jdsp.port/jdsp.token are written");
        System.out.println("  --tcp-port N          fixed control port (0 = system assigned)");
        System.out.println("  --mode auto|backend   override the stored mode for this run (not persisted)");
        System.out.println("  --stop                ask the running daemon to release everything and exit");
        System.out.println("  --help                this text");
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static String join(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString();
    }
}
