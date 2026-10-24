package com.jdsp.backend;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Polls {@code dumpsys media.audio_flinger} once per second, resolves sessions to (pid, uid, pkg)
 * and reconciles the effect handles: new sessions get attached (and their configured parameters
 * replayed plus {@code setEnabled(true)}), vanished or excluded ones are released.
 *
 * <p>The shell command runs through {@code sh -c} with a hard 3 s watchdog; a hung dumpsys can
 * never wedge the daemon (the next tick simply tries again).
 */
public final class SessionWatcher {
    /** Poll period. */
    public static final long POLL_MS = 1000L;
    /** Hard timeout for one dumpsys invocation. */
    public static final long DUMPSYS_TIMEOUT_MS = 3000L;
    private static final int MAX_DUMP_BYTES = 8 * 1024 * 1024;
    private static final String DUMPSYS_CMD = "dumpsys media.audio_flinger";

    private final ConfigStore config;
    private final PolicyEngine policy;
    private final EffectManager effects;
    private final ParamBus bus;
    /** Optional: keeps the App's endpoint files alive across App reinstalls. */
    private TcpService tcp;

    /** How often the App's endpoint files (port/token) are re-checked. */
    public static final long ENDPOINT_REFRESH_MS = 30000L;

    private final Object wakeLock = new Object();
    private volatile boolean running;
    private volatile boolean wake;
    private Thread thread;

    private volatile List<SessionInfo> sessions = new ArrayList<SessionInfo>();
    private volatile long ticks;
    private volatile long failures;
    private volatile long lastTickAt;
    private volatile int lastDumpBytes;
    private volatile String lastError = "";
    private long lastEndpointCheck;

    public SessionWatcher(ConfigStore config, PolicyEngine policy, EffectManager effects,
                          ParamBus bus) {
        this.config = config;
        this.policy = policy;
        this.effects = effects;
        this.bus = bus;
    }

    /** Lets the watcher re-create the App's port/token files when they disappear. */
    public void setTcpService(TcpService tcp) {
        this.tcp = tcp;
    }

    public void start() {
        if (thread != null) {
            return;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "jdspd-watcher");
        thread.setDaemon(true);
        thread.start();
        Log.i("session watcher started (poll " + POLL_MS + "ms)");
    }

    public void stop() {
        running = false;
        poke();
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(2000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Makes the watcher run a pass immediately (used after policy/mode changes). */
    public void poke() {
        synchronized (wakeLock) {
            wake = true;
            wakeLock.notifyAll();
        }
    }

    private void loop() {
        while (running) {
            try {
                pollOnce();
            } catch (Throwable t) {
                failures++;
                lastError = EffectManager.describe(t);
                Log.e("watcher tick failed: " + lastError, t);
            }
            synchronized (wakeLock) {
                if (!running) {
                    break;
                }
                if (!wake) {
                    try {
                        wakeLock.wait(POLL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                wake = false;
            }
        }
        Log.i("session watcher stopped");
    }

    /** Runs exactly one watcher pass (also used by --selftest). */
    public void pollOnce() {
        long t0 = System.currentTimeMillis();
        String dump = runDumpsys();
        ticks++;
        lastTickAt = t0;
        if (dump == null) {
            failures++;
            config.saveIfDirty();
            return;
        }
        lastDumpBytes = dump.length();
        List<SessionInfo> list = DumpsysParser.parse(dump);
        policy.refresh();
        for (int i = 0; i < list.size(); i++) {
            SessionInfo s = list.get(i);
            if (s.uid < 0 && s.pid > 0) {
                s.uid = uidOfPid(s.pid);
            }
            if (s.pkg == null || s.pkg.length() == 0) {
                s.pkg = policy.resolvePkg(s);
            }
        }
        sessions = list;
        reconcile(list);
        config.saveIfDirty();
        refreshEndpointFiles();
    }

    /**
     * Re-creates the App's endpoint files when they are missing (the App was uninstalled or
     * reinstalled, which wipes /data/data/&lt;pkg&gt;/files). Without this the App could not
     * connect until the daemon restarted.
     */
    private void refreshEndpointFiles() {
        TcpService service = tcp;
        if (service == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastEndpointCheck < ENDPOINT_REFRESH_MS) {
            return;
        }
        lastEndpointCheck = now;
        try {
            service.refreshEndpointFiles();
        } catch (Throwable t) {
            Log.w("endpoint file refresh failed: " + EffectManager.describe(t));
        }
    }

    /** Runs the dumpsys command with a watchdog. Returns null when it failed. */
    private String runDumpsys() {
        Process process = null;
        Thread killer = null;
        try {
            process = new ProcessBuilder("sh", "-c", DUMPSYS_CMD)
                    .redirectErrorStream(true).start();
            final Process target = process;
            killer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(DUMPSYS_TIMEOUT_MS);
                        target.destroyForcibly();
                    } catch (InterruptedException ignored) {
                        // finished in time
                    }
                }
            }, "jdspd-dumpsys-watchdog");
            killer.setDaemon(true);
            killer.start();

            StringBuilder sb = new StringBuilder(64 * 1024);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"), 16 * 1024);
            String line;
            int total = 0;
            while ((line = reader.readLine()) != null) {
                total += line.length() + 1;
                if (total > MAX_DUMP_BYTES) {
                    lastError = "dumpsys output too large";
                    Log.w(lastError);
                    break;
                }
                sb.append(line).append('\n');
            }
            process.waitFor();
            lastError = "";
            return sb.toString();
        } catch (Throwable t) {
            lastError = "dumpsys failed: " + EffectManager.describe(t);
            Log.w(lastError);
            return null;
        } finally {
            if (killer != null) {
                killer.interrupt();
            }
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
        }
    }

    /** Attaches / releases handles so that the live set matches policy and config. */
    private void reconcile(List<SessionInfo> list) {
        Set<Integer> present = new HashSet<Integer>();
        for (int i = 0; i < list.size(); i++) {
            SessionInfo s = list.get(i);
            present.add(Integer.valueOf(s.sessionId));
            PolicyEngine.Decision d = policy.decide(s, config);
            if (d.attach) {
                if (!effects.isAttached(s.sessionId)) {
                    int rc = effects.attach(s.sessionId, s.effectId);
                    if (rc == EffectManager.OK) {
                        effects.setOwner(s.sessionId, s.uid, s.pkg, s.pid);
                        bus.applyAll(s.sessionId, d.params);
                        effects.setEnabled(s.sessionId, true);
                        Log.i("attached session=" + s.sessionId + " uid=" + s.uid
                                + " pkg=" + s.pkg + " (" + d.reason + ")");
                    } else {
                        Log.w("attach session=" + s.sessionId + " failed rc=" + rc);
                    }
                } else {
                    effects.setOwner(s.sessionId, s.uid, s.pkg, s.pid);
                }
            } else if (effects.isAttached(s.sessionId)) {
                effects.setEnabled(s.sessionId, false);
                effects.detach(s.sessionId);
                Log.i("detached session=" + s.sessionId + " pkg=" + s.pkg + " (" + d.reason + ")");
            }
        }
        List<Integer> held = effects.attachedSessions();
        for (int i = 0; i < held.size(); i++) {
            Integer id = held.get(i);
            if (!present.contains(id)) {
                effects.detach(id.intValue());
                Log.i("released session=" + id + " (no longer present)");
            }
        }
    }

    /** uid of a pid: /proc/<pid> is owned by the process' real uid. */
    static int uidOfPid(int pid) {
        if (pid <= 0) {
            return -1;
        }
        try {
            return android.system.Os.stat("/proc/" + pid).st_uid;
        } catch (Throwable ignored) {
            // fall through to the status file
        }
        byte[] raw = FileUtil.readAll(new File("/proc/" + pid + "/status"), 64 * 1024);
        if (raw == null) {
            return -1;
        }
        try {
            String text = new String(raw, "UTF-8");
            String[] lines = text.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.startsWith("Uid:")) {
                    String[] parts = line.substring(4).trim().split("\\s+");
                    return Integer.parseInt(parts[0]);
                }
            }
        } catch (Throwable ignored) {
            // unknown
        }
        return -1;
    }

    /** Last parsed session list (a snapshot, never null). */
    public List<SessionInfo> snapshot() {
        return sessions;
    }

    public long ticks() {
        return ticks;
    }

    public long failures() {
        return failures;
    }

    public long lastTickAt() {
        return lastTickAt;
    }

    public int lastDumpBytes() {
        return lastDumpBytes;
    }

    public String lastError() {
        return lastError;
    }

    /** Applies parameters to every attached session (used by loadPreset). */
    public int reapplyAll() {
        int applied = 0;
        List<SessionInfo> list = sessions;
        for (int i = 0; i < list.size(); i++) {
            SessionInfo s = list.get(i);
            if (effects.isAttached(s.sessionId)) {
                applied += bus.applyAll(s.sessionId, config.paramsFor(s.pkg));
            }
        }
        return applied;
    }
}
