package com.jdsp.backend;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;
import com.jdsp.common.EngineUnlockGate;
import com.jdsp.common.EngineWriteKey;

/**
 * Host tests for the handle lifecycle (mount / unload) against the AudioEffect test double in
 * test/java/android/media/audiofx: the global switch off must detach every handle (the
 * instances leave the chain and are destroyed), and a remount must present the engine key
 * again, so real-time writes work immediately after off -> on.
 */
public final class EffectManagerDetachTest {
    private EffectManagerDetachTest() {}

    private static byte[] key() {
        byte[] k = new byte[EngineWriteKey.KEY_LEN];
        for (int i = 0; i < k.length; i++) {
            k[i] = (byte) (0x10 + i); // must not be constant: isValid rejects all-equal keys
        }
        return k;
    }

    private static byte[] value(int v) {
        return EffectManager.le32(v);
    }

    public static void run(Suite suite) {
        suite.test("handles: detachAll removes every handle", new com.jdsp.cli.Body() {
            public void run() {
                Log.setLogcat(false);
                android.media.audiofx.AudioEffect.reset();
                EffectManager em = new EffectManager();
                em.setEngineKey(key());
                Assert.eq(0, em.attach(11, -1));
                Assert.eq(0, em.attach(12, -1));
                Assert.eq(0, em.attach(13, -1));
                Assert.eq(3, em.handleCount());
                Assert.eq(3, em.detachAll());
                Assert.eq(0, em.handleCount(), "no handle may survive the global off");
                Assert.isTrue(em.attachedSessions().isEmpty(), "no session attached");
                Assert.eq(0, em.detachAll(), "detaching twice is a no-op");
            }
        });

        suite.test("handles: detach forgets the unlock, the remount presents the key again",
                new com.jdsp.cli.Body() {
                    public void run() {
                        Log.setLogcat(false);
                        android.media.audiofx.AudioEffect.reset();
                        EffectManager em = new EffectManager();
                        em.setEngineKey(key());
                        Assert.eq(0, em.attach(21, -1));
                        Assert.eq(0, em.setParam(21, 1, value(42)));
                        Assert.eq(0, em.setParam(21, 2, value(43)));
                        Assert.eq(1, android.media.audiofx.AudioEffect.unlockCount(21),
                                "exactly one unlock for the first mount");
                        // Global off: the handle (and with it the instance) is gone.
                        Assert.eq(1, em.detachAll());
                        // Global on: the watcher remounts; the new instance is locked again.
                        Assert.eq(0, em.attach(21, -1));
                        Assert.eq(0, em.setParam(21, 3, value(44)));
                        Assert.eq(2, android.media.audiofx.AudioEffect.unlockCount(21),
                                "the remount must present the key exactly once more");
                        Assert.isTrue(
                                android.media.audiofx.AudioEffect.hasDataWrite(21, 3, value(44)),
                                "the write after remount reached the engine");
                        Assert.isTrue(em.isUnlocked(21),
                                "no leftover locked state after the remount write");
                    }
                });

        suite.test("handles: high frequency writes go straight through after one unlock",
                new com.jdsp.cli.Body() {
                    public void run() {
                        Log.setLogcat(false);
                        android.media.audiofx.AudioEffect.reset();
                        EffectManager em = new EffectManager();
                        em.setEngineKey(key());
                        Assert.eq(0, em.attach(31, -1));
                        for (int i = 0; i < 100; i++) {
                            Assert.eq(0, em.setParam(31, 100 + i, value(i)));
                        }
                        Assert.eq(1, android.media.audiofx.AudioEffect.unlockCount(31),
                                "one unlock, not one per write");
                        Assert.eq(101, android.media.audiofx.AudioEffect.writeCount(31),
                                "1 unlock + 100 data writes, nothing dropped");
                        Assert.isTrue(
                                android.media.audiofx.AudioEffect.hasDataWrite(31, 199,
                                        value(99)),
                                "the last write of the burst arrived");
                    }
                });

        suite.test("handles: writes fail closed without a handle or without a key",
                new com.jdsp.cli.Body() {
                    public void run() {
                        Log.setLogcat(false);
                        android.media.audiofx.AudioEffect.reset();
                        EffectManager em = new EffectManager();
                        Assert.eq(EffectManager.ERR_NO_HANDLE, em.setParam(41, 1, value(1)));
                        Assert.eq(0, em.detach(41), "detaching an unknown session is a no-op");
                        em.setEngineKey(null);
                        Assert.eq(0, em.attach(41, -1));
                        Assert.eq(EngineUnlockGate.ERR_NO_KEY, em.setParam(41, 1, value(1)),
                                "without a key every write fails closed");
                        Assert.eq(0, android.media.audiofx.AudioEffect.writeCount(41),
                                "no write reached the engine");
                    }
                });

        suite.test("handles: detach disables before it releases",
                new com.jdsp.cli.Body() {
                    public void run() {
                        Log.setLogcat(false);
                        android.media.audiofx.AudioEffect.reset();
                        EffectManager em = new EffectManager();
                        Assert.eq(0, em.attach(51, -1));
                        Assert.eq(0, em.setEnabled(51, true));
                        Assert.eq(0, em.detach(51));
                        int disable = android.media.audiofx.AudioEffect
                                .firstEventIndex(51, "disable");
                        int release = android.media.audiofx.AudioEffect
                                .firstEventIndex(51, "release");
                        Assert.isTrue(disable >= 0, "detach must disable the instance");
                        Assert.isTrue(release >= 0, "detach must release the instance");
                        Assert.isTrue(disable < release,
                                "the disable (immediate bypass) must precede the release");
                        Assert.eq(1, android.media.audiofx.AudioEffect.eventCount(51, "release"),
                                "released exactly once");
                    }
                });

        suite.test("handles: no-lock engine - rejected unlock does not block the writes",
                new com.jdsp.cli.Body() {
                    public void run() {
                        Log.setLogcat(false);
                        android.media.audiofx.AudioEffect.reset();
                        // Engine b6e1fc1f form: the reserved unlock id 0x7A00 is rejected.
                        android.media.audiofx.AudioEffect.unlockReply = -22;
                        EffectManager em = new EffectManager();
                        em.setEngineKey(key());
                        Assert.eq(0, em.attach(71, -1));
                        Assert.eq(0, em.setParam(71, 1, value(11)),
                                "the real write succeeds despite the rejected unlock");
                        Assert.eq(1, android.media.audiofx.AudioEffect.unlockCount(71),
                                "the key was presented once");
                        Assert.eq(2, android.media.audiofx.AudioEffect.writeCount(71),
                                "1 unlock + 1 real write");
                        // Now unlocked: further writes go straight through.
                        Assert.eq(0, em.setParam(71, 2, value(12)));
                        Assert.eq(1, android.media.audiofx.AudioEffect.unlockCount(71),
                                "no new unlock after a successful write");
                        Assert.isTrue(em.isUnlocked(71), "unlocked after the successful write");
                    }
                });

        suite.test("handles: locked engine with a wrong key - writes blocked, handshake retried",
                new com.jdsp.cli.Body() {
                    public void run() {
                        Log.setLogcat(false);
                        android.media.audiofx.AudioEffect.reset();
                        // Locked engine, wrong key: the unlock and every real write are
                        // refused by the engine itself (fail closed).
                        android.media.audiofx.AudioEffect.unlockReply = -22;
                        android.media.audiofx.AudioEffect.writeReply = -7;
                        EffectManager em = new EffectManager();
                        em.setEngineKey(key());
                        Assert.eq(0, em.attach(72, -1));
                        Assert.eq(-7, em.setParam(72, 1, value(21)),
                                "the engine's refusal is reported");
                        Assert.isTrue(!em.isUnlocked(72), "a refused write must not mark");
                        Assert.eq(-7, em.setParam(72, 2, value(22)));
                        Assert.eq(2, android.media.audiofx.AudioEffect.unlockCount(72),
                                "each write retries the handshake");
                        Assert.eq(4, android.media.audiofx.AudioEffect.writeCount(72),
                                "2 unlock + 2 refused real writes");
                    }
                });

        suite.test("handles: detaching an already disabled instance skips the disable call",
                new com.jdsp.cli.Body() {
                    public void run() {
                        Log.setLogcat(false);
                        android.media.audiofx.AudioEffect.reset();
                        EffectManager em = new EffectManager();
                        Assert.eq(0, em.attach(52, -1));
                        // The fresh instance reports enabled=false: the framework must not be
                        // called for the disable (idempotence rule), only for the release.
                        Assert.eq(0, em.detach(52));
                        Assert.eq(0, android.media.audiofx.AudioEffect.eventCount(52, "disable"),
                                "no redundant disable call");
                        Assert.eq(1, android.media.audiofx.AudioEffect.eventCount(52, "release"),
                                "still released");
                    }
                });

        suite.test("handles: detachAll keeps the disable-before-release order",
                new com.jdsp.cli.Body() {
                    public void run() {
                        Log.setLogcat(false);
                        android.media.audiofx.AudioEffect.reset();
                        EffectManager em = new EffectManager();
                        Assert.eq(0, em.attach(61, -1));
                        Assert.eq(0, em.attach(62, -1));
                        Assert.eq(0, em.setEnabled(61, true));
                        Assert.eq(0, em.setEnabled(62, true));
                        Assert.eq(2, em.detachAll());
                        for (int sessionId = 61; sessionId <= 62; sessionId++) {
                            int disable = android.media.audiofx.AudioEffect
                                    .firstEventIndex(sessionId, "disable");
                            int release = android.media.audiofx.AudioEffect
                                    .firstEventIndex(sessionId, "release");
                            Assert.isTrue(disable >= 0 && disable < release,
                                    "session " + sessionId + ": disable precedes release");
                        }
                    }
                });
    }
}
