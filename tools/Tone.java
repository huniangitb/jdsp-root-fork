import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

public class Tone {
    public static void main(String[] args) throws Exception {
        int sr = 48000;
        int secs = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sr)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();
        int minBuf = AudioTrack.getMinBufferSize(sr,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        System.out.println("SESSION=" + t.getAudioSessionId() + " minBuf=" + minBuf);
        t.play();
        short[] buf = new short[2048];
        double phase = 0.0;
        long total = (long) sr * secs;
        long written = 0;
        while (written < total) {
            for (int i = 0; i < 1024; i++) {
                short v = (short) (Math.sin(phase) * 8000);
                buf[2 * i] = v;
                buf[2 * i + 1] = v;
                phase += 2.0 * Math.PI * 440.0 / sr;
                if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI;
            }
            t.write(buf, 0, buf.length);
            written += 1024;
        }
        t.stop();
        t.release();
        System.out.println("DONE");
    }
}
