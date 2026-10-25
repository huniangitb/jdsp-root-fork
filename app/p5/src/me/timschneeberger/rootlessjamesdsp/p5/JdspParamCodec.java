package me.timschneeberger.rootlessjamesdsp.p5;

/**
 * 参数字节的编解码（纯 Java，无 Android 依赖，可在宿主上单测）。
 *
 * 存在的理由：App 的"读侧"要改成读后端 dumpConfig，就必然要把后端存的
 * **十六进制字节**还原成 App 界面认识的数值（float / short / int）。
 * 这一层是纯字节运算，没有任何 Android 依赖，所以可以放进宿主单测。
 *
 * 字节序与引擎一致：小端（little endian）。
 * （AudioEffectExtensions.setParameterFloatArray 用的是 ByteOrder.nativeOrder()，
 *  在 ARM/x86 上都是小端。）
 */
public final class JdspParamCodec {

    private JdspParamCodec() {}

    /** 十六进制字符串 -> 字节。非法字符按 0 处理（与 JdspCodec.unhex 同义）。 */
    public static byte[] fromHex(String hex) {
        return JdspCodec.unhex(hex);
    }

    /** 取第 index 个 float（小端）。越界返回 null。 */
    public static Float getFloat(byte[] data, int index) {
        if (data == null) return null;
        int off = index * 4;
        if (off < 0 || off + 4 > data.length) return null;
        int bits = (data[off] & 0xFF)
                | ((data[off + 1] & 0xFF) << 8)
                | ((data[off + 2] & 0xFF) << 16)
                | ((data[off + 3] & 0xFF) << 24);
        return Float.valueOf(Float.intBitsToFloat(bits));
    }

    /** 取第 index 个 short（小端）。越界返回 null。 */
    public static Short getShort(byte[] data, int index) {
        if (data == null) return null;
        int off = index * 2;
        if (off < 0 || off + 2 > data.length) return null;
        int v = (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8);
        return Short.valueOf((short) v);
    }

    /** 取第 index 个 int（小端）。越界返回 null。 */
    public static Integer getInt(byte[] data, int index) {
        if (data == null) return null;
        int off = index * 4;
        if (off < 0 || off + 4 > data.length) return null;
        int v = (data[off] & 0xFF)
                | ((data[off + 1] & 0xFF) << 8)
                | ((data[off + 2] & 0xFF) << 16)
                | ((data[off + 3] & 0xFF) << 24);
        return Integer.valueOf(v);
    }

    /** 参数里有多少个 float。 */
    public static int floatCount(byte[] data) {
        return data == null ? 0 : data.length / 4;
    }
}
