package com.jdsp.offline;

/**
 * OfflineRender — renders a fixed PCM block through the vendor JamesDSP
 * engine, without any recording or playback.
 *
 * The native part (tools/native/offline_render.cpp) loads a copy of
 * /vendor/lib64/soundfx/libjamesdsp.so and drives the exported AOSP effect
 * interface, so the measurement is free of the environment noise that makes a
 * microphone capture unusable.
 *
 * Usage:
 *   OfflineRender <paramId> <float...>
 *
 * The same block is rendered twice: once with neutral parameters and once with
 * the parameters given on the command line.  Output:
 *   NEUTRAL rms=<..> peak=<..>
 *   TESTED  rms=<..> peak=<..>
 *   DELTA  db=<..> direction=increase|decrease|none
 *   RESULT ok|failed
 *
 * Exit code: 0 = both variants rendered, 3 = the engine could not be used.
 */
public final class OfflineRender {
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final double TONE_HZ = 440.0;
    private static final double TONE_DBFS = -6.0;
    private static final int FRAMES = 48000;

    private OfflineRender() {}

    private static native float[] nativeRender(int paramId, float[] input, int frames,
                                               int channels, float[] output);
    private static native void nativeSetValues(float[] values);

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("usage: OfflineRender <paramId> <float...>");
            System.exit(3);
        }
        int paramId = Integer.parseInt(args[0]);
        float[] values = new float[args.length - 1];
        for (int i = 1; i < args.length; i++) {
            values[i - 1] = Float.parseFloat(args[i]);
        }
        float[] input = tone();
        float[] neutralOut = new float[input.length];
        float[] testedOut = new float[input.length];

        try {
            System.loadLibrary("offlinerender");
        } catch (Throwable t) {
            System.out.println("RESULT failed reason=library detail=" + t);
            System.exit(3);
        }
        float[] neutral = null;
        float[] tested = null;
        try {
            nativeSetValues(new float[] {0.0f, 0.0f, 0.0f});
            neutral = nativeRender(paramId, input, FRAMES, CHANNELS, neutralOut);
            nativeSetValues(values);
            tested = nativeRender(paramId, input, FRAMES, CHANNELS, testedOut);
        } catch (Throwable t) {
            System.out.println("RESULT failed reason=render detail=" + t);
            System.exit(3);
        }
        if (neutral == null || tested == null) {
            System.out.println("RESULT failed reason=null-reply");
            System.exit(3);
        }
        double nRms = neutral[0];
        double nPeak = neutral[1];
        double tRms = tested[0];
        double tPeak = tested[1];
        System.out.println("NEUTRAL rms=" + fmt(nRms) + " peak=" + fmt(nPeak));
        System.out.println("TESTED  rms=" + fmt(tRms) + " peak=" + fmt(tPeak));
        if (nRms <= 0.0) {
            System.out.println("RESULT failed reason=silent-neutral");
            System.exit(3);
        }
        double delta = 20.0 * Math.log10(tRms / nRms);
        System.out.println("DELTA  db=" + fmt(delta) + " direction=" + direction(delta));
        System.out.println("RESULT ok");
    }

    private static float[] tone() {
        float[] out = new float[FRAMES * CHANNELS];
        double amplitude = Math.pow(10.0, TONE_DBFS / 20.0);
        double phase = 0.0;
        for (int i = 0; i < FRAMES; i++) {
            float v = (float) (Math.sin(phase) * amplitude);
            out[2 * i] = v;
            out[2 * i + 1] = v;
            phase += 2.0 * Math.PI * TONE_HZ / SAMPLE_RATE;
            if (phase > 2.0 * Math.PI) {
                phase -= 2.0 * Math.PI;
            }
        }
        return out;
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
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }
}
