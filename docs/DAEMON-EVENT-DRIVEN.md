# jdspd 事件驱动 + 分锁改造（v2.1.6 之后，未发版）

> 范围：backend/（jdspd）。**不改 App / native / 模块脚本**；协议与外部行为向后兼容。
> 提交：本轮改动**未提交**，由 lead 统一提交（两个 agent 同树协作，避免 index 打架）。
> 真机：OnePlus OPD2404 / ColorOS 16 / Android 16 / SM8650，序列号 64a4bad0，KernelSU root，
> 模块 v2.1.6-fork(216) / jdspd 2.1.6 / App 1.6.20-66。原始数据在 `out/event-driven/`。

---

## 0. 结论摘要

| 指标（同机、同会话对、同脚本） | 改造前（2.1.6 dex） | 改造后 | 变化 |
|---|---|---|---|
| jdspd CPU（30 s utime+stime / 单核） | **13.53 %** | **3.43 %** | **-75 %** |
| watcher 全量 dumpsys 次数 | **1.73 /s**（52 次/30 s） | **0.33 /s**（10 次/30 s） | **-81 %** |
| 新播放会话 → effect attach 完成（8 次） | p50 **1325 ms** / p95 2723 ms | p50 **739 ms** / p95 **755 ms** | **p50 -44 %，p95 -72 %** |
| 总开关 off（API 往返，5 轮） | 5-19 ms | 7-10 ms | 持平 |
| 总开关 on（API 往返，5 轮） | 11-17 ms | 5-9 ms | 略好 |
| 总开关 off→bypass 生效（日志） | — | **4 ms（2 实例）** | 见 §5.4 |
| 总开关 on→un-bypass 生效（日志） | — | **5 ms（2 实例）** | 见 §5.4 |
| 单参数 RTT p50（2 会话在线） | 16.6 ms | 20.7 ms | **受引擎漂移主导，见 §5.3：同时段 A/B/A 为 25.9 vs 25.3，无回归** |
| 并发 2 客户端 / 2 会话 p95 | 45.8 / 45.6 ms | **35.1 / 32.1 ms** | **p95 -23 % ~ -30 %** |
| 慢参数（16 KB 分片）期间另一会话 p95 | 168.0 ms | 163.5 ms | 持平（瓶颈在 audioserver，见 §6） |

三条主要收益：**CPU -75 %**、**新会话 attach 快一倍**、**并发写 p95 降 23-30 %**。
单参数 RTT 的 p50 由 "AudioEffect.setParameter 的 binder 往返" 决定（本次实测 13.3 → 18.1 ms 的漂移），
daemon 侧改造对它几乎无影响——这与 latency-auditor 早前的结论一致（engine 写占 RTT 约 85 %）。

---

## 1. 做了什么 / 没做什么

### 1.1 做了

| # | 改动 | 文件 | 归谁 |
|---|---|---|---|
| A1 | EffectManager 全局 `synchronized` → **per-session 锁 + 状态机**（ST_LIVE/ST_CLOSING + in-flight 计数，detach 等在飞写完成、CLOSING 立即 NO_HANDLE、`gate.forget()` 移到 release 之后）；enabled/hasControl 加 **1 s TTL 缓存**（states()/state() 不再持锁调 binder） | `backend/src/com/jdsp/backend/EffectManager.java` + 测试 | latency-fix |
| A2 | 热路径不再重建 session view（`CoreApi.setParam` 用 `effects.pkgOf()`；HEAD 已具备，本轮只做**实测确认**） | `CoreApi.java` | HEAD / 本轮确认 |
| A3 | config fsync 不落在任何请求路径锁内（HEAD 已有 `fileLock`；本轮把 `applyEnabled/setAppPolicy/setMode` 里的**内联** `saveThrottled/saveIfDirty` 换成异步 persister，并给 persister 加**单次自解除定时器**补写） | `CoreApi.java`、`AsyncPersister.java`、`ConfigStore.java` | 本轮 |
| B4 | SessionWatcher：**500 ms 定时 dumpsys → 事件驱动**（100 ms 廉价 binder 探测活跃集合签名 + 100 ms 去抖 + 3 s 兜底全量）；tick 里 `config.saveIfDirty()` 与 `pollEnableSwitch()` 移到**只在兜底 pass 执行**的 `slowMaintenance()`；新增**attach 去抖的一次性定时唤醒** | `SessionWatcher.java`、新增 `AudioServiceProbe.java`、`common/WatcherPolicy.java` + 测试 | 本轮 |
| B5 | Daemon 主循环去掉 1 s 周期 sleep → **CountDownLatch 等待 shutdown**；stop 文件（模块卸载用）改成 **inotify WatchService**（不可用时 5 s 复查兜底） | `Daemon.java` | 本轮 |

### 1.2 没做（按评审结论）

- **NIO/Selector 替代 thread-per-connection：不做。** 实测单次 ping p50 = 84 us、连接数上限 8；
  thread-per-conn 在 1-8 客户端下不是瓶颈（本次改造前后 ping 往返都在 0.08-0.2 ms），而 NIO 单线程
  事件循环会把**长请求**（attach+26 参数重放 ≈ 300 ms、`baseline` 26 写 ≈ 160 ms、16 KB 分片 6-13 ms）
  串到所有客户端前面。要做也必须是 "NIO 只做收发 + 长操作丢 worker 池并异步回包"，那是 Phase 2 的工作量。
  **再评估条件**：并发客户端 > 8、或需要单连接多路复用/背压、或要做跨进程流式推送。
- **ParamBus 去掉 2 ms 合并：不做。** App 走的是 `op=setParam` 同步路径，根本不经过
  `setParamDeferred`（只有 `jdspcli commit` 用）；合并窗口只影响 commit 路径，去掉它
 对 p95 无收益，反而丢掉 "一次 commit 只触发一次引擎写" 的语义。`flushTask` 本身已是定时任务（非轮询）。

---

## 2. Phase 0 探针：会话发现的事件源到底能不能用

在 root `app_process`（无 Activity、无 system uid）里实测（探针源码 `out/event-driven/tools/`
与 `/data/local/tmp/probeaudiocb.dex`、`probeaudiosvc.dex`）：

| 方案 | 结果（实测） | 结论 |
|---|---|---|
| `ActivityThread.systemMain().getSystemContext()` + `AudioManager.registerAudioPlaybackCallback` | **不可用**：调用抛 `InvocationTargetException`（进程没走图形/system-process 初始化）；而且它内部会把自己 attach 到 AMS 注册成 system app——daemon 不该有这种副作用 | 放弃 |
| `IAudioFlingerClient::ioConfigChanged` | 只上报 I/O 配置变化（output/input 打开关闭、采样率/声道/格式），**没有 track/session 级事件**（AOSP Effects.cpp 的 ioConfigChanged 分支） | 不能做会话发现 |
| `IAudioService.getActivePlaybackConfigurations()`（反射 `ServiceManager.getService("audio")` → `IAudioService$Stub.asInterface`） | **可用**：**64-93 us/次**（首次 549 us）；条目里能拿到 uid/pid/playerState（`getClientUid/getClientPid/getPlayerState`），但 **没有 sessionId**（本 ROM 上 `getPlayerIId/getAudioSessionId` 不存在） | **采用**：当"活跃集合签名变化"探测器，变了才跑 dumpsys 拿 sessionId |

因此 B4 的形态是：**100 ms 廉价探测（~70 us，≈0.07 % 单核）+ 变化去抖 100 ms → 一次 dumpsys**，
外加 **3 s 兜底全量**（覆盖探测器看不到的变化：同 uid/pid/state 的会话替换、探测器不可用的 ROM）。
探测器不可用时整条链路自动退化为旧的 500 ms 定时 dumpsys（`WatcherPolicy.LEGACY_POLL_MS`）。

---

## 3. 设计

### 3.1 SessionWatcher 三触发器

```
loop:
  poke?  fallback(3s)?  probe.changed()?        -> 是: 跑一次全量 pass（dumpsys + reconcile）
                                                   fallback 时额外跑 slowMaintenance()
                                                -> 否: 等 100 ms（有探测器）/ 500 ms（无探测器）
```

- `poke()`：policy/mode 变化、显式 attach/detach、总开关变化、**attach 去抖到期**（见 3.2）。
- 探测器变化：`AudioServiceProbe.poll()` 用 "count + 排序后的 uid:pid:state 列表" 做签名。
- 兜底 pass（3 s）：顺便做 `pollEnableSwitch()`（总开关 deferred 的兜底；主路径是 CoreApi 的
  ScheduledExecutor）、`config.saveIfDirty()`（多数情况已是 no-op，热路径由 AsyncPersister 覆盖）、
  `refreshEndpointFiles()`（自带 30 s 节流）——这三件事都不再出现在快路径上。

### 3.2 attach 去抖必须"事件化"（本轮最大的坑）

原来的 400 ms/`2000 ms` attach 去抖**隐含依赖"总有一个下一个 tick"**：第一次看到会话时只记
`firstSeen`，靠下一个 tick 判断"够老了"。事件驱动后没有下一个 tick，于是新会话只能等 3 s 兜底
pass——实测 attach p50 从 1325 ms 掉到 **3416 ms**（回归）。

修法：`reconcile()` 里顺便算出"最近一个会话何时够老"（`nextAttachAt`），用**一次性**
ScheduledExecutor 在该时刻 `poke()` 自己（只有一个 pending 任务，触发即解除）。修后 attach
p50 = **739 ms**。

### 3.3 顺手修掉的一个"poke 被吞"bug

循环里等 `wakeLock.wait(PROBE_MS)` 之后原来会无条件 `wake = false`，把"叫醒它的那个 poke"
丢掉了：poke 若发生在 wait 期间，会被清除而不会触发 pass。表现同样是最坏等 3 s 兜底。
现在改为**只由循环开头的 `takeWake()` 消费 wake**。实测 attach p95 从 2723 ms → 755 ms。

### 3.4 EffectManager（A1，由 latency-fix 实现，本文只做集成与实测）

- `handles` 改 `ConcurrentHashMap`；每个 `Handle` 自带 `lock` + `ST_LIVE/ST_CLOSING` + `inFlight` 计数。
- 写/读：`acquire()`（非 LIVE 立即返回 `ERR_NO_HANDLE`，**不阻塞**）→ 反射调用在锁外 → `release()`。
- `detach`：先从表里摘除（新写立刻 NO_HANDLE）→ 等 `inFlight` 归零（上限 `DRAIN_TIMEOUT_MS=3000`）
  → `setEnabled(false)` + `release()` → **release 之后**才 `gate.forget()`（写锁语义：下一次 attach 重新握手）。
- `enabled/hasControl` 走 **1 s TTL 缓存**，`states()/state()/isEnabled()/hasControl()` 不再在锁内做 binder；
  `setEnabled()` 成功即刷新缓存。改造前 watcher 每 tick 每 handle 2 次 binder（2 句柄 × 2 次 × 2/s = 8 次/s），
  现在是每 handle 每秒 ≤2 次、且只在真正需要时。

### 3.5 Daemon 主循环与 stop 文件

- 主线程 `while(!shuttingDown) sleep(1000)` + 每秒 stat stop 文件 → 改成
  `CountDownLatch MAIN_EXIT.await()`（`shutdown()` 结尾 countDown）。
- stop 文件：`WatchService`（inotify）监听 `/data/adb/jdspd`，`ENTRY_CREATE/ENTRY_MODIFY` → 触发优雅退出；
  inotify 不可用时 `poll(5 s)` 复查兜底（只在进程存活期存在，且只在卸载场景有意义）。

### 3.6 其它周期点

| 周期点 | 改造后 |
|---|---|
| `AsyncPersister`（落盘） | 已经是"请求即跑 + 合并"；本轮补 **单次自解除延时补跑**（`requestAfter()`），使"节流窗口吞掉这次保存"时仍有且仅有一次补写 |
| `ParamBus.flushTask` | 已是定时任务（2 ms 后触发），非轮询 |
| `TcpService` | `SO_TIMEOUT=30 s` 是**内核级阻塞读超时**，不是轮询；`refreshEndpointFiles` 只在 watcher 兜底 pass 里调用（自带 30 s 节流），未改 |
| `BinderService.startRetryLoop` | 只在 binder 未发布时存在，60 s 一次，成功即退出；不改（启动路径，非稳态） |
| `EngineUnlockGate` | `HashSet + synchronized` → 保持（每次写一次极短的集合查询）；EffectManager 侧已是无锁读 |

---

## 4. 代码改动清单（本轮，供 review）

| 文件 | 改动要点 |
|---|---|
| `backend/src/com/jdsp/backend/SessionWatcher.java` | 事件驱动循环（三触发器）、`pollOnce()` 去掉 saveIfDirty/pollEnableSwitch、`slowMaintenance()`、`armDebounce()` 一次性唤醒、poke 不再被吞、每 pass 一行诊断日志（≤1/s）、每 60 s 一行 watcher 计数（passes/probePolls/probeChanges/probeCostUs） |
| `backend/src/com/jdsp/backend/AudioServiceProbe.java`（新增） | 反射 `IAudioService.getActivePlaybackConfigurations()`，签名变化检测；失败即永久降级并在日志里说明 |
| `backend/src/com/jdsp/common/WatcherPolicy.java`（新增，纯 Java） | `PROBE_MS=100`、`FALLBACK_MS=3000`、`CHANGE_DEBOUNCE_MS=100`、`LEGACY_POLL_MS=500`、`shouldRunPass()`、`fallbackDue()`、`nextSleepMs()` |
| `backend/src/com/jdsp/backend/Daemon.java` | `MAIN_EXIT` latch、`StopFileWatcher`（inotify + 5 s 兜底）、shutdown 末尾 countDown |
| `backend/src/com/jdsp/backend/AsyncPersister.java` | `requestAfter(delayMs)`（单次自解除）+ `isDelayArmed()` |
| `backend/src/com/jdsp/backend/ConfigStore.java` | `isDirty()`（诊断/调度用） |
| `backend/src/com/jdsp/backend/CoreApi.java` | persister 动作里"仍 dirty 就补一次"；`applyEnabled/setAppPolicy/setMode` 不再在请求线程 fsync |
| `backend/test/java/com/jdsp/common/WatcherPolicyTest.java`（新增） | 触发器真值表、兜底截止时间、睡眠选择 |
| `backend/test/java/com/jdsp/backend/AsyncPersisterTest.java` | 新增"延时补跑只跑一次且自我解除"用例 |
| `backend/test/java/com/jdsp/cli/AllTests.java` | 注册 `WatcherPolicyTest`（与 A1 的 `EffectManagerConcurrencyTest` 同一处注册） |

测试：`./backend/test/run_tests.sh` → **268 passed, 0 failed**（原 258 + A1 的 6 + 本轮 4），
device selection self-test 11 PASS；`git diff --check` 干净（见 §8）。

---

## 5. 真机实测（同机、同会话对、同脚本）

方法：`out/event-driven/measure.sh <label> [sessA] [sessB]`——脚本自己起一对静音 AudioTrack 会话
（一个进程两条轨，避免内存压力与 tone churn），然后依次测：CPU/全量 pass 窗口（30 s，读
`/proc/<pid>/stat` 与 `stats.watcherTicks`）、单参数 RTT（100 次）、100 Hz×300 突发、总开关 5 轮、
并发 2 客户端（`conc2`，单进程两线程两条 TCP 连接）、慢参数干扰（`slowload`：A 上连续 16 KB 分片上传，
同时 B 上 100 次写）、新会话 attach（`AttachProbe` 8 次）。
**before = 模块里原来的 jdspd 2.1.6 dex（md5 4a48b334…），after = 本轮构建（md5 defea0c1…）。**

### 5.1 主表

| 指标 | before | after |
|---|---|---|
| jdspd CPU | 13.53 % | **3.43 %** |
| watcher 全量 pass | 1.733 /s | **0.333 /s** |
| attach p50 / p95 / max | 1325 / 2723 / 2723 ms | **739 / 755 / 755 ms**（最终构建复测 4 次 p50=703 ms） |
| `setParam` RTT p50 / p95 / max | 16.63 / 27.81 / 36.15 ms | 20.74 / 31.68 / 47.82 ms |
| 100 Hz×300 p50 / p95 | 12.52 / 27.34 ms | 20.72 / 30.10 ms |
| 总开关 off RTT（5 轮） | 5,16,16,16,19 ms | 8,10,7,8,10 ms |
| 总开关 on RTT（5 轮） | 15,11,12,17,14 ms | 6,5,9,7,8 ms |
| 并发 A p50 / p95 | 23.99 / 45.77 ms | **23.81 / 35.10 ms** |
| 并发 B p50 / p95 | 24.60 / 45.57 ms | **23.86 / 32.08 ms** |
| 慢参数期间 B p50 / p95 | 30.64 / 167.99 ms | 29.61 / 163.45 ms |

### 5.2 attach 的分段解释

before 的 1325 ms ≈ 500 ms（定时 tick 发现）+ 400 ms（去抖）+ 下一 tick 再 500 ms + attach 60-120 ms；
after 的 739 ms ≈ 100 ms（探测）+ 100 ms（去抖）+ 400 ms（去抖定时唤醒）+ attach 60-120 ms。
剩余可优化空间：把 `ATTACH_DEBOUNCE_FAST_MS` 从 400 ms 降到 200 ms 可再省 ~200 ms，但那会加大
短命会话的 chain churn 风险（历史教训），本轮不动。

### 5.3 单参数 RTT：为什么 after 的 p50 反而高？——引擎漂移，不是回归

同一轮里 daemon 自测的"引擎写"（`avgLatencyUs` 增量）：
**13.26 ms（15:24，before 窗口）→ 18.14 ms（15:38，A/B/A 窗口）**，即那一次
`AudioEffect.setParameter` binder 往返本身在 20 分钟内变慢了 37 %（audioserver 侧，2 个 effect chain
同时在线 + 设备连续压测后的正常漂移）。

为排除"改造导致回归"，在同一会话对（session 513/521）上做了 **A/B/A**（换 dex + 等 supervisor 重启，
每次 30-60 s）：

| 时刻 | dex | `statbench` p50 | 100 Hz×300 p50 | 引擎写（stats 增量） |
|---|---|---|---|---|
| 15:34 | after | 20.74 ms | 20.72 ms | — |
| 15:38 | **before** | **25.86 ms** | **25.63 ms** | 18.14 ms |
| 15:42 | after | 25.35 ms | 24.89 ms | — |

结论：**同时段两者相差 ≤2 %，RTT 由引擎侧主导，本次改造对单参数 p50 无回归**（也无可见收益，
因为 daemon 侧本来只占 ~0.6-3 ms）。真正被改善的是 CPU、attach 与并发 p95。

### 5.4 总开关（bypass 优先已在 HEAD）

`enabled off` → `global disabled: 2 instance(s) bypassed` 用时 **4 ms**；
`enabled on` → `global enabled: 2 instance(s) un-bypassed` 用时 **5 ms**（引擎实例不再重建）。
API 往返 5-10 ms（before 5-19 ms）：开关路径上不再有内联 fsync（本轮把 `applyEnabled` 里的
`config.saveThrottled(200L)` 换成异步 persister）。

---

## 6. 为什么"跨会话慢参数阻塞"没有明显改善

`slowload` 的结果前后几乎一样（p95 168 → 163 ms）。原因：阻塞点在 **audioserver**，不在 jdspd。

- 16 KB 分片上传走 `EFFECT_CMD_SET_PARAM`（STREAM 类，inline，见引擎补丁 0003 的分类表），
  在 audioserver 的 binder 线程里执行 FFT/解析，期间持有 AudioFlinger 的效果链/模块锁；
- 另一个会话的 `setParam` 也要进 audioserver 的同一条命令路径，于是照样排队；
- A1 的 per-session 锁只去掉了 **daemon 内部**的串行（实测这部分在并发 p50 上已体现：
  并发 p95 45.8 → 35.1 ms）。

要进一步改善只能改引擎（把分片上传也做成队列化/异步，或把 16 KB 分片拆小），不在本轮范围。

---

## 7. 风险、兜底与回滚

| 改动 | 风险 | 兜底 | 回滚 |
|---|---|---|---|
| 事件驱动 watcher | 漏事件 → 会话一直不 attach / 已消失的句柄不释放 | **3 s 全量兜底**（最坏 3 s 收敛）+ 每次 `poke`；探测器异常自动退化为 500 ms 定时 | 把 `WatcherPolicy.FALLBACK_MS` 调回 500 ms（一行），或 `git revert` 本文件 |
| 廉价探测器（隐藏 API 反射） | ROM 变更/权限/SELinux → 探测失败 | 失败即 `unavailable`（日志说明原因），链路自动退化；**不会**忙等 | 同上传 `probe=null` 走纯定时 |
| attach 去抖定时器 | 定时器丢任务 → 该会话等 3 s 兜底 | 兜底 pass 会再次 arm；最多慢 3 s，不会永久卡住 | 同上 |
| Daemon latch / stop 文件 inotify | inotify 不可用 → stop 文件不生效 | 5 s 复查兜底；shutdown hook/控制通道仍在 | 恢复旧循环（一次性 patch） |
| EffectManager 分锁（A1） | 状态机 bug → 句柄泄漏/重复实例 | 单实例由 `putIfAbsent` 保证；detach 有 3 s drain 上限；宿主测试 6 例 + 并发用例 | `git revert` EffectManager.java（自包含） |
| 1 s TTL 缓存 | 外部客户端改了 enabled → 最多 1 s 内不一致 | watcher 每 pass 读一次即刷新；`setEnabled` 写后立即刷新 | 把 `STATE_TTL_MS` 设为 0（等于关缓存） |

---

## 8. 复现

```bash
# 构建 + 宿主测试
cd backend && ./build.sh && ./test/run_tests.sh      # 268 passed / self-test 11 PASS
git diff --check                                      # 干净

# 真机：热替换（备份 + kill + 等 supervisor 重启）
adb push backend/out/jdspd.dex /data/local/tmp/jdspd.new.dex
adb push out/event-driven/install_dex.sh /data/local/tmp/install_dex.sh
adb shell su -c 'sh /data/local/tmp/install_dex.sh /data/local/tmp/jdspd.new.dex'
adb shell su -c 'sh /data/local/tmp/install_dex.sh /data/local/tmp/jdspd-2.1.6.before.dex'   # 回滚

# 前后对照（自己起一对静音会话；也可以传已有 sessionId 复用）
bash out/event-driven/measure.sh after
bash out/event-driven/measure.sh before 513 521

# 分段验证
adb shell su -c 'CLASSPATH=/data/local/tmp/attachprobe.dex app_process /system/bin AttachProbe 8 400'
adb shell su -c 'CLASSPATH=/data/local/tmp/latprobe.dex app_process /system/bin LatencyProbe conc2 <A> <B> 100'
adb shell su -c 'CLASSPATH=/data/local/tmp/latprobe.dex app_process /system/bin LatencyProbe slowload <A> <B> 100'
adb shell "su -c 'grep -E \"watcher pass|watcher:\" /data/adb/jdspd/jdspd.log | tail -5'"
```

诊断字段（daemon 日志，每 60 s）：`watcher: passes=N probePolls=N probeChanges=N probeCostUs=N probe=on|off lastReason=...`；
每次全量 pass 一行 `watcher pass reason=poke|change|fallback sessions=N dumpMs=N`；该行**只在会话数变化或非 fallback 触发时打印**且 ≤1/s（否则 3 s 兜底 pass 会写约 28k 行/天）。

---

## 9. 设备状态（本轮结束）

- 模块 dex：**本轮构建（md5 defea0c1d8c7972be7607e8150254a3c）**；回滚件
  `/data/local/tmp/jdspd-2.1.6.before.dex`（md5 4a48b334…）+ 每次 swap 前的自动备份
  `/data/local/tmp/jdspd.before-swap.<ts>.dex`。
- `enabled=on`、`mode=backend`、参数基线 26 项、测试用静音会话已停。
- 未 reboot、未重装模块、未改 App/native/模块脚本、未 git add/commit。
