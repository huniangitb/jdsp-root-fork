package me.timschneeberger.rootlessjamesdsp.p5;

import android.media.audiofx.AudioEffect;

import java.lang.reflect.Method;

/**
 * P5 第二阶段的业务逻辑（纯 Java，可 javac 编译、可宿主单测）。
 *
 * JdspRemoteEffect.smali 是一个 **smali 子类**（extends AudioEffect），
 * 因为 AudioEffect 的 4 参构造器与 setParameter 都是隐藏 API，公开 android.jar 里没有
 * —— smali 能直接 invoke-direct 隐藏成员，Java 做不到（super() 必须首句调用）。
 *
 * ## 语义（用户要求：彻底废除 App 自身设置音效的接口）
 *
 * 1. **写路径只走后端。** 后端不可用时 **不写本地**，返回错误码，
 *    参数留在 JdspBridge 的重试队列里，等后端恢复后重放。
 *    → App 侧再也不可能"自己设置音效"。
 * 2. **读路径只读本地，且只用于健康查询。** App 读的是引擎内部量
 *    （20001 sampleRate / 20002 pid / 19998 paramCommitCount），
 *    这些量**不构成音效配置来源**，只是"引擎是否活着"的信号。
 *    v6.7.2 引擎不支持 GET_PARAM，jdspd 拿不到它们（实测 getParamEngineHits=0），
 *    所以本地读是唯一可靠来源；读不到返回 -1（绝不返回假的 0 触发健康检查误判）。
 * 3. **setEnabled 走后端的全局配置开关**，不再控制本地实例，
 *    也不再用引擎的 cmd=400（v6.7.2 引擎没有实现它）。
 * 4. **release 只释放本地句柄，不 detach 公共会话。** jdspd 是所有会话的常驻控制者，
 *    句柄生命周期归它的 watcher 管。
 */
public final class JdspRemoteOps {

    private JdspRemoteOps() {}

    /** 写失败（后端不可用）。调用方据此保留重试队列。 */
    public static final int ERR_NO_BACKEND = -1;

    /**
     * 写一个参数：**只走后端**。
     *
     * @return 0 = 已被后端受理；负数 = 后端不可用（参数已留在重试队列）。
     */
    public static int setParamBytes(AudioEffect local, int sessionId, int param, byte[] value) {
        if (value == null) {
            return ERR_NO_BACKEND;
        }
        return JdspBridge.submitParam(sessionId, param, value);
    }

    public static int setParamShort(AudioEffect local, int sessionId, int param, short value) {
        byte[] bytes = new byte[] { (byte) (value & 0xFF), (byte) ((value >>> 8) & 0xFF) };
        return JdspBridge.submitParam(sessionId, param, bytes);
    }

    public static int setParamInt(AudioEffect local, int sessionId, int param, int value) {
        byte[] bytes = new byte[] {
                (byte) (value & 0xFF),
                (byte) ((value >>> 8) & 0xFF),
                (byte) ((value >>> 16) & 0xFF),
                (byte) ((value >>> 24) & 0xFF)
        };
        return JdspBridge.submitParam(sessionId, param, bytes);
    }

    /** setParameter(byte[], byte[]) 形态：参数 id 在数组里。 */
    public static int setParamRaw(AudioEffect local, int sessionId, byte[] param, byte[] value) {
        if (param == null || param.length < 4) {
            return ERR_NO_BACKEND;
        }
        int id = (param[0] & 0xFF)
                | ((param[1] & 0xFF) << 8)
                | ((param[2] & 0xFF) << 16)
                | ((param[3] & 0xFF) << 24);
        return JdspBridge.submitParam(sessionId, id, value);
    }

    /**
     * 读一个参数：**只读本地**，且**只用于健康查询**，不构成音效配置来源。
     *
     * 本地读不到就返回 -1（调用方按"未知"处理），不会拿远程值冒充引擎内部量。
     */
    public static int getParamBytes(AudioEffect local, int sessionId, int param, byte[] value) {
        if (local == null || value == null) {
            return -1;
        }
        return getLocalBytes(local, param, value);
    }

    /**
     * setEnabled：**走后端的全局配置开关**，不控制本地实例。
     *
     * 后端不可用 / 被全局关掉（-1006）时，开关进"待重放"状态：
     * 后端恢复（或用户把 enabled 开回来）后自动补发，不丢用户意图。
     */
    public static int setEnabled(AudioEffect local, int sessionId, boolean enabled) {
        return JdspBridge.setEnabledNow(enabled);
    }

    /** release：只释放本地句柄，**不 detach** 后端公共会话。 */
    public static void release(AudioEffect local) {
        try {
            local.release();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 本地调用（隐藏 API 反射）

    private static final Method GET_BYTES = resolve("getParameter", int.class, byte[].class);

    private static Method resolve(String name, Class<?>... args) {
        try {
            Method m = AudioEffect.class.getMethod(name, args);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int getLocalBytes(AudioEffect local, int param, byte[] value) {
        if (GET_BYTES == null) return -1;
        try {
            Object rc = GET_BYTES.invoke(local, Integer.valueOf(param), value);
            return rc instanceof Integer ? ((Integer) rc).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }
}
