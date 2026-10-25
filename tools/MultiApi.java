import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Looper;

import java.io.File;

/**
 * Multi-API playback probe for JamesDSP backend debugging.
 * Usage: CLASSPATH=multiapi.dex app_process /system/bin MultiApi <mode> <secs> [tag]
 * Modes:
 *   track     - AudioTrack normal MODE_STREAM
 *   lowlat    - AudioTrack PERFORMANCE_MODE_LOW_LATENCY
 *   soundpool - SoundPool playing a wav (loop)
 *   media     - MediaPlayer playing the same wav (loop)
 *   all       - all four concurrently, each on its own session id
 *   samesess  - AudioTrack normal + lowlat sharing one session id
 * Each player is monitored: playback head must advance. Reports STALL/OK.
 */
public class MultiApi {
    static final int SR = 48000;
    static final String WAV = "/data/local/tmp/jdsp_tone.wav";

    interface Player {
        /** returns session id, starts playing; returns -1 on failure */
        int start() throws Exception;
        /** true if playback position advanced since last call */
        boolean poll();
        void stop();
        String name();
        /** last error string if any */
        default String getErr() { return ""; }
    }


    static <T> T runOnMain(java.util.concurrent.Callable<T> c) throws Exception {
        final Object[] result = new Object[1];
        final Exception[] ex = new Exception[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        new android.os.Handler(Looper.getMainLooper()).post(() -> {
            try { result[0] = c.call(); } catch (Exception e) { ex[0] = e; }
            finally { latch.countDown(); }
        });
        latch.await();
        if (ex[0] != null) throw ex[0];
        return (T) result[0];
    }

    static String getErr(Player p) {
        try { return (String) p.getClass().getField("err").get(p); } catch (Exception e) { return null; }
    }

    static class TrackPlayer implements Player {
        final String tag;
        final boolean lowLat;
        AudioTrack t;
        int lastHead = -1;
        boolean advanced = false;
        public String err = "";
        final int rate;
        final int channels; // CHANNEL_OUT_* bitmask
        final int encoding;
        final int usage;
        final int contentType;
        TrackPlayer(String tag, boolean lowLat) { this(tag, lowLat, SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC); }
        TrackPlayer(String tag, boolean lowLat, int rate, int channels, int encoding) { this(tag, lowLat, rate, channels, encoding, AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC); }
        TrackPlayer(String tag, boolean lowLat, int usage, int contentType) { this(tag, lowLat, SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, usage, contentType); }
        TrackPlayer(String tag, boolean lowLat, int rate, int channels, int encoding, int usage, int contentType) {
            this.tag = tag; this.lowLat = lowLat; this.rate = rate; this.channels = channels; this.encoding = encoding;
            this.usage = usage; this.contentType = contentType;
        }
        public String name() { return tag; }
        public int start() throws Exception {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(contentType).build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(rate)
                    .setChannelMask(channels).build();
            int minBuf = AudioTrack.getMinBufferSize(rate, channels, encoding);
            if (minBuf <= 0) { err = "getMinBufferSize=" + minBuf; return -1; }
            AudioTrack.Builder b = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(minBuf * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (lowLat) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            t = b.build();
            int state = t.getState();
            if (state != AudioTrack.STATE_INITIALIZED) {
                err = "init state=" + state;
                return -1;
            }
            final boolean floatEnc = encoding == AudioFormat.ENCODING_PCM_FLOAT;
            final int nch = Integer.bitCount(channels);
            t.play();
            new Thread(() -> {
                double phase = 0.0;
                if (floatEnc) {
                    float[] buf = new float[2048 * nch];
                    while (!stopped) {
                        for (int i = 0; i < 1024; i++) {
                            float v = (float) (Math.sin(phase) * 0.25);
                            for (int c = 0; c < nch; c++) buf[i * nch + c] = v;
                            phase += 2.0 * Math.PI * 440.0 / rate;
                            if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI;
                        }
                        int w = t.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING);
                        if (w < 0) { err = "write rc=" + w; stopped = true; }
                    }
                } else {
                    short[] buf = new short[2048 * nch];
                    while (!stopped) {
                        for (int i = 0; i < 1024; i++) {
                            short v = (short) (Math.sin(phase) * 8000);
                            for (int c = 0; c < nch; c++) buf[i * nch + c] = v;
                            phase += 2.0 * Math.PI * 440.0 / rate;
                            if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI;
                        }
                        int w = t.write(buf, 0, buf.length);
                        if (w < 0) { err = "write rc=" + w; stopped = true; }
                    }
                }
            }, tag + "-writer").start();
            return t.getAudioSessionId();
        }
        volatile boolean stopped = false;
        public boolean poll() {
            if (t == null) return false;
            int head = t.getPlaybackHeadPosition();
            if (head != lastHead) { advanced = true; lastHead = head; }
            return advanced;
        }
        public void stop() {
            stopped = true;
            if (t != null) try { t.stop(); t.release(); } catch (Exception e) {}
        }
        String status() {
            if (t == null) return tag + ": FAIL " + err;
            if (err.isEmpty()) return tag + ": " + (advanced ? "OK" : "STALL") + " head=" + lastHead;
            return tag + ": FAIL " + err;
        }
    }

    static class SharedSession {
        AudioTrack normal, lowLat;
        int sessionId = -1;
        public String err = "";
        boolean start() throws Exception {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
            int minBuf = AudioTrack.getMinBufferSize(SR,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            normal = new AudioTrack.Builder().setAudioAttributes(attrs)
                    .setAudioFormat(fmt).setBufferSizeInBytes(minBuf * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
            if (normal.getState() != AudioTrack.STATE_INITIALIZED) { err = "normal init fail"; return false; }
            sessionId = normal.getAudioSessionId();
            lowLat = new AudioTrack(AudioManager.STREAM_MUSIC, SR,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 4, AudioTrack.MODE_STREAM, sessionId);
            if (lowLat.getState() != AudioTrack.STATE_INITIALIZED) { err = "lowlat init fail sess=" + sessionId; return false; }
            normal.play();
            lowLat.play();
            return true;
        }
        void writeLoop() {
            Thread a = new Thread(() -> writeStream(normal, "normal"));
            Thread b = new Thread(() -> writeStream(lowLat, "lowlat"));
            a.start(); b.start();
        }
        void writeStream(AudioTrack t, String tag) {
            short[] buf = new short[2048];
            double phase = 0.0;
            while (!stopped) {
                for (int i = 0; i < 1024; i++) {
                    short v = (short) (Math.sin(phase) * 8000);
                    buf[2 * i] = v;
                    buf[2 * i + 1] = v;
                    phase += 2.0 * Math.PI * 440.0 / SR;
                    if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI;
                }
                int w = t.write(buf, 0, buf.length);
                if (w < 0) { System.out.println(tag + " write rc=" + w); stopped = true; }
            }
        }
        void stopAll() {
            stopped = true;
            for (AudioTrack t : new AudioTrack[]{normal, lowLat})
                if (t != null) try { t.stop(); t.release(); } catch (Exception e) {}
        }
        volatile boolean stopped = false;
    }

    static class SoundPoolPlayer implements Player {
        SoundPool sp;
        int soundId = -1, streamId = -1;
        volatile boolean loaded = false;
        public String err = "";
        public String name() { return "soundpool"; }
        public int start() throws Exception {
            runOnMain(() -> {
                sp = new SoundPool.Builder()
                        .setMaxStreams(2)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .build();
                sp.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                    public void onLoadComplete(SoundPool s, int id, int status) {
                        loaded = true;
                    }
                });
                soundId = sp.load(WAV, 1);
                return 0;
            });
            for (int i = 0; i < 60 && !loaded; i++) Thread.sleep(100);
            if (!loaded) { err = "load timeout soundId=" + soundId; return -1; }
            final int sid = runOnMain(() -> sp.play(soundId, 1.0f, 1.0f, 1, -1, 1.0f));
            streamId = sid;
            if (streamId == 0) { err = "play returned 0"; return -1; }
            return 0; // soundpool session id not exposed; session lives in AudioFlinger mix
        }
        public boolean poll() { return streamId != 0; }
        public void stop() { if (sp != null) { try { sp.stop(streamId); } catch (Exception e) {} sp.release(); } }
    }

    static Context systemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object sysMain = at.getMethod("systemMain").invoke(null);
        return (Context) sysMain.getClass().getMethod("getSystemContext").invoke(sysMain);
    }

    static class MediaPlayerPlayer implements Player {
        MediaPlayer mp;
        volatile boolean started = false;
        public String err = "";
        public String name() { return "media"; }
        public int start() throws Exception {
            try {
                final Context ctx = runOnMain(MultiApi::systemContext);
                mp = runOnMain(() -> {
                    MediaPlayer m;
                    try {
                        m = MediaPlayer.class.getConstructor(Context.class).newInstance(ctx);
                    } catch (Exception e) {
                        System.out.println("MEDIA-CTOR-CTX-FAIL " + e);
                        m = new MediaPlayer();
                    }
                    m.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                    java.io.FileInputStream fis = new java.io.FileInputStream(WAV);
                    m.setDataSource(fis.getFD());
                    fis.close();
                    m.prepare();
                    return m;
                });
            } catch (Throwable t) {
                Throwable c = t; while (c.getCause() != null) c = c.getCause();
                c.printStackTrace();
                err = "prepare fail: " + c;
                return -1;
            }
            mp.setLooping(true);
            mp.start();
            Thread.sleep(300);
            started = mp.isPlaying();
            if (!started) { err = "not playing after start"; return -1; }
            return mp.getAudioSessionId();
        }
        public boolean poll() { return started && mp.isPlaying(); }
        public void stop() { if (mp != null) try { mp.stop(); mp.release(); } catch (Exception e) {} }
    }

    /** stress <secs_per_round_unused> <rounds>: rapid create/play/stop cycles across sessions */
    static void stress(int unused, int rounds) throws Exception {
        int failures = 0;
        for (int r = 0; r < rounds; r++) {
            try {
                TrackPlayer a = new TrackPlayer("s" + r, false);
                TrackPlayer b = new TrackPlayer("sl" + r, true);
                int sa = a.start();
                int sb = b.start();
                Thread.sleep(700);
                boolean okA = a.poll(), okB = b.poll();
                if (sa < 0 || sb < 0 || !okA || !okB) {
                    failures++;
                    System.out.println("ROUND " + r + " FAIL a=" + sa + "/" + okA + " b=" + sb + "/" + okB
                            + " errA=" + a.err + " errB=" + b.err);
                }
                a.stop();
                b.stop();
                Thread.sleep(200);
            } catch (Exception e) {
                failures++;
                System.out.println("ROUND " + r + " EXC " + e);
            }
        }
        System.out.println("STRESS_DONE rounds=" + rounds + " failures=" + failures);
        System.exit(failures == 0 ? 0 : 5);
    }

    /** capture <secs>: record REMOTE_SUBMIX and print per-second RMS */
    static void capture(int secs) throws Exception {
        int sr = 48000;
        int min = android.media.AudioRecord.getMinBufferSize(sr,
                android.media.AudioFormat.CHANNEL_IN_STEREO,
                android.media.AudioFormat.ENCODING_PCM_16BIT);
        android.media.AudioRecord rec = new android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.REMOTE_SUBMIX, sr,
                android.media.AudioFormat.CHANNEL_IN_STEREO,
                android.media.AudioFormat.ENCODING_PCM_16BIT, min * 4);
        System.out.println("CAPTURE state=" + rec.getState() + " recstate=" + rec.getRecordingState());
        if (rec.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
            System.out.println("CAPTURE_UNAVAILABLE");
            System.exit(2);
        }
        rec.startRecording();
        short[] buf = new short[sr]; // 1s stereo
        for (int s = 0; s < secs; s++) {
            int read = 0;
            while (read < buf.length) {
                int r = rec.read(buf, read, buf.length - read);
                if (r <= 0) break;
                read += r;
            }
            double sum = 0;
            for (short v : buf) sum += (v / 32768.0) * (v / 32768.0);
            double rms = Math.sqrt(sum / buf.length);
            System.out.println("RMS t=" + s + "s " + String.format("%.6f", rms)
                    + " dbfs=" + String.format("%.2f", 20 * Math.log10(rms + 1e-12)));
        }
        rec.stop();
        rec.release();
        System.out.println("CAPTURE_DONE");
    }

    public static void main(String[] args) throws Exception {
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        final String[] a = args;
        new Thread(() -> {
            try { runTest(a); } catch (Throwable t) {
                t.printStackTrace();
                System.exit(9);
            }
        }, "worker").start();
        Looper.loop();
    }

    static void runTest(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "all";
        int secs = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        String tag = args.length > 2 ? args[2] : "t";
        if (mode.equals("capture")) { capture(secs); return; }
        if (mode.equals("stress")) { stress(secs, Integer.parseInt(args.length > 3 ? args[3] : "20")); return; }
        // note: caller (main) has prepared a Looper and pumps it so SoundPool/MediaPlayer
        // callbacks can be delivered on the main looper; this body runs on the worker thread.

        java.util.List<Player> players = new java.util.ArrayList<>();
        SharedSession shared = null;

        if (mode.equals("track") || mode.equals("all"))
            players.add(new TrackPlayer("track", false));
        if (mode.equals("lowlat") || mode.equals("all"))
            players.add(new TrackPlayer("lowlat", true));
        if (mode.equals("soundpool") || mode.equals("all"))
            players.add(new SoundPoolPlayer());
        if (mode.equals("media") || mode.equals("all"))
            players.add(new MediaPlayerPlayer());
        if (mode.equals("voicetrack")) players.add(new TrackPlayer("voicetrack", false, AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.CONTENT_TYPE_SPEECH));
        if (mode.equals("gametrack")) players.add(new TrackPlayer("gametrack", false, AudioAttributes.USAGE_GAME, AudioAttributes.CONTENT_TYPE_SONIFICATION));
        if (mode.equals("track44")) players.add(new TrackPlayer("track44", false, 44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT));
        if (mode.equals("track96")) players.add(new TrackPlayer("track96", false, 96000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT));
        if (mode.equals("trackmono")) players.add(new TrackPlayer("trackmono", false, 48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT));
        if (mode.equals("trackfloat")) players.add(new TrackPlayer("trackfloat", false, 48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT));
        if (mode.equals("lowlat44")) players.add(new TrackPlayer("lowlat44", true, 44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT));
        if (mode.equals("variants")) {
            players.add(new TrackPlayer("track44", false, 44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT));
            players.add(new TrackPlayer("track96", false, 96000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT));
            players.add(new TrackPlayer("trackmono", false, 48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT));
            players.add(new TrackPlayer("trackfloat", false, 48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT));
        }
        if (mode.equals("samesess")) {
            shared = new SharedSession();
            boolean ok = shared.start();
            if (!ok) { System.out.println("RESULT FAIL shared: " + shared.err); System.exit(3); }
            shared.writeLoop();
            System.out.println("SESSIONS shared=" + shared.sessionId);
            Thread.sleep(secs * 1000L);
            int n1 = shared.normal.getPlaybackHeadPosition();
            int n2 = shared.lowLat.getPlaybackHeadPosition();
            System.out.println("HEAD normal=" + n1 + " lowlat=" + n2);
            boolean ok1 = n1 > 0, ok2 = n2 > 0;
            shared.stopAll();
            System.out.println("RESULT " + (ok1 && ok2 ? "PASS" : "FAIL")
                    + " normal=" + (ok1 ? "OK" : "STALL") + " lowlat=" + (ok2 ? "OK" : "STALL"));
            System.exit(ok1 && ok2 ? 0 : 4);
            return;
        }

        StringBuilder sb = new StringBuilder("SESSIONS");
        boolean anyFail = false;
        for (Player p : players) {
            try {
                int sid = p.start();
                if (sid < 0) { anyFail = true; sb.append(" ").append(p.name()).append("=FAIL"); }
                else sb.append(" ").append(p.name()).append("=").append(sid);
            } catch (Exception e) {
                anyFail = true;
                sb.append(" ").append(p.name()).append("=EXC:").append(e.getClass().getSimpleName());
                e.printStackTrace();
            }
            String err = getErr(p);
            if (err != null && !err.isEmpty()) sb.append("(").append(err).append(")");
        }
        System.out.println(sb);

        long deadline = System.currentTimeMillis() + secs * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            for (Player p : players) p.poll();
        }
        StringBuilder res = new StringBuilder("RESULT");
        boolean allOk = true;
        for (Player p : players) {
            boolean ok = p.poll();
            if (!ok) allOk = false;
            res.append(" ").append(p.name()).append("=").append(ok ? "OK" : "STALL");
        }
        System.out.println(res);
        System.exit(!anyFail && allOk ? 0 : (anyFail ? 3 : 4));
    }
}
