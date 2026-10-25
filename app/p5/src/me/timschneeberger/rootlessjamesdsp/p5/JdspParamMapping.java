package me.timschneeberger.rootlessjamesdsp.p5;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * App 偏好项 -> 后端引擎参数的映射表（纯 Java，无 Android 依赖，可宿主单测）。
 *
 * 为什么需要这张表：
 * 用户要求"App 不持有配置"——配置的真相在后端的 config.json（引擎参数字节），
 * 而 App 的界面读的是**偏好键**（"limiter_threshold" 这种）。
 * 两者不是同一个命名空间，要让界面读到后端的值，就必须有一张明确的对应表。
 *
 * 这张表只覆盖**能无损还原**的偏好项：
 *   - 布尔开关：引擎里就是 enable 参数（short 0/1），或 float 数组里的 0/1 标志；
 *   - 标量：引擎参数里的某一个 float / short。
 * **不覆盖**（必须留在 App 本地，见 docs/P5-APP-INTEGRATION.md §12 的完整清单）：
 *   - 序列化成字符串的频段列表（peq_bands / geq_nodes / eq_bands / compander_response）：
 *     后端存的是 float 数组，App 存的是自己的字符串格式，两边不是一一对应；
 *   - 文件路径类（convolver_file / ddc_file / liveprog_file / convolver_adv_imp 等）：
 *     后端根本没有"文件路径"这个概念。
 *   - App 侧才有的开关（loudness_auto_volume：它是"要不要跟随系统音量"的接收器开关，
 *     引擎里没有对应参数）。
 *
 * scale 的含义：**本地值 = 引擎值 * scale**。
 * App 的写路径里有几处先做了换算再发给引擎，读回来时必须还原，例如：
 *   - loudness_attenuation：App 发 attenuation/100，引擎存 1.0 → scale = 100
 *   - loudness_ls_slope：  App 发 slope/100，引擎存 0.52   → scale = 100
 *   - loudness_iso_q：     App 发 q/1000，引擎存 0.432      → scale = 1000
 *   - tube_drive：         App 发 drive*1000，引擎存 2000    → scale = 0.001
 */
public final class JdspParamMapping {

    /** 引擎参数里的 float。 */
    public static final int KIND_FLOAT = 0;
    /** 引擎参数里的 short。 */
    public static final int KIND_SHORT = 1;
    /** 引擎参数里的 int。 */
    public static final int KIND_INT = 2;
    /** 布尔：来自一个 short 参数（0 = false，非 0 = true）。 */
    public static final int KIND_BOOL_SHORT = 3;
    /** 布尔：来自 float 数组里的第 index 个元素（0 = false，非 0 = true）。 */
    public static final int KIND_BOOL_FLOAT = 4;

    /** 特殊换算：引擎把"有符号的开关"存成 -1/1（多段均衡的插值模式）。 */
    public static final int TRANSFORM_SIGNED_BOOL = 1;

    /** 一条映射。 */
    public static final class Entry {
        /** App 偏好所在命名空间（dsp_output_control / dsp_compander / ...）。 */
        public final String namespace;
        /** App 偏好键（"limiter_threshold" 这种，值域与 res/values/keys.xml 一致）。 */
        public final String key;
        /** 引擎参数 id。 */
        public final int paramId;
        public final int kind;
        /** kind=FLOAT / KIND_BOOL_FLOAT 时的下标；其余为 0。 */
        public final int index;
        /** 本地值 = 引擎值 * scale。 */
        public final double scale;
        /** 特殊换算（0 = 无）。 */
        public final int transform;

        Entry(String namespace, String key, int paramId, int kind, int index, double scale, int transform) {
            this.namespace = namespace;
            this.key = key;
            this.paramId = paramId;
            this.kind = kind;
            this.index = index;
            this.scale = scale;
            this.transform = transform;
        }
    }

    private JdspParamMapping() {}

    // App 命名空间（与 utils/Constants.kt 的 PREF_* 一致）。
    public static final String NS_OUTPUT = "dsp_output_control";
    public static final String NS_COMPANDER = "dsp_compander";
    public static final String NS_BASS = "dsp_bass";
    public static final String NS_EQ = "dsp_equalizer";
    public static final String NS_GEQ = "dsp_graphiceq";
    public static final String NS_PEQ = "dsp_parametriceq";
    public static final String NS_SQUIG = "dsp_squig";
    public static final String NS_REVERB = "dsp_reverb";
    public static final String NS_STEREOWIDE = "dsp_stereowide";
    public static final String NS_CROSSFEED = "dsp_crossfeed";
    public static final String NS_TUBE = "dsp_tube";
    public static final String NS_HARMONIC = "dsp_harmonic_expander";
    public static final String NS_SUBHARMONIC = "dsp_subharmonic_expander";
    public static final String NS_NOSR2R = "dsp_nosr2r";
    public static final String NS_DDC = "dsp_ddc";
    public static final String NS_LIVEPROG = "dsp_liveprog";
    public static final String NS_LOUDNESS = "dsp_loudness";
    public static final String NS_CONVOLVER = "dsp_convolver";

    private static final List<Entry> ENTRIES = new ArrayList<Entry>();
    private static final Map<String, Entry> INDEX = new HashMap<String, Entry>();

    private static void put(String ns, String key, int paramId, int kind, int index) {
        put(ns, key, paramId, kind, index, 1.0, 0);
    }

    private static void put(String ns, String key, int paramId, int kind, int index, double scale) {
        put(ns, key, paramId, kind, index, scale, 0);
    }

    private static void put(String ns, String key, int paramId, int kind, int index, double scale, int transform) {
        Entry e = new Entry(ns, key, paramId, kind, index, scale, transform);
        ENTRIES.add(e);
        INDEX.put(ns + "/" + key, e);
    }

    static {
        // ---- 输出控制：引擎 1500 = [threshold, release, postGain]（3 个 float）----
        put(NS_OUTPUT, "limiter_threshold", 1500, KIND_FLOAT, 0);
        put(NS_OUTPUT, "limiter_release", 1500, KIND_FLOAT, 1);
        put(NS_OUTPUT, "output_postgain", 1500, KIND_FLOAT, 2);

        // ---- 压缩器：115 = [timeConstant, granularity, tfTransforms, response...]----
        put(NS_COMPANDER, "compander_enable", 1200, KIND_BOOL_SHORT, 0);
        put(NS_COMPANDER, "compander_timeconstant", 115, KIND_FLOAT, 0);
        put(NS_COMPANDER, "compander_granularity", 115, KIND_FLOAT, 1);
        put(NS_COMPANDER, "compander_tftransforms", 115, KIND_FLOAT, 2);

        // ---- 低频增强：112 = maxGain(short)，1201 = enable ----
        put(NS_BASS, "bass_enable", 1201, KIND_BOOL_SHORT, 0);
        put(NS_BASS, "bass_max_gain", 112, KIND_SHORT, 0);

        // ---- 多段均衡：116 = [filterType, interpolation, bands...]，1202 = enable ----
        put(NS_EQ, "eq_enable", 1202, KIND_BOOL_SHORT, 0);
        put(NS_EQ, "eq_filter_type", 116, KIND_FLOAT, 0);
        // 引擎存的是 ±1（AudioEffectExtensions: interpolation==1 ? 1 : -1），本地是 0/1。
        put(NS_EQ, "eq_interpolation", 116, KIND_FLOAT, 1, 1.0, TRANSFORM_SIGNED_BOOL);

        // ---- 图示均衡：1210 = enable（频段列表是字符串，不在表里）----
        put(NS_GEQ, "geq_enable", 1210, KIND_BOOL_SHORT, 0);

        // ---- 参数均衡：1300 = 频段数组，1214 = enable（频段列表是字符串，不在表里）----
        put(NS_PEQ, "peq_enable", 1214, KIND_BOOL_SHORT, 0);

        // ---- Squig Live：与主 PEQ 合并成同一条级联，只有开关可分辨 ----
        put(NS_SQUIG, "squig_peq_enable", 1214, KIND_BOOL_SHORT, 0);

        // ---- 混响：128 = preset(short)，1203 = enable ----
        put(NS_REVERB, "reverb_enable", 1203, KIND_BOOL_SHORT, 0);
        put(NS_REVERB, "reverb_preset", 128, KIND_SHORT, 0);

        // ---- 立体声扩展：137 = level(short)，1204 = enable ----
        put(NS_STEREOWIDE, "stereowide_enable", 1204, KIND_BOOL_SHORT, 0);
        put(NS_STEREOWIDE, "stereowide_mode", 137, KIND_SHORT, 0);

        // ---- Crossfeed：188 = mode(short)，1208 = enable ----
        put(NS_CROSSFEED, "bs2b_crossfeed_enable", 1208, KIND_BOOL_SHORT, 0);
        put(NS_CROSSFEED, "bs2b_crossfeed_mode", 188, KIND_SHORT, 0);

        // ---- 电子管：150 = drive(short，×1000)，1206 = enable ----
        put(NS_TUBE, "tube_enable", 1206, KIND_BOOL_SHORT, 0);
        put(NS_TUBE, "tube_drive", 150, KIND_SHORT, 0, 0.001);

        // ---- 谐波扩展：1305 = [gain1..9, crossover, mix]，1216 = enable ----
        put(NS_HARMONIC, "harmonic_expander_enable", 1216, KIND_BOOL_SHORT, 0);
        for (int i = 0; i < 9; i++) {
            put(NS_HARMONIC, "harmonic_expander_gain_" + (i + 1), 1305, KIND_FLOAT, i);
        }
        put(NS_HARMONIC, "harmonic_expander_crossover", 1305, KIND_FLOAT, 9);
        put(NS_HARMONIC, "harmonic_expander_mix", 1305, KIND_FLOAT, 10);

        // ---- 次谐波扩展：1306 = 同上，1217 = enable ----
        put(NS_SUBHARMONIC, "subharmonic_expander_enable", 1217, KIND_BOOL_SHORT, 0);
        for (int i = 0; i < 9; i++) {
            put(NS_SUBHARMONIC, "subharmonic_expander_gain_" + (i + 1), 1306, KIND_FLOAT, i);
        }
        put(NS_SUBHARMONIC, "subharmonic_expander_crossover", 1306, KIND_FLOAT, 9);
        put(NS_SUBHARMONIC, "subharmonic_expander_mix", 1306, KIND_FLOAT, 10);

        // ---- NOS R2R：1307 = 11 个 float，1218 = enable ----
        put(NS_NOSR2R, "nosr2r_enable", 1218, KIND_BOOL_SHORT, 0);
        put(NS_NOSR2R, "nosr2r_target_rate", 1307, KIND_FLOAT, 0);
        put(NS_NOSR2R, "nosr2r_bit_depth", 1307, KIND_FLOAT, 1);
        // App 发 tolerance/100，引擎存 0.xx
        put(NS_NOSR2R, "nosr2r_tolerance", 1307, KIND_FLOAT, 2, 100.0);
        put(NS_NOSR2R, "nosr2r_deviation_growth", 1307, KIND_FLOAT, 3);
        put(NS_NOSR2R, "nosr2r_harmony", 1307, KIND_FLOAT, 4);
        put(NS_NOSR2R, "nosr2r_serial", 1307, KIND_FLOAT, 5);
        put(NS_NOSR2R, "nosr2r_jitter", 1307, KIND_FLOAT, 6);
        put(NS_NOSR2R, "nosr2r_add_harmonics", 1307, KIND_FLOAT, 7);
        put(NS_NOSR2R, "nosr2r_invert_phase", 1307, KIND_BOOL_FLOAT, 8);

        // ---- VDC：1212 = enable（内容是字符缓冲，不在表里）----
        put(NS_DDC, "ddc_enable", 1212, KIND_BOOL_SHORT, 0);

        // ---- Liveprog：1213 = enable（脚本是字符缓冲，不在表里）----
        put(NS_LIVEPROG, "liveprog_enable", 1213, KIND_BOOL_SHORT, 0);

        // ---- 响度：1301 = [sampleRate, refLevel, refOffset, attenuation, volume, mode]，
        //      1303 = 调音参数，1304 = 次声波，1215 = enable，1302 = 当前音量 ----
        put(NS_LOUDNESS, "loudness_enable", 1215, KIND_BOOL_SHORT, 0);
        put(NS_LOUDNESS, "loudness_reference_level", 1301, KIND_FLOAT, 1);
        put(NS_LOUDNESS, "loudness_reference_offset", 1301, KIND_FLOAT, 2);
        // App 发 attenuation/100
        put(NS_LOUDNESS, "loudness_attenuation", 1301, KIND_FLOAT, 3, 100.0);
        put(NS_LOUDNESS, "loudness_volume", 1301, KIND_FLOAT, 4);
        put(NS_LOUDNESS, "loudness_mode", 1301, KIND_FLOAT, 5);
        // 调音参数（1303）：lsSlope/lsRatio/hsSlope 发的是 /100，hsRatio/isoQ 发的是 /1000
        put(NS_LOUDNESS, "loudness_ls_freq", 1303, KIND_FLOAT, 0);
        put(NS_LOUDNESS, "loudness_ls_slope", 1303, KIND_FLOAT, 1, 100.0);
        put(NS_LOUDNESS, "loudness_ls_ratio", 1303, KIND_FLOAT, 2, 100.0);
        put(NS_LOUDNESS, "loudness_hs_freq", 1303, KIND_FLOAT, 3);
        put(NS_LOUDNESS, "loudness_hs_slope", 1303, KIND_FLOAT, 4, 100.0);
        put(NS_LOUDNESS, "loudness_hs_ratio", 1303, KIND_FLOAT, 5, 1000.0);
        put(NS_LOUDNESS, "loudness_iso_base_phon", 1303, KIND_FLOAT, 6);
        put(NS_LOUDNESS, "loudness_iso_q", 1303, KIND_FLOAT, 7, 1000.0);
        // 次声波（1304）：q 发的是 /1000
        put(NS_LOUDNESS, "loudness_subsonic_enable", 1304, KIND_BOOL_FLOAT, 0);
        put(NS_LOUDNESS, "loudness_subsonic_freq", 1304, KIND_FLOAT, 1);
        put(NS_LOUDNESS, "loudness_subsonic_order", 1304, KIND_FLOAT, 2);
        put(NS_LOUDNESS, "loudness_subsonic_q", 1304, KIND_FLOAT, 3, 1000.0);

        // ---- 卷积：1205 = enable（IR 与文件路径不在表里）----
        put(NS_CONVOLVER, "convolver_enable", 1205, KIND_BOOL_SHORT, 0);
    }

    /** 查一条映射；没有返回 null。 */
    public static Entry lookup(String namespace, String key) {
        if (namespace == null || key == null) return null;
        return INDEX.get(namespace + "/" + key);
    }

    /** 全部映射（诊断用）。 */
    public static List<Entry> all() {
        return ENTRIES;
    }

    /** 某个命名空间下的全部映射。 */
    public static List<Entry> forNamespace(String namespace) {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : ENTRIES) {
            if (namespace.equals(e.namespace)) out.add(e);
        }
        return out;
    }

    /**
     * 从引擎参数字节里解出一个偏好值（原始引擎尺度）。
     *
     * @return Float / Short / Integer / Boolean；无法解码返回 null（调用方回落本地）。
     */
    public static Object decode(Entry e, byte[] data) {
        if (e == null || data == null) return null;
        switch (e.kind) {
            case KIND_FLOAT:
                return JdspParamCodec.getFloat(data, e.index);
            case KIND_SHORT:
                return JdspParamCodec.getShort(data, e.index);
            case KIND_INT:
                return JdspParamCodec.getInt(data, e.index);
            case KIND_BOOL_SHORT: {
                Short v = JdspParamCodec.getShort(data, 0);
                return v == null ? null : Boolean.valueOf(v.shortValue() != 0);
            }
            case KIND_BOOL_FLOAT: {
                Float v = JdspParamCodec.getFloat(data, e.index);
                return v == null ? null : Boolean.valueOf(v.floatValue() != 0f);
            }
            default:
                return null;
        }
    }

    /**
     * 把一个引擎参数字节转成 App 偏好里对应类型的值（已按 scale 还原）。
     *
     * @param defaultValue 读不到时用的默认值（类型必须与 App 偏好一致）。
     */
    public static Object coerce(Entry e, byte[] data, Object defaultValue) {
        Object raw = decode(e, data);
        if (raw == null) return null;
        if (e.transform == TRANSFORM_SIGNED_BOOL) {
            // 引擎：+1 = 开，-1 = 关；本地：1 = 开，0 = 关
            Float f = (raw instanceof Float) ? (Float) raw : Float.valueOf(0f);
            raw = Boolean.valueOf(f.floatValue() >= 0f);
        } else if (e.scale != 1.0 && raw instanceof Number) {
            double scaled = ((Number) raw).doubleValue() * e.scale;
            if (defaultValue instanceof Float) {
                raw = Float.valueOf((float) scaled);
            } else if (defaultValue instanceof Integer) {
                raw = Integer.valueOf((int) Math.round(scaled));
            } else if (defaultValue instanceof Long) {
                raw = Long.valueOf(Math.round(scaled));
            } else {
                raw = Double.valueOf(scaled);
            }
        }
        return cast(raw, defaultValue);
    }

    /** 把原始值转成 defaultValue 的类型（转不了就返回 null）。 */
    @SuppressWarnings("unchecked")
    private static Object cast(Object raw, Object defaultValue) {
        if (defaultValue == null) return raw;
        if (defaultValue instanceof Boolean) {
            if (raw instanceof Boolean) return raw;
            if (raw instanceof Number) return Boolean.valueOf(((Number) raw).floatValue() != 0f);
            return null;
        }
        // 布尔 -> 数值/字符串：App 有些偏好是 Int/String（"0"/"1"）。
        if (raw instanceof Boolean && defaultValue instanceof Number) {
            int v = ((Boolean) raw).booleanValue() ? 1 : 0;
            if (defaultValue instanceof Integer) return Integer.valueOf(v);
            if (defaultValue instanceof Long) return Long.valueOf(v);
            if (defaultValue instanceof Float) return Float.valueOf(v);
            if (defaultValue instanceof Double) return Double.valueOf(v);
        }
        if (raw instanceof Boolean && defaultValue instanceof String) {
            return ((Boolean) raw).booleanValue() ? "1" : "0";
        }
        if (defaultValue instanceof Float) {
            if (raw instanceof Number) return Float.valueOf(((Number) raw).floatValue());
            return null;
        }
        if (defaultValue instanceof Integer) {
            if (raw instanceof Number) return Integer.valueOf(((Number) raw).intValue());
            return null;
        }
        if (defaultValue instanceof Long) {
            if (raw instanceof Number) return Long.valueOf(((Number) raw).longValue());
            return null;
        }
        if (defaultValue instanceof String) {
            if (raw instanceof String) return raw;
            return null;
        }
        return raw;
    }
}
