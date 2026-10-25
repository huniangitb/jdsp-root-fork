package com.jdsp.backend;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;

import com.jdsp.common.EndpointFiles;

import org.json.JSONObject;

/**
 * Loopback TCP control channel with a token handshake (the transport the P5 App uses).
 *
 * <p>Why not a unix domain socket: {@code LocalServerSocket(String)} always binds the abstract
 * namespace (nothing lands on disk) and the only constructor that can bind a filesystem path is
 * not available at runtime on Android 15, so a pure Java daemon cannot create one (measured on
 * device by P5, see docs/PLAN-v2-IMPL.md).
 *
 * <p>Security: the socket only listens on 127.0.0.1, and the first line of every connection must
 * be {@code {"op":"hello","token":"<token>"}} where the token is a fresh random value written to
 * a 0400 file inside the App's private data directory. Both conditions are required, so other
 * apps cannot drive the daemon.
 *
 * <p>Protocol after the handshake: one JSON request per line, one JSON response per line, see
 * {@link JsonProtocol}.
 */
public final class TcpService {
    /** Default App private directory holding the port/token files. */
    public static final String DEFAULT_DIR = "/data/data/james.dsp/files";
    /** Port file the App reads to find the daemon. */
    public static final String PORT_FILE_NAME = "jdsp.port";
    /** Token file the App reads to authenticate. */
    public static final String TOKEN_FILE_NAME = "jdsp.token";

    private static final int MAX_CLIENTS = 8;
    private static final int IDLE_TIMEOUT_MS = 30000;
    private static final int MAX_LINE_CHARS = 1024 * 1024;
    private static final int TOKEN_BYTES = 32;

    private final CoreApi api;
    private final JsonProtocol protocol;
    private final String dirPath;
    private final int requestedPort;

    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;
    private volatile int port = -1;
    private volatile int clients;
    private volatile long requests;
    private volatile String token = "";
    private volatile String lastError = "";
    private volatile String portFile = "";
    private volatile String tokenFile = "";
    /** Set while the App data directory is missing (only logged once). */
    private volatile boolean endpointMissingLogged;

    public TcpService(CoreApi api, String dirOverride, int portOverride) {
        this.api = api;
        this.protocol = new JsonProtocol(api);
        this.dirPath = (dirOverride == null || dirOverride.length() == 0)
                ? DEFAULT_DIR : dirOverride;
        this.requestedPort = portOverride;
    }

    public int port() {
        return port;
    }

    public String token() {
        return token;
    }

    public String portFile() {
        return portFile;
    }

    public String tokenFile() {
        return tokenFile;
    }

    public String lastError() {
        return lastError;
    }

    public long requests() {
        return requests;
    }

    public int clients() {
        return clients;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Binds loopback and starts accepting. The endpoint files (port/token) are written when the
     * App's data directory exists; the listener runs in any case, so an App that is installed
     * (or reinstalled) later can still be served once the files show up.
     */
    public boolean start() {
        if (!new File(dirPath).isDirectory()) {
            // The App may be installed without ever having been launched (fresh install or
            // reinstall): create files/ ourselves so the endpoint files land right away.
            ensureAppFilesDir();
        }
        File dir = new File(dirPath);
        if (!dir.isDirectory()) {
            // The App is not installed (yet). Keep listening: the token file is what actually
            // gates access, and it is written as soon as the directory appears.
            lastError = "app data dir missing: " + dirPath;
            Log.w("tcp: " + lastError + " (listener stays up, endpoint files are written later)");
        }
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),
                    requestedPort), 8);
            port = server.getLocalPort();
        } catch (Throwable t) {
            lastError = "bind failed: " + EffectManager.describe(t);
            Log.w("tcp: " + lastError, t);
            server = null;
            api.setTcpInfo(-1, 0, "", "", lastError);
            return false;
        }

        token = randomToken();
        portFile = new File(dir, PORT_FILE_NAME).getPath();
        tokenFile = new File(dir, TOKEN_FILE_NAME).getPath();
        boolean okPort = writeEndpointFiles();
        if (!okPort) {
            lastError = "cannot write port/token files into " + dirPath;
            Log.w("tcp: " + lastError + " (port=" + port + ")");
        } else {
            lastError = "";
        }

        running = true;
        api.setTcpInfo(port, 0, portFile, tokenFile, lastError);
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "jdspd-tcp");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i("tcp control channel on 127.0.0.1:" + port
                + " (endpoint files written=" + okPort + ")");
        return true;
    }

    public void stop() {
        running = false;
        ServerSocket srv = server;
        server = null;
        if (srv != null) {
            try {
                srv.close();
            } catch (Throwable ignored) {
                // nothing to do
            }
        }
        Thread t = acceptThread;
        acceptThread = null;
        if (t != null) {
            try {
                t.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        deleteQuietly(portFile);
        deleteQuietly(tokenFile);
        api.setTcpInfo(-1, 0, "", "", "stopped");
        Log.i("tcp control channel stopped");
    }

    private void acceptLoop() {
        while (running) {
            Socket client;
            try {
                ServerSocket srv = server;
                if (srv == null) {
                    break;
                }
                client = srv.accept();
            } catch (Throwable t) {
                if (!running) {
                    break;
                }
                Log.w("tcp accept failed: " + EffectManager.describe(t));
                sleepQuietly(200L);
                continue;
            }
            if (client == null) {
                continue;
            }
            if (!isLoopback(client)) {
                Log.w("tcp: rejected non loopback client "
                        + client.getRemoteSocketAddress());
                closeQuietly(client);
                continue;
            }
            if (clients >= MAX_CLIENTS) {
                Log.w("tcp: too many clients (" + clients + "), rejecting one");
                closeQuietly(client);
                continue;
            }
            clients++;
            api.setTcpInfo(port, clients, portFile, tokenFile, lastError);
            final Socket socket = client;
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    serve(socket);
                }
            }, "jdspd-tcp-conn");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void serve(Socket socket) {
        BufferedReader reader = null;
        OutputStream out = null;
        try {
            socket.setSoTimeout(IDLE_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"), 16 * 1024);
            out = socket.getOutputStream();

            // ---- handshake: {"op":"hello","token":"<hex>"} -------------------
            String helloLine = reader.readLine();
            if (helloLine == null) {
                return;
            }
            if (helloLine.length() > MAX_LINE_CHARS) {
                writeLine(out, "{\"ok\":false,\"error\":\"hello too long\",\"code\":-1003}");
                return;
            }
            JSONObject hello;
            try {
                hello = new JSONObject(helloLine);
            } catch (Throwable t) {
                Log.w("tcp: bad hello json from " + socket.getRemoteSocketAddress());
                writeLine(out, "{\"ok\":false,\"error\":\"bad hello json\",\"code\":-1003}");
                return;
            }
            String op = hello.optString("op", hello.optString("cmd", ""));
            String given = hello.optString("token", "");
            if (!"hello".equalsIgnoreCase(op) || !token.equals(given)) {
                Log.w("tcp: rejected client (bad hello/token) from "
                        + socket.getRemoteSocketAddress());
                writeLine(out, "{\"ok\":false,\"error\":\"bad token\",\"code\":-1003}");
                return;
            }
            JSONObject accept = new JSONObject();
            accept.put("ok", true);
            accept.put("op", "hello");
            accept.put("version", CoreApi.VERSION);
            accept.put("pid", android.os.Process.myPid());
            accept.put("port", port);
            writeLine(out, accept.toString());
            Log.i("tcp: client accepted " + socket.getRemoteSocketAddress());

            // ---- request loop ------------------------------------------------
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > MAX_LINE_CHARS) {
                    writeLine(out, "{\"ok\":false,\"error\":\"line too long\",\"code\":-1003}");
                    break;
                }
                if (line.trim().length() == 0) {
                    continue;
                }
                requests++;
                String response;
                try {
                    response = protocol.handleLine(line);
                } catch (Throwable t) {
                    Log.e("tcp request failed", t);
                    response = "{\"ok\":false,\"error\":\"internal\",\"code\":-1004}";
                }
                writeLine(out, response);
            }
        } catch (SocketTimeoutException e) {
            Log.i("tcp: idle timeout, closing " + socket.getRemoteSocketAddress());
        } catch (Throwable t) {
            Log.w("tcp: connection closed: " + EffectManager.describe(t));
        } finally {
            clients--;
            api.setTcpInfo(port, clients, portFile, tokenFile, lastError);
            closeQuietly(socket);
        }
    }

    /**
     * (Re)writes the endpoint files when they are missing or stale.
     *
     * <p>Called at startup and periodically by the watcher, because the App's data directory is
     * wiped on uninstall/reinstall: without this the daemon would keep serving a port whose
     * token file no longer exists, and the App could never connect again until the daemon
     * restarted.
     *
     * @return true when the files are in place and match the current listener
     */
    public boolean refreshEndpointFiles() {
        if (port <= 0) {
            return false;
        }
        if (!new File(dirPath).isDirectory()) {
            // App reinstalled but never launched: Android does not create files/ on install,
            // only on the App's first getFilesDir() call. Create it ourselves so the endpoint
            // files are ready before the App's very first connection attempt.
            ensureAppFilesDir();
        }
        File dir = new File(dirPath);
        if (!dir.isDirectory()) {
            // App not installed: nothing to do, and nothing to complain about.
            if (!endpointMissingLogged) {
                endpointMissingLogged = true;
                Log.i("tcp: app data dir " + dirPath
                        + " absent, endpoint files not written (App not installed)");
            }
            return false;
        }
        String wantPort = String.valueOf(port) + "\n";
        String wantToken = token + "\n";
        EndpointFiles.Decision decision = EndpointFiles.decide(dir,
                new File(portFile), new File(tokenFile), wantPort, wantToken);
        if (decision.upToDate) {
            if (endpointMissingLogged) {
                endpointMissingLogged = false;
                Log.i("tcp: endpoint files present again (" + portFile + ")");
            }
            return true;
        }
        if (decision.appMissing) {
            // App not installed: nothing to do, and nothing to complain about.
            if (!endpointMissingLogged) {
                endpointMissingLogged = true;
                Log.i("tcp: app data dir " + dirPath
                        + " absent, endpoint files not written (App not installed)");
            }
            return false;
        }
        boolean ok = writeEndpointFiles();
        if (ok) {
            endpointMissingLogged = false;
            Log.i("tcp: endpoint files (re)written: " + portFile + " port=" + port);
        }
        return ok;
    }

    /**
     * Creates the App's {@code files/} directory when the App data root exists without it.
     *
     * <p>Why: after a reinstall {@code /data/data/<pkg>} exists (owned by the new App uid) but
     * {@code files/} does not — Android only creates it on the App's first {@code getFilesDir()}.
     * Waiting for the App to be launched means its first connection attempt always fails;
     * creating the directory as root with the App's ownership removes that window
     * ({@code getFilesDir()} reuses an existing directory without complaints).
     *
     * <p>The uid >= 10000 guard keeps us out of system-owned directories.
     *
     * @return true when dirPath exists afterwards
     */
    private boolean ensureAppFilesDir() {
        File dir = new File(dirPath);
        if (dir.isDirectory()) {
            return true;
        }
        File parent = dir.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            return false;
        }
        try {
            android.system.StructStat st = android.system.Os.stat(parent.getPath());
            if (st.st_uid < 10000) {
                return false;
            }
            android.system.Os.mkdir(dir.getPath(), 0771);
            android.system.Os.chown(dir.getPath(), st.st_uid, st.st_gid);
            Log.i("tcp: created missing app files dir " + dirPath
                    + " (app reinstalled, not yet started)");
            return true;
        } catch (Throwable t) {
            Log.w("tcp: cannot create " + dirPath + ": " + EffectManager.describe(t));
            return false;
        }
    }

    /** Writes port + token files (chown to the dir owner, mode 0400). */
    private boolean writeEndpointFiles() {
        File dir = new File(dirPath);
        if (!dir.isDirectory()) {
            return false;
        }
        boolean okPort = writeSecret(dir, portFile, String.valueOf(port) + "\n");
        boolean okToken = writeSecret(dir, tokenFile, token + "\n");
        return okPort && okToken;
    }

    // ---- helpers -------------------------------------------------------------

    /** Writes one secret file into the App directory: chown to the dir owner, mode 0400. */
    private static boolean writeSecret(File dir, String path, String content) {
        File file = new File(path);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file, false);
            out.write(content.getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
            out.close();
            out = null;
        } catch (Throwable t) {
            Log.w("tcp: cannot write " + path + ": " + EffectManager.describe(t));
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
        }
        boolean chowned = false;
        try {
            android.system.StructStat st = android.system.Os.stat(dir.getPath());
            android.system.Os.chown(path, st.st_uid, st.st_gid);
            chowned = true;
        } catch (Throwable t) {
            Log.w("tcp: chown " + path + " failed: " + EffectManager.describe(t));
        }
        // 0400 when we own it as the App, 0444 otherwise (the directory is 0700, so it is still
        // only reachable by the App and root).
        FileUtil.chmod(file, chowned ? 0400 : 0444);
        return true;
    }

    private static String randomToken() {
        byte[] raw = new byte[TOKEN_BYTES];
        try {
            new SecureRandom().nextBytes(raw);
        } catch (Throwable t) {
            // fall back to a weaker but still unguessable-enough source
            java.util.Random rnd = new java.util.Random(System.nanoTime()
                    ^ (android.os.Process.myPid() * 0x9E3779B1L));
            rnd.nextBytes(raw);
        }
        return ConfigStore.hex(raw);
    }

    private static boolean isLoopback(Socket socket) {
        try {
            InetAddress address = socket.getInetAddress();
            return address != null && address.isLoopbackAddress();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void writeLine(OutputStream out, String text) throws java.io.IOException {
        out.write(text.getBytes("UTF-8"));
        out.write('\n');
        out.flush();
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Throwable ignored) {
                // nothing to do
            }
        }
    }

    private static void deleteQuietly(String path) {
        if (path == null || path.length() == 0) {
            return;
        }
        try {
            File f = new File(path);
            if (f.exists()) {
                f.delete();
            }
        } catch (Throwable ignored) {
            // nothing to do
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
