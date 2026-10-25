package me.timschneeberger.rootlessjamesdsp.p5;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 后端配置快照：把 dumpConfig 的 JSON 解析成"参数 id -> 字节"。
 *
 * 纯 Java（无 Android 依赖），所以可以在宿主单测里跑。
 *
 * 后端 config.json 的形状（见 backend/src/com/jdsp/backend/ConfigStore.java）：
 * <pre>
 * {
 *   "version": 1,
 *   "mode": 0,
 *   "enabled": true,
 *   "globalParams":  { "1500": "cdccccbd0000704200004040", ... },
 *   "policies":     { "com.example": 0 },
 *   "perAppParams": { "com.example": { "1500": "..." } },
 *   "updatedAt": 1690000000
 * }
 * </pre>
 *
 * 优先级：**perAppParams > globalParams**。
 * 理由：后端写参数时用的是同一条 scope 规则（ConfigStore.scopeFor）：
 * 只有当某个 App 已经有 per-app 参数或显式 policy 时才写 per-app，
 * 否则写 global。App 读的时候必须用同一条规则，否则"写进去的"和"读出来的"
 * 会不一致（这正是 docs §8.3.3 记录过的那个坑）。
 */
public final class JdspConfigSnapshot {

    /** 参数 id -> 字节。 */
    private final Map<Integer, byte[]> params;
    /** 后端全局开关。 */
    private final boolean enabled;
    /** 后端模式：0=auto 1=backend。 */
    private final int mode;
    /** 解析是否成功。 */
    private final boolean valid;

    private JdspConfigSnapshot(Map<Integer, byte[]> params, boolean enabled, int mode, boolean valid) {
        this.params = params;
        this.enabled = enabled;
        this.mode = mode;
        this.valid = valid;
    }

    /**
     * 解析 dumpConfig 的 JSON 文本。
     *
     * @param json     后端 dumpConfig 的原始 JSON
     * @param pkg      本 App 的包名（用于 per-app 作用域），可为空
     * @return 解析失败时返回 null（调用方回落本地偏好）
     */
    public static JdspConfigSnapshot parse(String json, String pkg) {
        Object root = JdspJson.parse(json);
        if (root == null) return null;
        if (!(root instanceof Map)) return null;

        Map<Integer, byte[]> global = readParams(JdspJson.get(root, "globalParams"));
        Map<Integer, byte[]> perApp = null;
        if (pkg != null && pkg.length() > 0) {
            Object perRoot = JdspJson.get(root, "perAppParams");
            if (perRoot instanceof Map) {
                Object inner = JdspJson.get(perRoot, pkg);
                if (inner instanceof Map) {
                    perApp = readParams(inner);
                }
            }
        }

        Map<Integer, byte[]> merged = new HashMap<Integer, byte[]>();
        merged.putAll(global);
        if (perApp != null) {
            merged.putAll(perApp);
        }
        boolean enabled = JdspJson.getBoolean(root, "enabled", true);
        int mode = JdspJson.getInt(root, "mode", 0);
        return new JdspConfigSnapshot(merged, enabled, mode, true);
    }

    private static Map<Integer, byte[]> readParams(Object node) {
        Map<Integer, byte[]> out = new HashMap<Integer, byte[]>();
        if (!(node instanceof Map)) return out;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) node).entrySet()) {
            Object k = e.getKey();
            Object v = e.getValue();
            if (k == null || v == null) continue;
            int id;
            try {
                id = Integer.parseInt(String.valueOf(k).trim());
            } catch (Throwable t) {
                continue;
            }
            if (v instanceof String) {
                byte[] bytes = JdspCodec.unhex((String) v);
                if (bytes != null && bytes.length > 0) {
                    out.put(Integer.valueOf(id), bytes);
                }
            }
        }
        return out;
    }

    /** 参数 id -> 字节（不可修改）。 */
    public Map<Integer, byte[]> params() {
        return Collections.unmodifiableMap(params);
    }

    /** 取一个参数；没有返回 null。 */
    public byte[] param(int paramId) {
        return params.get(Integer.valueOf(paramId));
    }

    public boolean enabled() {
        return enabled;
    }

    public int mode() {
        return mode;
    }

    public boolean valid() {
        return valid;
    }

    public int size() {
        return params.size();
    }
}
