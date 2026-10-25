import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AudioEffect;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * SignalProbe — signal level evidence for the JamesDSP effect chain.
 *
 * Two jobs, both printed as machine readable key=value lines:
 *
 *   probe          Can we capture the output mix at all?  Tries
 *                  AudioRecord(REMOTE_SUBMIX) and reports whether it could be
 *                  initialised, whether it delivers data and whether that data
 *                  is all zero.  Nothing is played.
 *
 *   capture        Play a 440 Hz tone (-6 dBFS, USAGE_MEDIA so the music
 *                  stream policy applies) and capture the output mix at the
 *                  same time.  Prints the AudioTrack session id plus the RMS
 *                  and the peak of the captured signal in dBFS, the number of
 *                  non-zero samples and the ratio of non-zero samples.
 *
 * Usage:
 *   SignalProbe probe
 *   SignalProbe capture <seconds> [tag]
 *
 * The tag is written into the output so a script can correlate several runs.
 * Exit code: 0 = capture produced data, 3 = capture is not usable (the caller
 * then has to fall back to an offline rendering comparison).
 */
public final class SignalProbe {
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final double TONE_HZ = 440.0;
    private static final double TONE_DBFS = -6.0;
    private static final UUID EFFECT_TYPE =
            UUID.fromString("f98765f4-c321-5de6-9a45-123459495ab2");
    private static final UUID EFFECT_UUID =
            UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2");

    private SignalProbe() {}

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "probe";
        if ("probe".equals(mode)) {
            String source = args.length > 1 ? args[1] : "remote";
            System.exit(probe(source));
        } else if ("capture".equals(mode)) {
            int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 6;
            String tag = args.length > 2 ? args[2] : "run";
            double dbfs = args.length > 3 ? Double.parseDouble(args[3]) : TONE_DBFS;
            String source = args.length > 4 ? args[4] : "remote";
            System.exit(capture(seconds, tag, dbfs, source));
        }
        System.out.println("usage: SignalProbe probe [remote|mic] | "
                + "capture <seconds> [tag] [toneDbfs] [remote|mic]");
        System.exit(3);
    }

    // ------------------------------------------------------------------ probe --

    /** "remote" = AudioRecord(REMOTE_SUBMIX), "mic" = the acoustic loopback. */
    private static int audioSource(String source) {
        if ("mic".equals(source)) {
            return MediaRecorder.AudioSource.MIC;
        }
        return MediaRecorder.AudioSource.REMOTE_SUBMIX;
    }

    private static int probe(String source) {
        System.out.println("PROBE start source=" + source);
        AudioRecord recorder = null;
        try {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            System.out.println("PROBE minBufferBytes=" + min);
            if (min <= 0) {
                System.out.println("PROBE result=unavailable reason=getMinBufferSize");
                return 3;
            }
            recorder = new AudioRecord(audioSource(source),
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT, min * 4);
            System.out.println("PROBE state=" + recorder.getState());
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                System.out.println("PROBE result=unavailable reason=not_initialized");
                return 3;
            }
            recorder.startRecording();
            System.out.println("PROBE recordingState=" + recorder.getRecordingState());
            byte[] buffer = new byte[min * 4];
            int read = recorder.read(buffer, 0, buffer.length);
            System.out.println("PROBE readBytes=" + read);
            if (read <= 0) {
                System.out.println("PROBE result=unavailable reason=no_data");
                return 3;
            }
            Stats stats = stats(buffer, read);
            System.out.println("PROBE rmsDbfs=" + fmt(stats.rmsDbfs())
                    + " peakDbfs=" + fmt(stats.peakDbfs())
                    + " nonZero=" + stats.nonZero + " samples=" + stats.samples);
            if (stats.nonZero == 0) {
                System.out.println("PROBE result=unavailable reason=all_zero");
                return 3;
            }
            System.out.println("PROBE result=available");
            return 0;
        } catch (Throwable t) {
            System.out.println("PROBE result=unavailable reason=exception detail=" + t);
            return 3;
        } finally {
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (Throwable ignored) {
                    // best effort
                }
                recorder.release();
            }
        }
    }

    // ---------------------------------------------------------------- capture --

    private static int capture(int seconds, String tag, double toneDbfs, String source) {
        System.out.println("CAPTURE start tag=" + tag + " seconds=" + seconds);
        AudioRecord recorder = null;
        AudioTrack track = null;
        try {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                System.out.println("CAPTURE result=unavailable reason=getMinBufferSize");
                return 3;
            }
            recorder = new AudioRecord(audioSource(source),
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT, min * 4);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                System.out.println("CAPTURE result=unavailable reason=not_initialized");
                return 3;
            }

            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build();
            int trackMin = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(trackMin * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            int session = track.getAudioSessionId();
            System.out.println("CAPTURE session=" + session + " trackState=" + track.getState());

            recorder.startRecording();
            track.play();

            Thread writer = new Thread(new ToneWriter(track, seconds, toneDbfs));
            writer.start();

            byte[] buffer = new byte[min * 4];
            java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
            long totalBytes = 0;
            long totalNonZero = 0;
            double sumSquares = 0.0;
            int peak = 0;
            int samples = 0;
            long deadline = System.currentTimeMillis() + seconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    continue;
                }
                totalBytes += read;
                sink.write(buffer, 0, read);
                Stats chunk = stats(buffer, read);
                sumSquares += chunk.sumSquares;
                totalNonZero += chunk.nonZero;
                samples += chunk.samples;
                if (chunk.peak > peak) {
                    peak = chunk.peak;
                }
            }
            writer.join(2000);
            captured = sink.toByteArray();

            double rms = samples > 0 ? Math.sqrt(sumSquares / samples) : 0.0;
            double tone = goertzelDbfs();
            System.out.println("CAPTURE tag=" + tag + " session=" + session
                    + " source=" + source
                    + " bytes=" + totalBytes
                    + " rmsDbfs=" + fmt(toDb(rms))
                    + " peakDbfs=" + fmt(toDb(peak))
                    + " toneDbfs=" + fmt(tone)
                    + " nonZero=" + totalNonZero
                    + " samples=" + samples);
            if (totalBytes == 0) {
                System.out.println("CAPTURE result=unavailable reason=no_data");
                return 3;
            }
            if (totalNonZero == 0) {
                System.out.println("CAPTURE result=unavailable reason=all_zero");
                return 3;
            }
            System.out.println("CAPTURE result=ok");
            return 0;
        } catch (Throwable t) {
            System.out.println("CAPTURE result=unavailable reason=exception detail=" + t);
            return 3;
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (Throwable ignored) {
                    // best effort
                }
                track.release();
            }
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (Throwable ignored) {
                    // best effort
                }
                recorder.release();
            }
        }
    }

    // ------------------------------------------------------------------ tones --

    /** Writes the test tone into the AudioTrack in the background. */
    private static final class ToneWriter implements Runnable {
        private final AudioTrack track;
        private final int seconds;
        private final double dbfs;

        ToneWriter(AudioTrack track, int seconds, double dbfs) {
            this.track = track;
            this.seconds = seconds;
            this.dbfs = dbfs;
        }

        @Override
        public void run() {
            short[] buf = new short[2048];
            double phase = 0.0;
            double amplitude = Math.pow(10.0, dbfs / 20.0) * 32767.0;
            long total = (long) SAMPLE_RATE * seconds;
            long written = 0;
            while (written < total) {
                for (int i = 0; i < 1024; i++) {
                    short v = (short) (Math.sin(phase) * amplitude);
                    buf[2 * i] = v;
                    buf[2 * i + 1] = v;
                    phase += 2.0 * Math.PI * TONE_HZ / SAMPLE_RATE;
                    if (phase > 2.0 * Math.PI) {
                        phase -= 2.0 * Math.PI;
                    }
                }
                track.write(buf, 0, buf.length);
                written += 1024;
            }
        }
    }

    // ------------------------------------------------------------------ stats --

    /** The captured samples of the last capture() call (for the tone detector). */
    private static byte[] captured = new byte[0];

    /**
     * Amplitude of the 440 Hz component in dBFS, measured with a Goertzel
     * detector.  It ignores the noise floor, so the numbers are repeatable
     * even when the capture path picks up a lot of background noise.
     */
    private static double goertzelDbfs() {
        int count = captured.length / 2;
        if (count < 64) {
            return -200.0;
        }
        // use the first channel only
        int n = count;
        double k = 2.0 * Math.PI * TONE_HZ / SAMPLE_RATE;
        double coeff = 2.0 * Math.cos(k);
        double s0 = 0.0;
        double s1 = 0.0;
        double s2 = 0.0;
        for (int i = 0; i < n; i++) {
            short v = (short) ((captured[2 * i] & 0xff) | (captured[2 * i + 1] << 8));
            s0 = v + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
        if (power <= 0.0) {
            return -200.0;
        }
        double amplitude = 2.0 * Math.sqrt(power) / n;
        return toDb(amplitude);
    }

    private static final class Stats {
        double sumSquares;
        long nonZero;
        int samples;
        int peak;

        double rmsDbfs() {
            if (samples == 0) {
                return -200.0;
            }
            return toDb(Math.sqrt(sumSquares / samples));
        }

        double peakDbfs() {
            return toDb(peak);
        }
    }

    private static Stats stats(byte[] buffer, int length) {
        Stats stats = new Stats();
        int count = length / 2;
        for (int i = 0; i < count; i++) {
            short v = (short) ((buffer[2 * i] & 0xff) | (buffer[2 * i + 1] << 8));
            double d = v;
            stats.sumSquares += d * d;
            if (v != 0) {
                stats.nonZero++;
            }
            int a = v < 0 ? -v : v;
            if (a > stats.peak) {
                stats.peak = a;
            }
        }
        stats.samples = count;
        return stats;
    }

    private static double toDb(double amplitude) {
        if (amplitude <= 0.0) {
            return -200.0;
        }
        return 20.0 * Math.log10(amplitude / 32768.0);
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
