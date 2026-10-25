package android.media.audiofx;

/**
 * 宿主测试用的假 AudioEffect（公开 android.jar 里的是 stub，方法全是 throw）。
 * 记录所有调用，便于断言"走了本地"还是"走了后端"。
 */
public class AudioEffect {

    public static final int SUCCESS = 0;
    public static final int ERROR = -1;

    /** 记录调用序列：例如 "setParameter:1201"。 */
    public static final java.util.List<String> calls = new java.util.ArrayList<String>();

    /** getParameter 返回这个值；null 表示读失败。 */
    public static byte[] getParamReply = null;
    public static boolean enabled = true;
    public static boolean released = false;

    public AudioEffect() {}

    public int setParameter(int param, byte[] value) {
        calls.add("setParameter:" + param);
        return SUCCESS;
    }

    public int setParameter(int param, short value) {
        calls.add("setParameter:" + param);
        return SUCCESS;
    }

    public int setParameter(int param, int value) {
        calls.add("setParameter:" + param);
        return SUCCESS;
    }

    public int setParameter(byte[] param, byte[] value) {
        calls.add("setParameter:raw");
        return SUCCESS;
    }

    public int getParameter(int param, byte[] value) {
        calls.add("getParameter:" + param);
        if (getParamReply == null) return ERROR;
        System.arraycopy(getParamReply, 0, value, 0, Math.min(getParamReply.length, value.length));
        return getParamReply.length;
    }

    public int setEnabled(boolean value) {
        calls.add("setEnabled:" + value);
        enabled = value;
        return SUCCESS;
    }

    public boolean getEnabled() {
        return enabled;
    }

    public void release() {
        calls.add("release");
        released = true;
    }
}
