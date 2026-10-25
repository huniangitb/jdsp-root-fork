package com.jdsp.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonical parameter baseline ("known good" set) of jdspd, embedded in the daemon.
 *
 * <p>Background (2026-10-25 regression): the App pushed a full dump of its local
 * preferences (including garbage defaults such as NOS R2R enable=true + bitDepth=1 and
 * postGain=-14.4 dB) into the backend's global configuration. As soon as an effect was
 * mounted the audio was squashed by almost 24 dB. This baseline is the single authority
 * to restore a known-good configuration:
 *
 * <ul>
 *   <li>{@code jdspcli baseline} - replace globalParams with this set and broadcast it to
 *       every mounted session (CoreApi.applyBaseline -> SessionWatcher.reapplyAll);</li>
 *   <li>{@code jdspcli baseline --all} - additionally clear every perAppParams scope;</li>
 *   <li>the test drivers (scripts/bench-burst.py, scripts/dual-session-probe.sh,
 *       scripts/hal-crash-probe.sh) run it before and after every run, so test residue
 *       (e.g. the 2204 burst leftovers that once accumulated in state.bin) can no longer
 *       leak into the next run.</li>
 * </ul>
 *
 * <p>The same values ship as {@code scripts/baseline.json} for the shell/python drivers;
 * a host unit test (ParamBaselineTest) asserts that the JSON copy and this constant never
 * diverge. Values were extracted verbatim from the clean
 * {@code /data/adb/jdspd/config.json} (2026-10-25).
 *
 * <p>Every entry is lower-case hex, little endian, as the engine expects on the wire.
 */
public final class ParamBaseline {
    private ParamBaseline() {}

    /** Number of parameters in the baseline. */
    public static final int COUNT = 26;

    /** Returns a fresh copy of the baseline (callers may store/modify it freely). */
    public static Map<Integer, byte[]> params() {
        Map<Integer, byte[]> out = new LinkedHashMap<Integer, byte[]>();
        for (int i = 0; i < IDS.length; i++) {
            out.put(Integer.valueOf(IDS[i]), hex(HEX_VALUES[i]));
        }
        return out;
    }

    /** The baseline param ids, same order as {@link #HEX_VALUES}. */
    public static final int[] IDS = {
            1500, 1201, 1210, 1215, 112, 1300, 1301, 1302, 1305, 1306, 1307,
            115, 116, 1200, 1202, 1203, 1204, 1208, 1214, 1216, 1217, 1218,
            1206, 1212, 1213, 1205
    };

    /** Baseline values (lower-case hex, little endian), same order as {@link #IDS}. */
    static final String[] HEX_VALUES = {
            // 1500: output control [limiterThreshold, limiterRelease, postGain] = -0.1 dB / 60 ms / 0 dB
    //       (postGain MUST be 0: a non-zero value here was the -14.4 dB regression)
            "cdccccbd0000704200000000",
            // 1201: bass boost enable = off
            "0000",
            // 1210: graphic equalizer enable = off
            "0000",
            // 1215: loudness correction enable = off
            "0000",
            // 112: bass boost max gain = 1 (int16 LE)
            "0100",
            // 1300: parametric EQ cascade [sampleRate=48000, preamp=0, no bands]
            "00c0304700000000",
            // 1301: loudness correction data (5 floats, zeroed; inert while 1215 is off)
            "0000000000000000000000000000000000000000",
            // 1302: loudness current volume (dB) = 0
            "00000000",
            // 1305: harmonic expander data (11 floats; inert while 1216 is off)
            "000000000000000000000000000000000000000000000000000000006f12833a000000000000fa4300000000",
            // 1306: sub-harmonic expander data (11 floats; inert while 1217 is off)
            "0000000000000000000000000000000000000000000000000000000000000000000000000000204200000000",
            // 1307: NOS R2R config (11 floats; inert while 1218 is off)
            "000000000000803f000000000000803f00000000000000000000000000000000000000000000000000000000",
            // 115: compander [tc, granularity, tfTransforms, response[14]]; inert while 1200 is off
            "ae47613e00000040000000000000be42000048430000c843000048440000c844008054450060ea4500000000000000000000000000000000000000000000000000000000",
            // 116: 16-band equalizer [filterType, interpolation, bands[30]]; inert while 1202 is off
            "00000000000080bf0000c8410000204200007c420000c8420000204300007a430000c84300801d4400007a440000c84400401c4500007a4500e0c44500401c4600007a460000000000000000000000000000000000000000780ea9bfc01771c0146b27c0303de6bf000000000000000098e0c5bf1c5253c03ae421c000000000",
            // 1200: compander enable = off
            "0000",
            // 1202: 16-band equalizer enable = on (the one deliberately active module)
            "0100",
            // 1203: reverb enable = off
            "0000",
            // 1204: stereo widen enable = off
            "0000",
            // 1208: crossfeed enable = off
            "0000",
            // 1214: parametric EQ cascade enable = off
            "0000",
            // 1216: harmonic expander enable = off
            "0000",
            // 1217: sub-harmonic expander enable = off
            "0000",
            // 1218: NOS R2R enable = off (enable=true here was the regression trigger)
            "0000",
            // 1206: vacuum tube enable = off
            "0000",
            // 1212: DDC enable = off
            "0000",
            // 1213: liveprog enable = off
            "0000",
            // 1205: convolver enable = off
            "0000",
    };

    /** Parses a lower-case hex string; throws when malformed (compile-time data). */
    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("bad hex in ParamBaseline: " + s);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
