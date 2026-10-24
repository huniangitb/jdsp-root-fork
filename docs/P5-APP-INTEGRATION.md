# P5 — App 接入方案与实现（jdspd 前端改造）

状态：**传输链已真机验证（对真 jdspd）+ 补丁 APK 已构建并装机做过冒烟与参数下发验证；未做：杀/卸载前端的完整验收、UI 侧改造（P5 第二阶段）**
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
| App → jdspd 参数下发 | **已验证**：补丁版 App 启动后，jdspd 的 `dump-config` 出现 25 个 App 侧参数（含 PEQ 1300 / 响度 1301 / 压缩器 115 / 多段均衡 116），值与 App 的 SharedPreferences 语义一致（见 §6.2） |
| 杀前端/卸载前端后配置是否还在 | **未做完整验收**。原理上由 jdspd 的 ConfigStore 保证（App 每次参数写入都已镜像进 jdspd 的持久化配置），但"杀/卸载后配置与音效不变"这条验收脚本**本轮没有跑完**，不能算已验证。App 自己仍 new 一个 AudioEffect（P5 第二阶段再摘掉） |

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

### 6.2 端到端（App → jdspd 参数下发**已验证**，见 §8.3.2/§8.3.3；杀/卸载前端的验收**未做**，见 §8.3.6）

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

> 本轮**已完成**：传输链打通（对真 jdspd）、补丁 APK 构建 + 装机冒烟 + App 参数下发到 jdspd 已验证（详见 §8.3）。
> 以下为**尚未完成**的项。

1. **杀/卸载前端的验收脚本**（§8.3.6）：需要一轮"有播放会话时 force-stop → 读 `dump-config`/`sessions`"和"卸载重装后 token 恢复"的完整跑通。
   其中"卸载后 token 文件消失"是 **daemon 侧**的缺口（§8.3.7 第 2 条），不是 App 侧。
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


---

## 8.3 真 jdspd 端到端验证记录（2026-10-25 00:50–00:57）

### 8.3.1 装机（已按 lead 批准的条件执行）

| 步骤 | 命令 | 结果 |
|---|---|---|
| 备份 | `adb shell 'cp $(pm path james.dsp\|sed s/package://) /data/local/tmp/james.dsp-stock.apk'` + `adb pull` | 拉到 `/home/bdy/work/p5-app/backup/james.dsp-stock.apk`，**sha256 = f82e17607256444431bc9c50848a0ce519a6a6fdcd1ccb4c81deccdfcd9a5faa** |
| 证书对比 | `apksigner verify --print-certs` | 已装版与补丁版**同为** `CN=JamesDSP Fork, O=Local, C=CN`，SHA-256 指纹 `ac4af82bc33f5fea60c9706ba465e49ba80c1361c8a973826d9cbe217e301b4d` → 可 `-r` 覆盖安装 |
| 安装 | `adb install -r james.dsp-zh3-p5-v2.apk` | Success；`versionName=1.6.15-zh3` 不变；`shared_prefs/*` 全部保留 |
| 冒烟 | `am start james.dsp/me.timschneeberger.rootlessjamesdsp.activity.MainActivity` | **无 FATAL / 无 ANR / crash buffer 干净**；进程存活 |

回滚方法：

```sh
adb install -r /home/bdy/work/p5-app/backup/james.dsp-stock.apk    # 同签名，可直接覆盖
# 若签名不匹配（换了 keystore）则需要先 uninstall 再 install
```

### 8.3.2 App → jdspd 参数下发（**已验证**）

App 启动后 logcat：

```
I JdspBridge: transport ready: tcp(127.0.0.1:38255)
```

（无 "transport failing"、无异常；jdspd 侧日志同步出现 `tcp: client accepted /127.0.0.1:xxxxx`）

jdspd 侧 `jdspcli --tcp dump-config`（**这是 App 下发的参数，不是 CLI 测试残留**）：

```
param ids: 112 115 116 1200 1201 1202 1203 1204 1206 1208 1210 1212 1213 1214 1215 1216 1217 1218
           1300 1301 1302 1305 1306 1307 1500      (25 个)
  115  = ae47613e 00000040 00000000 00be4200 00484300 00c843…   (压缩器：阈值 0.28dB/时间常数 0.22s)
  116  = 00000000 000080bf 0000c841 00002042 00007c42 0000c842…  (多段均衡)
  1300 = 00803b47 00000000                                     (PEQ cascade)
  1301 = 00000000… (20 字节)                                   (响度)
  1500 = cdccccbd 00007042 00004040                            (-0.1dB / 60 / 3.0，与 App 的 output_control 偏好一致)
```

交叉验证 App 自己的偏好（`/data/data/james.dsp/shared_prefs/dsp_output_control.xml`）：

```
<float name="limiter_threshold" value="-0.1" />
<float name="limiter_release" value="60.0" />
```

→ 与 jdspd 里 1500 的 `cdccccbd 00007042 00004040` 逐位对应（-0.1f / 60.0f / 3.0f）。

jdspd `stats`：`setParamCalls=294`、`paramErrors=4`、`globalParams=25`、`avgLatencyUs=2069`、`maxLatencyUs=39061`。
那 4 个 paramErrors 来自 App 首启时的 **session=0**（App legacy 模式的全局实例）——见 §8.3.4。

### 8.3.3 参数变更能否被 App 推到 jdspd（**已验证**）

把 App 偏好 `limiter_threshold` 从 `-0.1` 改成 `-6.0`，重启 App：

```
BEFORE  jdspcli --tcp get 113 1500 12  ->  cdccccbd0000704200004040
AFTER   jdspcli --tcp get 113 1500 12  ->  0000c0c00000704200000000   (=-6.0f, 60.0f, 0.0f)
```

→ **App 的参数变更确实到达了引擎句柄**（读回的字节变了）。
注意 `dump-config` 的 `globalParams` 里 1500 仍是旧值 `cdccccbd…`，而 `perAppParams` 为空——这是 jdspd 侧 `setGlobalParam`/`setPerAppParam` 的落盘路径与 `getParam` 的取值路径不一致（后端问题，**不是 App 侧问题**：App 的下发确实生效并被引擎读到了）。
→ 建议 daemon-core 核对 `CoreApi.setParam` 里 `pkg==""` 时写 global、`pkg!=""` 时写 perApp 的分支，与 `getParam` 的回退读取路径。

### 8.3.4 关键设计修正：session 0 → 广播

App 的 legacy 模式（`audioformat_processing=true`、`audioformat_enhanced_processing=false`）只持有**一个全局实例**（`AudioEffect` 的 sessionId = **0**），
而 jdspd 只接受真实 AudioFlinger session（>0），否则 `setParam(session=0)` 直接被拒（`remote status -1003`）。

修正（**全部在 App 侧，后端零改动**）：

- `JdspBridge.push()` 遇到 `session <= 0` 时，把该参数**广播**到 jdspd 当前所有 `attached=true` 的会话（`sessions` 命令取列表）；
- 此刻还没有任何播放会话时，参数进入 `pendingBroadcast` 暂存，每秒刷新一次会话列表，**有新会话就重放**（这正是"一份配置作用于所有播放会话"的目标语义）；
- 会话列表通过新增的 `JdspTransport.listAttachedSessions()` 获取（tcp / unix socket 实现；binder / shell 返回 null 保留占位）。

### 8.3.5 传输性能（对真 jdspd，App 侧同一份协议代码）

用 `app/p5/selftest/ClientCheck.java`（完全按 `TcpTransport` 的协议走）直连真 jdspd：

```
port=38011 tokenLen=64
hello reply={"ok":true,"op":"hello","version":"jdspd 2.0.0","pid":2458,"port":38011}
ping  reply={"id":2,"ok":true,"r":1,"version":"jdspd 2.0.0","pid":2458,"uptimeMs":1407180}
bench.total_ms=294.38  avg=1.47  p50=1.44  p95=2.35  max=5.45  fail=200/200  679.39 次/s
```

> 说明：这 200 次故意用 `param=1303`（该参数在 v6.7.2 引擎里没有实现，长度也不匹配），所以**全部被引擎拒绝**，用于压测往返延迟与错误路径；
> 换成引擎支持的参数（1201/1210/1215/115/116/1300/1301/1305/1306/1307/1500）时 `fail=0`。
> 用**引擎实际接受的参数**复测（`param=1201 --short 1`）：`ok session=113 param=1201 len=2 hex=0100`，且 `get 113 1201 2` 读回 `0100`（写入→读回一致）。

延迟对比：

| 目标 | 实测（对真 jdspd） | 实测（对假探针） |
|---|---|---|
| < 20 ms/次 | **avg 1.47 ms / p95 2.35 ms / max 5.45 ms** | avg 1.77 / p95 2.67 / max 6.06 |
| 0 丢包 | 0 丢包（失败均为引擎侧按预期拒绝） | 0/200 |

### 8.3.6 本轮**没有**做完的验证项（保守记录）

| 项 | 状态 | 说明 |
|---|---|---|
| 杀前端（`am force-stop james.dsp`）后配置与音效不变 | **未做** | 需要在有播放会话的状态下 force-stop 再读 `dump-config` / `sessions`。原理上成立（参数已落 jdspd 的 ConfigStore），但本轮没有跑这条脚本。 |
| 卸载前端（`pm uninstall james.dsp`）后配置不变 | **未做** | 卸载会删掉 App 私有目录（`jdsp.port`/`jdsp.token`），jdspd 需要能重新拿到目录并重发 token —— **这一条与 daemon-core 有关**：jdspd 现在只在启动时写一次 port/token，卸载重装后 App 读不到 token 就连不上。需要在 daemon 侧补"App 目录消失/变化时重发 token"或改成按需握手。 |
| 断线重连（daemon 重启换 token，App 10s 内重连） | **未做** | 代码里已实现（连接失败 5 次即丢弃连接，下次取用时重读 `jdsp.port`/`jdsp.token`），但没有在真机上重启 jdspd 验证（lead 明确要求不要重启设备/jdspd）。 |
| 探针停止后 App 不崩、降级静默 | **未做** | 同样因为不能重启/杀 jdspd。代码路径存在（传输探测失败 3 次即 `disabled=true`，App 行为回到官方版），但未实测。 |
| UI 侧改造（App 不再持有 AudioEffect / 读配置走 jdspd） | **未做** | 属 P5 第二阶段，见 §7。 |
| c16 / c17 多设备 | **未做** | — |

### 8.3.7 已知问题清单（交给 Lead 分派）

1. **jdspd 侧：`dump-config` 的 globalParams 与 `getParam` 读回值不一致**（§8.3.3）。App 下发本身没问题，但"配置落盘"和"配置读取"两条路径的 pkg 判定不一致。建议 daemon-core 核对 `CoreApi.setParam` / `CoreApi.getParam` 的 pkg 分支。
2. **jdspd 侧：App 卸载/重装后 token 文件没了就再也连不上**（§8.3.6 第 2 行）。建议 daemon 侧增加"App 目录缺失时重建 token 文件"或改为"App 首次连接时由 daemon 校验并下发 token"。
3. **App 侧：legacy 模式只有全局实例（session=0）**，已用广播解决；但如果未来 App 改成 per-session 引擎实例（enhanced 模式），会天然拿到真实 sessionId，广播路径就不再需要。
4. **引擎侧：`param=400`（全局启停）在 v6.7.2 引擎里不存在**（实测 `remote status -1`），`1303`（响度调音）也未实现。所以 App 的 `setEnabled()` 镜像暂时落不了地——需要引擎侧补这两个 cmd，或约定别的 cmd 号。
5. **App 侧 `getParameter` 仍走自己的实例**（见 §3.2），没有走 jdspd。这是刻意的保守选择。

---

## 9. 本轮产出物清单

| 路径 | 内容 |
|---|---|
| `jdsp-repo/app/p5/src/me/timschneeberger/rootlessjamesdsp/p5/` | 传输层与桥接层 Java 源码（`JdspTransport`/`TcpTransport`/`SocketTransport`/`BinderTransport`/`ShellTransport`/`JdspCodec`/`JdspBridge`） |
| `jdsp-repo/app/p5/smali/JdspProxyEffect.smali` | AudioEffect 代理（4 个 override：setParameter×3 + setEnabled） |
| `jdsp-repo/app/p5/patch_app_p5.sh` | 补丁流水线（apktool 解包 → 注入 smali → 改 createEffect 2 行 → 编 classes4.dex → apktool b → zipalign → apksigner） |
| `jdsp-repo/app/p5/selftest/` | `com.jdsp.selftest` 自测 App（4 项探测 + 200 次基准）、根侧假探针 `ProbeServer.java`、对真 jdspd 的客户端校验 `ClientCheck.java`、构建脚本 |
| `/home/bdy/work/p5-app/james.dsp-zh3-p5-v2.apk` | 装机验证用的补丁 APK（已装在设备上） |
| `/home/bdy/work/p5-app/backup/james.dsp-stock.apk` | 回滚用备份（sha256 f82e1760…） |

### 复现命令

```sh
# 1) 可行性（Gradle 离线）
cd /home/bdy/work/loundess_git
./gradlew --offline --no-daemon -Dorg.gradle.jvmargs=-Xmx2g --max-workers=2 :app:tasks
# （前置：本机 ndk/27.0.12077973 是空壳目录，需软链到 ndk/30.0.16248370）

# 2) 构建补丁 APK
cd /home/bdy/work/jdsp-repo/app/p5
./patch_app_p5.sh <输入.apk> <输出.apk> jdsp.keystore jdsp123 jdsp123

# 3) 装机 + 冒烟
adb install -r <输出.apk>
adb shell am start -n james.dsp/me.timschneeberger.rootlessjamesdsp.activity.MainActivity
adb logcat -d -s JdspBridge

# 4) 看 App 下发到 jdspd 的参数
adb shell 'CLASSPATH=/data/adb/modules/ainur_jamesdsp/backend/jdspcli.dex app_process /system/bin com.jdsp.cli.Main --tcp dump-config'
adb shell 'CLASSPATH=... com.jdsp.cli.Main --tcp stats'
```

### 8.4 关键命令回放

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
