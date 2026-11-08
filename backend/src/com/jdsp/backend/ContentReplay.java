package com.jdsp.backend;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the content parameters that are forwarded to the engine but never written to
 * {@code config.json} - the LiveProg script ({@code 8888} alloc, {@code 12001} chunks,
 * {@code 10010} compile, {@code 25002}, DDC/ArbEQ blobs) - so they can be replayed into the next
 * session that gets a handle.
 *
 * <p><b>Why.</b> Those values are not persistable (a 27th parameter in {@code dump-config} broke
 * the baseline, and runtime diagnostics must not be replayed on daemon start), so a script pushed
 * while nothing was playing had nowhere to go: v2.1.12 reported {@code -1002} and dropped it, which
 * is honest but means the user has to press "run" again while music plays. With a replay log the
 * write is accepted and applied as soon as a session appears, which is what "every parameter is
 * hot" means: no restart, no re-send.
 *
 * <p><b>Order matters.</b> The engine validates the script in the order it receives it
 * ({@code jamesdsp.c}: {@code 8888} allocates the buffer, {@code 12001} appends the chunks,
 * {@code 10010} compiles), so entries are kept in arrival order, and {@code 8888} - which starts a
 * new script - clears everything recorded before it.
 *
 * <p><b>Bounded.</b> Recording stops at {@link #MAX_TOTAL_BYTES} / {@link #MAX_ENTRIES} and logs
 * once: an impulse response is megabytes, and silently growing the daemon's heap for it would be
 * worse than asking the user to re-send while playing.
 */
public final class ContentReplay {
    /** A single value larger than this is content (impulse response), not a script chunk. */
    private static final int MAX_VALUE_BYTES = 256 * 1024;
    /** Total budget of the log: a 32 KB EEL script is 128 chunks of 256 bytes. */
    private static final int MAX_TOTAL_BYTES = 1024 * 1024;
    private static final int MAX_ENTRIES = 8192;
    /** First parameter of a LiveProg transfer: everything before it belongs to the previous run. */
    static final int PARAM_ALLOC = 8888;

    private static final ContentReplay INSTANCE = new ContentReplay();

    private static final class Entry {
        final int id;
        final byte[] value;

        Entry(int id, byte[] value) {
            this.id = id;
            this.value = value;
        }
    }

    private final List<Entry> entries = new ArrayList<Entry>();
    private int bytes;
    private long replays;
    private boolean truncated;

    private ContentReplay() {
    }

    public static ContentReplay get() {
        return INSTANCE;
    }

    /** Where a replay goes; {@code ParamBus} adapts to it, tests can pass a recorder. */
    public interface Sink {
        int setParam(int sessionId, int paramId, byte[] value);
    }

    /** Records one non-persistable content write (called on the request thread). */
    public synchronized void record(int paramId, byte[] value) {
        if (value == null || value.length == 0) {
            return;
        }
        if (value.length > MAX_VALUE_BYTES) {
            Log.i("content replay: param " + paramId + " is " + value.length
                    + " bytes, too large to remember (re-send it while a session is mounted)");
            return;
        }
        if (paramId == PARAM_ALLOC) {
            // A new transfer: the previous script is superseded even if it never played.
            entries.clear();
            bytes = 0;
            truncated = false;
        }
        if (entries.size() >= MAX_ENTRIES || bytes + value.length > MAX_TOTAL_BYTES) {
            if (!truncated) {
                truncated = true;
                Log.w("content replay: log full (" + entries.size() + " entries, " + bytes
                        + " bytes), further content parameters are forwarded only");
            }
            return;
        }
        byte[] copy = new byte[value.length];
        System.arraycopy(value, 0, copy, 0, value.length);
        entries.add(new Entry(paramId, copy));
        bytes += copy.length;
    }

    /**
     * Applies the log to one session, in the recorded order. Called right after the configured
     * parameters were replayed on attach, so a session that starts later still gets the script.
     */
    public synchronized void replay(Sink sink, int sessionId) {
        if (entries.isEmpty() || sink == null) {
            return;
        }
        int failed = 0;
        int ok = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            int rc = sink.setParam(sessionId, e.id, e.value);
            if (rc == 0) {
                ok++;
            } else {
                failed++;
            }
        }
        replays++;
        if (failed == 0) {
            Log.i("content replay: " + ok + " value(s) into session=" + sessionId);
        } else {
            Log.w("content replay: " + ok + " ok, " + failed + " failed for session=" + sessionId);
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized int bytes() {
        return bytes;
    }

    public synchronized long replays() {
        return replays;
    }

    public synchronized void clear() {
        entries.clear();
        bytes = 0;
        truncated = false;
    }
}