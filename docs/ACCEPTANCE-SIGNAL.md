# ACCEPTANCE-SIGNAL.md — 音效按参数设定真实作用于音频信号

状态：**PASS**（signal-delta / signal-direction / signal-restore 三项由 SKIP 变为可确证 PASS）

- 判据原文：「音效按参数设定真实作用于音频信号」
- 设备：`64a4bad0`（OnePlus OPD2404 / ColorOS 16 / Android 16 / SDK 36，root KernelSU）
- 引擎：`/vendor/lib64/soundfx/libjamesdsp.so`（JamesDSP v4.01）
- 引擎源码：`/home/bdy/work/jdsp-state/src/Main/libjamesdsp/jni/jamesdsp/`
- 验收脚本：`scripts/verify-signal.sh`（`--serial 64a4bad0`）

---

## 1. 为什么原来是 SKIP

上一轮的结论是「没有可用的采集通路」：

| 采集通路 | 实测 | 结论 |
| --- | --- | --- |
| AudioRecord(REMOTE_SUBMIX) | 可初始化，但输出全零：`rmsDbfs=-200.00 nonZero=0 samples=15360` | 不可用 |
| 麦克风 | 同一参数下抖动 > 4 dB；且出现 `postGain +12 dB` 后电平反而 `-1.02 dB` 的反向结果 | 不可作判据 |

于是 `signal-delta` / `signal-direction` / `signal-restore` 全部 SKIP。

**离线渲染器是唯一能给出确定性数字的通路**，所以判据的成败完全取决于它能否工作。

## 2. 离线渲染器之前为什么崩溃

旧版 `tools/native/offline_render.c` 有**两个独立的错误**，第二个是致命的：

### 2.1 错误的 vtable 成员顺序（致命）

旧代码假定 vtable 是 `{ get, set, process, release, get_descriptor }`。
引擎实际导出的是 AOSP `effect_interface_s`（见 `essential.h` 第 238 行起）：

```c
struct effect_interface_s {
    int32_t (*process)(effect_handle_t self, audio_buffer_t *inBuffer, audio_buffer_t *outBuffer);
    int32_t (*command)(effect_handle_t self, uint32_t cmdCode, uint32_t cmdSize,
                       void *pCmdData, uint32_t *replySize, void *pReplyData);
    int32_t (*get_descriptor)(effect_handle_t self, effect_descriptor_t *pDescriptor);
    int32_t (*process_reverse)(...);
};
```

旧代码把 `process` 当成第 3 个成员（实际是第 1 个）、把 `command` 当成第 2 个（实际是第 2 个但参数顺序不同），
于是在 `EFFECT_CMD_SET_CONFIG` 时调用的是完全错误的函数指针。

### 2.2 臆造的 `effect_config_t` 布局（致命）

旧代码用了自己编的 `effect_buffer_config_t`（`bufferCount/frameCount/channelMask/sampleRate/format/dataOffset/frameSize`），
而引擎读的是 AOSP 的 `buffer_config_t`（`essential.h` 第 754 行）：

```c
typedef struct buffer_config_s {
    audio_buffer_t    buffer;          // { size_t frameCount; union { ... }; }
    uint32_t         samplingRate;
    uint32_t         channels;
    buffer_provider_t bufferProvider;
    uint8_t          format;
    uint8_t          accessMode;
    uint16_t         mask;
} buffer_config_t;

typedef struct effect_config_s {
    buffer_config_t inputCfg;
    buffer_config_t outputCfg;
} effect_config_t;
```

引擎的 `configure()`（`jamesdsp.c` 第 124 行）按这个布局读 `in.samplingRate`：
旧布局的第 4 个 `uint32` 落在 `outputCfg` 区域，读到的是垃圾值 →
`JamesDSPSetSampleRate()` 用这个垃圾采样率重建缓冲区 → 巨量 `malloc` → **被 OOM killer 杀掉（`<- Killed`）**。

这正是之前观察到的「`EffectCreate` rc=0，打印 handle/vtable 成功，`SET_CONFIG` 之后被 Killed」。

### 2.3 正确布局（已在设备上实测确认）

用 aarch64 编译器在设备上实测：

| 项 | 字节数 | 偏移 |
| --- | --- | --- |
| `audio_buffer_t` | 16 | — |
| `buffer_config_t` | 56 | `samplingRate`=16, `channels`=20, `bufferProvider`=24, `format`=48, `accessMode`=49, `mask`=50 |
| `effect_config_t` | 112 | `inputCfg`=0, `outputCfg`=56 |

这些常量现在以 `typedef char layout_...[...]` 的形式写进 `offline_render.c`，
布局一旦不符就是**编译期错误**，不会再变成设备上的段错误。

## 3. 修正后的流程

```
dlopen(libjamesdsp.so)
  → EffectCreate(uuid, 0, 0, &handle)        rc=0
  → itfe = *(struct effect_interface_s **)handle
  → command(SET_CONFIG=1, sizeof(effect_config_t)=112, &config)
        inputCfg/outputCfg: samplingRate=48000, channels=3(stereo),
        format=1(PCM_16), accessMode=0(WRITE), mask=0x3F(ALL),
        buffer.frameCount=4800
  → command(INIT=0)                            rc=0
  → command(ENABLE=3)                          rc=0
  → command(SET_PARAM=5, {status,psize=4,vsize=12,data=[id, f0,f1,f2]})
  → process(in, out)                          rc=0
```

`SET_PARAM` 的字节布局（引擎第 708–732 行）：
```c
if (cep->psize == 4 && cep->vsize == 12) {
    int32_t cmd = ((int32_t *)cep)[3];       // data[0..3]  = 参数 id
    if (cmd == 1500) {
        double limThreshold = ((float*)cep)[4];  // data[4..7]
        double limRelease   = ((float*)cep)[5];  // data[8..11]
        double postgain     = ((float*)cep)[6];  // data[12..15]
        JLimiterSetCoefficients(...); JamesDSPSetPostGain(...);
```

参数 1500 = 3 个 float：`limThreshold / limRelease / postGain`（dB）。

## 4. 两个必须处理的工程问题

### 4.1 引擎自带限幅器会吃掉增益 → 测试音必须足够低

引擎输出级永远挂着一个 brickwall 限幅器（`jdspController.c` 第 359–377 行），
阈值被钳位在 `-0.09 dBFS`（`jamesdsp.c` 第 716 行）。因此：

| 测试音 | 中性 | +12 dB | 实际 Δ | 说明 |
| --- | --- | --- | --- | --- |
| -6 dBFS | -9.01 | -2.61 | **+6.40 dB** | 被限幅器压缩，增益不可见 |
| -12 dBFS | -15.01 | -3.08 | **+11.93 dB** | 接近，但仍受轻微限幅影响 |
| **-18 dBFS** | **-21.01** | **-9.01** | **+12.00 dB** | 限幅器透明，**精确等于请求的增益** |
| -24 dBFS | -27.01 | -15.01 | +12.00 dB | 同样精确 |

因此离线渲染器默认测试音为 **-18 dBFS**（`OFFLINE_TONE_DBFS`，脚本可覆盖）。
任务书里写的 -6 dBFS 在这个引擎上测不出 +12 dB —— 不是实现问题，是限幅器的物理行为。

### 4.2 引擎会持久化参数并抢占调试 socket → 必须隔离

引擎把每个生效的参数写进 `/data/vendor/audio/jamesdsp_state.bin`，
并在 `EffectCreate` 时把该文件回放到新实例。对测量来说有两个危害：
(a) 引擎会从**用户已保存的设置**启动，而不是本测试需要的中性基线；
(b) 测试值会**覆盖用户的真实设置**。

同时引擎会在 `/data/vendor/audio/jdsp_ctl.sock` 绑定调试 socket，抢占运行中音频栈的路径。

渲染器的处理（`isolate_state()`）：
1. `setenv("JDSP_STATE_DISABLE", "1")` —— 在 `dlopen` **之前**设置，关闭持久化与回放；
2. `unshare(CLONE_NEWNS)` + `mount(NULL, "/", NULL, MS_REC|MS_PRIVATE, NULL)` + `mount("tmpfs", "/data/vendor/audio", ...)`
   —— 私有 mount namespace，**先做 MS_PRIVATE 再挂载**，所以 tmpfs 不会泄漏到宿主 namespace。

> **注**：调试过程中我曾手动执行 `unshare -m sh -c 'mount -t tmpfs tmpfs /data/vendor/audio'`，
> 该命令**缺少 MS_PRIVATE**，导致 tmpfs 泄漏进设备全局 mount namespace，
> 遮蔽了 `/data/vendor/audio`。已用 `umount /data/vendor/audio` 还原，
> `jamesdsp_state.bin`（14578 字节，原始 mtime）完整恢复。渲染器自身的隔离路径是正确的（已加 MS_PRIVATE）。

验证：渲染器连续运行 3 次后，宿主 `/data/vendor/audio` 的 `md5sum` 不变、目录内容不变、`/proc/mounts` 中无 `vendor/audio` 条目。

## 5. 原始输出

### 5.1 渲染器单次运行

```
$ adb -s 64a4bad0 shell "cd /data/local/tmp/jdsp-signal; \
    LD_LIBRARY_PATH=/data/local/tmp/jdsp-signal ./offline_render 1500 0 0 12"
offline_render: private mount namespace, /data/vendor/audio isolated
offline_render: dlopen(/data/local/tmp/jdsp-signal/libjamesdsp.so) ok
offline_render: EffectCreate rc=0 handle=0xb400007dc45ef000
offline_render: vtable=0x7d4080bfc8 process=0x7d4074b294 command=0x7d4074b4dc desc=0x7d4074b5a4
offline_render: command 1 rc=0 reply=0
offline_render: command 0 rc=0 reply=0
offline_render: command 3 rc=0 reply=0
offline_render: command 5 rc=0 reply=0
offline_render: process rc=0 frames=4800
offline_render: command 5 rc=0 reply=0
offline_render: process rc=0 frames=4800
offline_render: command 5 rc=0 reply=0
offline_render: process rc=0 frames=4800
NEUTRAL rms=2916.431570
TESTED rms=11610.518347
RESTORED rms=2916.431570
NEUTRAL_DBFS=-21.01
TESTED_DBFS=-9.01
RESTORED_DBFS=-21.01
TONE_DBFS=-18.00 FRAMES=4800 PARAM=1500
RESULT ok
```

（`RESTORED_DBFS=-21.01` 与 `NEUTRAL_DBFS` 完全一致：恢复后精确回到基线。）

### 5.2 物理一致性校验

中性时的输出 RMS 应当等于纯 440 Hz 正弦的理论值 `A/√2`：

| 量 | 理论值 | 实测值 | 比值 |
| --- | --- | --- | --- |
| 中性 RMS (-18 dBFS) | 2916.900959 | 2916.431570 | 0.999839 |
| 测试 RMS (+12 dB) | 11612.391877 | 11610.518347 | 0.999839 |

两者的比值完全相同（差异只是 4800 点正弦的采样残差），
说明引擎在中性时是**位透明**的（没有偷偷加增益），+12 dB 也**精确**是 +12 dB。
连续 3 次运行输出完全一致（确定性）。

### 5.3 完整验收脚本

```
$ JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./scripts/verify-signal.sh --serial 64a4bad0

=== build test binaries ===
test dex    : /data/local/tmp/jdsp-signal/tone.dex, jdspcli.dex, signalprobe.dex
offline    : /data/local/tmp/jdsp-signal/offline_render (+ libjamesdsp.so copy)

=== global switch ===
enabled     : on

=== session ===
session     : 177

=== transport consistency (binder vs tcp) ===
binder      : session= attached=true effect=427 params= mode=0

=== capture capability ===
    PROBE start source=remote
    PROBE minBufferBytes=7680
    PROBE state=1
    PROBE recordingState=3
    PROBE readBytes=30720
    PROBE rmsDbfs=-200.00 peakDbfs=-200.00 nonZero=0 samples=15360
    PROBE result=unavailable reason=all_zero
plan        : offline
original    : param 1500 = cdccccbd0000704200004040
=== neutral vs significant ===
delta       : 12.00 dB (increase)
restored    : rms=-21.01 dBFS (baseline -21.01 dBFS, delta 0.00 dB)

=== log hygiene ===

=== binder side effect ===
stats       : setParamCalls=495 paramErrors=5 avgLatencyUs=1210

RESULT ITEM                   DETAIL
PASS   global-enabled         global switch already on
PASS   session-attached       session=177 attached=true (binder)
PASS   transport-sessions     sessionId= attached=true effectId=427 paramCount= (binder == tcp)
PASS   transport-config       globalParams identical (binder == tcp)
PASS   transport-mode         mode=0 (binder == tcp)
PASS   capture-available      REMOTE_SUBMIX is unusable; using the offline renderer (plan=offline)
PASS   signal-delta           postGain +12 dB changed the level by 12.00 dB (> 1.5 dB, increase)
PASS   signal-direction       postGain up -> RMS up (-21.01 -> -9.01 dBFS)
PASS   signal-restore         level back at the baseline (-21.01 vs -21.01 dBFS, 0.00 dB)
PASS   log-crash              no crash / ANR in the capture window
PASS   no-underrun            dumpsys underrun counters are zero
PASS   binder-setparam        stats.setParamCalls=495 (the daemon applied the parameters)
PASS   binder-latency         stats.avgLatencyUs=1210 (< 20 ms)

summary: 13 passed, 0 failed, 0 skipped
restored    : param 1500 = cdccccbd0000704200004040 (verified)
```

## 6. PASS/FAIL/SKIP 表

| # | 判据 | 之前 | 现在 | 证据 |
| --- | --- | --- | --- | --- |
| 1 | **signal-delta**：参数改变后电平变化 > 1.5 dB | SKIP | **PASS** | Δ = 12.00 dB (> 1.5 dB) |
| 2 | **signal-direction**：增益↑ → RMS↑（方向正确） | SKIP | **PASS** | -21.01 → -9.01 dBFS（increase） |
| 3 | **signal-restore**：恢复中性后回到基线（≤ 0.5 dB） | SKIP | **PASS** | 0.00 dB 偏差（基线 -21.01，恢复 -21.01） |
| 4 | capture-available：存在可用采集通路 | SKIP | PASS | 离线渲染器可用（plan=offline） |
| 5 | log-crash：测量窗口内无崩溃/ANR | — | PASS | 0 行崩溃 |
| 6 | no-underrun：dumpsys underrun 计数为零 | — | PASS | partial=0 empty=0 |

**成功标准核对**
- 显著与中性 RMS 差 > 1.5 dB → 实测 **12.00 dB** ✅
- 方向正确（增益↑→RMS↑）→ 实测 **increase** ✅
- 恢复后与首次中性差 ≤ 0.5 dB → 实测 **0.00 dB** ✅

## 7. 改动清单

| 文件 | 改动 |
| --- | --- |
| `tools/native/offline_render.c` | 重写：正确的 `effect_interface_s` vtable 顺序、正确的 `effect_config_t/buffer_config_t/audio_buffer_t` 布局（含编译期布局断言）、参数解析修正为恰好 3 个 value float、stdout 隔离以屏蔽引擎的杂散输出、`JDSP_STATE_DISABLE=1` + 私有 mount namespace 隔离、测试音默认 -18 dBFS、额外打印 `*_DBFS` 行 |
| `tools/native/offline_render.cpp` | 旧的 ABI 逆向版本，替换为指向 `offline_render.c` 的说明注释（保留文件避免破坏引用） |
| `scripts/verify-signal.sh` | `measure_offline` 改读 `*_DBFS` 行并回退到线性 RMS；新增 `OFFLINE_TONE_DBFS`（默认 -18）；离线分支改用真实的 `$GAIN_DB` 而非硬编码 12.0；`capture-available` 增加「渲染器存在但不可执行」分支 |
| `docs/ACCEPTANCE-SIGNAL.md` | 本文档 |

未改动：`backend/`（仅 `device.sh` 里已有的构建函数被脚本复用，未修改）、`app/`、`module/`、`dist/`。
未执行 git commit。未安装/卸载/重启设备，未改动模块。

## 8. 复现命令

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
cd /home/bdy/work/jdsp-repo

# 单次渲染（直接在设备上手动验证）
adb -s 64a4bad0 shell "cd /data/local/tmp/jdsp-signal; \
  LD_LIBRARY_PATH=/data/local/tmp/jdsp-signal ./offline_render 1500 0 0 12"

# 完整验收
./scripts/verify-signal.sh --serial 64a4bad0
```

覆盖项：`OFFLINE_TONE_DBFS`（测试音）、`GAIN_DB`（增益）、`DELTA_MIN_DB`、`RESTORE_TOL_DB`。
例如 `GAIN_DB=6 ./scripts/verify-signal.sh --serial 64a4bad0` 应得到 Δ = 6.00 dB。
