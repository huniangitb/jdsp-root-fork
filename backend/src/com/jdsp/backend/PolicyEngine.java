package com.jdsp.backend;

import com.jdsp.common.JdspConst;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps uids to packages (/data/system/packages.list) and decides what to do with a session.
 *
 * <p>Decision inputs: the per app policy (follow / force on / exclude), the global enable flag
 * and the architecture mode:
 * <ul>
 *   <li>{@code auto}: only attach to sessions that already carry a JamesDSP instance created by
 *       the framework postprocess, so jdspd never creates a second one.</li>
 *   <li>{@code backend}: attach every eligible session, creating the instance when missing.</li>
 * </ul>
 * Session 0 (the global mix) is never attached: the framework applies effects per session.
 */
public final class PolicyEngine {
    /** Package database maintained by PackageManagerService (root readable). */
    public static final String PACKAGES_LIST = "/data/system/packages.list";
    private static final long REFRESH_MS = 30000L;
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private final File packagesList;
    private final Map<Integer, String> uidToPkg = new LinkedHashMap<Integer, String>();
    private final Map<String, Integer> pkgToUid = new LinkedHashMap<String, Integer>();
    private final Map<Integer, List<String>> uidToPkgs = new LinkedHashMap<Integer, List<String>>();
    private long loadedAt;
    private String lastError = "";

    public PolicyEngine() {
        this(new File(PACKAGES_LIST));
    }

    public PolicyEngine(File packagesList) {
        this.packagesList = packagesList;
    }

    /** One decision for one session. */
    public static final class Decision {
        /** True when jdspd should hold an effect handle for the session. */
        public boolean attach;
        /** True when the policy forbids any effect for this session (must be released). */
        public boolean excluded;
        /**
         * True when the current view cannot answer the attach question yet: keep whatever
         * handle is held and attach nothing new (auto mode, cheap-probe row).
         */
        public boolean keep;
        /** Effective policy mode (0 follow / 1 on / 2 exclude). */
        public int policyMode = JdspConst.POLICY_FOLLOW;
        /** Human readable reason, for logs and stats. */
        public String reason = "";
        /** Parameters to (re)apply when the handle is created or the policy changed. */
        public Map<Integer, byte[]> params = new LinkedHashMap<Integer, byte[]>();
    }

    /** Refreshes the uid -> package map when it is older than 30 s. */
    public synchronized void refresh() {
        long now = System.currentTimeMillis();
        if (now - loadedAt < REFRESH_MS) {
            return;
        }
        loadedAt = now;
        refreshLocked();
    }

    public synchronized void reload() {
        loadedAt = 0L;
        refresh();
    }

    private void refreshLocked() {
        byte[] raw = FileUtil.readAll(packagesList, MAX_BYTES);
        if (raw == null) {
            lastError = "cannot read " + packagesList;
            Log.w("policy: " + lastError);
            return;
        }
        Map<Integer, String> u2p = new LinkedHashMap<Integer, String>();
        Map<String, Integer> p2u = new LinkedHashMap<String, Integer>();
        Map<Integer, List<String>> u2ps = new LinkedHashMap<Integer, List<String>>();
        int count = 0;
        try {
            String text = new String(raw, "UTF-8");
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.length() == 0) {
                    continue;
                }
                // pkg uid debuggable datadir seinfo [gids] targetSdk
                String[] parts = line.split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                int uid;
                try {
                    uid = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    continue;
                }
                String pkg = parts[0];
                if (pkg.length() == 0) {
                    continue;
                }
                Integer key = Integer.valueOf(uid);
                if (!u2p.containsKey(key)) {
                    u2p.put(key, pkg);
                }
                p2u.put(pkg, key);
                List<String> list = u2ps.get(key);
                if (list == null) {
                    list = new ArrayList<String>();
                    u2ps.put(key, list);
                }
                list.add(pkg);
                count++;
            }
        } catch (Throwable t) {
            lastError = "parse: " + t;
            Log.w("policy: " + lastError);
            return;
        }
        uidToPkg.clear();
        uidToPkg.putAll(u2p);
        pkgToUid.clear();
        pkgToUid.putAll(p2u);
        uidToPkgs.clear();
        uidToPkgs.putAll(u2ps);
        lastError = "";
        Log.i("policy: " + count + " package entries, " + uidToPkg.size() + " uids");
    }

    /** Package owning {@code uid}, "" when unknown. */
    public synchronized String pkgForUid(int uid) {
        String pkg = uidToPkg.get(Integer.valueOf(uid));
        return pkg == null ? "" : pkg;
    }

    public synchronized int uidForPkg(String pkg) {
        if (pkg == null) {
            return -1;
        }
        Integer uid = pkgToUid.get(pkg);
        return uid == null ? -1 : uid.intValue();
    }

    public synchronized int packageCount() {
        return uidToPkg.size();
    }

    public synchronized String lastError() {
        return lastError;
    }

    /**
     * Resolves the package of a session: the dump's owner name wins when it is a real package of
     * that uid (that also covers shared uids, where packages.list has several entries), then the
     * first package of the uid.
     */
    public synchronized String resolvePkg(SessionInfo info) {
        if (info == null) {
            return "";
        }
        String ref = info.refName == null ? "" : info.refName;
        if (ref.length() > 0) {
            Integer uid = pkgToUid.get(ref);
            if (uid != null && (info.uid < 0 || uid.intValue() == info.uid)) {
                return ref;
            }
        }
        if (info.uid < 0) {
            return "";
        }
        String pkg = uidToPkg.get(Integer.valueOf(info.uid));
        return pkg == null ? "" : pkg;
    }

    /** Decides whether a session gets a handle and with which parameters. */
    public Decision decide(SessionInfo info, ConfigStore cfg) {
        Decision d = new Decision();
        if (info == null) {
            d.reason = "no info";
            return d;
        }
        if (info.sessionId <= 0) {
            d.reason = "global session 0 is never attached";
            return d;
        }
        String pkg = resolvePkg(info);
        info.pkg = pkg;
        int policy = cfg.getPolicy(pkg);
        d.policyMode = policy;
        d.params = cfg.paramsFor(pkg);

        if (policy == JdspConst.POLICY_EXCLUDE) {
            d.attach = false;
            d.excluded = true;
            d.reason = "excluded by policy";
            return d;
        }
        if (policy == JdspConst.POLICY_ON) {
            d.attach = true;
            d.reason = "forced on by policy";
            return d;
        }
        // follow global
        if (cfg.getMode() == JdspConst.MODE_BACKEND) {
            d.attach = true;
            d.reason = "backend mode";
            return d;
        }
        if (info.probeOnly) {
            // Auto mode turns on info.jdspPresent, and only a dump can answer it. Deriving it
            // from the cheap view (always false there) made jdspd attach and detach the very
            // same handle within 1 ms - measured on ColorOS 16: the daemon never held a handle
            // in auto mode and parameters never reached the framework instance.
            d.keep = true;
            d.reason = "auto: cheap view, handle kept";
            return d;
        }
        d.attach = info.jdspPresent;
        d.reason = info.jdspPresent ? "auto: framework instance present" : "auto: no instance, not created";
        return d;
    }
}
