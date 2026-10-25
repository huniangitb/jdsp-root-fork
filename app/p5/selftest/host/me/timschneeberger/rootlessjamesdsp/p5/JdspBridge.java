package me.timschneeberger.rootlessjamesdsp.p5;

import java.util.List;

/**
 * 宿主测试用的假 JdspBridge：记录后端收到的写，并让测试控制"后端是否可用"。
 */
public final class JdspBridge {

    public static boolean backendAvailable = false;

    /** 后端收到的写。 */
    public static final List<String> backendCalls = new java.util.ArrayList<String>();

    /** App 提交（入队）的写。 */
    public static final List<String> submitCalls = new java.util.ArrayList<String>();

    /** 后端不可用时保留待重放的参数。 */
    public static final List<String> retryQueue = new java.util.ArrayList<String>();

    /** 后端是否接受写入（模拟服务端拒绝）。 */
    public static boolean backendAccepts = true;

    public static byte[] remoteValue = null;

    public static boolean isBackendAvailable() {
        return backendAvailable;
    }

    /** 模拟后台线程真正下发（submitParam 只入队）。 */
    public static int setParamNow(int sessionId, int paramId, byte[] value) {
        if (setParamRejectCode != 0) {
            return setParamRejectCode;
        }
        if (!backendAccepts) {
            return -2;
        }
        backendCalls.add("bytes:" + sessionId + ":" + paramId);
        return 0;
    }

    /**
     * 提交（入队）。宿主测试里没有真的后台线程，
     * 所以这里用"立刻下发"来模拟队列消费，并记录入队。
     */
    public static int submitParam(int sessionId, int paramId, byte[] value) {
        submitCalls.add("submit:" + sessionId + ":" + paramId);
        if (!backendAvailable) {
            retryQueue.add("retry:" + sessionId + ":" + paramId);
            return 0;   // 仍然"已受理"（意图已保留）
        }
        int rc = setParamNow(sessionId, paramId, value);
        if (rc < 0) {
            retryQueue.add("retry:" + sessionId + ":" + paramId);
        }
        return 0;
    }

    public static void retainForRetry(int sessionId, int paramId, byte[] value) {
        retryQueue.add("retry:" + sessionId + ":" + paramId);
    }

    /** 后端是否处于"全局关闭"（daemon-core 的 -1006 语义）。 */
    public static boolean backendGloballyDisabled = false;

    /** setParam 被拒时返回的码（0 = 接受）。 */
    public static int setParamRejectCode = 0;

    public static int setEnabledNow(boolean enabled) {
        if (!backendAvailable) {
            retryQueue.add("retryEnabled:" + enabled);
            return -1;
        }
        if (backendGloballyDisabled) {
            // 后端被全局关掉时，**启用类命令仍应成功**（它就是用来开回来的）。
            // 后端用 EnableErrors.forEnableCommand 保证这条路径不返回 -1006。
            backendCalls.add("setEnabled:" + enabled);
            backendGloballyDisabled = false;
            return 0;
        }
        backendCalls.add("setEnabled:" + enabled);
        return 0;
    }

    public static Boolean getEnabled() {
        if (!backendAvailable) return null;
        backendCalls.add("getEnabled");
        return Boolean.valueOf(!backendGloballyDisabled);
    }

    public static void onParamBytes(int sessionId, int paramId, byte[] value) {
        backendCalls.add("bytes:" + sessionId + ":" + paramId);
    }

    public static void onParamShort(int sessionId, int paramId, short value) {
        backendCalls.add("short:" + sessionId + ":" + paramId);
    }

    public static void onParamInt(int sessionId, int paramId, int value) {
        backendCalls.add("int:" + sessionId + ":" + paramId);
    }

    public static void onParamRaw(int sessionId, byte[] param, byte[] value) {
        backendCalls.add("raw:" + sessionId);
    }

    public static byte[] getParam(int sessionId, int paramId, int vsize) {
        backendCalls.add("get:" + sessionId + ":" + paramId);
        return remoteValue;
    }
}
