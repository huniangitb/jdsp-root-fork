# v2.1.12 真机验收记录（ColorOS 16 / OPD2404）

> 验收对象：`dist/JDSP-fork-v2.1.12.zip`（模块 `v2.1.12-fork` / 222）+ 内置 App `1.6.21`（67）。
> 设备：OnePlus OPD2404 / ColorOS 16 / Android 16（SDK 36）/ SM8650 / arm64-v8a / KernelSU（`u:r:ksu:s0`），adb 序列号 `64a4bad0`。
> 关联文档：`docs/MODE-SWITCH.md` §7（运行期 mode 语义与 App 契约）、`module/src/changelog.md` v2.1.12 条目。

## 0. 本轮要解决的三件事

| # | 用户反馈 | 结论 |
|---|---|---|
| 1 | App 崩溃：`Slider value(1.0) must be greater or equal to valueFrom(3.0)` | 修好并在真机复现路径上验证通过（§3） |
| 2 | 「传统模式」选了却不生效、「增强处理」实际按应用处理 —— 前端与后端不对齐 | 两个开关接 `setmode`，改单选语义，"当前生效"只读行渲染 `modeEffective`（§2） |
| 3 | 「实时可编程 DSP（LiveProg）」失效 | 编辑器"保存并运行"改为无条件下发，真机确认 5 个参数全部 `rc=0`，脚本进入引擎 state（§4） |

## 1. 交付物与开机状态

```
dist/JDSP-fork-v2.1.12.zip   33,292,370 B
sha256 55406d5805b398a6e61c28374647940b6913b2d90eb9c3cd98515e577e9e3f77
内置 APK  JamesDSP-v1.6.21-67-root-full-universal-release.apk (39,793,045 B)
          md5 9a6d51a125aff7b7c2fe9422a142f72f  versionCode=67 versionName=1.6.21
```

`ksud module install` + 重启后（08:11）：

```
module.prop      version=v2.1.12-fork (2026-11-08)   versionCode=222
/data/adb/jdspd/mode   auto
jdspd            ready: binder=true tcp=37249 config=/data/adb/jdspd/config.json  (pid 3660)
App              versionCode=67 versionName=1.6.21；/data/app/.../base.apk md5 9a6d51a1…（与打包内一致）
automount XML    /odm/etc/audio_effects.xml: 1 处 effect="jamesdsp"   → modeEffective=auto
soak             开机后 soak_device.sh 自动续跑
```

开机后播 1 kHz 正弦，自动进入 auto 模式 join：

```
08:12:33.502 attach session=89 effectId=43 (dump said 43) enabled=true hasControl=true in 23103us
08:12:34.152 attached session=89 uid=0 pkg= (auto: framework instance present)
```

## 2. 模式开关：App ↔ 后端双向对齐（真机 UI 驱动）

操作路径：主界面右下"设置" → 音频处理。

### 2.1 点「增强处理」→ 后端确实切到 backend，并如实提示未生效

```
/data/adb/jdspd/mode            backend
/data/adb/jdspd/config.json     "mode": 1
UI 弹窗                          需要重启
                               处理模式已切换为 增强处理（按应用）。音频配置只在开机时读取，
                               重启后才会真正生效；在此之前仍沿用原模式。
状态行                           当前生效：传统模式（全局挂载）
                               已选择：增强处理（按应用） — 重启后生效
```

### 2.2 点「传统模式」→ 后端回到 auto，状态行恢复

```
/data/adb/jdspd/mode            auto
状态行                           当前生效：传统模式（全局挂载）
                               每个音频会话的音效实例由系统创建，后端只负责下发参数，不会在会话边界插入处理。
```

### 2.3 原始协议（`tools/RawJson.java`，同一台设备）

```
getmode  → {"ok":true,"mode":0,"modeName":"auto","modeEffective":"auto","changed":false,
            "reboot_required":false,"rebootRequired":false}
setmode backend → {"ok":true,"mode":1,"modeName":"backend","modeEffective":"auto",
                   "changed":true,"reboot_required":true,"rebootRequired":true}
setmode auto    → {"ok":true,"mode":0,"modeName":"auto","modeEffective":"auto",
                   "changed":true,"reboot_required":false,"rebootRequired":false}
```

### 2.4 本轮改掉的"前端假象"

- 两个开关原来是两条独立 SharedPreferences，对音频链路**零影响**；现在都是 `setmode` 的唯一入口。
- 后端只有两个 mode ⇒ 两个开关改为**单选**：关「传统模式」= 选「增强处理」，反之亦然（不再存在"两个都关"的伪状态）。
- root 模式下点「增强处理」不再弹"没有 Root 权限"：DUMP 权限门只保留给 rootless 采集回路
  （`isRootless() && !hasDumpPermission()`），root 模式的会话发现全在 jdspd。
- 开关不再启停 `RootAudioProcessorService`、不再发 `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` 给旧 session-0 链路。
- 两行 summary 文案改成描述真实语义（不再出现"附加到会话 0（已作废）"）。

## 3. 崩溃回归：低音最大增益 = 1.0

崩溃根因：后端/引擎允许 `0..15`（默认 1），控件写死 `min 3 / default 5`，绑定路径把后端值 1.0 直接塞进
`com.google.android.material.slider.BaseSlider` ⇒ `IllegalStateException`。

修复与验证：

- `MaterialSeekbarPreference` 新增 `clampToRange()`（bind 两个分支 + `onSetInitialValue` 兜底；
  **不能**放 `onGetDefaultValue`，那时 `mMin/mMax` 还是 0）；
- `dsp_bass_preferences.xml`：`minValue 3 → 0`、`defaultValue 5 → 1`；
- 后端当时的真值就是 `"112": "0100"`（int16 = 1）。

真机：主界面打开「动态低音提升」，子项立即绑定后端值：

```
最大增益    Value, 1    1.00dB          ← 崩溃复现路径，未抛异常
logcat     write dsp_bass/bass_enable param=1201 value=true snapshot=true
无 FATAL / IllegalStateException
```

同批修复的其它值域错配（`out/review/params-fix.md`）：谐波/次谐波 9 个 gain 键名错位一格且未除 100、
`iso_q` scale 1000→100、删除 `loudness_mode` 映射（1301 只有 5 个 float）、`cast()` 补 Number→String。

## 4. LiveProg：编辑器"保存并运行"现在真的下发

真机步骤：把测试脚本 `@init gain = 1.0; @sample spl0 *= gain; spl1 *= gain;` 放到 App 私有目录，
用 `am start -n james.dsp/...LiveprogEditorActivity -e TargetFile <path>` 打开编辑器，点"保存并运行"。

### 4.1 有播放会话时（session 已 join）

```
I/JdspPrefWriter: liveprog submit: source=…/test.eel chars=54 parts=1
I/JdspPrefWriter: liveprog send alloc id=8888  len=8   rc=0
I/JdspPrefWriter: liveprog send chunk[0/1] id=12001 len=256 rc=0
I/JdspPrefWriter: liveprog send value id=10010 len=2 value=1 rc=0
I/JdspPrefWriter: liveprog send value id=25002 len=4 value=-301179968 rc=0
I/JdspPrefWriter: liveprog send value id=1213  len=2 value=1 rc=0
I/JdspPrefWriter: liveprog submit: source=…/test.eel -> queued
```

引擎侧证据（脚本进了引擎状态，说明 10010 的 EEL 编译成功并被采纳）：

```
$ strings /data/vendor/audio/jamesdsp_state.bin | grep '@init'
@init
gain = 1.0;
@sample
spl0 *= gain;
spl1 *= gain;
```

### 4.2 没有播放会话时（诚实失败，不再假装成功）

```
W/jdspd: setParamGlobal: no mounted session for non-persistable param 8888 (vsize=8), dropped
E/JdspBridge: Structural rejection rc=-1002 for param 12001 (global); dropping
```

**已知限制（刻意保留）**：脚本内容（8888/12001/10010）不可持久化，jdspd 在没有已挂载会话时会丢弃并回
`ERR_NO_SESSION(-1002)`（v2.1.11 之前是假成功）。因此"点运行"要在**有声音在播**时做；一旦成功下发一次，
引擎会把它写进自己的 `jamesdsp_state.bin`，后续实例会自行恢复。

### 4.3 附带说明

一次点击会看到两组相同的 5 条下发：一组来自编辑器 `run()`，一组来自随后的
`ACTION_SERVICE_RELOAD_LIVEPROG` 广播（`MainApplication` → `JamesDspRemoteEngine.reloadLiveprog()`）。
两组都是幂等的，代价是 10 次 TCP 写（<15 ms）。

## 5. 静态检查（本轮代码）

| 检查 | 命令 | 结果 |
|---|---|---|
| 后端构建 | `./backend/build.sh` | 成功（jdspd.dex 164,008 B / jdspcli.dex 79,584 B） |
| 后端单测 | `./backend/test/run_tests.sh` | **297 passed / 0 failed** + 自检 11 PASS |
| 编译告警 | `javac --release 8 -Xlint:all -Xlint:-options` | 0 告警 |
| App 构建 | `assembleRootFullRelease`（离线） | BUILD SUCCESSFUL 12m28s |
| 模块打包自检 | `./module/package.sh` | 结构 16 项 + 内容 6 项全绿 |
| 归档完整性 | `unzip -t dist/JDSP-fork-v2.1.12.zip` | OK |

## 6. 残留（不影响本轮验收）

1. 只改了 `values/strings.xml` 与 `values-zh-rCN/strings.xml`；其余 18 个语种仍是旧文案（"附加到会话 0"）。
2. `JdspPrefWriter.defaultFloats(1305/1306)` 的 `mix=50f`、`defaultFloats(1303)[7]=0.432` 仍是旧值（只在后端缺该参数时用作底值）。
3. LiveProg 内容无持久化（见 §4.2），要支持"暂停时编辑、播放后自动生效"需要后端把内容类 id 落盘并按序回放，
   属独立改动。
