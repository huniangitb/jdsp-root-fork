package com.jdsp.backend;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;
import com.jdsp.common.EngineWriteKey;

/**
 * Host tests for the per-session locking of {@link EffectManager} (v2.1.6 daemon).
 *
 * <p>The invariant that matters for latency: a slow write on one session must not serialise
 * writes of another session (before, one global monitor covered every reflective call), and a
 * detach must never release an instance while a write for it is still in flight.
 */
public final class EffectManagerConcurrencyTest {
    private EffectManagerConcurrencyTest() {}

    private static byte[] key() {
        byte[] k = new byte[EngineWriteKey.KEY_LEN];
        for (int i = 0; i < k.length; i++) {
            k[i] = (byte) (0x20 + i);
        }
        return k;
    }

    private static byte[] value(int v) {
        return EffectManager.le32(v);
    }

    public static void run(Suite suite) {
        suite.test("locks: a slow write on one session does not block another session",
                new com.jdsp.cli.Body() {
                    public void run() {
                        Log.setLogcat(false);
                        android.media.audiofx.AudioEffect.reset();
                        EffectManager em = new EffectManager();
                        em.setEngineKey(key());
                        Assert.eq(EffectManager.OK, em.attach(31, -1));
                        Assert.eq(EffectManager.OK, em.attach(32, -1));
                        android.media.audiofx.AudioEffect.writeDelayMs = 150L;
                        final EffectManager fm = em;
                        final int[] rc = new int[2];
                        final long[] doneAt = new long[2];
                        long t0 = System.currentTimeMillis();
                        Thread a = new Thread(new Runnable() {
                            public void run() {
                                rc[0] = fm.setParam(31, 1, value(1));
                                doneAt[0] = System.currentTimeMillis();
                            }
                        });
                        Thread b = new Thread(new Runnable() {
                            public void run() {
                                rc[1] = fm.setParam(32, 1, value(2));
                                doneAt[1] = System.currentTimeMillis();
                            }
                        });
                        a.start();
                        sleepQuietly(20L);
                        b.start();
                        joinQuietly(a);
                        joinQuietly(b);
                        android.media.audiofx.AudioEffect.writeDelayMs = 0L;
                        Assert.eq(EffectManager.OK, rc[0], "slow write applied");
                        Assert.eq(EffectManager.OK, rc[1], "other session's write applied");
                        long bWait = doneAt[1] - t0;
                        Assert.isTrue(bWait < 260L,
                                "second session finished after " + bWait
                                        + " ms (serialised behind the 150 ms write?)");
                        Assert.eq(2, em.detachAll(), "both handles released");
                        Assert.eq(0, em.handleCount());
                    }
                });

        suite.test("locks: detach waits for the in-flight write before releasing",
                new com.jdsp.cli.Body() {
                    public void run() {
                        Log.setLogcat(false);
                        android.media.audiofx.AudioEffect.reset();
                        EffectManager em = new EffectManager();
                        em.setEngineKey(key());
                        Assert.eq(EffectManager.OK, em.attach(41, -1));
                        android.media.audiofx.AudioEffect.writeDelayMs = 200L;
                        final EffectManager fm = em;
                        final int[] rc = new int[1];
                        Thread w = new Thread(new Runnable() {
                            public void run() {
                                rc[0] = fm.setParam(41, 1, value(7));
                            }
                        });
                        w.start();
                        sleepQuietly(50L);
                        long t0 = System.currentTimeMillis();
                        Assert.eq(EffectManager.OK, em.detach(41));
                        long detachMs = System.currentTimeMillis() - t0;
                        joinQuietly(w);
                        android.media.audiofx.AudioEffect.writeDelayMs = 0L;
                        Assert.eq(EffectManager.OK, rc[0], "the in-flight write completed");
                        Assert.isTrue(detachMs >= 100L,
                                "detach returned after only " + detachMs
                                        + " ms, i.e. it did not wait for the in-flight write");
                        int write = android.media.audiofx.AudioEffect.firstEventIndex(41, "write");
                        int release = android.media.audiofx.AudioEffect.firstEventIndex(41, "release");
                        Assert.isTrue(write >= 0 && release > write,
                                "release (" + release + ") must come after the write (" + write + ")");
                    }
                });

        suite.test("locks: a write while the session is closing fails fast with NO_HANDLE",
                new com.jdsp.cli.Body() {
                    public void run() {
                        Log.setLogcat(false);
                        android.media.audiofx.AudioEffect.reset();
                        EffectManager em = new EffectManager();
                        em.setEngineKey(key());
                        Assert.eq(EffectManager.OK, em.attach(51, -1));
                        android.media.audiofx.AudioEffect.writeDelayMs = 300L;
                        final EffectManager fm = em;
                        Thread w = new Thread(new Runnable() {
                            public void run() {
                                fm.setParam(51, 1, value(9));
                            }
                        });
                        w.start();
                        sleepQuietly(50L);
                        Thread d = new Thread(new Runnable() {
                            public void run() {
                                fm.detach(51);
                            }
                        });
                        d.start();
                        sleepQuietly(40L);
                        long t0 = System.currentTimeMillis();
                        int rc = em.setParam(51, 2, value(10));
                        long ms = System.currentTimeMillis() - t0;
                        joinQuietly(w);
                        joinQuietly(d);
                        android.media.audiofx.AudioEffect.writeDelayMs = 0L;
                        Assert.eq(EffectManager.ERR_NO_HANDLE, rc,
                                "a write to a closing session must report NO_HANDLE");
                        Assert.isTrue(ms < 100L,
                                "the rejected write blocked for " + ms + " ms");
                        Assert.isTrue(!em.isAttached(51), "no handle survives the detach");
                    }
                });

        runCacheTests(suite);
    }

    /**
     * State-cache tests: the watcher used to do two framework reads per handle per 500 ms tick;
     * after the TTL cache the same information costs two reads per second per handle and the
     * reads are never taken while a session write is running.
     */
    public static void runCacheTests(Suite suite) {
        suite.test("state cache: repeated reads inside the TTL do not touch the framework",
                new com.jdsp.cli.Body() {
                    public void run() {
                        Log.setLogcat(false);
                        android.media.audiofx.AudioEffect.reset();
                        EffectManager em = new EffectManager();
                        em.setEngineKey(key());
                        Assert.eq(EffectManager.OK, em.attach(61, -1));
                        // attach primes the cache (its log line reads both values anyway)
                        for (int i = 0; i < 20; i++) {
                            em.isEnabled(61);
                            em.hasControl(61);
                            em.state(61);
                            em.states();
                        }
                        Assert.isTrue(android.media.audiofx.AudioEffect.enabledReads <= 1,
                                "getEnabled read " + android.media.audiofx.AudioEffect.enabledReads
                                        + " times for 20 cached lookups");
                        Assert.isTrue(android.media.audiofx.AudioEffect.controlReads <= 1,
                                "hasControl read " + android.media.audiofx.AudioEffect.controlReads
                                        + " times for 20 cached lookups");
                        em.detachAll();
                    }
                });

        suite.test("state cache: setEnabled writes through and refreshes the cached value",
                new com.jdsp.cli.Body() {
                    public void run() {
                        Log.setLogcat(false);
                        android.media.audiofx.AudioEffect.reset();
                        EffectManager em = new EffectManager();
                        em.setEngineKey(key());
                        Assert.eq(EffectManager.OK, em.attach(62, -1));
                        Assert.eq(EffectManager.OK, em.setEnabled(62, true));
                        int before = android.media.audiofx.AudioEffect.enabledReads;
                        Assert.isTrue(em.isEnabled(62), "the cached value follows the write");
                        Assert.eq(before, android.media.audiofx.AudioEffect.enabledReads,
                                "no extra framework read after the write-through");
                        em.detachAll();
                    }
                });

        suite.test("state cache: invalidateState forces a fresh framework read",
                new com.jdsp.cli.Body() {
                    public void run() {
                        Log.setLogcat(false);
                        android.media.audiofx.AudioEffect.reset();
                        EffectManager em = new EffectManager();
                        em.setEngineKey(key());
                        Assert.eq(EffectManager.OK, em.attach(63, -1));
                        Assert.isTrue(!em.isEnabled(63), "a fresh handle starts disabled");
                        em.setEnabled(63, true);
                        int before = android.media.audiofx.AudioEffect.enabledReads;
                        em.invalidateState(63);
                        Assert.isTrue(em.isEnabled(63), "the invalidated read sees the framework");
                        Assert.isTrue(android.media.audiofx.AudioEffect.enabledReads > before,
                                "the invalidated lookup actually read the framework");
                        em.detachAll();
                    }
                });
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(5000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}