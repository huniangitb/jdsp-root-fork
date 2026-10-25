// IJdspService — the jdspd control interface (design doc: docs/PLAN-v2.md §3.2).
package android.jdsp.backend;

import android.jdsp.backend.JdspSession;

interface IJdspService {
    /** Backend version string, e.g. "jdspd 2.0.0". */
    String getVersion();

    /** "ok" answers prove the daemon is alive. */
    int ping();

    // ---- sessions -------------------------------------------------------
    List<JdspSession> listSessions();
    JdspSession getSession(int sessionId);
    /** Create and keep an effect handle for the session. 0 = ok. */
    int attach(int sessionId);
    /** Release the handle. 0 = ok. */
    int detach(int sessionId);

    // ---- real time parameters ------------------------------------------
    /** Forward one SET_PARAM to the session's effect handle; returns the engine status (0 = applied). */
    int setParam(int sessionId, int paramId, in byte[] value);
    /** One GET_PARAM; returns the engine reply bytes (empty on error). */
    byte[] getParam(int sessionId, int paramId, int vsize);
    /** Flush parameters queued by setParamDeferred. 0 = ok. */
    int commit(int sessionId);

    // ---- configuration --------------------------------------------------
    /** Full configuration as JSON (global params + per app overrides + policy + mode). */
    String dumpConfig();
    /** Per session engine summary (sample rate, format, enabled, applied params). */
    String dumpSession(int sessionId);
    int loadPreset(String path);
    int savePreset(String path);

    // ---- policy ---------------------------------------------------------
    /** mode: 0=follow global 1=force on 2=excluded. */
    int setAppPolicy(String pkg, int mode);
    int getAppPolicy(String pkg);
    /** All policies as JSON. */
    String listPolicies();

    // ---- global mode and diagnostics -----------------------------------
    /** 0=auto (framework postprocess creates, jdspd attaches) 1=backend (jdspd creates). */
    int setMode(int mode);
    int getMode();

    // ---- global master switch ------------------------------------------
    /**
     * Master switch for the whole backend.
     *
     * <p>false: every session loses its effect (the daemon disables and releases the handles),
     * and parameter writes are rejected with a negative status until it is turned on again.
     * true: the daemon re-attaches the sessions it is allowed to touch.
     *
     * <p>This is a daemon side switch only: no engine parameter is written, so it works with
     * every engine build. 0 = ok, negative = error.
     */
    /** Runtime statistics as JSON. */
    String stats();

    // ---- global master switch (appended: the transaction numbers of the
    //      methods above stay stable) ---------------------------------------
    /**
     * Master switch for the whole backend.
     *
     * <p>false: every session loses its effect (the daemon disables and releases the handles),
     * and parameter writes are rejected with a negative status until it is turned on again.
     * true: the daemon re-attaches the sessions it is allowed to touch.
     *
     * <p>This is a daemon side switch only: no engine parameter is written, so it works with
     * every engine build. 0 = ok, negative = error.
     */
    int setEnabled(boolean enabled);
    /** Current state of the master switch. */
    boolean getEnabled();
}
