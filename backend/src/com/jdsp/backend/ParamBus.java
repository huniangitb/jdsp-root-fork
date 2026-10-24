package com.jdsp.backend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Parameter path between the transports and {@link EffectManager}.
 *
 * <ul>
 *   <li>{@link #setParam} writes straight through to the effect handle (measured round trip
 *       ~3 ms on the device, no queueing) and returns the engine status.</li>
 *   <li>{@link #setParamDeferred} + {@link #commit} coalesce repeated writes of the same
 *       (session, paramId) inside a 2 ms window (last value wins) before touching the handle.
 *       Parameters that carry chunked transfers are never coalesced, so the App's ordered
 *       impulse/string chunks reach the engine unchanged.</li>
 * </ul>
 */
public final class ParamBus {
    /** Coalescing window. */
    public static final long BATCH_MS = 2L;
    /** Parameter ids that must never be merged or reordered. */
    private static final int[] NO_COALESCE = {
            8888, 9999, 12000, 12001, 10004, 10006, 10009, 10010, 25000, 25001, 25002, 25003
    };

    /** Runtime counters exposed through stats. */
    public static final class Stats {
        public long setCalls;
        public long getCalls;
        public long errors;
        public long batchFlushes;
        public long batchMerged;
        public long lastLatencyUs;
        public long totalLatencyUs;
        public long maxLatencyUs;
        public long bulkApplied;
        public long cacheHits;
        public long engineHits;
    }

    private static final class Pending {
        final int sessionId;
        final int paramId;
        final byte[] value;

        Pending(int sessionId, int paramId, byte[] value) {
            this.sessionId = sessionId;
            this.paramId = paramId;
            this.value = value;
        }
    }

    /** Upper bound of cached (session, param) values; the cache is dropped when exceeded. */
    private static final int MAX_CACHE_ENTRIES = 20000;

    private final EffectManager effects;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Pending> pending = new LinkedHashMap<String, Pending>();
    /**
     * Last value successfully written per (session, param). The clean JamesDSP engine does not
     * implement EFFECT_CMD_GET_PARAM (it returns -EINVAL), so a read is answered from here when
     * the engine has no reply.
     */
    private final Map<String, byte[]> lastValues = new LinkedHashMap<String, byte[]>();
    private final Stats stats = new Stats();
    private ScheduledFuture<?> flushTask;

    public ParamBus(EffectManager effects) {
        this.effects = effects;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "jdspd-parambus");
                t.setDaemon(true);
                return t;
            }
        });
    }

    /** Synchronous write, returns the engine status (0 = applied). */
    public int setParam(int sessionId, int paramId, byte[] value) {
        if (value == null || value.length == 0) {
            return EffectManager.ERR_BAD_ARG;
        }
        long t0 = System.nanoTime();
        int rc = effects.setParam(sessionId, paramId, value);
        long us = (System.nanoTime() - t0) / 1000L;
        if (rc == 0) {
            rememberValue(sessionId, paramId, value);
        }
        synchronized (stats) {
            stats.setCalls++;
            stats.lastLatencyUs = us;
            stats.totalLatencyUs += us;
            if (us > stats.maxLatencyUs) {
                stats.maxLatencyUs = us;
            }
            if (rc != 0) {
                stats.errors++;
            }
        }
        return rc;
    }

    /**
     * One GET_PARAM; the value is written into {@code reply}.
     *
     * <p>The engine is asked first. When it has no support for GET_PARAM (the clean v6.7.2
     * engine returns -EINVAL) or no handle answers, the last value that was successfully written
     * for this (session, param) is returned instead, so {@code get} reflects the last {@code set}.
     *
     * @return 0 when {@code reply} holds a value, negative otherwise
     */
    public int getParam(int sessionId, int paramId, byte[] reply) {
        long t0 = System.nanoTime();
        int rc = effects.getParam(sessionId, paramId, reply);
        long us = (System.nanoTime() - t0) / 1000L;
        boolean cached = false;
        if (rc != 0) {
            cached = readCached(sessionId, paramId, reply);
            if (cached) {
                rc = 0;
            }
        }
        synchronized (stats) {
            stats.getCalls++;
            stats.lastLatencyUs = us;
            stats.totalLatencyUs += us;
            if (us > stats.maxLatencyUs) {
                stats.maxLatencyUs = us;
            }
            if (cached) {
                stats.cacheHits++;
            } else if (rc == 0) {
                stats.engineHits++;
            } else {
                stats.errors++;
            }
        }
        return rc;
    }

    private void rememberValue(int sessionId, int paramId, byte[] value) {
        if (value.length > 64 * 1024) {
            return;
        }
        synchronized (lastValues) {
            if (lastValues.size() >= MAX_CACHE_ENTRIES) {
                lastValues.clear();
            }
            lastValues.put(sessionId + ":" + paramId, value.clone());
        }
    }

    private boolean readCached(int sessionId, int paramId, byte[] reply) {
        byte[] cached;
        synchronized (lastValues) {
            cached = lastValues.get(sessionId + ":" + paramId);
        }
        if (cached == null || cached.length == 0) {
            return false;
        }
        int n = Math.min(cached.length, reply.length);
        System.arraycopy(cached, 0, reply, 0, n);
        for (int i = n; i < reply.length; i++) {
            reply[i] = 0;
        }
        return true;
    }

    /** Drops the cached values of a session (called when its handle is released). */
    public void forgetSession(int sessionId) {
        String prefix = sessionId + ":";
        synchronized (lastValues) {
            java.util.Iterator<String> it = lastValues.keySet().iterator();
            while (it.hasNext()) {
                if (it.next().startsWith(prefix)) {
                    it.remove();
                }
            }
        }
    }

    /** Applies a whole parameter set to one session (used when a handle is created). */
    public int applyAll(int sessionId, Map<Integer, byte[]> params) {
        if (params == null || params.isEmpty()) {
            return 0;
        }
        int ok = 0;
        int failed = 0;
        for (Map.Entry<Integer, byte[]> e : params.entrySet()) {
            int rc = setParam(sessionId, e.getKey().intValue(), e.getValue());
            if (rc == 0) {
                ok++;
            } else {
                failed++;
            }
        }
        synchronized (stats) {
            stats.bulkApplied += ok;
        }
        if (failed > 0) {
            Log.w("applyAll session=" + sessionId + ": " + ok + " ok, " + failed + " failed");
        } else {
            Log.i("applyAll session=" + sessionId + ": " + ok + " parameter(s) applied");
        }
        return ok;
    }

    /**
     * Queues a write that is flushed after the {@link #BATCH_MS} window. Repeated writes of the
     * same (session, paramId) inside the window keep only the last value. Chunked parameters are
     * written straight through. Returns 0 when the write was accepted.
     */
    public int setParamDeferred(int sessionId, int paramId, byte[] value) {
        if (value == null || value.length == 0) {
            return EffectManager.ERR_BAD_ARG;
        }
        if (isNoCoalesce(paramId)) {
            return setParam(sessionId, paramId, value);
        }
        String key = sessionId + ":" + paramId;
        synchronized (pending) {
            if (pending.containsKey(key)) {
                synchronized (stats) {
                    stats.batchMerged++;
                }
            }
            pending.put(key, new Pending(sessionId, paramId, value));
            if (flushTask == null) {
                flushTask = scheduler.schedule(new Runnable() {
                    @Override
                    public void run() {
                        flushAll();
                    }
                }, BATCH_MS, TimeUnit.MILLISECONDS);
            }
        }
        return 0;
    }

    /** Flushes queued writes of one session (or everything when {@code sessionId <= 0}). */
    public int commit(int sessionId) {
        flush(sessionId);
        return 0;
    }

    /** Flushes every queued write; returns the number of engine writes performed. */
    public int flushAll() {
        return flush(0);
    }

    private int flush(int sessionId) {
        List<Pending> batch = new ArrayList<Pending>();
        synchronized (pending) {
            if (pending.isEmpty()) {
                flushTask = null;
                return 0;
            }
            if (sessionId <= 0) {
                batch.addAll(pending.values());
                pending.clear();
                flushTask = null;
            } else {
                String prefix = sessionId + ":";
                List<String> keys = new ArrayList<String>(pending.keySet());
                for (int i = 0; i < keys.size(); i++) {
                    String key = keys.get(i);
                    if (key.startsWith(prefix)) {
                        batch.add(pending.remove(key));
                    }
                }
                if (pending.isEmpty()) {
                    flushTask = null;
                }
            }
        }
        int n = 0;
        for (int i = 0; i < batch.size(); i++) {
            Pending p = batch.get(i);
            setParam(p.sessionId, p.paramId, p.value);
            n++;
        }
        synchronized (stats) {
            stats.batchFlushes++;
        }
        return n;
    }

    /** Current counters (a copy). */
    public Stats stats() {
        Stats copy = new Stats();
        synchronized (stats) {
            copy.setCalls = stats.setCalls;
            copy.getCalls = stats.getCalls;
            copy.errors = stats.errors;
            copy.batchFlushes = stats.batchFlushes;
            copy.batchMerged = stats.batchMerged;
            copy.lastLatencyUs = stats.lastLatencyUs;
            copy.totalLatencyUs = stats.totalLatencyUs;
            copy.maxLatencyUs = stats.maxLatencyUs;
            copy.bulkApplied = stats.bulkApplied;
            copy.cacheHits = stats.cacheHits;
            copy.engineHits = stats.engineHits;
        }
        return copy;
    }

    public long averageLatencyUs() {
        synchronized (stats) {
            long calls = stats.setCalls + stats.getCalls;
            return calls == 0 ? 0 : stats.totalLatencyUs / calls;
        }
    }

    public void shutdown() {
        try {
            scheduler.shutdownNow();
        } catch (Throwable ignored) {
            // nothing to do
        }
    }

    private static boolean isNoCoalesce(int paramId) {
        for (int i = 0; i < NO_COALESCE.length; i++) {
            if (NO_COALESCE[i] == paramId) {
                return true;
            }
        }
        return false;
    }
}
