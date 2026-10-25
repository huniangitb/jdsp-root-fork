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

    /** 让 jdspd 放掉会话的句柄；0 = ok（幂等）。 */
    int detach(int sessionId);

    /**
     * 后端全局启停（配置开关）。
     *
     * 语义：**配置开关**，不是"音频会话的 bypass" —— 它决定 jdspd 是否把配置
     * 下发给引擎。不再使用引擎的 cmd=400（v6.7.2 引擎没有实现它）。
     *
     * 后端语义（daemon-core 实现）：
     *   - enabled=false = 全局旁通：daemon 保留句柄（实例留在链上）但 setEnabled(false)，
     *     **不 detach**（detach 会重建音频链、有爆音风险）；
     *   - enabled=true 恢复：≤1s 内 watcher 重新 attach 并重放参数；
     *   - 持久化到 config.json 的 enabled 字段。
     *
     * @return 0 = ok；负数 = 失败。
     */
    int setEnabled(boolean enabled);

    /**
     * 查询后端全局开关。失败返回 null。
     */
    Boolean getEnabled();

    /**
     * 读取后端的**完整配置快照**（config.json 的 JSON 文本）。
     *
     * 这是"App 不持有配置"的关键：App 的界面要读后端的值，就必须能拿到
     * 后端存着的那份配置。返回的 JSON 形状见 JdspConfigSnapshot。
     *
     * @return JSON 文本；失败返回 null（调用方按"读不到"处理，
     *         **不能**用自己的旧值冒充后端的值）。
     */
    String dumpConfig();

    /**
     * 后端"被全局关掉"的错误码。
     *
     * 这**不是故障**，是用户意图（用户关了全局开关）。收到它时：
     *   - 不要当故障无限重试；
     *   - 把参数留在重试队列，等 enabled=true 之后重放。
     */
    int ERR_DISABLED = -1006;

    /**
     * 列出 jdspd 当前已挂载（attached=true）的会话 id。
     * 用于把 App 的"全局实例"参数广播到所有真实播放会话。
     * 失败返回 null。
     */
    int[] listAttachedSessions();
}
