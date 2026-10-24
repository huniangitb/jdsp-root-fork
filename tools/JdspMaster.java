import android.media.audiofx.AudioEffect;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * Global on/off switch for the JamesDSP engine.
 *
 * Usage: JdspMaster <0|1> [sessionId]
 *
 * Sends SET_PARAM command 400 (int16 value) through the normal effect
 * parameter path, so the state module records it and the setting survives a
 * reboot.  EFFECT_CMD_ENABLE cannot do this: the framework re-sends it for
 * every new output session, so the app's off switch never sticks.
 */
public class JdspMaster {
    static final UUID TYPE   = UUID.fromString("f98765f4-c321-5de6-9a45-123459495ab2");
    static final UUID ENGINE = UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2");

    static byte[] le32(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: JdspMaster <0|1> [sessionId]");
            return;
        }
        short on = (short) (Integer.parseInt(args[0]) != 0 ? 1 : 0);
        int session = args.length > 1 ? Integer.parseInt(args[1]) : 0;

        Constructor<AudioEffect> ctor = AudioEffect.class
                .getConstructor(UUID.class, UUID.class, int.class, int.class);
        ctor.setAccessible(true);
        AudioEffect fx = null;
        try {
            fx = ctor.newInstance(TYPE, ENGINE, 0, session);
            // param bytes carry the command id, value bytes carry the int16 setting;
            // the framework assembles effect_param_t { psize, vsize, status, data }.
            byte[] value = new byte[] { (byte) (on & 0xFF), (byte) ((on >> 8) & 0xFF) };
            Method sp = AudioEffect.class.getMethod("setParameter", byte[].class, byte[].class);
            int rc = (Integer) sp.invoke(fx, le32(400), value);
            System.out.println("master=" + on + " session=" + session + " rc=" + rc);
        } catch (Throwable t) {
            System.out.println("ERROR " + t);
        } finally {
            if (fx != null) fx.release();
        }
    }
}
