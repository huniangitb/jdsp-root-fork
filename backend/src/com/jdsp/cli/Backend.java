package com.jdsp.cli;

import java.util.List;

/**
 * The CLI's view of jdspd, one method per AIDL call.
 *
 * This interface is free of Android types so the command layer and its host
 * unit tests run on a plain JVM. {@link Client} is the binder implementation.
 */
public interface Backend {
    String getVersion() throws CliException;

    int ping() throws CliException;

    List<SessionRow> listSessions() throws CliException;

    SessionRow getSession(int sessionId) throws CliException;

    int attach(int sessionId) throws CliException;

    int detach(int sessionId) throws CliException;

    int setParam(int sessionId, int paramId, byte[] value) throws CliException;

    byte[] getParam(int sessionId, int paramId, int vsize) throws CliException;

    int commit(int sessionId) throws CliException;

    String dumpConfig() throws CliException;

    String dumpSession(int sessionId) throws CliException;

    int loadPreset(String path) throws CliException;

    int savePreset(String path) throws CliException;

    int setAppPolicy(String pkg, int mode) throws CliException;

    int getAppPolicy(String pkg) throws CliException;

    String listPolicies() throws CliException;

    int setMode(int mode) throws CliException;

    int getMode() throws CliException;

    /** Global master switch: false bypasses every session's effect. 0 = ok. */
    int setEnabled(boolean enabled) throws CliException;

    /** Current state of the global master switch. */
    boolean getEnabled() throws CliException;

    /**
     * Restores the canonical parameter baseline and broadcasts it to mounted sessions.
     * @return the number of engine writes applied (>= 0), negative on error
     */
    int applyBaseline(boolean clearPerApp) throws CliException;

    String stats() throws CliException;
}
