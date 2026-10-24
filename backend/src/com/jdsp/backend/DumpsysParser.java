package com.jdsp.backend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code dumpsys media.audio_flinger} into a list of {@link SessionInfo}.
 *
 * <p>Deliberately free of Android dependencies (plain Java + java.util.regex) so it can be unit
 * tested on the host. Static and never throws: an unparsable dump yields an empty list, because
 * the daemon must survive ROM differences (MIUI already differs from AOSP in places).
 *
 * <p>Recovered structures (verified on Xiaomi xaga / Android 15):
 * <pre>
 *   2 Tracks of which 2 are active
 *     Type     Id Active Client Session Port Id S  Flags   Format ...
 *                62    yes  17432     153      36 A  0x000 ...
 *
 *     1 effects for session 153
 *        Effect ID 115:
 *          Session State Registered Internal Enabled Suspended:
 *          00153   003   y          n        y       n
 *          - UUID: f27317f4-c984-4de6-9a90-545759495bf2
 *
 *   Global session refs:
 *     session  cnt     pid    uid  name
 *         153    1   17432      0  root
 * </pre>
 */
public final class DumpsysParser {
    /** JamesDSP implementation UUID (audio_effects.xml, same value as JdspConst.EFFECT_UUID). */
    public static final String JDSP_UUID = "f27317f4-c984-4de6-9a90-545759495bf2";
    /** JamesDSP effect type UUID. */
    public static final String JDSP_TYPE = "f98765f4-c321-5de6-9a45-123459495ab2";
    /** audioserver uid: holds a ref for every session and is never the owner we want. */
    public static final int AUDIOSERVER_UID = 1041;

    /** "N effects for session X" starts the effect chain listing of one session. */
    private static final Pattern EFFECT_CHAIN =
            Pattern.compile("^\\s*(\\d+)\\s+effects?\\s+for\\s+session\\s+(\\d+)\\s*$");

    /** "Effect ID 115:" - one effect entry ("Effect ID 11 HAL dump:" must not match). */
    private static final Pattern EFFECT_ID =
            Pattern.compile("^\\s*Effect\\s+ID\\s+(\\d+):\\s*$");

    /** Introduces the five state columns of an effect. */
    private static final Pattern EFFECT_STATE_HEADER = Pattern.compile(
            "^\\s*Session\\s+State\\s+Registered\\s+Internal\\s+Enabled\\s+Suspended.*$");

    /** "00153   003   y          n        y       n": session state registered internal enabled suspended. */
    private static final Pattern EFFECT_STATE = Pattern.compile(
            "^\\s*(\\d+)\\s+(\\d+)\\s+([ynYN])\\s+([ynYN])\\s+([ynYN])\\s+([ynYN])\\s*$");

    private static final Pattern UUID_LINE = Pattern.compile(
            "^\\s*-\\s*UUID:\\s*([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\s*$");

    /** One track row: Id Active Client Session PortId S Flags Format ChnMask SRate ... */
    private static final Pattern TRACK_ROW = Pattern.compile(
            "^\\s+(?:\\S+\\s+)?(\\d+)\\s+(yes|no)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+[A-Za-z]{1,3}"
                    + "\\s+0x[0-9A-Fa-f]{3,8}\\s+[0-9A-Fa-f]{8}\\s+[0-9A-Fa-f]{8}\\s+\\d+");

    private static final String GLOBAL_REFS_HEADER = "Global session refs:";

    /** "    153    1   17432      0  root" */
    private static final Pattern REF_ROW = Pattern.compile(
            "^\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)\\s*$");

    private DumpsysParser() {
    }

    /** One row of the dump's "Global session refs" table. */
    public static final class SessionRef {
        public final int sessionId;
        public final int count;
        public final int pid;
        public final int uid;
        public final String name;

        SessionRef(int sessionId, int count, int pid, int uid, String name) {
            this.sessionId = sessionId;
            this.count = count;
            this.pid = pid;
            this.uid = uid;
            this.name = name == null ? "" : name;
        }
    }

    private static final Comparator<SessionInfo> SESSION_ORDER = new Comparator<SessionInfo>() {
        @Override
        public int compare(SessionInfo a, SessionInfo b) {
            return a.sessionId < b.sessionId ? -1 : (a.sessionId == b.sessionId ? 0 : 1);
        }
    };

    /** Parses a dump; never throws and never returns null. */
    public static List<SessionInfo> parse(String dump) {
        if (dump == null || dump.length() == 0) {
            return new ArrayList<SessionInfo>();
        }
        try {
            return doParse(dump);
        } catch (Throwable t) {
            return new ArrayList<SessionInfo>();
        }
    }

    private static List<SessionInfo> doParse(String dump) {
        Map<Integer, SessionInfo> sessions = new LinkedHashMap<Integer, SessionInfo>();
        Map<Integer, List<SessionRef>> refs = new LinkedHashMap<Integer, List<SessionRef>>();
        String[] lines = dump.split("\r?\n", -1);

        boolean inRefs = false;
        int chainSession = -1;
        int effectId = -1;
        String effectUuid = null;
        boolean expectState = false;
        boolean effectEnabled = false;
        boolean inEffect = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.length() == 0) {
                continue;
            }

            // ---- "Global session refs" table -------------------------------
            if (trimmed.equals(GLOBAL_REFS_HEADER)) {
                inRefs = true;
                continue;
            }
            if (inRefs) {
                if (trimmed.startsWith("session") && !REF_ROW.matcher(line).find()) {
                    continue; // "session  cnt  pid  uid  name" header line
                }
                Matcher rm = REF_ROW.matcher(line);
                if (rm.find()) {
                    SessionRef ref = new SessionRef(Integer.parseInt(rm.group(1)),
                            Integer.parseInt(rm.group(2)), Integer.parseInt(rm.group(3)),
                            Integer.parseInt(rm.group(4)), rm.group(5));
                    Integer key = Integer.valueOf(ref.sessionId);
                    List<SessionRef> list = refs.get(key);
                    if (list == null) {
                        list = new ArrayList<SessionRef>();
                        refs.put(key, list);
                    }
                    list.add(ref);
                    continue;
                }
                inRefs = false;
            }

            // ---- effect chain sections -------------------------------------
            Matcher cm = EFFECT_CHAIN.matcher(line);
            if (cm.find()) {
                finishEffect(sessions, chainSession, effectId, effectUuid, effectEnabled, inEffect);
                chainSession = Integer.parseInt(cm.group(2));
                effectId = -1;
                effectUuid = null;
                effectEnabled = false;
                expectState = false;
                inEffect = false;
                continue;
            }
            Matcher em = EFFECT_ID.matcher(line);
            if (em.find()) {
                finishEffect(sessions, chainSession, effectId, effectUuid, effectEnabled, inEffect);
                effectId = Integer.parseInt(em.group(1));
                effectUuid = null;
                effectEnabled = false;
                expectState = false;
                inEffect = true;
                continue;
            }
            if (inEffect) {
                if (EFFECT_STATE_HEADER.matcher(line).matches()) {
                    expectState = true;
                    continue;
                }
                if (expectState) {
                    Matcher sm = EFFECT_STATE.matcher(line);
                    expectState = false;
                    if (sm.find()) {
                        // session, state, registered, internal, enabled, suspended
                        effectEnabled = "y".equalsIgnoreCase(sm.group(5));
                        continue;
                    }
                }
                Matcher um = UUID_LINE.matcher(line);
                if (um.find()) {
                    effectUuid = um.group(1).toLowerCase(Locale.ROOT);
                }
                continue;
            }

            // ---- track table rows ------------------------------------------
            Matcher tm = TRACK_ROW.matcher(line);
            if (tm.find()) {
                int session = Integer.parseInt(tm.group(4));
                int pid = Integer.parseInt(tm.group(3));
                boolean active = "yes".equals(tm.group(2));
                SessionInfo info = session(sessions, session);
                if (pid > 0) {
                    info.pid = pid;
                }
                if (active) {
                    info.active = true;
                }
            }
        }
        finishEffect(sessions, chainSession, effectId, effectUuid, effectEnabled, inEffect);

        // ---- uid / owner name from the global session refs -----------------
        for (Map.Entry<Integer, List<SessionRef>> e : refs.entrySet()) {
            SessionInfo info = session(sessions, e.getKey().intValue());
            SessionRef best = pickRef(e.getValue(), info.pid);
            if (best != null) {
                info.uid = best.uid;
                info.refName = best.name;
                if (info.pid <= 0) {
                    info.pid = best.pid;
                }
            }
        }

        List<SessionInfo> out = new ArrayList<SessionInfo>(sessions.values());
        Collections.sort(out, SESSION_ORDER);
        return out;
    }

    private static SessionInfo session(Map<Integer, SessionInfo> sessions, int sessionId) {
        Integer key = Integer.valueOf(sessionId);
        SessionInfo info = sessions.get(key);
        if (info == null) {
            info = new SessionInfo(sessionId);
            sessions.put(key, info);
        }
        return info;
    }

    /**
     * A session normally has two refs: the owning client and audioserver. Prefer the ref matching
     * the track's client pid, then any ref that is not audioserver.
     */
    private static SessionRef pickRef(List<SessionRef> refs, int pid) {
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        if (pid > 0) {
            for (int i = 0; i < refs.size(); i++) {
                if (refs.get(i).pid == pid) {
                    return refs.get(i);
                }
            }
        }
        for (int i = 0; i < refs.size(); i++) {
            if (refs.get(i).uid != AUDIOSERVER_UID) {
                return refs.get(i);
            }
        }
        return refs.get(0);
    }

    /** Record a finished effect block when it is the JamesDSP instance of its session. */
    private static void finishEffect(Map<Integer, SessionInfo> sessions, int chainSession,
                                     int effectId, String uuid, boolean enabled, boolean inEffect) {
        if (!inEffect || chainSession < 0 || effectId < 0 || uuid == null) {
            return;
        }
        if (!uuid.equals(JDSP_UUID)) {
            return;
        }
        SessionInfo info = session(sessions, chainSession);
        info.effectId = effectId;
        info.jdspPresent = true;
        info.jdspEnabled = enabled;
    }
}
