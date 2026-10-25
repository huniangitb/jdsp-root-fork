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

    /** 后端当前配置快照；读不到返回 null。 */
    public static JdspConfigSnapshot snapshot() {
        String json = JdspBridge.dumpConfig();
        if (json == null) return null;
        return JdspConfigSnapshot.parse(json, packageName);
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
