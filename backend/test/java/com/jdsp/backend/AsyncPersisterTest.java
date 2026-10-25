package com.jdsp.backend;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Host tests for {@link AsyncPersister}, the background runner of the hot path config persist:
 * requests never run the action on the caller's thread, requests that arrive while a run is
 * queued/running coalesce into that run, and the action is re-scheduled after a run when new
 * requests arrived. This is what keeps a slider burst off the fsync (35-50 ms per save on the
 * target device) without changing what and how often is written.
 */
public final class AsyncPersisterTest {
    private AsyncPersisterTest() {}

    public static void run(Suite suite) {
        suite.test("async persist: the action never runs on the caller's thread",
                new com.jdsp.cli.Body() {
            public void run() throws Exception {
                final CountDownLatch done = new CountDownLatch(1);
                final Thread[] runner = new Thread[1];
                AsyncPersister p = new AsyncPersister(new Runnable() {
                    @Override
                    public void run() {
                        runner[0] = Thread.currentThread();
                        done.countDown();
                    }
                });
                p.request();
                Assert.isTrue(done.await(5L, TimeUnit.SECONDS), "action ran");
                Assert.isTrue(runner[0] != Thread.currentThread(),
                        "action ran off the caller thread");
            }
        });
        suite.test("async persist: requests arriving during a run coalesce into it",
                new com.jdsp.cli.Body() {
            public void run() throws Exception {
                final CountDownLatch started = new CountDownLatch(1);
                final CountDownLatch release = new CountDownLatch(1);
                final AtomicInteger runs = new AtomicInteger();
                AsyncPersister p = new AsyncPersister(new Runnable() {
                    @Override
                    public void run() {
                        runs.incrementAndGet();
                        started.countDown();
                        try {
                            release.await(5L, TimeUnit.SECONDS);
                        } catch (InterruptedException ignored) {
                        }
                    }
                });
                p.request();
                Assert.isTrue(started.await(5L, TimeUnit.SECONDS), "first run started");
                // A burst of requests while the first run is still executing: all coalesce.
                for (int i = 0; i < 50; i++) {
                    p.request();
                }
                Assert.isTrue(p.isBusy(), "marked busy while running");
                release.countDown();
                // The coalesced burst must produce exactly ONE follow-up run.
                long deadline = System.currentTimeMillis() + 5000L;
                while (runs.get() < 2 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10L);
                }
                Thread.sleep(100L);
                Assert.eq(2, runs.get(), "burst collapsed into one follow-up run");
            }
        });
        suite.test("async persist: a request after the run finished schedules a new run",
                new com.jdsp.cli.Body() {
            public void run() throws Exception {
                final AtomicInteger runs = new AtomicInteger();
                AsyncPersister p = new AsyncPersister(new Runnable() {
                    @Override
                    public void run() {
                        runs.incrementAndGet();
                    }
                });
                p.request();
                long deadline = System.currentTimeMillis() + 5000L;
                while (runs.get() < 1 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(5L);
                }
                p.request(); // not busy anymore: must run again
                deadline = System.currentTimeMillis() + 5000L;
                while (runs.get() < 2 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(5L);
                }
                Assert.eq(2, runs.get(), "second request produced a second run");
            }
        });
    }
}
