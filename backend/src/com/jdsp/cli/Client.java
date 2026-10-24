package com.jdsp.cli;

import android.jdsp.backend.IJdspService;
import android.jdsp.backend.JdspSession;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.IBinder;
import android.os.RemoteException;

import com.jdsp.common.JdspConst;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Binder client for jdspd.
 *
 * android.os.ServiceManager is a hidden API (not in the public android.jar),
 * so it is reached through reflection; IBinder and the AIDL stub are public.
 * A missing jdsp.service becomes a CliException with exit code 2.
 */
public final class Client implements Backend {

    /** Read timeout for one socket request (daemon answers in milliseconds). */
    private static final int SOCKET_READ_TIMEOUT_MS = 2000;

    /**
     * Production transports: binder first, unix socket as fallback.
     * {@code jdspcli --socket [path]} forces the socket.
     */
    public static final TransportFactory TRANSPORTS = new AutoTransport(
            new AutoTransport.BinderOpener() {
                @Override
                public Backend open() throws CliException {
                    return Client.connect();
                }
            },
            new AutoTransport.SocketOpener() {
                @Override
                public Backend open(String path) throws CliException {
                    return Client.connectSocket(path);
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

    /** Connects to jdspd's filesystem unix socket (JSON lines protocol). */
    private static Backend connectSocket(String path) throws CliException {
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(path,
                    LocalSocketAddress.Namespace.FILESYSTEM));
            socket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
            return new SocketBackend(socket.getInputStream(), socket.getOutputStream(), path);
        } catch (IOException e) {
            closeQuietly(socket);
            throw new CliException(Exit.UNAVAILABLE,
                    "socket " + path + " unavailable: " + describe(e), e);
        }
    }

    private static void closeQuietly(LocalSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing useful to do while reporting the original failure
        }
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
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
