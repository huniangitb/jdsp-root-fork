# JamesDSP 后端派发架构 (v2) —— 实施计划与执行记录

状态：**P0–P4 已验证；P5 完成镜像接入但未完全解耦；P6 仅完成单机验证与打包**（本文件是 PLAN-v2 的落地版：把设计稿拆成可执行任务、记录真机验证结论）
验收证据：`docs/ACCEPTANCE-P0.md`、`docs/ACCEPTANCE-V2.md`、`docs/ACCEPTANCE-P2-P4-LEAD.md`、`docs/ACCEPTANCE-v2-P1-P4.md`、`docs/P5-APP-INTEGRATION.md`
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
                 │  loopback TCP + token（App）；Binder jdsp.service（root CLI）
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

    # 正常部署由模块 service.sh → jdspd.sh 看护，不要手工再起第二实例。
    # 手工调试（先停止看护与现有 daemon，并保留现场）：
    CLASSPATH=/data/adb/modules/ainur_jamesdsp/backend/jdspd.dex \
        app_process /system/bin --nice-name=jdspd com.jdsp.backend.Daemon
    # CLI（debug 时可用 /data/local/tmp/jdspcli.dex）
    CLASSPATH=/data/adb/modules/ainur_jamesdsp/backend/jdspcli.dex \
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

## 8. P5（前端）交付范围与未完成项

App 的预编译 APK 通过 `app/p5/patch_app_p5.sh` 打补丁，本轮交付的是**参数镜像接入**：

1. `JamesDspRemoteEngine.createEffect()` 使用 `JdspProxyEffect`，保留原 AudioEffect 调用，同时把参数经 TCP 镜像给 jdspd；
2. `jdsp.service` 是 root CLI 的 AIDL 通道，**不是普通 App 的传输**；App 实际使用 loopback TCP + token；
3. App 侧 UI 状态/SharedPreferences 与自身 AudioEffect **尚未完全移除**，读取路径也仍走 App 自己的实例；
4. 已验证：补丁 App 可启动、连接真 jdspd、参数到达后端与引擎；App 连接 token 文件删除后由 daemon 低频自愈重建。

**这不是 PLAN-v2 §3 所描述的“前端只做 UI”的完全实现**。P5 第二阶段必须继续：
- 移除 App 自持有 AudioEffect 的参数控制路径，统一使用 jdspd 的会话与配置模型；
- 明确读取配置的降级语义（不能把 GET_PARAM 不支持/读 0 误判成引擎故障）；
- 完整验收杀前端/卸载前端/重装后的配置与音效连续性；
- 验证断线重连、daemon 重启、无后端时原 App 行为回退。

详见 `docs/P5-APP-INTEGRATION.md`，其“已实现”字样均仅指镜像链路，不代表完全解耦。

## 8.1 传输层决策（2026-10-24 追加，已真机验证）

| 通道 | 形态 | 实测可用性 | 用途 |
|---|---|---|---|
| **C' loopback TCP + token** | jdspd 绑 127.0.0.1:0，端口与 token 写进 App 私有目录 | **App（untrusted_app）与 root 都可用**；200 次 setParam avg 1.77 ms / p95 2.67 ms / 0 失败 | **主用** |
| A binder | service `jdsp.service` | root 域可用；**untrusted_app 不可用**（getService 返回 null，find 被拒） | CLI / 其它 root 工具 |
| B root shell | `su -c jdspcli ...` | 取决于用户是否给该 App 授权；测试 App 未授权 → 不可用 | 兜底 |

### 为什么不是 unix socket

`LocalServerSocket(String)` 只创建 **abstract 命名空间**（不落盘）；能创建 filesystem 命名空间的
`LocalServerSocket(LocalSocketAddress)` 在 Android 15 运行时是隐藏 API，实测 `NoSuchMethodException`。
纯 Java 守护进程无法在 `/data/data/<pkg>/files/` 下建 socket 文件，除非引入 native 辅助进程 ——
不值得，loopback TCP 达到同样效果。

### 为什么不是 binder（给 App 用）

AOSP servicemanager 对 `service_contexts` 里没有登记的服务名，按注册进程的域给 SID；
jdspd 跑在 ksu/su 域 → 服务标签即 su 域。要让 untrusted_app 连上就得
`allow untrusted_app su:binder { call transfer }` + `service_manager find`，等于把 su 域对普通
App 敞开，**不采纳**。

### C' 的协议

    # 发现
    /data/data/james.dsp/files/jdsp.port    纯数字端口，mode 0400，owner = App uid
    /data/data/james.dsp/files/jdsp.token   32 字节随机 hex，mode 0400
    （每次 jdspd 启动重新生成；App 每次连接前重读）

    # 握手（首行）
    {"op":"hello","token":"<hex>"}          -> {"ok":true}
    # 之后一行一请求
    {"cmd":"ping"}                                    -> {"ok":true,"version":"jdspd 2.0.0"}
    {"cmd":"sessions"}                                -> {"ok":true,"sessions":[{sessionId,uid,pkg,attached,enabled,paramCount,effectId,policyMode,pid}]}
    {"cmd":"setParam","session":145,"param":1500,"value":"cdccccbd..."} -> {"ok":true,"status":0}
    {"cmd":"getParam","session":145,"param":1500,"vsize":64}            -> {"ok":true,"value":"..."}
    {"cmd":"attach"|"detach","session":145}
    {"cmd":"mode","mode":"auto"|"backend"}
    {"cmd":"policy","pkg":"com.x","mode":2} / {"cmd":"policies"}
    {"cmd":"dumpConfig"} / {"cmd":"stats"}

安全：只绑 loopback、token 只在 App 私有目录（0700，其它 App 读不到）、连接数上限 + 空闲超时。
服务端 handler 与 AIDL 共用同一份 CoreApi 实现；`jdspcli --tcp` 走同一条通道。

## 9. 风险与回退

| 风险 | 对策 |
|---|---|
| dumpsys 解析随 ROM 变化 | 解析器单测 + 失败时降级只做 attach(已知 session)；CLI 手工 attach |
| 双实例（postprocess + backend 同时挂） | `ALREADY_EXISTS` 语义保证不重复；模式切换要求重启 |
| SELinux：普通 App 不能访问 su 域 binder | **不给 untrusted_app 开放 su 域**；App 使用 loopback TCP + 私有 token，root CLI 使用 binder |
| 守护进程被杀 | `service.sh` 看护循环自动重启；`stats` 里暴露启动时间/重启次数 |
| 回退 | 模块保留 postprocess 链路；`mode=auto` + 禁用 jdspd 即回到 v6.7.2 行为 |

---

## 10. 交付结果（2026-10-25 09:30 更新）

### 最终验收状态（新设备 64a4bad0 / ColorOS 16 / Android 16）

两条核心判据**均已确证 PASS**（我已亲自复跑验证）：

| 判据 | 结果 | 证据 |
|---|---|---|
| A：后端完全脱离前端可用 | ✅ | force-stop 后新会话 attached、配置 25 参数保留、setParamCalls 467→468 |
| B：音效按参数真实生效 | ✅ | 离线渲染：postGain +12 dB → 电平 **12.00 dB**，方向正确，恢复偏差 **0.00 dB** |

`scripts/verify-signal.sh --serial 64a4bad0` → **13 passed / 0 failed / 0 skipped**。

### 已补验的卸载前端场景（原“未跑”项，现已完成）

- 卸载 `james.dsp` 后：App 目录与端点文件消失，但后端配置**完整保留**（25 参数，`1500=cdccccbd…` 不变）
- 卸载状态下放音：新会话 209 / 225 / 241 **均 attached=true**，后端独立工作
- 重装最新 phase2 APK 成功，前端恢复
- ⇒ PLAN-v2 P5「卸载前端配置与音效不变」已确证

### 关键数字

- `backend/test/run_tests.sh`：184/184 通过；App 宿主单测 30/30
- 最终包：`dist/JDSP-fork-v2.0.0.zip` sha256 `414394f0…`
- 后端 dex：jdspd `12c20cb8…`、jdspcli `ef129417…`
- 稳定性：daemon 连续约 5.3 小时，`watcherFailures=0`，churn 修复后 AudioTrack 崩溃计数 0

### 修复的关键缺陷

1. **bypass churn**（发布阻塞）：全局关闭时每 tick 重复 `setEnabled(false)`（约 28 行/秒），与 system_server 崩溃窗口重合 → `BypassRule` 幂等修复，实测 60 s 增量从数百行降到 4 行
2. **离线渲染段错误**：vtable 顺序错 + config 布局臆造 → 垃圾采样率 → OOM → 改为标准布局 + 编译期断言
3. **enabled 抖动**：`EnableSwitch` 回声 no-op + 1 次/秒限流
4. **端点文件自愈**：App 重装/目录丢失后 30 s 内重建

### 仍未完成（不虚报，供下个会话继续）

1. **听感验证**（人工听音）未做
2. **P5 第二阶段部分达成**：App 仍保留一个只用于健康查询（19998/20001/20002）的本地 `AudioEffect` 对象；
   彻底移除需后端提供这三者真实值，而 v6.7.2 引擎不支持 `GET_PARAM`。
   UI 读侧改读 `dumpConfig` 需重写 App 源码（约 10 个 Preference 读取点）。
3. **72 小时长稳未跑**（最长约 5.3 小时）
4. **多设备仅 2 台**（xaga Android 15、本机 Android 16），PLAN-v2 P6 要求三台
5. **设备上装的仍是旧 phase2（`00802267…`）**，最新构建为 `0dd8fb5c…`（phase2 v5）与 `ec773e9d…`（phase3，含后端偏好读取改造但**未在设备验证**）
6. churn 修复后未做长时间回归确认零复发

### 下个会话建议的修复顺序

1. 装最新 phase2（`0dd8fb5c…`）并回归；评估 phase3（后端偏好读取）是否可安全启用
2. 补 P5 第二阶段：让后端提供 19998/20001/20002，或改造 App 读侧走 `dumpConfig`
3. 跑 72 h 长稳（可后台）
4. 接入第三台设备补 P6
