package com.jdsp.backend;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;
import com.jdsp.common.JdspConst;
import com.jdsp.common.WatcherPolicy;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Host tests for the mode decision (v2.1.13: the mode is a scope, not the insertion path).
 *
 * <p>History: v2.1.12 made {@code mode=auto} attach only sessions whose framework instance a dump
 * had seen, and a cheap probe row had to "keep" whatever handle was held. Measured on ColorOS 16
 * before that fix: {@code jdsp cli attach 137} joined the framework instance and a cheap pass
 * detached it 1 ms later, so parameters never reached the framework instance.
 *
 * <p>v2.1.13 removes that special case: which path inserts the effect is detected at runtime
 * ({@link ModeEffective}, i.e. whether the mounted audio_effects.xml carries the module marker),
 * and the mode only decides the scope - global ({@code auto}: everything is processed, the per app
 * blacklist is ignored because the App hides it) or per app ({@code backend}: the blacklist
 * applies). That is what makes switching modes a hot operation: the decision flips on the next
 * watcher tick, nothing has to be restarted and no chain has to be rebuilt.
 */
public final class AutoModeDecisionTest {
    private AutoModeDecisionTest() {}

    public static void run(Suite suite) {
        suite.test("global mode: a cheap-probe row is attachable (no keep state any more)",
                new com.jdsp.cli.Body() {
            public void run() {
                SessionInfo info = new SessionInfo(137);
                info.uid = 10123;
                info.probeOnly = true;
                PolicyEngine.Decision d = policyWithMode(JdspConst.MODE_AUTO)
                        .decide(info, configWithMode(JdspConst.MODE_AUTO));
                Assert.eq(true, d.attach);
                Assert.eq(false, d.excluded);
            }
        });
        suite.test("global mode: a dump without an instance is still attachable (join decides)",
                new com.jdsp.cli.Body() {
            public void run() {
                SessionInfo info = new SessionInfo(137);
                info.uid = 10123;
                info.jdspPresent = false;
                PolicyEngine.Decision d = policyWithMode(JdspConst.MODE_AUTO)
                        .decide(info, configWithMode(JdspConst.MODE_AUTO));
                Assert.eq(true, d.attach);
            }
        });
        suite.test("global mode: the per app blacklist is ignored (the App hides it)",
                new com.jdsp.cli.Body() {
            public void run() {
                ConfigStore cfg = configWithMode(JdspConst.MODE_AUTO);
                cfg.setPolicy("com.example.player", JdspConst.POLICY_EXCLUDE);
                SessionInfo info = new SessionInfo(137);
                info.uid = 10123;
                PolicyEngine policy = policyMapping("com.example.player", 10123);
                PolicyEngine.Decision d = policy.decide(info, cfg);
                Assert.eq(false, d.excluded);
                Assert.eq(true, d.attach);
                Assert.eq("global: blacklist ignored", d.reason);
            }
        });
        suite.test("per app mode: a blacklisted app is excluded (bypass), not attached",
                new com.jdsp.cli.Body() {
            public void run() {
                ConfigStore cfg = configWithMode(JdspConst.MODE_BACKEND);
                cfg.setPolicy("com.example.player", JdspConst.POLICY_EXCLUDE);
                SessionInfo info = new SessionInfo(137);
                info.uid = 10123;
                info.probeOnly = true;
                PolicyEngine.Decision d = policyMapping("com.example.player", 10123)
                        .decide(info, cfg);
                Assert.eq(true, d.excluded);
                Assert.eq(false, d.attach);
                Assert.eq("excluded by policy", d.reason);
            }
        });
        suite.test("per app mode: an unlisted app follows the global switch (attach)",
                new com.jdsp.cli.Body() {
            public void run() {
                SessionInfo info = new SessionInfo(137);
                info.uid = 10123;
                info.probeOnly = true;
                PolicyEngine.Decision d = policyWithMode(JdspConst.MODE_BACKEND)
                        .decide(info, configWithMode(JdspConst.MODE_BACKEND));
                Assert.eq(true, d.attach);
                Assert.eq("per app: follow global", d.reason);
            }
        });
        suite.test("switching the mode flips the decision for the same session and policy",
                new com.jdsp.cli.Body() {
            public void run() {
                // The hot switch in one assertion: same session, same blacklist entry, only the
                // mode changes - the daemon then bypasses (per app) or processes (global).
                ConfigStore cfg = configWithMode(JdspConst.MODE_AUTO);
                cfg.setPolicy("com.example.player", JdspConst.POLICY_EXCLUDE);
                SessionInfo info = new SessionInfo(137);
                info.uid = 10123;
                PolicyEngine policy = policyMapping("com.example.player", 10123);
                Assert.eq(true, policy.decide(info, cfg).attach);
                cfg.setMode(JdspConst.MODE_BACKEND);
                PolicyEngine.Decision after = policy.decide(info, cfg);
                Assert.eq(false, after.attach);
                Assert.eq(true, after.excluded);
                cfg.setMode(JdspConst.MODE_AUTO);
                Assert.eq(true, policy.decide(info, cfg).attach);
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
                Assert.eq("", ModeEffective.scan());
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
        modeParkLadder(suite);
    }

    private static void modeParkLadder(Suite suite) {
        suite.test("auto park ladder: first park is one second, then it doubles to 30 s",
                new com.jdsp.cli.Body() {
            public void run() {
                long ms = WatcherPolicy.nextAutoParkMs(0L);
                Assert.eq(WatcherPolicy.ATTACH_AUTO_PARK_START_MS, ms);
                Assert.eq(1000L, ms);
                ms = WatcherPolicy.nextAutoParkMs(ms);
                Assert.eq(2000L, ms);
                ms = WatcherPolicy.nextAutoParkMs(ms);
                Assert.eq(4000L, ms);
                long capped = WatcherPolicy.nextAutoParkMs(20000L);
                Assert.eq(WatcherPolicy.ATTACH_AUTO_PARK_MAX_MS, capped);
                Assert.eq(30000L, capped);
            }
        });
        suite.test("auto park ladder: a session without a framework instance waits, never retries per tick",
                new com.jdsp.cli.Body() {
            public void run() {
                // The chain of a brand new track appears a moment after the session, so the first
                // park must stay short; without any cap a stuck session would buy one dump per
                // second forever.
                Assert.eq(30000L, WatcherPolicy.nextAutoParkMs(30000L));
                Assert.eq(30000L, WatcherPolicy.nextAutoParkMs(300000L));
            }
        });
    }

    private static PolicyEngine policyWithMode(int mode) {
        return new PolicyEngine(missingFile());
    }

    private static PolicyEngine policyMapping(String pkg, int uid) {
        // The policy is looked up by the package the uid resolves to, so the engine needs a
        // package database that really maps the uid to it.
        PolicyEngine policy = new PolicyEngine(packagesFile(pkg, uid));
        policy.refresh();
        return policy;
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