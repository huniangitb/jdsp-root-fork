package android.media.audiofx;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Host-side test double for the (hidden API) framework {@code AudioEffect}: only the surface
 * {@link com.jdsp.backend.EffectManager} uses, plus a recording of every SET_PARAM so the tests
 * can assert the unlock handshake of the engine write lock.
 *
 * <p>Compiled only into the test classpath (backend/test/run_tests.sh picks up every file under
 * backend/test/java); on the device the real framework class is used.
 */
public class AudioEffect {
    /** One recorded SET_PARAM call. */
    public static final class Write {
        public final int sessionId;
        public final int paramId;
        public final byte[] value;
        public final boolean unlock;

        Write(int sessionId, int paramId, byte[] value) {
            this.sessionId = sessionId;
            this.paramId = paramId;
            this.value = value;
            this.unlock = paramId == 31232; /* EngineWriteKey.UNLOCK_PARAM_ID */
        }
    }

    private static final List<Write> WRITES = new ArrayList<Write>();
    /** Ordered lifecycle events per session, "sessionId:kind" (enable/disable/write/release). */
    private static final List<String> EVENTS = new ArrayList<String>();
    /** Scripted engine replies (0 = accept; a no-lock engine rejects the unlock id). */
    public static int unlockReply = 0;
    public static int writeReply = 0;
    /**
     * Delay (ms) applied to every <b>data</b> SET_PARAM, simulating a slow binder round trip
     * (an impulse upload is 6-100 ms on the device). The unlock write is not delayed, so tests
     * can keep the harness setup cheap. Test-only knob.
     */
    public static volatile long writeDelayMs = 0;
    /** Framework state reads, so tests can prove the EffectManager cache works. */
    public static int enabledReads = 0;
    public static int controlReads = 0;
    private static int nextId = 1;

    private final int id;
    private final int sessionId;
    private boolean enabled;
    private boolean released;

    public AudioEffect(UUID type, UUID uuid, int priority, int sessionId) {
        this.id = nextId++;
        this.sessionId = sessionId;
        synchronized (EVENTS) {
            EVENTS.add(sessionId + ":construct");
        }
    }

    public int getId() {
        return id;
    }

    public boolean getEnabled() {
        synchronized (WRITES) {
            enabledReads++;
        }
        return enabled;
    }

    public boolean hasControl() {
        synchronized (WRITES) {
            controlReads++;
        }
        return !released;
    }

    public int setEnabled(boolean on) {
        enabled = on;
        synchronized (EVENTS) {
            EVENTS.add(sessionId + ":" + (on ? "enable" : "disable"));
        }
        return 0;
    }

    public void release() {
        released = true;
        synchronized (EVENTS) {
            EVENTS.add(sessionId + ":release");
        }
    }

    public int setParameter(byte[] param, byte[] value) {
        int paramId = le32(param);
        long delay = writeDelayMs;
        if (delay > 0 && paramId != 31232) { /* UNLOCK_PARAM_ID stays fast */
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (WRITES) {
            WRITES.add(new Write(sessionId, paramId, value));
        }
        synchronized (EVENTS) {
            EVENTS.add(sessionId + ":write");
        }
        return paramId == 31232 ? unlockReply : writeReply; /* UNLOCK_PARAM_ID */
    }

    public int getParameter(byte[] param, byte[] reply) {
        return 0;
    }

    // ---- test helpers --------------------------------------------------------

    public static void reset() {
        synchronized (WRITES) {
            WRITES.clear();
        }
        synchronized (EVENTS) {
            EVENTS.clear();
        }
        unlockReply = 0;
        writeReply = 0;
        writeDelayMs = 0;
        enabledReads = 0;
        controlReads = 0;
    }

    /**
     * Index of the first event of {@code kind} for {@code sessionId}, or -1. Combined with
     * {@link #lastEventIndex} this asserts call ordering on the framework surface.
     */
    public static int firstEventIndex(int sessionId, String kind) {
        synchronized (EVENTS) {
            String prefix = sessionId + ":" + kind;
            for (int i = 0; i < EVENTS.size(); i++) {
                if (EVENTS.get(i).equals(prefix)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Index of the last event of {@code kind} for {@code sessionId}, or -1. */
    public static int lastEventIndex(int sessionId, String kind) {
        synchronized (EVENTS) {
            String prefix = sessionId + ":" + kind;
            int found = -1;
            for (int i = 0; i < EVENTS.size(); i++) {
                if (EVENTS.get(i).equals(prefix)) {
                    found = i;
                }
            }
            return found;
        }
    }

    public static int eventCount(int sessionId, String kind) {
        synchronized (EVENTS) {
            int n = 0;
            String prefix = sessionId + ":" + kind;
            for (String e : EVENTS) {
                if (e.equals(prefix)) {
                    n++;
                }
            }
            return n;
        }
    }

    public static int writeCount(int sessionId) {
        synchronized (WRITES) {
            int n = 0;
            for (Write w : WRITES) {
                if (w.sessionId == sessionId) {
                    n++;
                }
            }
            return n;
        }
    }

    public static int unlockCount(int sessionId) {
        synchronized (WRITES) {
            int n = 0;
            for (Write w : WRITES) {
                if (w.sessionId == sessionId && w.unlock) {
                    n++;
                }
            }
            return n;
        }
    }

    /** True when a data write (not the unlock write) carried exactly these bytes. */
    public static boolean hasDataWrite(int sessionId, int paramId, byte[] value) {
        synchronized (WRITES) {
            for (Write w : WRITES) {
                if (w.sessionId == sessionId && !w.unlock && w.paramId == paramId
                        && java.util.Arrays.equals(w.value, value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int le32(byte[] b) {
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16)
                | ((b[3] & 0xFF) << 24);
    }
}
