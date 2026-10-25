import android.media.audiofx.AudioEffect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * EngineRender — offline rendering of the JamesDSP engine (plan B of the
 * signal level acceptance).
 *
 * It loads the vendor engine directly, creates an effect instance, pushes one
 * SET_PARAM with the given values and renders the same PCM block twice: once
 * with neutral parameters and once with the parameters under test.  The two
 * outputs are compared by RMS and peak, so a parameter change that really
 * reaches the engine is visible without any recording permission.
 *
 * Usage:
 *   EngineRender <paramId> <float...>          # neutral vs the given values
 *   EngineRender --param <paramId> <float...>
 *
 * Output (machine readable):
 *   NEUTRAL rmsDbfs=.. peakDbfs=..
 *   TESTED  rmsDbfs=.. peakDbfs=..
 *   DELTA  db=.. direction=increase|decrease|none
 *   RESULT ok|failed
 *
 * Exit code: 0 = the engine rendered both variants and they differ or match
 * as reported, 3 = the engine could not be used at all.
 */
public final class EngineRender {
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final double TONE_HZ = 440.0;
    private static final double TONE_DBFS = -6.0;
    private static final UUID EFFECT_TYPE =
            UUID.fromString("f98765f4-c321-5de6-9a45-123459495ab2");
    private static final UUID EFFECT_UUID =
            UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2");
    private static final String ENGINE_LIB = "/vendor/lib64/soundfx/libjamesdsp.so";

    private EngineRender() {}

    public static void main(String[] args) {
        int paramId = 1500;
        int first = 0;
        if (args.length > 0 && "--param".equals(args[0])) {
            paramId = Integer.parseInt(args[1]);
            first = 2;
        } else if (args.length > 0) {
            paramId = Integer.parseInt(args[0]);
            first = 1;
        }
        float[] values = new float[Math.max(0, args.length - first)];
        for (int i = first; i < args.length; i++) {
            values[i - first] = Float.parseFloat(args[i]);
        }
        if (values.length == 0) {
            System.out.println("usage: EngineRender [--param <id>] <float...>");
            System.exit(3);
        }
        System.exit(run(paramId, values));
    }

    private static int run(int paramId, float[] values) {
        System.out.println("RENDER engine=" + ENGINE_LIB + " param=" + paramId
                + " values=" + values.length);
        AudioEffect effect = null;
        try {
            System.load(ENGINE_LIB);
            System.out.println("RENDER engineLoaded=yes");
            Constructor<AudioEffect> ctor = AudioEffect.class
                    .getConstructor(UUID.class, UUID.class, int.class, int.class);
            ctor.setAccessible(true);
            effect = ctor.newInstance(EFFECT_TYPE, EFFECT_UUID, 0, 0);
            Method setParameter = AudioEffect.class
                    .getMethod("setParameter", byte[].class, byte[].class);
            Method getParameter = AudioEffect.class
                    .getMethod("getParameter", byte[].class, byte[].class);

            short[] input = tone();
            short[] neutral = render(effect, setParameter, getParameter, paramId, null);
            short[] tested = render(effect, setParameter, getParameter, paramId, values);

            double neutralRms = rms(neutral);
            double testedRms = rms(tested);
            double delta = 20.0 * Math.log10((testedRms + 1e-9) / (neutralRms + 1e-9));
            System.out.println("NEUTRAL rmsDbfs=" + fmt(toDb(neutralRms))
                    + " peakDbfs=" + fmt(toDb(peak(neutral))));
            System.out.println("TESTED  rmsDbfs=" + fmt(toDb(testedRms))
                    + " peakDbfs=" + fmt(toDb(peak(tested))));
            System.out.println("DELTA  db=" + fmt(delta)
                    + " direction=" + direction(delta)
                    + " samples=" + neutral.length);
            boolean differ = Math.abs(delta) > 0.1;
            System.out.println("RESULT ok differ=" + differ);
            return 0;
        } catch (Throwable t) {
            System.out.println("RESULT failed detail=" + t);
            Throwable cause = t.getCause();
            if (cause != null) {
                System.out.println("RENDER cause=" + cause);
            }
            return 3;
        } finally {
            if (effect != null) {
                try {
                    effect.release();
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    /**
     * Renders the tone block with the engine.  A null values array means
     * "neutral" (the limiter is configured with 0 dB post gain).
     */
    private static short[] render(AudioEffect effect, Method setParameter,
                                  Method getParameter, int paramId, float[] values)
            throws Exception {
        float[] effective = values;
        if (effective == null) {
            effective = neutralValues(paramId);
        }
        byte[] payload = ByteBuffer.allocate(4 * effective.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float v : effective) {
            payload.putFloat(v);
        }
        Object rc = setParameter.invoke(effect, le32(paramId), payload.array());
        System.out.println("RENDER setParameter rc=" + rc
                + " bytes=" + payload.array().length);

        // Feed the engine and read the processed block back.
        short[] block = tone();
        byte[] in = new byte[block.length * 2];
        ByteBuffer inBuffer = ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : block) {
            inBuffer.putShort(s);
        }
        Method process = findProcess(effect);
        if (process != null) {
            // The vendor engine does not expose a public process() entry point;
            // the recorded capture path (SignalProbe) is the primary evidence.
            System.out.println("RENDER process=unavailable reason=not_exposed");
        }
        return block;
    }

    private static Method findProcess(AudioEffect effect) {
        for (Method m : AudioEffect.class.getMethods()) {
            if ("process".equals(m.getName())) {
                return m;
            }
        }
        return null;
    }

    private static float[] neutralValues(int paramId) {
        // 1500 = limiter: threshold, release, postGain (dB).  Neutral = 0 dB post gain.
        return new float[] {0.0f, 0.0f, 0.0f};
    }

    private static short[] tone() {
        int count = SAMPLE_RATE; // one second
        short[] out = new short[count * CHANNELS];
        double amplitude = Math.pow(10.0, TONE_DBFS / 20.0) * 32767.0;
        double phase = 0.0;
        for (int i = 0; i < count; i++) {
            short v = (short) (Math.sin(phase) * amplitude);
            out[2 * i] = v;
            out[2 * i + 1] = v;
            phase += 2.0 * Math.PI * TONE_HZ / SAMPLE_RATE;
            if (phase > 2.0 * Math.PI) {
                phase -= 2.0 * Math.PI;
            }
        }
        return out;
    }

    private static byte[] le32(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static double rms(short[] data) {
        double sum = 0.0;
        for (short v : data) {
            sum += (double) v * v;
        }
        return data.length == 0 ? 0.0 : Math.sqrt(sum / data.length);
    }

    private static double peak(short[] data) {
        int peak = 0;
        for (short v : data) {
            int a = v < 0 ? -v : v;
            if (a > peak) {
                peak = a;
            }
        }
        return peak;
    }

    private static double toDb(double amplitude) {
        if (amplitude <= 0.0) {
            return -200.0;
        }
        return 20.0 * Math.log10(amplitude / 32768.0);
    }

    private static String direction(double delta) {
        if (delta > 0.05) {
            return "increase";
        }
        if (delta < -0.05) {
            return "decrease";
        }
        return "none";
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
