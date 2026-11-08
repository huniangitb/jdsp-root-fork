package com.jdsp.backend;

/**
 * One AudioFlinger playback session as recovered from {@code dumpsys media.audio_flinger}.
 *
 * <p>This is a plain data holder on purpose: no Android or org.json dependency, so the parser
 * and its model can be unit tested on the host with plain javac.
 */
public final class SessionInfo {
    /** AudioFlinger session id (AudioTrack.getAudioSessionId()). */
    public int sessionId;

    /** Client pid that owns the track, 0 when unknown. */
    public int pid;

    /** Owning uid, resolved from the dump's global session refs when possible (-1 = unknown). */
    public int uid = -1;

    /** Package name for the uid ("" when unknown). */
    public String pkg = "";

    /** Name the dump itself reports for the session owner (process name, e.g. "root"). */
    public String refName = "";

    /** True when the session has at least one active track. */
    public boolean active;

    /** AudioFlinger effect id of the JamesDSP instance on this session, -1 when absent. */
    public int effectId = -1;

    /** True when the effect chain of this session already has a JamesDSP instance (framework postprocess). */
    public boolean jdspPresent;

    /** True when that existing JamesDSP instance reports "enabled". */
    public boolean jdspEnabled;

    /**
     * True when this row comes from the cheap probe view instead of a dump.
     *
     * <p>The probe knows the session ids but nothing about effect chains, so it cannot answer
     * "does the framework already own a JamesDSP instance here" - that is exactly the question
     * the auto mode turns on (see {@link PolicyEngine}). Such a row must never be used to
     * attach (it would create an instance the auto mode must not create) or to detach (it would
     * tear down a working framework instance on every cheap pass).
     */
    public boolean probeOnly;

    public SessionInfo() {
    }

    public SessionInfo(int sessionId) {
        this.sessionId = sessionId;
    }

    /** Short one line description used by logs and dumps. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("session=").append(sessionId);
        sb.append(" pid=").append(pid);
        sb.append(" uid=").append(uid);
        sb.append(" pkg=").append(pkg == null ? "" : pkg);
        sb.append(" active=").append(active);
        sb.append(" effect=").append(effectId);
        sb.append(" jdsp=").append(jdspPresent);
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
