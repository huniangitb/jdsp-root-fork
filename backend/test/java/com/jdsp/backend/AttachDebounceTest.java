package com.jdsp.backend;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;

/**
 * Host tests for the short-session attach debounce (SessionWatcher): a session must
 * have been observed for at least ATTACH_DEBOUNCE_MS before an effect chain is built
 * for it; already-attached sessions are never affected.
 */
public final class AttachDebounceTest {
    private AttachDebounceTest() {}

    public static void run(Suite suite) {
        suite.test("debounce: never-seen session is not attachable", new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                Assert.eq(false, SessionWatcher.mayAttach(null, 1000L));
            }
        });
        suite.test("debounce: session younger than the window is not attachable", new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                long now = 100_000L;
                Assert.eq(false, SessionWatcher.mayAttach(Long.valueOf(now), now));
                Assert.eq(false, SessionWatcher.mayAttach(Long.valueOf(now), now + 1L));
                Assert.eq(false,
                        SessionWatcher.mayAttach(Long.valueOf(now), now + SessionWatcher.ATTACH_DEBOUNCE_MS - 1L));
            }
        });
        suite.test("debounce: session at/after the window is attachable", new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                long now = 100_000L;
                Assert.eq(true,
                        SessionWatcher.mayAttach(Long.valueOf(now), now + SessionWatcher.ATTACH_DEBOUNCE_MS));
                Assert.eq(true,
                        SessionWatcher.mayAttach(Long.valueOf(now), now + 10L * SessionWatcher.ATTACH_DEBOUNCE_MS));
            }
        });
        suite.test("debounce: window is 2000 ms", new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                Assert.eq(2000L, SessionWatcher.ATTACH_DEBOUNCE_MS);
            }
        });
    }
}
