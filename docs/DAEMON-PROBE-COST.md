# jdspd 探测/兜底成本优化（v2.1.9）

> 目标：把 jdspd 在"空转"状态下为**发现播放会话**付出的 CPU 压到最低，同时用 AOSP 官方 API 取代
> `dumpsys media.audio_flinger` 作为会话发现主路径，并评估当前架构相对 AOSP 常规做法是否合理。
> 基线 v2.1.8（模块 218 / jdspd 2.1.8，dex md5 `286b8be2702751a6c10e22f418d579df`），
> 目标版本 v2.1.9（dex md5 `c5efac5bbeb519de04d76de6f214870b`）。
> 设备 64a4bad0 / OnePlus OPD2404 / ColorOS 16 / Android 16。

## 1. 结论摘要

| 指标（60 s 窗口） | v2.1.8 基线 | v2.1.9 | 变化 |
|---|---|---|---|
| jdspd CPU（空闲） | `2.38 %` | `0.22 %`（复测 0.17 %） | **-91 %** |
| jdspd CPU（播放中，两条静音轨） | `2.18 %` | `1.05 %` | **-52 %** |
| jdspd CPU（会话抖动，每 5 s 一条轨） | `2.22 %` | `0.97 %` | **-56 %** |
| dumpsys / min（空闲 / 播放 / 抖动） | `19 / 19 / 19` | `0 / 0 / 0` | **稳态归零** |
| 会话配置查询 / min（空闲） | `570`（10 Hz） | `12`（0.2 Hz） | **-98 %** |
| 新会话 attach p50 | `1255 ms`（v2.1.8 发布实测） | `584 ms`（n=8，p95 969 ms，全程 0 次 dump） | **-53 %** |
| 原生播放器盲区（AAudio/MMAP 流）CPU | `2.4-2.7 %`（降到 dump 每 3 s） | `1.38 %`（阶梯升到 15 s） | **-45 %** |

三条主要结论：

1. **`dumpsys` 不再是会话发现路径**。`IAudioService.getActivePlaybackConfigurations()` 对 uid 0
   的 jdspd 会返回**真实 sessionId**（`AudioPlaybackConfiguration.getSessionId()`，Android 12+ 起
   存在；`AudioService` 只对无 `MODIFY_AUDIO_ROUTING` 权限的调用方做匿名化），一个 binder 调用就
   得到可挂载的 `(sessionId, uid, pid)` 集合，包名走 `/proc/<pid>/cmdline` 解析，不再需要 dump 里的
   session 引用表。实测 attach 全程 `fullPasses` 增量为 0（一次 dump 都没跑）。
2. **空转时连这个查询都可以省**。`AudioSystem.isStreamActive(STREAM_MUSIC, 0)` 是一次到 AudioFlinger
   的轻量 binder 调用（实测 0.18-0.4 ms，无 parcel、无 `List` 分配），比配置查询（1.0-2.8 ms，每个
   播放器一个 config 对象 + 一个 `List`）便宜 5 倍左右；用它做闸门：没有会话且没在播放时，查询只在
   闸门翻转时 + 每 5 s 一次安全查询才跑。
3. **保留 dump 的三条路径**：5 min 交叉校验；盲区（`isStreamActive` 说有声音但"没有已注册播放器在
   播"或"没有任何一行带 sessionId"）；查询不可用（老 ROM / 反射失败 / 匿名化）时的降级阶梯。

## 2. 本次改动（逐条）

### 2.1 快速查询成为主发现路径（`AudioServiceProbe`）

`backend/src/com/jdsp/backend/AudioServiceProbe.java`

- 反射 `IAudioService`（`ServiceManager.getService("audio")` + `IAudioService$Stub.asInterface`），
  读 `getActivePlaybackConfigurations()`，逐行取 `getSessionId()/getClientUid()/getClientPid()/
  getPlayerState()/isActive()`，缓存 `Method`；失败只记 `lastError` 并计一次失败。
- 新增 `Row`（sessionId/uid/pid/state/active）与 `signature()`（按 sessionId:uid:pid:state 排序拼接），
  变化即触发一次"廉价 pass"——不跑 dump。
- **修正了一个会把整个进程拖回 dump 的判定**：`sessionIdsUsable()` 原实现把"所有行 sessionId 都是 0"
  当成"调用方被匿名化"，于是 3 个空闲系统播放器（session 0 = 尚未分配音轨）就让 jdspd 整段生命周期
  走降级 dump（实测 3.1 s 一拍、19 次 dump/min、CPU 2.4 %）。现在"可用"只表示查询本身可用，匿名化
  改由 `anySessionId()` + `isStreamActive` 闸门识别。
- 新增 `musicActive()`：反射 `android.media.AudioSystem.isStreamActive(int, int)`（`STREAM_MUSIC`=3,
  `inPastMs`=0），带 `Method` 缓存、失败自愈、耗时/次数/最后一次结果统计。

### 2.2 空转闸门（`SessionWatcher.loop()` + `WatcherPolicy`）

`backend/src/com/jdsp/backend/SessionWatcher.java`、`backend/src/com/jdsp/common/WatcherPolicy.java`

- 每轮先跑一次闸门；当 `sessions.isEmpty() && !isStreamActive` 时**不跑查询**，只按
  `PROBE_IDLE_MS`=500 ms 轮询闸门，另有 `IDLE_QUERY_MS`=5000 ms 的安全查询。
- **闸门上升沿强制立刻出结果**：闸门 false→true 时把 `lastPassAt` 置 0，本轮立即跑 pass/dump，
  不会因为 5 s 安全查询周期而晚发现。
- 闸门值带 `GATE_HOLD_MS`=3 s 保持（`musicRecent`）：闸门在两次循环之间抖动（静音轨、未喂数据的
  轨）不会把降级阶梯打回密集档——抖动正是早期"整个 AAudio 流期间 16 次 dump/min"的原因之一。

### 2.3 原生播放器：盲区检测 + 只有 dump 见过的会话不被廉价 pass 摘掉

把 discovery 从 dump 换成查询时**必须**补的两个洞：

- `blind`：`isStreamActive(MUSIC)` 为真，但"没有已注册播放器在播"**或**"没有任何一行带真实
  sessionId"（后者 = 调用方被匿名化，认识但挂不上）→ 认为廉价视图不完整，由 dump 驱动发现。
- `dumpOnly` + `lastDumpSessions`：每次 dump 记下"dump 见过但查询看不见"的 sessionId；之后的廉价
  pass 不会因为这些会话不在查询结果里就 detach。只有下一次 dump 不再列出它时才算消失。
- 盲区期间 dump 走**降级阶梯**（3 s → 15 s 封顶 `BLIND_MAX_MS`）：AAudio(MMAP) 播放器在 dump 里是
  session 0/-1，**根本没有可挂载的 sessionId**，为它每 3 s 跑一次 dump 毫无收益。阶梯只看"dump 找到
  的可挂载会话集合是否变化"（`noteDumpSet`），不看廉价 pass 的原始签名——否则盲区里 probe 空集 /
  dump 非空集交替，每次都算"变化"，阶梯永远停在 3 s（这就是第一版实测 22 次 dump/75 s 的原因）。

### 2.4 交叉校验取代"定时 dump"

- `CROSSCHECK_MS`=300 s 一次完整 dump，与查询结果比对，不一致打
  `watcher crosscheck mismatch: probe=[...] dumpsys=[...]`，并计 `watcherCrossChecks`。
- 降级阶梯：查询不可用时 3 s → 15 s → 30 s（每 `DEGRADED_STABLE_PASSES`=3 个"无变化"的 full pass
  升一档，上限 `DEGRADED_MAX_MS`=30 s）；任何"dump 找到的会话集合变化"立刻回到 3 s 密集档；
  查询在启动阶段失败时按 `ProbeSchedule` 每 5 s 重试（v2.1.8 已修，不能把启动竞态记一辈子）。
- 维护定时（配置落盘请求、延后开关、端点文件刷新）从"顺带在 dump pass 里"改成独立
  `MAINTENANCE_MS`=3 s，与 pass 类型无关。

### 2.5 可观测性

`CoreApi.stats()` 新增：`watcherMode`（probe/dumpsys）、`fullPasses`、`watcherProbePasses`、
`watcherIdleSkips`、`watcherBlindFlips`、`watcherDumpOnly`、`watcherBlind`、`watcherCrossChecks`、
`watcherCrosscheckAgoMs`、`watcherProbeRows`、`probeDiscovering`、`probePolls`、`probeChanges`、
`probeCostUs`、`probeMusicActive`、`probeMusicPolls`、`probeMusicCostUs`、`probeAnySessionId`、
`probeError`。诊断日志一行打印 mode/probePasses/idleSkips/dumpOnly/blind/music/probePolls。

## 3. 分级 / 时基参数表

| 参数 | 值 | 含义 |
|---|---|---|
| `PROBE_MS` | 100 ms | 有声音在播（或已有会话）时的查询节奏 |
| `PROBE_IDLE_MS` | 500 ms | 空转时 `isStreamActive` 闸门的节奏 |
| `IDLE_QUERY_MS` | 5000 ms | 空转时的安全查询周期（闸门之外唯一会跑查询的时刻） |
| `GATE_HOLD_MS` | 3000 ms | 闸门"有声音"判决的保持时间（抗抖动） |
| `CROSSCHECK_MS` | 300 s | 完整 dump 交叉校验 |
| `MAINTENANCE_MS` | 3000 ms | 非热路径维护（配置落盘/延后开关/端点文件） |
| `CHANGE_DEBOUNCE_MS` | 100 ms | 变化合并窗口（一串播放器事件算一拍） |
| `FALLBACK_MS` | 3000 ms | 降级/盲区阶梯的密集档 |
| `DEGRADED_STABLE_PASSES` | 3 | 每 3 个"无变化"的 full pass 升一档 |
| `DEGRADED_TIERS` | 3 / 15 / 30 s | 查询不可用时的降级阶梯（上限 `DEGRADED_MAX_MS`） |
| `BLIND_MAX_MS` | 15 s | 盲区阶梯上限（比降级更紧，可挂载但看不见的会话不该等 30 s） |
| `LEGACY_POLL_MS` | 500 ms | 查询完全不可用时的兜底节奏（不得慢于 v2.1.6） |

注：第一版空闲节奏是 250 ms 闸门 + 2 s 安全查询，实测空闲 `0.40-0.42 %`（闸门 0.16 %、安全查询
0.06 %，其余是 jdspd 自身基线），超出 <0.3 % 的目标，才降到 500 ms / 5 s。

## 4. 日志样例（均为设备实测片段）

```
I/jdspd: session watcher started (event driven: query 100ms while playing, isStreamActive gate 500ms /
         safety query 5000ms while idle; dump only on 300s crosscheck, blind player or degraded (3000ms
         dense rung), legacy 500ms when the probe is unavailable)
I/jdspd: session watcher: cheap playback probe available (getActivePlaybackConfigurations, sessionIds=yes)
I/jdspd: watcher pass reason=change mode=probe sessions=3 costMs=36
I/jdspd: attach session=345 effectId=315 (dump said -1) enabled=false hasControl=true in 103274us
I/jdspd: attached session=345 uid=0 pkg= (backend mode)
I/jdspd: watcher pass reason=blind mode=dumpsys sessions=1 dumpMs=123 blind=1
W/jdspd: watcher crosscheck mismatch: probe=[] dumpsys=[457]
I/jdspd: watcher: passes=13 mode=probe probePasses=16 idleSkips=188 dumpOnly=1 blind=false music=false
         probePolls=1377 probeChanges=5 probeCostUs=1347 probe=on ...
I/jdspd: session watcher: cheap playback probe available after 3 retries (getActivePlaybackConfigurations,
         sessionIds=yes)                     <- 开机后 audioserver 起来之前一直重试
```

## 5. 实测：三场景 before/after

方法：`out/event-driven/measure_probe.sh`，每场景 3 轮 × 60 s；jdspd CPU = `/proc/<pid>/stat` 的
utime+stime 增量 / 窗口时长取中位数；pass/查询/dump 计数取自 `jdspcli stats` 前后差值。同一台设备，
两次测量之间只更换 dex（基线 v2.1.8 `286b8be2`，新版 `8f6adb2e`，最终交付 `c5efac5b` 只多了一个
阶梯键控修正，三场景结论不变，空闲复测 0.167 %）。

| 场景 | 轮次 CPU（%） | 中位数 | passes/min | 查询/min | dump/min |
|---|---|---|---|---|---|
| 空闲 before | 2.383 / 2.267 / 2.417 | **2.38** | 19 | 570（日志计数） | 19 |
| 空闲 after | 0.250 / 0.217 / 0.217 | **0.22** | 0 | 12 | **0** |
| 播放 before | 2.183 / 2.200 / 2.167 | **2.18** | 19 | 570 | 19 |
| 播放 after | 1.050 / 1.017 / 1.117 | **1.05** | 0 | 589 | **0** |
| 抖动 before | 2.233 / 2.217 / 2.217 | **2.22** | 19 | 570 | 19 |
| 抖动 after | 0.967 / 0.950 / 1.067 | **0.97** | 0 | 588 | **0** |

口径说明：
- v2.1.8 的 `stats` 不输出 `probePolls`/`fullPasses`，其查询速率取自 daemon 周期日志
  （60 s 两个采样点 27421 → 27991 = 570 次/min）；其 dump 速率 = `watcherTicks` 增量
  （v2.1.8 只有 dump pass 才 tick），19-20 次/min。
- 播放场景里 `passes/min` 为 0 是**正常**的：两轨静音且集合不变，daemon 只轮询不 reconcile；
  一次 5 min 交叉校验会落在某一轮（表里取中位数，所以是 0）。
- 播放场景剩下的 1.05 % 里约 0.9-1.0 % 就是"10 次/s × 1.0-1.6 ms"的配置查询本身——这是
  "发现新会话"必须付的代价，再降只能上事件回调（见 §6/§8）。

### 5.1 attach 延迟（探针路径，全程无 dump）

`out/event-driven/tools/AttachProbe.java`（8 次，每次一条新轨，保持 3 s）：

| n | min | p50 | p95 | max | 期间 dump 次数 |
|---|---|---|---|---|---|
| 8（`8f6adb2e`） | 491 ms | **584 ms** | 969 ms | 969 ms | **0** |
| 5（交付版 `c5efac5b`） | 492 ms | **507 ms** | 996 ms | 996 ms | 5（每次音轨起停的瞬态各 1 次） |

对照 v2.1.8 发布实测 1255 ms（当时靠 3 s 兜底 dump）。日志证据：新会话先被廉价 pass 看见
（`reason=change mode=probe`），再 `attach session=... in ~90-105 ms` 完成挂载。

注意：若探针每次都立刻释放音轨（会话寿命 <2 s），会触发 daemon 的短命会话风暴保护
（30 s 内 5 次短命死亡 → attach 去抖从 400 ms 抬到 2000 ms），此时观测到的 2.1 s 是**保护机制的
预期行为**，不是回归；改成 3 s 寿命后 p50 恒在 0.5-0.6 s。

### 5.2 原生播放器（盲区）

- OpenSL ES（`out/event-driven/native/opensles_tone.c`）走 AudioTrack，**会**注册播放配置，
  探针能看见 sessionId → 不是盲区，attach 在 dump 找到它后 455 ms 完成。
- AAudio MMAP（`out/event-driven/native/aaudio_tone.c`）`AAudioStream_getSessionId()` 返回 -1，
  `dumpsys media.audio_flinger` 里只有 `1 effects for session 0` / `1 effects for session -1`：
  **没有任何可挂载的 session id**，per-session 挂载对它无能为力（只有全局 postprocess 插入才行）。
  盲区逻辑正确进入（`blind=1`、`dumpOnly=1`、`probeAnySessionId=false`），dump 阶梯从 3 s 升到 15 s，
  CPU 从第一版的 2.4-2.7 % 降到 `1.38 %`（剩下的主要是播放态 10 Hz 的配置查询）。

## 6. 未做项与理由

1. **`IAudioService.registerPlaybackCallback()` 真事件驱动（未做）**：可行（自建
   `IPlaybackConfigDispatcher` Binder + 反射事务码 `onPlaybackConfigChanged(List)`），但收益只剩
   "播放期约 0.9-1.0 % CPU + 盲区那点 dump"，而回调要多一条常驻 binder 引用、一条跨进程回调线程、
   注册/注销/死亡重连的生命周期，以及 audioserver 重启后的兜底。作为下一阶段可选项，触发条件见 §8。
2. **dumpsys 参数化/裁剪（未做）**：`dumpsys media.audio_flinger` 没有"只打印 tracks"的口子，
   能省的只有"什么时候跑"，已做到（正常 5 min 一次，稳态 0 次/min）。
3. **NIO / Selector 替换"一连接一线程"（不做）**：1-8 个客户端净收益约 0，长请求
   （attach+applyAll ~300 ms、16 KB 冲激响应分包 6-13 ms）会阻塞事件循环。详见
   `docs/DAEMON-EVENT-DRIVEN.md`。
4. **`ParamBus` 2 ms 合并批（不动）**：App 走 `op=setParam` 单发路径，只有 `jdspcli commit` 用延迟
   合并；删掉它只影响 CLI 批量语义，对实时控制没有收益。
5. **`AudioManager.registerAudioPlaybackCallback`（不做）**：需要 `Context`；root `app_process` 里
   `ActivityThread.systemMain()` 实测抛 `InvocationTargetException`，且会把自己注册成系统进程。

## 7. 回滚

- dex 级：`/data/local/tmp/jdspd.before-swap.1793436811.dex`（v2.1.8，`286b8be2...`）复制回
  `/data/adb/modules/ainur_jamesdsp/backend/jdspd.dex`，然后 `pkill -f nice-name=jdspd`；
  监督脚本 8-58 s 内拉起（实测 8/10/24/34/48/58 s）。
- 代码级：本版没有协议/配置格式变化，`/data/adb/jdspd/config.json` 向前兼容；行为可只用常量回退：
  `IDLE_QUERY_MS` 调小 = 更频繁查询，`CROSSCHECK_MS` 调小 = 更频繁 dump 交叉校验，不需要回滚代码。
- 探针本身可关：反射失败自动降级到 `LEGACY_POLL_MS`=500 ms 定时 dump（v2.1.6 行为）。

## 8. AOSP 最佳实践评估（结论：架构合理，两处应当演进）

**当前架构**：引擎是 `libjamesdsp.so`（`AudioEffect` 的 C++ 实现），jdspd 是 root 常驻进程，
按 session 逐个 `AudioEffect` 挂载，控制面是 `127.0.0.1:port` 的 TCP+JSON，会话发现靠
`IAudioService` 查询（本版起）。

**AOSP 常规做法**：
1. **全局插入（postprocess）**：`/vendor/etc/audio_effects.xml` 声明一个 `postprocess` 效果并挂到
   output stream / device port，由 `AudioFlinger` 的 `EffectChain` 自动实例化。**不需要任何会话发现**，
   参数改动就是一次 binder `setParameter`。参考 <https://source.android.com/docs/core/audio/effects>
2. **按会话挂载**：应用自己 `AudioEffect(...)`，发现由框架 `AudioPolicyManager` 在创建 track 时完成，
   没有轮询。
3. **事件回调**：`AudioManager.registerAudioPlaybackCallback(AudioPlaybackCallback, Handler)`（API 26+）
   或系统侧 `IAudioService.registerPlaybackCallback`；`AudioPlaybackConfiguration` 带
   `getSessionId()`（12+，`@SystemApi`）。参考
   <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/AudioPlaybackCallback.java>、
   <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/audio/AudioService.java>

**判断**：
- `getActivePlaybackConfigurations()` + 真实 sessionId 就是 AOSP 给特权调用方的正规发现接口，本版用它
  取代 `dumpsys` 是**向 AOSP 做法靠拢**，不是权宜之计；剩下的轮询是"没有 `Context`、不改框架"前提下
  能拿到的最便宜的等价物。
- **应当演进之一：全局插入优先**。若产品不需要"按应用开关"，`audio_effects.xml` 的 postprocess 插入是
  零发现、零轮询、零 attach/detach 抖动的方案，且**能处理 AAudio MMAP 播放器**（本版实测这类播放器
  没有任何可挂载 session id，per-session 路线天然覆盖不到）。保留按会话挂载的唯一理由是**按包策略**与
  "模块化安装、不动 `/vendor`"；建议把"全局插入 + 只对例外包 bypass"作为下一代架构候选（KernelSU 的
  magic mount 可覆盖 `/vendor/etc/audio_effects.xml`，需模块侧验证）。
- **应当演进之二：事件化**。要彻底去掉 100 ms 轮询，正确做法是 `registerPlaybackCallback`，而不是继续
  压查询周期；收益（播放期约 1 % 的 CPU + 盲区 dump）与复杂度不成比例，优先级排在全局插入之后。
- **不该改的**：TCP+JSON 控制面。AOSP 用 binder/AIDL 是因为控制方是系统里的 App 或系统服务；这里是
  root 守护进程 + 普通 App，走 binder 要注册服务、改 SELinux 策略，收益只是"少一次本机 socket 往返"
  （实测 RTT p50 0.2 ms），不值得。**但 `dumpsys` 任何时候都不应出现在热路径**——本版之后它只出现在
  5 min 交叉校验、盲区、探针不可用三种情况。

## 9. 版本与产物

- 目标版本：`jdspd 2.1.9`，dex `backend/out/jdspd.dex` md5 `c5efac5bbeb519de04d76de6f214870b`（153,976 B）。
- 已装到模块：`/data/adb/modules/ainur_jamesdsp/backend/jdspd.dex` 同一 md5，守护进程自
  `17:40:06` 那次重启后即为该 dex。
- 基线（回滚用）：`/data/local/tmp/jdspd.before-swap.1793436811.dex`（v2.1.8，`286b8be2...`）。
- 分发包：`dist/JDSP-fork-v2.1.9.zip`（33,274,174 B，sha256 `4918908cc089d38e12aff749b78557f1a0143400622bfd6675c5826960beec6f`），模块 `v2.1.9-fork`（versionCode 219）。
- 单测：`backend/test/run_tests.sh` → **280 passed, 0 failed**（基线 273；新增 `WatcherPolicyTest`
  用例：弹窗节奏、空闲闸门、交叉校验、维护定时、降级阶梯、盲区上限、闸门保持），device-selection
  自测 11 PASS。`git diff --check` 干净。

## 10. 复现命令

```bash
# 0) 设备与基线
adb -s 64a4bad0 shell su -c 'sh /data/local/tmp/jdspcli.sh stats'
adb -s 64a4bad0 shell su -c 'sh /data/local/tmp/jdspcli.sh dump-config' > baseline.json

# 1) 编译与单测
cd /home/bdy/work/jdsp-repo && ./backend/test/run_tests.sh && ./backend/build.sh

# 2) 换 dex（监督脚本 60 s 内重启）
adb -s 64a4bad0 push backend/out/jdspd.dex /data/local/tmp/jdspd.new.dex
adb -s 64a4bad0 shell su -c 'sh /data/local/tmp/install_dex.sh'

# 3) 三场景 CPU 测量（各 3 轮 × 60 s）
bash out/event-driven/measure_probe.sh probe-cost-final

# 4) attach 延迟（探针路径，全程应无 dump）
adb -s 64a4bad0 push out/event-driven/tools/dex/classes.dex /data/local/tmp/attachprobe2.dex
adb -s 64a4bad0 shell su -c 'cd /data/local/tmp && CLASSPATH=/data/local/tmp/attachprobe2.dex   app_process /system/bin AttachProbe 8 1000 3000'

# 5) 原生播放器（盲区）
adb -s 64a4bad0 push out/event-driven/native/opensles_tone out/event-driven/native/aaudio_tone /data/local/tmp/
adb -s 64a4bad0 shell su -c 'chmod 755 /data/local/tmp/opensles_tone /data/local/tmp/aaudio_tone'
adb -s 64a4bad0 shell su -c 'setsid /data/local/tmp/aaudio_tone 60 >/dev/null 2>&1 & sleep 3; true'
adb -s 64a4bad0 shell su -c 'sh /data/local/tmp/jdspcli.sh stats'   # blind=true, dumpOnly=1

# 6) 还原
adb -s 64a4bad0 shell su -c 'pkill -TERM -f "aaudio_[t]one"; pkill -TERM -f "app_process /system/bin Tone"'
```

## 11. 设备状态（交付时）

```
jdspd 2.1.9 pid 3670, dex c5efac5bbeb519de04d76de6f214870b
config: mode=1 (backend), enabled=true, globalParams=26, policies=0  (与基线逐字节一致)
sessions=0, 无残留测试播放器（Tone/Tone2/opensles/aaudio 均已退出）
开机后复测（60 s 窗口，App 已连接）：CPU 0.183 %，dump 0 次，查询 12 次，闸门 109 次
```

交付过程中设备在 `17:40:06` 和 `17:41:2x` 连续重启两次（不是我发起的：我只做了 dex 热替换、静音
测试轨、AAudio/OpenSL 测试播放器，没有 reboot/clear 类操作）。`/data/tombstones` 里这两次时间点的
崩溃全部是 `/vendor/bin/hw/vendor.qti.camera.provider-service_64`（SIGABRT in
`CameraProvider::binderDied`，`Process uptime: 0s`），**没有任何 jdspd 的 tombstone**；重启前
`17:38:58` 起 audioserver 出现过 `DeadObjectException`（盲区/AAudio 试验期间），但 daemon 自身只是
按 `ProbeSchedule` 重试、没有异常退出。这是这台 ColorOS 设备上厂商相机 HAL 的既有抖动，与本版改动
无因果关系（本版没有触碰任何 HAL/播放器路径）。

开机后（已解锁、App 已连接）复测符合预期：jdspd 由监督脚本拉起，探针 `after 2-3 retries` 就绪
（audioserver 本身在开机后约 20-30 s 才注册，探针每 5 s 重试，一旦可用立刻接上），稳态 idle 0.183 %。
建议后续补一次 App 侧端到端抽查（滑杆写入时延 + 开关），本轮因重启窗口未做。

## 12. Android 12 以上兼容性

用户要求「确保在安卓 12 以上支持」。本版新增/依赖的三处系统 API 在 Android 12 就已存在，逐条按 AOSP 源码核对
（`curl 'https://android.googlesource.com/platform/frameworks/base/+/refs/heads/<branch>/<path>?format=TEXT' | base64 -d`，
分支 `android12-release` … `android16-release`）：

| API | 用途 | 12 | 13 | 14 | 15 | 16 | 源码依据 |
|---|---|---|---|---|---|---|---|
| `IAudioService.getActivePlaybackConfigurations()` | 会话发现主路径 | ✅ | ✅ | ✅ | ✅ | ✅ | `PlaybackActivityMonitor#getActivePlaybackConfigurations(boolean isPrivileged)` 五个分支都在 |
| `AudioPlaybackConfiguration#getSessionId()` | 取真实 sessionId | ✅ | ✅ | ✅ | ✅ | ✅ | 12-16 均为 `public int getSessionId()`，`writeToParcel` 写 `mSessionId`（12: AudioPlaybackConfiguration.java:338/551） |
| 非特权匿名化 | 说明降级条件 | ✅ | ✅ | ✅ | ✅ | ✅ | 12/13 `anonymizedCopy` 显式置 `AudioSystem.AUDIO_SESSION_ALLOCATE`；14+ 不再赋值（默认 0） |
| `AudioSystem.isStreamActive(int,int)` | 空转闸门 | ✅ | ✅ | ✅ | ✅ | ✅ | `public static native boolean isStreamActive(int stream, int inPastMs)`（12: AudioSystem.java:491，16: :618） |
| `AudioEffect` + `setParameter/getParameter` | 挂载与调参 | ✅ | ✅ | ✅ | ✅ | ✅ | API 9+ 稳定接口，未变 |

三档保障，缺任何一档都不影响功能，只影响 CPU 开销：

1. **反射失败即降级**：`getActivePlaybackConfigurations` 事务不存在（老 ROM/OEM 裁剪）或 SELinux 拒绝 → 自动回到
   `LEGACY_POLL_MS`=500 ms 定时 dumpsys（= v2.1.6 行为），功能不减，只是回到旧的 CPU 开销。
2. **匿名化检测**：某些环境（非 root、无 `MODIFY_AUDIO_ROUTING`、OEM 改动）拿不到真实 sessionId →
   `probeAnySessionId=false` → 判为盲区，由 dump 驱动发现，挂载照常。
3. **逻辑全部有单测**：`WatcherPolicyTest` 覆盖闸门、交叉校验、降级阶梯、盲区上限、维护定时，设备侧走的是同一条被测试的分支。

**为什么用反射而不是编译期依赖**：jdspd 是模块自带 dex、以 uid 0 的 `app_process` 运行（不是安装的 App），
`ServiceManager`/`IAudioService`/`AudioSystem` 都是 `@hide`/`@SystemApi`，模块无法依赖某个 SDK 的隐藏 stub；
同时 uid 0 进程不受 hidden-API 黑名单约束（模块早已用同样方式反射 `AudioEffect` 的隐藏构造器）。
因此 12-16 全区间只依赖「方法名 + 参数类型」这一稳定契约，缺失即降级。

## 13. 发版安装与开机验证（2026-10-31）

安装路径走 KernelSU 官方流程（不是手工替换文件）：

```bash
adb -s 64a4bad0 push dist/JDSP-fork-v2.1.9.zip /data/local/tmp/
adb -s 64a4bad0 shell 'su -c "ksud module install /data/local/tmp/JDSP-fork-v2.1.9.zip"'
#  -> Module installed successfully!（暂存到 /data/adb/modules_update/ainur_jamesdsp，versionCode=219）
adb -s 64a4bad0 reboot   # 18:33:56，开机约 30 s 完成
```

开机后核对（全部通过）：

| 项 | 结果 |
|---|---|
| 模块版本 | `version=v2.1.9-fork (2026-10-31)` / `versionCode=219`，`/data/adb/modules_update` 已空（更新已合并） |
| 守护进程 | `jdspd 2.1.9`，`backend/jdspd.dex` md5 `c5efac5bbeb519de04d76de6f214870b`（与 `dist` 内一致） |
| 配置 | `dump-config` 1299 B 与重启**前逐字节一致**（26 个全局参数、`mode=backend`、`enabled=true`） |
| 探针 | `probeAvailable=true`；启动期 `IllegalStateException: audio service not found` 是 audioserver 注册前的正常重试（v2.1.8 修的就是不缓存这个失败） |
| attach | 4 次新会话实测 **109.6 / 57.5 / 71.1 / 65.7 ms**（`attach session=… in …us`，全程 `mode=probe`，0 次 dump） |
| 空转 CPU | 开机 8 min 后 60 s 窗口 **0.133 %**，同期 `dumpsys` **0 次** |
| soak | `/data/adb/service.d/99-jdsp-soak-autostart.sh` 正常拉起（pid 3866），开机后 18:34:22 写出首行（bootid `8a69cce5`，`jdsphits=0`） |
| App | `james.dsp` 1.6.20（versionCode 66） |

### 13.1 残会话与原生播放器：v2.1.10 收紧（+ 修一个 v2.1.9 回归）

**观察（v2.1.9）**：测试播放器被 `SIGTERM` 杀掉后，audioserver 侧 tracks 已为 0，但 dump 里仍有
`1 effects for session 113`（那是 jdspd 自己的 effect 句柄）。`dumpOnly` 保护策略规定「只有 dump 见过的会话不能被
廉价 pass 摘掉」，因此该会话一直保留到下一次 5 min 交叉校验：实测 `18:40:58 watcher pass reason=crosscheck
mode=dumpsys sessions=0` → `detach session=113` → `released session=113`，残留 **5 min 40 s**，
期间探针 10 Hz 空转 ≈0.95 % CPU（正常空转 0.13-0.22 %）。

**根因**：廉价视图看不见"只有 dump 记得的会话"，而 `dumpOnly` 又必须保护它们（否则原生播放器的效果链会被误摘）。
两者的唯一交汇点是 dump —— 所以收紧点不是缩短交叉校验周期（那会把 5 min 一次变成分钟级常态开销），
而是**按需**在可疑状态出现时提前要一次 dump。

**v2.1.10 的实现**（三处，均在 `WatcherPolicy` 里有纯逻辑 + 单测）：

| 机制 | 规则 | 作用 |
|---|---|---|
| 确认 dump | 已挂句柄 + 没在播（`!isStreamActive` 且探针无 playing 行）持续 `CONFIRM_DWELL_MS`=1.5 s → 跑一次 dump | 死会话当次释放；活着的（暂停中的原生播放器）确认它还是真的 |
| 确认退避 | 确认 dump 后仍有会话：`CONFIRM_BACKOFF_START_MS`=4 s 起翻倍，封顶 `CONFIRM_BACKOFF_MAX_MS`=60 s；播放恢复/会话清空立即复位 | 暂停的原生播放器稳态成本 ≤1 dump/min，不会因为"暂停"变成 dump 循环 |
| 静音节拍 | 循环等待只看"真在播"（闸门 + 探针 playing 行），不再因"持有句柄"按 100 ms 转 | 暂停中的已挂载会话 10 Hz → 2 Hz |

**顺带修掉一个 v2.1.9 回归（原生播放器从不挂载）**：v2.1.9 让廉价 pass 接管稳态后，`reconcile` 的防抖记账把
"本轮会话列表里没有"的会话当作消失、清掉 `firstSeen` 戳。原生播放器（AAudio/OpenSL）只出现在 dump 里，于是
"dump 看见 → 廉价 pass 忘掉 → dump 再看见"无限循环，**400 ms 防抖永远等不到**，效果链从不建立
（实测 uid 10123 的 AAudio 播放器 50 s 内 attach 0 次）。修法：
`WatcherPolicy.keepFirstSeen(present, dumpOnly)` —— 只有 dump 见过的会话必须保留戳；再加一个"attach dump"触发器
（`DUMP_ATTACH_MIN_MS`=1 s 限速，连续 `DUMP_ATTACH_MAX_MISSES`=5 次拿不到句柄就放弃并重新观察），
让"戳已到期但廉价 pass 看不见"的会话花一次 dump 完成挂载。

**实测（同机，v2.1.9 → v2.1.10）**：

| 指标 | v2.1.9 | v2.1.10 |
|---|---|---|
| 强杀到 `released session` | >90 s（实测 90 s 预算内未释放，只有 5 min 交叉校验能收；历史 5 min 40 s） | **1400 ms**（`reason=confirm` 的 dump 当次 detach+release） |
| 原生播放器（AAudio, uid 10123）attach | 0 次（永不） | **800 ms** |
| Java 播放器 attach | 95-103 ms | 95-103 ms（无回归） |
| 空闲 40 s CPU | 0.13-0.22 % | 0.225 % |
| 单测 | 280 passed | **283 passed**（+3：确认触发、确认退避、dumpOnly 记账）+ 自检 11 PASS |

复现/验收脚本：`out/confirm/stale-ab.sh`（原生播放器挂载 + 强杀 + 计时），
verdict 判据 = `kill->release < 5 s` 且 `attach session=` 出现在 `aaudio_tone` 启动后 2 s 内。


