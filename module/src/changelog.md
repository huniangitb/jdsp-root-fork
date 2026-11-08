### v2.1.12-fork（"传统模式"真正全局生效：jdspd join 框架实例；App 模式开关接后端、参数值域对齐、LiveProg 恢复）

* **auto 模式下 DSP「时有时无」的两个真因（两个 P0）**：
  * *attach 后 1 ms 被拆*：廉价探针（`getActivePlaybackConfigurations`）看不到 effect chain，`SessionInfo.jdspPresent`
    恒 false，`PolicyEngine.decide` 在 auto 模式下据此回 `attach=false`，reconcile 立刻把刚 join 上的句柄 detach
    （实测日志 `attach session=137 effectId=155` 后 1 ms `detach … (auto: no instance, not created)`），
    daemon 因此**几乎从不持有句柄**，参数只能靠偶发的交叉校验 dump 撞上。
    修法：`SessionInfo.probeOnly` + `Decision.keep`（auto 模式下廉价行既不 attach 也不 detach，只维持 owner 记账），
    attach/release 全部交给 dump 视图。
  * *detach 把框架实例关掉*：`detach()` 原来无条件 `setEnabled(false)`，对 join 来的**框架拥有**的实例等于把
    用户正在听的 DSP 静默关掉（实测残留 `enabled=false`）。修法：`Handle.joined`（`attach(sid, knownEffectId>0)` 即 join），
    `detach()` 对 joined 只 release，只有"被排除的 App / 总开关关闭"才走新的 `detachHard()` 强制 disable。
* **传统模式（全局挂载）现在真的是全局**：auto 模式下 audioserver 按 `<postprocess><stream type="music">` 在
  **track 创建时**建实例（早于出声 ⇒ 无爆音、无附加延迟），jdspd 用同一个 `(type, uuid, sessionId)` 构造器
  **加入**该实例（AOSP `EffectChain::addEffect_l` refcount++，不是第二个实例：实测 `jdsp instances=1`、该 effect
  `2 Clients` = audioserver + jdspd），之后只做纯 `setParam`（0.25 ms）。这解释并落实了"按应用追加处理有一瞬底噪/生效延迟，
  不如全局挂载稳定"的现场结论：差别不在引擎，而在**谁在什么时候建链**（backend 模式的 `createEffect_l` 要持混音线程锁
  52-112 ms，还要 crossfade）。
* **auto 模式的 dump 预算**：新增"未挂载的廉价会话最多花一次 dump"（`autoAttachCandidates`）；该 dump 里没有框架实例
  （游戏/语音流不在 postprocess 覆盖范围）就固定 park 30 s，不再每秒问一次；attach in-flight 时不算 miss。
* **控制通道：选择 vs 生效**：`mode`/`getmode`/`setmode` 回复新增 `modeEffective`（读已挂载 XML 里的
  `effect="jamesdsp"` 标记，新类 `ModeEffective`，5 s TTL，读不到就回 `""` 绝不猜）、`changed`、
  `reboot_required`/`rebootRequired`（= modeName ≠ modeEffective）。以前只回 `mode`，App 无法区分"已切换"与"已生效"。
* **App（1.6.21 / 67）**：
  * "传统模式"开关 → 后端 `setmode auto`，"增强处理" → `setmode backend`，以 `modeEffective` 渲染
    "当前生效"，`rebootRequired` 时提示重启（不自动重启）；删掉只发 `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`
    给旧 root 服务、对 jdspd 毫无影响的 `updateLegacyMode` 调用。
  * 两个开关改成**单选**（后端只有两个 mode，界面上不存在"两个都关"）：关掉"传统模式"= 选"增强处理"，
    关掉"增强处理"= 回"传统模式"；顺带修掉 root 模式下点"增强处理"会弹"没有 Root 权限"onboarding 的误导
    （DUMP 权限门只留给 rootless 采集回路），开关也不再启停 `RootAudioProcessorService`；
    两行 summary 文案改成描述真实语义（不再有"附加到会话 0"字样）。
  * 参数值域按引擎语义对齐 + 绑定路径 clamp：低音最大增益控件原来 `min 3 / default 5`，而引擎与基线允 0..15，
    后端值 1.0 直接塞 Slider 触发 `IllegalStateException: Slider value(1.0) must be greater or equal to valueFrom(3.0)`
    （用户上报的崩溃）——现在 clamp + 域改 0..15（`MaterialSeekbarPreference.clampToRange`，注意**不能**放在
    `onGetDefaultValue`，那时 mMin/mMax 还是 0）；谐波/次谐波 gain 键名错位一格且百分数未除 100（引擎要 0..1 线性）、
    `iso_q` scale 1000→100、`loudness_mode` 映射删除（1301 只有 5 个 float）、`cast()` 补 Number→String（8 个下拉读路径）。
  * LiveProg 恢复：编辑器"运行"现在**无条件**把脚本下发（`8888 → 12001×N → 10010 → 25002 → 1213`，顺序由引擎
    `jamesdsp.c:853-877` 的容量校验决定），并新增 RELOAD_LIVEPROG 接收器（预设切换/恢复默认一并修好）。
* **后端**：`setParamGlobal` 在没有任何已挂载会话且参数不可持久化时返回 `ERR_NO_SESSION(-1002)`，不再假成功。
* **单测**：287 → **297 passed** + 自检 11 PASS（新增 `AutoModeDecisionTest` 10 例：auto 廉价行 keep、dump 无实例不挂、
  dump 有实例挂、backend 廉价行挂、排除优先、session 0、marker 计数、无文件=unknown、有文件无 marker=backend、有 marker=auto）。

### v2.1.11-fork（静态审查收口：截断 dump 不再拆链、失败 dump 不再记账、dump 失败退避）

* **截断/失败的 dump 不再被当成"真的没有会话"（P1）**：`runDumpsys` 改为有界等待，并抽出纯函数
  `WatcherPolicy.dumpProblem` 判定四类残缺（超出大小上限 / 非 0 退出 / 无输出 / 缺 `Output thread` 段）；
  另外只要 jdspd 自己还持有句柄，dump 里就必须出现自身 effect UUID。任何一条不满足都**不解析**——
  以前截断的 dump 会解析成空会话列表，让 reconcile 把正在播放的效果链整条拆掉（可听见断音）。
* **失败的 dump 不再消耗确认/attach/交叉校验的记账（P1）**：`pollOnce` 返回 `boolean`，确认退避、
  attach 放弃预算、5 min 交叉校验这些"截止时间"只在 dump 真的成功时推进。以前 5 次失败就能把确认退避
  顶到 60 s、把真实原生播放器的 `firstSeen` 清掉，让本版要修的残会话问题以另一种形态复发。
* **attach dump 先过策略闸门（P1）**：新增 `attachAllowed()`（总开关 + 不 in-flight + `policy.decide().attach`）。
  被 exclude 或总开关关闭的原生会话以前永远挂不上、却持续按 ≈6-7 dump/min 烧 dumpsys，现在一次都不烧。
* **dump 失败退避（P1，审查新发现）**：失败后 3 s→6 s→12 s→24 s→48 s→60 s 重试（`WatcherPolicy.nextDumpRetryMs`），
  且失败时改用廉价 pass（探针可用时）继续挂载/执行策略。以前失败不记账 → 调度器永远"到期" →
  每轮 fork 一次 dumpsys，同时跳过维护定时与等待，等于热循环且守护进程整体停摆（开机早期最容易撞上）。
* **其它收紧**：attach 放弃计数改为 per-id（不再一个无望会话陪绑把健康原生播放器一起 park 30 s-5 min）；
  off-by-one（第 6 次才放弃）；5 min 交叉校验不再被 confirm/attach dump 饿死；`confirmArmedAt` 改 volatile；
  确认 dump 上限 4 次（**暂停中再被杀**的最坏释放回退到 ≤5 min 交叉校验，无听感影响）；
  watcher 线程外层 catch 重启；`attachSession` 异常兜底；`dumpOnly` 过滤 sessionId<=0；
  poke 推迟改为有界（`POKE_DEFER_MAX_MS`=250 ms），连续滑条不会饿死到期的 dump。
* **诊断**：`stats` 新增 `watcherDumpFails` / `watcherDumpRetryInMs`；60 s 诊断行新增 `dumpFails` / `dumpRetryIn`；
  `--selftest` 的 `ok` 现在取决于 dump 是否成功（新增 `dumpOk`），dump 全失败时不再报 ok。
* **实测（ColorOS 16 / OPD2404，v2.1.11）**：原生播放器（AAudio）attach **1110-1370 ms**；
  `SIGKILL` → `released session` **2040 ms**；暂停中的原生播放器确认 dump 4 次后静默
  （4.2 / 12.8 / 29.7 / 61.6 s 各一次）；暂停后被杀的最坏释放 200 s（≤5 min 交叉校验）；
  空闲 120 s CPU **0.183%**、期间 0 次 dumpsys；App 滑条 0.00→6.13 dB，后端 `1500=[-0.1, 60.0, 6.131]`，
  配置键数不变（26）。单测 283 → **287 passed** + 自检 11 PASS，`javac -Xlint:all` 0 告警。

### v2.1.10-fork（残会话 5 min → 1.4 s；修 v2.1.9 原生播放器从不挂载的回归）

* **残会话确认（P1，用户的"小问题"）**：播放器被强杀/崩溃时不关闭 track，audioserver 里会留下 jdspd 自己的
  effect 句柄（dump 里 `0 Tracks` + `1 effects for session N`），廉价查询看不见它，`dumpOnly` 保护又让它
  只能等 5 min 交叉校验 —— 实测残留 **5 min 40 s**，期间探针 10 Hz 空转约 0.95% CPU。新增"确认 dump"触发器：
  已经挂上句柄但"没有任何东西在播"持续 1.5 s，就跑一次 dump 确认；会话若真死了当次释放，若还活着（暂停中的
  原生播放器）按 4 s→8 s→…→60 s 退避重试，播放恢复或会话清空立即复位。**实测 SIGKILL 到 `released session`
  1400 ms**（v2.1.9：>90 s，只有 5 min 交叉校验能收）。
* **修 v2.1.9 回归：原生播放器（AAudio/OpenSL）被发现但从不挂载（P1）**：v2.1.9 让廉价 pass 接管稳态后，
  `reconcile` 的防抖记账把"本轮会话列表里没有"的会话当作消失并清掉 firstSeen 戳；原生播放器只存在于 dump，
  于是"dump 看见 → 廉价 pass 忘掉 → dump 再看见"无限循环，**永远等不到 400 ms 防抖到期**，效果链从不建立
  （实测 uid 10123 的 AAudio 播放器 50 s 内 attach 0 次）。新增 `WatcherPolicy.keepFirstSeen(present, dumpOnly)`
  —— 只有 dump 见过的会话保留戳，同时新增"attach dump"触发（1 s 限速、连续 5 次无句柄则放弃并重新观察）。
  **实测原生播放器 attach 800 ms（v2.1.9：从不）**。
* **静音附着不再 10 Hz 轮询（P2）**：循环等待节拍只看"真在播"（isStreamActive 保持窗 + 探针 playing 行），
  不再因为"持有句柄"就按 100 ms 转：暂停中的已挂载会话从 10 Hz 降到 2 Hz（每个 tick 一次 isStreamActive +
  一次配置查询，实测约 0.95% → 0.2%）。挂载本身不慢——App 与发现都直接 poke 循环。
* **诊断**：`stats` 新增 `watcherConfirmDumps` / `watcherConfirmAgoMs`（-2 = 触发器未武装），
  60 s 诊断行新增 `confirmDumps` / `confirmAgo`；pass reason 新增 `confirm`、`attach`。
* **实测（同机 v2.1.9 → v2.1.10）**：残会话释放 **>90 s → 1.4 s**；原生播放器 attach **0 次 → 800 ms**；
  Java 播放器 attach **95-103 ms**（无回归）；空闲 40 s CPU **0.225%**；单测 283 passed（v2.1.9: 280）+ 自检 11 PASS。

### v2.1.9-fork（会话发现改用 IAudioService 查询 + 空转闸门：jdspd 空闲 CPU -91%，稳态 dumpsys 归零，attach p50 -53%）

* **发现路径不再用 dumpsys（P1）**：`IAudioService.getActivePlaybackConfigurations()` 一次 binder 调用就给出每个
  已注册播放器的 `(sessionId, uid, pid)`；uid 0 的 jdspd 拿到的是**真实 sessionId**
  （`AudioPlaybackConfiguration.getSessionId()`，Android 12+；`AudioService` 只对没有 `MODIFY_AUDIO_ROUTING`
  的调用方做匿名化），包名走 `/proc/<pid>/cmdline`。dumpsys 只保留三种用途：**5 min 交叉校验**、
  **原生播放器盲区**、**查询不可用时的降级阶梯**。实测 attach 全程 dump 增量为 0。
* **空转闸门（P2）**：`AudioSystem.isStreamActive(STREAM_MUSIC, 0)`（实测 0.18-0.4 ms）做闸门；无会话且没在播时
  查询只在闸门翻转 / 每 5 s 安全查询时跑，其余 tick 只打闸门（500 ms）；播放期仍 100 ms 查询；闸门上升沿
  立即出 pass，带 3 s 保持抗抖动。
* **原生播放器盲区（P3）**：AAudio/OpenSL 这类不注册 AudioService 的播放器（dump 里 session 0/-1，**没有可挂载的**
  sessionId）会让廉价视图不完整；`blind` 判定 = 音乐流活跃但「无已注册播放器在播」或「无一行带真实 sessionId」
  → 改由 dump 驱动，走 3 s→15 s 阶梯；`dumpOnly` 保证「只有 dump 见过」的会话不被廉价 pass 误摘。
* **降级阶梯（P4）**：查询不可用 → 500 ms 密集档起步，3 个无变化 pass 升一档（3 s→15 s→30 s），任何会话集合
  变化立刻回密集档；启动期失败每 5 s 重试（保留 v2.1.8 的修复）。
* **维护改为独立时基（P5）**：配置落盘 / 延后开关 / 端点文件刷新从「顺带在 dump pass 里」改成独立 3 s 维护定时。
* **实测（同机、同脚本，3 场景 × 3 轮 × 60 s 中位数；before = v2.1.8，after = v2.1.9）**：
  - jdspd CPU：空闲 **2.38% → 0.22%**（开机后复测 0.183%）、播放 2.18% → **1.05%**、会话抖动 2.22% → **0.97%**
  - `dumpsys` / 分钟：19 → **0**（三场景全零）；空闲配置查询 570/min → **12/min**
  - 新会话 attach p50：1255 ms → **584 ms**（n=8，p95 969 ms，全程 0 次 dump；交付版复测 p50 507 ms）
  - 盲区（AAudio 流）CPU：2.4-2.7% → **1.38%**
* 单测 273 → **280 passed**、device-selection selftest 11 PASS、`git diff --check` 干净。
* 文档：`docs/DAEMON-PROBE-COST.md`（优化点逐条、分级参数表、日志样例、before/after、未做项与理由、回滚、
  AOSP 最佳实践评估、**Android 12-16 兼容矩阵**）。
### v2.1.8-fork（daemon 事件驱动 + per-session 分锁；修复开机 probe 退化：jdspd CPU -75%，attach p95 -72%）

* **EffectManager 改 per-session 锁 + 状态机（A1）**：原来的全局 `synchronized` 把"一条会话的 binder
  写"变成"全体会话串行"。现在每会话一把锁 + `ST_LIVE/ST_CLOSING` 状态机与在飞写计数：detach 会等
  在飞写完成，`CLOSING` 期间的新写立刻返回 `NO_HANDLE`（不再打到一个正在关闭的句柄上），
  `gate.forget()` 挪到 release 之后；`enabled`/`hasControl` 加 **1 s TTL 缓存**，`states()/state()`
  不再持锁调 binder。
* **SessionWatcher 改事件驱动（B4）**：500 ms 定时 dumpsys → **100 ms 廉价 binder 探测**
  （`IAudioService.getActivePlaybackConfigurations()`，实测 **64-93 µs/次**）算活跃集合签名，
  签名变化才跑一次 dumpsys 取 sessionId，另加 **3 s 兜底全量**；`config.saveIfDirty()`、
  `pollEnableSwitch()` 只在兜底 pass 的 `slowMaintenance()` 里跑。attach 去抖从"每 tick 轮询"改成
  **一次性定时唤醒**。
* **Daemon 主循环（B5）**：去掉 1 s 周期 sleep → `CountDownLatch` 等 shutdown；stop 文件（卸载用）
  改用 **inotify WatchService**（不可用时退 5 s 复查兜底）。
* **落盘彻底离开请求线程（A3）**：`applyEnabled`/`setAppPolicy`/`setMode` 里的内联
  `saveThrottled`/`saveIfDirty` 换成异步 persister，并给 persister 加**单次自解除的延时补跑**
  （`requestAfter()`）——节流窗口吞掉的那次保存最终且只写一次。
* **实测（同机、同会话对、同脚本；latency-auditor）**：
  - jdspd CPU（30 s utime+stime / 单核）**13.53% → 3.43%（-75%）**
  - watcher 全量 dumpsys **1.733/s → 0.333/s（-81%）**
  - 新播放会话 → effect attach 完成（8 次）p50 **1325 → 739 ms**、p95 **2723 → 755 ms（-72%）**
  - 并发 2 客户端 / 2 会话 p95 **45.8 / 45.6 → 35.1 / 32.1 ms**
  - 总开关 API 往返：off 5-19 → 7-10 ms、on 11-17 → **5-9 ms**；日志侧 off→bypass **4 ms**、
    on→un-bypass **5 ms**（2 实例）
  - 单参数 RTT **无回归**（引擎侧漂移主导：同时段 A/B/A 为 25.9 vs 25.3 ms）
  - 慢参数（16 KB 分片）期间另一会话 p95 168.0 → 163.5 ms —— **阻塞点在 audioserver，不在 jdspd**
* **修复"正常开机后事件驱动其实是关的"（v2.1.8，装机复验发现，P1）**：daemon 在开机时比 audio 服务
  更早起，首次探测 `ServiceManager.getService("audio")` 必然拿到 `audio service not found`；v2.1.7 把这个
  失败**永久缓存**，于是整次开机 `probe=off`，而退化路径又仍然等 **3 s 的 fallback**（日志却写着 500 ms），
  实测新会话 attach **2.5-3.1 s**，比 v2.1.6 的 1.3 s **更差**。现在：
  - 探测失败**不缓存**，每 **5 s** 重试一次，成功时打一行 `cheap playback-set probe available after N retries`；
  - probe 不可用时整轮 pass 按 **`LEGACY_POLL_MS` 500 ms** 跑（不再等 3 s），日志文案改成实话
    （`legacy 500 ms polling, retrying every 5000 ms`）；
  - 状态行增加 `probeAttempts/probeSetupFail`，方便现场判断（详见 `docs/DAEMON-EVENT-DRIVEN.md`）。
* 后端 **jdspd 2.1.8**；模块 versionCode **218**；引擎与 App 与 v2.1.6 相同（补丁 0001-0008、
  arm64 `658aff86` / arm `b7aba479`、App 1.6.20-66）。
* 测试：`./backend/test/run_tests.sh` → **273 passed**（原 258 + 新增 15：`EffectManagerConcurrencyTest`、
  `WatcherPolicyTest`（含"probe 不可用 → 500 ms 一轮"与"probe 可用时 3 s"）、`ProbeScheduleTest`（重试
  计划与 retry 计数文案）、`AsyncPersisterTest` 的 `requestAfter` 用例），设备选择 selftest **11 PASS**。
* 细节与 Phase 0 探针（哪些事件源能用/不能用）见 `docs/DAEMON-EVENT-DRIVEN.md`。

### v2.1.6-fork（GET 返回值语义修正 + 诊断参数不再落盘；App 开关状态以后端为准）

* **修复 `getParam` 的返回值语义（P1，装机复验发现）**：引擎的 `GET_PARAM` 成功时返回**写入的字节数**
  （mix 保真自检计数 30023/30024 返回 4，采集 tap 状态 32002 返回 112；传统 DSP 参数返回 0），
  而 daemon 只把 `rc == 0` 当成功、把任何 `rc > 0` 当失败，于是 `jdspcli get` 对 30000-32999 整个
  诊断区间恒回 "no data"——尽管引擎其实已经答了（日志 `W/jdspd: getParam … param=32002 rc=112`）。
  现在 **`rc >= 0` 即成功**：`rc > 0` 取前 `rc` 字节，`rc == 0` 保持旧行为（整段回复缓冲），
  `rc < 0` 才回落到配置快照 / 报错。
* **诊断参数不再写进 config.json（P1，装机复验发现）**：`30000-32999`（mix 自检 30023-30028、
  采集 tap 32001/32002）是**运行期状态**（计数器、武装标志、环形缓冲路径），不是设置项。之前一次
  `jdspcli set 30025 --int 1` 被持久化，daemon 重启后按 **27 项** replay，污染了 26 项参数基线。
  现在这个区间：写**不落盘**、`dump-config` 不出现、attach replay 不参与；已污染的 config.json 在
  **加载时自动清理并重写**（日志 `dropped N diagnostic param(s)`），引擎状态文件导入同样过滤。
  引擎仍照常收到这些写——只有持久化被跳过。
* **App 1.6.20（versionCode 66）**：重连后以**后端**开关状态为准——修掉 v2.1.5 装机复验里看到的
  "App 把本地 UI 开关状态推给刚重启的 daemon，导致 off→on→off 翻转、最后停在 off"。三条把
  "App 缓存值"当"用户意图"的路径全部收口：新会话不再推快照、AudioEffect 兼容层的 setEnabled 改 no-op、
  `pendingEnabled` 值缓冲改为只由用户点击写的 `userEnableIntent`；重连后只 `getEnabled()` 读一次真值。
  不变量：**只有用户点击能写后端开关**。细节与真机三用例（80 s 0 次翻转）见
  `docs/APP-SWITCH-STATE-AUTHORITY.md`。
* 后端 **jdspd 2.1.6**；模块 versionCode **216**；引擎与 v2.1.5 相同（补丁 0001-0008、
  arm64 `658aff86` / arm `b7aba479`）。
* 测试：`./backend/test/run_tests.sh` → **258 passed**（原 248 + 新增 10：`ParamReplyTest` 覆盖
  `rc > 0 / == 0 / < 0` 与诊断区间边界，`DiagnosticParamPersistenceTest` 覆盖不落盘 / 不 replay /
  加载时清理），设备选择 selftest **11 PASS**。

### v2.1.5-fork（引擎采集 tap + mix 保真自检；App 参数写入一帧一批）

* **引擎 0007（capture tap，诊断设施，默认关闭）**：新增 `jdsp_tap.c/h` 与 `generic_process()` 里的
  两处 hook——把 effect **输入**（lane 0）和引擎交回 AudioFlinger 的 **out buffer**（lane 1，与厂商
  AF35 dump 同一个对象）拷进预分配环形，由 writer 线程排空成 44 字节头的 WAV。接口
  **32001（SET）**：192 B `jdsp_tap_config_t`（magic "TAP1" / lanes bit0=IN bit1=OUT / frames
  1024..2^18 / limit_frames / sample_rate / flags bit0=写 WAV / path[160]）、
  **32002（GET）**：112 B 状态（captured / written / dropped / read_by_caller / overruns / files）。
  渲染线程只做边界检查 + memcpy，环形满丢最旧并计数，**绝不阻塞音频线程**；参数在写锁 gate
  **之前**处理，因此不需要 engine key、不进延迟参数队列、不写 state.bin。
  **未武装零开销**：只剩每 lane 一次 NULL 检查——零分配、零 memcpy、零日志、零文件
  （同输入同持久化状态下，新库对 0001-0006 产物逐字节复现，ref-out.raw md5 89f89ed3 一致）。
  arm64 与 armv7a **两路都已重建**；主机测试新增 `test_tap`（五种格式逐位捕获回读、overrun 策略、
  limit_frames、WAV 头、并发生产/消费），**8 组主机测试全绿**；真机 -20 dB@1 kHz 陷波实测
  **-21.74 dB**（tap 侧 IN -70.19 / OUT -91.93 dBFS，每路 3.18 MB）。
* **引擎 0008（mix 保真自检，默认关闭）**：新增 `jdsp_mixcheck.c/h`，把 0005 的 ACCUMULATE 契约
  变成**计数器**——accumulate 路径上断言 `out_after == out_before + rendered`（float32/int32/int16，
  整型按饱和；别名缓冲不检查），不一致的 buffer 数暴露为 **30023（broken）/ 30024（runs）**，
  武装 **30025**、清零 **30026**、状态 **30027**、上次坏样本数 **30028**。计数是进程级的
  （audioserver 内任何实例造成的损坏都计入）。未武装只多一次 relaxed atomic load + 一个分支；
  真机正常播放实测 **runs=99 / broken=0**，武装与否渲染出的音频逐位相同。
  这样"引擎吞了别人的混音"变成可测量，不必依赖厂商 PCM dump。
  细节见 `docs/ENGINE-TAP-AND-MIX-CHECK.md` 与 `native/README.md`。
* **App 1.6.19（versionCode 65）**：参数写入改**一帧一批**（同一帧内的多笔写合并成一次
  `setparams` 批量操作，每帧只付一个往返），全局参数用 **`s=0`** 直发（不再先查会话列表再逐会话写）。
  时延数字见 app-batch 报告（本条先记行为，数字随报告补）。
* 模块 versionCode **215**；后端 **jdspd 2.1.5**；引擎补丁集 **0001-0008**（arm64/arm 两路）。

### v2.1.4-fork（最低操作链路时延：总开关 bypass 优先 + 事件唤醒 deferred + 后端批量/全局写 + App 直投）

* **总开关改 bypass 优先、空闲兜底卸载（jdspd 2.1.4）**：`off` 不再拆链，只在活着的句柄上下一次
  `EnableGate.ACTION_BYPASS_HELD`（bypass 生效 12 ms），`on` 变成取消 bypass——on 方向
  **p50 412 ms → 14 ms**（7-25 ms 区间），正常的 off/on 周期**不再重建 effect chain**。
  `off` 满 20 s 才做一次空闲卸载（`CoreApi.IDLE_UNLOAD_MS=20 s`，日志
  `idle unload: N bypassed instance(s) released`），此后再 `on` 回到完整 attach 代价（实测 328 ms）。
* **deferred `on` 改事件唤醒**：`EnableSwitch.pendingDeadlineMs()` 到期由 ScheduledExecutor 直接
  apply（watcher tick 只作兜底，且挪到 tick 最前），端到端 **857-999 ms → 52-57 ms**；同时
  `EnableSwitch.DEFAULT_MIN_INTERVAL_MS` 300 → 50 ms——bypass 优先之后 off 不再拆链，这个间隔只
  约束挂载方向，连点被压成一次跳变（单独回退这一项 = 把常量恢复 300L）。
* **config 落盘 fsync 移出锁**：JSON 快照在 ConfigStore 监视器内构建、fsync 在独立 fileLock 下，
  保存不再挡住参数热路径（`isEnabled()`/`setParamFor()`）：参数写 p95 15.6 → 11.7 ms，
  **RTT max 94.8 → 16.1 ms**。
* **后端写路径**：`pkgOf(sessionId)` 直接读句柄、不再为每次写重建整张会话视图；新增
  `setParamGlobal`（session <= 0 = 所有已挂载会话）与 JsonProtocol 的 `setparams` 批量操作
  （每帧一个往返，向后兼容）。
* **App 1.6.18（versionCode 64，源码构建，同签名可直接升级）**：
  - 总开关意图**直投 worker**（`ENABLE_REQUEST` + `LockSupport.unpark`，循环顶消费，不限速、
    不被 `backendDisabled` 拦）：连点第二次不再等 500 ms 重放闸门（实测 470-537 ms → 点击窗口内到达）。
  - `backendDisabled` 有了真正的出口：重放闸门放行 "on"，用户开关请求清零退避并立刻重连
    （不再等 3 s / 10 s / 30 s）。
  - `TcpTransport.setParam` 返回后端业务码而不是一律 -2：**-1006（总开关关着）不再被当成传输
    故障**（不丢连接、不退避）；实测后端关着时六次被拒写后，紧接着的开关点击立即到达 daemon。
  - worker 改 park 2 ms + **20 s 一次 TCP 保活 ping**：空闲 >30 s 后第一条写不再付重连代价（+65 ms）。
* 引擎与 `libjamesdsp.so` 不变（arm64 `265544f5` / arm `6a5ad413`，补丁 0001-0006 未改）；
  后端 jdspd 2.1.4。
* 测试：`./backend/test/run_tests.sh` → **248 passed**，设备选择 selftest **11 PASS**
  （`EnableGateTest` 期望 `ACTION_BYPASS_HELD`，`EnableSwitchTest` 覆盖 `pendingDeadlineMs()`；
  App 无主机测试目标，App 侧改动在真机复验）。

### v2.1.3-fork（App 1.6.17：UI 不持有任何音效配置参数，先拉取再修改）

* **App 1.6.17（versionCode 63，源码构建，同签名可直接升级）**——按"UI 不持有参数、只从后端拉取，
  取到之后才允许用户修改"重做写侧：
  - **停用"App 偏好 → 引擎"全量下发链**（`JamesDspRemoteEngine.syncWithPreferences` 改为 no-op）：
    服务启动与每次 `ACTION_PREFERENCES_UPDATED` 不再把 ~30 个参数（含 UI 侧默认值）整批推给后端。
  - **建屏/首次绑定窗口（2.5 s）内的偏好写入只落本地、不外发**：androidx 的
    `ListPreference`/`EditTextPreference` 在首次绑定会把刚读到的值再 persist 一次，
    以前这就是"打开 DSP 屏就把 App 默认值写进后端"的通道（ColorOS 实测 1307/115/116/188）。
  - `JdspPrefWriter` 写侧守卫：后端配置里没有的参数不写；没有快照时只放行"规范基线 + 内容类"
    白名单；与后端当前值相同的写入直接丢弃。
  - `MaterialSeekbarPreference` 只在**用户真实拖动/输入**时落盘，程序化回填（绑定、后端刷新）只改显示。
  - 读侧改 stale-while-revalidate：写完参数不再把快照置空，UI 不会在"刚改一笔"后回落本地值。
  - 修正 `1301`（loudness）参数长度 6→5 个 float（20 字节，与 fork 引擎一致；旧长度被引擎按 size 拒绝）。
* 模块/引擎/jdspd 与 v2.1.2 完全相同（引擎 `265544f5`、jdspd `d584ba4c`(2.1.3)）。

### v2.1.2-fork（ColorOS 16 会话泄漏修复；后端 jdspd 2.1.3）

* **后端 jdspd 2.1.3（P0，新设备实测发现）**：ColorOS 16 会给已 attach 的 effect 保留 session
  引用，于是一条曲目结束后，我们的效果链比它的 track 行活得更久。`DumpsysParser` 过去会用
  **自己的效果链**反推会话（`finishEffect` 里调 `session()`），watcher 因此永远认为该死会话"仍在"，
  `released session=` 永不触发——每播完一首歌泄漏一个 effect 实例（实测：tone 结束 50s+ 后
  flinger 里只剩 daemon 自己的 session ref + 效果链，`jdspcli sessions` 仍显示 attached）。
  现在效果链只做**富化**（`sessions.get`，不创建），会话存活一律以输出线程的 track 表为准；
  重复播放不再累积实例。
* 其余组件与 v2.1.1 / v2.1.0 相同（引擎 0003-0006 未改、App 1.6.16-62 未改）。

### v2.1.1-fork（ColorOS 16 会话发现修复；后端 jdspd 2.1.2）

* **后端 jdspd 2.1.2（P0，新设备实测发现）**：`DumpsysParser` 的 track 行正则不认识 ColorOS 16
  的 `Client(pid/uid)` 合列格式（`57 yes 15080/   1000 105 32 A 0x000 …`，AOSP 是 pid/uid 两列）。
  后果：backend 模式下 watcher 恒解析出 0 个会话——音频确实在播、`jdspcli sessions` 却始终为空，
  效果永不 attach，整机静默。现在行正则接受可选的 `/uid` 复合列，AOSP 与 ColorOS 两种表头都能解析；
  `DumpsysParserTest` 增加真实 dump 摘录回归样例（OnePlus OPD2404 / Android 16）。
* 其余组件与 v2.1.0 相同：引擎 0003-0006 未改（arm64 `265544f5` / arm `6a5ad413`），
  内置 App 仍为 1.6.16（versionCode 62，`b7ff6461`）。

### v2.1.0-fork（引擎 0003-0006 + 后端 2.1.0 + 源码改造版 App）

* **引擎 0003（高频调参噪声根修）**：SET_PARAM 不再从命令线程原地改写渲染中的引擎（系数撕裂读），
  改为每实例参数队列（同参数最新值合并），音频回调在块边界统一应用；直接写参后同步全部实例代次，
  消除"其余实例整引擎重建+交叉淡化切换"风暴。外部热重载语义不变。
* **引擎 0004**：补 EFFECT_CMD_DUMP 分支（消除 watcher 每秒一条的 DUMP 错误日志）；禁用态 process 返回 0；
  PEQ/loudness 段改堆缓冲（>4096 帧不再静默跳过）；state.bin 异步落盘（250ms 去抖，参数记录路径纯内存）。
* **引擎 0005（同输出第二会话静音根修）**：遵守 AOSP EFFECT_BUFFER_ACCESS_ACCUMULATE 契约——
  链输出缓冲映射线程共享混音时先渲染进私有缓冲再饱和累加，不再替换写抹掉其他会话
  （原"第一路播空音频、第二路整路无声"即此）。别名缓冲/OOM/packed-24 保持原语义。
* **引擎 0006（开关/生效延迟优化）**：EffectCreate 发布 pristine 直通实例，state 重放移到 builder 线程
  异步完成后交叉淡化换入，attach 引擎路径从同步数百 ms 降到 ~90ms。
* **后端 jdspd 2.1.0**：watcher 500ms；自适应防抖（常态 400ms，短命会话风暴才升 2s，已知会话跳过）；
  attach/detach 并行（4 线程池）；开关 on 方向 300ms。实测：off 全卸载 26ms、on 全挂载 256ms、
  新会话 ~1s 生效；切换毛刺有界、零静音。
* **内置 App 换为源码改造版 v1.6.16（versionCode 62）**：App 不再持有任何 AudioEffect，
  配置读/写全走 jdspd 后端（TCP），参数保存由后端权威管理；与旧签名相同可直接升级
  （versionCode ≥ 旧 59/61）。
* 已知事项：aidl / arm64-sve / x86 三路仍为上游预编译引擎（无 0001-0006）；若设备装有
  其它提供 /vendor/etc/audio_effects.xml 的模块（如第三方调音包），需移除其 jamesdsp apply 行
  backend 模式才能生效（service.sh 的 boot.log 会给出 WARN 与来源）。

### v6.7.2-fork

* 安装器新增 AIDL 版本校验：只有当设备同时具备 `android.hardware.audio.effect.IFactory`
  服务 **且** 存在 effect-V3 / common.types-V5 / common-V2 / common.fmq-V1 这四个 NDK 库时
  才安装 AIDL 变体；否则回退 legacy 并提示重新构建。
* 避免在 Android 13/14 的 AIDL 设备（effect V2）上装到无法加载的 .so 导致完全无声。

### v6.6.5-fork（内置 APK 换成汉化版：按设备配置默认开启 + 界面汉化）

* 内置 APK 更新为 `JamesDSP-root-full-v1.6.15-59-zh.apk`（versionCode 59 / 1.6.15-zh）：
  - 「按设备配置」（Device profiles）默认开启：`default_device_profiles_enable` false → true，
    连接过的耳机/蓝牙设备会自动出现在主界面「设备」区，可绑定预设。
  - 简体中文补全 389 条缺失字符串（PEQ、响度补偿、卷积器按采样率分配、谐波/次谐波扩展器、
    NOS R2R、Smooth Volume、Squig Live、MEOW 测量向导、目标曲线、Auto-EQ、Direct Mode、
    Low-latency 模式、缓冲区建议、Android 15 录屏提示等），中文条目从 567 → 956，与英文条目零缺口。
  - 该 APK 与旧版签名不同，安装前需卸载旧版；音效参数保存在模块的状态文件里，卸载 App 不会丢设置。

### v6.6.4-fork（重加载不再占用渲染线程：后台构建 + 原子切换 + 单 buffer 交叉淡化）

* 彻底消除重加载卡顿：**渲染回调不再应用任何参数**。
  - 新增 builder 线程（每 20 ms 轮询一次 store 代次，带 20 ms 突发去抖，App 启动时几十条参数只重建一次）。
  - 代次变化时，builder 在**自己线程上**新建一个完整引擎（`EffectDSPMainConstructor` + 复制采样率/格式 + 重放全部记录），
    全部应用完成后才通过原子指针发布到实例的 `pending`。
  - 渲染线程在**回调开头**用一次 `__atomic_exchange` 取走 `pending`，指针切换即为生效点：
    要么整份新参数，要么整份旧参数，永远不会看到半应用状态。
  - 被替换的旧引擎通过一把只做两次指针写的小锁交给 builder 销毁（销毁本身在 builder 线程，绝不在音频线程）。
* 切换处做一个**单 buffer 线性交叉淡化**：旧引擎把这一 buffer 渲染到预分配的 scratch，新引擎渲染到输出，
  两者按 0→1 交叉，掩盖新引擎滤波器/卷积器历史清零的瞬态。scratch 在 `EffectCreate` 一次分配（8192 帧立体声 int32），
  帧数超限或格式不支持（packed 24-bit）时退化为直接切换。
* 顺带修复 stamp 竞态：watcher 与轮询 API 的 `stat()` 现在都在 `g_lock` 内完成。之前可能在 `state_save()`
  的 rename 与 stamp 刷新之间取到中间态，把自己的写盘误判成外部变更（会触发一次多余的重建）。
  该问题由主机单测 `test_watch` 偶发失败暴露，修复后连跑 3 轮全绿。
* 采样率变化（SET_CONFIG）不再就地重放：builder 通过 `built_fs != cur->mSamplingRate` 感知并重建，
  期间旧引擎继续用旧系数工作，不会出现 EQ 断档。
* 状态文件格式不变；导出符号仍 1023。

### v6.6.3-fork（修复：重发 ENABLE/DISABLE 会清空已保存的参数）

* 修复状态去重逻辑的数据丢失 bug：`state_dedupe()` 命中重复记录时原本调用
  `state_evict_prefix(i+1)`，会把**重复记录之前的所有记录一起删掉**。框架在每次
  effect 创建/释放时都会重发 ENABLE/DISABLE，而它们的记录恒定相同，于是一旦命中，
  用户已保存的全部参数（PEQ/limiter 等）会被连带清除——真机表现为状态文件从 N 条
  掉到只剩 ENABLE+DISABLE 两条（36 字节）。
* 改为新增的 `state_evict_at(idx)`：只删除命中的那一条重复记录，其余记录与其顺序
  全部保留；新记录仍追加在末尾，因此重放顺序依旧“最后写入的生效”。
* 主机单测新增 `test_dedupe_keeps_unrelated_records`（重发 ENABLE 后计数不变、
  两条参数仍在重放序列中、重发非末尾参数同样不丢记录）；该用例在修复前的代码上
  会失败 4 项，可复现原 bug。
* 状态文件格式不变。

### v6.6.2-fork（inotify 热加载：毫秒级实时生效，文件 IO 离开音频线程）

* 引擎内新增状态文件监听线程：inotify 监听状态文件所在目录（`IN_CLOSE_WRITE|IN_MOVED_TO|IN_CREATE`），
  用单个 `poll()` 循环等待事件（只监听一个 fd，epoll 在此场景没有优势，故不用）。
  文件被替换时由该线程读文件并解析，**渲染线程不再做任何文件 IO**。
* 渲染线程每处理一个 buffer 只做一次 relaxed 原子读（`jdsp_state_generation()`）比较代次，
  代次变化即重放，因此从写盘到生效由“最坏 ~1.3 s”降到“下一个回调”（~40 ms）。
* 兜底不变：inotify 不可用 / 监听被拒 / 线程创建失败 / 目录尚不存在（重试 20 次后放弃）时，
  自动回到 v6.6.1 的 stat() 轮询路径，功能完全一致，只是延迟回到 ~1.3 s。
  可用 `JDSP_STATE_NO_INOTIFY=1` 强制走轮询（测试用）。
* 防自激：自己 `state_save()` 产生的 inotify 事件用 size+mtime+inode 的 stamp 过滤，不会触发重载。
* 日志区分来源：`state reloaded: N record(s) [inotify]` / `[poll]` / `(in-process update)`。
* 状态文件格式不变，旧 state.bin 直接沿用；导出符号仍为 1023（新增函数全部 hidden）。
* 主机单测新增 `test/test_watch.c`：watcher 生效、外部 rename 覆盖可被感知、自身写盘不自激、
  `JDSP_STATE_NO_INOTIFY=1` 时轮询路径仍可用。

### v6.6.1-fork（状态热加载：播放中实时生效）

* native 引擎新增热加载：渲染回调里每 ~32 个 buffer 做一次 stat()，用 size+mtime+inode 组成的
  stamp 与上次 load/save 比较；文件被替换时当场重新解析并重放，**播放不中断、不用重启音频**。
* 状态日志降噪：只有真正写盘时打一行 INFO \`state file updated: N record(s)\`；热加载成功打一行
  INFO \`state reloaded: N record(s)\`；回放打一行 INFO \`replayed N persisted command(s)\`。
  默认构建不再带 -DDEBUG，\`EffectDSPMain:\` 的创建/回放调试日志全部消失。
* 状态文件格式不变（magic/version/CRC32 与 v6.6.0 完全兼容），旧 state.bin 直接沿用。

### v6.6.0-fork（元模块版 + fork 引擎 + 状态持久化）

* 内置 fork PEQ+loudness 引擎（来自 evoeram/Loundess-PEQ-RootlessJamesDSP）：
  替换 common/files/arm64 与 common/files/arm 两个 ABI；aidl / arm64-sve / x86 仍为上游库。
* 接入 native 状态自持久化：新增 jdsp_state.sh（准备 /data/vendor/audio 的目录/属主/0660/标签/日志，
  幂等、只碰这一个路径），post-fs-data.sh 与 service.sh 各调用一次。
* sepolicy.rule 增加 hal_audio_default 对 vendor_audio_data_file 的路径级规则。
* 元模块（hybrid_mount）负责分区挂载与 SELinux 上下文：模块脚本不再自行 mount、
  不建 vendor 软链、不批量 chcon 分区文件。

### v6.5 RC3-test4 + pp1（postprocess 实验版）

* 安装时向 audio_effects.xml 的 <postprocess> 注入 `<apply effect="jamesdsp"/>`（music 流）。
  由 audioserver 的 AudioPolicyEffects 在每次 startOutput() 时自动创建并启用效果，
  不再依赖 App 主动 AudioEffect()，也不会落错输出线程。
* 新增 common/jdsp_postprocess.sh：注释感知、幂等；无可用 <postprocess> 时自动新建；
  已有未注释 <stream type="music"> 时把 <apply> 插进内部以保留原有效果。
* 新增 jdsp_check.sh 自检脚本；service.sh 开机自检增加 apply 行数与 flinger 实例数统计。

### v6.5 RC- 12.29.2025

* Created post-fs-data.sh with apex mounting support if needed
* Service.sh will now seek inside AIDL lib dependency, and modify it to match files in system (not need to add additional dependency files which might break functions of phone)
* JDSP will now automatically launch itself, minimize, grant notification permissions, launch notif. service

### v6.4 - 04.28.2025

* Updated AIDL lib
* Updated PBone APK
* Updated patching script
* AML compatibility for non-pixel AIDL devices

### v6.3 - 03.18.2025

* Force mount if AML is not flashed
* Better compatibility with AML module

### v6.2 - 03.19.2025

* Updated AIDL lib
* Fix odm partition mount for KSU and KSU Next

### v6.1 fix + AIDL - 11.26.2024

* Added AIDL lib
* Changed installer to include devices with AIDL

### v6.1 fix - 10.12.2024

* mmtex fix for magisk 28

### v6.1 - 4.24.2024

* mmtex v3.7 update

### v6.0 - 2.16.2024

* mmtex v3.6 update
* Update pbone apk
* Update jdsp app/libs

### v5.8 - 9.11.2023

* Update pbone apk
* Update jdsp app/libs
* mmtex v3.5 update

### v5.7 - 9.3.2023

* Automatic 32/64 bit lib detection
* mmtex v3.4 update

### v5.6 - 8.24.2023

* Lib bug fix

### v5.5 - 8.21.2023

* Update official apk:

  * Introduce fully functional continuous wavelet transform to dynamic range compander as a experimental trial
  * Use custom implementation of libsamplerate, the file no longer bloat with coefficients

### v5.4 - 8.13.2023

* Update to mmtex v3.3
* Update the thepbone jdsp app

### v5.3 - 8.11.2023

* Update mmtex to v3.2

### v5.2 - 7.28.2023

* Bug fix with osp removal
* Add thepbone APK option

### v5.1 - 7.25.2023

* Bug fix for odm on KSU and magisk delta
* Updated to mmtex v3.1

  * Added support for odm in regular magisk (should fix oneplus device issues) - thanks to @ShadoV90 (https://t.me/ShadoV90) for the fix

### v5.0 - 7.15.2023

* JDSP app updated!:

  * LiveProg update: Clean up EEL RAM disk before new script being loaded
  * EEL engine update:

    * Unify string, thread pool, convolver in one virtual data type
    * Introduce efficient running min/max, median filter
    * Allow STFT window function to be specified
    * Correct STFT phase value(Yield no difference for any previous scripts that depends on relative phase / magnitude only spectral processing)

### v4.8 - 7.10.2023

* Update mmtex to v3.0 - adds support for ksu and magisk delta

### v4.7 - 5.22.2023

* Update mmtex v2.1 - fix for magisk v26+

### v4.6 - 8.4.2022

* Update jdsp libs

### v4.5 - 6.25.2022

* Update jdsp libs

### v4.4 - 1.31.2022

* Update to MMT-Ex v2.0 - support for magisk 24 module update method

### v4.3 - 11.21.2021

* MMT-EX v1.8 update - fix for magisk canary, minmagisk now 20.4

### v4.2 - 9.17.2021

* Always install as data app, no need for system app in older android versions

### v4.1 - 9.9.2021

* Only uninstall app if older than current
* Update mmtex to v1.7
* Fix app update process - uninstall app during zip flash, reboot to complete process

### v4.0 - 9.8.2021

* JDSP app updated!:

  * New app/drivers made open sourced
  * Frequency domain dynamic range compression, give you much crazier compression
  * New dynamic bass boost, proper implementation of signal power detection, proper sample delay apply to multirate analysis and deploy state space filter to achieve boosting filter parameters.
  * Greatly reduce power consumption for some cases, especially when Convolver and Equalizer is used
  * Add user manual to main app

* No more new/old options
* Removed old options

### v3.4 - 5.14.2021

* Update volkey addon

### v3.3 - 11.2.2020

* Update to MMT-Ex v1.6
* Update volkey addon

### v3.2 - 9.9.2020

* Added hidden\_api\_policy workaround for android 11

### v3.1 - 8.25.2020

* New JDSP app updated!:

  * Equalizer interface and actual frequency response mismatch
  * libsndfile replaced with much smaller dr\_libs for impulse response file loading
  * Uses Android 10 API as compiling
  * Additional features:

    * Shrinking impulse response according users thresholds
    * Uses minimum phase transform on impulse response if user needed
    * Customizable channel dependent sample delay at the impulse response beginning

### v3.0 - 4.9.2020

* Fix boot script
* Update to MMT-EX v1.5
* New JDSP app update!:

  * Reduce parameter commit
  * Junked Convolver benchmark system, improve device responsiveness when the device is booting
  * New Dynamic range compressor, full automatic internal parameter adjustment(BUT haven't fully adapt to level depend system(I mean audio framework)
  * New bass boost system, the system automatically detect "Interesting frequency", and adjust filter gain, bandwidth accordingly
  * New FIR Equalizer, enhance interpolator algorithms, more interpolation mode and faster filter generation
  * New convolution algorithm, much faster, much more power saving, remove impulse response cutting feature, however, algorithm not yet fully completed, there will still update that boost performance further.
  * New stereo widening algorithm
  * New BS2B Crossfeed system, with traditional BS2B and a few HRTF based crossfeed(Include the one provided by @Joe0Bloggs )
  * Remove computationally heavy load 7.1 surround simulation, if you still want one, go download Hiby music player!
  * More stable live programming virtual machine, more built-in features that allow user to build very complex audio effect.
  * Processing performance improvement for all effects
  * Arbitrary phase response
  * DDC, Reverb, Arbitrary magnitude response algorithm remain the same
  * Remove all GPL code, allow full close source implementation😂😂😂However live programming compiler/Virtual machine will still go open source, last but not least, all rewritten C API will still go open source for our reference implementation of processing system.

### v2.7 - 1.22.2020

* Update to MMT-Ex v1.3 (Fixes issues on Q)

### v2.6 - 1.17.2020

* Update to MMT-Ex v1.2

### v2.5 - 1.4.2020

* Switched from Unity to MMT-Ex

### v2.4 - 12.30.2019

* Unity v5.0 update

### v2.3 - 8.11.2019

* Unity v4.4 update

### v2.2 - 5.16.2019

* Update to Unity v4.2

### v2.1 - 5.3.2019

* Update to Unity v4.1

### v2.0 - 3.28.2019

* Don't prompt for lib workaround if device that needs it
* Update to unity v4.0

### v1.9.8 - 2.21.2019

* Added Samsung Galaxy S9 and Zuk Z2 Pro to lib workaround
* Made lib workaround a choice so I don't have to keep updating this
* Unity v3.3 update

### v1.9.7 - 1.16.2019

* Forgot to move apk back to priv-app (needed for unity 3.2)

### v1.9.6 - 1.15.2019

* Unity v3.2 update

### v1.9.5 - 1.11.2019

* Unity hotfix

### v1.9.4 - 1.10.2019

* Unity v3.1 update

### v1.9.3 - 1.5.2019

* Unity v3.0 update

### v1.9.2 - 12.23.2018

* Fixed hua/nhua zipname trigger for real this time
* Unity v2.2 update

### v1.9.1 - 12.20.2018

* Fixed hua/nhua zipname trigger
* Fix lib workaround
* Update to Unity v2.1

### v1.9 - 12.18.2018

* Unity v2.0 update
* Fixed limitation in zipname triggers - you can use spaces in the zipname now and trigger is case insensitive
* Improved boot script for user app install - should fix bootloop issues

### v1.8 - 11.21.2018

* Updated JDSP to 11.20.18 release (Change FFT to slower but a commercial friendly implementation, Improve TruCentre separation, Reduce TruCentre latency to 21 ms(from 32 ms), Fix arbitrary response equalizer memory leaks)

### v1.7.5 - 11.8.2018

* Add libstdc++ workaround for pixel 2's, 3's, and essential phone

### v1.7.4 - 10.29.2018

* Switch to manual huawei selection - too much variation with custom roms for consistent huawei device detection

### v1.7.3 - 10.24.2018

* Unity v1.7.2 update
* Bug fix for arm64 devices
* Bug fixes for huawei devices

### v1.7.2 - 10.23.2018

* Fixed awesome boot script for pie - magisk apk stuff is automated once again :)

### v1.7.1 - 10.22.2018

* Fix boot hanging on Pie roms

### v1.7 - 9.20.2018

* Update to unity 1.7.1
* Widen detection for huawei devices

### v1.6.9.1 - 9.16.2018

* Hotfix for Huawei devices

### v1.6.9 -9.13.2018

* Updated JDSP to 9.13 release (adds huawei support, adds auto DDC resampler for device running on 96k sample rate or above, fixes some device volume issue caused by bit depth)

### v1.6.8 - 9.3.2018

* Updated JDSP to 8.31 release

### v1.6.7 - 9.2.2018

* Unity v1.7 update
* Added auto-install/uninstall of apk for magisk only (works for disable/enabling mod in magisk manager as well)
* Add compression to save space/bandwidth

### v1.6.6 - 8.30.2018

* Unity v1.6.1 update

### v1.6.5 - 8.26.2018

* New JamesDSP build (Now supports HTC devices)
* Fixed bit depth problem from HTC and Android Pie
* Upgraded Fast Fourier Transform libraryand  Android NDK

### v1.6.4 - 8.24.2018

* Unity v1.6 update

### v1.6.3 - 7.17.2018

* Unity v1.5.5 update

### v1.6.2 - 5.7.2018

* Unity v1.5.4 update

### v1.6.1 - 4.26.2018

* Unity v1.5.3 update

### v1.6 - 4.16.2018

* Unity v1.5.2 update
* Add AML detection/notification
* Add auto install for apk if in bootmode

### v1.5.9 - 4.12.2018

* Reworking/fixing of audio file patching

### v1.5.8 - 4.12.2018

* Unity v1.5.1 update

### v1.5.7 - 4.12.2018

* Unity v1.5 update

### v1.5.6 - 4.9.2018

* Use dynamic effect removal

### v1.5.5 - 3.30.2018

* Fix effect removals

### v1.5.4 - 3.29.2018

* Unity v1.4.1 update

### v1.5.3 - 3.18.2018

* Unity v1.4 update

### v1.5.2 - 3.1.2018

* Real fix for vol key logic

### v1.5.1 - 2.26.2018

* Quick fix for vol key logic

### v1.5 - 2.25.2018

* Fixed vendor files in bootmode for devices with separate vendor partitions
* Bring back old keycheck method or devices that don't like the newer chainfire method
* Fix seg faults on system installs

### v1.4.2 - 2.17.2018

* Boot script not working, have user do it manually for oreo

### v1.4.1 - 2.16.2018

* Updated jdsp to new version

### v1.4 - 2.16.2018

* Add file backup on system installs
* Fine tune unity prop logic
* Update util\_functions with magisk 15.4 stuff
* Fix music\_helper/sa3d removal in xml files

### v1.3 - 2.12.2018

* Fix vendor cfg creation for devices that don't have it
* Fix sepolicy patching

### v1.2 - 2.10.2018

* Added sa3d removal for samsung devices

### v1.1 - 2.6.2018

* Fixes for xml cfg files

### v1.0 - 2.5.2018

* Initial rerelease

## Credits

* [James34602](https://forum.xda-developers.com/android/apps-games/app-reformed-dsp-manager-t3607970)

## Source Code

* Module [GitHub](https://github.com/therealahrion/JamesDSPManager)

App [GitHub](https://github.com/james34602/JamesDSPManager)

