package com.jdsp.backend;

/**
 * Coalescing background runner for one periodic action (the throttled config persist of the
 * hot parameter path). While a run is queued or executing, further requests do not stack up:
 * they collapse into a single follow-up run that picks up the state as of the run's end, so a
 * burst of writes costs at most two saves and a change made while a save is in flight is
 * never dropped.
 *
 * <p>Pure Java and host testable ({@code CoreApi} is not: it pulls in org.json); the action
 * itself is injected. One daemon thread, created lazily on the first request.
 */
final class AsyncPersister {
    private final Runnable action;
    private final Object lock = new Object();
    private java.util.concurrent.ExecutorService pool;
    /** A run is queued or executing (true from request() until the follow-up decision). */
    private boolean scheduled;
    /** At least one request arrived while a run was in flight: run once more afterwards. */
    private boolean rerun;

    AsyncPersister(Runnable action) {
        this.action = action;
    }

    /**
     * Schedules one run of the action. Never blocks on the action and never runs it on the
     * caller's thread; while a run is queued/executing, requests only mark the follow-up.
     */
    void request() {
        synchronized (lock) {
            if (scheduled) {
                rerun = true;
                return;
            }
            scheduled = true;
        }
        submit();
    }

    /** True while a run is queued or executing (diagnostics / tests). */
    boolean isBusy() {
        synchronized (lock) {
            return scheduled;
        }
    }

    private void submit() {
        pool().submit(new Runnable() {
            @Override
            public void run() {
                try {
                    action.run();
                } finally {
                    boolean again;
                    synchronized (lock) {
                        again = rerun;
                        rerun = false;
                        scheduled = again;
                    }
                    if (again) {
                        submit();
                    }
                }
            }
        });
    }

    private synchronized java.util.concurrent.ExecutorService pool() {
        if (pool == null) {
            pool = java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "jdspd-persist");
                            t.setDaemon(true);
                            return t;
                        }
                    });
        }
        return pool;
    }
}
