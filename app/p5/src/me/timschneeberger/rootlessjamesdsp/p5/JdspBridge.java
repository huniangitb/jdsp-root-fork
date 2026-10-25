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
    private static volatile long broadcastedParams;
    /** 因后端不可用而保留、等待重放的参数数。 */
    private static volatile long retainedForRetry;
    /** 待重放的后端全局开关（null = 无）。 */
    private static volatile Boolean pendingEnabled;
    /** 后端当前处于"全局关闭"状态（用户意图，不是故障）。 */
    private static volatile boolean backendDisabled;
    /** 后端返回的"全局关闭"错误码（daemon-core 约定 -1006）。 */
    public static final int ERR_DISABLED = -1006;
    /** 上次刷新会话列表的时刻。 */
    private static volatile long lastSessionRefresh;
    /** 最近一次 dumpConfig 的结果（带 TTL 缓存）。 */
    private static volatile String configCache;
    private static volatile long configCacheAt;
    /** 配置快照缓存时间（毫秒）。 */
    public static final long CONFIG_CACHE_TTL_MS = 200L;

    private static final ConcurrentHashMap<Long, Pending> COALESCE = new ConcurrentHashMap<Long, Pending>();
    /** session<=0 的全局参数暂存区（等有真实会话时重放）。 */
    private static final ConcurrentHashMap<Long, Pending> pendingBroadcast = new ConcurrentHashMap<Long, Pending>();
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

    /**
     * 引擎参数 id 以 4 字节小端数组给出的写入（AudioEffect.setParameter(byte[], byte[]) 形态）。
     * 数组长度不足 4 时忽略。
     */
    public static void onParamRaw(int sessionId, byte[] param, byte[] value) {
        if (param == null || param.length < 4) return;
        int id = (param[0] & 0xFF)
                | ((param[1] & 0xFF) << 8)
                | ((param[2] & 0xFF) << 16)
                | ((param[3] & 0xFF) << 24);
        onParamBytes(sessionId, id, value);
    }

    /**
     * 全局启用/旁通：**走后端的配置开关**（不再用引擎的 cmd=400）。
     *
     * v6.7.2 引擎没有实现 cmd=400（实测写入返回 -1），镜像过去只会让
     * UI 显示"开了"而实际无效果。后端已有 config.enabled 字段，
     * 语义是"配置开关"（是否把配置下发给引擎），与"音频会话 bypass"是两件事。
     */
    public static void onEnabled(int sessionId, boolean enabled) {
        setEnabledNow(enabled);
    }

    /**
     * 提交一个参数（**异步**，UI 线程永不阻塞）。
     *
     * 为什么必须异步：写路径如果同步发 TCP，每次 setParameter 要等一个 RTT
     * （实测 ~1.5 ms）。而一次 syncWithPreferences 会连着下发约 30 个参数
     * → UI 线程会被卡住约 50 ms。滑条拖动时更是每帧一次。
     *
     * 语义：
     *   - 后端可用 → 入队，后台线程按合并策略（last-wins）批量下发；
     *   - 后端不可用 → 同样入队（保留意图），等后端恢复后重放；
     *   - 返回值只表示"是否已受理"（0 = 已入队），**不代表引擎已生效**。
     *
     * @return 0 = 已入队；负数 = 连队列都进不去（参数丢失，仅在极端情况）。
     */
    public static int submitParam(int sessionId, int paramId, byte[] value) {
        if (value == null) return -1;
        ensureStarted();
        if (!started) return -1;
        Pending p = new Pending(sessionId, paramId, value);
        if (ORDERED.contains(paramId)) {
            if (ORDERED_QUEUE.size() > 8192) { droppedParams++; return -1; }
            ORDERED_QUEUE.offer(p);
        } else {
            Pending previous = COALESCE.put(p.key(), p);
            if (previous != null) droppedParams++;  // 旧值被新值取代，符合预期
        }
        return 0;
    }

    /**
     * 立即把一个参数发给后端（**同步**，只供内部/测试使用）。
     *
     * @return 0 = 已被后端受理；负数 = 后端不可用/失败。
     */
    public static int setParamNow(int sessionId, int paramId, byte[] value) {
        if (value == null || value.length == 0) return -1;
        JdspTransport t = currentTransport();
        if (t == null) return -1;
        try {
            int rc = t.setParam(sessionId, paramId, value);
            if (rc >= 0) {
                sentParams++;
                consecutiveFailures = 0;
            } else if (rc == ERR_DISABLED) {
                // 后端被全局关掉：不是故障，是用户意图。保留参数，等 enabled=true 再重放。
                backendDisabled = true;
                lastError = "backend globally disabled (rc=" + rc + ")";
            }
            return rc;
        } catch (Throwable th) {
            lastError = th.getClass().getSimpleName() + ": " + th.getMessage();
            return -1;
        }
    }

    /**
     * 后端不可用时把参数**留在重试队列**（不写本地）。
     *
     * 队列语义与普通参数一致：同 (session,param) 只留最新值；
     * 后端恢复后由后台线程重放。
     */
    public static void retainForRetry(int sessionId, int paramId, byte[] value) {
        if (value == null) return;
        ensureStarted();
        if (!started) return;
        Pending pending = new Pending(sessionId, paramId, value);
        if (ORDERED.contains(paramId)) {
            // 保序参数（缓冲区分片/提交）**不能合并**，必须 FIFO 保留
            if (ORDERED_QUEUE.size() > 8192) { droppedParams++; return; }
            ORDERED_QUEUE.offer(pending);
        } else {
            Pending previous = COALESCE.put(pending.key(), pending);
            if (previous != null) droppedParams++;
        }
        retainedForRetry++;
    }

    /**
     * 后端全局配置开关。
     *
     * @return 0 = ok；负数 = 后端不可用。
     */
    public static int setEnabledNow(boolean enabled) {
        JdspTransport t = currentTransport();
        if (t == null) {
            pendingEnabled = Boolean.valueOf(enabled);
            retainedForRetry++;
            return -1;
        }
        try {
            int rc = t.setEnabled(enabled);
            if (rc >= 0) {
                pendingEnabled = null;
                consecutiveFailures = 0;
                if (enabled) {
                    // 用户把开关打开：后端 ≤1s 内会重放参数，这里也把暂存的补发一次
                    backendDisabled = false;
                }
                return rc;
            }
            // 失败一律按**故障**处理。
            //
            // 后端已做结构性保证（backend/src/com/jdsp/common/EnableErrors.java）：
            // 启用类命令（setEnabled/getEnabled）**永远不返回 -1006**，
            // 万一内部产生也会被降级成 -1004（普通故障）。
            // → 所以这里不需要（也不应该）把 -1006 当成"等用户开回来"，
            //   否则真故障会被误判成用户意图、无限等待。
            pendingEnabled = Boolean.valueOf(enabled);
            consecutiveFailures++;
            lastError = "setEnabled failed rc=" + rc + " via " + t.name();
            if (consecutiveFailures >= 5) {
                Log.w(TAG, "setEnabled failing, re-select transport: " + lastError);
                dropTransport();
            }
            return rc;
        } catch (Throwable th) {
            lastError = th.getClass().getSimpleName() + ": " + th.getMessage();
            pendingEnabled = Boolean.valueOf(enabled);
            consecutiveFailures++;
            return -1;
        }
    }

    /**
     * 查询后端全局开关。读不到返回 null。
     */
    public static Boolean getEnabled() {
        JdspTransport t = currentTransport();
        if (t == null) return null;
        try {
            Boolean v = t.getEnabled();
            if (v != null) {
                backendDisabled = !v.booleanValue();
            }
            return v;
        } catch (Throwable th) {
            lastError = th.getClass().getSimpleName() + ": " + th.getMessage();
            return null;
        }
    }

    /**
     * 读取后端的**完整配置快照**（config.json 的 JSON 文本）。
     *
     * 这是"App 不持有配置"的读侧入口：App 的界面从这里拿到后端存着的值。
     * 读不到返回 null —— 调用方必须按"读不到"处理（回落本地偏好），
     * **绝不能**用 App 自己的旧值冒充后端的值。
     *
     * 带一个短 TTL 缓存：一次界面刷新会连读几十个偏好项，
     * 每项都打一次 TCP 往返太浪费。TTL 200ms 足够覆盖一次同步读取，
     * 又不会让界面长时间显示旧值。
     */
    public static String dumpConfig() {
        long now = System.currentTimeMillis();
        if (configCache != null && (now - configCacheAt) < CONFIG_CACHE_TTL_MS) {
            return configCache;
        }
        JdspTransport t = currentTransport();
        if (t == null) return null;
        try {
            String json = t.dumpConfig();
            if (json == null || json.isEmpty()) {
                return null;
            }
            configCache = json;
            configCacheAt = now;
            return json;
        } catch (Throwable th) {
            lastError = th.getClass().getSimpleName() + ": " + th.getMessage();
            Log.w("JdspP5", "dumpConfig transport error: " + lastError);
            return null;
        }
    }

    /** 让下一次 dumpConfig() 重新拉取（App 写完参数后调用）。 */
    public static void invalidateConfigCache() {
        configCache = null;
        configCacheAt = 0L;
    }

    /**
     * 向 jdspd 查询一个参数（第二阶段：App 不再持有引擎句柄，读也走后端）。
     * 读不到返回 null（调用方按 AudioEffect.ERROR 处理）。
     */
    public static byte[] getParam(int sessionId, int paramId, int vsize) {
        JdspTransport t = currentTransport();
        if (t == null) return null;
        try {
            return t.getParam(sessionId, paramId, vsize);
        } catch (Throwable th) {
            lastError = th.getClass().getSimpleName() + ": " + th.getMessage();
            return null;
        }
    }

    /**
     * App 侧的会话结束：**不 detach 后端句柄**。
     *
     * jdspd 是所有会话的常驻控制者，句柄生命周期归它的 watcher 管；
     * App 在这里 detach 会把正在播放的会话音效全部摘掉。
     * 这里只清理 App 自己的"我以为 attach 过"记录。
     */
    public static void onRelease(int sessionId) {
        ATTACHED.remove(Integer.valueOf(sessionId));
    }

    /** 取当前可用传输；必要时现连一个（不阻塞 UI 太久）。 */
    private static JdspTransport currentTransport() {
        JdspTransport t = transport;
        if (t != null && t.isConnected()) return t;
        return ensureTransport();
    }

    /**
     * 后端是否可用（决定写路径走 jdspd 还是回退本地）。
     *
     * 注意：**只读不建连**。建连是有副作用的（一次 TCP 握手 + 一次 hello），
     * 不能在 setParameter 的热路径上同步做；连接由后台线程负责，
     * 真正开始走后端之前它会先 connect 好。
     */
    public static boolean isBackendAvailable() {
        JdspTransport t = transport;
        return t != null && t.isConnected() && !disabled;
    }

    /** 诊断用：当前传输与计数。 */
    public static String status() {
        JdspTransport t = transport;
        return "transport=" + (t == null ? "none" : t.name())
                + " connected=" + (t != null && t.isConnected())
                + " disabled=" + disabled
                + " queued=" + (COALESCE.size() + ORDERED_QUEUE.size())
                + " sent=" + sentParams
                + " broadcasted=" + broadcastedParams
                + " pendingGlobal=" + pendingBroadcast.size()
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
                // disabled = "传输被判定不可用"（连续失败过多）。
                // 注意：**不清空队列** —— 参数必须保留，等传输恢复后重放。
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
                // 后端被用户全局关掉：不要再反复尝试（会白烧 CPU 并刷日志）。
                // 把这批参数原样放回队列，等 enabled=true 后再发。
                if (backendDisabled) {
                    for (Pending p : batch) {
                        retainForRetry(p.session, p.param, p.value);
                    }
                    batch.clear();
                    Thread.sleep(200);
                    continue;
                }
                // 每秒刷新一次会话列表，把暂存的全局参数补给新出现的会话
                long now = System.currentTimeMillis();
                if (now - lastSessionRefresh > 1000L) {
                    lastSessionRefresh = now;
                    try { replayPendingBroadcast(t); } catch (Throwable ignored) { }
                    try { replayPendingEnabled(t); } catch (Throwable ignored) { }
                }
                for (Pending p : batch) {
                    if (disabled) break;
                    boolean ok = push(t, p);
                    if (!ok) {
                        // 失败：**回队**，不能丢（等后端恢复或用户开回来后重放）
                        retainForRetry(p.session, p.param, p.value);
                        break;
                    }
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

    /**
     * 下发一个参数（**在后台线程**执行）。
     *
     * 关键点：App 的 legacy 模式只持有"全局实例"（AudioEffect 的 sessionId = 0），
     * 而 jdspd 只接受真实 AudioFlinger session（>0）。因此 session<=0 的写入
     * 会被**广播**到 jdspd 当前所有已挂载的会话——这正是"一份配置作用于所有播放会话"
     * 的目标语义。
     *
     * 返回 false = 失败（调用方负责回队）。
     */
    private static boolean push(JdspTransport t, Pending p) {
        try {
            if (p.session > 0) {
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
                if (rc == ERR_DISABLED) {
                    // 后端被用户关掉了：**不是故障**，不计失败次数、不重选传输。
                    // 参数由调用方回队，等 enabled=true 后重放。
                    backendDisabled = true;
                    return false;
                }
                // 真故障（引擎拒绝 -1/-4、无句柄 -1001 等）：计失败，必要时重选传输
                consecutiveFailures++;
                if (consecutiveFailures >= 5) {
                    Log.w(TAG, "transport failing, re-select: " + lastError);
                    dropTransport();
                }
                return false;
            }

            // session <= 0：广播到所有已挂载会话
            int[] sessions = broadcastTargets(t);
            if (sessions == null || sessions.length == 0) {
                // 还没有任何播放会话：参数暂存，等有会话时再重放
                pendingBroadcast.put(Long.valueOf(p.key()), p);
                return true;
            }
            int ok = 0;
            for (int s : sessions) {
                if (ATTACHED.add(s)) {
                    t.attach(s);
                }
                int rc = t.setParam(s, p.param, p.value);
                if (rc >= 0) ok++;
                else lastError = "broadcast setParam rc=" + rc + " session=" + s;
            }
            sentParams += ok;
            if (ok > 0) {
                consecutiveFailures = 0;
                broadcastedParams += ok;
                return true;
            }
            consecutiveFailures++;
            return false;
        } catch (Throwable th) {
            lastError = th.getClass().getSimpleName() + ": " + th.getMessage();
            consecutiveFailures++;
            return false;
        }
    }

    /** 取 jdspd 已挂载的会话列表；失败时返回 null（调用方按"暂存"处理）。 */
    private static int[] broadcastTargets(JdspTransport t) {
        int[] sessions = t.listAttachedSessions();
        if (sessions != null) return sessions;
        return null;
    }

    /** 后端恢复后重放待定的全局开关。 */
    private static void replayPendingEnabled(JdspTransport t) {
        Boolean want = pendingEnabled;
        if (want == null) return;
        try {
            if (t.setEnabled(want.booleanValue()) >= 0) {
                pendingEnabled = null;
                Log.i(TAG, "replayed pending global enable=" + want);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 有新会话出现时，把暂存的全局参数重放给它们。 */
    private static void replayPendingBroadcast(JdspTransport t) {
        if (pendingBroadcast.isEmpty()) return;
        int[] sessions = broadcastTargets(t);
        if (sessions == null || sessions.length == 0) return;
        java.util.List<Pending> list = new java.util.ArrayList<Pending>(pendingBroadcast.values());
        pendingBroadcast.clear();
        for (Pending p : list) {
            for (int s : sessions) {
                if (ATTACHED.add(s)) t.attach(s);
                t.setParam(s, p.param, p.value);
            }
            sentParams += sessions.length;
        }
        Log.i(TAG, "replayed " + list.size() + " global param(s) to " + sessions.length + " session(s)");
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
            if (port <= 0 || token.isEmpty()) {
                Log.w("JdspP5", "tcp endpoint missing in " + dir + ": port=" + port
                        + " tokenLen=" + token.length());
                return null;
            }
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
