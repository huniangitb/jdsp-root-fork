import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

/**
 * Dual-session silence probe for the "second low-latency session goes silent" bug.
 *
 * Usage: CLASSPATH=dual.dex app_process /system/bin DualSession <mode> [args...]
 * Modes:
 *   dual <A> <B> <bothSecs> <soloSecs>
 *       A = silence holder track, B = 440 Hz sine track; A/B in {low, normal}.
 *       Timeline: A starts (t0) -> 3 s settle (daemon attaches) -> B starts ->
 *       both play <bothSecs> -> A stops -> B plays solo <soloSecs> -> exit.
 *       Per-phase playback heads are printed so a stalled/silent B is visible.
 *   tone <kind> <secs>
 *       one sine track alone; kind in {low, normal} (B-alone baseline)
 *   miccap <secs>
 *       MIC capture RMS per second (audibility ground truth)
 */
public class DualSession {
    static final int SR = 48000;

    static AudioTrack makeTrack(boolean low, boolean silent) {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SR)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
        int minBuf = AudioTrack.getMinBufferSize(SR,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack.Builder b = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM);
        if (low) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        return b.build();
    }

    /** Sine/silence writer loop. Returns the track after start. */
    static Track startTrack(String name, boolean low, boolean silent, int amp, double freq) throws Exception {
        final AudioTrack t = makeTrack(low, silent);
        if (t.getState() != AudioTrack.STATE_INITIALIZED)
            throw new IllegalStateException(name + " init state=" + t.getState());
        t.play();
        Thread w = new Thread(() -> {
            short[] buf = new short[2048];
            double phase = 0.0;
            while (!stopped) {
                if (silent) {
                    if (amp > 0) {
                        // near-silence: tiny amplitude, keeps the track non-zero
                        for (int i = 0; i < 1024; i++) {
                            short v = (short) (Math.sin(phase) * amp);
                            buf[2 * i] = v;
                            buf[2 * i + 1] = v;
                            phase += 2.0 * Math.PI * freq / SR;
                            if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI;
                        }
                    } else {
                        java.util.Arrays.fill(buf, (short) 0);
                    }
                } else {
                    for (int i = 0; i < 1024; i++) {
                        short v = (short) (Math.sin(phase) * 12000);
                        buf[2 * i] = v;
                        buf[2 * i + 1] = v;
                        phase += 2.0 * Math.PI * freq / SR;
                        if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI;
                    }
                }
                int r = t.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING);
                if (r < 0) { System.out.println(name + " write rc=" + r); stopped = true; }
            }
        }, name + "-writer");
        w.start();
        return new Track(name, t);
    }

    static volatile boolean stopped = false;

    static class Track {
        final AudioTrack t;
        final String name;
        int lastHead = -1;
        Track(String n, AudioTrack t) { name = n; this.t = t; }
        int head() { return t.getPlaybackHeadPosition(); }
        void stop() {
            try { t.stop(); t.release(); } catch (Exception e) {}
        }
    }

    static int prevVol = -1;

    static void runOnMain(Runnable r) throws Exception {
        final Exception[] ex = new Exception[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try { r.run(); } catch (Exception e) { ex[0] = e; }
            finally { latch.countDown(); }
        });
        latch.await();
        if (ex[0] != null) throw ex[0];
    }

    static void setMaxVolume() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            final Object sysMain = at.getMethod("systemMain").invoke(null);
            final android.content.Context ctx = (android.content.Context) sysMain.getClass()
                    .getMethod("getSystemContext").invoke(sysMain);
            android.media.AudioManager am = ctx.getSystemService(android.media.AudioManager.class);
            prevVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, max, 0);
            System.out.println("VOL prev=" + prevVol + " max=" + max);
        } catch (Throwable t) {
            Throwable c = t; while (c.getCause() != null) c = c.getCause();
            System.out.println("VOL fail " + c);
        }
    }

    static int modeOf(String s) {
        if (s.equals("low")) return 1;
        if (s.equals("normal")) return 0;
        throw new IllegalArgumentException("bad kind " + s);
    }

    static void dual(String[] a) throws Exception {
        int aKind = modeOf(a[1]);
        int bKind = modeOf(a[2]);
        int bothSecs = a.length > 3 ? Integer.parseInt(a[3]) : 8;
        int soloSecs = a.length > 4 ? Integer.parseInt(a[4]) : 5;
        int aAmp = a.length > 5 ? Integer.parseInt(a[5]) : 0; // 0 = pure silence
        double aFreq = a.length > 6 ? Double.parseDouble(a[6]) : 440.0;
        double bFreq = a.length > 7 ? Double.parseDouble(a[7]) : 440.0;
        stopped = false;
        setMaxVolume();

        Track A = startTrack("A", aKind == 1, true, aAmp, aFreq);
        System.out.println("A sid=" + A.t.getAudioSessionId() + " kind="
                + (aKind == 1 ? "low" : "normal") + " amp=" + aAmp + " freq=" + aFreq
                + " perf=" + A.t.getPerformanceMode());
        System.out.flush();
        Thread.sleep(3000); // let the daemon watcher attach

        Track B = startTrack("B", bKind == 1, false, 0, bFreq);
        System.out.println("B sid=" + B.t.getAudioSessionId() + " kind="
                + (bKind == 1 ? "low" : "normal") + " freq=" + bFreq
                + " perf=" + B.t.getPerformanceMode());
        System.out.println("B sid=" + B.t.getAudioSessionId() + " kind="
                + (bKind == 1 ? "low" : "normal") + " perf=" + B.t.getPerformanceMode());
        System.out.println("PHASE BOTH " + System.currentTimeMillis());
        System.out.flush();
        long endB = System.currentTimeMillis() + bothSecs * 1000L;
        while (System.currentTimeMillis() < endB) {
            Thread.sleep(1000);
            System.out.println("TICK both " + System.currentTimeMillis()
                    + " A=" + A.head() + " B=" + B.head());
            System.out.flush();
        }
        int bHeadBoth = B.head();
        int aHeadBoth = A.head();
        System.out.println("HEADS both A=" + aHeadBoth + " B=" + bHeadBoth
                + " Badvanced=" + (bHeadBoth > 0));

        A.stop();
        System.out.println("PHASE SOLO " + System.currentTimeMillis());
        System.out.flush();
        long endS = System.currentTimeMillis() + soloSecs * 1000L;
        while (System.currentTimeMillis() < endS) {
            Thread.sleep(1000);
            System.out.println("TICK solo " + System.currentTimeMillis() + " B=" + B.head());
            System.out.flush();
        }
        int bHeadSolo = B.head();
        System.out.println("HEADS solo B=" + bHeadSolo
                + " BadvancedInSolo=" + (bHeadSolo > bHeadBoth));
        B.stop();
        boolean okBoth = bHeadBoth > 0;
        boolean okSolo = bHeadSolo > bHeadBoth;
        System.out.println("RESULT B_both=" + (okBoth ? "OK" : "SILENT_OR_STALL")
                + " B_solo=" + (okSolo ? "OK" : "STALL"));
        System.exit(okBoth && okSolo ? 0 : (okSolo ? 6 : 4));
    }

    static void tone(String[] a) throws Exception {
        int kind = modeOf(a[1]);
        int secs = Integer.parseInt(a[2]);
        stopped = false;
        setMaxVolume();
        Track T = startTrack("T", kind == 1, false, 0,
                a.length > 3 ? Double.parseDouble(a[3]) : 440.0);
        System.out.println("T sid=" + T.t.getAudioSessionId() + " perf="
                + T.t.getPerformanceMode());
        System.out.flush();
        Thread.sleep(secs * 1000L);
        int h = T.head();
        T.stop();
        System.out.println("RESULT head=" + h + " " + (h > 0 ? "OK" : "STALL"));
        System.exit(h > 0 ? 0 : 4);
    }

    static void miccap(int secs) throws Exception {
        int min = AudioRecord.getMinBufferSize(SR,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SR,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, min * 4);
        System.out.println("CAPTURE state=" + rec.getState());
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            System.out.println("CAPTURE_UNAVAILABLE");
            System.exit(2);
        }
        rec.startRecording();
        short[] buf = new short[SR]; // 1 s stereo
        for (int s = 0; s < secs; s++) {
            int read = 0;
            long t0 = System.currentTimeMillis();
            while (read < buf.length) {
                int r = rec.read(buf, read, buf.length - read);
                if (r <= 0) break;
                read += r;
            }
            double sum = 0;
            for (short v : buf) { double x = v / 32768.0; sum += x * x; }
            double rms = Math.sqrt(sum / Math.max(1, read));
            System.out.println("RMS t=" + s + " wall=" + t0 + " "
                    + String.format("%.6f", rms) + " dbfs="
                    + String.format("%.2f", 20 * Math.log10(rms + 1e-12)));
            System.out.flush();
        }
        rec.stop();
        rec.release();
        System.out.println("CAPTURE_DONE");
        System.exit(0);
    }

    static void micrec(int secs, String path) throws Exception {
        int min = AudioRecord.getMinBufferSize(SR,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SR,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, min * 4);
        System.out.println("CAPTURE state=" + rec.getState() + " wall="
                + System.currentTimeMillis());
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            System.out.println("CAPTURE_UNAVAILABLE");
            System.exit(2);
        }
        java.io.FileOutputStream out = new java.io.FileOutputStream(path);
        rec.startRecording();
        short[] buf = new short[SR]; // 1 s stereo
        long t0 = System.currentTimeMillis();
        for (int s = 0; s < secs; s++) {
            int read = 0;
            while (read < buf.length) {
                int r = rec.read(buf, 0, buf.length - read);
                if (r <= 0) break;
                for (int i = 0; i < r; i++) {
                    out.write(buf[i] & 0xFF);
                    out.write((buf[i] >> 8) & 0xFF);
                }
                read += r;
            }
        }
        out.close();
        rec.stop();
        rec.release();
        System.out.println("REC_DONE wall0=" + t0 + " wall1=" + System.currentTimeMillis());
        System.exit(0);
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "dual";
        if (mode.equals("dual")) dual(args);
        else if (mode.equals("tone")) tone(args);
        else if (mode.equals("miccap")) miccap(args.length > 1 ? Integer.parseInt(args[1]) : 10);
        else if (mode.equals("micrec")) micrec(args.length > 1 ? Integer.parseInt(args[1]) : 10,
                args.length > 2 ? args[2] : "/data/local/tmp/p05.pcm");
        else { System.out.println("unknown mode " + mode); System.exit(1); }
    }
}
