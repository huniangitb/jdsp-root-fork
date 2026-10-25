package me.timschneeberger.rootlessjamesdsp.p5;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 一个 SharedPreferences 的**读侧代理**：读的值来自**后端**（jdspd 的 config.json），
 * 写仍然落到 App 自己的 SharedPreferences。
 *
 * 为什么这样做（用户要求："App 不持有配置；音效处理只接收后端更改"）：
 *
 *   App 的界面（androidx Preference 屏幕、参数编辑页）读的是 SharedPreferences。
 *   要让界面显示后端存着的值，唯一的公共接口就是 SharedPreferences，
 *   所以这里实现一个门面，把 getXxx() 转成"读后端 dumpConfig"，
 *   把 edit()/putXxx() 原样转给真的 SharedPreferences。
 *
 *   - **读**：优先后端（后端是配置的真相）。后端没有这一项 / 读不到后端时，
 *     回落到真的 SharedPreferences（保证 UI 还能用，且**不会**用本地旧值冒充后端值 ——
 *     因为后端有值时一定用后端的）。
 *   - **写**：仍然写本地 SharedPreferences。原因是 App 的写路径依赖
 *     "SharedPreferences 变更 -> PreferenceGroupFragment 的 listener ->
 *     ACTION_PREFERENCES_UPDATED -> engine.syncWithPreferences() -> 发给后端"。
 *     如果写不落地，这个链条就断了，参数就永远发不出去。
 *     **写出去的最终归宿仍然是后端**（引擎只发后端，见 JdspRemoteOps）。
 *
 * 覆盖范围由 JdspParamMapping 决定（只覆盖能无损还原的偏好项）。
 * 序列化成字符串的频段列表、文件路径类**不在映射里**，那些项读本地。
 *
 * 落点：androidx/preference/PreferenceManager.getSharedPreferences() 被改成返回本类
 * （见 app/p5/patch_app_p5.sh 的 P5_MODE=phase3 补丁）。
 */
public final class JdspBackendSharedPreferences implements SharedPreferences {

    private final SharedPreferences real;
    private final String namespace;
    /** 原始 readAll() 的结果（界面刷新用）。 */
    private final Map<String, ?> allDelegate;

    public JdspBackendSharedPreferences(SharedPreferences real, String namespace) {
        this.real = real;
        this.namespace = namespace;
        Map<String, ?> all;
        try {
            all = real.getAll();
        } catch (Throwable t) {
            all = new HashMap<String, Object>();
        }
        this.allDelegate = all == null ? new HashMap<String, Object>() : all;
    }

    /** 真实的 SharedPreferences（写、以及需要本地值的场合）。 */
    public SharedPreferences delegate() {
        return real;
    }

    /**
     * 静态工厂：给 smali 补丁用。
     *
     * <p>smali 侧只做一件事：把 PreferenceManager.getSharedPreferences() 拿到的
     * SharedPreferences 包一层。包的动作放在这里，smali 就不用写一堆 try/catch。
     *
     * @param real      真的 SharedPreferences
     * @param namespace 偏好命名空间
     * @param context   App 的 Context（用来取包名，做 per-app 作用域）
     * @return 包好的 SharedPreferences；出错时返回 real（不改变原行为）
     */
    public static SharedPreferences wrap(SharedPreferences real, String namespace, Context context) {
        if (real == null) return null;
        try {
            if (context != null) {
                JdspBackendPreferences.init(context);
            }
            android.util.Log.i("JdspP5", "wrap namespace=" + namespace);
            return new JdspBackendSharedPreferences(real, namespace);
        } catch (Throwable t) {
            // 任何异常都不能让 App 崩：退回原 SharedPreferences。
            android.util.Log.w("JdspP5", "wrap failed, falling back", t);
            return real;
        }
    }

    // ---------------------------------------------------------------- 读侧

    private Object backendValue(String key, Object def) {
        JdspParamMapping.Entry entry = JdspParamMapping.lookup(namespace, key);
        if (entry == null) {
            return null;
        }
        JdspConfigSnapshot snapshot = JdspBackendPreferences.snapshot();
        if (snapshot == null) {
            android.util.Log.w("JdspP5", "read " + namespace + "/" + key + ": backend unreachable, local");
            return null;
        }
        byte[] bytes = snapshot.param(entry.paramId);
        if (bytes == null) {
            android.util.Log.i("JdspP5", "read " + namespace + "/" + key
                    + ": param " + entry.paramId + " not in backend, local");
            return null;
        }
        Object v = JdspParamMapping.coerce(entry, bytes, def);
        android.util.Log.i("JdspP5", "read " + namespace + "/" + key
                + ": backend hit (param " + entry.paramId + ")");
        return v;
    }

    @Override
    public Map<String, ?> getAll() {
        Map<String, Object> out = new HashMap<String, Object>(allDelegate);
        JdspConfigSnapshot snapshot = JdspBackendPreferences.snapshot();
        if (snapshot == null) {
            return out;
        }
        for (JdspParamMapping.Entry e : JdspParamMapping.all()) {
            if (!namespace.equals(e.namespace)) continue;
            byte[] bytes = snapshot.param(e.paramId);
            if (bytes == null) continue;
            Object def = allDelegate.get(e.key);
            if (def == null) def = Boolean.FALSE;
            Object v = JdspParamMapping.coerce(e, bytes, def);
            if (v != null) out.put(e.key, v);
        }
        return out;
    }

    @Override
    public String getString(String key, String defValue) {
        Object v = backendValue(key, defValue);
        if (v instanceof String) return (String) v;
        return real.getString(key, defValue);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String key, Set<String> defValues) {
        // 集合类型不在映射表里：读本地。
        return real.getStringSet(key, defValues);
    }

    @Override
    public int getInt(String key, int defValue) {
        Object v = backendValue(key, Integer.valueOf(defValue));
        if (v instanceof Number) return ((Number) v).intValue();
        return real.getInt(key, defValue);
    }

    @Override
    public long getLong(String key, long defValue) {
        Object v = backendValue(key, Long.valueOf(defValue));
        if (v instanceof Number) return ((Number) v).longValue();
        return real.getLong(key, defValue);
    }

    @Override
    public float getFloat(String key, float defValue) {
        Object v = backendValue(key, Float.valueOf(defValue));
        if (v instanceof Number) return ((Number) v).floatValue();
        return real.getFloat(key, defValue);
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Object v = backendValue(key, Boolean.valueOf(defValue));
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        return real.getBoolean(key, defValue);
    }

    // ---------------------------------------------------------------- 写侧（原样转发）

    @Override
    public boolean contains(String key) {
        return real.contains(key);
    }

    @Override
    public Editor edit() {
        return real.edit();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        real.registerOnSharedPreferenceChangeListener(listener);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        real.unregisterOnSharedPreferenceChangeListener(listener);
    }
}
