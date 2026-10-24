// JdspSession — one playback session as seen by jdspd.
package android.jdsp.backend;

parcelable JdspSession {
    /** AudioFlinger session id (the id apps get from AudioTrack.getAudioSessionId()). */
    int sessionId;
    /** Owning uid of the client pid that created the session. */
    int uid;
    /** Package name for uid ("" when unknown, e.g. root/audioserver). */
    String pkg;
    /** jdspd currently holds a control handle for this session. */
    boolean attached;
    /** The held effect instance reports enabled. */
    boolean enabled;
    /** Number of parameters jdspd applied to this session. */
    int paramCount;
    /** AudioFlinger effect id, -1 when unknown. */
    int effectId;
    /** Policy applied to this session: 0=follow global 1=force on 2=excluded. */
    int policyMode;
    /** Client pid that owns the track (for diagnostics). */
    int pid;
}
