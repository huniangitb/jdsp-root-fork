package host;

import android.media.audiofx.AudioEffect;

import me.timschneeberger.rootlessjamesdsp.p5.JdspBridge;
import me.timschneeberger.rootlessjamesdsp.p5.JdspRemoteOps;

/**
 * 宿主侧单元测试：JdspRemoteOps 的策略分支（不需要设备、不需要 jdspd）。
 *
 * 覆盖用户要求"彻底废除 App 自身设置音效的接口"：
 *   1. 后端不可用 -> **不写本地**，返回错误，参数进重试队列；
 *   2. 后端可用 -> 只走后端，本地一次都不写；
 *   3. 读路径只读本地（健康查询），读不到返回 -1；
 *   4. setEnabled 走后端全局开关，不控制本地实例；
 *   5. release 只释放本地，不 detach 公共会话。
 */
public final class OpsHostTest {

    private static int failures = 0;
    private static int total = 0;

    public static void main(String[] args) {
        // ---- 1. 后端不可用：不写本地 + 进重试队列 ----
        reset();
        JdspBridge.backendAvailable = false;
        AudioEffect local = new AudioEffect();
        int rc = JdspRemoteOps.setParamBytes(local, 7, 1201, new byte[] { 1, 0 });
        check("offline: setParamBytes accepted (queued, never blocks UI)", rc == 0);
        check("offline: LOCAL NOT written (App cannot set audio itself)",
                !AudioEffect.calls.contains("setParameter:1201"));
        check("offline: param retained for retry", JdspBridge.retryQueue.contains("retry:7:1201"));
        check("offline: backend not called", JdspBridge.backendCalls.isEmpty());
        check("offline: write was submitted (queued)", JdspBridge.submitCalls.contains("submit:7:1201"));

        // ---- 2. 后端可用：只走后端，本地一次都不写 ----
        reset();
        JdspBridge.backendAvailable = true;
        local = new AudioEffect();
        rc = JdspRemoteOps.setParamBytes(local, 7, 1201, new byte[] { 1, 0 });
        check("online: setParamBytes -> 0", rc == 0);
        check("online: backend got the param", JdspBridge.backendCalls.contains("bytes:7:1201"));
        check("online: LOCAL NOT written", !AudioEffect.calls.contains("setParameter:1201"));
        check("online: nothing retained", JdspBridge.retryQueue.isEmpty());

        // 三种重载同样只走后端
        reset();
        JdspBridge.backendAvailable = true;
        local = new AudioEffect();
        JdspRemoteOps.setParamShort(local, 7, 1202, (short) 1);
        JdspRemoteOps.setParamInt(local, 7, 1203, 3);
        JdspRemoteOps.setParamRaw(local, 7, new byte[] { (byte) 0xD0, 0x04, 0, 0 }, new byte[] { 1 });
        check("online: short -> backend", JdspBridge.backendCalls.contains("bytes:7:1202"));
        check("online: int -> backend", JdspBridge.backendCalls.contains("bytes:7:1203"));
        // 0xD0,0x04 小端 = 0x04D0 = 1232
        check("online: raw -> backend", JdspBridge.backendCalls.contains("bytes:7:1232"));
        check("online: local untouched for all overloads", AudioEffect.calls.isEmpty());

        // ---- 3. 读路径：只读本地，读不到返回 -1（不冒充远程值）----
        reset();
        JdspBridge.backendAvailable = true;
        JdspBridge.remoteValue = new byte[] { 0x01, 0x02, 0x03, 0x04 };
        AudioEffect.getParamReply = new byte[] { (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD };
        local = new AudioEffect();
        byte[] buf = new byte[4];
        int grc = JdspRemoteOps.getParamBytes(local, 7, 20001, buf);
        check("read: returns local length", grc == 4);
        check("read: value comes from LOCAL", (buf[0] & 0xFF) == 0xAA);
        check("read: backend NOT used for reads", !JdspBridge.backendCalls.contains("get:7:20001"));

        reset();
        JdspBridge.backendAvailable = true;
        JdspBridge.remoteValue = new byte[] { 0x01, 0x02, 0x03, 0x04 };
        AudioEffect.getParamReply = null;
        local = new AudioEffect();
        buf = new byte[4];
        grc = JdspRemoteOps.getParamBytes(local, 7, 20001, buf);
        check("read: local fails -> -1 (never a fake 0, never remote impersonation)", grc == -1);

        // ---- 4. setEnabled：走后端全局开关，不碰本地实例 ----
        reset();
        JdspBridge.backendAvailable = true;
        local = new AudioEffect();
        rc = JdspRemoteOps.setEnabled(local, 7, false);
        check("setEnabled: backend called", JdspBridge.backendCalls.contains("setEnabled:false"));
        check("setEnabled: local instance NOT touched", !AudioEffect.calls.contains("setEnabled:false"));

        reset();
        JdspBridge.backendAvailable = false;
        local = new AudioEffect();
        rc = JdspRemoteOps.setEnabled(local, 7, true);
        check("setEnabled offline: error + retained",
                rc < 0 && JdspBridge.retryQueue.contains("retryEnabled:true"));

        // ---- 5. release：只释放本地，不 detach ----
        reset();
        JdspBridge.backendAvailable = true;
        local = new AudioEffect();
        JdspRemoteOps.release(local);
        check("release: local released", AudioEffect.released);
        check("release: no backend detach", JdspBridge.backendCalls.isEmpty());


        // ---- 6. 后端被全局关掉（-1006）：不是故障，参数要留在重试队列 ----
        reset();
        JdspBridge.backendAvailable = true;
        JdspBridge.setParamRejectCode = -1006;
        local = new AudioEffect();
        rc = JdspRemoteOps.setParamBytes(local, 7, 1201, new byte[] { 1, 0 });
        // 异步提交：返回 0（已入队），实际下发在后台线程，-1006 在那时才发生
        check("disabled(-1006): setParamBytes accepted (queued)", rc == 0);
        check("disabled(-1006): param retained for later replay",
                JdspBridge.retryQueue.contains("retry:7:1201"));
        check("disabled(-1006): LOCAL NOT written", !AudioEffect.calls.contains("setParameter:1201"));

        // ---- 7. setEnabled 在后端被关掉时：仍应成功（它就是用来开回来的）----
        reset();
        JdspBridge.backendAvailable = true;
        JdspBridge.backendGloballyDisabled = true;
        local = new AudioEffect();
        rc = JdspRemoteOps.setEnabled(local, 7, true);
        check("disabled: setEnabled still succeeds (EnableErrors guarantee)", rc == 0);
        check("disabled: enable reached the backend",
                JdspBridge.backendCalls.contains("setEnabled:true"));
        check("disabled: local instance NOT touched", !AudioEffect.calls.contains("setEnabled:true"));

        // ---- 8. getEnabled 走通（查询不参与写路径）----
        reset();
        JdspBridge.backendAvailable = true;
        Boolean en = JdspBridge.getEnabled();
        check("getEnabled: returns TRUE when backend on", Boolean.TRUE.equals(en));
        reset();
        JdspBridge.backendAvailable = true;
        JdspBridge.backendGloballyDisabled = true;
        en = JdspBridge.getEnabled();
        check("getEnabled: returns FALSE when backend globally off", Boolean.FALSE.equals(en));

        System.out.println(failures == 0 ? "ALL OK (" + total + " checks)" : failures + " FAILURE(S) of " + total);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void reset() {
        AudioEffect.calls.clear();
        AudioEffect.getParamReply = null;
        AudioEffect.enabled = true;
        AudioEffect.released = false;
        JdspBridge.backendCalls.clear();
        JdspBridge.retryQueue.clear();
        JdspBridge.remoteValue = null;
        JdspBridge.backendAccepts = true;
        JdspBridge.backendGloballyDisabled = false;
        JdspBridge.setParamRejectCode = 0;
    }

    private static void check(String name, boolean ok) {
        total++;
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }
}
