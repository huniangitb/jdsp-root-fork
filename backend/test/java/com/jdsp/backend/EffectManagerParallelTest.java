package com.jdsp.backend;

import android.media.audiofx.AudioEffect;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;

import java.util.concurrent.CountDownLatch;

/**
 * Host tests for the parallel handle lifecycle (the refactor that moved the expensive
 * AudioEffect constructor and the disable+release binder calls outside the EffectManager
 * monitor): concurrent attaches of different sessions must all succeed, a concurrent double
 * attach of the same session must produce exactly one handle, and detachAll must unload every
 * handle exactly once (each instance disabled before it is released).
 */
public final class EffectManagerParallelTest {
    private EffectManagerParallelTest() {}

    public static void run(Suite suite) {
        suite.test("parallel: attaching different sessions concurrently attaches all of them",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                AudioEffect.reset();
                final EffectManager em = new EffectManager();
                final int n = 8;
                final CountDownLatch ready = new CountDownLatch(n);
                final CountDownLatch go = new CountDownLatch(1);
                final int[] rcs = new int[n];
                Thread[] threads = new Thread[n];
                for (int i = 0; i < n; i++) {
                    final int idx = i;
                    threads[i] = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            ready.countDown();
                            try {
                                go.await();
                            } catch (InterruptedException ignored) {
                                return;
                            }
                            rcs[idx] = em.attach(100 + idx, -1);
                        }
                    }, "attach-" + i);
                    threads[i].start();
                }
                try {
                    ready.await();
                    go.countDown();
                    for (Thread t : threads) {
                        t.join(5000L);
                    }
                } catch (InterruptedException ignored) {
                    // fall through to the asserts
                }
                for (int i = 0; i < n; i++) {
                    Assert.eq(EffectManager.OK, rcs[i], "attach " + (100 + i) + " succeeded");
                    Assert.isTrue(em.isAttached(100 + i), "session " + (100 + i) + " attached");
                }
                Assert.eq(n, em.handleCount());
                Assert.eq(n, em.detachAll());
                Assert.eq(0, em.handleCount(), "everything unloaded after detachAll");
            }
        });

        suite.test("parallel: a same-session attach race yields exactly one handle",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                AudioEffect.reset();
                final EffectManager em = new EffectManager();
                final int n = 6;
                final CountDownLatch ready = new CountDownLatch(n);
                final CountDownLatch go = new CountDownLatch(1);
                final int[] rcs = new int[n];
                Thread[] threads = new Thread[n];
                for (int i = 0; i < n; i++) {
                    final int idx = i;
                    threads[i] = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            ready.countDown();
                            try {
                                go.await();
                            } catch (InterruptedException ignored) {
                                return;
                            }
                            rcs[idx] = em.attach(200, -1);
                        }
                    }, "attach-race-" + i);
                    threads[i].start();
                }
                try {
                    ready.await();
                    go.countDown();
                    for (Thread t : threads) {
                        t.join(5000L);
                    }
                } catch (InterruptedException ignored) {
                    // fall through to the asserts
                }
                int ok = 0;
                int busy = 0;
                for (int i = 0; i < n; i++) {
                    if (rcs[i] == EffectManager.OK) {
                        ok++;
                    } else if (rcs[i] == EffectManager.ERR_BUSY) {
                        busy++;
                    }
                }
                Assert.eq(n, ok + busy, "every racing attach answered OK or BUSY");
                Assert.eq(1, em.handleCount(), "exactly one handle for the session");
                Assert.eq(1, AudioEffect.eventCount(200, "construct"),
                        "the framework instance was created exactly once");
                Assert.eq(1, em.detachAll());
            }
        });

        suite.test("parallel: detachAll disables before releasing every instance, exactly once",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                AudioEffect.reset();
                EffectManager em = new EffectManager();
                for (int s = 300; s < 306; s++) {
                    Assert.eq(0, em.attach(s, -1));
                    Assert.eq(0, em.setEnabled(s, true));
                }
                Assert.eq(6, em.detachAll());
                for (int s = 300; s < 306; s++) {
                    int disable = AudioEffect.firstEventIndex(s, "disable");
                    int release = AudioEffect.firstEventIndex(s, "release");
                    Assert.isTrue(disable >= 0 && disable < release,
                            "session " + s + ": disable precedes release");
                    Assert.eq(1, AudioEffect.eventCount(s, "release"),
                            "session " + s + " released exactly once");
                    Assert.eq(1, AudioEffect.eventCount(s, "disable"),
                            "session " + s + " disabled exactly once");
                    Assert.isTrue(!em.isAttached(s), "session " + s + " gone");
                }
                Assert.eq(0, em.handleCount());
            }
        });
    }
}
