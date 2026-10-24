package me.timschneeberger.rootlessjamesdsp.p5;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * P5 桥：把 App 的引擎参数写入镜像到 jdspd。
 *
 * 调用者只有一个：JdspProxyEffect（extends AudioEffect 的代理，补丁替换
 * JamesDspRemoteEngine.createEffect() 的实例）。因此 App 里几十处 setParameter
 * 调用点不需要改，全部自动流到 jdspd。
 *
 * 传输降级链：socket -> binder -> shell；全失败则整体静默关闭，
 * App 保持原有行为（不会因为 jdspd 不在而崩溃或变哑）。
 *
 * 线程模型：调用方（UI 线程）只做入队，永不阻塞。
 *   - 普通参数：按 (session,param) 合并，只保留最新值（滑条场景）；
 *   - 流式参数（字符/IR 分片 + 提交号）：严格 FIFO，不能乱序。
 * 单后台线程每 ~10ms 排空一次，单轮上限 64 条。
 */
public final class JdspBridge {

    public static final String TAG = "JdspBridge";

    /** 必须保序的参数：缓冲区分片与提交、以及大块配置。 */
    private static final Set<Integer> ORDERED = new HashSet<Integer>();
    static {
        // buffer info / partitions / commits
        ORDERED.add(8888);
        ORDERED.add(9999);
        ORDERED.add(12000);
        ORDERED.add(12001);
        ORDERED.add(10004);
        ORDERED.add(10006);
        ORDERED.add(10009);
        ORDERED.add(10010);
        ORDERED.add(25000);
        ORDERED.add(25001);
        ORDERED.add(25002);
        ORDERED.add(25003);
        // 大块配置数组：设置后通常紧跟一个 enable 参数，顺序错了会有一次瞬态
        ORDERED.add(115);
        ORDERED.add(116);
        ORDERED.add(1300);
        ORDERED.add(1305);
        ORDERED.add(1306);
        ORDERED.add(1307);
    }

    /** 全局开关（App 侧约定 cmd=400，1=开 0=关）。 */
    public static final int PARAM_GLOBAL_ENABLE = 400;

    private static volatile boolean disabled;
    private static volatile boolean started;
    private static volatile String forceTransport;
    private static volatile String socketPathOverride;
    private static volatile JdspTransport transport;
    private static volatile Context appContext;
    private static volatile String lastError = "";
    private static volatile long lastFailureAt;
    private static volatile int consecutiveFailures;
    private static volatile long droppedParams;
    private static volatile long sentParams;

    private static final ConcurrentHashMap<Long, Pending> COALESCE = new ConcurrentHashMap<Long, Pending>();
    private static final LinkedBlockingQueue<Pending> ORDERED_QUEUE = new LinkedBlockingQueue<Pending>();
    private static final Set<Integer> ATTACHED = java.util.Collections.synchronizedSet(new HashSet<Integer>());

    private JdspBridge() {}

    private static final class Pending {
        final int session;
        final int param;
        final byte[] value;
        Pending(int session, int param, byte[] value) {
            this.session = session;
            this.param = param;
            this.value = value;
        }
        long key() {
            return ((long) session << 32) | (param & 0xFFFFFFFFL);
        }
    }

    // ---------------------------------------------------------------- 对外入口

    /** 由代理 Effect 在构造时登记进程上下文（可省略，缺省走反射）。 */
    public static void init(Context context) {
        appContext = context;
    }

    public static void onParamBytes(int sessionId, int paramId, byte[] value) {
        enqueue(sessionId, paramId, value);
    }

    public static void onParamShort(int sessionId, int paramId, short value) {
        enqueue(sessionId, paramId, JdspCodec.shortBytes(value));
    }

    public static void onParamInt(int sessionId, int paramId, int value) {
        enqueue(sessionId, paramId, JdspCodec.intBytes(value));
    }

    public static void onParamFloats(int sessionId, int paramId, float[] value) {
        enqueue(sessionId, paramId, JdspCodec.floatBytes(value));
    }

    /** 全局启用/旁通：镜像成 cmd=400（引擎侧持久化）。 */
    public static void onEnabled(int sessionId, boolean enabled) {
        enqueue(sessionId, PARAM_GLOBAL_ENABLE, JdspCodec.shortBytes((short) (enabled ? 1 : 0)));
    }

    /** 诊断用：当前传输与计数。 */
    public static String status() {
        JdspTransport t = transport;
        return "transport=" + (t == null ? "none" : t.name())
                + " connected=" + (t != null && t.isConnected())
                + " disabled=" + disabled
                + " queued=" + (COALESCE.size() + ORDERED_QUEUE.size())
                + " sent=" + sentParams
                + " dropped=" + droppedParams
                + " lastError=" + lastError;
    }

    /** 测试/排障：强制指定传输（socket|binder|shell|off）。 */
    public static void setForceTransport(String kind) {
        forceTransport = kind;
        JdspTransport t = transport;
        transport = null;
        if (t != null) t.close();
        consecutiveFailures = 0;
        disabled = "off".equals(kind);
    }

    public static void setSocketPath(String path) {
        socketPathOverride = path;
    }

    // ---------------------------------------------------------------- 入队与出队

    private static void enqueue(int sessionId, int paramId, byte[] value) {
        if (disabled || value == null) return;
        ensureStarted();
        if (!started || disabled) return;
        Pending p = new Pending(sessionId, paramId, value);
        if (ORDERED.contains(paramId)) {
            if (ORDERED_QUEUE.size() > 8192) { droppedParams++; return; }
            ORDERED_QUEUE.offer(p);
        } else {
            Pending previous = COALESCE.put(p.key(), p);
            if (previous != null) droppedParams++;  // 旧的被新值取代，符合预期
        }
    }

    private static synchronized void ensureStarted() {
        if (started) return;
        started = true;
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                workerLoop();
            }
        }, "JdspBridge");
        worker.setDaemon(true);
        worker.start();
    }

    private static void workerLoop() {
        List<Pending> batch = new ArrayList<Pending>(64);
        while (true) {
            try {
                if (disabled) { Thread.sleep(500); continue; }
                batch.clear();
                // 1) 保序队列优先
                Pending ordered = ORDERED_QUEUE.poll();
                while (ordered != null && batch.size() < 64) {
                    batch.add(ordered);
                    ordered = ORDERED_QUEUE.poll();
                }
                // 2) 合并队列（最新值）
                if (batch.size() < 64 && !COALESCE.isEmpty()) {
                    for (Map.Entry<Long, Pending> e : COALESCE.entrySet()) {
                        if (batch.size() >= 64) break;
                        Pending p = COALESCE.remove(e.getKey());
                        if (p != null) batch.add(p);
                    }
                }
                if (batch.isEmpty()) {
                    Thread.sleep(5);
                    continue;
                }
                JdspTransport t = ensureTransport();
                if (t == null) { Thread.sleep(500); continue; }
                for (Pending p : batch) {
                    if (disabled) break;
                    boolean ok = push(t, p);
                    if (!ok) break;
                }
                batch.clear();
                Thread.sleep(5);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable th) {
                lastError = th.getClass().getSimpleName() + ": " + th.getMessage();
                try { Thread.sleep(500); } catch (InterruptedException ignored) { return; }
            }
        }
    }

    private static boolean push(JdspTransport t, Pending p) {
        try {
            if (ATTACHED.add(p.session)) {
                t.attach(p.session);
            }
            int rc = t.setParam(p.session, p.param, p.value);
            if (rc >= 0) {
                sentParams++;
                consecutiveFailures = 0;
                return true;
            }
            lastError = "setParam rc=" + rc + " via " + t.name();
            consecutiveFailures++;
            if (consecutiveFailures >= 5) {
                Log.w(TAG, "transport failing, re-select: " + lastError);
                dropTransport();
            }
            return false;
        } catch (Throwable th) {
            lastError = th.getClass().getSimpleName() + ": " + th.getMessage();
            consecutiveFailures++;
            return false;
        }
    }

    private static synchronized void dropTransport() {
        JdspTransport t = transport;
        transport = null;
        ATTACHED.clear();
        consecutiveFailures = 0;
        if (t != null) t.close();
    }

    // ---------------------------------------------------------------- 传输选择

    private static synchronized JdspTransport ensureTransport() {
        JdspTransport current = transport;
        if (current != null && current.isConnected()) return current;
        if (disabled) return null;
        if (current != null) current.close();
        transport = null;
        ATTACHED.clear();

        String forced = forceTransport;
        JdspTransport chosen = null;
        if (forced != null && !"off".equals(forced)) {
            chosen = create(forced);
            if (chosen != null && chosen.connect()) {
                transport = chosen;
                Log.i(TAG, "transport ready: " + chosen.name());
                return chosen;
            }
            chosen = null;
        } else if (forced == null) {
            for (String kind : new String[] { "tcp", "socket", "binder", "shell" }) {
                JdspTransport candidate = create(kind);
                if (candidate == null) continue;
                if (candidate.connect()) { chosen = candidate; break; }
                candidate.close();
            }
            if (chosen != null) {
                transport = chosen;
                Log.i(TAG, "transport ready: " + chosen.name());
                return chosen;
            }
        }

        lastFailureAt = System.currentTimeMillis();
        if (consecutiveFailures >= 3) {
            disabled = true;
            Log.w(TAG, "no usable transport, mirroring disabled: " + lastError);
        }
        return null;
    }

    private static JdspTransport create(String kind) {
        if ("tcp".equals(kind)) {
            Context ctx = appContext;
            if (ctx == null) ctx = currentApplication();
            if (ctx == null) return null;
            appContext = ctx;
            File dir = ctx.getFilesDir();
            if (dir == null) return null;
            int port = readInt(new File(dir, "jdsp.port"), 0);
            String token = readLine(new File(dir, "jdsp.token"));
            if (port <= 0 || token.isEmpty()) return null;
            return new TcpTransport("127.0.0.1", port, token);
        }
        if ("socket".equals(kind)) {
            String path = socketPathOverride;
            if (path == null) path = resolveSocketPath();
            if (path == null) return null;
            return new SocketTransport(path);
        }
        if ("binder".equals(kind)) return new BinderTransport("jdsp.service");
        if ("shell".equals(kind)) return new ShellTransport();
        return null;
    }

    /** App 私有目录里的 jdsp.sock；拿不到 Context 时退回按包名拼。 */
    private static String resolveSocketPath() {
        Context ctx = appContext;
        if (ctx == null) ctx = currentApplication();
        if (ctx != null) {
            appContext = ctx;
            File dir = ctx.getFilesDir();
            if (dir != null) return new File(dir, "jdsp.sock").getAbsolutePath();
        }
        return null;
    }

    /** 读取 jdspd 写下的端口/token 文件（不存在或读不到返回默认值）。 */
    private static int readInt(File file, int def) {
        try {
            return Integer.parseInt(readLine(file).trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static String readLine(File file) {
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(new java.io.FileReader(file));
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Throwable t) {
            return "";
        } finally {
            if (reader != null) { try { reader.close(); } catch (Throwable ignored) {} }
        }
    }

    private static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
