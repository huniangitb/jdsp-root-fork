package me.timschneeberger.rootlessjamesdsp.p5;

/**
 * 一条可用传输通道的抽象：只包含 App 侧真正需要的 5 个操作。
 *
 * 三级降级顺序（见 docs/P5-APP-INTEGRATION.md）：
 *   C socket  -> A binder -> B root shell
 *
 * 每个实现都必须做到：失败时返回错误码/空值，绝不抛异常给调用方。
 */
public interface JdspTransport {

    /** 通道名，用于日志与诊断。 */
    String name();

    /** 尝试建立通道；返回 true 表示可用。可重复调用。 */
    boolean connect();

    /** 关闭通道（可重复调用）。 */
    void close();

    boolean isConnected();

    /** 0 = ok；负数 = 失败。 */
    int ping();

    /** 下发一个引擎参数；返回引擎状态（0 = applied），负数 = 失败。 */
    int setParam(int sessionId, int paramId, byte[] value);

    /** 读取一个引擎参数；失败返回 null。 */
    byte[] getParam(int sessionId, int paramId, int vsize);

    /** 批量提交（socket/binder 立即生效，可忽略）。 */
    int commit(int sessionId);

    /** 让 jdspd 为会话创建/保持一个效果句柄；0 = ok。 */
    int attach(int sessionId);

    /**
     * 列出 jdspd 当前已挂载（attached=true）的会话 id。
     * 用于把 App 的"全局实例"参数广播到所有真实播放会话。
     * 失败返回 null。
     */
    int[] listAttachedSessions();
}
