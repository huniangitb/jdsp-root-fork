package com.jdsp.backend;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;
import com.jdsp.common.JdspConst;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Host tests for the auto-mode decision rule (v2.1.12).
 *
 * <p>Measured on ColorOS 16 before the fix: {@code jdsp cli attach 137} joined the framework
 * instance, and 1 ms later a cheap watcher pass detached it again with reason
 * "auto: no instance, not created" - the probe view cannot see effect chains, so
 * {@code jdspPresent} was always false there. The daemon therefore never held a handle in auto
 * mode and the App's parameters never reached the framework instance. The rule is: a cheap-view
 * row keeps whatever handle is held ({@link PolicyEngine.Decision#keep}) and only a dump may
 * attach or release.
 */
public final class AutoModeDecisionTest {
    private AutoModeDecisionTest() {}

    public static void run(Suite suite) {
        suite.test("auto mode: cheap-probe row keeps the handle (no attach, no detach)",
                new com.jdsp.cli.Body() {
            public void run() {
                PolicyEngine policy = policyWithMode(JdspConst.MODE_AUTO);
                SessionInfo info = new SessionInfo(137);
                info.uid = 10123;
                info.probeOnly = true;
                PolicyEngine.Decision d = policy.decide(info, configWithMode(JdspConst.MODE_AUTO));
                Assert.eq(true, d.keep);
                Assert.eq(false, d.attach);
                Assert.eq(false, d.excluded);
            }
        });
        suite.test("auto mode: a dump without an instance still means 'do not attach'",
                new com.jdsp.cli.Body() {
            public void run() {
                SessionInfo info = new SessionInfo(137);
                info.uid = 10123;
                info.probeOnly = false;
                info.jdspPresent = false;
                PolicyEngine.Decision d = policyWithMode(JdspConst.MODE_AUTO)
                        .decide(info, configWithMode(JdspConst.MODE_AUTO));
                Assert.eq(false, d.keep);
                Assert.eq(false, d.attach);
            }
        });
        suite.test("auto mode: a dump that lists the framework instance attaches (join)",
                new com.jdsp.cli.Body() {
            public void run() {
                SessionInfo info = new SessionInfo(137);
                info.uid = 10123;
                info.probeOnly = false;
                info.jdspPresent = true;
                info.effectId = 155;
                PolicyEngine.Decision d = policyWithMode(JdspConst.MODE_AUTO)
                        .decide(info, configWithMode(JdspConst.MODE_AUTO));
                Assert.eq(false, d.keep);
                Assert.eq(true, d.attach);
            }
        });
        suite.test("backend mode: a cheap-probe row attaches (the daemon creates the instance)",
                new com.jdsp.cli.Body() {
            public void run() {
                SessionInfo info = new SessionInfo(137);
                info.uid = 10123;
                info.probeOnly = true;
                PolicyEngine.Decision d = policyWithMode(JdspConst.MODE_BACKEND)
                        .decide(info, configWithMode(JdspConst.MODE_BACKEND));
                Assert.eq(false, d.keep);
                Assert.eq(true, d.attach);
            }
        });
        suite.test("auto mode: an excluded app is still released, not kept",
                new com.jdsp.cli.Body() {
            public void run() {
                ConfigStore cfg = configWithMode(JdspConst.MODE_AUTO);
                cfg.setPolicy("com.example.player", JdspConst.POLICY_EXCLUDE);
                SessionInfo info = new SessionInfo(137);
                info.uid = 10123;
                info.probeOnly = true;
                // The policy is looked up by the package the uid resolves to, so the engine needs
                // a package database that really maps 10123 to it.
                PolicyEngine policy = new PolicyEngine(
                        packagesFile("com.example.player", 10123));
                policy.refresh();
                PolicyEngine.Decision d = policy.decide(info, cfg);
                Assert.eq(true, d.excluded);
                Assert.eq(false, d.keep);
                Assert.eq(false, d.attach);
            }
        });
        suite.test("session 0 is never attached in either mode",
                new com.jdsp.cli.Body() {
            public void run() {
                SessionInfo zero = new SessionInfo(0);
                Assert.eq(false, policyWithMode(JdspConst.MODE_AUTO)
                        .decide(zero, configWithMode(JdspConst.MODE_AUTO)).attach);
                Assert.eq(false, policyWithMode(JdspConst.MODE_BACKEND)
                        .decide(zero, configWithMode(JdspConst.MODE_BACKEND)).attach);
            }
        });
        suite.test("mode effective: the injected marker is counted, not guessed",
                new com.jdsp.cli.Body() {
            public void run() {
                Assert.eq(0, ModeEffective.count("<postprocess/>"));
                Assert.eq(1, ModeEffective.count(
                        "<stream type=\"music\"><apply effect=\"jamesdsp\"/></stream>"));
                Assert.eq(2, ModeEffective.count(
                        "effect=\"jamesdsp\" effect=\"jamesdsp\""));
                Assert.eq(0, ModeEffective.count("effect=\"jamesdsp2\""));
                Assert.eq(0, ModeEffective.count(null));
            }
        });
        suite.test("mode effective: no mounted file means 'unknown', never a guess",
                new com.jdsp.cli.Body() {
            public void run() {
                // The host has no /vendor/etc/audio_effects.xml: the detector must say
                // "unknown" instead of picking a mode it cannot know.
                ModeEffective.reset();
                String got = ModeEffective.scan();
                Assert.eq("", got);
            }
        });
        suite.test("mode effective: a readable file without the marker means backend",
                new com.jdsp.cli.Body() {
            public void run() {
                File f = tempFile("<postprocess/>");
                Assert.eq("backend", ModeEffective.scanFiles(listOf(f)));
            }
        });
        suite.test("mode effective: a readable file with the marker means auto",
                new com.jdsp.cli.Body() {
            public void run() {
                File f = tempFile("<apply effect=\"jamesdsp\"/>");
                Assert.eq("auto", ModeEffective.scanFiles(listOf(f)));
            }
        });
    }

    private static PolicyEngine policyWithMode(int mode) {
        return new PolicyEngine(missingFile());
    }

    private static ConfigStore configWithMode(int mode) {
        ConfigStore cfg = new ConfigStore(missingFile());
        cfg.setMode(mode);
        return cfg;
    }

    private static File missingFile() {
        return new File(System.getProperty("java.io.tmpdir"),
                "jdsp-missing-" + System.nanoTime() + ".json");
    }

    private static java.util.List<File> listOf(File f) {
        java.util.List<File> out = new java.util.ArrayList<File>();
        out.add(f);
        return out;
    }

    private static File packagesFile(String pkg, int uid) {
        return tempFile(pkg + " " + uid + " 0 /data/user/0/" + pkg
                + " default:targetSdkVersion=34 3003 34\n");
    }

    private static File tempFile(String content) {
        try {
            File f = File.createTempFile("jdsp-mode", ".xml");
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(content.getBytes("UTF-8"));
            } finally {
                out.close();
            }
            return f;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
