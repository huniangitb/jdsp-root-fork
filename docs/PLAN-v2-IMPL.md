# JamesDSP 后端派发架构 (v2) —— 实施计划与执行记录

状态：**实施中**（本文件是 PLAN-v2 的落地版：把设计稿拆成可执行任务、记录真机验证结论）
基线：v6.7.2-fork（versionCode 112）
关联：`docs/PLAN-v2.md`（设计稿，不动）

---

## 1. 本轮目标

把 PLAN-v2 的 P0–P6 落成代码并逐阶段验收：

| 阶段 | 内容 | 本轮产物 |
|---|---|---|
| P0 | 干净基线（仓库 + 设备） | 模块/引擎/脚本回到 v6.7.2；设备重装并验证 |
| P1 | jdspd 骨架 + AIDL + jdspcli（只读） | `backend/` 全部源码 + dex；`jdspcli sessions/stats/dump` |
| P2 | 后端主动挂载（createEffect / 销毁）+ 与 postprocess 二选一 | `EffectManager`；`mode=auto|backend` |
| P3 | 实时调参 + 参数持久化迁到 ConfigStore | `ParamBus`/`ConfigStore`；`jdspcli set/get` |
| P4 | 按应用策略（uid→pkg + 排除表） | `PolicyEngine`；`jdspcli policy` |
| P5 | 前端改造（App 侧全部走 AIDL，去掉 SharedPreferences 依赖） | 见 §8（本轮实现 AIDL 客户端 + 桥接方案，App 全量替换另行验收） |
| P6 | 多设备验证 + 打包 | 本轮设备（xaga / Android 15）验证 + 模块打包；c16/c17 待接入 |

---

## 2. 真机验证结论（2026-10-24，xaga / Android 15 / SDK35 / KernelSU）

这些结论直接决定了实现方式，先记下来，避免后人重复试错。

### 2.1 root 进程可以对**任意 session** createEffect

用 `app_process`（uid 0）调 `AudioEffect(UUID,UUID,priority,session)` 隐藏构造：

- `session=0` → 新建全局实例，`hasControl=true`；
- 自己的 AudioTrack session → 新建实例；
- **别的进程的 session（正在放音的 tone）→ 成功**，AOSP `AudioFlinger::createEffect()` 对普通
  sessionId **没有归属校验**（源码里就是一句 TODO），root 直接可建。

### 2.2 已有实例时返回的是**既有实例的 handle**（关键）

同一 session 上已存在同 uuid/type 的实例（框架 postprocess 建的）时，
`createEffect` 返回 `ALREADY_EXISTS` 语义：拿到的是**既有实例**的控制句柄
（实测返回 id=35 = dumpsys 里的既有实例，`enabled=true`，chain 里没有出现第二个实例）。

→ 这意味着 **jdspd 不需要接管创建权也能实时调参**：postprocess 负责"挂"，jdspd 负责"控"。
这就是 P3 能立刻落地的原因。

### 2.3 没有实例时新建实例

会话上没有对应实例时，`createEffect` 会为该 session 建链并创建实例
（实测 session 145：新建 id=91，`updatePolicyState ... registered 1`）。
→ P2 的 `mode=backend`（关掉 postprocess，由 jdspd 自己挂）可行。

### 2.4 app_process 可以注册 binder 服务

`ServiceManager.addService("jdsp.probe", binder)` 在 su 域成功，
`service list` 可见（`150 jdsp.probe: []`），logcat **无 AVC 拒绝**。
`getService/checkService` 正常。→ AIDL 方案的 IPC 底座成立。

### 2.5 引擎实例位置与状态文件

- 效果模块加载在 **HAL 进程**（`android.hardware.audio.service.mediatek`，本次 pid 1330），
  不是 audioserver；参数链路 `EffectHandle → EffectModule → EffectsFactory → 引擎`。
- 状态文件 `/data/vendor/audio/jamesdsp_state.bin`（`vendor_audio_data_file`，1041:1005，0660）。
- 设备当前装的是 **6.7.3~6.7.6 实验引擎**（`libjamesdsp.so` 里带 `jdsp_ctl.sock`/`PING`/`STATS`，
  sha256 `6d7da1b0…`）；仓库里的是干净 v6.7.2（sha256 `b6e1fc1f…`，无 socket 代码）。
  → P0 必须重装干净基线（这也是本轮第一步要做的设备操作）。

### 2.6 KernelSU 支持模块 sepolicy 规则

`ksud sepolicy apply <file>`（service.sh 里已经在用）。需要给 App → jdspd 的 binder
访问加规则时，用同一机制。

---

## 3. 目标架构

    ┌──────────────────────────┐
    │ 前端 App（只做 UI）      │  普通 app（untrusted_app）
    └────────────┬─────────────┘
                 │  AIDL / Binder：jdsp.service（或 root shell → jdspcli 兜底）
    ┌────────────▼─────────────┐
    │ jdspd（常驻，root）      │  App_process (ART) 守护进程
    │  SessionWatcher          │  轮询 AudioFlinger dump 得到 session↔(pid,uid,pkg)
    │  EffectManager           │  每 session 一个 AudioEffect 句柄（建/接/释放）
    │  ParamBus                │  setParam/getParam 直通句柄，批量提交
    │  ConfigStore             │  /data/adb/jdspd/config.json（权威配置）
    │  PolicyEngine            │  uid→pkg + 每应用 跟随/强制开/排除
    │  BinderService           │  IJdspService
    └────────────┬─────────────┘
                 │  AudioEffect 句柄
    ┌────────────▼─────────────┐
    │ AudioFlinger / HAL 进程  │  每条 music session 的 EffectChain
    │  libjamesdsp.so 实例     │  引擎（状态持久化 + 热加载）
    └──────────────────────────┘

---

## 4. 与设计稿的差异（及原因）

| 设计稿 | 实现 | 原因 |
|---|---|---|
| `/system/bin/jdspd`（native，SELinux 域 jdspd） | `app_process` 起的 Java 守护进程 | 本机是 KernelSU 模块环境，没有 ROM 编译；`AudioEffect` 只在 Java 层公开（native 需要 libaudioclient 头/ABI 对齐）。Java 守护进程能用同一套 framework API，且句柄语义完全一致。域仍是 root（su/ksu），binder 能力已验证 |
| SessionWatcher 走 AudioPolicy/AudioFlinger binder | 解析 `dumpsys media.audio_flinger`（1 s 轮询） | IAudioFlinger 公开 AIDL 没有"列 session"方法；dump 里 track 表（Client/Session）与 "N effects for session X" 齐全，root 可读。解析器独立成类，后续可换成 binder 实现 |
| `<postprocess>` ↔ backend 运行中切换 | 开机时切换（`mode` 写入配置，`service.sh`/安装时决定是否注入 `<apply>`） | postprocess 由 AudioPolicyManager 在 audioserver 启动时读 XML，运行中改 XML 不可靠；开机切换是一条命令、可回退 |
| 参数持久化直接替掉 state.bin | ConfigStore 为权威，**启动时导入一次 state.bin**，之后由 jdspd 回放 | 平滑迁移：老用户既有参数不丢；state.bin 继续作为引擎侧兜底 |

---

## 5. 目录与构建

    backend/
      aidl/android/jdsp/backend/IJdspService.aidl   # 契约
      aidl/android/jdsp/backend/JdspSession.aidl
      src/com/jdsp/common/     # 双方共享常量
      src/com/jdsp/backend/    # jdspd 守护进程
      src/com/jdsp/cli/        # jdspcli
      test/                    # 主机侧单测（纯 Java，不依赖设备）
      out/                     # jdspd.dex / jdspcli.dex（gitignore）
      build.sh                 # AIDL → javac → d8（只用 Android SDK）

构建：`cd backend && ./build.sh`（产物 `out/jdspd.dex`、`out/jdspcli.dex`）。

运行（设备）：

    # 守护进程
    CLASSPATH=/data/adb/modules/ainur_jamesdsp/jdspd.dex \
        app_process /system/bin --nice-name=jdspd com.jdsp.backend.Daemon
    # CLI
    CLASSPATH=/data/adb/modules/ainur_jamesdsp/jdspcli.dex \
        app_process /system/bin com.jdsp.cli.Main sessions

---

## 6. AIDL 契约（`IJdspService`）

见 `backend/aidl/android/jdsp/backend/IJdspService.aidl`。要点：

- 会话：`listSessions/getSession/attach/detach`
- 参数：`setParam/getParam/commit`（byte[] 直传引擎参数，daemon 不解释语义）
- 配置：`dumpConfig/dumpSession/loadPreset/savePreset`
- 策略：`setAppPolicy/getAppPolicy/listPolicies`（0=跟随 1=强制开 2=排除）
- 模式与诊断：`setMode/getMode/stats/ping/getVersion`

---

## 7. 验收映射（PLAN-v2 §6）

| 验收项 | 验证方式 |
|---|---|
| 1. 无 App 播放时 `jdspcli sessions` 显示 `attached=1`、`paramCount>0` | 设备脚本：装测试播放器/用 tone.dex，force-stop App 后跑 |
| 2. `jdspcli get <session> <id>` 返回最后设置值 | 脚本对比 set→get 字节 |
| 3. set 后 20 ms 内 get 为新值，无 underrun/爆音日志 | ParamBus 直接落句柄（实测句柄内往返 ~3 ms），logcat 检索 underrun |
| 4. 排除列表应用 `attached=0`，其他 `attached=1` | 两个不同 uid 的播放器 + policy 排除其中一个 |
| 5. 72 h 长稳 | 本轮给出 soak 脚本与首轮结果，长周期由用户跑 |

---

## 8. P5（前端）本轮范围

App 是预编译 APK（Kotlin 源码在 `../loundess_git`，Gradle 全量构建在本机不可行）。
本轮先交付：

1. `IJdspService` + `jdspcli`（App 端调用的同一套契约）；
2. App 侧接入方案：`JamesDspRemoteEngine` 的参数下发改为
   `jdsp.service`（binder）或 root shell `jdspcli set`（兜底）；
3. App 仍是"只读 UI"：不持有配置、不 new 自己的 AudioEffect；
4. App 改造的验收（杀/卸载前端配置不变）其实现有架构已满足，v2 下由 ConfigStore 保证。

---

## 8.1 传输层决策（2026-10-24 追加）

三条通道，运行时按顺序降级：

| 通道 | 路径 | 谁能用 | 状态 |
|---|---|---|---|
| A binder | service `jdsp.service` | root/su 域（CLI、jdspd 自身） | 已验证可用（跨进程 AIDL 实测通过） |
| **C unix socket** | `/data/data/james.dsp/files/jdsp.sock`（app_data_file） | 本 App + root | **主用**，App 走这条 |
| B root shell | `su -c jdspcli ...` | App（root flavor 自带 libsu） | 兜底 |

为什么 App 不走 binder：AOSP servicemanager 对 `service_contexts` 里**没有登记**的服务名，
按注册进程的域给 SID；jdspd 跑在 ksu/su 域 → 服务标签即 su 域。要让 untrusted_app 连上就得
`allow untrusted_app su:binder { call transfer }` + `service_manager find`，等于把 su 域对普通
App 敞开，**不采纳**。socket 放在 App 自己的数据目录里：只有本 App（app_data_file）与 root 能连，
不需要任何 SELinux 放行，且是长连接，滑条拖动也扛得住。

socket 协议（JSON lines，一行请求一行响应）：

    {"cmd":"ping"}                                    -> {"ok":true,"version":"jdspd 2.0.0"}
    {"cmd":"sessions"}                                -> {"ok":true,"sessions":[{sessionId,uid,pkg,attached,enabled,paramCount,effectId,policyMode,pid}]}
    {"cmd":"setParam","session":145,"param":1500,"value":"cdccccbd..."} -> {"ok":true,"status":0}
    {"cmd":"getParam","session":145,"param":1500,"vsize":64}            -> {"ok":true,"value":"..."}
    {"cmd":"attach"|"detach","session":145}
    {"cmd":"mode","mode":"auto"|"backend"}
    {"cmd":"policy","pkg":"com.x","mode":2} / {"cmd":"policies"}
    {"cmd":"dumpConfig"} / {"cmd":"stats"}

服务端 handler 与 AIDL 共用同一份实现，`jdspcli --socket` 也走这条通道。

## 9. 风险与回退

| 风险 | 对策 |
|---|---|
| dumpsys 解析随 ROM 变化 | 解析器单测 + 失败时降级只做 attach(已知 session)；CLI 手工 attach |
| 双实例（postprocess + backend 同时挂） | `ALREADY_EXISTS` 语义保证不重复；模式切换要求重启 |
| SELinux：App 直连 `jdsp.service` | 先给 su 域打通（CLI 可用），App 走 root shell 兜底；需要时 `ksud sepolicy apply` 加规则 |
| 守护进程被杀 | `service.sh` 看护循环自动重启；`stats` 里暴露启动时间/重启次数 |
| 回退 | 模块保留 postprocess 链路；`mode=auto` + 禁用 jdspd 即回到 v6.7.2 行为 |
