# ACCEPTANCE — ColorOS 16（OnePlus OPD2404 / Android 16）dist v2.1.2

- 日期：2026-10-26
- 设备：OPD2404（一加平板 Pro / OnePlus Pad Pro），Android 16 / SDK 36 / 内核 6.1.118，
  平台 `pineapple`（高通 SM8650），ABI 仅 `arm64-v8a`（abilist32 为空），KernelSU（`u:r:ksu:s0`）
- 交付物：`dist/JDSP-fork-v2.1.2.zip`（sha256 `1419b9ee28c1d84186a9adaf2e2771a1be71ee79e10bacf2d780369348b7f841`）
  - 模块 `v2.1.2-fork`（versionCode 212）/ 后端 `jdspd 2.1.3` / 引擎 `265544f5`（arm64，含 0001-0006）/ App `1.6.16-62`（`b7ff6461`）
- 计划来源：`docs/PLAN-next-device.md`（"下一台设备验证"执行手册）
- 结论：**模块 + 后端在 ColorOS 16 上通过 §3 清单 1-12 项；新发现并修复两处 ColorOS 专属 P0
  （会话发现格式、会话泄漏）。App 侧发现一处 P1（见 §6.3），不影响模块交付，但"App 仅 UI"的
  宣称在"有播放会话 + App 前台"时会破功，必须在下个 App 版本修掉。**

---

## 0. 一句话结论表

| # | 项 | 结果 |
|---|---|---|
| 1 | 模式生效（backend，挂载 XML apply=0） | ✅ |
| 2 | 守护健康（jdspd 2.1.3 / watcherFailures=0 / TCP listening） | ✅ |
| 3 | 信号路径（自动 attach、params=26、effectId≥0、停止后释放） | ✅（修复 A 后） |
| 4 | 参数可听生效（postGain 阶跃 + 机内麦 RMS） | ⚠️ 通过（受设备输出 DRC 压缩，见 §6.2） |
| 5 | 电源卸载语义（off→零实例 / on→重挂 26 参数） | ✅ off 16 ms / on 137 ms（日志口径） |
| 6 | 黑名单（真实 App exclude→detach，follow→重挂） | ✅ |
| 7 | 多会话不静音（双会话探针 + 双频 FFT） | ✅ |
| 8 | 低延迟组合（low/normal 双路） | ✅ |
| 9 | 突发提交（100 次/秒 × 20 s） | ✅ 设备侧 0 失败、p50 3.5 ms |
| 10 | 基线命令（污染→baseline→逐字节恢复并广播） | ✅ |
| 11 | App 首启零污染（无播放时） | ✅（但有播放会话时 App 会写参，见 §6.3） |
| 12 | 开关毛刺（10 ms 粒度 mic 静音检测） | ✅ 无 >200 ms 静音 |
| 13 | 听感终审（用户） | ⏳ 待用户 |
| — | 72 h soak | 🚀 2026-10-26 06:28:04 起跑（至 10-29 06:28） |

> 计划原定的"第三台设备（P6）"本会话**没有硬件接入**（`adb devices` 只有 64a4bad0 一台，
> USB 上也只有这一台）。因此本会话执行的是计划 §6.1 的可行半程：把 ColorOS 机从旧栈升到
> dist 全量并复跑 §3 清单——这同时也是"dist 包可交付"的最强证据（新机型/新 ROM 家族）。

---

## 1. 安装与切换（计划 §1-§2）

| 步骤 | 命令 | 结果 |
|---|---|---|
| 预检 root | `ksud` / `id` | `u:r:ksu:s0`，`/data/adb/ksud` 可用，`/data/local/tmp` 可写 |
| 第三方音频模块冲突扫描 | `grep -rl 'apply effect="jamesdsp"' /data/adb/modules/*/system` | 仅 `ainur_jamesdsp` 自身的 4 个 XML 副本命中，**无第三方模块抢占**（与 xaga 的坑不同） |
| 推包校验 | host/设备 md5 | `189a0385…`（v2.1.0）/ `3fd74a93…`（v2.1.1）/ v2.1.2 sha256 `1419b9ee…` 一致 |
| 安装 | `ksud module install /data/local/tmp/JDSP-fork-v2.1.2.zip` | 成功；`modules_update` 内 `jdspd.dex=d584ba4c…`、`lib64=265544f5…`、`module.prop=212` |
| 切 backend | `sh …/jdsp_mode.sh set backend` | `mode=backend`，4 个 XML 副本 `apply: 1 -> 0` |
| 重启 | `adb reboot` | boot 22 s 完成；`boot.log`：`[mode] service mode=backend … =0 … OK`（无 WARN mismatch） |

挂载后系统 XML 计数（`/odm`、`/system`、`/vendor/etc/audio/sku_cliffs`、`sku_pineapple`）全部 `apply=0`，
证明 backend 模式下没有框架 postprocess 抢先挂载。

---

## 2. 本会话修复的两处 ColorOS 专属 P0

这两处都是"只有换设备才暴露"的缺陷——xaga（小米 / Android 15）与 ColorOS 16 的 AudioFlinger
dump 格式/生命周期语义不同。

### 2.1 修复 A：`Client(pid/uid)` 合列导致会话发现恒为空（jdspd 2.1.2）

- **症状**：backend 模式下音频明明在播（`Global session refs` 有 session、输出线程有 track），
  `jdspcli sessions` 恒为 `[]`，watcher 永不 attach，效果不生效（整机无声效）。
- **根因**：`DumpsysParser.TRACK_ROW` 只认 AOSP 的三列 `Client Session PortId`；ColorOS 16 把
  `Client(pid/uid)` 合成一列：`57 yes 15080/   1000 105 32 A 0x000 …`，正则不匹配 → 解析 0 会话。
- **修复**：行正则增加可选 `/uid` 复合列：`(\\d+)(?:/\\s*\\d+)?`，AOSP 与 ColorOS 两种表头都能解析。
- **单测**：新增 `dumpsys: ColorOS 16 fused Client(pid/uid) track rows are parsed`（真实 dump 摘录）。
- **现场验证**：修复后 `sessions` 立刻返回 `[{"session":81,…"params":26,"effectId":43}]}`，
  daemon 日志 `attach session=81 effectId=43 … in 39777us` + `applyAll 26 parameters`。

### 2.2 修复 B：自己的效果链让会话"永生"→ 每首歌泄漏一个实例（jdspd 2.1.3）

- **症状**：曲目结束后 `sessions` 仍显示 `attached=true`，flinger 里只剩 daemon 自己的 session ref
  和效果链；实测 tone 结束 50 s+ 仍持有实例。长时间使用会按"播放过的曲目数"累积实例。
- **根因**：`DumpsysParser.finishEffect()` 用**自己的效果链**反推并 `new` 会话；而 ColorOS 16 会给
  已 attach 的 effect 保留 session 引用 → 效果链比 track 行活得久 → watcher 认为会话"仍在"，
  `released session=` 永不触发（自持环）。
- **修复**：效果链只做**富化**（`sessions.get()`，取不到就返回），会话存活一律以输出线程 track 表为准。
- **单测**：新增 `dumpsys: our own JamesDSP chain alone never creates a session`；全套 247 项 PASS。
- **现场验证**：tone 结束后 3 s 内 `released session=89 (no longer present)`，`sessions=[]`，
  `grep -c f27317f4`（flinger 实例）= 0；连续 3 轮 tone 不再累积。

### 2.3 交付物变化（v2.1.0 → v2.1.2）

相对 v2.1.0 只有 3 个文件变化：`backend/jdspd.dex`（两处修复）、`module.prop`、`changelog.md`；
`backend/jdspcli.dex`、引擎 `common/files/*/libjamesdsp.so`、内置 APK **逐字节不变**。
v2.1.1 是中间态（只含修复 A），已被 v2.1.2 取代。

---

## 3. 验收清单逐项（计划 §3）

### 3.1 项 1 模式生效 ✅
`grep -c 'apply effect="jamesdsp"' /vendor/etc/audio_effects.xml` → 0；`/system`、`/odm`、
两个 sku 变体全部 0；`boot.log` 无 `WARN mismatch`、无 `provided by another module`。

### 3.2 项 2 守护健康 ✅
```json
{"version":"jdspd 2.1.3","pid":20845,"watcherFailures":0,"modeName":"backend","enabled":true,
 "globalParams":26,"effectReflection":"ok",
 "transport":{"binder":true,"tcp":{"port":37213,"listening":true}}}
```
（TCP 端口每次 boot 随机；端点文件 `/data/data/james.dsp/files/jdsp.{port,token}` 由 daemon 维护。）

### 3.3 项 3 信号路径 ✅
`Tone 8` → 自动 attach；`sessions` = `params=26, effectId>=0, attached=true`；
flinger 里 `UUID f27317f4…` / `name: JamesDSP v4.01` / client pid = daemon；
停止后 3 s 内 `released`（修复 B）。attach 耗时实测 **39.8 / 43.7 / 66-73 / 104-112 ms**（随负载）。

### 3.4 项 4 参数可听生效 ⚠️（通过，但被设备声学路径压缩）
- 字节级：`set <s> 1500 --hex cdccccbd00007042 00007041` → `get` 回读完全一致；
  `dump-session.effectiveParams["1500"]` 同步；`baseline` 复位后回读 `…00000000`。
- 声学 A/B（机内麦，1 s RMS 窗口）：
  - postGain 0 → +15 dB：**+10.2 dB**（源幅度 8000）
  - 源幅度 2500、postGain 0 → +15 dB：**+9.4 dB**
  - 把限制器阈值抬到 +40 dB 再 +15 dB：**仍 +9.4 dB** ⇒ 压缩不在引擎，在设备输出/采集路径
  - 0 → +25 dB：**+9.5 dB**（同一上限 −36 dBFS @ mic）⇒ 输出侧硬压缩
  - −15 dB → +15 dB 摆幅：稳态 **15.5 dB**、DRC 攻击段瞬时 **19.4 dB**
- 结论：参数确实可听生效（且方向/幅度单调），但本机"喇叭 + 麦"路径约 1.6:1 压缩，
  计划要求的"+15 dB 阶跃 ≥12 dB"在该设备上**物理不可达**；用 30 dB 摆幅测得 15.5 dB（≥12 dB）。
  记录为设备特性，不是 DSP 缺陷（限制器旁路实验已排除引擎侧）。

### 3.5 项 5 电源卸载语义 ✅
tone 播放中执行 `enabled off` → `sessions` 内该会话 `attached=false, params=0, effectId=-1`；
`dumpsys media.audio_flinger` 中 `f27317f4` 实例数 0；`enabled on` → 重挂 `params=26`。
日志口径：off **16 ms**（`global disabled`→`detached all`）、on **137 ms**（`global enabled`→`attach`），
均满足 off≤0.5 s / on≤1 s。

### 3.6 项 6 黑名单 ✅
真实 App：`com.salt.music`（uid 10344）播放 → `sessions` 显示 `pkg=com.salt.music, attached=true`；
`policy set com.salt.music exclude` → `attached=false, effectId=-1`，flinger 实例 0，日志
`policy com.salt.music -> 2` + `detach session=457`；`policy set … follow` → 重挂 `effectId=979, params=26`；
`policy list` 恢复空。

### 3.7 项 7 多会话不静音 ✅
- `scripts/dual-session-probe.sh ds-normal normal normal 14 8 on` → `RESULT B_both=OK B_solo=OK`；
  flinger 同时存在 **2 条** JamesDSP 效果链（session 289 与 297）。
- 双频 FFT（两个独立会话同时播 440 Hz 与 880 Hz，机内麦 PCM 逐秒 FFT）：
  `e440=-49.8 dB`、`e880=-44.5 dB`，verdict `A_on B_on` ⇒ 双频同时在。

### 3.8 项 8 低延迟组合 ✅
`scripts/dual-session-probe.sh ds-lowlat low normal 14 8 on` → `RESULT B_both=OK B_solo=OK`；
`BadvancedInSolo=true`（fast track 与 deep buffer 混跑不互相静音）。

### 3.9 项 9 突发提交 ✅
设备侧客户端（`tools/TcpBurst.java`，与 App 同一条 loopback TCP 协议，**避开 adb forward 的传输噪声**）：
```json
{"sent":2000,"fails":0,"elapsed_s":20.00,"achieved_rate":100.0,
 "latency_ms_p50":3.498,"p95":10.653,"p99":12.900,"max":17.375,"final_match":true}
```
（字段单位 µs：p50 **3.5 ms**、p95 10.7 ms、p99 12.9 ms、max 17.4 ms；全程 100 次/秒）
flinger 播放线程 `underruns=0`、`Normal mixer raw underrun counters partial=0 empty=0`；
最终值 `final_match=true`（后写覆盖前写，不错序）。
对照：经 `adb forward` 的主机侧跑法 p50 ≈ 43 ms（传输占 ~35 ms），同样 0 失败/终值精确——
说明慢在 adb，不在 daemon。

### 3.10 项 10 基线命令 ✅
污染 `1500=000000c00000c84100007042`、`1218=0100` → `baseline --all` → `applied=26`（广播到挂载会话）+
`perAppParams cleared` → `dump-config.globalParams` 与 `scripts/baseline.json` **逐字节相同**（26/26，零差异）。

### 3.11 项 11 App 首启零污染 ✅（限无播放时，见 §6.3）
`pm clear james.dsp` → 启动 → DSP 屏在位（截图 `out/accept-v2.1.0/app-dsp-screen.png`：
"内置扬声器 / 限制器阈值 -0.10dB / 释放 60.00ms / 后增益 0.00dB"，开关默认关）→ 停留 + 滚动 →
`dump-config` 仍与基线逐字节相同，`perAppParams` 空，无 App 崩溃。

### 3.12 项 12 开关毛刺 ✅
播放中连做两轮 `enabled off → on`，机内麦 10 ms 帧 RMS：暖机后 **0 次**低于 `ref-20 dB` 的静音段；
开关事件 ±0.3 s 窗口内最低 `-47.9 dBFS`（稳态 `-41.2 dBFS`），无 >200 ms 静音。

---

## 4. 关键指标（本机实测，供下一台设备对照）

| 指标 | 本机（ColorOS 16 / 高通） | 计划基准（xaga） |
|---|---|---|
| attach（含引擎构建） | 40-112 ms | ~90 ms |
| 主开关 off（全卸载） | 16 ms | 26 ms |
| 主开关 on（全重挂） | 137 ms | 256 ms |
| 新会话生效 | ~0.5-1.0 s | ~1 s |
| setParam p50/p95 | 3.5 / 10.7 ms | 4-5 / ~45 ms |
| 双会话 | 双链、双频可听 | 通过 |
| 声学 A/B（+15 dB 请求） | +9.4~10.2 dB（设备压缩） | ≥12 dB |

---

## 5. soak

- 启跑：**2026-10-26 06:32:15**（干净基线、App 已 force-stop），计划 72 h → **10-29 06:32**；
  首行 `report.tsv`：`10-26 06:32:15  1  20845  289877  0  156  0  2  0  0  0  da961fdb  17664  0`
- 启动方式：`setsid sh /data/local/tmp/jdsp-soak/soak_device.sh`（KernelSU 下 su 会杀后台子进程，必须 setsid）
- 说明：06:23 首次起跑后，为把被 App 污染过的配置换成干净基线，**有意** kill 了 daemon 两次
  （daemon 退出码 143 = SIGTERM，由看护 `jdspd.sh` 2 s 内拉起；设备未重启，`bootid` 不变、`devuptime` 单调）。
  因此最终 soak 窗口从 06:32:15 重新起算。**daemon 重启不是异常，是本会话的手工操作。**
- **本会话加固了 soak 脚本**（`scripts/soak_device.sh`，旧栈复盘结论 + 本次实测）：
  - 新增 `bootid`（设备是否重启）、`devuptime`（区分 daemon 重启与设备重启）、
    `jdsphits`（dropbox 里 james.dsp/jdspd/libjamesdsp 条目数 = JDSP 定向崩溃证据）
  - 每 6 h 的驱动 tone 改用 `timeout 20 env CLASSPATH=… Tone 5 soak` 包裹：ColorOS 上 tone 播完偶发不退出
    （实测攒了 2 个僵进程），不包裹的话 72 h 会累积十几个
  - 注意：`fatal/anr/audiotrackCrash` 三列取的是 logcat 环形缓冲**当前内容**，会被 `logcat -c`/轮转清空，
    只能当参考；首行 `fatal=2` 是缓冲里的历史条目，与 JDSP 无关（`jdsphits=0`）。详见
    `docs/COLOROS-v2.0.0-SOAK-REPORT.md`

---

## 6. 未通过 / 未完成 / 偏差

### 6.1 第三台设备（P6）未接入
本会话 `adb devices` 只有 `64a4bad0`（ColorOS 机），无第二台硬件；计划 §1-§3 的"新设备首装"
无法执行。作为替代，本会话把 ColorOS 机（计划里的第二台）升级并全量复跑，等价于"新 ROM 家族的
首次安装验收"，并把两处 ColorOS 专属 P0 修掉——这是计划 §1 预检/§2 装机/§3 清单的真实演练。
**第三台设备仍是 P6 的唯一缺口**，接上后按 `docs/PLAN-next-device.md` 执行即可（命令已在本次全部验证过）。

### 6.2 项 4 的声学测量在 ColorOS 上饱和（设备特性）
见 §3.4：本机喇叭→麦路径约 1.6:1 压缩（6 dB 阶跃测 3.4 dB、15 dB 测 9.4 dB、25 dB 仍 9.5 dB；
限制器旁路阈值 +40 dB 后不变 ⇒ 压缩在引擎之外）。因此"≥12 dB"只能用 30 dB 摆幅（15.5 dB）
或 DRC 攻击瞬时（19.4 dB）达成。下一台设备若喇叭/麦路径不自带 DRC，可复现 xaga 的 ≥12 dB。

### 6.3 **P1：App（1.6.16-62，`b7ff6461`）在"有播放会话"时仍会写参数**（新发现，阻塞"App 仅 UI"宣称）
- 复现（确定性）：`pm clear james.dsp` → 启动 App 到 DSP 屏 → 播 tone（任意会话）→
  daemon 日志出现 TCP 客户端 `attach(<sid>)`，随后写入：
  - `116`（16 段 EQ）被覆盖成"全零/无曲线"（基线带 EQ 曲线）
  - `128=0f00`、`188=0500`（旧引擎参数，基线不存在）
  - `1307` 被覆盖成 App 默认（`00442c47…`）
  - 还尝试写 `1301`(24B)、`1304`(16B)，被引擎以 status=-1 拒绝（**新旧引擎参数长度不一致**的活证据）
  - 结果：`dump-config` 26 → 28 个键，`116/1307` 偏离基线；**每次"开 App + 播放"都会再写一遍**。
- 对照：App 未打开时播 tone → `dump-config` 26 键逐字节不变（daemon 自身零污染）。
- 影响：用户在 App 里"看一眼"就会把后端权威配置改成 App 的 UI 默认值；soak 期间若 App 在前台，
  每 6 h 的 tone 也会触发一次污染。
- 当前处置：**soak 期间 App 已 force-stop**；基线已重写为干净 26 键并重启 daemon 校验通过。
- 修复建议（下一个 App 版本）：只把**用户真实编辑**的参数发给 daemon；不要用 App 默认值回填；
  删除 1301/1304 等旧长度写；UI 的初值一律以 `getConfig/dumpSession` 的后端返回为准。

### 6.4 项 13 待用户
听感终审（拖滑杆/双 App 播放/开关）需要用户在场，未做。

---

## 7. 遗留清单（更新版，按优先级）

1. **App 写参（§6.3，P1）**：App 源码在 `/home/bdy/work/p5-app` 与 `loundess_git`，需按 §6.3 建议改并重新签名发布。
2. **第三台设备接入（P6 缺口）**：接上后按 `docs/PLAN-next-device.md` 执行；重点关注 §3.4 的声学项。
3. **watcher 观测项**（host-verify 建议）：有 track 行但 0 匹配时打 WARN 并暴露 `unparsedTrackRows`；
   现在会话只能由 track 行创建，下一家 ROM 变体若格式再变会"完全没有会话"且无提示。
4. **arm64-sve / aidl / x86 三路引擎仍是上游预编译**（无 0002-0006）：命中这些分支的机型会"名义 v2.1.2
   但丢 0003/0005/0006 能力"。本机与 xaga 都走 arm64，未受影响。
5. **`common/install.sh:122`** 无条件把所选 ABI 库复制到 `system/lib/soundfx`：本机 abilist32 为空故无害，
   但对有 32 位进程的机型是 latent defect（`system/vendor/lib/soundfx/libjamesdsp.so` = arm64 文件）。
6. 构建链不可复现：`native/build.sh` 不应用补丁、`setup-src.sh` 只到 0003，`0002` 补丁未入 git（见 `out/verify/dist-verify.md` R1/R2）。
7. 旧栈 soak 报告：`docs/COLOROS-v2.0.0-SOAK-REPORT.md`（7h41m/72h = 10.7%，被 20:08 的普通重启掐断；
   fatal 列=环形缓冲快照，全部为腾讯 soter 的 NPE，零条 JDSP）。
8. ColorOS 写锁 framework 返回值问题（0002 引擎侧已编入）：本机 backend 模式下未见异常，留观察。
9. 开机竞态（O-1，低）：daemon 先于 App 数据目录就绪时写 `/data/data/james.dsp/files/jdsp.{port,token}` 失败
   （日志 WARN `cannot write port/token files`），30 s 内自愈；首次开机到 App 首启之间有窗口期。
10. 日志噪声（O-2，低）：`policy: 453 package entries, 388 uids` 每 30 s 一条（≈2880 行/天），
    长期 soak 会把 jdspd.log 撑到 MB 级（已有 1 MB 轮转保护），建议降频或降级为 DEBUG。
11. soak 的 `Tone 5 soak` 在 ColorOS 上偶发不退出（已用 `timeout 20` 包裹兜住）；根因疑似
    AudioTrack release 与写入线程的竞态，未深挖（不影响数据）。

---

## 8. 复现与证据索引

常用命令（本机 `adb -s 64a4bad0`）：
```sh
CLI="su -c 'CLASSPATH=/data/adb/modules/ainur_jamesdsp/backend/jdspcli.dex app_process /system/bin com.jdsp.cli.Main'"
adb shell "$CLI --json stats"            # 版本/会话/watcher/tcp
adb shell "$CLI --json sessions"         # 会话与 attach 状态
adb shell "$CLI baseline --all"          # 复位 + 广播
adb shell "$CLI policy set <pkg> exclude|follow"
adb shell "su -c 'CLASSPATH=/data/local/tmp/tone3.dex app_process /system/bin Tone 8 tag 2500'"   # 第 3 参数=幅度
adb shell "su -c 'CLASSPATH=/data/local/tmp/tone3.dex app_process /system/bin DualSession miccap 10 mic|unprocessed|voice'"
DualSession dual normal normal 12 6 <ampA> 440 880      # 双会话 + 双频
```

证据文件（全部在 `out/` 下，gitignore）：
`out/accept-v2.1.0/` 安装日志（install/install-v211/install-v212）、各项 `item*-*.log`、
`mic-*.txt`（RMS）、`dual-fft.pcm`/`two-tone.pcm`（FFT 原始 PCM）、`glitch.pcm`（开关毛刺）、
`dump-*.json`（配置快照，含 App 污染前后）、`app-dsp-screen.png`（App DSP 屏）；
`out/verify/`（dist v2.1.0/v2.1.1/v2.1.2 的独立核验报告）；
`out/oldstack-evidence/`（旧栈 soak/崩溃原始证据，已与设备解耦）。
