# PLAN：下一台设备验证（第三台 / P6）——下一会话执行手册

- 日期：2026-10-26
- 前置状态：xaga（小米 Pad，Android 15/MTK/KernelSU）已全项验证通过；所有修复已进入
  `dist/JDSP-fork-v2.1.0.zip`（引擎补丁 0001-0006、jdspd 2.1.1、App 1.6.16-62）。
  ColorOS 机（64a4bad0，高通，Android 16）仍运行旧栈（dex a04f33e6 / 引擎 b6e1fc1f / App v8），
  其 72h soak 于 10-28 12:19 结束后收报告，随后同法升级。
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
| 配置统一基线 | jdspd 2.1.1 `baseline [--all]` + 脚本自动复位 | 污染→baseline→精确恢复 |
| App 仅 UI | App 1.6.16-62（绑定不落盘/默认值安全/1307 映射修复） | 首启零污染 |

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
adb push dist/JDSP-fork-v2.1.0.zip /data/local/tmp/   # 校验 sha256 与主机一致
adb shell su -c '/data/adb/ksud module install /data/local/tmp/JDSP-fork-v2.1.0.zip'  # 或 Magisk 等价
# 首装默认 mode=auto；切 backend：
adb shell su -c 'sh /data/adb/modules/ainur_jamesdsp/jdsp_mode.sh set backend'
adb reboot
```
装后预重启检查：`modules_update` 内容 md5（jdspd.dex `83bc5808`、libjamesdsp arm64 `265544f5`）、
模块 XML 副本 apply=0、`/data/adb/jdspd/mode`=backend。
App：安装 `JamesDSP-v1.6.16-62-root-full-universal-release.apk`（模块捆绑版同款，md5 `b7ff6461`）。

## 3. 验收清单（全部通过才算交付；命令可复制）

CLI 前缀：`CLI="su -c 'CLASSPATH=/data/adb/modules/ainur_jamesdsp/backend/jdspcli.dex app_process /system/bin com.jdsp.cli.Main'"`（**必须绝对路径**）

| # | 项 | 命令/方法 | 通过标准 |
|---|---|---|---|
| 1 | 模式生效 | `grep -c 'apply effect="jamesdsp"' /vendor/etc/audio_effects.xml`；boot.log `[mode] service` | 挂载 XML=0，无 WARN mismatch |
| 2 | 守护健康 | `jdspcli stats` | version 2.1.1、watcherFailures=0、tcp listening |
| 3 | 信号路径 | tone 播放（见 §5 后台法）+ `sessions` | 自动 attach、params=26、effectId≥0 |
| 4 | 参数可听生效 | attach 后 `set <s> 1500 --hex cdccccbd0000704200007041`（postGain=+15dB，float 15.0 LE=00007041）+ 机内麦 RMS A/B；测完 `baseline` 复位 | A/B ≥ +12dB 差 |
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

## 6. 遗留清单（带入下一会话按优先级处理）

1. ColorOS 机：10-28 12:19 收 soak 报告 → 升级到 dist v2.1.0 全量 → §3 清单复跑（重点 5/7 项，高通为 ACCUMULATE 别名路径的对照平台）。
2. 写锁 framework 返回值问题（0002 引擎侧已编入但 ColorOS framework 把解锁 reply 改写为 -1；乐观门已绕过，留观察）。
3. burst p95≈45ms 尾部：已定性在框架 binder/HAL 层（与 daemon 无关），不阻塞。
4. `compander_granularity` XML 默认 0 vs 引擎 fallback 2 不一致（上游语义，待评审）。
5. dist 的 aidl/arm64-sve/x86 ABI 仍为上游预编译（无 0001-0006）；如目标机非 arm64/arm 需先补构建。

## 7. 关键产物坐标

- 分发包：`dist/JDSP-fork-v2.1.0.zip`（jdspd `83bc5808` / cli `2483ecd7` / 引擎 arm64 `265544f5` arm `6a5ad413` / App `b7ff6461`）
- 验证工具：`scripts/{dual-session-probe.sh,dual-session-fft.py,bench-burst.py,hal-crash-probe.sh,soak_device.sh,soak.sh}`；
  `tools/{DualSession.java,EffectCtl.java,MultiApi.java,tone(Tone.java)}`；基线 `scripts/baseline.json`
- 文档：`docs/MODE-SWITCH.md`（模式切换机理）、`module/src/changelog.md`（v2.1.0 全量变更）
- xaga 现状：全修复在线、配置基线、soak 运行中
