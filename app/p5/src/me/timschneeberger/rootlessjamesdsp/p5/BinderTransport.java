package me.timschneeberger.rootlessjamesdsp.p5;

import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Method;

/**
 * 传输 A：直接 binder 调 jdsp.service（IJdspService）。
 *
 * 不依赖生成的 AIDL Stub：手写 transact，事务号取自 backend/out/gen 的
 * IJdspService.java（DESCRIPTOR = android.jdsp.backend.IJdspService）：
 *   ping=+1 listSessions=+2 getSession=+3 attach=+4 detach=+5 setParam=+6
 *   getParam=+7 commit=+8 dumpConfig=+9 dumpSession=+10 ...
 *
 * 注意：这条通道需要 SELinux 允许 untrusted_app 对服务 SID 做 find/call。
 * 若 jdspd 以 su/ksu 域注册服务，按 AOSP 规则服务 SID 会落到注册域，
 * 放行等于把 su 域对普通 App 敞开 —— 因此本通道默认是"探测到可用才用"，
 * 不作为首选。见 docs/P5-APP-INTEGRATION.md §4。
 */
public final class BinderTransport implements JdspTransport {

    public static final String DEFAULT_SERVICE = "jdsp.service";
    private static final String DESCRIPTOR = "android.jdsp.backend.IJdspService";

    private static final int T_PING = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int T_SET_PARAM = IBinder.FIRST_CALL_TRANSACTION + 6;
    private static final int T_GET_PARAM = IBinder.FIRST_CALL_TRANSACTION + 7;
    private static final int T_COMMIT = IBinder.FIRST_CALL_TRANSACTION + 8;

    private final String serviceName;
    private IBinder binder;
    private String lastError = "";

    public BinderTransport(String serviceName) {
        this.serviceName = serviceName == null ? DEFAULT_SERVICE : serviceName;
    }

    @Override
    public String name() {
        return "binder(" + serviceName + ")";
    }

    public String lastError() {
        return lastError;
    }

    @Override
    public boolean isConnected() {
        return binder != null && binder.pingBinder();
    }

    @Override
    public boolean connect() {
        try {
            // 隐藏 API：App 侧 MainApplication 已调用 HiddenApiBypass.addHiddenApiExemptions("L")
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method get = sm.getMethod("getService", String.class);
            Object b = get.invoke(null, serviceName);
            if (!(b instanceof IBinder)) {
                lastError = "service not found";
                binder = null;
                return false;
            }
            binder = (IBinder) b;
            return ping() >= 0;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            binder = null;
            return false;
        }
    }

    @Override
    public void close() {
        binder = null;
    }

    private interface Call {
        void run(Parcel data, Parcel reply) throws Exception;
    }

    private int transactInt(int code, Call call) {
        IBinder b = binder;
        if (b == null) return -1;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            call.run(data, reply);
            if (!b.transact(code, data, reply, 0)) return -1;
            reply.readException();
            return reply.dataAvail() > 0 ? reply.readInt() : 0;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            if (t instanceof SecurityException || t instanceof android.os.DeadObjectException) binder = null;
            return -1;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    @Override
    public int ping() {
        return transactInt(T_PING, new Call() {
            @Override public void run(Parcel data, Parcel reply) {}
        });
    }

    @Override
    public int setParam(final int sessionId, final int paramId, final byte[] value) {
        return transactInt(T_SET_PARAM, new Call() {
            @Override public void run(Parcel data, Parcel reply) {
                data.writeInt(sessionId);
                data.writeInt(paramId);
                data.writeByteArray(value);
            }
        });
    }

    @Override
    public byte[] getParam(final int sessionId, final int paramId, final int vsize) {
        IBinder b = binder;
        if (b == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(sessionId);
            data.writeInt(paramId);
            data.writeInt(vsize);
            if (!b.transact(T_GET_PARAM, data, reply, 0)) return null;
            reply.readException();
            return reply.createByteArray();
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    @Override
    public int attach(final int sessionId) {
        return transactInt(IBinder.FIRST_CALL_TRANSACTION + 4, new Call() {
            @Override public void run(Parcel data, Parcel reply) {
                data.writeInt(sessionId);
            }
        });
    }

    @Override
    public int[] listAttachedSessions() {
        return null;  // A 通道不实现会话列表（本机不可用，保留占位）
    }

    @Override
    public int commit(final int sessionId) {
        return transactInt(T_COMMIT, new Call() {
            @Override public void run(Parcel data, Parcel reply) {
                data.writeInt(sessionId);
            }
        });
    }
}
