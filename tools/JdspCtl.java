import android.media.audiofx.AudioEffect;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * Command line front end for the JamesDSP effect, so settings can be pushed
 * without opening the app.
 *
 * Usage: JdspCtl <sessionId> <paramId> <float> [float ...]
 * Example: JdspCtl 0 1500 -0.1 60 3.0
 *
 * The four argument AudioEffect constructor is a hidden API; it is looked up
 * reflectively because app_process has no target SDK, so non-SDK access is not
 * restricted here.
 */
public class JdspCtl {
    static final UUID TYPE   = UUID.fromString("f98765f4-c321-5de6-9a45-123459495ab2");
    static final UUID ENGINE = UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2");

    static byte[] le32(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: JdspCtl <sessionId> <paramId> <float> [float ...]");
            return;
        }
        int session = Integer.parseInt(args[0]);
        if (args.length == 2 && (args[1].equals("enable") || args[1].equals("disable"))) {
            boolean on = args[1].equals("enable");
            Constructor<AudioEffect> ctor0 = AudioEffect.class
                    .getConstructor(UUID.class, UUID.class, int.class, int.class);
            ctor0.setAccessible(true);
            AudioEffect fx0 = ctor0.newInstance(TYPE, ENGINE, 0, session);
            java.lang.reflect.Method se = AudioEffect.class.getMethod("setEnabled", boolean.class);
            int rc0 = (Integer) se.invoke(fx0, on);
            System.out.println("effect id=" + fx0.getId() + " setEnabled(" + on + ") rc=" + rc0);
            fx0.release();
            return;
        }
        int param = Integer.parseInt(args[1]);
        int n = args.length - 2;
        ByteBuffer vb = ByteBuffer.allocate(4 * n).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) vb.putFloat(Float.parseFloat(args[2 + i]));
        byte[] value = vb.array();

        AudioEffect fx = null;
        try {
            Constructor<AudioEffect> ctor = AudioEffect.class
                    .getConstructor(UUID.class, UUID.class, int.class, int.class);
            ctor.setAccessible(true);
            fx = ctor.newInstance(TYPE, ENGINE, 0, session);
            java.lang.reflect.Method sp = AudioEffect.class
                    .getMethod("setParameter", byte[].class, byte[].class);
            int rc = (Integer) sp.invoke(fx, le32(param), value);
            System.out.println("effect id=" + fx.getId() + " session=" + session
                    + " enabled=" + fx.getEnabled());
            System.out.println("setParameter(" + param + ", " + n + " floats) rc=" + rc
                    + (rc == AudioEffect.SUCCESS ? " SUCCESS" : " FAILURE"));
        } catch (Throwable t) {
            System.out.println("ERROR " + t);
            Throwable c = t.getCause();
            if (c != null) System.out.println("  cause: " + c);
        } finally {
            if (fx != null) fx.release();
        }
    }
}