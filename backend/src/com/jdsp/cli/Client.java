package com.jdsp.cli;

import android.jdsp.backend.IJdspService;
import android.jdsp.backend.JdspSession;
import android.os.IBinder;
import android.os.RemoteException;

import com.jdsp.common.JdspConst;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Binder client for jdspd, plus the transport wiring.
 *
 * android.os.ServiceManager is a hidden API (not in the public android.jar), so
 * it is reached through reflection; IBinder and the AIDL stub are public. A
 * missing jdsp.service becomes a CliException with exit code 2, which makes
 * AutoTransport fall back to the loopback TCP channel.
 */
public final class Client implements Backend {

    /**
     * Production transports: binder first, loopback TCP (token handshake) as
     * fallback. {@code jdspcli --tcp [host:port]} forces TCP.
     */
    public static final TransportFactory TRANSPORTS = new AutoTransport(
            new AutoTransport.BinderOpener() {
                @Override
                public Backend open() throws CliException {
                    return Client.connect();
                }
            },
            new AutoTransport.TcpOpener() {
                @Override
                public Backend open(String endpoint) throws CliException {
                    return Client.connectTcp(endpoint);
                }
            });

    private final IJdspService service;

    private Client(IJdspService service) {
        this.service = service;
    }

    public static Client connect() throws CliException {
        IBinder binder = lookupBinder(JdspConst.SERVICE_NAME);
        if (binder == null) {
            throw new CliException(Exit.UNAVAILABLE, "jdspd is not running (service "
                    + JdspConst.SERVICE_NAME + " not found)");
        }
        if (!binder.pingBinder()) {
            throw new CliException(Exit.UNAVAILABLE, "jdspd is not responding (service "
                    + JdspConst.SERVICE_NAME + " is dead)");
        }
        IJdspService service = IJdspService.Stub.asInterface(binder);
        if (service == null) {
            throw new CliException(Exit.UNAVAILABLE, "service " + JdspConst.SERVICE_NAME
                    + " does not implement " + JdspConst.INTERFACE_DESCRIPTOR);
        }
        return new Client(service);
    }

    private static Backend connectTcp(String endpoint) throws CliException {
        return TcpBackend.open(endpoint);
    }

    private static IBinder lookupBinder(String name) {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getMethod("getService", String.class);
            Object binder = getService.invoke(null, name);
            return binder instanceof IBinder ? (IBinder) binder : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static CliException unavailable(String operation, RemoteException e) {
        return new CliException(Exit.UNAVAILABLE, "jdspd died while handling " + operation
                + ": " + e.getMessage(), e);
    }

    private static SessionRow row(JdspSession session) {
        return new SessionRow(session.sessionId, session.uid, session.pkg, session.attached,
                session.enabled, session.paramCount, session.effectId, session.policyMode,
                session.pid);
    }

    @Override
    public String getVersion() throws CliException {
        try {
            return service.getVersion();
        } catch (RemoteException e) {
            throw unavailable("getVersion", e);
        }
    }

    @Override
    public int ping() throws CliException {
        try {
            return service.ping();
        } catch (RemoteException e) {
            throw unavailable("ping", e);
        }
    }

    @Override
    public List<SessionRow> listSessions() throws CliException {
        try {
            List<JdspSession> sessions = service.listSessions();
            List<SessionRow> rows = new ArrayList<SessionRow>();
            if (sessions != null) {
                for (JdspSession session : sessions) {
                    if (session != null) {
                        rows.add(row(session));
                    }
                }
            }
            return rows;
        } catch (RemoteException e) {
            throw unavailable("listSessions", e);
        }
    }

    @Override
    public SessionRow getSession(int sessionId) throws CliException {
        try {
            JdspSession session = service.getSession(sessionId);
            return session == null ? null : row(session);
        } catch (RemoteException e) {
            throw unavailable("getSession", e);
        }
    }

    @Override
    public int attach(int sessionId) throws CliException {
        try {
            return service.attach(sessionId);
        } catch (RemoteException e) {
            throw unavailable("attach", e);
        }
    }

    @Override
    public int detach(int sessionId) throws CliException {
        try {
            return service.detach(sessionId);
        } catch (RemoteException e) {
            throw unavailable("detach", e);
        }
    }

    @Override
    public int setParam(int sessionId, int paramId, byte[] value) throws CliException {
        try {
            return service.setParam(sessionId, paramId, value);
        } catch (RemoteException e) {
            throw unavailable("setParam", e);
        }
    }

    @Override
    public byte[] getParam(int sessionId, int paramId, int vsize) throws CliException {
        try {
            return service.getParam(sessionId, paramId, vsize);
        } catch (RemoteException e) {
            throw unavailable("getParam", e);
        }
    }

    @Override
    public int commit(int sessionId) throws CliException {
        try {
            return service.commit(sessionId);
        } catch (RemoteException e) {
            throw unavailable("commit", e);
        }
    }

    @Override
    public String dumpConfig() throws CliException {
        try {
            return service.dumpConfig();
        } catch (RemoteException e) {
            throw unavailable("dumpConfig", e);
        }
    }

    @Override
    public String dumpSession(int sessionId) throws CliException {
        try {
            return service.dumpSession(sessionId);
        } catch (RemoteException e) {
            throw unavailable("dumpSession", e);
        }
    }

    @Override
    public int loadPreset(String path) throws CliException {
        try {
            return service.loadPreset(path);
        } catch (RemoteException e) {
            throw unavailable("loadPreset", e);
        }
    }

    @Override
    public int savePreset(String path) throws CliException {
        try {
            return service.savePreset(path);
        } catch (RemoteException e) {
            throw unavailable("savePreset", e);
        }
    }

    @Override
    public int setAppPolicy(String pkg, int mode) throws CliException {
        try {
            return service.setAppPolicy(pkg, mode);
        } catch (RemoteException e) {
            throw unavailable("setAppPolicy", e);
        }
    }

    @Override
    public int getAppPolicy(String pkg) throws CliException {
        try {
            return service.getAppPolicy(pkg);
        } catch (RemoteException e) {
            throw unavailable("getAppPolicy", e);
        }
    }

    @Override
    public String listPolicies() throws CliException {
        try {
            return service.listPolicies();
        } catch (RemoteException e) {
            throw unavailable("listPolicies", e);
        }
    }

    @Override
    public int setMode(int mode) throws CliException {
        try {
            return service.setMode(mode);
        } catch (RemoteException e) {
            throw unavailable("setMode", e);
        }
    }

    @Override
    public int getMode() throws CliException {
        try {
            return service.getMode();
        } catch (RemoteException e) {
            throw unavailable("getMode", e);
        }
    }

    @Override
    public String stats() throws CliException {
        try {
            return service.stats();
        } catch (RemoteException e) {
            throw unavailable("stats", e);
        }
    }
}
