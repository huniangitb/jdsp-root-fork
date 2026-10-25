import android.media.audiofx.AudioEffect;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * ProcessProbe — checks whether the framework AudioEffect exposes a hidden
 * process() entry point, which would allow rendering PCM through the real
 * engine without any recording.
 */
public final class ProcessProbe {
    private static final UUID TYPE =
            UUID.fromString("f98765f4-c321-5de6-9a45-123459495ab2");
    private static final UUID ENGINE =
            UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2");

    public static void main(String[] args) {
        System.out.println("--- declared methods of AudioEffect");
        for (Method m : AudioEffect.class.getDeclaredMethods()) {
            String name = m.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("process")) {
                StringBuilder sb = new StringBuilder();
                sb.append("METHOD ").append(m.toString());
                System.out.println(sb);
            }
        }
        AudioEffect effect = null;
        try {
            java.lang.reflect.Constructor<AudioEffect> ctor = AudioEffect.class
                    .getConstructor(UUID.class, UUID.class, int.class, int.class);
            ctor.setAccessible(true);
            effect = ctor.newInstance(TYPE, ENGINE, 0, 0);
            System.out.println("effect id=" + effect.getId() + " enabled=" + effect.getEnabled());
            Method process = null;
            for (Method m : AudioEffect.class.getDeclaredMethods()) {
                if ("process".equals(m.getName()) && m.getParameterTypes().length == 2
                        && m.getParameterTypes()[0] == float[].class) {
                    process = m;
                    break;
                }
            }
            if (process == null) {
                System.out.println("RESULT no-process-method");
                return;
            }
            process.setAccessible(true);
            int n = 4800;
            float[] in = new float[n];
            float[] out = new float[n];
            for (int i = 0; i < n; i++) {
                in[i] = (float) (0.5 * Math.sin(2 * Math.PI * 440.0 * i / 48000.0));
            }
            Object rc = process.invoke(effect, in, out);
            double sum = 0.0;
            for (float v : out) {
                sum += v * v;
            }
            double rms = Math.sqrt(sum / out.length);
            System.out.println("RESULT process rc=" + rc + " inRms=" + rms
                    + " firstOut=" + out[0]);
        } catch (Throwable t) {
            System.out.println("RESULT failed detail=" + t);
            Throwable c = t.getCause();
            if (c != null) {
                System.out.println("cause=" + c);
            }
        } finally {
            if (effect != null) {
                effect.release();
            }
        }
    }
}
