package me.timschneeberger.rootlessjamesdsp.p5;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * App 侧的"读配置"门面：读**后端**的配置，而不是 App 自己的 SharedPreferences。
 *
 * 用户要求："App 不持有配置；音效处理只接收后端更改"。
 * 这一类是 P5 读侧的核心：
 *   - App 的界面（Preference 屏幕、参数编辑页）要显示后端存着的值；
 *   - 读不到后端时**回落**到 App 本地偏好（保证 UI 还能用），
 *     但回落必须是**显式的、可观测的**（记日志 + 计数），
 *     绝不能用本地旧值冒充后端的值。
 *
 * 覆盖范围由 JdspParamMapping 决定（只覆盖能无损还原的偏好项）。
 * 序列化成字符串的频段列表、文件路径类**不在映射里**，那些项仍然读本地
 * （见 docs/P5-APP-INTEGRATION.md §12 的完整清单）。
 *
 * 用法（替换 PreferenceCache.uncachedGet 的调用点）：
 * <pre>
 *   Object v = JdspBackendPreferences.get(context, "dsp_output_control", "limiter_threshold", -0.1f);
 * </pre>
 */
public final class JdspBackendPreferences {

    private JdspBackendPreferences() {}

    /** 读到的值来自后端的次数。 */
    private static volatile long hitsFromBackend;
    /** 回落到 App 本地偏好的次数。 */
    private static volatile long missesToLocal;
    /** 后端读失败（连接不可用）的次数。 */
    private static volatile long backendUnreachable;

    /** 本 App 的包名（用于 per-app 作用域）。 */
    private static volatile String packageName;

    /**
     * 内存里的后端配置快照。UI 线程只读它，网络刷新永远在后台线程——
     * 主线程碰 socket 会直接 NetworkOnMainThreadException（loopback 也不例外，
     * 这是系统强制的，不是 StrictMode 选项），所以 snapshot() 绝不能同步建连。
     */
    private static volatile JdspConfigSnapshot cached;
    private static volatile boolean refreshRunning;
    private static volatile long lastRefreshAt;
    private static java.util.concurrent.ExecutorService refreshExecutor;

    /** 登记包名（App 启动时调一次）。 */
    public static void init(Context context) {
        if (context == null) return;
        try {
            packageName = context.getPackageName();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 读一个偏好值。
     *
     * @param context   用于读本地偏好（回落用）
     * @param namespace 偏好命名空间（dsp_output_control 等）
     * @param key       偏好键（limiter_threshold 等）
     * @param def       默认值（同时决定返回类型）
     * @return 后端的值；后端没有这一项 / 读不到后端时返回 def 或本地偏好里的值。
     */
    public static Object get(Context context, String namespace, String key, Object def) {
        JdspParamMapping.Entry entry = JdspParamMapping.lookup(namespace, key);
        if (entry == null) {
            // 不在映射表里（频段字符串、文件路径等）：只能读本地。
            missesToLocal++;
            return local(context, namespace, key, def);
        }
        JdspConfigSnapshot snapshot = snapshot();
        if (snapshot == null) {
            backendUnreachable++;
            missesToLocal++;
            return local(context, namespace, key, def);
        }
        byte[] bytes = snapshot.param(entry.paramId);
        if (bytes == null) {
            // 后端没有这个参数（例如用户从没开过这个功能）。
            // 这不算错误：后端就是权威，回落到本地（首次使用的默认值）。
            missesToLocal++;
            return local(context, namespace, key, def);
        }
        Object value = JdspParamMapping.coerce(entry, bytes, def);
        if (value == null) {
            // 解不出来：回落本地，但记一次 miss。
            missesToLocal++;
            return local(context, namespace, key, def);
        }
        hitsFromBackend++;
        return value;
    }

    /**
     * 后端当前配置快照；没有可用快照返回 null（调用方回落本地偏好）。
     *
     * <p>只读内存缓存，同时安排一次后台刷新——网络往返在后台线程做。
     * 这意味着冷启动后的第一次读可能还没有快照（显示本地值），
     * 后台拉到之后下一次界面刷新就会显示后端的值。
     */
    public static JdspConfigSnapshot snapshot() {
        scheduleBackgroundRefresh();
        return cached;
    }

    /** 丢掉内存快照并立刻后台重拉（写完参数后调用，保证 UI 不显示旧的后端值）。 */
    public static void invalidateSnapshot() {
        cached = null;
        scheduleBackgroundRefresh();
    }

    private static void scheduleBackgroundRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshAt < 250L) return;
        lastRefreshAt = now;
        if (refreshRunning) return;
        synchronized (JdspBackendPreferences.class) {
            if (refreshRunning) return;
            refreshRunning = true;
            if (refreshExecutor == null) {
                refreshExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(
                        r -> {
                            Thread t = new Thread(r, "jdsp-p5-config-refresh");
                            t.setDaemon(true);
                            return t;
                        });
            }
        }
        final String pkg = packageName;
        refreshExecutor.execute(() -> {
            try {
                String json = JdspBridge.dumpConfig();
                JdspConfigSnapshot fresh =
                        json == null ? null : JdspConfigSnapshot.parse(json, pkg);
                if (fresh != null) {
                    cached = fresh;
                }
            } catch (Throwable ignored) {
                // 刷新失败：保留旧快照（比回落本地更接近后端真相）。
            } finally {
                refreshRunning = false;
            }
        });
    }

    /** 读 App 本地偏好（回落路径）。 */
    private static Object local(Context context, String namespace, String key, Object def) {
        if (context == null) return def;
        try {
            SharedPreferences prefs =
                    context.getSharedPreferences(namespace, Context.MODE_MULTI_PROCESS);
            if (prefs == null) return def;
            if (def instanceof Boolean) {
                return Boolean.valueOf(prefs.getBoolean(key, ((Boolean) def).booleanValue()));
            }
            if (def instanceof String) {
                String v = prefs.getString(key, (String) def);
                return v == null ? def : v;
            }
            if (def instanceof Integer) {
                return Integer.valueOf(prefs.getInt(key, ((Integer) def).intValue()));
            }
            if (def instanceof Long) {
                return Long.valueOf(prefs.getLong(key, ((Long) def).longValue()));
            }
            if (def instanceof Float) {
                return Float.valueOf(prefs.getFloat(key, ((Float) def).floatValue()));
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    /** 诊断计数。 */
    public static String stats() {
        return "backendHits=" + hitsFromBackend
                + " localFallbacks=" + missesToLocal
                + " backendUnreachable=" + backendUnreachable;
    }
}
