package com.jdsp.backend;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;

/**
 * Host tests for the adaptive short-session attach debounce (SessionWatcher): a brand new
 * session must have been observed for at least ATTACH_DEBOUNCE_FAST_MS (400 ms) before an
 * effect chain is built for it, and for the full ATTACH_DEBOUNCE_MS (2000 ms) while a
 * short-lived-session storm is active.  A session that has been observed for >= 2000 ms is
 * known to be stable and never debounced again (the master-on re-attach path).
 */
public final class AttachDebounceTest {
    private AttachDebounceTest() {}

    public static void run(Suite suite) {
        suite.test("debounce: never-seen session is not attachable", new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                Assert.eq(false, SessionWatcher.mayAttach(null, 1000L));
                Assert.eq(false, SessionWatcher.mayAttach(null, 1000L, true));
            }
        });
        suite.test("debounce: session younger than the fast window is not attachable",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                long now = 100_000L;
                Assert.eq(false, SessionWatcher.mayAttach(Long.valueOf(now), now));
                Assert.eq(false, SessionWatcher.mayAttach(Long.valueOf(now), now + 1L));
                Assert.eq(false, SessionWatcher.mayAttach(Long.valueOf(now),
                        now + SessionWatcher.ATTACH_DEBOUNCE_FAST_MS - 1L));
            }
        });
        suite.test("debounce: session at/after the fast window is attachable (no storm)",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                long now = 100_000L;
                Assert.eq(true, SessionWatcher.mayAttach(Long.valueOf(now),
                        now + SessionWatcher.ATTACH_DEBOUNCE_FAST_MS));
                Assert.eq(true, SessionWatcher.mayAttach(Long.valueOf(now),
                        now + 10L * SessionWatcher.ATTACH_DEBOUNCE_FAST_MS));
            }
        });
        suite.test("debounce: storm mode raises the window to 2000 ms", new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                long now = 100_000L;
                Assert.eq(false, SessionWatcher.mayAttach(Long.valueOf(now),
                        now + SessionWatcher.ATTACH_DEBOUNCE_FAST_MS, true));
                Assert.eq(false, SessionWatcher.mayAttach(Long.valueOf(now),
                        now + SessionWatcher.ATTACH_DEBOUNCE_MS - 1L, true));
                Assert.eq(true, SessionWatcher.mayAttach(Long.valueOf(now),
                        now + SessionWatcher.ATTACH_DEBOUNCE_MS, true));
            }
        });
        suite.test("debounce: a known session (>= 2000 ms) skips the debounce even in a storm",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                long now = 100_000L;
                Assert.eq(true, SessionWatcher.mayAttach(Long.valueOf(now),
                        now + SessionWatcher.ATTACH_DEBOUNCE_MS, true));
                Assert.eq(true, SessionWatcher.mayAttach(Long.valueOf(now),
                        now + 60_000L, true));
            }
        });
        suite.test("debounce: the long window stays 2000 ms and the fast window 400 ms",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                Assert.eq(2000L, SessionWatcher.ATTACH_DEBOUNCE_MS);
                Assert.eq(400L, SessionWatcher.ATTACH_DEBOUNCE_FAST_MS);
            }
        });
        suite.test("storm: fewer deaths than the threshold stays calm", new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                Assert.eq(false, SessionWatcher.stormActive(0));
                Assert.eq(false, SessionWatcher.stormActive(SessionWatcher.STORM_DEATHS - 1));
            }
        });
        suite.test("storm: the threshold (5 short-lived deaths) activates storm mode",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                Assert.eq(true, SessionWatcher.stormActive(SessionWatcher.STORM_DEATHS));
                Assert.eq(true, SessionWatcher.stormActive(SessionWatcher.STORM_DEATHS + 10));
            }
        });
    }
}
