package com.jdsp.cli;

/**
 * One playback session as printed by {@code jdspcli sessions}.
 *
 * Plain data holder; {@link Client} maps the AIDL parcelable onto it so the
 * formatting layer stays host-testable.
 */
public final class SessionRow {
    private final int sessionId;
    private final int uid;
    private final String pkg;
    private final boolean attached;
    private final boolean enabled;
    private final int paramCount;
    private final int effectId;
    private final int policyMode;
    private final int pid;

    public SessionRow(int sessionId, int uid, String pkg, boolean attached, boolean enabled,
                      int paramCount, int effectId, int policyMode, int pid) {
        this.sessionId = sessionId;
        this.uid = uid;
        this.pkg = pkg;
        this.attached = attached;
        this.enabled = enabled;
        this.paramCount = paramCount;
        this.effectId = effectId;
        this.policyMode = policyMode;
        this.pid = pid;
    }

    public int sessionId() {
        return sessionId;
    }

    public int uid() {
        return uid;
    }

    public String pkg() {
        return pkg;
    }

    public boolean attached() {
        return attached;
    }

    public boolean enabled() {
        return enabled;
    }

    public int paramCount() {
        return paramCount;
    }

    public int effectId() {
        return effectId;
    }

    public int policyMode() {
        return policyMode;
    }

    public int pid() {
        return pid;
    }
}
