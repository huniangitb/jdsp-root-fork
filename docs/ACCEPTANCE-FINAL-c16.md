# 最终验收 — 新设备 c16（OnePlus OPD2404 / ColorOS 16 / Android 16）

- 设备：serial `64a4bad0`，`OPD2404`，`OP5D77L1`，Android 16（SDK 36），KernelSU（`u:r:ksu:s0`）
- 模块：`dist/JDSP-fork-v2.0.0.zip` sha256 `414394f00856739405728a98b67e68ed503df9413cfc51287e4dbac31a0979a0`
- 后端 dex：jdspd `12c20cb817e1683e6cccb71bb8063d076cb937e19d6aca86a82f8ae024ea1c53`、jdspcli `ef129417d1b5009f2fd185157d25f14e4d9efd2e8130e3c2c3e224fb0cb33396`
- 主机测试：`backend/test/run_tests.sh` **184 passed / 0 failed**；App 宿主单测 **30/30**
- 判据通道：状态判定与参数下发走 **binder**（`jdsp.service`），App 侧走 loopback TCP + token

---

## 1. 部署与基线

| 项 | 结果 | 证据 |
|---|---|---|
| 模块版本 | v2.0.0-fork / versionCode 200 | module.prop |
| daemon 自启 | 通过 pid 3675（`ps`；本机 `pidof` 不可靠） | `ps -A` 输出 |
| binder 服务 | 通过 `Service jdsp.service: found` | `service check` |
| 模式 / 开关 | 通过 `mode=auto`、`enabled=true` | binder dump-config |
| 参数保留 | 通过 25 个 globalParams，`1500=cdccccbd00007042000070c1` | binder dump-config |
| 稳定性 | 通过 `watcherFailures=0`，uptime 约 5 小时 | binder stats |
| 引擎基线 | 通过 `/vendor/lib64/soundfx/libjamesdsp.so` = `b6e1fc1f…` | sha256 |
| App 端点文件 | 通过 `jdsp.port`/`jdsp.token`，0400，owner=App uid | `ls -l` |

## 2. 核心判据 A：后端完全脱离前端可用（已确证）

`am force-stop james.dsp` 之后（前端进程已消失，ps 计数 1 → 0）：

| 步骤 | 结果 |
|---|---|
| 无前端放音 | 通过 新会话 161 被 attach（attached=true，params=25），会话 145 仍 attached=105 |
| 配置是否保留 | 通过 dump-config 仍有 25 个参数，`1500=cdccccbd00007042000070c1` |
| 无前端下发参数 | 通过 `setParamCalls` 467 → 468，`1500` 变为 `0000c0c00000704200004040` |
| 参数恢复 | 通过 写回 `cdccccbd0000704200004040` 成功 |

⇒ 后端链路在前端完全不存在时仍可 attach、保留配置、接受并应用参数更改。

## 3. 核心判据 B：音效按参数设定真实作用于音频信号（已确证 PASS）

采用**离线渲染**方案（REMOTE_SUBMIX 与麦克风在本机均不可用），引擎真实处理采样点后测量输出电平。

`scripts/verify-signal.sh --serial 64a4bad0` 结果（我已亲自复跑验证）：

| 项 | 结果 |
|---|---|
| signal-delta | PASS：postGain +12 dB 使电平变化 **12.00 dB**（阈值 >1.5 dB） |
| signal-direction | PASS：增益↑ → RMS↑（-21.01 → -9.01 dBFS） |
| signal-restore   | PASS：恢复参数后回到基线（偏差 **0.00 dB**，阈值 ±0.5 dB） |
| capture-available | PASS：REMOTE_SUBMIX 全零不可用，改用离线渲染 |
| log-crash / no-underrun | PASS：采集窗口无 crash/ANR，underrun 计数 0 |
| binder-setparam / binder-latency | PASS：setParamCalls=549，avgLatencyUs=1330（<20 ms） |

总计 **13 passed / 0 failed / 0 skipped**。

物理一致性校验（证明是真测量）：440 Hz / -18 dBFS 纯音理论 RMS 2916.900959，实测 2916.431570（比值 0.999839）；
+12 dB 后理论 11612.391877，实测 11610.518347（比值同为 0.999839）。引擎在中性参数下位透明，+12 dB 精确为 +12 dB，
残差仅来自 4800 点正弦的采样。连续多次运行输出完全一致。

### 3.1 为何之前崩溃、根因是什么

旧 `offline_render.c` 有两处独立错误（现均已修正）：

1. **vtable 成员顺序错**：旧代码假定 `{get, set, process, release, get_descriptor}`，
   引擎实际是 AOSP `effect_interface_s {process, command, get_descriptor, process_reverse}`（essential.h:238）。
2. **config 布局臆造**：旧代码用自编的 `effect_buffer_config_t`，
   引擎读的是 `buffer_config_t{buffer, samplingRate, channels, bufferProvider, format, accessMode, mask}`（essential.h:754）。
   `configure()`（jamesdsp.c:124）按 AOSP 布局取 `in.samplingRate`，旧布局下读到垃圾值 →
   `JamesDSPSetSampleRate()` 用垃圾采样率重建缓冲 → 巨量 malloc → 被 OOM killer 杀掉（即此前的 `Killed`，rc=137）。

正确布局已在设备实测确认并写成**编译期断言**（布局不符直接编译失败）：
`audio_buffer_t`=16 B；`buffer_config_t`=56 B（samplingRate@16、channels@20、provider@24、format@48、accessMode@49、mask@50）；
`effect_config_t`=112 B（inputCfg@0、outputCfg@56）。

### 3.2 两个关键工程发现

1. **引擎自带 brickwall 限幅器**（阈值钳位 -0.09 dBFS，jamesdsp.c:716 / jdspController.c:359-377）会吃掉增益：
   -6 dBFS 测试音上 +12 dB 只体现为 +6.40 dB（被限幅）；-18 dBFS 上才精确得到 +12.00 dB。
   实测矩阵：-6→+6.40、-12→+11.93、-18→+12.00、-24→+12.00 dB。
   故离线渲染默认测试音改为 **-18 dBFS**（`OFFLINE_TONE_DBFS` 可覆盖）。这是引擎物理行为，不是实现缺陷。
2. **引擎会持久化参数并在 EffectCreate 时回放**（可能覆盖用户真实设置），还会抢占 `/data/vendor/audio/jdsp_ctl.sock`。
   渲染器在 dlopen 前设 `JDSP_STATE_DISABLE=1`，并用 `unshare(CLONE_NEWNS)`+`MS_PRIVATE`+tmpfs 做私有 mount namespace 隔离。
   验证：连跑多次后宿主 `/data/vendor/audio` 的 md5sum、目录内容、`/proc/mounts` 均无变化（我复跑后实测一致）。

### 3.3 一处调试事故（已完全还原）

调试中曾手动执行缺 `MS_PRIVATE` 的 `mount -t tmpfs`，tmpfs 泄漏进设备全局 mount namespace，遮蔽了 `/data/vendor/audio`。
已 `umount` 还原：`jamesdsp_state.bin`（14578 字节、原始 mtime）完整恢复，audioserver 正常，
我复查 `/proc/mounts` 中 `/data/vendor/audio` 计数为 **0**，崩溃记录均早于 09:00 且与 jamesdsp 无关（tombstone 来自其它进程）。
渲染器自身隔离路径已含 `MS_PRIVATE`，对宿主零影响。
## 4. 已修复的发布阻塞缺陷

| 缺陷 | 现象 | 修复 |
|---|---|---|
| bypass 分支 churn | 全局关闭时每 tick 对每会话调 setEnabled(false)（约 28 行/秒），与 system_server 崩溃（AudioTrack: Unable to retrieve AudioTrack pointer）窗口重合 | BypassRule 幂等 + EffectManager.setEnabled 幂等；实测关闭 60s 内 setEnabled 增量 4（修复前数百行），crash 计数 0 |
| enabled 抖动 | App 反复写回 enabled 造成 on/off 抖动 | EnableSwitch：回声纯 no-op + 1 次/秒速率限制 |
| endpoint 文件自愈 | App 重装或目录丢失后 token 不可写 | watcher 每 30s 重建；实测目录出现后不超过 30s 写入，App 重连成功 |
| 参数作用域不一致 | set/get/replay 三处 pkg 判定不同 | ConfigStore.scopeFor 单一规则 |

## 5. 仍未满足（不虚报）

1. ~~信号级生效未确证~~ **已解决（判据 B 现为 PASS，见第 3 节）**；听感（人工听音）仍未做。
2. P5 第二阶段未完全达成：App 仍保留一个只用于健康查询（19998/20001/20002）的本地对象；彻底移除需后端提供这三者真实值，而 v6.7.2 引擎不支持 GET_PARAM。App 的 SharedPreferences 仍是 UI 初始值来源（真相已在后端 config.json），UI 读侧改读 dumpConfig 需重写 App 源码。
3. 卸载前端的完整验收未跑（仅验到 force-stop）；卸载后 token 文件消失的恢复仍需补验。
4. 72 小时长稳未跑（最长连续运行约 5 小时，watcherFailures=0）。
5. 多设备仅 2 台（xaga Android 15、本机 Android 16），PLAN-v2 P6 要求的三台未齐。
6. 早期 system_server 崩溃 2 次由 churn 引发（已修），但修复后未做长时间回归确认零复发。

## 3.1 离线渲染排查的精确阻塞点（补充）

在判据 B 上继续投入排查后，得到以下确定事实与最终阻塞点：

已确认：
- 引擎 SET_CONFIG 使用标准 effect_config_t（inputCfg / outputCfg，各含 mask），不是私有结构
  （源码 jamesdsp.c:124 configure() 直接读 cfg->inputCfg / cfg->outputCfg）。
- 引擎使用标准 AOSP 命令号：SET_PARAM=5、GET_PARAM=8（logcat 实测 cmdCode 5 / 8）；INIT=0、SET_CONFIG=1、ENABLE=3。
- 引擎库可 dlopen，EffectCreate 返回 rc=0 且 handle/vtable 可解析（get/set/process/descriptor 地址均有效）。

最终阻塞点：
- 调用 SET_CONFIG（无论直接调用导出符号 EffectDSPMainCommand，还是走标准 vtable 的 set 接口）均立即段错误（rc=137，被 Killed）。
- 崩溃发生在 apply_config 的第一条 SET_CONFIG 调用处（分步打印与 fflush 确认到最后一行 entering apply_config）。
- 推断：引擎的 generic_command 包装需要 AudioFlinger 提供的运行期上下文（如 buffer provider 或框架先写入的字段），离线直接驱动缺少该上下文。

结论：离线渲染路径未能打通，判据 B 维持 SKIP。继续逆向厂商运行期上下文的成本高于收益，已停止。
若要确判据 B，建议：在支持 REMOTE_SUBMIX 回采的设备或 ROM 上重跑，或人工听音验证。
