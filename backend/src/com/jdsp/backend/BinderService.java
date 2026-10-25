package com.jdsp.backend;

import android.jdsp.backend.IJdspService;
import android.jdsp.backend.JdspSession;
import android.os.IBinder;

import com.jdsp.common.EnableErrors;
import com.jdsp.common.JdspConst;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * AIDL transport: publishes {@code jdsp.service} and forwards every call to {@link CoreApi}.
 *
 * <p>The service is registered through the hidden {@code ServiceManager.addService} (verified to
 * work from an app_process daemon in the ksu/su domain, see docs/PLAN-v2-IMPL.md 2.4). When
 * publication fails the daemon keeps running and retries once a minute; the Unix socket transport
 * is unaffected.
 */
public final class BinderService extends IJdspService.Stub {
    private static final long RETRY_MS = 60000L;

    private final CoreApi api;
    private volatile boolean published;
    private volatile int attempts;
    private volatile String lastError = "";
    private Thread retryThread;

    public BinderService(CoreApi api) {
        this.api = api;
    }

    /** Registers the binder service. Returns true on success. Never throws. */
    public boolean publish() {
        if (published) {
            return true;
        }
        attempts++;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method add = sm.getMethod("addService", String.class, IBinder.class);
            add.invoke(null, JdspConst.SERVICE_NAME, this);
            Object found = sm.getMethod("getService", String.class)
                    .invoke(null, JdspConst.SERVICE_NAME);
            published = found != null;
            if (published) {
                lastError = "";
                Log.i("binder service published: " + JdspConst.SERVICE_NAME);
            } else {
                lastError = "getService returned null after addService";
                Log.w("binder publish: " + lastError);
            }
        } catch (Throwable t) {
            lastError = EffectManager.describe(t);
            Log.w("binder publish failed: " + lastError, t);
        }
        api.setBinderPublished(published);
        return published;
    }

    /** Keeps retrying publication every 60 s until it succeeds. */
    public void startRetryLoop() {
        if (retryThread != null || published) {
            return;
        }
        retryThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!published) {
                    try {
                        Thread.sleep(RETRY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!published) {
                        Log.i("retrying binder publication (attempt " + (attempts + 1) + ")");
                        publish();
                    }
                }
            }
        }, "jdspd-binder-retry");
        retryThread.setDaemon(true);
        retryThread.start();
    }

    public boolean isPublished() {
        return published;
    }

    public String lastError() {
        return lastError;
    }

    public int attempts() {
        return attempts;
    }

    // ---- AIDL surface: thin forwarding, every call guarded --------------------

    @Override
    public String getVersion() {
        return CoreApi.VERSION;
    }

    @Override
    public int ping() {
        return CoreApi.PING_OK;
    }

    @Override
    public List<JdspSession> listSessions() {
        try {
            List<CoreApi.SessionView> views = api.listSessions();
            List<JdspSession> out = new ArrayList<JdspSession>(views.size());
            for (int i = 0; i < views.size(); i++) {
                out.add(toParcel(views.get(i)));
            }
            return out;
        } catch (Throwable t) {
            Log.e("listSessions failed", t);
            return new ArrayList<JdspSession>();
        }
    }

    @Override
    public JdspSession getSession(int sessionId) {
        try {
            CoreApi.SessionView v = api.getSession(sessionId);
            return v == null ? null : toParcel(v);
        } catch (Throwable t) {
            Log.e("getSession failed", t);
            return null;
        }
    }

    @Override
    public int attach(int sessionId) {
        try {
            return api.attach(sessionId);
        } catch (Throwable t) {
            Log.e("attach failed", t);
            return EffectManager.ERR_INTERNAL;
        }
    }

    @Override
    public int detach(int sessionId) {
        try {
            return api.detach(sessionId);
        } catch (Throwable t) {
            Log.e("detach failed", t);
            return EffectManager.ERR_INTERNAL;
        }
    }

    @Override
    public int setParam(int sessionId, int paramId, byte[] value) {
        try {
            return api.setParam(sessionId, paramId, value);
        } catch (Throwable t) {
            Log.e("setParam failed", t);
            return EffectManager.ERR_INTERNAL;
        }
    }

    @Override
    public byte[] getParam(int sessionId, int paramId, int vsize) {
        try {
            return api.getParam(sessionId, paramId, vsize);
        } catch (Throwable t) {
            Log.e("getParam failed", t);
            return CoreApi.EMPTY;
        }
    }

    @Override
    public int commit(int sessionId) {
        try {
            return api.commit(sessionId);
        } catch (Throwable t) {
            Log.e("commit failed", t);
            return EffectManager.ERR_INTERNAL;
        }
    }

    @Override
    public String dumpConfig() {
        try {
            return api.dumpConfig();
        } catch (Throwable t) {
            Log.e("dumpConfig failed", t);
            return "{\"error\":\"dumpConfig failed\"}";
        }
    }

    @Override
    public String dumpSession(int sessionId) {
        try {
            return api.dumpSession(sessionId);
        } catch (Throwable t) {
            Log.e("dumpSession failed", t);
            return "{\"error\":\"dumpSession failed\"}";
        }
    }

    @Override
    public int loadPreset(String path) {
        try {
            return api.loadPreset(path);
        } catch (Throwable t) {
            Log.e("loadPreset failed", t);
            return EffectManager.ERR_INTERNAL;
        }
    }

    @Override
    public int savePreset(String path) {
        try {
            return api.savePreset(path);
        } catch (Throwable t) {
            Log.e("savePreset failed", t);
            return EffectManager.ERR_INTERNAL;
        }
    }

    @Override
    public int setAppPolicy(String pkg, int mode) {
        try {
            return api.setAppPolicy(pkg, mode);
        } catch (Throwable t) {
            Log.e("setAppPolicy failed", t);
            return EffectManager.ERR_INTERNAL;
        }
    }

    @Override
    public int getAppPolicy(String pkg) {
        try {
            return api.getAppPolicy(pkg);
        } catch (Throwable t) {
            Log.e("getAppPolicy failed", t);
            return JdspConst.POLICY_FOLLOW;
        }
    }

    @Override
    public String listPolicies() {
        try {
            return api.listPolicies();
        } catch (Throwable t) {
            Log.e("listPolicies failed", t);
            return "{\"policies\":{},\"count\":0}";
        }
    }

    @Override
    public int setMode(int mode) {
        try {
            return api.setMode(mode);
        } catch (Throwable t) {
            Log.e("setMode failed", t);
            return EffectManager.ERR_INTERNAL;
        }
    }

    @Override
    public int getMode() {
        try {
            return api.getMode();
        } catch (Throwable t) {
            Log.e("getMode failed", t);
            return JdspConst.MODE_AUTO;
        }
    }

    @Override
    public int setEnabled(boolean enabled) {
        try {
            // Never report "disabled" here: the client would wait for a re-enable that may
            // never come (see EnableErrors).
            return EnableErrors.forEnableCommand(api.setEnabled(enabled));
        } catch (Throwable t) {
            Log.e("setEnabled failed", t);
            return EffectManager.ERR_INTERNAL;
        }
    }

    @Override
    public boolean getEnabled() {
        try {
            return api.getEnabled();
        } catch (Throwable t) {
            Log.e("getEnabled failed", t);
            return false;
        }
    }

    @Override
    public String stats() {
        try {
            return api.stats();
        } catch (Throwable t) {
            Log.e("stats failed", t);
            return "{\"error\":\"stats failed\"}";
        }
    }

    @Override
    public int applyBaseline(boolean clearPerApp) {
        try {
            return api.applyBaseline(clearPerApp);
        } catch (Throwable t) {
            Log.e("applyBaseline failed", t);
            return EffectManager.ERR_INTERNAL;
        }
    }

    // ---- helpers -------------------------------------------------------------

    private static JdspSession toParcel(CoreApi.SessionView v) {
        JdspSession s = new JdspSession();
        s.sessionId = v.sessionId;
        s.uid = v.uid;
        s.pkg = v.pkg == null ? "" : v.pkg;
        s.attached = v.attached;
        s.enabled = v.enabled;
        s.paramCount = v.paramCount;
        s.effectId = v.effectId;
        s.policyMode = v.policyMode;
        s.pid = v.pid;
        return s;
    }
}
