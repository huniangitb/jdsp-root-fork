import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * EffectCtl — session effect matrix probe, bypassing the jdspd daemon.
 *
 * Usage: CLASSPATH=effectctl.dex app_process /system/bin EffectCtl <mode> ...
 *   matrix <kind> <name> <op> <holdSecs> [freq] [bSecs]
 *       kind: normal | low | off (no track) | sess (explicit session id in [freq])
 *       name: jdsp | dynproc | misound | dap | gamedap | vqe | loudness
 *       op:   create (Registered, not Enabled) | enable | disable | release1 (release after 1 s)
 *       Plays a sine for holdSecs with the effect attached; prints states.
 *   getq <sessionId> <paramId> <pollSecs>
 *       Attaches the JamesDSP effect to an existing session and polls a GET_PARAM
 *       counter id (30010 procCalls, 30011 procFrames, 30012 lastEnable,
 *       30013 lastFmt, 30014 lastFrames, 30015 setCfgRet, 30016 cfgFmtIn,
 *       30017 cfgFmtOut, 30018 setCfgCalls).
 *   setf <sessionId> <paramId> <float...> — SET_PARAM floats (join existing instance).
 */
public class EffectCtl {
    static final UUID TYPE = UUID.fromString("f98765f4-c321-5de6-9a45-123459495ab2");
    static final UUID JDSP = UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2");
    static final UUID DYNPROC = UUID.fromString("e0e6539b-1781-7261-676f-6d7573696340");
    static final UUID MISOUND = UUID.fromString("5b8e36a5-144a-4c38-b1d7-0002a5d5c51b");
    static final UUID DAP = UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa");
    static final UUID GAMEDAP = UUID.fromString("3783c334-d3a0-4d13-874f-0032e5fb80e2");
    static final UUID VQE = UUID.fromString("64a0f614-7fa4-48b8-b081-d59dc954616f");
    // AOSP loudness enhancer: type 3ddd3118-6b19-46bc-a846-7c4e9dfff745,
    // impl 5a0f6ce3-7d7e-4f8d-a98e-0e5b1d0f0e4b (system libeffects)
    static final UUID LOUD_TYPE = UUID.fromString("3ddd3118-6b19-46bc-a846-7c4e9dfff745");
    static final UUID LOUD_IMPL = UUID.fromString("5a0f6ce3-7d7e-4f8d-a98e-0e5b1d0f0e4b");

    static final int SR = 48000;

    static byte[] le32(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    static volatile boolean stopped = false;

    static void setMaxVolume() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            final Object sysMain = at.getMethod("systemMain").invoke(null);
            final android.content.Context ctx = (android.content.Context) sysMain.getClass()
                    .getMethod("getSystemContext").invoke(sysMain);
            android.media.AudioManager am = ctx.getSystemService(android.media.AudioManager.class);
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, max, 0);
            System.out.println("VOL set max=" + max);
        } catch (Throwable t) {
            System.out.println("VOL fail " + t);
        }
    }

    static AudioTrack makeTrack(boolean low) {
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

    static AudioTrack startTone(double freq) throws Exception {
        final AudioTrack t = makeTrack(false);
        if (t.getState() != AudioTrack.STATE_INITIALIZED)
            throw new IllegalStateException("track init " + t.getState());
        t.play();
        Thread w = new Thread(() -> {
            short[] buf = new short[2048];
            double phase = 0.0;
            while (!stopped) {
                for (int i = 0; i < 1024; i++) {
                    short v = (short) (Math.sin(phase) * 12000);
                    buf[2 * i] = v;
                    buf[2 * i + 1] = v;
                    phase += 2.0 * Math.PI * freq / SR;
                    if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI;
                }
                int r = t.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING);
                if (r < 0) { System.out.println("write rc=" + r); stopped = true; }
            }
        }, "tone-writer");
        w.start();
        return t;
    }

    static UUID implOf(String name) {
        if (name.equals("jdsp")) return JDSP;
        if (name.equals("dynproc")) return DYNPROC;
        if (name.equals("misound")) return MISOUND;
        if (name.equals("dap")) return DAP;
        if (name.equals("gamedap")) return GAMEDAP;
        if (name.equals("vqe")) return VQE;
        if (name.equals("loudness")) return LOUD_IMPL;
        throw new IllegalArgumentException("unknown effect " + name);
    }

    static UUID typeOf(String name) {
        if (name.equals("loudness")) return LOUD_TYPE;
        return TYPE; // custom type for jdsp; for the others the framework looks up by impl uuid
    }

    static AudioEffect attach(String name, int session) throws Exception {
        Constructor<AudioEffect> ctor = AudioEffect.class
                .getConstructor(UUID.class, UUID.class, int.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(typeOf(name), implOf(name), 0, session);
    }

    static int setParam(AudioEffect fx, int paramId, byte[] value) throws Exception {
        Method sp = AudioEffect.class.getMethod("setParameter", byte[].class, byte[].class);
        Object rc = sp.invoke(fx, le32(paramId), value);
        return rc instanceof Integer ? (Integer) rc : -999;
    }

    static int getParam(AudioEffect fx, int paramId, byte[] value) throws Exception {
        Method gp = AudioEffect.class.getMethod("getParameter", byte[].class, byte[].class);
        Object rc = gp.invoke(fx, le32(paramId), value);
        return rc instanceof Integer ? (Integer) rc : -999;
    }

    static int le32val(byte[] b) {
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    static void describe(AudioEffect fx, String tag) {
        System.out.println(tag + " id=" + fx.getId() + " hasControl=" + safe(fx, "hasControl")
                + " enabled=" + safeEnabled(fx));
    }

    static Boolean safe(AudioEffect fx, String m) {
        try { return (Boolean) AudioEffect.class.getMethod(m).invoke(fx); }
        catch (Throwable t) { return null; }
    }

    static Boolean safeEnabled(AudioEffect fx) {
        try { return fx.getEnabled(); }
        catch (Throwable t) { return null; }
    }

    static void matrix(String[] a) throws Exception {
        String kind = a[1];
        String name = a[2];
        String op = a[3];
        int holdSecs = Integer.parseInt(a[4]);
        double freq = a.length > 5 ? Double.parseDouble(a[5]) : 440.0;
        int bSecs = a.length > 6 ? Integer.parseInt(a[6]) : 0;
        stopped = false;
        setMaxVolume();
        AudioTrack t = null;
        int session;
        if (kind.equals("sess")) {
            session = (int) freq;
        } else if (kind.equals("off")) {
            session = 0;
        } else {
            t = startTone(freq);
            session = t.getAudioSessionId();
            System.out.println("TONE sid=" + session + " kind=" + kind
                    + " perf=" + t.getPerformanceMode() + " freq=" + freq);
            Thread.sleep(2000); // settle
        }
        AudioEffect fx;
        try {
            fx = attach(name, session);
        } catch (Throwable attT) {
            System.out.println("ATTACH FAILED " + name + " session=" + session + ": " + attT);
            Throwable c = attT.getCause();
            while (c != null) { System.out.println("  cause: " + c); c = c.getCause(); }
            System.exit(6);
            return;
        }
        describe(fx, "ATTACH " + name + " session=" + session);
        if (op.equals("enable")) {
            int rc = fx.setEnabled(true);
            System.out.println("setEnabled(true) rc=" + rc);
        } else if (op.equals("disable")) {
            int rc = fx.setEnabled(false);
            System.out.println("setEnabled(false) rc=" + rc);
        } else if (op.equals("release1")) {
            Thread.sleep(1000);
            fx.release();
            System.out.println("released after 1 s");
            Thread.sleep(500);
            describe(fx, "AFTER-RELEASE");
        }
        describe(fx, "POST-OP");
        long end = System.currentTimeMillis() + holdSecs * 1000L;
        int tick = 0;
        byte[] val = new byte[4];
        while (System.currentTimeMillis() < end) {
            Thread.sleep(1000);
            if (name.equals("jdsp")) {
                java.util.Arrays.fill(val, (byte) 0);
                int rc = getParam(fx, 30010, val);
                int c1 = le32val(val);
                java.util.Arrays.fill(val, (byte) 0);
                getParam(fx, 30011, val);
                int c2 = le32val(val);
                java.util.Arrays.fill(val, (byte) 0);
                getParam(fx, 30018, val);
                System.out.println("TICK t=" + tick + " head="
                        + (t != null ? t.getPlaybackHeadPosition() : -1)
                        + " procCalls=" + c1 + " procFrames=" + c2
                        + " setCfg=" + le32val(val) + " rc=" + rc);
            } else if (t != null && tick % 2 == 0) {
                System.out.println("TICK head=" + t.getPlaybackHeadPosition());
            }
            System.out.flush();
            tick++;
        }
        if (bSecs > 0 && t != null) {
            // optional B session: second tone on a fresh session
            AudioTrack t2 = startTone(freq == 440.0 ? 880.0 : 440.0);
            System.out.println("TONE2 sid=" + t2.getAudioSessionId());
            AudioEffect fx2 = attach(name, t2.getAudioSessionId());
            describe(fx2, "ATTACH2");
            if (!op.equals("create")) { int rc = fx2.setEnabled(true); System.out.println("setEnabled2 rc=" + rc); }
            long e2 = System.currentTimeMillis() + bSecs * 1000L;
            while (System.currentTimeMillis() < e2) {
                Thread.sleep(1000);
                System.out.println("TICK2 A=" + t.getPlaybackHeadPosition()
                        + " B=" + t2.getPlaybackHeadPosition());
                System.out.flush();
            }
            t2.stop(); t2.release(); fx2.release();
        }
        stopped = true;
        if (t != null) { t.stop(); t.release(); }
        try { fx.release(); } catch (Throwable ignored) {}
        System.out.println("DONE");
        System.exit(0);
    }

    static void getq(String[] a) throws Exception {
        int session = Integer.parseInt(a[1]);
        int secs = a.length > 3 ? Integer.parseInt(a[3]) : 10;
        int paramId = Integer.parseInt(a[2]);
        AudioEffect fx = attach("jdsp", session);
        describe(fx, "GETQ session=" + session);
        byte[] val = new byte[4];
        for (int s = 0; s < secs; s++) {
            StringBuilder sb = new StringBuilder("Q wall=" + System.currentTimeMillis() + " t=" + s);
            int[] ids = paramId > 0
                    ? new int[]{paramId}
                    : new int[]{30010, 30011, 30012, 30013, 30014, 30018, 30019, 30020};
            for (int id : ids) {
                java.util.Arrays.fill(val, (byte) 0);
                int rc = getParam(fx, id, val);
                sb.append(" ").append(id).append("=").append(le32val(val)).append("(rc=").append(rc).append(")");
            }
            System.out.println(sb);
            System.out.flush();
            Thread.sleep(1000);
        }
        try { fx.release(); } catch (Throwable ignored) {}
        System.exit(0);
    }

    static void setf(String[] a) throws Exception {
        int session = Integer.parseInt(a[1]);
        int paramId = Integer.parseInt(a[2]);
        int n = a.length - 3;
        ByteBuffer vb = ByteBuffer.allocate(4 * n).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) vb.putFloat(Float.parseFloat(a[3 + i]));
        AudioEffect fx = attach("jdsp", session);
        describe(fx, "SETF session=" + session);
        int rc = setParam(fx, paramId, vb.array());
        System.out.println("setParameter(" + paramId + ", " + n + " floats) rc=" + rc
                + (rc == AudioEffect.SUCCESS ? " SUCCESS" : " FAILURE"));
        Thread.sleep(1000);
        try { fx.release(); } catch (Throwable ignored) {}
        System.exit(rc == AudioEffect.SUCCESS ? 0 : 5);
    }

    /** ctl <session> <enable|disable|release> — join an existing instance and act. */
    static void ctl(String[] a) throws Exception {
        int session = Integer.parseInt(a[1]);
        String op = a[2];
        AudioEffect fx;
        try {
            fx = attach("jdsp", session);
        } catch (Throwable attT) {
            System.out.println("ATTACH FAILED session=" + session + ": " + attT);
            Throwable c = attT.getCause();
            while (c != null) { System.out.println("  cause: " + c); c = c.getCause(); }
            System.exit(6);
            return;
        }
        describe(fx, "CTL " + op + " session=" + session);
        if (op.equals("enable")) {
            System.out.println("rc=" + fx.setEnabled(true));
        } else if (op.equals("disable")) {
            System.out.println("rc=" + fx.setEnabled(false));
        } else if (op.equals("release")) {
            fx.release();
            System.out.println("released");
        }
        Thread.sleep(300);
        try { fx.release(); } catch (Throwable ignored) {}
        System.exit(0);
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        if (mode.equals("matrix")) matrix(args);
        else if (mode.equals("getq")) getq(args);
        else if (mode.equals("setf")) setf(args);
        else if (mode.equals("ctl")) ctl(args);
        else { System.out.println("unknown mode " + mode); System.exit(1); }
    }
}
