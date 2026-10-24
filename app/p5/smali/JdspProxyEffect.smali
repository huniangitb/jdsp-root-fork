.class public final Lme/timschneeberger/rootlessjamesdsp/p5/JdspProxyEffect;
.super Landroid/media/audiofx/AudioEffect;
.source "JdspProxyEffect.smali"


# P5 代理 Effect。
#
# 替换 JamesDspRemoteEngine.createEffect() 里 new 出来的 AudioEffect：
# 所有参数写入仍然照原样走 super（App 自己的行为完全不变），
# 同时把 (sessionId, param, value) 镜像给 jdspd（JdspBridge 内部做合并/降级）。
#
# 这样 App 的几十个 setParameter 调用点一个都不用改。

.field private final sessionId:I


.method public constructor <init>(Ljava/util/UUID;Ljava/util/UUID;II)V
    .registers 5

    invoke-direct {p0, p1, p2, p3, p4}, Landroid/media/audiofx/AudioEffect;-><init>(Ljava/util/UUID;Ljava/util/UUID;II)V

    iput p4, p0, Lme/timschneeberger/rootlessjamesdsp/p5/JdspProxyEffect;->sessionId:I

    return-void
.end method


# int setParameter(int, byte[]) — 所有 setParameterFloatArray / CharArray / IntArray 最终都走这里
.method public setParameter(I[B)I
    .registers 5

    invoke-super {p0, p1, p2}, Landroid/media/audiofx/AudioEffect;->setParameter(I[B)I

    move-result v0

    iget v1, p0, Lme/timschneeberger/rootlessjamesdsp/p5/JdspProxyEffect;->sessionId:I

    invoke-static {v1, p1, p2}, Lme/timschneeberger/rootlessjamesdsp/p5/JdspBridge;->onParamBytes(II[B)V

    return v0
.end method


# int setParameter(int, short) — 各种 enable/预设号
.method public setParameter(IS)I
    .registers 5

    invoke-super {p0, p1, p2}, Landroid/media/audiofx/AudioEffect;->setParameter(IS)I

    move-result v0

    iget v1, p0, Lme/timschneeberger/rootlessjamesdsp/p5/JdspProxyEffect;->sessionId:I

    invoke-static {v1, p1, p2}, Lme/timschneeberger/rootlessjamesdsp/p5/JdspBridge;->onParamShort(IIS)V

    return v0
.end method


# int setParameter(int, int)
.method public setParameter(II)I
    .registers 5

    invoke-super {p0, p1, p2}, Landroid/media/audiofx/AudioEffect;->setParameter(II)I

    move-result v0

    iget v1, p0, Lme/timschneeberger/rootlessjamesdsp/p5/JdspProxyEffect;->sessionId:I

    invoke-static {v1, p1, p2}, Lme/timschneeberger/rootlessjamesdsp/p5/JdspBridge;->onParamInt(III)V

    return v0
.end method


# void setEnabled(boolean) — 全局开关镜像成 cmd=400（引擎侧持久化）
.method public setEnabled(Z)V
    .registers 3

    invoke-super {p0, p1}, Landroid/media/audiofx/AudioEffect;->setEnabled(Z)V

    iget v0, p0, Lme/timschneeberger/rootlessjamesdsp/p5/JdspProxyEffect;->sessionId:I

    invoke-static {v0, p1}, Lme/timschneeberger/rootlessjamesdsp/p5/JdspBridge;->onEnabled(IZ)V

    return-void
.end method
