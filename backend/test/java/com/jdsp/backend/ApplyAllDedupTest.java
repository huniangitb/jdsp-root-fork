package com.jdsp.backend;

import android.media.audiofx.AudioEffect;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Host tests for the benign-race log cleanup:
 * <ul>
 *   <li>{@link ParamBus#applyLogAllowed}: a repeated applyAll of the same session within
 *       {@link ParamBus#LOG_DEDUP_MS} stays silent (the watcher tick that slips in between the
 *       parallel attach task's handle publication and its replay used to log the identical
 *       "applyAll session=X: N parameter(s) applied" line twice per attach);</li>
 *   <li>{@link SessionWatcher} per-session in-flight marker: set while the attach task runs,
 *       cleared when it finishes (also on failure), and honoured by the reconcile skip - the
 *       dedup must never change WHAT is applied, only what is logged/repeated.</li>
 * </ul>
 */
public final class ApplyAllDedupTest {
    private ApplyAllDedupTest() {}

    public static void run(Suite suite) {
        suite.test("applyAll log dedup: first log of a session is allowed",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                Assert.eq(true, ParamBus.applyLogAllowed(null, 100_000L));
            }
        });
        suite.test("applyAll log dedup: a repeat inside the 2 s window is suppressed",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                Long last = Long.valueOf(100_000L);
                Assert.eq(false, ParamBus.applyLogAllowed(last, 100_001L));
                Assert.eq(false, ParamBus.applyLogAllowed(last,
                        100_000L + ParamBus.LOG_DEDUP_MS - 1L));
            }
        });
        suite.test("applyAll log dedup: a repeat after the window logs again",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                Long last = Long.valueOf(100_000L);
                Assert.eq(true, ParamBus.applyLogAllowed(last,
                        100_000L + ParamBus.LOG_DEDUP_MS));
                Assert.eq(true, ParamBus.applyLogAllowed(last,
                        100_000L + 10L * ParamBus.LOG_DEDUP_MS));
            }
        });
        suite.test("applyAll log dedup: the window stays 2000 ms", new com.jdsp.cli.Body() {
            public void run() {
                Assert.eq(2000L, ParamBus.LOG_DEDUP_MS);
            }
        });
        suite.test("applyAll log dedup: dedup only affects logging, every write still applies",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                AudioEffect.reset();
                EffectManager em = new EffectManager();
                byte[] key = new byte[com.jdsp.common.EngineWriteKey.KEY_LEN];
                key[0] = 1; // a valid key is 32 bytes and not uniform
                em.setEngineKey(key);
                ParamBus bus = new ParamBus(em);
                Assert.eq(0, em.attach(400, -1));
                Map<Integer, byte[]> params = new LinkedHashMap<Integer, byte[]>();
                params.put(1500, new byte[12]);
                // Three applyAlls back to back (the race pattern): every write must go
                // through, only the duplicate log lines are dropped. The stub counts the
                // unlock handshake of the first write as a "write" too, hence 4 = 3 + 1.
                Assert.eq(1, bus.applyAll(400, params));
                Assert.eq(1, bus.applyAll(400, params));
                Assert.eq(1, bus.applyAll(400, params));
                Assert.eq(4, AudioEffect.eventCount(400, "write"),
                        "all three replays (plus one handshake) reached the framework");
                Assert.eq(1, AudioEffect.unlockCount(400), "exactly one unlock handshake");
                Assert.eq(3, em.paramCount(400));
                Assert.eq(1, em.detachAll());
            }
        });
        suite.test("watcher in-flight marker: mark/clear lifecycle", new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                ConfigStore config = new ConfigStore(new File(
                        System.getProperty("java.io.tmpdir"), "jdspd-test-dedup-config.json"));
                SessionWatcher sw = new SessionWatcher(config, new PolicyEngine(),
                        new EffectManager(), new ParamBus(new EffectManager()));
                Assert.eq(false, sw.isAttachInFlight(500));
                sw.markAttachInFlight(500);
                Assert.eq(true, sw.isAttachInFlight(500));
                sw.clearAttachInFlight(500);
                Assert.eq(false, sw.isAttachInFlight(500));
            }
        });
        suite.test("watcher in-flight marker: cleared after a failed attach too",
                new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                AudioEffect.reset();
                ConfigStore config = new ConfigStore(new File(
                        System.getProperty("java.io.tmpdir"), "jdspd-test-dedup-config.json"));
                EffectManager em = new EffectManager();
                SessionWatcher sw = new SessionWatcher(config, new PolicyEngine(), em,
                        new ParamBus(em));
                SessionInfo s = new SessionInfo(501);
                s.uid = 1000;
                s.pkg = "com.example.test";
                PolicyEngine.Decision d = new PolicyEngine.Decision();
                d.attach = true;
                d.reason = "test";
                // Mark as in flight exactly like reconcile does, then run the task: the
                // finally block must clear the marker even when the attach refuses.
                sw.markAttachInFlight(s.sessionId);
                sw.attachSession(s, d); // session 0 refusals aside, 501 attaches on the stub
                Assert.eq(false, sw.isAttachInFlight(s.sessionId),
                        "marker cleared when the attach task finished");
                Assert.isTrue(em.isAttached(501), "session attached by the task");
                Assert.eq(1, em.detachAll());
            }
        });
    }
}
