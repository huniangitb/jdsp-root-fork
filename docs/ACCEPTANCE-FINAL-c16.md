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

## 3. 核心判据 B：音效按参数设定真实作用于音频信号（未自动化确证 — SKIP）

判据未能在本机自动化，原因与证据：

1. **AudioRecord(REMOTE_SUBMIX) 不可用**：可初始化（state=1、recordingState=3）且读到 30720 字节，但全零（`rms=-200 dBFS, nonZero=0`）。播放时同样全零。
2. **麦克风源不可用作判据**：同一中性参数三次基线 -40.69 / -45.03 / -45.83 dBFS（抖动 > 4 dB），且出现 postGain +12 dB 后电平反而 -1.02 dB 的反向结果。
3. **离线渲染（引擎库直调）**：能 dlopen 并 EffectCreate 成功（rc=0），但 SET_CONFIG 之后被 Killed（段错误）；继续逆向引擎私有结构风险与时间成本过高，已停止。

⇒ signal-delta / signal-direction / signal-restore 记为 SKIP，原因如实记录（非伪造通过）。听感验证同样未做。

## 4. 已修复的发布阻塞缺陷

| 缺陷 | 现象 | 修复 |
|---|---|---|
| bypass 分支 churn | 全局关闭时每 tick 对每会话调 setEnabled(false)（约 28 行/秒），与 system_server 崩溃（AudioTrack: Unable to retrieve AudioTrack pointer）窗口重合 | BypassRule 幂等 + EffectManager.setEnabled 幂等；实测关闭 60s 内 setEnabled 增量 4（修复前数百行），crash 计数 0 |
| enabled 抖动 | App 反复写回 enabled 造成 on/off 抖动 | EnableSwitch：回声纯 no-op + 1 次/秒速率限制 |
| endpoint 文件自愈 | App 重装或目录丢失后 token 不可写 | watcher 每 30s 重建；实测目录出现后不超过 30s 写入，App 重连成功 |
| 参数作用域不一致 | set/get/replay 三处 pkg 判定不同 | ConfigStore.scopeFor 单一规则 |

## 5. 仍未满足（不虚报）

1. 信号级生效未自动化确证（SKIP，见第 3 节），听感未验。
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
