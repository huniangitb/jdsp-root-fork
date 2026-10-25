# 验收：引擎调用方写保护（0002-engine-write-lock）

目标：证明 **App（untrusted_app 域）即使持有 AudioEffect 句柄也写不进参数，
jdspd（root/ksu 域）写参数正常**。

前提：刷入含新 libjamesdsp.so（arm64/arm 路径）与 backend/jdspd.dex 的模块包，
重启后 `jdspd` 守护进程在跑（`pgrep -f jdspd` / logcat 里有
`engine write key ready`）。

## 0. 前置检查（引擎锁已生效）

```sh
su -c 'cat /data/adb/jdspd/engine.key | wc -c'          # = 32
su -c 'ls -Z /data/vendor/audio/jamesdsp_engine.key'    # u:object_r:vendor_audio_data_file:s0
su -c 'cat /data/vendor/audio/jamesdsp_engine.key | wc -c'  # = 32，内容与上面一致
adb logcat -d | grep -E 'JamesDSPWriteLock|engine write key'
# 期望：jdspd 日志 "engine write key ready (... mirror ...: ok)"
#       引擎日志（解锁后）"instance unlocked by daemon"
```

## 1. jdspd 写正常

用 CLI（backend/test/build/shared/jdspcli.dex 或 tools/JdspMaster.java）设一个参数：

```sh
su -c 'CLASSPATH=/data/adb/jdspd/jdspd.dex app_process /system/bin com.jdsp.cli.Main \
      set <session> 1300 3.0'        # 1300 = one of the normal param ids
```

- 期望返回 0，`dumpsys audio` / 引擎日志确认生效；
- logcat `JamesDSPWriteLock` 里出现一次 `instance unlocked by daemon`
  （首次写之前的解锁），且**没有** `rejected: instance is locked`。

## 2. App 直写被拒

在任一 App（或 adb shell app_process 以 app 域跑的探针，见
backend/test/device 的现成 probe 思路）里构造同 type/UUID/session 的
AudioEffect 并 setParameter：

```java
AudioEffect fx = new AudioEffect(UUID_TYPE, UUID_EFFECT, 0, sessionId);
fx.setParameter(1300, floatBytes(3.5f));   // 期望返回负值
```

- 期望 `setParameter` 返回**负值**（-EINVAL/-ILLEGAL_ARGUMENT 一族）；
- logcat 同步出现 `JamesDSPWriteLock: SET_PARAM param=1300 rejected: instance is locked`；
- 参数实际值不变（用 jdspd get 确认还是 3.0）。

错误传播路径（代码依据，设备上以第 2 步实测为准）：引擎 `generic_command`
对锁定的 SET_PARAM 同时写命令返回值和 reply status（`effect_param_t.status`）
为 -1，AudioFlinger `EffectHandle::setParameter` 把 `p->status` 透传给 binder，
framework `AudioEffect::setParameter` 返回负值给 App。

## 3. 拒绝不伤音频、不写状态文件

- 第 2 步反复失败后：音频照常渲染（引擎根本没被触碰）；
- `su -c stat -c %s /data/vendor/audio/jamesdsp_state.bin` 大小不变（拒绝的
  SET_PARAM 不进 state 记录），解锁命令同样不进记录。

## 4. 热重载 / 重建后仍受保护

```sh
su -c 'touch /data/vendor/audio/jamesdsp_state.bin 相关热重载流程'  # 或改 config 后 applyAll
```

- 引擎重建（builder 线程换新引擎）**不需要**重新解锁（写锁挂在 effect 实例上，
  不在 EffectDSPMain 上）；
- 整个 effect 实例被框架重建后（重启 audioserver/HAL，或 session 销毁重建）：
  新实例默认锁定，jdspd attach 后第一条写参数前自动解锁；此时 App 直写再次被拒
  （重复第 2 步验证）。

## 5. 无 key 时 fail-closed

```sh
su -c 'mv /data/vendor/audio/jamesdsp_engine.key /data/vendor/audio/jamesdsp_engine.key.bak
       && mv /data/adb/jdspd/engine.key /data/adb/jdspd/engine.key.bak'
# 重启 HAL（kill android.hardware.audio.service）+ 重启 jdspd
```

- 期望：jdspd 日志 `engine write key unavailable: parameter writes will be refused`，
  所有参数写返回 -1008（ERR_NO_KEY），App 与 jdspd 都写不进——不会出现半保护状态。

## 6. 变体覆盖检查（重要）

模块里 `common/files/aidl|arm64-sve|x86` 的 libjamesdsp.so 仍是上游预编译
（无写锁）。确认目标设备实际安装的变体：

```sh
su -c 'ls -l /vendor/lib64/soundfx/libjamesdsp.so /system/lib64/soundfx/libjamesdsp.so'
su -c 'grep -l jamesdsp /proc/$(pidof android.hardware.audio.service)/maps'
sha256sum <上面那个路径>   # 应等于 module/src/common/files/arm64/libjamesdsp.so 的新哈希
```

若设备走 aidl 变体（`/system/lib64` 下 AIDL effects factory + stub 库齐全），
必须先按 BUILDING.md 第 5 节构建带 0002 的 AIDL 变体再验收，否则 App 直写在
该变体上不受保护。
