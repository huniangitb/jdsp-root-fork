# 非麦克风取数能否验证「第二个音频会话无声」——判定方法与真机 A/B/A 闭环

> 设备 OnePlus OPD2404 / ColorOS 16 / Android 16 / SM8650 / KernelSU（adb 64a4bad0）
> 模块 ainur_jamesdsp v2.1.3-fork，mode=backend，jdspd 2.1.3
> 时间 2026-10-31 12:43–13:15（本机时钟 CST）
> 全部脚本、样本、备份在 `out/tap-loss/`，本文所有数字都能用那里的脚本重跑。

---

## 1. 结论

**能验证，而且要在 thread 级取数。判据落在厂商的 `AF40_ThreadWrite`（mask bit4 = 0x010）上。**

一句话判据：

> 两个会话（同一进程两条 AudioTrack、两个不同 sessionId）同时放 **1 kHz** 与 **3 kHz**，
> 在 `AF40_ThreadWrite` 里（**相位 both 的时间窗内**）两个频率必须同时在；
> **缺一个 = 复现了「某一路被覆盖」**。用 0.5 s 窗、峰值高出本窗谱底 ≥20 dB 记为「在」，
> 要求 both 窗内 ≥90% 的窗都同时有 1 kHz 与 3 kHz。

三轮实测（同一台机、同一 harness、同一 dump 闸门、音量固定 120/160）：

| 轮次 | 装的引擎 | AF40 1 kHz | AF40 3 kHz | 判定 |
|---|---|---|---|---|
| `fix-pass`（12:56） | 出货 0005（累加语义） | **100%** | **96.4%** | ✅ PASS |
| `revert0005`（12:58） | 0001–0004（覆盖语义，= 回退 0005） | 100% | **0%** | ❌ FAIL（3 kHz 整路消失） |
| `fix-pass-restored`（13:04） | 恢复出货 0005，md5 `265544f5…` 校验 | **100%** | **96.6%** | ✅ PASS |

样本侧数字见 §4.1 / §5.3；`AF40` 里 3 kHz 峰值在回退版里**完全不见**（不是被压低 20 dB，是没了）：

![AF40 频谱对比](../out/tap-loss/af40-spectrum.png)

**单靠引擎自己的 tap 判定不了**（除非给它加「mix 保真自检」，见 §9）：
本机 AudioFlinger 给 per-session chain 配的是 `EFFECT_BUFFER_ACCESS_ACCUMULATE`，
**该实例的 out buffer 就是线程共享 mix**，所以引擎 tap 的 lane0（effect 入）只有本会话，
lane1（交还的 out）看到的已经是「别人 + 自己」的混合结果——**覆盖与正确累加在 lane1 上长得一模一样**。
实测证据：修复版里会话 A 的 `AF35_EffectOut` 含 1 kHz 与 3 kHz（100%/100%），
回退版里同一个 lane 只剩 1 kHz——引擎自己无法区分「我该看到的」与「我弄丢的」。
判据必须落在**混音之后**：`AF40_ThreadWrite`（送 HAL 的 buffer），或等价的引擎侧 thread 级 tap。

---

## 2. 历史 bug 的确切机理

### 2.1 框架契约（`native/patches/0005-engine-accumulate-out.patch` 头部原文）

> AudioFlinger configures an effect with EFFECT_BUFFER_ACCESS_ACCUMULATE whenever its input and
> output buffers differ (`EffectModule::requiredEffectBufferAccessMode`). For a per-session chain
> the output buffer maps the output thread's shared mix, which already carries the contribution of
> every other track and chain of that thread. The contract is therefore "ADD your rendered output
> to the out buffer"; all process paths of this engine stored over it instead.

后果（同一段头注释，xaga/MTK 上实测过）：

1. 任何被挂上 JamesDSP 实例的会话，只要它送的是**纯数字静音**，引擎就把共享 mix 覆盖成静音
   → **整条输出线程聋掉**（`threadLoop_write write muted data to hw`），链在就一直聋；
2. 多个实例时**只有最后处理的那条链的声音活着**（前面链的贡献被后面链覆盖）；
3. 单会话播放正常——那时 in/out buffer 是同一块（`ACCESS_WRITE`，原地变换）。

`module/src/changelog.md` v2.1.0 用词一致：
「**引擎 0005（同输出第二会话静音根修）**：遵守 AOSP EFFECT_BUFFER_ACCESS_ACCUMULATE 契约——
链输出缓冲映射线程共享混音时先渲染进私有缓冲再饱和累加，不再替换写抹掉其他会话
（原"第一路播空音频、第二路整路无声"即此）。别名缓冲/OOM/packed-24 保持原语义。」

对应提交 `20ceedd Engine 0005: honor EFFECT_BUFFER_ACCESS_ACCUMULATE (fixes dual-session silence)`。

### 2.2 修复代码长什么样（0005 的核心）

`EffectDSPMainSetConfig` 记住框架给的访问模式：

```c
dspmain->outAccumulate = (out.accessMode == EFFECT_BUFFER_ACCESS_ACCUMULATE);
```

`EffectDSPMainProcess` 变成「先渲染进私有 scratch，再饱和累加」；只有 in/out 别名、
packed-24、或 scratch 申请失败时才回到历史覆盖行为：

```c
int accumulate = dspmain->outAccumulate && frames > 0u && out->raw != in->raw
    && dspmain->formatFloatModeInt32Mode != JDSP_ACCUM_MODE_PACKED24;
...
renderBuf = *out;  renderBuf.raw = dspmain->accScratch;      /* 私有缓冲 */
EffectDSPMainRender(dspmain, in, &renderBuf);
jdsp_accum_add(out->raw, dspmain->accScratch, frames, ...);  /* ADD，不是 store */
```

**所以「回退 0005」= 把 `EffectDSPMainProcess` 换回直接 store 覆盖，并删掉 `jdsp_accum.c/h`。**
本机复现就是这个版本（§5）。

### 2.3 与本机现象对上：被吃掉的是「先被混进 mix、后被覆盖」的那一路

本机实测（§4.1）说明链处理顺序是 **B（3 kHz）先、A（1 kHz）后**：
B 的 out buffer 在处理时只有自己（`AF35_sid5689 = 100%/0%`），
A 的 out buffer 里已经有 B 的 3 kHz（`AF35_sid5681 = 100%/100%`）。
回退版让 A 用「只有 A」覆盖了这块 buffer → **3 kHz 整路消失**，与
「只有最后处理的那条链的声音活着」逐字对应。也解释了用户描述里「第二个会话无声」：
**哪一路被吃取决于它是不是被后处理的链覆盖，而不是谁先 start。**

---

## 3. 检测点：本机 dump 点全图（这次全部实测确认）

### 3.1 闸门与 mask

闸门（`AudioFlingerExtImpl` 构造时读一次，**改属性必须重启 audioserver**）：

```
property_get_bool("ro.audio.voiplog.on", 0) && strstr(getprop("ro.build.version.ota"), "PRE")
```

mask（`AudioFlingerExtImpl::oplusSetParameters` → `OplusAudioPcmDump::updatePcmDumpEnableInfo`），
**只改 mask 不需要重启**；反汇编确认 `getPcmDumpEnableInfo(e) = mask & (1 << e)`，且 `e > 11` 恒 false：

| mask | 位 | dump 点 | 落盘名 | 本机实测 |
|---|---|---|---|---|
| 0x001 | bit0 | `AF10_Track_output` | `AF10_Track_io21_sid<N>_pid<P>_2ch_48000Hz_…wav` | ✅ |
| 0x008 | bit3 | `AF34_Effect_In` + `AF35_Effect_Out` | `AF34_EffectIn_sid<S>_<effectId>_2ch_48000Hz_…wav` | ✅ |
| **0x010** | **bit4** | **`AF40_ThreadWrite`** | `AF40_ThreadWrite_<ioHandle>_2ch_48000Hz_…wav` | ✅ **本次首次跑通** |
| 0x040 | bit6 | `AF50_ThreadFast_Write` / fast mixer `pcm_write` | — | 未用 |

反汇编（`out/tap/src/dumppoints.py`，扫 `libaudioflingerextimpl.so` 里 `getPcmDumpEnableInfo` 的立即数）：

```
AudioFlinger_PlaybackThreadExtImpl::pcmDumpWrite   -> bit 4     <-- AF40，送 HAL 的那块
AudioFlinger_RecordThreadExtImpl::pcmDumpWrite     -> bit 7
AudioFlinger_FastMixerExtImpl::pcm_write           -> bit 6 / bit 10
```

`AF40_ThreadWrite_21_…` 里的 **21 是输出线程的 I/O handle**：这轮两个 track 都在
`Output thread … name AudioOut_15, tid 14961` / `I/O handle: 21` 上
（`samples/fix-pass/dumpsys-during.txt`），文件名里的 21 与之对上
⇒ 它就是「这条输出线程写给 HAL 的 buffer」。

### 3.2 格式（这次踩到的坑，必须写下来）

| lane | 采样格式 | WAV |
|---|---|---|
| AF34 / AF35（effect 层，线程处理格式） | float32 | `tag=3, 32 bit` |
| **AF40（thread → HAL）** | **`AUDIO_FORMAT_PCM_24_BIT_PACKED`**（dumpsys `HAL format: 0x6`） | **`tag=1, 24 bit`，3 字节小端** |
| AF10（track） | float32 | `tag=3, 32 bit` |

`out/tap-loss/tools/analyze_dual.py` 已支持 float32/int32/**int24 packed**/int16/int8，并按文件名里的
`<MMDD>_<HHMMSS.mmm>` 还原每条 lane 的**起始墙钟**，再和 harness 的 `MARK` 行对齐，只统计 both 相位。

### 3.3 闸门上的第二个开关（前人没记录的坑，会让人以为「dump 坏了」）

`persist.oplus.mm.pcmdump=1`（11:06 AD 实验留下的持久属性）会让 dump 库**忽略 atlas mask**：

```
D/AudioFlingerExtImpl: [oplusVoipLogOn]keyvalue qcom_atlas_pcm_dump=8, byLogkit 1, byAtlas 8, dumpState 1
D/OplusAudioPcmDump:   updatePcmDumpEnableInfo() mPcmDumpEnableInfo 1, ...
```

`byAtlas 8` 被 `byLogkit 1` 吃掉 ⇒ 只有 AF10 落盘，**任何 mask 都没用**（8/16/24 都试过，只出 AF10）。
把 `persist.oplus.mm.pcmdump` 置 0 后 mask 才生效（`mPcmDumpEnableInfo 24`），AF34/AF35/AF40 一起出来。
`tools/vendor-gate.sh on` 现在自动做这件事，并在 `off` 时还原原值。

---

## 4. 修复版通过证据（判据 1 通过）

### 4.1 三块 lane 一起看（`samples/fix-pass`，12:56:49，出货引擎）

| lane | 会话 | 1 kHz | 3 kHz | 读法 |
|---|---|---|---|---|
| `AF34_EffectIn_sid1305_147` | A（1 kHz） | 100% | 0% | **effect 入**：只有本会话 ⇒ 天然 per-instance |
| `AF34_EffectIn_sid1313_155` | B（3 kHz） | 0% | 100% | 同上 |
| `AF35_EffectOut_sid1305_147` | A（1 kHz） | **100%** | **100%** | **effect 出 = 线程共享 mix**（已含 B 的 3 kHz） |
| `AF35_EffectOut_sid1313_155` | B（3 kHz） | 0% | 100% | B 先被处理，那时 mix 里还没有 A |
| **`AF40_ThreadWrite_21`** | 线程 | **100%** | **96.4%** | **判据通过：两路都在** |

`AF35_sid1305` 里同时有 1 kHz 与 3 kHz，是「本机 out buffer = 共享 mix」的**直接证据**，
也是「引擎 tap 的 lane1 判不了」的**直接证据**（覆盖与累加在这块 buffer 上不可区分）。

### 4.2 相位切片

harness 的 `MARK` 行给每个相位打墙钟（`samples/fix-pass/harness.log`）：

```
MARK A_start 1793422609444 sid=1305 freq=1000.0
MARK B_start 1793422614491 sid=1313 freq=3000.0 sids=1305,1313
MARK A_stop  1793422628000
```

只统计 `[B_start, A_stop)` 的 0.5 s 窗——**pre 相位（只有 1 kHz）与 solo 相位（只有 3 kHz）不参与判定**，
所以「整段文件里都有两个频率」不是判据，判据只落在 both 相位。

---

## 5. 回退 0005 的复现证据（判据抓得住）

### 5.1 怎么造的回退版（可复现，不含任何「模拟」）

```bash
cd /home/bdy/work/jdsp-native-work
cp -a src-pre0006 src-revert0005                    # src-pre0006 = 0001..0005，未含 0006
patch -R -p1 -d src-revert0005 < native/patches/0005-engine-accumulate-out.patch
# 结果：jamesdsp.c 回到 store 覆盖语义，jdsp_accum.c/h 被删除
NDK=$HOME/Android/Sdk/ndk/30.0.16248370 \
SRC=$PWD/src-revert0005/Main/libjamesdsp/jni/jamesdsp \
OUT=$HOME/work/jdsp-repo/out/tap-loss/build/revert0005 \
  bash $HOME/work/jdsp-repo/native/build.sh           # 只 build arm64-v8a
```

产物 `out/tap-loss/build/revert0005/libjamesdsp-arm64-v8a.so`，md5 `64433e57e394180c06847b04d64c6bae`，
1672528 B —— 与仓库里历史预修版 `native/out/libjamesdsp-arm64-v8a.so.0004-641220f5` 同尺寸
（就是 0001–0004 那条线），说明回退到的正是 bug 时代的行为。

### 5.2 装上它（不需要 reboot）

模块挂载是**同一 inode 的绑定挂载**：`/data/adb/modules/.../libjamesdsp.so` 与
`/vendor/lib64/soundfx/libjamesdsp.so` 的 inode 都是 **579855**，md5 都是 `265544f5…`
⇒ 原位写模块文件、`stop/start audioserver` 就能让新库生效（`tools/engine-swap.sh` 已封装，
含 md5 双向校验、备份、日志）：

```bash
./tools/engine-swap.sh install build/revert0005/libjamesdsp-arm64-v8a.so revert0005
#  -- mounted md5 now: 64433e57e394180c06847b04d64c6bae
#  -- audioserver restarted: pid=22212
```

### 5.3 同一 harness 再跑（`samples/revert0005`，12:58:19）

| lane | 会话 | 1 kHz | 3 kHz |
|---|---|---|---|
| `AF34_EffectIn_sid5681_43` | A（1 kHz） | 100% | 0% |
| `AF34_EffectIn_sid5689_51` | B（3 kHz） | 0% | 100% |
| `AF35_EffectOut_sid5681_43`（A） | 1 kHz | **100%** | **0%** ← 原来的 3 kHz 被覆盖掉了 |
| `AF35_EffectOut_sid5689_51`（B） | 3 kHz | 0% | 100% |
| **`AF40_ThreadWrite_21`** | 线程 | 100% | **0%** ← **整路消失，判据 FAIL** |

两件事同时成立才叫「判据抓得住」：
① 每个实例自己的入/出仍然「看着正常」（B 的 3 kHz 一路 100%）；
② 线程输出里 3 kHz 彻底没有。这正是「per-instance 取数会漏掉、thread 级取数才看得到」。

回退版下 `AF34_EffectIn_sid0_35` 也从「100%/100%」变成「100%/0%」——那条是线程级链（session 0，
厂商自己的 effect），它的入 buffer 直接就是线程 mix，跟着一起缺了 3 kHz，交叉印证。

### 5.4 恢复后闭环（`samples/fix-pass-restored`，13:04:45）

`engine-swap.sh restore` → 模块与挂载点 md5 都回到 `265544f5870b7d527ae6bf7e6d4cbd0e`，
再跑同一 harness：AF40 **1 kHz 100% / 3 kHz 96.6% → PASS**。A/B/A 闭环成立。

---

## 6. 怎么用（脚本）

```bash
cd out/tap-loss

# 一次性全流程（闸门 → 修复版 → 回退版 → 恢复 → 复测 → 关闸门），约 6 分钟
./run-all.sh

# 或者分步
./tools/vendor-gate.sh status
./tools/vendor-gate.sh on 24          # 属性 + 重启 audioserver + mask=0x18（AF34|AF35|AF40）
./tools/capture.sh fix-pass both 1000 3000 5 14 3 8000
./tools/engine-swap.sh install build/revert0005/libjamesdsp-arm64-v8a.so revert0005
./tools/capture.sh revert0005 both 1000 3000 5 14 3 8000
./tools/engine-swap.sh restore
./tools/capture.sh fix-pass-restored both 1000 3000 5 14 3 8000
./tools/vendor-gate.sh off            # mask 0、还原 ro 属性与 persist.oplus.mm.pcmdump、重启

# 单独复算 / 出图
python3 tools/analyze_dual.py samples/RUN --marks samples/RUN/harness.log --phase both -v
python3 tools/plot_af40.py samples af40-spectrum.png fix-pass revert0005 fix-pass-restored
```

产物：`samples/<run>/{*.wav, harness.log, dumplist.txt, dumpsys-before.txt, dumpsys-during.txt, fft.txt, summary.json}`。

harness 也能单独跑（不需要 dump）：

```bash
adb shell su -c 'cd /data/local/tmp/tap-loss && app_process -Djava.class.path=dualloss.dex \
    /system/bin DualLoss both 1000 3000 5 14 3 8000'
#  silence 模式：A 送纯数字静音 + B 送 1 kHz（「整条线程聋掉」那一版）
```

---

## 7. 局限

1. **依赖 ROM 内部实现**：AF34/AF35/AF40 是 ColorOS 的 `OplusAudioPcmDump`。ROM 升级后 mask 位、
   文件名、采样格式都可能变；换机先跑 `tools/vendor-gate.sh status` + §3 的反汇编核对。
2. **需要重启窗口**：闸门属性在 `AudioFlingerExtImpl` 构造时读一次，开/关各要一次
   `stop/start audioserver`（音频中断 ~1 s）。mask 与 `persist.oplus.mm.pcmdump` 不用重启。
3. **dump 点在流音量之后**：绝对电平随媒体音量变，A/B 两轮必须固定同一音量
   （本次全程 120/160；`vendor-gate.sh` 记录并在 `off` 时还原原值）。
4. **AF40 是 24-bit packed**：分析端必须解 3 字节小端，否则会被当成 float32 读出噪声。
5. **「回退即复现」依赖 ROM 契约**：别的 ROM 若给 per-session chain 的 in/out 是别名
   （`ACCESS_WRITE`），回退 0005 不会有可观测差异——那时判据仍然正确（线程输出两路都在），
   但「回退复现」这一步要在给非别名 buffer 的 ROM 上做。本机不是这种情况（§4.1 已证）。
6. **要产品化**：厂商 dump 是取证手段，不是产品功能。产品侧应有**引擎自己的 thread 级 tap**，
   或第 9 节的「mix 保真自检」断言。

---

## 8. 设备恢复确认（本次结束时）

| 项 | 值 |
|---|---|
| 模块 .so md5 == 挂载点 md5 | `265544f5…` == `265544f5…`（inode 均 579855） |
| `ro.build.version.ota` | `OPD2404_11.F.25_2250_202512102150`（去掉 `_PRE`） |
| `ro.audio.voiplog.on` | 空（`resetprop --delete`） |
| `persist.oplus.mm.pcmdump` | `1`（= 我接手时的值；它是 11:06 AD 实验留下的，建议后续置 0，否则每次播放都会往 `/data/persist_log/TMP/pcm_dump` 写 AF10） |
| mask | `0`；`/data/persist_log/TMP/pcm_dump` 0 个文件 |
| 媒体音量 | `streamVolume:8`（Max 160，= 我接手时的台架值） |
| audioserver / jdspd | 均在跑；mode=`backend`；`james.dsp` 已安装 |
| jdspd config.json md5 | `cb1121f6…`（未变） |
| `jamesdsp_state.bin` | 391702 B（大小未变；内容因 attach/detach 的 ENABLE/DISABLE 记录重写而 md5 变动，属既有行为） |
| 端到端 | 播 5 s 1 kHz：track 正常推完、jdspd attach（session 89 / effectId 43）后正常 release，会话表清空 |
| 闸门功能性关闭 | 关闸门后播 4 s tone：`/data/persist_log/TMP/pcm_dump` **0 个文件**（不是只改了属性） |

> 备注（如实记录）：测试窗口内设备**自行重启过 4 次**（约 12:44 / 13:01 / 13:03 / 13:12，uptime 归零），
> 由用户侧其它模块的重启验证引起（lead 12:45 确认），不是本任务执行的 `adb reboot`；
> 每次重启后都重新核对并恢复了 .so / 属性 / 音量；最后一次重启（13:12）之后又完整复验了一遍上表
> （.so md5、mode、jdspd/audioserver、属性、音量、闸门功能性关闭、端到端放音）。

---

## 9. 加分项草稿：engine「mix 保真自检」计数器

目的：没有厂商 dump 的机器上也能在设备里断言「引擎没有吞掉别人的混音」。

思路（不改 DSP 语义）：

```c
/* 进 process 之前快照 out（共享 mix），累加之后再读一次 */
if (accumulate) {
    memcpy(snap, out->raw, frames * 2 * 4);
    EffectDSPMainRender(in, &renderBuf);            /* 私有 scratch */
    jdsp_accum_add(out->raw, scratch, frames, mode);
    /* 语义自检：out_after 必须 == out_before + rendered（逐样本，允许量化误差） */
    if (!mix_matches(out->raw, snap, scratch, frames, mode)) g_mix_broken++;
}
```

`g_mix_broken` 通过 `EFFECT_CMD_GET_PARAM`（例如 param 30023，沿用 `src-exp` 里
「把 accessMode 暴露成参数」的做法）读出来，即可作为运行时断言：
**计数 > 0 ⇒ 引擎覆盖了别人的混音**。它同时覆盖「覆盖语义回归」与「累加饱和处理写错」。

状态：**草稿已给出并做过离线验证，未上机**（属可选项）。

| 验证项 | 结果 |
|---|---|
| 自检实现 `out/tap-loss/patch-draft/jdsp_mixcheck.c` | float32 / int32 / int16，整型按饱和；packed-24 与 int8_24 不覆盖（保持原语义） |
| host 单测 `test_mixcheck` | **PASSED 10/10**（含「覆盖回归」与「静音实例清空整条 mix」两个真实 bug 形态；写这套测试时还抓出我自己一个 int32 饱和比较的浮点精度 bug） |
| 现有 host 测试（打了草稿的树） | `native/test/run_tests.sh` → **all host tests passed**（含 test_accum / test_paramq / test_state / test_watch / test_writelock / test_async_save） |
| arm64 编译 | 通过，1 679 080 B，md5 `c17f78b1…`（`patch-draft/build/`） |
| 接线 diff | `patch-draft/0008-mix-fidelity-counter.patch`（结构体 + 构造/析构 + process 钩子 + GET_PARAM 30023/30024）+ `0008-build-list.diff` |

---

## 附：本轮新增/发现的关键事实

1. `AF40_ThreadWrite` 在本机确实存在，**mask bit4 = 0x010**，落盘
   `AF40_ThreadWrite_<ioHandle>_2ch_48000Hz_…wav`，格式 **24-bit packed**（AF34/AF35 是 float32）。
2. `persist.oplus.mm.pcmdump=1` 会**吃掉 atlas mask**（只有 AF10 落盘）——必须置 0。
3. 本机 per-session chain 的 **out buffer 就是线程共享 mix**（`AF35_sidA` 同时含两路），
   ⇒ 引擎的 per-instance tap 判不了这个 bug，判据必须落在 `AF40`。
4. 模块 .so 是**同 inode 绑定挂载**，换库只需重启 audioserver，**不需要 reboot**。
5. 回退 0005（`0001–0004` 覆盖语义）在同一 harness 下让 AF40 的 3 kHz **整路消失**，
   覆盖与累加的可观测差异确定。
