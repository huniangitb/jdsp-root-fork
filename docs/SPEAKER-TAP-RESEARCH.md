# 不依赖麦克风读取『送往扬声器的音频数据』——方案研究与 PoC 验证

> 研究任务：task-6（researcher / audio-tap2）。设备：OnePlus OPD2404 / ColorOS 16 / Android 16 (SDK 36) / SM8650 / arm64-v8a，KernelSU root。
> 模块 v2.1.3-fork(213)，backend 模式，jdspd 2.1.3 常驻。样本与证据全部在 `out/tap/` 下，未提交 git、未改动 `native/patches/` 与 `dist/`。
> 时间标注：厂商 dump 采样 **2026-10-31 11:20–11:23**；引擎 tap 真机验证 **11:30–11:34**。
> 设备在 **11:27 左右被本任务以外的人重启过**（11:36 时 uptime 9 min），重启前所有属性已复原并复验，重启后 ro 属性天然回到出厂值。

---

## 0. 结论先行

**推荐两条路，职责不同**

1. **产品化 / 可移植的最终方案 = 引擎内 capture tap**（patch 草稿 `out/tap/patches/0007-engine-capture-tap.patch`）。
   在 `generic_process()` 前后各取一份 PCM：lane0 = 进 effect 前，lane1 = 出 effect 后（就是交还给 AudioFlinger 的那个 buffer）。音频线程只做一次边界检查 + memcpy 进预分配环形缓冲，写 WAV 由独立 writer 线程完成。
   - **bit-exact**：两路与"我们喂进去的 buffer / 框架拿回去的 buffer"逐字节相同（实测 `BIT_IDENTICAL=True`）。
   - **不改 DSP**：tap 武装与否引擎输出完全一致；打了 0001–0007 的 `.so` 与出货 `vendor/lib64/soundfx/libjamesdsp.so` 对同一输入、同一持久化状态**逐字节相同**（max&#124;diff&#124; = 0）。
   - **零 ROM 依赖**：不需要 root 属性、不需要重启 audioserver。
   - **开销**：每 480 帧 block +5~20 µs（≈realtime 的 0.05%~0.2%），内存 2×128 KB 环形 + 64 KB writer scratch，音频线程不加锁、不分配、不 syscall、不打日志。

2. **验收 / 取证工具 = 厂商自带的 `OplusAudioPcmDump`（AF34_EffectIn / AF35_EffectOut）**。
   零代码即可拿到 **effect 前/后两路 48 kHz / 2ch / float32 WAV**；缺点是需要 `resetprop` 打开 pre-OTA 闸门 + 重启一次 audioserver，且绑定 ColorOS 内部实现。**它是工具，不是产品功能**（脚本见 §4.2）。

**麦克风验收（现状）** 从此降级为"端到端可听性冒烟"：它受设备声学链路约 1.6:1 压缩（见 `docs/ACCEPTANCE-COLOROS-v2.1.2.md` §3.4），只能证明"有声音出来、方向单调"，不能证明"送进扬声器的 PCM 正确"。

**框架层全部不可行**（AudioPlaybackCapture / REMOTE_SUBMIX+AudioPolicyMix / dumpsys）；HAL 层（AGM/PAL、tinymix/tinycap、/proc/asound）只能拿到"更下游、且要独占某条 PCM 通路"的信号，覆盖不到 per-session 的 effect 输出。

---

## 1. 背景：为什么必须换掉麦克风

`docs/ACCEPTANCE-COLOROS-v2.1.2.md` §3.4 的记录：把限制器阈值抬到 +40 dB 后，"6 dB 阶跃只测到 3.4 dB、15 dB 测到 9.4 dB、25 dB 仍 9.5 dB" ⇒ 本机"喇叭 → 麦克风"这条**声学**路径本身约 1.6:1 压缩。后果：

- 无法用 mic 测出真实的增益阶跃幅度，验收阈值只能退让（"≥12 dB"只能靠 30 dB 摆幅才勉强到 15.5 dB）；
- 无法区分"引擎处理错了"与"扬声器/采集链路压缩了"；
- 需要安静环境 + 固定机位，无法无人值守重复。

结论：需要一条**电气域、post-DSP、逐样本可复现**的取数路径。本报告验证了两条。

---

## 2. 方案矩阵

| # | 方案 | 拿到的信号级别 | 保真度 | 时延 / 开销 | 工作量 | 依赖 / 风险 | 结论 |
|---|------|----------------|--------|-------------|--------|-------------|------|
| 1 | **引擎 capture tap（0007）** | **effect 入 / effect 出**（per-instance，正是 DSP 的输入输出） | **bit-exact**（32-bit float 原样，无转换） | 音频线程 +5~20 µs/480 帧；内存 ~320 KB；不引入额外音频时延 | 已实现：`jdsp_tap.c/h` ~600 行 + `jamesdsp.c` ~120 行钩子 + 主机测试 | 只依赖我们自己的代码；需编进模块（未提交） | ✅ **推荐（最终方案）** |
| 2 | **厂商 `OplusAudioPcmDump` AF34/AF35** | **effect 入 / effect 出**（AudioFlinger 的 EffectModule buffer） | bit-exact（float32 WAV，44 B 头） | 384 kB/s/lane 落盘；不阻塞 | 零代码 + 两个脚本 | ColorOS 内部实现；需 `resetprop` 改 ro 属性 + 重启 audioserver；dump 点在音量之后；ROM 升级可能失效 | ✅ **推荐（验收/取证工具）** |
| 3 | 厂商 `OplusAudioDump`（AD：AudioChip_*/AudioPop_*/AudioZd_*） | 声称 chip 输出 / 会话级 | — | — | — | 属性可控但**本 ROM 里是死代码**（§3.3） | ❌ 不可行 |
| 4 | `AudioPlaybackCapture` + `MediaProjection` | 应用层"正在播放的音频"（policy mix 的重定向副本） | 16-bit，可能重采样/混音 | 需 MediaProjection 授权弹窗 | 中 | 需要用户授权；仅 USAGE_MEDIA/GAME 且目标 App 未关 `allowAudioPlaybackCapture`；**不是 effect 后信号** | ❌ 不满足要求 |
| 5 | `REMOTE_SUBMIX` + `AudioPolicyMix` | 同上（policy mix 的 capture 端） | 同上 | 同上 | 大 | 需 `CAPTURE_AUDIO_OUTPUT`(signature\|privileged) + `MODIFY_AUDIO_ROUTING`；必须 priv-app + privapp 白名单（本机 XML 里确有该权限条目，机制有效）；ColorOS 校验与升级会被清；**仍非 post-DSP** | ⚠️ 理论可行、成本高、信号级别不对 |
| 6 | `dumpsys media.audio_flinger` / `audio_policy` | 只有状态、track/patch 元数据、effect 描述 | — | — | 零 | 本机 dumpsys 只有 `--hal/--stats/--effects/--memory`，**没有 PCM dump** | ❌ 不可行 |
| 7 | HAL 层：`agmcap` / `tinycap` / `tinymix` / `/proc/asound` | FE/BE PCM（I2S/AGM 图上的某条通路） | 取决于通路（可能 16-bit） | 需独占 PCM 设备，可能与播放冲突 | 中~大 | 本机有 `/vendor/bin/agmcap`、`/system/bin/tinycap`、`tinymix`（364 controls）、`/proc/asound/card0`；但拿的是下游某条 PCM，非 per-session | ⚠️ 只能做旁证 |
| 8 | 麦克风采集（现状） | 声学输出 | **被设备压缩 ~1.6:1**，含房间 | 无 | 已有 | 需安静环境；只能证"可听" | ⚠️ 降级为冒烟 |

---

## 3. 每条方案的真机证据

### 3.1 引擎 capture tap（方案 1）——真机 bit-exact 验证

**做法**：在 `out/tap/engine2`（= 已打 0001–0006 的树 + 0007）里新增 `jdsp_tap.c/h`，并在 `jamesdsp.c` 的 `generic_process()` 外面套一层：

    if (e->tap != NULL) jdsp_tap_capture(e->tap, JDSP_TAP_LANE_IN,  in->raw,  frames);
    rc = generic_process_inner(self, in, out);
    if (e->tap != NULL) jdsp_tap_capture(e->tap, JDSP_TAP_LANE_OUT, out->raw, frames);

`jdsp_tap_capture()` 只做：lane 检查 → 空闲帧数计算 → 必要时推进消费者（丢最旧 + 计数）→ 1~2 次 memcpy → release 语义写 head。**无 malloc / 无 mutex / 无 syscall / 无 log**。writer 线程（4 ms 轮询）把环形排空成 44 字节 canonical WAV，`jdsp_tap_destroy()` 负责 join 并回填 RIFF/data 长度。

**真机验证（不需要 AudioFlinger、不需要重启）**：`out/tap/src/tap_harness.c` 在我们自己的进程里 `dlopen` 这个 `.so` → `EffectCreate` → `EFFECT_CMD_SET_CONFIG`（48 kHz / stereo / float32 / ACCESS_WRITE）→ 喂 10×480 帧 1 kHz 正弦 → 从 tap 取 WAV。设备输出：

    CREATE ok
    UNLOCK rc=0
    SET_CONFIG reply=0
    TAP_CONFIG rc=0 lanes=3 frames=16384 path=./tap-out
    TAP status captured_in=4800 captured_out=4800 dropped_in=0 dropped_out=0 overruns=0 files=0 mode=1 frame_bytes=8 ring=16384
    TAP_OFF rc=0
    RENDER frames=4800 process_us_total=254.5 process_us_per_block=25.45 (0.0530 us/frame)
    RESULT ok

**bit-exact（主机侧逐字节比较）**：

| 文件 | 格式 | 帧数 | 与"喂进去的输入 / 框架拿回的输出"比较 |
|------|------|------|------------------------------------------|
| `samples/tap-harness/tap-out/jdsp-tap-in-*.wav` | fmt tag 3（IEEE float）/ 2ch / 48000 / 32 bit | 4800 | **BIT_IDENTICAL = True**（== `fed-in.raw`） |
| `samples/tap-harness/tap-out/jdsp-tap-out-*.wav` | 同上 | 4800 | **BIT_IDENTICAL = True**（== `ref-out.raw`） |

**能看见 DSP**（同一进程里设 PEQ 陷波 1 kHz / −20 dB / Q=4：param 1300 + 1214 使能）：

| 通道 | 1 kHz | 结论 |
|------|-------|------|
| tap-in | **−6.02 dBFS**（喂的是 −6 dBFS） | 输入原样 |
| tap-out | **−25.65 dBFS** | **−19.63 dB**，与设定的 −20 dB 陷波一致；3 kHz / 500 Hz 处 tap-in 本就没有内容（−188 / −148 dBFS），tap-out 是滤波器底噪 |

**tap 是"透明"的（不改变引擎行为）**：

| 比较 | 结果 |
|------|------|
| `ref-out.raw`（tap 武装） vs `ref-out.raw`（不武装） | **逐字节相同** |
| 我们的 `.so`（0001–0007，不武装 tap） vs 出货 `vendor/lib64/soundfx/libjamesdsp.so` | **逐字节相同**（max&#124;diff&#124; = 0） |
| tap-out 通道 vs 我们自己的 out buffer | **逐字节相同** |

**开销**（同模式背靠背，live-state 重放，480 帧/block）：

| 配置 | process 时间/block | 说明 |
|------|--------------------|------|
| 无 tap | 346.18 µs | 引擎本身（该 live 状态很重） |
| 有 tap | 366.63 µs | **+20.45 µs/block ≈ +0.2% realtime**（block = 10 ms 音频） |
| 轻状态（默认参数）+ tap | 25.45 µs | 观测到 +5~9 µs 区间 |

内存：2 lane × 16384 帧 × 8 B = 256 KB + writer 64 KB scratch ≈ **320 KB/实例**；磁盘 384 kB/s/lane（仅开启文件导出时）。

**接口（已实现，见 §4.1）**：32001 = `jdsp_tap_config_t`（magic / lanes / frames / limit_frames / sample_rate / flags / path），32002 = `jdsp_tap_status_t`（captured / written / dropped / overruns / files…，可自证"没丢帧"）。两个 id 走 effect 参数通道，但**绕过** deferred-param 分类器、不持久化、不受 write-lock 限制。

### 3.2 厂商 `OplusAudioPcmDump`（方案 2）——零代码拿到 effect 前/后 WAV

**反汇编还原的机制**（库副本在 `out/tap/bin/vendorlibs/`，用 `llvm-objdump` + 自写解析脚本 `out/tap/src/*.py`）：

- `libaudioflinger.so` 的 `EffectModule` 处理路径里有两个 `dumpPcm()` 钩子：入参分别是 `EffectModule+0x1b8/0x1c0`（输入 buffer）与 `+0x1f0/0x1f8`（输出 buffer），dump 点名来自 `OplusAudioPcmDump::AF34_Effect_In` / `AF35_Effect_Out`。
- 使能条件：`property_get_bool("ro.audio.asyncdump.support")`（本机 = 1 ✅）且 `getPcmDumpEnableInfo(3)` ⇒ mask 的 **bit3**。
- mask 只能通过 `AudioSystem::setParameters("qcom_atlas_pcm_dump=<mask>")` → `AudioFlingerExtImpl::oplusSetParameters` → `OplusAudioPcmDump::updatePcmDumpEnableInfo(atoi(mask))` 写入。
- **唯一闸门**：`oplusSetParameters` 里 `ldrb w8,[x20,#0x600]; cmp w8,#1; b.ne …`，而 `+0x600` 在构造函数里被写成

      property_get_bool("ro.audio.voiplog.on", 0)   // 本机未设置 ⇒ false
      property_get("ro.build.version.ota", buf, 64)
      strstr(buf, "PRE")                            // 本机 OTA 串没有 "PRE"
      strb w21, [x19, #0x600]

  ⇒ 两个条件都要满足，且都是**只读属性、只在构造时读一次**，所以必须 `resetprop` + 重启 audioserver。

**实测**（`out/tap/evidence/A-unlock-112036.log`）：

    orig: ota=[OPD2404_11.F.25_2250_202512102150] voiplog.on=[]
    == 1. open the gate
    true
    OPD2404_11.F.25_2250_202512102150_PRE
    1
    == 2. restart audioserver
    audioserver  27918     1 ... S audioserver
    == 3. enable mask bit3 (AF34_Effect_In | AF35_Effect_Out)
    uid=0 setParameters("qcom_atlas_pcm_dump=8") -> 0
    -- dump dir during playback:
    -rw-rw-rw-  2780204 ... AF34_EffectIn_sid0_27_2ch_48000Hz_1031_112045.069.wav
    -rw-rw-rw-  2073644 ... AF34_EffectIn_sid3561393_43_2ch_48000Hz_1031_112046.855.wav
    -rw-rw-rw-  2780204 ... AF35_EffectOut_sid0_27_2ch_48000Hz_1031_112045.114.wav
    -rw-rw-rw-  2073644 ... AF35_EffectOut_sid3561393_43_2ch_48000Hz_1031_112046.861.wav

**文件规格**：WAV fmt = tag 3（IEEE float）/ 2ch / 48000 Hz / 32 bit / block align 8 ⇒ **384 kB/s 每路**。sid0 那份 6420524 B = 16.72 s，App 会话那份 5575724 B = 14.52 s。sid0 = **JDSP 挂载的全局链**（backend 模式的全局 effect），App 自己的会话另有一条链。

**判定"dump 点在 effect 之后"的关键实验（双音标记）**：同一份 dump 里先播 1 kHz 6 s、再播 3 kHz 6 s：

| 链 | 1 kHz | 3 kHz |
|----|-------|-------|
| sid0（JDSP 全局链） | in −57.43 → out −51.13 = **+6.29 dB** | in −57.30 → out −49.24 = **+8.06 dB** |
| sid3562457（App 会话链） | **−1.80 dB** | — |
| sid3562465（App 会话链） | — | **−0.30 dB** |

两个频点增益差 **1.77 dB** ⇒ 中间的变换**不是平坦增益而是滤波器**（真 DSP），且只出现在全局链；App 会话链 ≈ 直通（没有 JDSP）。这就是 "AF35 = post-DSP、AF34 = pre-DSP" 的直接证据（`out/tap/src/analyze_twotone.py`，数据 `evidence/A-twotone-gain.json`）。

**注意**：dump 点位于**流音量之后**（本次媒体音量很低 ⇒ in 只有 −57 dBFS），做验收必须**固定媒体音量**，或只比较 in/out 比值。

### 3.3 厂商 AD（`OplusAudioDump`：AudioChip_* / AudioPop_* / AudioZd_*）——不可行

1. 直接实验：`setprop persist.sys.enable_ad_pcmdump 1`（+ `persist.oplus.mm.pcmdump` + `enable_ad_logdump`）后播 15 s tone，logcat 里 `TrackInfo::initFromProperty()` 确实读到了 `pcmdumpEnable 1`：

       10-31 11:06:12.734 D/AFExtImpl( 2310): initFromProperty() 0xb400... , sAdSwitch 1, typeCheck 0, logdumpEnable 1, pcmdumpEnable 1, trackPid 20884

   但 `/data/persist_log/TMP/pcm_dump` 与 `/data/persist_log/DCS/de/audioDetLog` **都没有任何文件**（`evidence/ad-dumpdir-after.txt`、`ad-props-*.txt`）。
2. 反汇编解释：`OplusAudioDump::dumpAsync()` / `dumpSync()` 在 `libaudioflingerextimpl.so` 与 `libaudioflinger.so` 里**没有任何调用点**（只有定义本身）⇒ 本 ROM 的 AudioChip/AudioPop dump 是死代码；AD 子系统只把 `AD_Track_*`/`AD_Thread_*` 这类日志级 dump 挂在检测器上，普通音乐播放不触发。
3. 结论：AD 路线即使属性全开也**落不了盘**，不必再试。

### 3.4 框架层（方案 4/5/6）

- **`AudioPlaybackCapture` + `MediaProjection`**：官方文档说明它捕获"其他 App 正在播放的音频"（[Capture video and audio playback](https://developer.android.com/media/platform/av-capture)），需要 MediaProjection 授权；受 `USAGE_MEDIA/GAME` 与目标 App `allowAudioPlaybackCapture` 限制；拿到的是 policy mix 的**重定向副本**（通常 16-bit，可能重采样），**不是 effect 链输出**，无法用来断言 DSP 输出正确；且需要用户交互授权。→ ❌
- **`REMOTE_SUBMIX` + `AudioPolicyMix`**：本机 policy 里确有 r_submix（`dumpsys media.audio_policy`：`6. Handle: 50; "r_submix"` / `Remote Submix Out` / `Port ID: 28; "Remote Submix In"`）。但 `AudioRecord` 源 `REMOTE_SUBMIX` 需要 `CAPTURE_AUDIO_OUTPUT`（signature\|privileged），注册 policy mix 需要 `MODIFY_AUDIO_ROUTING`（signature）。本机 `/system/etc/permissions/privapp-permissions-{platform,oplus,qti}.xml`、`/system_ext/etc/permissions/*.xml`、`/product/etc/permissions/privapp-permissions-oplus.xml` 里都出现该权限条目（持有者如 `com.microsoft.deviceintegrationservice`、`com.android.soundrecorder`、SystemUI），说明 **priv-app + privapp 白名单在本机确实可行**；但：① 需要把 App 放进 priv-app 并用 overlay 写白名单（模块能做），② ColorOS 的 privapp 校验与每次 ROM 升级都要重验，③ 仍只拿"播放复本"而非 per-session post-DSP。→ ⚠️ 只适合整机内录，不适合本任务。
- **`dumpsys`**：本机 help 只有 `-h --hal --stats --effects --memory -a`，**没有任何 PCM/buffer dump**；`dumpsys media.audio_flinger` 里能看到 `JamesDSP v4.01` + `TYPE: f98765f4-…`，但仅此而已。→ ❌

### 3.5 HAL 层（方案 7）

本机确实有工具与节点：`/vendor/bin/agmcap`（AGM 图采集）、`/system/bin/tinycap`、`/system/bin/tinymix`（`Number of controls: 364`）、`/proc/asound/card0`（`pineapplemtpsnd` / `pineapple-mtp-snd-card`）。但：

- `tinycap`/`agmcap` 抓的是**某条 FE/BE PCM 通路**，要独占设备、要指定 PCM 编号与 AGM graph 参数（`-i/-dkv/-dppkv/...`），与正常播放互斥；
- 拿到的是 **effect 之后、且通常再经 volume/DRC** 的信号，非 per-session，回答不了"这个 App 的 effect 输出是否正确"；
- 需要 root，且容易把音频通路搞挂（本任务禁止 reboot）。

→ ⚠️ 只能作为物理层旁证，不作为验收路径。

---

## 4. PoC 构建与使用

### 4.1 引擎 tap（方案 1）

**构建**

    export NDK=/home/bdy/Android/Sdk/ndk/30.0.16248370
    cd jdsp-repo/out/tap
    ./native/build-tap.sh     # 编译 out/tap/engine2 → out/tap/build/libjamesdsp-arm64-v8a.so

与仓库 `native/build.sh` 的唯一差别：源目录指向 `out/tap/engine2/Main/libjamesdsp/jni/jamesdsp`、多编一个 `jdsp_tap.c`、只出 arm64。

**主机测试**（既有 6 组 + 新增 test_tap）

    cd jdsp-repo/out/tap/native
    SRC=$PWD/../engine2/Main/libjamesdsp/jni/jamesdsp ./test/run_tests.sh
    ...
    == build test_tap (0007 capture tap)
    == test_tap
    PASS all capture tap checks
    == all host tests passed

`test_tap` 覆盖：5 种格式逐字节回读、环形溢出（丢最旧 + 精确计数 + 只有一次 overrun 事件）、`limit_frames` 截断、WAV 头/负载逐字节核对、生产者/消费者并发 192000 帧 0 丢帧、NULL/未知格式/未使能 lane 的 no-op 语义、`jdsp_tap_configure` 的替换/拆除/拒绝路径。

**真机离线验证**

    adb push out/tap/build/libjamesdsp-arm64-v8a.so /data/local/tmp/libjamesdsp-tap.so
    adb push out/tap/build/tap_harness            /data/local/tmp/tap_harness
    adb shell "cd /data/local/tmp && ./tap_harness ./libjamesdsp-tap.so ./tap-out"                    # 纯 1 kHz
    adb shell "cd /data/local/tmp && ./tap_harness ./libjamesdsp-tap.so ./tap-out-notch notch"        # + PEQ 陷波
    adb shell "cd /data/local/tmp && ./tap_harness ./libjamesdsp-tap.so ./tap-out-live livestate -57.4"  # 重放设备当前状态
    python3 out/tap/src/verify_tap.py     # bit-exact 判定
    python3 out/tap/src/verify_notch.py   # 陷波判定

harness 里三个必踩的坑（写给后人）：
1. `AUDIO_CHANNEL_OUT_STEREO` 必须用**引擎自带 `essential.h` 的值 0x3**（不是 AOSP 的 0xC），否则 `SET_CONFIG` 回 −EINVAL；
2. `effect_param_t` 头是 12 字节（status/psize/vsize），**参数 id 在 +12、值在 +16**；
3. 实例默认 write-locked（patch 0002），必须先发 32 字节 key 的解锁命令（id 31232），否则所有 `SET_PARAM` 回 −EPERM；key 通过 `JDSP_WRITELOCK_KEYFILE` 指定。

**接口契约（供 jdspd/App 使用）**

| 参数 | 方向 | 内容 |
|------|------|------|
| `32001` `JDSP_TAP_PARAM_CONFIG` | SET | `jdsp_tap_config_t`：magic("TAP1" 0x31504154) / lanes(bit0=in, bit1=out) / frames(环形帧数, clamp 1024..2^18) / limit_frames(每路采够即停, 0=不限) / sample_rate / flags(bit0=写 WAV) / path[160]（输出目录，空=只进环形） |
| `32002` `JDSP_TAP_PARAM_STATUS` | GET | `jdsp_tap_status_t`：active / mode / sample_rate / frame_bytes / ring_frames + 每路 captured / written / dropped / read_by_caller + overruns / files |

- 关闭：`lanes = 0` 的 config（writer 结束、WAV 头回填、环形保留以便最后一次 read）。
- 非阻塞保证：磁盘慢/满时**丢最旧并计数**，绝不等待音频线程。
- 多实例：目前每实例独立 tap（会在同一目录各写一份）；生产化建议加"全局只允许一个 capture 拥有者"的仲裁。
- 已知边界：文件导出需要目标目录**对 audioserver 可写**（PoC 用 `/data/local/tmp` 是因为 harness 以 root 跑；装进 audioserver 后应改用 `/data/misc/audioserver/` 或模块建的 `/data/vendor/audio/` 子目录）。

### 4.2 厂商 dump（方案 2，验收/取证工具）

    out/tap/tools/vendor-pcmdump.sh status     # 闸门三件套 + dump 目录
    out/tap/tools/vendor-pcmdump.sh on         # resetprop + 重启 audioserver + mask=8
    out/tap/tools/vendor-pcmdump.sh capture 8 ./samples/vendor-1130   # 播 1 kHz、拉回、自动 FFT
    out/tap/tools/vendor-pcmdump.sh off        # mask 0 + 还原 ro 属性 + 重启 audioserver
    out/tap/tools/vendor-pcmdump.sh mask 0x09  # 需要别的 dump 点时改 mask（bit0..bit10，见脚本头）

脚本特性：① 自动记录/固定/还原**媒体音量**（`evidence/vendor-pcmdump.volume.before`，因为 dump 点在音量之后）；② 每次 capture 落到独立目录并带设备时间戳（`device-time.txt`、`dumplist.txt`），天然支持按时间切片（多次 capture 即可，文件名里也有 HHMMSS）；③ `off` 一定还原 `ro.build.version.ota` 并 `resetprop --delete ro.audio.voiplog.on`；④ 抓完自动跑 `analyze_wav.py` 出 in/out 的峰值频率与电平表。

**使用约束（写进验收流程）**
- 必须**固定媒体音量**（脚本会做，并记录原值）；
- 每次 `on/off` 会中断音频 ~1 s（重启 audioserver），要单独申请窗口；
- 每路 384 kB/s，**不要长时间开着**（1 小时两路 ≈ 2.7 GB）；
- ColorOS 专属取证手段，**不是产品功能**，ROM 升级后需重新验证闸门与 dump 点。

### 4.3 两条路线的交叉校验（如实记录）

目标："同一次播放里同时拿两路，比较同一 1 kHz 的 effect-out 增益（±0.1 dB）"。**在不动模块/不重启的前提下做不到**：引擎 tap 只在我们自己加载的 `.so` 里，而设备 audioserver 里跑的是出货 `.so`（把 tap 版装进去 = 重装模块，本任务禁止）。因此做三层替代验证：

1. **语义等价**：tap 的 OUT 通道 = `generic_process()` 交还给 AudioFlinger 的 `out->raw`，**逐字节相同**；厂商 AF35 的 dump 点正是同一个 buffer。两条路取的是同一个对象（由 bit-exact 证明，不依赖数值比较）。
2. **实现等价**：我们打的 0001–0007 构建与**出货 vendor `.so`** 在同输入、同持久化状态下输出**逐字节相同**（`evidence/B-vendor-vs-tap.json`：`vendor_bit_identical: true`，max&#124;diff&#124; = 0）⇒ tap 的引入没有改变引擎行为。
3. **数值对照（两条独立实现、同一状态、同一电平）**：harness 重放设备当前持久化状态（`/data/vendor/audio/jamesdsp_state.bin`）并把输入电平设成与厂商 dump 完全一致的 −57.40 dBFS：
   - 我们的 tap：in −57.40 → out −59.19 dBFS（**−1.79 dB**）
   - 出货 vendor `.so`（不武装 tap）：out −59.19 dBFS（**−1.79 dB**，与 tap 完全一致 ✅）
   - 厂商 dump（设备里真实运行的实例）：in −57.43 → out −51.13 dBFS（**+6.29 dB**）

   前两者吻合到 0.00 dB ⇒ 取数路径没问题；与设备实例差 8.08 dB ⇒ **设备上那个实例的参数与"持久化文件重放"出来的不一样**（live 实例还带着 jdspd 运行时下发的参数/每应用策略，离线重放只有 globalParams；同一份 dump 里 App 会话链是 −1.8 dB 直通、全局链是 +6.3 dB，两条链本来就不同）。⇒ **这个 8 dB 是配置的差，不是取数方法的差**。要做到真正的"同一次播放 ±0.1 dB 对照"，只有一条路：把 tap 版 `.so` 装进模块后，同一次播放里同时开厂商 dump 与 tap（脚本与接口已就绪，等模块排期）。

---

## 5. 对现有麦克风验收方法的替代建议

1. **项 4「参数可听生效」**（现在 mic RMS + 声学 A/B）：改成 tap 的 in/out 传递函数。同一次渲染里两路天然对齐，直接 FFT 求 `20log10(|OUT(f)|/|IN(f)|)`，**不再受 1.6:1 声学压缩影响**，阈值可从"≥12 dB 只能靠 30 dB 摆幅"收紧到 **±0.1 dB**（陷波实测 −19.63 dB vs 设定 −20 dB；实现等价性 0.00 dB）。
2. **项 12「开关毛刺」**：用 tap 断言 bypass/使能切换时 out 是否与 in 逐字节相等（比 mic 静音检测精确到样本级）。
3. **项 9/11（会话、多实例）**：tap 的 `captured/dropped/overruns` 可直接断言"没有丢帧"，比 mic 的 RMS 更硬。
4. **麦克风保留**为最后一道"端到端可听性冒烟"（证明扬声器真的响了），不再承担"信号正确性"举证。
5. **一次性权威取证**：需要"ROM 自己的 dump 说同一句话"时，用 §4.2 的厂商 dump（带 audioserver 重启窗口与固定音量）。

---

## 6. 遗留风险与后续工作

| 风险 | 影响 | 建议 |
|------|------|------|
| 0007 只是**草稿**（未进 `native/patches/`、未提交） | 现在还不能随模块发布 | 评审后合入 patches 链，`build.sh` 增加 `jdsp_tap.c`，把 `test_tap` 并入仓库 `native/test/run_tests.sh` |
| tap 写文件需要 audioserver 可写目录 | PoC 用 `/data/local/tmp`（root） | 生产改 `/data/misc/audioserver/tap/` 或模块 `post-fs-data` 建 `/data/vendor/audio/tap` 并 chown |
| 多实例各写各的文件 | 多 effect 同时武装会产生重复/混淆的 WAV | 加"全局 capture 仲裁"（首个武装者胜出，其余回 −EBUSY） |
| 环形容量 vs 长时间抓取 | 抓太久丢最旧（有计数、不静默） | 用 `limit_frames` 限制时长；script 侧限时 |
| 厂商 dump 依赖 ROM 内部实现 | ROM 升级后闸门/dump 点可能变 | 脚本先跑 `status`；升级后重跑 §3.2 的反汇编核对 |
| 厂商 dump 改 `ro.build.version.ota` + 重启 audioserver | 全局可见、可能影响 OTA 逻辑；音频中断 ~1 s | 脚本强制还原并复验；只在取证窗口使用；不要做成常驻 |
| dump 点位于音量之后 | 绝对电平随音量变化 | 固定媒体音量（脚本已实现） |
| 设备在 11:27 被第三方重启 | 本文采样均标注时间；重启后属性天然归零 | 引用数据时注意时间窗 |

---

## 7. 产物清单

    out/tap/patches/0007-engine-capture-tap.patch   # patch 草稿（patch -p1 于源根；已验证可干净应用并复现 engine2）
    out/tap/engine2/                                # 打了 0001-0007 的引擎树（含 jdsp_tap.c/h）
    out/tap/native/build-tap.sh                     # arm64 构建（NDK r30）
    out/tap/native/test/test_tap.c                  # 新增主机测试（run_tests.sh 全绿）
    out/tap/src/tap_harness.c                       # 真机离线 harness（bitexact / notch / livestate）
    out/tap/src/verify_tap.py, verify_notch.py, analyze_wav.py, analyze_twotone.py
    out/tap/src/A-unlock-afdump.sh                  # 厂商 dump 的一次性解锁实验脚本（原始记录）
    out/tap/tools/vendor-pcmdump.sh                 # 厂商 dump 可复用验收工具（on/off/mask/capture/status）
    out/tap/src/Tone1k.java (+ tone1k.dex)          # 1 kHz 测试音（仓库 Tone.java 是 440 Hz）
    out/tap/bin/pcmdump, out/tap/bin/vendorlibs/    # setParameters 小工具 + 反汇编用厂商库副本
    out/tap/samples/                                # 厂商 dump WAV（6 个）+ harness 三组样本
    out/tap/evidence/                               # 属性前后快照、logcat、dump 列表、FFT/JSON 结果

脚本已随仓库提供：tools/vendor-pcmdump.sh
