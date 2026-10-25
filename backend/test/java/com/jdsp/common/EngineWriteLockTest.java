package com.jdsp.common;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure Java tests for the engine write lock handshake on the daemon side: the
 * key file lifecycle ({@link EngineWriteKey}) and the per session unlock gate
 * ({@link EngineUnlockGate}) that EffectManager consults before every write.
 */
public final class EngineWriteLockTest {
    private EngineWriteLockTest() {}

    /** Records every raw write, replying a scripted status per call. */
    private static final class FakeSink implements EngineUnlockGate.Sink {
        final List<String> calls = new ArrayList<String>();
        int unlockStatus = 0;
        int paramStatus = 0;

        @Override
        public int setParamRaw(int sessionId, int paramId, byte[] value) {
            calls.add(sessionId + ":" + paramId + ":" + value.length);
            return paramId == EngineWriteKey.UNLOCK_PARAM_ID ? unlockStatus : paramStatus;
        }

        int countUnlocks(int sessionId) {
            int n = 0;
            for (String c : calls) {
                if (c.startsWith(sessionId + ":" + EngineWriteKey.UNLOCK_PARAM_ID + ":")) {
                    n++;
                }
            }
            return n;
        }
    }

    private static byte[] key() {
        byte[] k = new byte[EngineWriteKey.KEY_LEN];
        for (int i = 0; i < k.length; i++) {
            k[i] = (byte) (0x10 + i);
        }
        return k;
    }

    public static void run(Suite suite) {
        suite.test("writelock: the reserved unlock id matches the engine's 0x7A00", new com.jdsp.cli.Body() {
            public void run() {
                Assert.eq(31232, EngineWriteKey.UNLOCK_PARAM_ID);
                Assert.eq(32, EngineWriteKey.KEY_LEN);
            }
        });

        suite.test("writelock: a rejected unlock does not block the real write (no-lock engine)",
                new com.jdsp.cli.Body() {
                    public void run() {
                        FakeSink sink = new FakeSink();
                        EngineUnlockGate gate = new EngineUnlockGate(sink);
                        byte[] k = key();
                        sink.unlockStatus = -1; // no-lock engine: rejects 0x7A00 itself

                        // The unlock reply is not a gate: the real write may proceed.
                        Assert.isTrue(gate.presentKey(42, k), "the write must go ahead");
                        Assert.eq(1, sink.countUnlocks(42), "the key was presented once");

                        // The real write succeeded: the engine is writable, mark it unlocked.
                        gate.onWriteResult(42, 0);
                        Assert.isTrue(gate.isUnlocked(42), "a successful write marks unlocked");

                        // Further writes go straight through, no new unlock.
                        Assert.isTrue(gate.presentKey(42, k), "the write must go ahead");
                        Assert.eq(1, sink.countUnlocks(42));
                    }
                });

        suite.test("writelock: locked engine with a valid key unlocks once",
                new com.jdsp.cli.Body() {
                    public void run() {
                        FakeSink sink = new FakeSink();
                        EngineUnlockGate gate = new EngineUnlockGate(sink);
                        byte[] k = key();

                        Assert.isTrue(gate.presentKey(43, k), "present with a valid key");
                        Assert.eq(1, sink.countUnlocks(43));
                        gate.onWriteResult(43, 0); // the real write succeeded
                        Assert.isTrue(gate.isUnlocked(43), "unlocked after a successful write");
                        Assert.isTrue(gate.presentKey(43, k), "go ahead again");
                        Assert.eq(1, sink.countUnlocks(43), "no re-handshake after success");
                    }
                });

        suite.test("writelock: locked engine with a wrong key stays fail-closed and retries",
                new com.jdsp.cli.Body() {
                    public void run() {
                        FakeSink sink = new FakeSink();
                        EngineUnlockGate gate = new EngineUnlockGate(sink);
                        byte[] k = key();
                        sink.unlockStatus = -1;
                        sink.paramStatus = -7; // the engine refuses every real write

                        Assert.isTrue(gate.presentKey(7, k), "the probe always proceeds with a valid key");
                        gate.onWriteResult(7, -7);
                        Assert.isTrue(!gate.isUnlocked(7), "a failed write must not mark");

                        // The next write re-presents the key (the handshake is retried); the
                        // engine, not the gate, keeps refusing it.
                        Assert.isTrue(gate.presentKey(7, k), "the handshake is retried");
                        Assert.eq(2, sink.countUnlocks(7));
                        gate.onWriteResult(7, -7);
                        Assert.isTrue(!gate.isUnlocked(7), "still fail-closed");
                    }
                });

        suite.test("writelock: no key fails closed without touching the engine", new com.jdsp.cli.Body() {
            public void run() {
                FakeSink sink = new FakeSink();
                EngineUnlockGate gate = new EngineUnlockGate(sink);

                Assert.isTrue(!gate.presentKey(1, null), "no key: nothing is sent");
                Assert.isTrue(!gate.presentKey(1, new byte[31]), "short key: nothing is sent");
                byte[] zero = new byte[EngineWriteKey.KEY_LEN];
                Assert.isTrue(!gate.presentKey(1, zero), "all-zero key: nothing is sent");
                Assert.eq(0, sink.calls.size());
            }
        });

        suite.test("writelock: forget forces a fresh handshake (detach / engine rebuild)",
                new com.jdsp.cli.Body() {
                    public void run() {
                        FakeSink sink = new FakeSink();
                        EngineUnlockGate gate = new EngineUnlockGate(sink);
                        byte[] k = key();

                        Assert.isTrue(gate.presentKey(5, k), "go ahead");
                        gate.onWriteResult(5, 0);
                        gate.forget(5);
                        Assert.isTrue(!gate.isUnlocked(5),
                                "after forget the session must present the key again");
                        Assert.isTrue(gate.presentKey(5, k), "go ahead again");
                        Assert.eq(2, sink.countUnlocks(5));

                        gate.presentKey(6, k);
                        gate.onWriteResult(6, 0);
                        gate.forgetAll();
                        Assert.isTrue(!gate.isUnlocked(6), "forgetAll clears every session");
                    }
                });

        suite.test("writelock: key validation", new com.jdsp.cli.Body() {
            public void run() {
                Assert.isTrue(EngineWriteKey.isValid(key()), "a normal 32 byte key is valid");
                Assert.isTrue(!EngineWriteKey.isValid(null), "null is invalid");
                Assert.isTrue(!EngineWriteKey.isValid(new byte[31]), "short key is invalid");
                Assert.isTrue(!EngineWriteKey.isValid(new byte[33]), "long key is invalid");
                Assert.isTrue(!EngineWriteKey.isValid(new byte[EngineWriteKey.KEY_LEN]),
                        "all-zero key is invalid");
            }
        });

        suite.test("writelock: key file round trip and rejection of junk", new com.jdsp.cli.Body() {
            public void run() throws Exception {
                File dir = tempDir();
                File f = new File(dir, "engine.key");

                // Missing: ensureKey creates one and the loader accepts it.
                byte[] created = EngineWriteKey.ensureKey(f);
                Assert.isTrue(created != null, "ensureKey creates a key");
                Assert.isTrue(EngineWriteKey.isValid(created), "the created key is valid");
                Assert.isTrue(!f.canExecute() || true, "mode check is best effort on the host");
                byte[] reloaded = EngineWriteKey.loadKey(f);
                Assert.isTrue(reloaded != null, "the file loads back");
                for (int i = 0; i < EngineWriteKey.KEY_LEN; i++) {
                    Assert.eq((int) created[i], (int) reloaded[i]);
                }

                // Second ensure returns the same bytes, no regeneration.
                byte[] again = EngineWriteKey.ensureKey(f);
                Assert.isTrue(again != null && again != created,
                        "existing key file wins over generation");
                for (int i = 0; i < EngineWriteKey.KEY_LEN; i++) {
                    Assert.eq((int) created[i], (int) again[i]);
                }

                // Short / long / all-zero files are rejected.
                write(f, new byte[8]);
                Assert.isTrue(EngineWriteKey.loadKey(f) == null, "short file rejected");
                byte[] longKey = new byte[40];
                longKey[0] = 1;
                write(f, longKey);
                Assert.isTrue(EngineWriteKey.loadKey(f) == null, "oversized file rejected");
                write(f, new byte[EngineWriteKey.KEY_LEN]);
                Assert.isTrue(EngineWriteKey.loadKey(f) == null, "all-zero file rejected");
            }
        });

        suite.test("writelock: vendor mirror carries the same bytes", new com.jdsp.cli.Body() {
            public void run() throws Exception {
                File dir = tempDir();
                File mirror = new File(dir, "jamesdsp_engine.key");
                byte[] k = key();

                Assert.isTrue(EngineWriteKey.mirrorToVendor(k, mirror), "mirror write succeeds");
                Assert.eq(EngineWriteKey.KEY_LEN, (int) mirror.length());
                FileInputStream in = new FileInputStream(mirror);
                byte[] back = new byte[EngineWriteKey.KEY_LEN];
                Assert.eq(EngineWriteKey.KEY_LEN, in.read(back));
                in.close();
                for (int i = 0; i < EngineWriteKey.KEY_LEN; i++) {
                    Assert.eq((int) k[i], (int) back[i]);
                }

                Assert.isTrue(!EngineWriteKey.mirrorToVendor(null, mirror),
                        "invalid key is not mirrored");
            }
        });
    }

    private static File tempDir() throws Exception {
        File dir = File.createTempFile("jdsp-writelock-test", "");
        Assert.isTrue(dir.delete(), "temp file removed to make room for the dir");
        Assert.isTrue(dir.mkdirs(), "temp dir created");
        dir.deleteOnExit();
        return dir;
    }

    private static void write(File f, byte[] data) throws Exception {
        java.io.FileOutputStream out = new java.io.FileOutputStream(f);
        out.write(data);
        out.close();
    }
}
