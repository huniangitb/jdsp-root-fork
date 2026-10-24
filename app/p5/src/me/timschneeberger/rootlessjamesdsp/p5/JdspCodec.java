package me.timschneeberger.rootlessjamesdsp.p5;

/** 小工具：hex、字节序、short/int 到引擎参数字节的转换（与 AudioEffect 的行为保持一致）。 */
public final class JdspCodec {

    private JdspCodec() {}

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String hex(byte[] data) {
        if (data == null) return "";
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    public static byte[] unhex(String s) {
        if (s == null || s.isEmpty()) return new byte[0];
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) ((digit(s.charAt(i * 2)) << 4) | digit(s.charAt(i * 2 + 1)));
        }
        return out;
    }

    private static int digit(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return 0;
    }

    /** 与 AudioEffect.setParameter(int, short) 一致的 2 字节小端。 */
    public static byte[] shortBytes(short value) {
        return new byte[] { (byte) (value & 0xFF), (byte) ((value >>> 8) & 0xFF) };
    }

    /** 与 AudioEffect.setParameter(int, int) 一致的 4 字节小端。 */
    public static byte[] intBytes(int value) {
        return new byte[] {
                (byte) (value & 0xFF),
                (byte) ((value >>> 8) & 0xFF),
                (byte) ((value >>> 16) & 0xFF),
                (byte) ((value >>> 24) & 0xFF)
        };
    }

    public static byte[] floatBytes(float[] values) {
        if (values == null) return new byte[0];
        byte[] out = new byte[values.length * 4];
        for (int i = 0; i < values.length; i++) {
            int bits = Float.floatToIntBits(values[i]);
            out[i * 4] = (byte) (bits & 0xFF);
            out[i * 4 + 1] = (byte) ((bits >>> 8) & 0xFF);
            out[i * 4 + 2] = (byte) ((bits >>> 16) & 0xFF);
            out[i * 4 + 3] = (byte) ((bits >>> 24) & 0xFF);
        }
        return out;
    }
}
