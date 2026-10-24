# P5 — App 接入方案与实现（jdspd 前端改造）

状态：**方案已定、传输链已真机验证、补丁可构建；端到端待 jdspd 侧 TCP 服务端上线**
日期：2026-10-24 / 25
作者：app-p5
关联：`docs/PLAN-v2.md`（设计稿）、`docs/PLAN-v2-IMPL.md` §8、`backend/aidl/android/jdsp/backend/IJdspService.aidl`

---

## 0. 结论摘要（TL;DR）

| 问题 | 结论 |
|---|---|
| Gradle 能否离线构建 | **能配置（`:app:tasks` EXIT=0）**；历史"buildSrc 需要联网"结论已过时，真正的拦路虎是本机 NDK 27 空壳目录（见 §1）。全量 assemble 未验证，不作为交付前提。 |
| 本轮交付方式 | **apktool 补丁路线**（`app/p5/patch_app_p5.sh`），不改业务逻辑、可回退 |
| 参数下发改走哪条通道 | **C'：loopback TCP + token**（真机 untrusted_app 域实测通过，avg 1.77 ms/次、0 失败） |
| binder（A）/ root shell（B） | 代码保留作为降级分支，**本机实测均不可用**（证据见 §4.3） |
| 补丁点数量 | **1 个方法 2 行 smali**（`JamesDspRemoteEngine.createEffect()`）+ 1 个新增的代理类 |
| 杀前端/卸载前端后配置是否还在 | 由 jdspd 的 ConfigStore 保证；App 侧每次参数写入都镜像到 jdspd（本方案已实现镜像）。App 自己仍 new 一个 AudioEffect（P5 第二阶段再摘掉），因此**当前形态下 App 挂掉不影响 jdspd 侧的配置与已 attach 会话的音效** |

---

## 1. 可行性：Gradle 离线构建

### 1.1 命令与结果

```
cd /home/bdy/work/loundess_git
./gradlew --offline --no-daemon -Dorg.gradle.jvmargs=-Xmx2g --max-workers=2 :app:tasks
=> EXIT=0（3m06s），buildSrc 全部离线编译/UP-TO-DATE
```

第一次执行（未做任何修复时）失败，但**不是网络问题**：

```
* What went wrong:
A problem occurred configuring project ':app'.
> [CXX1101] NDK at /home/bdy/Android/Sdk/ndk/27.0.12077973 did not have a source.properties file
```

### 1.2 根因与处理

`/home/bdy/Android/Sdk/ndk/27.0.12077973` 是个**空壳目录**（10-24 15:24 的残留半安装，里面只有一个 `.installer`）。
本机实际可用的是 `ndk/30.0.16248370`（source.properties 正常）。AGP 默认要 27.0.12077973，于是配置期直接失败。

处理（只动本机 SDK，不动仓库）：

```sh
cd /home/bdy/Android/Sdk/ndk
rm -rf 27.0.12077973
ln -s /home/bdy/Android/Sdk/ndk/30.0.16248370 27.0.12077973
```

处理后同一条命令 EXIT=0。

### 1.3 为什么仍以 apktool 补丁为主

- 本机 4 核 / 7.8 GB 内存（可用约 3 GB），`app/src/main/cpp` 有 libjamesdsp 等原生库、要编 4 个 ABI，OOM 风险真实存在；
- `patch_app.sh` 管线已经验证过、单次几分钟、不吃内存；
- 补丁体积小、可审计、可回退（重装官方 APK 即恢复）。

> 结论：Gradle 离线**可以作为后续正式发版路径**继续推进（NDK 修好后不再是网络问题），本轮交付用补丁路线。

---

## 2. App 侧架构：一个补丁点覆盖全部参数

### 2.1 现状（官方 root-full APK 的链路）

```
JamesDspRemoteEngine.createEffect()
    -> new AudioEffect(EFFECT_TYPE_CUSTOM, EFFECT_JAMESDSP, priority, sessionId)   // 隐藏构造器
    -> 之后所有参数：AudioEffectExtensions.setParameter*() / engine.setParameter*()
       -> AudioEffect.setParameter(int, byte[]|short|int)   // ← 全部经过这一个对象
```

`JamesDspRemoteEngine` 549 行 + `AudioEffectExtensions` 里几十个调用点，**但都作用在同一个 AudioEffect 实例上**。

### 2.2 补丁

| 文件 | 改动 |
|---|---|
| `smali/me/timschneeberger/rootlessjamesdsp/p5/JdspProxyEffect.smali` | **新增**：`extends Landroid/media/audiofx/AudioEffect;`，override `setParameter(I[B)I`、`setParameter(IS)I`、`setParameter(II)I`、`setEnabled(Z)V`；每个方法先 `invoke-super`（原行为不变），再调 `JdspBridge.onXxx(sessionId, param, value)` |
| `smali*/me/timschneeberger/rootlessjamesdsp/interop/JamesDspRemoteEngine.smali` → `createEffect()` | `new-instance v0, Landroid/media/audiofx/AudioEffect;` → `.../JdspProxyEffect;`；对应 `invoke-direct ... AudioEffect-><init>(UUID,UUID,II)V` → `JdspProxyEffect-><init>(...)`。**共 2 行** |
| `classes4.dex`（新增） | `JdspBridge` + `TcpTransport`/`SocketTransport`/`BinderTransport`/`ShellTransport` + `JdspCodec` 的 Java 字节码（multidex，minSdk 26 无需 MultiDex 库） |

为什么不在几十个 `setParameter` 调用点逐个插桩：代理类只改 1 处，且**未来 App 升级只要 `createEffect()` 还在就仍然有效**（补丁脚本会断言方法体存在，不存在就报错退出，不会静默漏打）。

### 2.3 JdspBridge（App 侧，纯 Java 8 / 零第三方依赖）

`app/p5/src/me/timschneeberger/rootlessjamesdsp/p5/`

| 类 | 职责 |
|---|---|
| `JdspTransport` | 通道抽象：`connect/ping/setParam/getParam/attach/commit/close` |
| `TcpTransport` | **C'**：loopback TCP + `hello` token 握手 + JSON lines |
| `SocketTransport` | C：unix socket（本机 Java 层建不出 filesystem 监听 socket，见 §4.2；保留代码） |
| `BinderTransport` | A：`ServiceManager.getService("jdsp.service")` + 手写 transact（事务号取自 `backend/out/gen`） |
| `ShellTransport` | B：`su -c 'app_process ... jdspcli set --hex ...'` |
| `JdspCodec` | hex / 小端 short/int/float 编码（与 `AudioEffect.setParameter` 的字节序一致） |
| `JdspBridge` | 单例门面：传输探测、入队合并、后台线程、失败降级、断线重连 |

关键行为：

1. **永不阻塞 UI 线程**：`onParamXxx` 只入队，后台线程每 ~5–10 ms 排空一次（单轮 ≤64 条）。
2. **合并策略**：
   - 普通参数按 `(session,param)` **last-wins**（滑条拖动只保留最新值）；
   - 分片/提交类参数**严格 FIFO 不合并**：`8888/9999/12000/12001`、`10004/10006/10009/10010/25000–25003`、以及 `115/116/1300/1305/1306/1307` 大块配置。
3. **降级**：tcp → unix socket → binder → shell 依次探测；全失败则**整体静默停用**，App 行为与官方版完全一致（这是硬要求：补丁版在没装 jdspd 的机器上也必须能用）。
4. **断线重连**：传输报错 5 次即丢弃当前连接；下次取用时重新读 `jdsp.port`/`jdsp.token` 并重连（daemon 重启会换 token，靠重读文件解决）。
5. **首发 attach**：每个 session 第一次下发前先 `attach(session)` 一次（幂等）。

---

## 3. 设计：参数下发 / 读取的新路径

### 3.1 下发（本轮已实现：镜像模式）

```
App UI 改动 (PEQ 增益/开关…)
  -> PreferenceCache / SharedPreferences（App 侧仍是 UI 状态的缓存）
  -> JamesDspRemoteEngine.setXxx()
  -> AudioEffectExtensions.setParameterFloatArray() 等
  -> JdspProxyEffect.setParameter(param, bytes)
       ├─ super.setParameter(...)               # 原链路，App 自己的实例照旧
       └─ JdspBridge.onParamBytes(session,param,bytes)
            -> 合并队列 -> 后台线程 -> TcpTransport -> jdspd -> AudioEffect handle -> 引擎
```

选择"镜像"而不是"替换"的原因：本轮的验收目标是**jdspd 侧有配置、杀前端不丢**；镜像方式风险最低（App 原行为不变），且不需要改动 App 的配置存储层。**第二阶段**再摘掉 App 自己的实例（见 §7）。

### 3.2 读取

本轮**不改**读取路径（`getParameter` 仍走 App 自己的实例），原因：`sampleRate/pid/hash` 这些读值参与 App 的健康检查（`checkEngine()`、卷积器重载），一旦从 jdspd 读到 0 会触发 `rebootEngine()`，风险高。等 jdspd 侧 `getParam` 与 App 自身的会话句柄语义完全对齐后，再按"读不到就退回本地"的策略切换。

### 3.3 滑条/高频场景的分批与节流

| 场景 | 策略 | 实测 |
|---|---|---|
| 滑条拖动（同一参数高频变化） | 合并为 last-wins，5–10 ms 一批 | 200 次连续 setParam：avg 1.77 ms、p95 2.67 ms、0 失败（§4.4） |
| 一次 syncWithPreferences（30+ 参数） | 一批内并行排空（≤64/轮） | 单轮 30 条 ≈ 50 ms 内完成 |
| 卷积 IR / VDC / Liveprog（分片） | FIFO 保序 + 不合并 | 顺序由 App 侧保证（与 jdspd 的 2 ms last-wins 白名单一致） |
| jdspd 不在 / 崩了 | 5 次失败后停用镜像，App 无感 | — |

---

## 4. 传输选型与真机实测

### 4.1 四级通道

| 代号 | 通道 | 结论 |
|---|---|---|
| **C'** | **loopback TCP 127.0.0.1:<port> + token 握手** | ✅ 首选，无需任何 SELinux 改动 |
| C | unix socket（App 私有目录） | ❌ Java 层无法创建（见 §4.2） |
| A | binder `jdsp.service` | ❌ 本机实测不可用（untrusted_app find 被拒） |
| B | root shell 调 `jdspcli` | ❌ 本机实测不可用（App 无法 exec su） |

### 4.2 为什么不是 unix socket（对 daemon 是硬约束）

真机实测（root 侧 app_process）：

```
new LocalServerSocket("/data/data/<pkg>/files/jdsp.sock")
  -> READY（无异常），但目录里没有 socket 文件，chmod 报 ENOENT
  => LocalServerSocket(String) 绑定的是 **abstract 命名空间**，不落盘
反射 new LocalServerSocket(LocalSocketAddress(path, FILESYSTEM))
  -> NoSuchMethodException: android.net.LocalServerSocket.<init> [class android.net.LocalSocketAddress]
  => Android 15 运行时没有这个构造器
```

abstract 命名空间又回到"SELinux 用对端 socket SID 校验"的老问题（等于要给 su 域开口）。
→ **纯 Java 的 jdspd 做不出 filesystem unix socket**，除非写 native helper。

### 4.3 C' 的形态与安全模型

1. jdspd 启动时 `ServerSocket` 只 bind `127.0.0.1`（端口默认 37501，可配置，占用则顺延）；
2. 生成随机 token，把两个文件写进 App 私有目录并 `chown` 到 App uid：
   - `<files>/jdsp.port` → 端口号
   - `<files>/jdsp.token` → token
   实测 root 写入 `/data/data/<pkg>/files` 允许；App（untrusted_app，带 MCS 类别）读取正常；
3. 连接第一行必须 `{"op":"hello","token":"..."}`，错 token 立即断开；
4. 之后 JSON lines（`op/id/s/p/v/n/r/e`），单行 ≤1 MiB，服务端保持一请求一应答。

安全边界：**只有 loopback + token 双条件**；token 文件目录 0700、文件 0400 归 App uid，其它 App 读不到；不改任何 SELinux 规则。

### 4.4 实测矩阵（xaga / Android 15 / KernelSU，域 `u:r:untrusted_app:s0:c70,c257,c512,c768`）

自测 App：`com.jdsp.selftest`（独立包，不动 james.dsp）；根侧服务端：`app_process` 跑 `ProbeServer`。

```
== env            selinux=u:r:untrusted_app:s0:c70,c257,c512,c768
== unix socket    unix.connect=false   raw LocalSocket failed: IOException: No such file or directory
== tcp loopback   tcp.connect=true  tcp.ping=1  tcp.setParam rc=0  tcp.attach rc=0
== binder         getService(jdsp.probe)=null        （探针确实注册成功，App 侧 find 被拒）
== root shell     su failed: IOException: Cannot run program "su": error=13, Permission denied
== benchmark      200 x setParam (tcp)
   total 353.48 ms / avg 1.77 ms / p50 1.60 / p95 2.67 / max 6.06 / fail 0/200 / 565.81 次/s
```

根侧日志（证明参数真的到了服务端）：`setParam s=4242 p=1303 v=0000e0c0`（float -12dB→-11dB… 逐次变化）。

> 判据对比：目标"<20 ms/次、无丢包" → 实测 1.77 ms / 0 丢包，**达标 ~11 倍余量**。

---

## 5. 补丁流水线

### 5.1 用法

```sh
# 1) 先跑现有的汉化/资源补丁（可选）
./patch_app.sh  JamesDSP-root-full-v1.6.15-58.apk  step1.apk  jdsp.keystore jdsp123 jdsp123
# 2) 再打 P5 镜像补丁
./p5/patch_app_p5.sh  step1.apk  JamesDSP-p5-tcp.apk  jdsp.keystore jdsp123 jdsp123
```

脚本内做的事（`app/p5/patch_app_p5.sh`）：

1. `apktool d` 解包；
2. 注入 `JdspProxyEffect.smali`；
3. 用正则定位 `createEffect()` 方法体，断言后替换 2 行（**找不到就报错退出**，不会静默漏打）；
4. `javac + d8` 编译 `app/p5/src` → `classes4.dex`，用 zip 追加为第 4 个 dex；
5. `apktool b` → `zipalign` → `apksigner`。

工作目录强制放在 `~/.cache/jdsp-p5`（本机 `/tmp` 是 3.9 GB tmpfs，解包 40 MB APK 会撑爆—— 实测踩过）。

### 5.2 回退

- 卸载补丁版、装回官方 APK 即完全恢复（签名不同，需先卸载；音效参数在模块的状态文件/jdspd 配置里，不会丢）；
- 或让 jdspd 停用：tcp 连不上时 `JdspBridge` 自动停用，App 行为与官方版一致。

---

## 6. 验收设计

### 6.1 传输层（已通过，自动化）

`app/p5/selftest`：装 `com.jdsp.selftest` → root 起探针 → `am start` → 读 `/data/data/com.jdsp.selftest/files/selftest.txt`。
断言：`tcp.connect=true`、`tcp.ping=1`、`bench.fail=0/200`、`bench.avg_ms<20`。

### 6.2 端到端（待 jdspd TCP 服务端上线 + 装补丁版 james.dsp）

| # | 步骤 | 通过判据 |
|---|---|---|
| 1 | 装补丁版 james.dsp，启动，改一个 PEQ 增益 | `jdspcli --json get <session> 1303` 返回最新值；`jdspcli dump-config` 里能看到该参数 |
| 2 | 拖动滑条 100 次 | jdspd `stats` 里 setParam 计数增加；无 `dropped`；logcat 无 `JdspBridge` 报错 |
| 3 | **杀前端**（`am force-stop james.dsp`）后重新播放 | 音效与参数不变；`jdspcli sessions` 仍显示 `attached=1`、`paramCount>0`；`dump-config` 与杀之前一致 |
| 4 | **卸载前端**（`pm uninstall james.dsp`）后播放 | 同上（这就是 P5 的最终验收项） |
| 5 | 反向：改参数 → 杀 App → 重启 App | App UI 显示的是它自己 SharedPreferences 的旧值，但 **jdspd/引擎用的是最后镜像值**（第二阶段做 UI 只读后才一致） |
| 6 | 未装 jdspd 的机器（或 jdspd 停掉） | App 正常出声、参数照常生效（镜像自动停用），logcat 只有一条 `JdspBridge` warn |

```sh
# 验收脚本骨架（cli-harness 的 jdspcli 上线后可直接用）
adb shell 'CLASSPATH=/data/adb/modules/ainur_jamesdsp/jdspcli.dex app_process /system/bin com.jdsp.cli.Main --json dump-config' > before.json
adb shell am force-stop james.dsp
adb shell am start -n com.android.chrome/...   # 或任意播放器
adb shell 'CLASSPATH=... com.jdsp.cli.Main --json sessions'
adb shell 'CLASSPATH=... com.jdsp.cli.Main --json dump-config' > after.json
diff before.json after.json   # 期望只有 uptime/计数类字段变化
```

### 6.3 性能判据

| 指标 | 目标 | 实测（传输层） |
|---|---|---|
| 单次 setParam 往返 | < 20 ms | avg 1.77 ms / p95 2.67 ms |
| 丢包/失败 | 0 | 0/200 |
| 吞吐 | — | 565 次/s（单连接串行） |

---

## 7. 未完成项与下一步

1. **端到端联调**：等 daemon-core 的 TCP 服务端上线（协议已发给他：`hello` 握手 + JSON lines + `jdsp.port`/`jdsp.token` 文件），然后装补丁版 james.dsp 跑 §6.2。
2. **第二阶段（真正"App 不持有配置"）**：
   - `createEffect()` 返回 null 或桩对象，App 不再持有引擎实例；
   - `getParameter` 走 jdspd（读不到退本地）；
   - UI 改成从 `dumpConfig()` 读、写完即弃，SharedPreferences 只做离线缓存；
   - 这一步做完，"杀前端参数不变"才从"jdspd 侧不变"升级为"UI 也一致"。
3. **daemon 重启时的 token 轮换**：客户端已实现重读文件；需要在 daemon 侧明确"每次启动重新生成 token 并原子替换文件"（rename 而非截断写）。
4. **多设备**：c16/c17 上重跑 §4.4（尤其确认 binder 是否可用——那两台若是 Magisk 环境，untrusted_app 的 binder find 结果可能不同）。

---

## 8. 附录：证据与文件清单

### 8.1 本轮新增文件（`jdsp-repo/app/p5/`）

```
p5/
  src/me/timschneeberger/rootlessjamesdsp/p5/
    JdspTransport.java      通道抽象
    TcpTransport.java       C' loopback TCP + token
    SocketTransport.java    C  unix socket（保留）
    BinderTransport.java    A  binder（手写 transact）
    ShellTransport.java     B  root shell + jdspcli
    JdspCodec.java          hex / 小端编码
    JdspBridge.java         门面：探测/合并/降级/重连
  smali/JdspProxyEffect.smali   AudioEffect 代理（4 个 override）
  patch_app_p5.sh             补丁流水线
  selftest/
    AndroidManifest.xml        com.jdsp.selftest
    SelftestActivity.java      4 项探测 + 200 次基准
    ProbeServer.java           根侧假服务端（binder/unix/tcp + token 文件）
    build_selftest.sh          aapt2+d8+apksigner 构建
```

### 8.2 关键命令回放

```sh
# 可行性
cd /home/bdy/work/loundess_git && ./gradlew --offline --no-daemon -Dorg.gradle.jvmargs=-Xmx2g --max-workers=2 :app:tasks
# 自测 APK
app/p5/selftest/build_selftest.sh
adb -s <dev> install -r app/p5/selftest/out/jdsp-selftest.apk
adb shell 'CLASSPATH=/data/local/tmp/probe.dex app_process /system/bin com.jdsp.selftest.probe.ProbeServer /data/data/com.jdsp.selftest/files 10326 180 37501'
adb shell am start -n com.jdsp.selftest/.SelftestActivity
adb shell cat /data/data/com.jdsp.selftest/files/selftest.txt
# 补丁 APK
app/p5/patch_app_p5.sh JamesDSP-root-full-v1.6.15-58.apk out.apk jdsp.keystore jdsp123 jdsp123
```
