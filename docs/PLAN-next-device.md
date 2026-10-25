# PLAN：下一台设备验证（第三台 / P6）——下一会话执行手册

> **执行状态（2026-10-26 更新）**：本会话**没有第三台硬件接入**（`adb devices` 只有 ColorOS 机 64a4bad0）。
> 因此执行了本计划的可行半程 = §6.1 的 ColorOS 升级回归：旧栈 → `dist/JDSP-fork-v2.1.2.zip`，
> §3 清单 1-12 项在 ColorOS 16 上跑通，并修掉两处"换设备才暴露"的 P0（AudioFlinger
> `Client(pid/uid)` 合列导致会话发现恒空、以及自有效果链造成会话泄漏）。详见
> `docs/ACCEPTANCE-COLOROS-v2.1.2.md`。
>
> 现状（2026-10-26 06:28 起）：设备跑 **v2.1.2-fork / jdspd 2.1.3 / mode=backend**，72h soak 已起跑
> （→ 10-29 06:28，report.tsv 已加 bootid/devuptime/jdsphits 三列）；**App 已 force-stop**
> ——因为发现 App 1.6.16-62 在"有播放会话"时会回写默认参数（`116/128/188/1307`，并尝试写旧长度的
> `1301/1304`），污染后端权威配置，见验收报告 §6.3。
>
> 第三台设备接上后，本手册 §1-§4 仍按原样执行；只需把 §3 项 2 的期望版本改成 **jdspd 2.1.3**，
> §7 的产物坐标改成 v2.1.2（见文末）。

- 原日期：2026-10-26
- 前置状态（撰写时）：xaga（小米 Pad，Android 15/MTK/KernelSU）已全项验证通过；所有修复已进入
  `dist/JDSP-fork-v2.1.0.zip`（引擎补丁 0001-0006、jdspd 2.1.1、App 1.6.16-62）。
  ColorOS 机（64a4bad0，高通，Android 16）当时仍运行旧栈（dex a04f33e6 / 引擎 b6e1fc1f / App v8）。
- 本会话目标：在一台**新设备**上完成安装 → 验收 → soak，证明 dist 包可交付；
  顺带完成 ColorOS 机升级回归。

---

## 0. 已交付能力基线（验收即验证这些）

| 能力 | 对应修复 | 验收要点 |
|---|---|---|
| 高频调参无噪声 | 引擎 0003（参数队列+块边界应用，消灭撕裂写与跨实例重建风暴） | 用户拖滑杆听感 |
| 双会话/多会话不静音 | 引擎 0005（ACCUMULATE 累加契约） | dual-session-probe 双路可听 |
| 开关/生效低延迟 | 引擎 0006 + jdspd 2.1.0（异步构建/并行挂载/自适应防抖） | off≈26ms on≈256ms 新会话~1s |
| 落盘去抖不影响实时调节 | 引擎 0004 + AsyncPersister | burst p50 4-5ms |
| 配置统一基线 | jdspd 2.1.1+ `baseline [--all]` + 脚本自动复位 | 污染→baseline→精确恢复（ColorOS 实测逐字节通过） |
| App 仅 UI | App 1.6.16-62（绑定不落盘/默认值安全/1307 映射修复） | 首启零污染 —— **2026-10-26 更正：仅"无播放会话"时成立；有播放会话时 App 会回写 116/128/188/1307（§6.3）** |

## 1. 设备预检（装机前，5 分钟）

1. `adb shell getprop ro.product.model / ro.build.version.release`；确认 root 方案
   （KernelSU/Magisk/APatch，影响后台进程与 su 行为，见 §5 陷阱表）。
2. **第三方音频模块冲突检查**（xaga 血泪教训）：
   `ls /data/adb/modules/` + 逐个 `grep -l jamesdsp */system*/*/etc/audio_effects.xml`。
   任何第三方模块自带 `<apply effect="jamesdsp"/>` 都会抢占 vendor XML 挂载使 backend 模式失效。
   处理：删除该模块 XML 内的 jamesdsp **apply 行**（保留库/效果声明与其它内容），原文件备份到
   `/data/adb/jdspd/backup/`；装完后 `boot.log` 的 `[mode] service` 段确认无 "provided by another module"。
3. 检查 `/data/adb/ksud` 或对应管理器 CLI 存在；`/data/local/tmp` 可写。

## 2. 安装（走分发包，不手工散推）

```sh
adb push dist/JDSP-fork-v2.1.2.zip /data/local/tmp/   # sha256 1419b9ee…（校验与主机一致）
adb shell su -c '/data/adb/ksud module install /data/local/tmp/JDSP-fork-v2.1.2.zip'  # 或 Magisk 等价
# 首装默认 mode=auto；切 backend（注意：要调用 modules_update 里的新脚本，不是旧模块的）：
adb shell su -c 'sh /data/adb/modules_update/ainur_jamesdsp/jdsp_mode.sh set backend'
adb reboot
```
装后预重启检查：`modules_update` 内容 md5（jdspd.dex `d584ba4c`、libjamesdsp arm64 `265544f5`）、
模块 XML 副本 apply=0、`/data/adb/jdspd/mode`=backend。
App：安装 `JamesDSP-v1.6.16-62-root-full-universal-release.apk`（模块捆绑版同款，md5 `b7ff6461`）。

## 3. 验收清单（全部通过才算交付；命令可复制）

CLI 前缀：`CLI="su -c 'CLASSPATH=/data/adb/modules/ainur_jamesdsp/backend/jdspcli.dex app_process /system/bin com.jdsp.cli.Main'"`（**必须绝对路径**）

| # | 项 | 命令/方法 | 通过标准 |
|---|---|---|---|
| 1 | 模式生效 | `grep -c 'apply effect="jamesdsp"' /vendor/etc/audio_effects.xml`；boot.log `[mode] service` | 挂载 XML=0，无 WARN mismatch |
| 2 | 守护健康 | `jdspcli stats` | version 2.1.3、watcherFailures=0、tcp listening |
| 3 | 信号路径 | tone 播放（见 §5 后台法）+ `sessions` | 自动 attach、params=26、effectId≥0 |
| 4 | 参数可听生效 | attach 后 `set <s> 1500 --hex cdccccbd0000704200007041`（postGain=+15dB，float 15.0 LE=00007041）+ 机内麦 RMS A/B；测完 `baseline` 复位 | A/B ≥ +12dB 差（注意：ColorOS 16 的喇叭/麦路径自带 ~1.6:1 压缩，+15dB 阶跃只能测到 ~10dB；改用 −15→+15 摆幅可测 15.5dB，见验收报告 §3.4） |
| 5 | 电源卸载语义 | `enabled off`→sessions 清空+dumpsys 零实例；`enabled on`→重挂+26 参数 | 日志口径 off≤0.5s on≤1s |
| 6 | 黑名单 | 真实音乐 App 播放 → `policy set <pkg> exclude`→`follow` | exclude 双会话 detach；follow 重挂 |
| 7 | 多会话不静音（核心） | `scripts/dual-session-probe.sh <name> normal normal 14 8 on` | `B_both=OK`；FFT 双频同在 |
| 8 | 低延迟组合 | 同上 `low normal` | 两路可听 |
| 9 | 突发提交 | `scripts/bench-burst.py`（自带基线复位） | 0 失败、终值精确、underrun=0 |
| 10 | 基线命令 | 污染 1500/1218 → `baseline` → dump-config | 逐字节恢复并广播 |
| 11 | App 首启零污染 | 装 App → `pm clear james.dsp` → 启动进 DSP 屏 → dump-config | 零模块 enable=1、1500=基线 |
| 12 | 开关毛刺 | 探针期间 mic 采集（probe 自带） | 无 >200ms 静音 |
| 13 | 听感终审（用户） | 拖滑杆/双 App 播放/开关 | 无噪声、无声丢失 |

## 4. soak 与收尾

- `adb push scripts/soak_device.sh tone.dex → /data/local/tmp/jdsp-soak/`；setsid 拉起 72h；
  报告 `/data/local/tmp/jdsp-soak/report.tsv`，10 行/小时，只盯 fatal/anr/audiotrackCrash 列。
- 会话结束条件：清单全绿 + soak 首日零 fatal → 移交日常监测；异常项进入 §6 遗留清单。

## 5. 设备环境陷阱速查（xaga 实测沉淀）

| 陷阱 | 症状 | 规避 |
|---|---|---|
| CLI 相对路径 | art abort（ClassNotFoundException 假象） | CLASSPATH 必须绝对路径；入口 `com.jdsp.cli.Main` |
| KernelSU su 杀后台子进程 | nohup+& 的 tone/探针无声无会话 | `setsid`，或主机侧 `nohup timeout N adb shell su -c '...' &` 保活 |
| `su <uid> app_process` | system_classloader 崩 | 不可用；用真实 App（monkey 启动 + `cmd media_session dispatch play`）做 uid 测试 |
| 第三方模块 XML 占位 | backend 切换"无效" | §1.2；boot.log `provided by` 指认 |
| 系统高负载 dumpsys stall | attach 虚高 30s+、假性回归 | 测试前看 load（>12 跳过该窗）；低负载分段 ≤2-3 分钟 |
| 非预期重启（'shell' 级） | 压测中断 | 记 uptime/dropbox 证据→暂停 10 分钟→低负载重试；勿连续重试 |
| MTK HAL 周期 SIGBUS | audioserver 偶发重启 | 与本项目无关的设备问题，记录不阻塞 |
| mic RMS 跨日不可比 | 误判"无声/恢复" | 一律当日同窗做 on/off A/B，不看绝对值 |
| ColorOS 声学路径自带压缩 | +15dB 阶跃只测到 ~+10dB，误判"参数没生效" | 先用"限制器阈值抬到 +40dB 再 +15dB"排除引擎（数值不变即设备侧）；要用 ≥12dB 判据就做 −15→+15 摆幅（实测 15.5dB） |
| AudioFlinger track 表格式随 ROM 变 | backend 模式 `sessions` 恒空、无声效 | 先看 `dumpsys media.audio_flinger` 的 track 行；ColorOS 16 把 `Client(pid/uid)` 合成一列（v2.1.2 已修） |
| 效果链让会话"永生" | 每首歌泄漏一个实例、`sessions` 不消失 | 会话存活只认 track 表；效果链只富化（v2.1.2 已修，见 `DumpsysParser.finishEffect`） |
| App 在有会话时回写默认参数 | 配置被改成 116/128/188/1307 的 UI 默认值 | soak/压测期间 `am force-stop james.dsp`；修复见验收报告 §6.3 |

## 6. 遗留清单（带入下一会话按优先级处理）

1. ~~ColorOS 机升级~~ **已完成（2026-10-26）**：升级到 dist v2.1.2，§3 清单 1-12 项通过，
   72h soak 06:28 起跑（详见 `docs/ACCEPTANCE-COLOROS-v2.1.2.md`）。
1b. **P1：App 1.6.16-62 在有播放会话时回写默认参数**（116/128/188/1307，并尝试写旧长度的 1301/1304），
   污染后端权威配置；soak 期间已 force-stop App。修复需改 App 源码（验收报告 §6.3）。
1c. **第三台设备（P6）仍未接入**——接上后按本手册 §1-§4 执行。
2. 写锁 framework 返回值问题（0002 引擎侧已编入但 ColorOS framework 把解锁 reply 改写为 -1；乐观门已绕过，留观察）。
3. burst p95≈45ms 尾部：已定性在框架 binder/HAL 层（与 daemon 无关），不阻塞。
4. `compander_granularity` XML 默认 0 vs 引擎 fallback 2 不一致（上游语义，待评审）。
5. dist 的 aidl/arm64-sve/x86 ABI 仍为上游预编译（无 0001-0006）；如目标机非 arm64/arm 需先补构建。

## 7. 关键产物坐标

- 分发包（当前交付）：`dist/JDSP-fork-v2.1.2.zip`（sha256 `1419b9ee28c1d84186a9adaf2e2771a1be71ee79e10bacf2d780369348b7f841`；
  jdspd `d584ba4c`（2.1.3）/ cli `2483ecd7` / 引擎 arm64 `265544f5` arm `6a5ad413` / App `b7ff6461`）
  - 中间态留档：`dist/JDSP-fork-v2.1.0.zip`（sha256 `2beca565…`）、`dist/JDSP-fork-v2.1.1.zip`（sha256 `9449aea9…`）
- 验证工具：`scripts/{dual-session-probe.sh,dual-session-fft.py,bench-burst.py,hal-crash-probe.sh,soak_device.sh,soak.sh}`；
  `tools/{DualSession.java,EffectCtl.java,MultiApi.java,Tone.java,TcpBurst.java}`；基线 `scripts/baseline.json`
  - `Tone.java` 现在支持第 3 参数为幅度（`Tone <secs> <tag> <amp>`）；
    `DualSession miccap <secs> [mic|unprocessed|voice]` 可选采集源；`TcpBurst` 为设备侧突发客户端（避开 adb forward 噪声）
- 文档：`docs/MODE-SWITCH.md`（模式切换机理）、`docs/ACCEPTANCE-COLOROS-v2.1.2.md`（本机全量验收）、
  `docs/COLOROS-v2.0.0-SOAK-REPORT.md`（旧栈 soak/崩溃取证）、`module/src/changelog.md`（v2.1.2 全量变更）
- 设备现状：ColorOS 机 = v2.1.2 / backend / 干净基线 / 72h soak 运行中（App 已 force-stop）；
  xaga = v2.1.0 全修复在线（待随 v2.1.2 一起升级）
