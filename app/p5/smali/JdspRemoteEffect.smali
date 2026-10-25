.class public final Lme/timschneeberger/rootlessjamesdsp/p5/JdspRemoteEffect;
.super Landroid/media/audiofx/AudioEffect;
.source "JdspRemoteEffect.smali"

# P5 第二阶段：写路径走后端，读路径留本地。
#
# 为什么是 smali 子类：AudioEffect 的 4 参构造器与 setParameter 都是隐藏 API，
# 公开 android.jar 里没有。smali 可以直接 invoke-direct 隐藏成员（第一阶段的
# JdspProxyEffect 已真机跑通），Java 做不到（super() 必须首句调用构造器）。
#
# 本类只做"转调"，策略全在 JdspRemoteOps（可 javac 编译、可单测）：
#   * 写：**只走后端**。后端不可用时返回错误，参数留在 JdspBridge 的重试队列，
#     **绝不写本地** —— App 侧不再有任何"自己设置音效"的路径。
#   * 读：**只读本地**，且只用于健康查询（19998/20001/20002 这类引擎内部量）。
#     引擎没有 GET_PARAM，jdspd 拿不到这些量（实测 getParamEngineHits=0）；
#     读不到返回 -1，绝不返回假的 0 触发健康检查反复重建。
#   * setEnabled：**走后端的全局配置开关**（config.enabled），不控制本地实例，
#     也不用引擎的 cmd=400（v6.7.2 引擎没有实现它）。
#   * release：只释放本地，**不 detach** 后端公共会话（jdspd 是常驻控制者，
#     误 detach 会把正在播放的会话音效全部摘掉）。
#
# 无 jdspd 时：写操作返回错误并等待后端恢复后重放，App 不会自己出声效参数。

.field private final sessionId:I


.method public constructor <init>(Ljava/util/UUID;Ljava/util/UUID;II)V
    .registers 5

    invoke-direct {p0, p1, p2, p3, p4}, Landroid/media/audiofx/AudioEffect;-><init>(Ljava/util/UUID;Ljava/util/UUID;II)V

    iput p4, p0, Lme/timschneeberger/rootlessjamesdsp/p5/JdspRemoteEffect;->sessionId:I

    return-void
.end method


.method public getSessionId()I
    .registers 2

    iget v0, p0, Lme/timschneeberger/rootlessjamesdsp/p5/JdspRemoteEffect;->sessionId:I

    return v0
.end method


# int setParameter(int, byte[])
#   JdspRemoteOps.setParamBytes(AudioEffect local, int sessionId, int param, byte[] value)
.method public setParameter(I[B)I
    .registers 7

    move-object v0, p0

    iget v1, p0, Lme/timschneeberger/rootlessjamesdsp/p5/JdspRemoteEffect;->sessionId:I

    move v2, p1

    move-object v3, p2

    invoke-static {v0, v1, v2, v3}, Lme/timschneeberger/rootlessjamesdsp/p5/JdspRemoteOps;->setParamBytes(Landroid/media/audiofx/AudioEffect;II[B)I

    move-result v0

    return v0
.end method


# int setParameter(int, short)
#   JdspRemoteOps.setParamShort(AudioEffect local, int sessionId, int param, short value)
.method public setParameter(IS)I
    .registers 7

    move-object v0, p0

    iget v1, p0, Lme/timschneeberger/rootlessjamesdsp/p5/JdspRemoteEffect;->sessionId:I

    move v2, p1

    move v3, p2

    invoke-static {v0, v1, v2, v3}, Lme/timschneeberger/rootlessjamesdsp/p5/JdspRemoteOps;->setParamShort(Landroid/media/audiofx/AudioEffect;IIS)I

    move-result v0

    return v0
.end method


# int setParameter(int, int)
#   JdspRemoteOps.setParamInt(AudioEffect local, int sessionId, int param, int value)
.method public setParameter(II)I
    .registers 7

    move-object v0, p0

    iget v1, p0, Lme/timschneeberger/rootlessjamesdsp/p5/JdspRemoteEffect;->sessionId:I

    move v2, p1

    move v3, p2

    invoke-static {v0, v1, v2, v3}, Lme/timschneeberger/rootlessjamesdsp/p5/JdspRemoteOps;->setParamInt(Landroid/media/audiofx/AudioEffect;III)I

    move-result v0

    return v0
.end method


# int setParameter(byte[], byte[])
#   JdspRemoteOps.setParamRaw(AudioEffect local, int sessionId, byte[] param, byte[] value)
.method public setParameter([B[B)I
    .registers 7

    move-object v0, p0

    iget v1, p0, Lme/timschneeberger/rootlessjamesdsp/p5/JdspRemoteEffect;->sessionId:I

    move-object v2, p1

    move-object v3, p2

    invoke-static {v0, v1, v2, v3}, Lme/timschneeberger/rootlessjamesdsp/p5/JdspRemoteOps;->setParamRaw(Landroid/media/audiofx/AudioEffect;I[B[B)I

    move-result v0

    return v0
.end method


# int getParameter(int, byte[]) — 本地权威
#   JdspRemoteOps.getParamBytes(AudioEffect local, int sessionId, int param, byte[] value)
.method public getParameter(I[B)I
    .registers 7

    move-object v0, p0

    iget v1, p0, Lme/timschneeberger/rootlessjamesdsp/p5/JdspRemoteEffect;->sessionId:I

    move v2, p1

    move-object v3, p2

    invoke-static {v0, v1, v2, v3}, Lme/timschneeberger/rootlessjamesdsp/p5/JdspRemoteOps;->getParamBytes(Landroid/media/audiofx/AudioEffect;II[B)I

    move-result v0

    return v0
.end method


# boolean getEnabled()
.method public getEnabled()Z
    .registers 2

    invoke-super {p0}, Landroid/media/audiofx/AudioEffect;->getEnabled()Z

    move-result v0

    return v0
.end method


# int setEnabled(boolean) — 走后端的全局配置开关，不控制本地实例
#   JdspRemoteOps.setEnabled(AudioEffect local, int sessionId, boolean enabled)
.method public setEnabled(Z)I
    .registers 5

    move-object v0, p0

    iget v1, p0, Lme/timschneeberger/rootlessjamesdsp/p5/JdspRemoteEffect;->sessionId:I

    move v2, p1

    invoke-static {v0, v1, v2}, Lme/timschneeberger/rootlessjamesdsp/p5/JdspRemoteOps;->setEnabled(Landroid/media/audiofx/AudioEffect;IZ)I

    move-result v0

    return v0
.end method


# void release() — 只释放本地，不 detach 后端会话
#   JdspRemoteOps.release(AudioEffect local)
.method public release()V
    .registers 2

    invoke-static {p0}, Lme/timschneeberger/rootlessjamesdsp/p5/JdspRemoteOps;->release(Landroid/media/audiofx/AudioEffect;)V

    return-void
.end method
