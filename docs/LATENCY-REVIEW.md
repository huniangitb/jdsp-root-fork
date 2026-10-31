# 操作链路时延审查：参数修改与全局启停（JamesDSP fork v2.1.3）

> 审查标准：**唯一标准是"操作到生效的时延最低"**。稳定性硬约束：不得频繁重建 effect chain
> （历史实测：chain churn 会打崩 vendor HAL / system_server）。本文所有"实测"数字都来自本机
> 真机（adb 序列号 64a4bad0），原始日志与 CSV 在 `out/latency/`，建议补丁在 `out/latency/patches/`。

审查人：latency-auditor ｜ 日期：2026-10-31 ｜ 代码基线：jdsp-repo HEAD b22ecff（jdspd 2.1.3），
App 源码 loundess_git HEAD 93ca762（james.dsp 1.6.17-63）

---

## 0. 结论摘要（TL;DR）

| # | 结论 | 关键数字（实测） |
|---|---|---|
| 1 | 单参数（滑条一步）从 App 的写入口到引擎值生效只要 **4-11 ms**，不是瓶颈 | App 写日志→jdspd 值变化 p50≈7 ms；其中"引擎写"本身 3.22 ms（binder 进 audioserver） |
| 2 | **真正的瓶颈是全局总开关**：on 方向 250-413 ms，被 300 ms 限速时 **612-986 ms** | 现在 off 是"立刻卸载"、on 是"重建 + 重放 26 参数" |
| 3 | 最高收益改造：总开关改 **bypass 优先 + 空闲兜底卸载** | bypass 一次 0.25 ms（400 次 0 错误）vs 现在的 detach/attach 周期 28 ms + 143-420 ms |
| 4 | 第二收益：deferred on 改**事件唤醒**（ScheduledExecutor 到期即 apply），并把它挪到 tick 最前 | 612-986 ms → 预计 ~310-360 ms（若同时收缩 300 ms 限速 → ~60-90 ms） |
| 5 | 第三收益：App 侧去掉 500 ms pending 重放闸门 | 连点第二次 470-537 ms → <20 ms |
| 6 | 附带发现（会让人以为"总开关坏了"）：`TcpTransport.setParam` 丢掉了后端业务码，把"后端已关"当成传输故障 → 丢连接 + 3 s/10 s/30 s 退避，期间的开关操作最坏延迟 **30 s**（实测一次 2.5 s） | 详见 F6/F7 |
| 7 | "UI 不持有参数"（`syncWithPreferences` no-op）**没有**让开关路径断掉（on 之后 jdspd 会 applyAll 26 参数），但 F7 的标志位一旦能被置上就会真的断 | 见 F7 与 §7 专项验证 |

> 组合推荐（§4.5）：**bypass 优先 + 20 s 空闲卸载 + 事件唤醒的 deferred + 用户意图直投 + 批量
> setparams + TCP 保活**。在"不能频繁重建 chain"的约束下，这是唯一能同时满足
> "开关手感 <50 ms" 与 "不碰 HAL" 的组合。

---

## 1. 环境、方法与证据强度

- 设备：OnePlus OPD2404 / ColorOS 16 / Android 16 (SDK36) / SM8650 / arm64-v8a，KernelSU root，
  模块 v2.1.3-fork(213)，`mode=backend`，jdspd 2.1.3 常驻（pid 3733）。
- 测量手段：设备内 `app_process` 客户端（`out/latency/tools/LatencyProbe.java`，走 App 同款
  loopback TCP JSON 行协议）+ `jdspcli`（binder 通道）+ `logcat -v epoch`（毫秒级，App 与
  jdspd 同源时钟）+ `getParam` 值变化轮询（把"daemon 收到"定位到约 0.5 ms 网格）+
  `stats` 计数增量（用来反推 daemon 自测的引擎写耗时）。
- 播放会话：用**静音** AudioTrack（`Tone <secs> silent 0`，amp=0）制造真实播放会话，
  避免干扰同一设备上另一位 teammate（audio-tap）的采集。
- 证据强度标注：
  - **【实测】** 本机真机测出的数字，附 CSV/日志文件名；
  - **【代码】** 读代码得出的必然结论（含 file:line），未单点测量；
  - **【外部】** AOSP/官方文档佐证，附链接（§5）；
  - **【推断】** 由实测 + 代码推出的估算。
- 干扰标注：10:54-11:00 有两轮测量被"我自己的静音会话退出时 watcher 反复 attach/detach
  （每次 120 ms，含 applyAll 26 参数）"污染，p95 抬到 139 ms、max 346 ms
  （`csv/statbench-1500-on.log`、`csv/burst-100hz-on.log`）。**正文用的是 11:03 之后
  干净会话（session 433）复测的数字**（`csv/clean-*.log`）。另一 teammate 的
  `persist.sys.enable_ad_pcmdump` 实验期间（现已为 0）未观察到额外影响。

---

## 2. 链路时延地图

### 2.1 参数路径（滑条一步：UI 事件 → 引擎生效）

| # | 跳 | 代码位置 | 实测/估算 | 备注 |
|---|---|---|---|---|
| 1 | SeekBar onProgressChanged → `persistFloat` | `preference/MaterialSeekbarPreference.kt:111,117,522` | **每步一次写**（拖动 ~60 次/s）【实测】 | 无本地去抖，只有 App 侧"最后值胜出"的合并 |
| 2 | DataStore → `JdspPrefWriter.write` | `interop/jdsp/JdspPreferenceDataStore.kt:56-64`；`JdspPrefWriter.kt:88-142` | 主线程 ~1-2 ms（帧间隔 16.4 ms 未被打断）【实测】 | 首次建屏 2500 ms 内被 `bindingPhase` 吞掉（:78-98） |
| 3 | 白名单/快照/去重 | `JdspPrefWriter.kt:162-211` | 命中内存快照 ~0.1 ms【代码】 | `backendAlready` 只对 float 生效；short/int 无去重 |
| 4 | `JdspBridge.enqueue`（COALESCE 最新值胜出） | `interop/jdsp/JdspBridge.java:167-194,492-504` | O(1) map put【代码】 | UI 线程只入队，不碰 socket（:433-438 主线程守卫） |
| 5 | worker 取出 → `push`（**每个事件 2 个往返**） | `JdspBridge.java:575-587,607-709` | sleep ≤5 ms + 2×RTT | `session<=0` 时先 `sessions` 查会话列表（:644），再逐会话 `setParam` |
| 6 | `sessions` 查询往返 | `TcpTransport.java:164-188`；`TcpService.java:281-298`；`CoreApi.listSessions:150-219` | **p50 0.083 ms**【实测】 | 每个事件都多一次（F8） |
| 7 | `setParam` 往返（TCP→daemon 处理完） | `JsonProtocol:200-216` + `CoreApi.setParam:316-353` | **p50 2.81-3.77 ms，p95 7.5-13.0 ms，max 16.2 ms**【实测】 | 分布见 §2.5 |
| 8 | daemon 记账（`getSession`→`listSessions`） | `CoreApi.setParam:341` → `listSessions:150-219` → `EffectManager.states:472-479` | 每 handle 2 次 binder（`getEnabled`+`hasControl`）【代码】 | 每次写都重建整张会话视图（F5） |
| 9 | 配置落盘（异步） | `CoreApi.setParam:350` → `AsyncPersister`（200 ms 合并）→ `ConfigStore.saveThrottled:136-144` | 不在热路径，但 fsync 在 ConfigStore 监视器内【代码】 | 会挡住 `isEnabled()`（F4） |
| 10 | `EffectManager.setParam` → 反射 `AudioEffect.setParameter` | `EffectManager.java:335-382` | **3.22 ms avg**（daemon 自测）【实测】，历史 max 95.6 ms | 方法 `synchronized`，跨会话串行（F5） |
| 11 | 引擎：deferred 应用到音频线程 | `native/patches/0003`：`jdsp_paramq_push` → `generic_process` 里 `jdsp_paramq_drain` | ≤1 个 buffer 周期（4-21 ms 量级）【推断】 | 值参数只排队、不重建；1300 也只排队 |
| **合计** | UI 事件 → 引擎生效 | — | **约 5-20 ms（p50 ≈ 10 ms）**；空闲 >30 s 后第一次 **+65 ms**【实测】 | 参数链路已经很好，只有尾部（p95/p99）值得修 |

### 2.2 全局启停路径（总开关）

| # | 跳 | 代码位置 | off（实测） | on（实测） |
|---|---|---|---|---|
| 1 | FAB 点击 → `JdspBridge.setEnabledNow` | `activity/MainActivity.kt:276-327`；`JdspBridge.java:302-341` | 主线程 → **永远走 pending 通道**（`currentTransport()` 主线程返回 null，:433-438） | 同左 |
| 2 | pending 重放闸门 | `JdspBridge.java:533-542`（`PENDING_REPLAY_INTERVAL_MS=500`，:100） | 闸门开着 +30-90 ms；闸门关着 **+470-537 ms**【实测】 | 同左 |
| 3 | TCP 往返（setEnabled） | `TcpTransport.java:206-222`；`TcpService.java:281-298` | RTT 24-53 ms（5 次）【实测】 | RTT 10-25 ms【实测】 |
| 4 | `CoreApi.setEnabled` → `EnableSwitch` | `CoreApi.java:558-576`；`EnableSwitch.java:86-103` | off 永不延迟 | **on 方向 300 ms 限速，可能 DEFER** |
| 5 | 应用：off=卸载 / on=重建 | `CoreApi.applyEnabled:579-602` | `config.saveThrottled`（fsync 内联）+ `detachAll` → **bypass 生效 5-11 ms，句柄释放 26-30 ms**【实测】 | `watcher.poke()` → 下一 tick |
| 6 | on 的重建路径 | `SessionWatcher.pollOnce:185-220`（先 dumpsys 再 reconcile）+ `attachSession:530-548` | — | `AudioEffect` ctor **52-112 ms**，applyAll 26 参数 **65-167 ms** |
| 7 | **deferred on 的应用时机** | `SessionWatcher.pollOnce:217-219`（tick **末尾**才 poll）+ `CoreApi.pollEnableSwitch:613-621` | — | **请求→applied 612 ms；请求→引擎重新 enabled 986 ms**【实测】 |
| **合计** | 点击 → 引擎状态变化 | — | **30-130 ms**（bypass 生效 5-11 ms） | **250-413 ms**（限速路径 612-986 ms；连点第二次再 +470-537 ms） |

补充实测（daemon 侧直接驱动，排除 App 变量）：off 请求到达→`global disabled` 日志 11 ms；
5 轮 off/on 周期里 on 完成 250 / 266 / 413 ms；**关掉 60 s 后再 on：339 ms**（完整 attach +
applyAll 26 参数）。

### 2.3 慢参数（会触发重建 / 大块搬运的写入）

| 参数 | 语义 | 引擎侧动作 | 实测 RTT |
|---|---|---|---|
| 1300（PEQ 级联，8 band / 168 B） | 值参数，变长 | deferred 到音频线程 `peq_cascade_configure`（含 biquad 重算） | p50 **0.835-2.82 ms**，p90 2.7-7.8 ms（与普通参数同级）【实测】 |
| 1301/1305/1306/1307 | 值参数（20/44 B） | deferred | 2-4 ms【实测+代码】 |
| 9999+12000×N+10004（卷积 IR） | **STREAM 类，内联**（`jdsp_paramq` 分类表） | `Convolver1DLoadImpulseResponse`（FFT）+ CRC32，**在命令线程** | 4096 帧立体声：总 **34.0 ms**（分片 max 6.46 ms，done 5.05 ms）；16384 帧立体声：总 **101.7 ms**（分片 max 13 ms，done 11.66 ms）【实测】 |
| 8888/12001/10006/10009/10010 | 字符串/DDC/EEL 上传，内联 | 解析 + 分配，命令线程 | 同量级（16 KB 分片 ≈ 6-13 ms/片）【实测】 |

结论：**滑动类参数不需要分级**（都是 ms 级）；真正需要的是"**批量**"和"**别让大块上传挡住别人的写**"
——`EffectManager.setParam` 是 `synchronized`，一次 100 ms 的 IR 上传会把同会话（以及跨会话）的
所有参数写、`getParam`、`attach` 全串行化（F5）。

### 2.4 首次连接 / 空闲后第一条

| 场景 | 实测 | 根因 |
|---|---|---|
| App 进程冷启后第一次写 | 先 `ensureTransport`（tcp→socket→binder→shell 逐个探测） | `JdspBridge.java:750-789` |
| 空闲 > 30 s 后第一条参数 | **+65 ms**【实测】 | daemon `TcpService.java:44` `IDLE_TIMEOUT_MS=30000` 主动断连；App `roundTrip` 失败→`close()`→重连+重试 |
| 后端关着时编辑 5-6 次，再点开关 | 首次退避 **2.5 s**，最坏 **30 s**【实测+代码】 | F6：`TcpTransport.setParam` 把 -1006 吞成 -2 → 当成传输故障 → 丢连接 → `DISABLE_BACKOFF_MS={3s,10s,30s}`（`JdspBridge.java:71`） |

### 2.5 单参数 RTT 分布（干净会话，100 次）

```
p50 3.77 ms  p90 9.68 ms  p95 10.31 ms  p99 13.07 ms  max 13.07 ms   (csv/clean-statbench-1500.log)
100 Hz 定速 300 次：p50 3.64  p90 9.93  p95 12.96  p99 15.10  max 16.16  (csv/clean-burst-100hz.log)
其中"引擎写"（daemon 自测）avg = 3.22 ms；纯传输 ping p50 = 0.201 ms
```

说明：**时延的约 85% 是那一次 `AudioEffect.setParameter` binder 往返**（进 audioserver 的
`EffectHandle::setParameter` → 引擎 `command()`【外部】），TCP + JSON + 记账只占 0.5 ms 左右。
所以"换传输通道"（binder↔TCP）最多省 0.2-0.5 ms，**不是**优化重点。

---

## 3. 问题表 F1..F9

> 排序按"对唯一标准的影响"。工作量：S ≤ 0.5 人日，M ≤ 2 人日，L > 2 人日。

### F1 总开关 off = 立刻卸载 effect chain（on 因此必须重建）
- 现象：off 之后实例被 `release()`；再 on 要 `AudioEffect` ctor（52-112 ms）+ 重放 26 参数
  （65-167 ms）+ `setEnabled`。
- 根因：`CoreApi.applyEnabled:588-597` → `EffectManager.detachAll()`；`EnableGate.action` 在
  `globallyOff && alreadyHeld` 时返回 `ACTION_RELEASE`（`EnableGate.java:40-46`）。
- 最坏时延：on 143-420 ms（deferred 时 986 ms）；且每次 on/off 都动 chain —— **与"不许 chain
  churn"的硬约束正面冲突**。
- 最小修复：off 先 `setEnabledAll(false)`（bypass），20 s 后才 `detachAll()`；on 时句柄还在就
  `setEnabledAll(true)`。→ 补丁 `0001`。
- 预期收益：**on 250-413 ms → 引擎侧 ~1 ms（端到端 <30 ms）**；off 26-30 ms → ~1 ms；chain 不再重建。
- 风险：关着时实例仍在链上（零处理、占一个句柄 + 一次直通拷贝；该流本来就在 normal mixer path，
  见 §5）。20 s 后仍会卸载 → 长期资源不变。
- 工作量：S（~90 行：EnableGate/EffectManager/CoreApi/SessionWatcher）。

### F2 deferred "on" 靠 500 ms 轮询落地，且 poll 在 tick 末尾
- 现象：被限速的 on 要 612 ms 才 applied，986 ms 才真正 enabled。
- 根因：`EnableSwitch.DEFAULT_MIN_INTERVAL_MS=300`（`EnableSwitch.java:51`）+
  `SessionWatcher.POLL_MS=500`（:30）+ `pollEnableSwitch()` 在 `pollOnce` **最后**
  （:217-219，在 78 KB dumpsys + reconcile + `saveIfDirty` 之后）。
- 最坏时延：300（限速）+ 500（轮询）+ dumpsys 28-150 ms ≈ **830-950 ms**（实测 612 / 986）。
- 最小修复：`EnableSwitch` 暴露 `pendingDeadlineMs()`，`CoreApi` 用 `ScheduledExecutor` 到点即
  apply；tick 里的 poll 挪到最前面当兜底。→ 补丁 `0002`。
- 预期收益：**612-986 ms → ~310-360 ms**（只留 300 ms 限速）；若限速降到 50 ms 防抖 →
  **~60-90 ms**。
- 风险：极小（只改触发时机，不改语义）。
- 工作量：S（~50 行）。

### F3 App 侧启停意图被 500 ms 节流闸门压住
- 现象：连点两次开关，第二次 470 / 516 / 537 ms 后才到 daemon（3/3 实测）。
- 根因：`JdspBridge.workerLoop:533-542` 的 `now - lastPendingReplay > 500` 闸门 +
  `setEnabledNow` 在主线程只能挂 `pendingEnabled`（:302-308）。
- 最坏时延：~540 ms（叠加 daemon 侧可达 ~1 s）。
- 最小修复：主线程把用户意图塞进 `ENABLE_REQUEST`（`AtomicReference`）+ `LockSupport.unpark`，
  worker 循环**最前面**无节流消费；成功即清 `backendDisabled`；传输出问题时立刻重连（不等退避）。
  → 补丁 `0005`。
- 预期收益：**470-537 ms → <20 ms**（一次 RTT + ≤2 ms 唤醒）。
- 风险：小（同一 worker，只是优先级；用户意图不再被节流）。
- 工作量：S（~60 行）。

### F4 config fsync 在 ConfigStore 监视器内，挡住参数热路径
- 现象：burst 的 p95/p99 尖峰（7.5-15 ms），历史 max 95.6 ms；`/data/adb` 一次 fsync 35-50 ms。
- 根因：`ConfigStore.saveThrottled/saveIfDirty/save` 都是 `synchronized` 方法，内部
  `saveLocked()` 做 tmp+fsync+rename（:136-169）；而 `CoreApi.setParam` 第一步就要
  `config.isEnabled()`（:323）→ 排在 fsync 后面。
- 最坏时延：一条写被 +35-50 ms 挡住（5 次/s 落盘 ⇒ ~5% 的写命中）。
- 最小修复：锁内只做 JSON 快照，fsync 移到独立 `fileLock` 下执行。→ 补丁 `0003`。
- 预期收益：p95 尾巴预计 **-30~-45 ms**；p50 不变。
- 风险：小（写顺序由 `fileLock` 串行；`dirty` 语义不变）。
- 工作量：S（~70 行）。

### F5 参数写隐式串行化 + 每次写重建会话视图
- 现象：任意一次慢写（如 100 ms 的 IR 分片上传）把所有会话的写/读/attach 串起来；每次写还重建
  整张 session view（每 handle 2 次 binder）。
- 根因：`EffectManager.setParam` 是 `synchronized`（:335）且反射调用在锁内；`CoreApi.setParam:341`
  为拿 pkg 调 `getSession()`→`listSessions()`→`EffectManager.states()`（:472-479，每 handle 调
  `getEnabled()`+`hasControl()`）。
- 最坏时延：并发时 +100 ms 级（取决于上传）；单写空转 +0.2-1 ms。
- 最小修复（本补丁内）：`pkgOf(sessionId)` 直接读 handle（补丁 `0004`）。锁粒度改造属设计变更
  （§6 档 B）。
- 预期收益：单写 -0.2-1 ms；并发抖动显著下降。
- 风险：锁结构改造为中风险（需回归 400+ 次并发写 + soak）。
- 工作量：S（补丁部分）/ M（锁改造）。

### F6 `TcpTransport.setParam` 丢掉后端业务码 → "后端已关"被误判为传输故障
- 现象：后端关着时连续编辑 5 次后 App 丢弃连接并进入 3 s/10 s/30 s 退避；期间的开关点击最坏等
  30 s（实测一次 2.5 s）。
- 根因：`TcpTransport.java:136-143` 失败统一 `return -2`（只有 `setEnabled` 会取 `code`，:214-218），
  而 `JdspBridge.push:621-626` 只在 `rc == -1006` 时置 `backendDisabled`。
- 最坏时延：30 s（`DISABLE_BACKOFF_MS` 封顶）。
- 最小修复：把后端 `code` 原样返回（补丁 `0006`）+ 保活（同补丁）。
- 预期收益：消除 3-30 s 的开关黑洞，参数意图留在正确通道里。
- 风险：小（只改返回语义；`-1006` 分支本来就有）。
- 工作量：S。

### F7 `backendDisabled` 一旦置上就没有可靠出口（修 F6 之后会变成真死锁）
- 现象：`backendDisabled=true` 时 worker 的 pending 重放闸门 `&& !backendDisabled`（:534）永久关闭；
  清它的地方只有 `setEnabledNow` 的成功分支（:316，要求非主线程且有连接）和
  `JdspBridge.getEnabled()`（:346-359，**全工程无调用者**；`JdspParamSink.getEnabled` 走的是
  `JdspStatusCache`）。
- 最坏时延：∞（要重启 App 进程）。今天看不出，是因为 F6 让 `backendDisabled` 根本置不上
  （代价是 3-30 s 退避）。
- 最小修复：补丁 `0005` —— 用户意图直投且不受 `backendDisabled` 影响；成功即清位；重放闸门对
  "on" 放行 + 退避期间用户操作直接重连。
- 预期收益：把"偶发 30 s"变成"确定 <20 ms"，消除潜在永久卡死。
- 风险：小。
- 工作量：S（与 F3 同一个补丁）。

### F8 每个滑条事件 2 个往返（`sessions` 查询 + `setParam`）
- 现象：App 的 sink session=0，`push` 每次都先 `listAttachedSessions()` 再逐会话写
  （`JdspBridge.java:644-664`）。
- 根因：daemon 侧 `CoreApi.setParam` 要求 `session>0`；App 没有"全局写"语义。
- 最坏时延：多 1 次 RTT（0.08-1 ms）+ 每会话多一次 3 ms 引擎写（N 个会话时 N 次）。
- 最小修复：daemon 增加 `setParamGlobal`（session<=0 = 全部已挂载会话）与 `setparams` 批量 op
  （补丁 `0004`）；App 侧把 session 传 0（一行）。
- 预期收益：事件 -0.1~1 ms；N 会话时省 N-1 次往返；顺带把"每写重建 session view"（F5）一起去掉。
- 风险：小（新 op 向后兼容，旧 op 语义不变）。
- 工作量：S（daemon ~120 行，补丁已给）。

### F9 空闲断连导致"第一条 +65 ms"
- 现象：空闲 >30 s 后第一次参数写/开关点击要付一次 TCP 重连（实测 +65 ms）���
- 根因：`TcpService.IDLE_TIMEOUT_MS=30000`（:44）vs App 侧无保活。
- 最小修复：App worker 空闲时每 20 s `ping`（补丁 `0006`）；或 daemon 空闲超时提到 120 s（更省）。
- 预期收益：**-65 ms**（只影响"隔一会儿再动手"的第一下）。
- 风险：极小。工作量：S。

---

### F10 UI 线程上"日志即文件 IO"（可选优化，非当前瓶颈）
- 现象：滑条每步都会在 UI 线程走一次 `Timber.i("write %s/%s param=%s ...")`（`JdspPrefWriter.kt:91-93`），
  而 `MainApplication.kt:164,177-186` 同时装了 `DebugTree`（logcat）与
  `fr.bipi.tressence.file.FileLoggerTree`（`minPriority=VERBOSE`，2 MB 文件），即每条日志都多
  一次文件侧写。
- 根因：热路径上的日志级别过高 + 文件 logger 常开。
- 实测影响：**未观察到阻塞**——拖动期间相邻两次写的日志间隔稳定在约 16.4 ms（60 Hz 帧节奏），
  没有出现掉帧式跳变；因此这是"理论风险 + 可做的小优化"，不是当前瓶颈。
- 最小修复：把 `JdspPrefWriter.write` 的那条日志降到 `v`/仅在 debug 输出；或给文件 logger 设
  `minPriority=INFO` 并只在 debug 构建开启。
- 预期收益：UI 线程每步省一次 logcat + 一次文件追加（无法精确量化，实测 <1 ms）；风险极低。
- 证据强度：【代码】+【实测（未观察到阻塞）】；该库是否为异步缓冲**未确认**（gradle 缓存里没有可读
  的 jar/class），因此按"同步写"保守估计。

## 4. 方案对比（按 lead 要求逐项对比，含预期 ms 与风险）

### 4.1 总开关语义：三方对比

| 方案 | off 生效 | on 生效 | chain 变动 | HAL/CPU 风险 | 语义 | 结论 |
|---|---|---|---|---|---|---|
| A 现在：off=**立刻卸载** | bypass 5-11 ms / 释放 26-30 ms | **250-413 ms**（deferred 986 ms） | 每次 on/off 都 create+teardown | **高**：历史实测 chain churn 打崩 HAL/system_server | "关就是彻底移除" | 时延最差，风险最高 |
| B 只 bypass、**永不卸载** | ~0.25 ms | ~0.25 ms | 不变 | 低（但关着也占句柄 + 一次直通拷贝，无限期） | "关=旁通" | 时延最好；资源语义差（关机/长时间关闭也留实例；ROM 侧 OOM/回收不可控） |
| **C 混合（推荐）**：bypass 立即 + **20 s 空闲后卸载** | ~0.25 ms（+ 后台 20 s 后 release） | 句柄在 → ~0.25 ms；已卸载 → 150-340 ms【实测】 | 只在长时间关闭后动一次 | **低** | "关=立刻静音，空闲后回收" | **全局最优**：手感 <30 ms、chain 几乎不动、长期资源与 A 相同 |

- 推荐 C 的量化依据：bypass 400 次实测 p50 0.259 ms / p95 0.583 ms / max 0.865 ms，**0 错误、无
  chain 重建**（`csv/bypass-200cycles.log`）；A 方案实测 5 轮 on 完成 250/266/413 ms，关 60 s 再 on
  339 ms。
- C 的风险与缓解：①关着时实例留在链上 → 用一个可配的 `IDLE_UNLOAD_MS`（建议 15-30 s，默认 20 s）
  并在"会话消失/策略排除/daemon 退出"时立即卸载；②用户在 20 s 内反复开关 → 只走 bypass，不会 churn；
  ③`EnableGate` 必须新增"off 但保留句柄"的判定，且 reconcile **不得**在 off 期间把实例重新
  `setEnabled(true)`（补丁 `0001` 已按此实现：`ACTION_BYPASS_HELD`）。

补充（bypass 稳定性证据的完整口径）：BypassBench 的 200 轮不是按 100 Hz 节流的，而是**不做任何
sleep 的连续切换**（实测 400 次切换总耗时 <1 s，等效约 2-4 kHz），比"100 Hz 连翻 200 次"苛刻一个
数量级：setEnabled(false) p50 0.259 ms / p95 0.583 ms / max 0.865 ms，setEnabled(true) p50 0.242 ms /
p95 0.630 ms / max 8.572 ms，**400/400 返回 0**；同期 jdspd 日志无 error，AudioFlinger/audioserver
在该窗口内**没有任何 E/F 级日志**（只有我自己的静音轨 "trackId 98 is playing mute data"），也没有
出现 attach/detach 重建。这直接支撑"bypass 不重建 chain ⇒ 可以放心连点"的结论。

### 4.2 deferred 的落地机制

| 机制 | 最坏落地时延 | 代价 | 结论 |
|---|---|---|---|
| A 现在：500 ms 轮询（且 poll 在 tick 末尾，前面还有 78 KB dumpsys） | 300 + 500 + dumpsys ≈ **830-950 ms**（实测 612/986） | 每 tick 一次 dumpsys | 不可接受 |
| **B 事件唤醒**：`ScheduledExecutor` 在 `pendingDeadline` 到点即 apply（补丁 0002） | **300-310 ms**（就剩限速本身） | 一个守护线程 | **推荐** |
| C 事件唤醒 + 把限速从 300 ms 收缩为 50 ms 防抖（lead 决策 2） | **~50-90 ms** | 需确认 HAL 侧 `AudioSystem::setEffectEnabled` 频率安全（bypass 路径实测 400 次/秒级无异常） | **推荐（与 C 方案叠加）** |
| D 单靠把 `pollEnableSwitch` 挪到 tick 头部 | 300 + dumpsys(28-150) ≈ 330-450 ms | 零成本 | 兜底保留（补丁 0002 也做了） |

### 4.3 参数写：单参数往返 vs 批量 vs daemon 合并

| 方案 | 每事件往返 | 每事件引擎写 | 实测/估算 | 说明 |
|---|---|---|---|---|
| A 现在：App 侧 1 事件 = `sessions` + 1×`setParam` | 2 | 1 | p50 3.8 ms、p95 13 ms | 会话数 N 时是 1+N 个往返 |
| **B 批量 op** `setparams`（补丁 0004） | 1 | 1 | 省 0.08-1 ms/事件 | 一帧内多个参数（如 PEQ 级联 + 使能位）一次发完 |
| **C 服务端"全局写"** `setParam`(s=0)（补丁 0004） | 1 | N（在 daemon 内，并行/串行由 daemon 决定） | 省 1 个往返 + 省 App 侧会话枚举 | App 语义最贴合（它只有 sink session=0） |
| D 用 `commit`/`setParamDeferred` 合并 | — | 每条参数已被引擎 3.2 ms 主导 | 收益很小 | 60 Hz 拖动时合并窗口 2 ms 只能合并 1-2 帧 |

结论：**B+C 是纯收益**（省往返、省 App 枚举、顺带修 F5 的会话视图重建）；D 不值得。

### 4.4 传输层：TCP vs binder vs 其它

| 通道 | 现状 | 单次往返 | 结论 |
|---|---|---|---|
| loopback TCP（现用） | 每连接一线程，`setTcpNoDelay(true)`，每请求一次 flush | **ping p50 0.201 ms**；JSON 编解码 + 0.2 ms | 可用；不是瓶颈 |
| binder（`BinderTransport`） | **`setEnabled` 是占位（+19 事务号，服务端未实现）**；`listAttachedSessions` 返回 null；`getEnabled` +20 也未实现 | 估 ~0.1-0.3 ms（binder 更快，但省下的 < 引擎写的 3.2 ms） | 现状不可用作主通道；**修好 setEnabled/getEnabled 也只是省 0.1-0.5 ms，不值得优先** |
| jdspcli/shell | 每次 fork 一个进程 | 数十 ms | 只适合诊断 |

结论：**保留 TCP**；binder 的价值在"少一层 JSON"和"更少失败模式"，但优先级低于 F1-F3。
（若未来把 App 的写改成"每个 UI 帧一批"，TCP 的 `flush` 成本也已被批量化摊薄。）

### 4.5 组合推荐（全局最优）

1. **总开关 = 方案 C**：bypass 立即（0.25 ms）+ 20 s 空闲卸载；on 若句柄在 → 0.25 ms，否则 150-340 ms。
2. **deferred = 事件唤醒**（ScheduledExecutor 到点 apply）+ 限速收缩为 50 ms 防抖 + tick 头部 poll 兜底。
3. **App 启停意图直投**（无 500 ms 闸门、清 `backendDisabled`、退避期立即重连）。
4. **参数写批量/全局 op**（`setparams` / `setParamGlobal`），并去掉每写重建 session view。
5. **TCP 保活 20 s** + 业务码透传（`-1006`）。
6. 配置 fsync 出锁（尾部时延）。

预期端到端（把 §2 的实测数字代进新路径）：

| 操作 | 现在 | 改造后（预期） |
|---|---|---|
| 滑条一步 → 引擎 | 5-20 ms（p95 13-16） | 4-12 ms（p95 预计 <8） |
| 开关 off → 静音 | 30-130 ms | **<10 ms** |
| 开关 on → 出声 | 250-413 ms | **<30 ms**（句柄在）/ 150-340 ms（已空闲卸载） |
| 连点 off/on | 510-1000 ms | **<60 ms** |
| deferred on（连点后） | 612-986 ms | **~60-90 ms** |
| 后端关着编辑后再开 | 2.5-30 s | **<20 ms** |

---

## 5. 外部依据（AOSP / 官方文档）

| 结论 | 依据 | 链接 |
|---|---|---|
| `AudioEffect.setParameter` 是**同步 binder 调用**，进 audioserver 后直接调用引擎 `command(EFFECT_CMD_SET_PARAM)`（在 binder 线程执行，不是音频线程） | AOSP `Effects.cpp`：`EffectHandle::parameter()` → `mEffect->command(EFFECT_CMD_SET_PARAM, ...)` | https://android.googlesource.com/platform/frameworks/av/+/aba407f/services/audioflinger/Effects.cpp |
| 使能/禁用走 `EffectModule::setEnabled`：先取 `mLock`，再 `AudioSystem::setEffectEnabled`（**每次 toggle 都触及 audio policy/HAL 路径**），并维护 IDLE/STARTING/ACTIVE/STOPPING 状态机 | AOSP `Effects.cpp`：`status_t AudioFlinger::EffectModule::setEnabled(bool enabled) { Mutex::Autolock _l(mLock); return setEnabled_l(enabled); }` | 同上 |
| **创建 effect 比切换 enabled 贵得多**：`createEffect_l()` 持播放线程锁，新建 chain/输入缓冲、`AudioSystem::registerEffect()`（HAL）、`new EffectModule` → `EffectCreate()` | AOSP `Threads.cpp` / `EffectChain` 分析（`createEffect_l()` + `addEffectChain_l()` 持 `mLock`、`new int16_t[numSamples]`） | https://blog.csdn.net/njuitjf/article/details/6891477 （引 AOSP 源码；AOSP 原文见 Threads.cpp） |
| **一旦有 effect，流就不在 fast mixer 上**（fast mixer 明确裁掉"每条轨道/每次混音的效果"）——所以"关着但保留句柄"不会额外牺牲低延迟路径 | AOSP 延迟设计文档："删减的功能：每条轨道的采样率转换 / 每条轨道的效果 / 每次混音的效果" | https://source.android.com/docs/core/audio/latency/design |
| AudioFlinger effect 框架/生命周期（chain 由 AudioFlinger 维护，effect 生命周期与 session 绑定） | AOSP "Audio effects" 文档 | https://source.android.com/docs/core/audio/audio-effects |
| `AudioEffect.setEnabled()`/`setParameter()` 需要 control（`hasControl`）才能改变状态；`setParameter` 在无控制权时也可能被拒 | Android `AudioEffect` API 文档（本机受限无法抓取正文，链接备查） | https://developer.android.com/reference/android/media/audiofx/AudioEffect |

> 说明：第三行引用的中文博客只是"转载 AOSP 源码片段"的可读版本，AOSP 原始文件为
> `frameworks/av/services/audioflinger/Threads.cpp` 的 `createEffect_l()` / `EffectChain::EffectChain`。
> 本机对 developer.android.com 的抓取被网关拒绝（HTTP 403 / domain rejected），故该行只作链接备查，
> 结论仍以本机实测为准。

---

## 6. 改造清单（两档）

### 档 A：零/低风险、可以立即做（不改协议语义、不动引擎）

| # | 改造 | 文件 | 预期收益 | 风险 | 工作量 | 补丁 |
|---|---|---|---|---|---|---|
| A1 | 总开关 **bypass 优先 + 20 s 空闲卸载**（IDLE_UNLOAD_MS 可配） | common/EnableGate.java、backend/EffectManager.java、backend/CoreApi.java、backend/SessionWatcher.java | on 250-413 → **<30 ms**；off 26-30 → **<10 ms**；不再 churn | 低（关着时占句柄 20 s；reconcile 语义要一起改） | S | 0001 |
| A2 | deferred 改**事件唤醒** + tick 头部 poll 兜底 | common/EnableSwitch.java、CoreApi.java、SessionWatcher.java | deferred 612-986 → **~310-360 ms** | 极低 | S | 0002 |
| A3 | 限速 300 ms → **50 ms 防抖**（连点折叠为最后一次） | EnableSwitch.java（一行常量 + 注释） | deferred 再降到 **~60-90 ms** | 低（bypass 路径实测 400 次/秒级无异常；仍保留防抖） | XS | 建议随 A1 |
| A4 | ConfigStore：fsync 出锁（锁内只做快照） | backend/ConfigStore.java | p95 尾巴 **-30~-45 ms** | 低 | S | 0003 |
| A5 | 参数写：pkgOf() 取代每次 getSession()；新增 setParamGlobal + setparams 批量 op | CoreApi.java、EffectManager.java、JsonProtocol.java | 每事件 -0.1~1 ms；N 会话时省 N-1 往返 | 低（向后兼容） | S | 0004 |
| A6 | App：启停意图**直投**（去 500 ms 闸门、清 backendDisabled、退避期立即重连） | interop/jdsp/JdspBridge.java | 连点 470-537 → **<20 ms**；消除 30 s 黑洞 | 低 | S | 0005 |
| A7 | App：TCP 业务码透传（-1006）+ 20 s 保活 | TcpTransport.java、JdspTransport.java、JdspBridge.java | 空闲首条 **-65 ms**；修 F6 | 极低 | S | 0006 |

### 档 B：需要设计变更（要评审 + 回归 soak + 双设备验证）

| # | 改造 | 为什么不是立即 | 预期收益 | 风险 |
|---|---|---|---|---|
| B1 | EffectManager 锁粒度：反射写移出监视器（或按会话分锁） | 动的是所有写/读/attach 的并发结构；需 400+ 并发写 + 长稳 | 并发抖动 -50~100 ms（大块上传时）；单写 -0.2 ms | 中高（写锁 jdsp_writelock 语义要一起回归） |
| B2 | App 侧"一帧一批"：把同一帧的多个参数合并成一个 setparams | 需要改 UI 写入契约（JdspPrefWriter 的同步单点写入 → 帧缓冲） | PEQ 级联 + 使能位少 1 个往返；多参数页收益明显 | 中（115/116/1300/1305-1307 现在靠 ORDERED 队列保序） |
| B3 | binder 通道补齐（setEnabled/getEnabled/listAttachedSessions）并做择优 | 补 AIDL 事务号 + SELinux 放行评估（untrusted_app → su 域服务是安全边界问题） | 省 0.1-0.5 ms/请求；少一层 JSON 失效点 | 中（安全域；见 docs/P5-APP-INTEGRATION.md §4） |
| B4 | "关=彻底卸载"改为后台可配策略（含长时间关闭预卸载） | 语义变更，需产品确认 | 与 A1 一起把 on 稳定在 <30 ms | 低-中 |

> **推荐落地顺序**：A1+A3（开关手感）→ A6+A7（App 侧不再拖后腿、修 F6/F7）→ A2（deferred 落地）
> → A4/A5（尾部与批量）→ 再评估 B1/B2/B3。

---

## 7. 专项验证：「UI 不持有参数」之后，App 侧总开关路径通不通？

结论：**正常状态下通；异常状态下会"点了不响"，且有两种不同成因。**

| 场景 | 实测结果 | 判定 |
|---|---|---|
| App 点击关（后端 on，传输在线） | 注入窗口 484.403-484.472 → daemon global disabled 484.493 → detach/bypass 484.494 → 释放 484.511；App 日志 replayed pending global enable=false 484.513 | **通**，端到端 ~90 ms |
| App 点击开（后端 off） | 点击 509.982-510.152 → daemon global enabled 510.095 → attach 完成 510.177 → applyAll 26 参数 510.237 → setEnabled(true) 510.238 | **通**，端到端 ~150-260 ms；**参数由 jdspd 的 watcher 重放**（syncWithPreferences no-op 不影响） |
| 连点两次 | 第 2 次 470/516/537 ms 后才发出（App 侧 500 ms 闸门） | **通但很慢**（F3） |
| 后端 off 期间编辑 5-6 次滑条，再点开 | 点击后 **2.5 s** 才到 daemon（首次退避 3 s）；最坏 30 s | **不可靠**（F6+F7）；且 JdspStatusCache.enabled() 是 2 s 轮询快照，UI 的开关方向甚至可能是"反的"（本次实测中我点"开"，App 实际发出的是 enable=false） |
| backendDisabled=true 且传输换成能透传 -1006 的通道（binder/socket，或修完 F6 之后） | pendingEnabled 永远不被重放（闸门 && !backendDisabled），唯一清位入口 JdspBridge.getEnabled() 无调用者 | **会永久卡死**（F7，代码判定；今天被 F6 掩盖） |

要点：
1. syncWithPreferences 改成 no-op **没有**破坏"开"的效果恢复——后端 attach 时会 applyAll 26 个参数
   （实测每次 attach 都有 applyAll session=X: 26 parameter(s) applied）。
2. 真正的问题在 App 的**错误码契约**与**标志位出口**（F6/F7），而不是"App 不持有参数"这件事本身。
3. JdspStatusCache.enabled() 的 2 s 轮询快照是 UI 状态的唯一来源：开关切换后 UI 最多 2 s 才能反映
   后端真相；后端被外部改过时，用户会看到与实际相反的开关方向。

---

## 8. 复现命令附录

> 全部命令在仓库根目录执行；S=64a4bad0。设备侧客户端与脚本都归档在 out/latency/tools/。

### 8.1 构建设备侧客户端（一次性）

    SDK=/home/bdy/Android/Sdk
    D8=$(ls $SDK/build-tools/*/d8 | sort -V | tail -1)
    AJ=$(ls $SDK/platforms/android-*/android.jar | sort -V | tail -1)
    cd out/latency/tools
    javac --release 8 -nowarn -d classes LatencyProbe.java
    $D8 --release --min-api 29 --lib "$AJ" --output dexroot classes/*.class
    adb -s 64a4bad0 push dexroot/classes.dex /data/local/tmp/latprobe.dex
    # 静音播放会话（amp=0，不打扰别人）：脚本 start_tone2.sh / cleanup.sh 已归档
    adb -s 64a4bad0 push start_tone2.sh /data/local/tmp/start_tone2.sh
    adb -s 64a4bad0 shell su -c 'sh /data/local/tmp/start_tone2.sh'    # 打印 SESSION=<id>

### 8.2 单参数 RTT / 各 op RTT

    P="CLASSPATH=/data/local/tmp/latprobe.dex app_process /system/bin LatencyProbe"
    adb -s 64a4bad0 shell su -c "$P bench 433 1500 100"      # S,<i>,<rtt_us>,<ok> + SUMMARY
    adb -s 64a4bad0 shell su -c "$P op ping 100"
    adb -s 64a4bad0 shell su -c "$P op sessions 50"
    adb -s 64a4bad0 shell su -c "$P op dumpConfig 20"
    adb -s 64a4bad0 shell su -c "$P burst 433 1500 100 3"    # 100 Hz 定速 300 次
    adb -s 64a4bad0 shell su -c "$P statbench 433 1500 100"  # 附带 stats 前后快照 → 反推引擎写耗时

### 8.3 滑条 App→jdspd 端到端

    # 1) 设备侧轮询 getParam（0.5 ms 网格）；2) 用 input 注入点击并记录注入窗口
    adb -s 64a4bad0 shell su -c 'setsid sh /data/local/tmp/watchparam.sh 433 1500 14000 12 /data/local/tmp/wp.csv < /dev/null > /dev/null 2>&1 &'
    adb -s 64a4bad0 shell 'date +%s%3N; input tap 1200 1418; date +%s%3N'
    adb -s 64a4bad0 shell su -c 'cat /data/local/tmp/wp.csv'
    adb -s 64a4bad0 shell su -c 'logcat -d -v epoch' | grep JdspPrefWriter

对齐方法：App 日志里的 value=<float> 与 getParam 回复的第 3 个 float 一一对应，取
"App 日志时刻 → 该值首次出现时刻"之差即为 App→daemon 时延（实测 4/6/8/11 ms）。
滑条坐标来自 uiautomator dump（见 raw/ui-dump.xml）：后增益 SeekBar 的 bounds 是 [108,1355][2682,1481]。

### 8.4 总开关（daemon 侧直驱 + App 侧真机点击）

    B="CLASSPATH=/data/adb/modules/ainur_jamesdsp/backend/jdspcli.dex app_process /system/bin com.jdsp.cli.Main"
    adb -s 64a4bad0 shell su -c "$B enabled off"; sleep 2; adb -s 64a4bad0 shell su -c "$B enabled on"
    adb -s 64a4bad0 shell su -c 'logcat -d -v epoch' | grep -E 'global enable|global disabl|applying the deferred|attach session|applyAll|setEnabled session'
    # 连点：第二个 on 落在 300 ms 限速窗口内 → 观察 deferred
    adb -s 64a4bad0 shell su -c "$P defer 100 2500"
    # App 侧：总开关 FAB 坐标来自 ui-dump.xml（james.dsp:id/power_toggle = [1427,1848][1574,1995]）
    adb -s 64a4bad0 shell 'date +%s%3N; input tap 1500 1921; date +%s%3N'
    adb -s 64a4bad0 shell su -c 'logcat -d -v epoch' | grep -E 'replayed pending|global enable|global disabl'

### 8.5 bypass-only 稳定性（200 次 off/on = 400 次切换）

    adb -s 64a4bad0 shell su -c "$B detach 433"    # 让本工具成为 controlling client
    adb -s 64a4bad0 shell su -c 'CLASSPATH=/data/local/tmp/bypassbench.dex app_process /system/bin BypassBench 433 200'
    # 期望：setEnabled(false) p50 ~0.26 ms / setEnabled(true) p50 ~0.24 ms；ERRS 0 of 400
    adb -s 64a4bad0 shell su -c 'logcat -d -v epoch' | grep jdspd | tail   # 期望：无错误、无 chain 重建

### 8.6 慢参数（卷积 IR 上传）

    adb -s 64a4bad0 shell su -c "$P imp 433 4096 2"     # 4096 帧立体声 → 总 34 ms
    adb -s 64a4bad0 shell su -c "$P imp 433 16384 2"    # 16384 帧立体声 → 总 102 ms

### 8.7 设备恢复（每次测完都做）

    adb -s 64a4bad0 shell su -c 'sh /data/local/tmp/cleanup.sh'   # 停掉静音 Tone，避免 watcher 对僵尸会话 churn
    adb -s 64a4bad0 shell su -c "$B enabled on"
    adb -s 64a4bad0 shell su -c "$B baseline"                    # 恢复 26 项规范基线
    adb -s 64a4bad0 shell su -c "$B mode"                        # 期望 backend
    adb -s 64a4bad0 shell su -c "$B dump-config" > out/latency/raw/final-config.json
    diff <(jq -S .globalParams out/latency/raw/baseline-config.json) \
         <(jq -S .globalParams out/latency/raw/final-config.json) && echo IDENTICAL

---

## 9. 设备状态与恢复确认

| 项 | 测量前 | 测量后 | 确认方式 |
|---|---|---|---|
| 全局开关 | enabled=true | **enabled=true** | jdspcli enabled → on |
| 模式 | mode=backend | **mode=backend** | jdspcli mode、config.json "mode": 1 |
| 参数基线 | 26 项（raw/baseline-config.json） | **逐字节相同** | diff 输出 IDENTICAL（26 项，policies=0，perAppParams=0） |
| 播放会话 | 0 | **0**（sessions 空表；测试用静音 Tone 已停） | jdspcli sessions |
| jdspd | 常驻 pid 3733，watcherFailures=0 | **同一进程，未重启**；watcherFailures=0、watcherError 为空 | raw/final-stats.json |
| 未做的操作 | — | 未 reboot、未重装模块、未 pm clear、未 stop/start audioserver 或 system_server、未 git commit | 见 §10 |

> 收工说明：设备已回到空闲可测状态。**audio-tap2 的 audioserver 重启窗口可以申请了**——我已停止一切
> 测量与测试会话（无测试 Tone 进程占用、无挂载会话），此后不再对设备做任何操作。
> 另外提醒：本次实测中还观察到一条无害的 CLI 小问题——jdspcli mode backend 会打印
> "setMode(backend) failed: remote status 1"，原因是 CoreApi.setMode 成功时返回的是 mode 值（1）而不是 0，
> 与 CLI 的"非 0 即失败"判断不一致；模式本身设置成功（后续 mode 查询为 backend）。

### 9.1 窗口后复验（audio-tap2 的 audioserver 重启窗口 11:22-11:32 之后，11:34:02 复验）

| 项 | 结果 |
|---|---|
| enabled / mode | on / backend |
| globalParams | 与 raw/baseline-config.json **逐项相同**（26 项） |
| sessions | 空（0 挂载） |
| jdspd | **进程已重启**（pid 3733 → 3703，uptime ~6.6 min）：这是 audio-tap2 的 audioserver 重启带来的副作用；重启后 config 从磁盘恢复正确、enabled/mode/参数基线均无误 |
| 日志 | 只有 30 s 一次的 policy 行，无 error |

> 结论：设备在我收工时刻处于"可用且干净"的状态（enabled=on / mode=backend / 26 项基线与测量前一致 /
> 无测试会话残留 / 无错误日志）。jdspd 的重启来自外部 audioserver 重启测试，不是我做的操作。

---

## 10. 产出清单与证据索引

### 10.1 原始数据（out/latency/）

| 文件 | 内容 | 对应结论 |
|---|---|---|
| raw/baseline-config.json、raw/final-config.json | 测量前/后完整配置 | §9 恢复确认 |
| raw/baseline-stats.json、raw/final-stats.json | daemon 计数器（setParamCalls、watcher、引擎耗时） | §2.1 |
| raw/logcat-jdsp-filtered.txt（全量压缩件 raw/logcat-session1-full.txt.gz） | logcat -v epoch 连续抓取，已过滤到 jdspd/JdspBridge/JdspPrefWriter 等 tag（含 switch 测试全时序） | §2.2、§7 |
| csv/bench-1500-100.csv | 100 次 setParam 单次 RTT（session 417） | §2.1 第 7 跳 |
| csv/clean-statbench-1500.log | 100 次 + stats 前后（引擎写 3.22 ms） | §2.5 |
| csv/clean-burst-100hz.log | 100 Hz 定速 300 次 | §2.5 |
| csv/clean-bench-1300.log | PEQ 级联 8 band × 50 | §2.3 |
| csv/imp-4096st-2ch.log、csv/imp-16384st-2ch.log | 卷积 IR 分片上传 | §2.3 |
| csv/bypass-200cycles.log | 200 次 off/on bypass（400 次切换，0 错误） | §4.1 |
| csv/switch-defer.csv | off→on（100 ms 间隔）→ deferred 路径 | §2.2 |
| csv/switch-cycles.csv | 5 轮 off/on 周期 | §2.2 |
| csv/statbench-1500-on.log、csv/burst-100hz-on.log | **污染轮次**（僵尸会话 churn），保留以证 §1 标注 | §1 |
| csv/bench-1500-100.csv、csv/op-ping-100.csv、csv/op-sessions-50.csv、csv/op-stats-50.csv、csv/op-dumpConfig-20.csv | 各 op 的往返基准 | §2.1 |
| raw/watchparam-1500.csv、raw/watchparam-1500b.csv | getParam 0.5 ms 网格的值变化时刻 | §8.3 |
| raw/app-main.png、raw/app-now.png、raw/app-after-toggle.png、raw/ui-dump.xml | 实测用的 UI 坐标（power_toggle / seekbar 的 bounds） | §8.3、§8.4 |

### 10.2 建议补丁（out/latency/patches/，**已生成但未 apply、未 commit**）

| 补丁 | 作用 | 触及文件 | 顺序 |
|---|---|---|---|
| 0001-jdspd-bypass-first-master-switch.patch | off=bypass 立即 + 20 s 空闲卸载；EnableGate 新动作；parallel setEnabledAll | EnableGate.java、SessionWatcher.java、EffectManager.java、CoreApi.java | 1 |
| 0002-jdspd-event-driven-enable-defer.patch | deferred 到点即 apply（ScheduledExecutor）+ tick 头部 poll 兜底 | EnableSwitch.java、CoreApi.java、SessionWatcher.java | 2 |
| 0003-jdspd-config-save-outside-lock.patch | config fsync 出 ConfigStore 监视器 | ConfigStore.java | 3 |
| 0004-jdspd-setparam-fastpath-and-global-batch.patch | pkgOf() 取代 getSession()；setParamGlobal(s<=0) + setparams 批量 op | EffectManager.java、CoreApi.java、JsonProtocol.java | 4 |
| 0005-app-enable-fast-handoff.patch | App 启停意图直投（去 500 ms 闸门、清 backendDisabled、退避期立即重连） | JdspBridge.java | 5 |
| 0006-app-error-code-and-keepalive.patch | TCP 业务码透传（-1006）+ 20 s 保活 | TcpTransport.java、JdspTransport.java、JdspBridge.java | 6 |

- 应用根目录不同：0001-0004 从 **jdsp-repo** 根执行 git apply -p1；0005-0006 从 **loundess_git** 根执行
  （App 源码树）。若两者在同一棵树里应用，路径前缀按 app/src/... 对齐。
- 已验证：6 个补丁**按序** git apply --check 全部通过；打完补丁的 daemon 源码（除需要 AIDL stub 的
  BinderService/Daemon）与 App 的 interop/jdsp 包，都用 javac --release 8 -classpath android.jar
  编译通过（0 error）。
- **未做**：没有把补丁应用到工作树、没有 git commit、没有重刷模块（需要重刷/重启的验证按纪律交回 lead 安排）。

### 10.3 建议的后续验证（补丁落地后）

1. A1/A3：真机连点 20 次；speaker 与 USB DAC 各一轮，确认 enabled 状态机无残留、无 HAL 报错；
   长关 5 min 后确认空闲卸载真的发生（日志 idle unload: N bypassed instance(s) released）。
2. A2：把 MIN_INTERVAL_MS 设 50 ms，复测 deferred 端到端（期望 60-90 ms）。
3. A5：多会话（两个 app 同时出声）下测 setparams 与 setParamGlobal，确认 N 会话只付 1 个往返。
4. A6/A7：断网 / 杀 daemon / 关后端三种异常下点开关，确认 <20 ms 或明确失败提示，不再出现 30 s 退避。
5. 回归：backend/test 的 EnableGateTest / EnableSwitchTest / WritePolicyTest / ApplyAllDedupTest
   + 一次 72 h soak（attach/detach 次数应显著下降，这是 chain churn 的直接指标）。

### 10.4 本文的证据强度速查

| 结论 | 强度 |
|---|---|
| setParam RTT 分布、engine write 3.22 ms、ping 0.2 ms | 实测 |
| 滑条 App→daemon 4-11 ms、空闲首条 +65 ms | 实测 |
| off bypass 5-11 ms / 释放 26-30 ms、on 250-413 ms（60 s 空闲后 339 ms） | 实测 |
| deferred on 612 ms（applied）/ 986 ms（enabled） | 实测 |
| App 连点第 2 次 470/516/537 ms、退避期 2.5 s | 实测 |
| bypass 400 次 0 错误、p50 0.25 ms | 实测 |
| 卷积 IR 4096 帧 34 ms / 16384 帧 102 ms | 实测 |
| "500 ms 轮询 + tick 末尾 poll" 的因果链、F5/F7/F8 的代码路径 | 代码（file:line 已给） |
| 2000 ms 内关闭再打开、空闲卸载后再开的影响 | 实测（339 ms）+ 推断（bypass 路径 ~1 ms） |
| "一次 setParam 的 85% 花在 AudioEffect binder 上" | 实测（引擎耗时占比）+ 外部（AOSP 路径） |
| "有 effect 的流不在 fast mixer 上" | 外部（AOSP 延迟设计文档） |
| "createEffect 比 setEnabled 贵得多、且持播放线程锁" | 外部（AOSP Threads.cpp/Effects.cpp）+ 实测（52-112 ms vs 0.25 ms） |

---

## 11. 「一帧一批」参数写入落地与复测（App 1.6.19-65）

> 落地：loundess_git commit `5d1b861`（App 侧）。daemon 侧没有改动（用到了 f3b72ee 引入的
> `op=setparams` 与 `s=0` 全局写）。本节数字全部来自本机真机（adb `64a4bad0`）。

### 11.1 改了什么

| # | 改动 | 位置 | 说明 |
|---|---|---|---|
| 1 | **一帧一批**：可合并参数在一个窗口内合成一次 `op=setparams` | `JdspBridge`：`BATCH_WINDOW_MS=12 ms`、`BATCH_QUIET_MS=1 ms`、`BATCH_IMMEDIATE_COUNT=2`、`pushBatch()` | 窗口上限 12 ms（< 一帧 16.4 ms，不跨帧）；**静默 1 ms 就发**（单独一条写入不会硬等 12 ms）；已攒够 2 条立即发；单批上限 64 |
| 2 | 保序参数**不合并** | `ORDERED_QUEUE`（8888/9999/12000/12001/10004/10006/10009/10010/25000-25003/115/116/1300/1305-1307） | 逐条 FIFO、立即发，保持分片/提交/级联的顺序语义 |
| 3 | 全局参数**不再先查 sessions** | `JdspBridge.push()/pushBatch()`：`s=0` 直投 | 老 daemon（回 -1003）自动永久退回逐会话广播，不会丢参数 |
| 4 | `setparams` 传输实现 | `TcpTransport`、`SocketTransport` | `JdspTransport` 默认实现逐条 `setParam`，binder/shell 协议一个字节没变 |
| 5 | 唤醒与收尾 | `markBatchPending()`（unpark）、`flushNow()`；`MaterialSeekbarPreference.onStopTrackingTouch` | 入队即唤醒 worker（省掉最多 2 ms 轮询）；滑条松手强制 flush，**最后一次值一定落盘** |
| 6 | 版本 | `buildSrc/.../AndroidConfig.kt` | 1.6.18 (64) → **1.6.19 (65)**；APK `JamesDSP-v1.6.19-65-root-full-universal-release.apk`（39 784 053 B） |

语义不变量（逐条自检过）：拖动中 last-wins 合并允许；松手最后值必发；后端不可用时参数照旧
回队重放（`retainForRetry` / `handleBatchFailure` 的 -1006、结构性拒绝、重试上限行为不变）；
UI 线程只入队、不碰 socket。

### 11.2 复测（同一测法：静音 tone + 设备侧 getParam 0.5 ms 网格 + logcat epoch 对齐）

| 指标 | 改造前 1.6.18/64 | 改造后 1.6.19/65 |
|---|---|---|
| 匹配事件 | 72/72 | 100/101（另一次单会话复测 24/24） |
| **p50** | 5.0 ms | **5.0 ms**（单会话复测 4.0 ms） |
| **p95** | 11.0 ms | **10.0 ms**（单会话复测 13.0 ms，样本仅 24） |
| max | 21 ms | 14 ms |
| 每个 UI 事件的往返数 | 2（`sessions` + `setParam`，每会话再加一次） | **1**（`setparams`，s=0 由后端扇出） |

其它已验证项：
- EQ 路径（`submitPeqCascade` 一次写 1300+1214）：daemon `setParamCalls` 每次 +2（两个参数都到），
  1300 走 ORDERED 单发、1214 进合并批次；1300 的值变化落在 tap 注入窗口内（窗口 95 ms，变化在窗口内 −20 ms 处）。
- `setparams` / `s=0` 功能验证：`{"ok":true,"applied":2,"failed":0}`，getParam 读回新值；未知 op 仍 `-1003`。
- 总开关回归：App 连点 3 次 FAB，后端按 on→off→on→off 正确翻转，收尾 `enabled` 读回正确，
  `dump-config` 的 26 项参数与基线逐项相同（开关与参数互不污染）。

### 11.3 为什么 p95 没进 8 ms（如实）

1. **引擎已经不是瓶颈**：新引擎上 jdspd 自测 `avgLatencyUs` 只有 0.67-0.84 ms（上一版报告是 3.2-4.1 ms），
   但端到端 p95 仍有 ~10 ms —— 剩下的尾巴在 **daemon 的请求处理/调度**（单连接串行 + `EffectManager` 监视器 +
   watcher 每 500 ms 的 78 KB dumpsys），不在 App 侧。属评审 §6 档 B1（锁粒度），本次没有动 daemon。
2. **测量本身有负载**：`watchparam` 以 ~1000 次/s 的 `getParam` 轮询（22 s 一轮 ~16k 次调用）与写入争同一个
   daemon；同一窗口内还有另一个 teammate 在切前台应用、换引擎 .so、重启 audioserver。
3. **ORDERED 不合并**：EQ 的 1300 是保序参数，所以 EQ 场景只省掉 `sessions` 那一次往返，收益天然小于滑条场景。
4. **样本与环境**：本次窗口内设备被重启 3 次（13:31 / 13:54 / 13:55）、jdspd 多次重启，部分轮次在 2-3 个
   已挂载会话下测量（s=0 会扇出到所有会话），尾部被放大；p95 的两次结果 10.0 / 13.0 也反映了这个抖动。

结论：**一帧一批把每个 UI 事件的往返数从 2 降到 1、p50 稳定在 4-5 ms、p95 从 11 降到 10 ms、max 从 21 降到 14**；
要真正进 8 ms 需要动 daemon 的请求处理路径（档 B1），本次不在范围内。

### 11.4 未完成的验证（下次接手先做）

- **EQ 拖动**：设备在本轮被反复重启、前台被别的任务占用，1.6.19 上只做了 11.2 里的协议级与开关级回归，
  没有在参数均衡器图上真拖一个 band；建议设备空闲时用 uiautomator 取 band 坐标后重跑。
- **总开关 <60 ms 的端到端数字**沿用本报告 §2.2 与 after-report（daemon 直驱 off 12 ms / on 14 ms）；
  本次只做了「能正确翻转、参数不被污染」的回归。
- 72 h soak，以及「拖动松手后最后值落盘」的专项压力测试（本次由 `flushNow()` 保证，逻辑上必然）。
