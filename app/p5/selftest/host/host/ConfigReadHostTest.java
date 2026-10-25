package host;

import me.timschneeberger.rootlessjamesdsp.p5.JdspCodec;
import me.timschneeberger.rootlessjamesdsp.p5.JdspConfigSnapshot;
import me.timschneeberger.rootlessjamesdsp.p5.JdspJson;
import me.timschneeberger.rootlessjamesdsp.p5.JdspParamCodec;
import me.timschneeberger.rootlessjamesdsp.p5.JdspParamMapping;

/**
 * 宿主侧单元测试："App 从后端读配置"这一层的纯逻辑。
 *
 * 覆盖用户要求"App 不持有配置"的读侧：
 *   1. dumpConfig 的 JSON 能被解析成"参数 id -> 字节"；
 *   2. per-app 作用域优先于 global（与后端写路径同一条规则）；
 *   3. 偏好键能通过映射表还原成 App 认识的数值（含换算）；
 *   4. 读不到时返回 null（**不返回假的 0**）。
 *
 * 不需要设备、不需要 jdspd、不需要 Android SDK。
 */
public final class ConfigReadHostTest {

    private static int failures = 0;
    private static int total = 0;

    /** 后端 dumpConfig 的样本（形状与 backend/src/com/jdsp/backend/ConfigStore.java 一致）。 */
    private static final String CONFIG_JSON =
            "{\n"
            + "  \"version\": 1,\n"
            + "  \"mode\": 0,\n"
            + "  \"modeName\": \"auto\",\n"
            + "  \"enabled\": true,\n"
            + "  \"globalParams\": {\n"
            + "    \"115\": \"7eb8513d0000404000000000\",\n"
            + "    \"112\": \"0a00\",\n"
            + "    \"1201\": \"0100\",\n"
            + "    \"128\": \"0200\",\n"
            + "    \"1500\": \"cdccccbd0000704200004040\"\n"
            + "  },\n"
            + "  \"policies\": {\n"
            + "    \"com.example.music\": 1\n"
            + "  },\n"
            + "  \"perAppParams\": {\n"
            + "    \"com.example.music\": {\n"
            + "      \"1500\": \"0000c0c00000704200000000\"\n"
            + "    }\n"
            + "  },\n"
            + "  \"updatedAt\": 1690000000\n"
            + "}";

    public static void main(String[] args) {
        // ---- 1. JSON 解析 ----
        Object root = JdspJson.parse(CONFIG_JSON);
        check("json: parses", root != null);
        check("json: enabled is true", JdspJson.getBoolean(root, "enabled", false));
        check("json: mode is 0", JdspJson.getInt(root, "mode", -1) == 0);

        // ---- 2. 快照解析（global）----
        JdspConfigSnapshot snap = JdspConfigSnapshot.parse(CONFIG_JSON, null);
        check("snapshot: parsed", snap != null && snap.valid());
        check("snapshot: 5 global params", snap != null && snap.size() == 5);
        check("snapshot: enabled", snap != null && snap.enabled());
        check("snapshot: mode auto", snap != null && snap.mode() == 0);

        // 1500 = [-0.1f, 60.0f, 3.0f]
        byte[] p1500 = snap == null ? null : snap.param(1500);
        check("snapshot: 1500 present", p1500 != null);
        Float th = JdspParamCodec.getFloat(p1500, 0);
        Float rel = JdspParamCodec.getFloat(p1500, 1);
        check("snapshot: 1500[0] == -0.1", th != null && Math.abs(th - (-0.1f)) < 1e-6);
        check("snapshot: 1500[1] == 60.0", rel != null && Math.abs(rel - 60.0f) < 1e-4);

        // ---- 3. per-app 作用域优先 ----
        JdspConfigSnapshot perApp = JdspConfigSnapshot.parse(CONFIG_JSON, "com.example.music");
        check("snapshot: per-app overrides global",
                perApp != null && perApp.param(1500) != null
                        && perApp.param(1500).length == 12
                        && JdspParamCodec.getFloat(perApp.param(1500), 0) != null
                        && Math.abs(JdspParamCodec.getFloat(perApp.param(1500), 0) - (-6.0f)) < 1e-6);
        // 其它包看不到 per-app 的值
        JdspConfigSnapshot other = JdspConfigSnapshot.parse(CONFIG_JSON, "com.other.app");
        check("snapshot: other pkg sees global value",
                other != null && other.param(1500) != null
                        && Math.abs(JdspParamCodec.getFloat(other.param(1500), 0) - (-0.1f)) < 1e-6);

        // ---- 4. 偏好映射还原 ----
        JdspParamMapping.Entry outThreshold =
                JdspParamMapping.lookup("dsp_output_control", "limiter_threshold");
        check("mapping: limiter_threshold found", outThreshold != null);
        check("mapping: limiter_threshold -> 1500[0]",
                outThreshold != null && outThreshold.paramId == 1500 && outThreshold.index == 0);
        Object v = JdspParamMapping.coerce(outThreshold, p1500, Float.valueOf(0f));
        check("mapping: limiter_threshold == -0.1",
                v instanceof Float && Math.abs(((Float) v) - (-0.1f)) < 1e-6);

        JdspParamMapping.Entry outRelease =
                JdspParamMapping.lookup("dsp_output_control", "limiter_release");
        Object rv = JdspParamMapping.coerce(outRelease, p1500, Float.valueOf(0f));
        check("mapping: limiter_release == 60.0",
                rv instanceof Float && Math.abs(((Float) rv) - 60.0f) < 1e-4);

        // bass_enable 来自 1201（short 1）
        JdspParamMapping.Entry bassEnable =
                JdspParamMapping.lookup("dsp_bass", "bass_enable");
        byte[] p1201 = snap == null ? null : snap.param(1201);
        Object bv = JdspParamMapping.coerce(bassEnable, p1201, Boolean.FALSE);
        check("mapping: bass_enable == true", Boolean.TRUE.equals(bv));

        // bass_max_gain 来自 112（short 10 -> 10）
        JdspParamMapping.Entry bassGain =
                JdspParamMapping.lookup("dsp_bass", "bass_max_gain");
        byte[] p112 = snap == null ? null : snap.param(112);
        Object bgv = JdspParamMapping.coerce(bassGain, p112, Integer.valueOf(0));
        check("mapping: bass_max_gain == 10", bgv instanceof Integer && ((Integer) bgv) == 10);

        // reverb_preset 来自 128（short 2）
        JdspParamMapping.Entry rp = JdspParamMapping.lookup("dsp_reverb", "reverb_preset");
        byte[] p128 = snap == null ? null : snap.param(128);
        Object rpv = JdspParamMapping.coerce(rp, p128, Integer.valueOf(0));
        check("mapping: reverb_preset == 2", rpv instanceof Integer && ((Integer) rpv) == 2);

        // ---- 5. 换算（scale）----
        // loudness_attenuation 是 1301 的第 3 个 float（index 3）。
        // 构造 6 个 float 的数组，把 index 3 设为 1.0f：
        // App 发 attenuation/100 -> 引擎存 1.0 -> 本地读回 100
        JdspParamMapping.Entry att =
                JdspParamMapping.lookup("dsp_loudness", "loudness_attenuation");
        check("mapping: loudness_attenuation -> 1301[3]",
                att != null && att.paramId == 1301 && att.index == 3);
        byte[] six = new byte[24];
        six[12] = 0; six[13] = 0; six[14] = (byte) 0x80; six[15] = (byte) 0x3f;  // 1.0f @ index 3
        Object attv = JdspParamMapping.coerce(att, six, Float.valueOf(0f));
        check("mapping: loudness_attenuation scale x100",
                attv instanceof Float && Math.abs(((Float) attv) - 100.0f) < 1e-3);

        // tube_drive: App 发 drive*1000 -> 引擎存 2000 (0x07D0) -> 本地 2.0
        JdspParamMapping.Entry tube =
                JdspParamMapping.lookup("dsp_tube", "tube_drive");
        byte[] twoThousand = new byte[] { (byte) 0xD0, 0x07 };  // 2000
        Object tv = JdspParamMapping.coerce(tube, twoThousand, Float.valueOf(0f));
        check("mapping: tube_drive scale x0.001",
                tv instanceof Float && Math.abs(((Float) tv) - 2.0f) < 1e-4);

        // eq_interpolation: 引擎存 +1/-1，本地 0/1（App 的偏好是字符串 "0"/"1"）
        JdspParamMapping.Entry interp =
                JdspParamMapping.lookup("dsp_equalizer", "eq_interpolation");
        check("mapping: eq_interpolation -> 116[1]",
                interp != null && interp.paramId == 116 && interp.index == 1);
        byte[] twoFloats = new byte[8];
        twoFloats[4] = 0; twoFloats[5] = 0; twoFloats[6] = (byte) 0x80; twoFloats[7] = (byte) 0x3f;  // 1.0f @ index 1
        Object iv = JdspParamMapping.coerce(interp, twoFloats, Integer.valueOf(0));
        check("mapping: eq_interpolation signed bool (+1 -> 1)",
                iv instanceof Integer && ((Integer) iv) == 1);
        Object ivs = JdspParamMapping.coerce(interp, twoFloats, "0");
        check("mapping: eq_interpolation signed bool (+1 -> \"1\")",
                ivs instanceof String && "1".equals(ivs));
        // -1 -> 0
        byte[] minusOne = new byte[8];
        minusOne[4] = 0; minusOne[5] = 0; minusOne[6] = (byte) 0x80; minusOne[7] = (byte) 0xBF;
        Object ivm = JdspParamMapping.coerce(interp, minusOne, Integer.valueOf(1));
        check("mapping: eq_interpolation signed bool (-1 -> 0)",
                ivm instanceof Integer && ((Integer) ivm) == 0);

        // ---- 6. 读不到时返回 null（不返回假的 0）----
        JdspParamMapping.Entry missing =
                JdspParamMapping.lookup("dsp_compander", "compander_enable");
        check("mapping: unknown param -> null", JdspParamMapping.coerce(missing, null, Boolean.FALSE) == null);
        check("mapping: unknown key -> null entry",
                JdspParamMapping.lookup("dsp_compander", "no_such_key") == null);

        // 引擎参数太短时也返回 null
        check("mapping: short data -> null",
                JdspParamMapping.coerce(outThreshold, new byte[] { 0, 0 }, Float.valueOf(0f)) == null);

        // ---- 7. 无效 JSON ----
        check("snapshot: invalid json -> null", JdspConfigSnapshot.parse("{not json", null) == null);
        check("snapshot: empty -> null", JdspConfigSnapshot.parse("", null) == null);

        // ---- 8. 频段列表类不在映射里（必须留在 App 本地）----
        check("mapping: peq_bands NOT mapped (stays local)",
                JdspParamMapping.lookup("dsp_parametriceq", "peq_bands") == null);
        check("mapping: geq_nodes NOT mapped (stays local)",
                JdspParamMapping.lookup("dsp_graphiceq", "geq_nodes") == null);
        check("mapping: convolver_file NOT mapped (stays local)",
                JdspParamMapping.lookup("dsp_convolver", "convolver_file") == null);

        System.out.println(failures == 0 ? "ALL OK (" + total + " checks)" : failures + " FAILURE(S) of " + total);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String name, boolean ok) {
        total++;
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }
}
