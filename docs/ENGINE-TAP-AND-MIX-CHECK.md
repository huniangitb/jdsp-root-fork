# 引擎采集 tap（0007）与 mix 保真自检（0008）

> 状态：已合入 native/patches/，两个补丁**默认关闭**，未武装时对 DSP 输出零影响。
> 真机验证：OnePlus OPD2404 / ColorOS 16 / Android 16 / 2026-10-31。

---

## 1. 为什么要有这两样东西

| 痛点 | 补丁 | 解决 |
|---|---|---|
| 验收只能靠麦克风录（压缩比 ~1.6:1 + 房间声），证不出「这段 PCM 真的到了 HAL」 | **0007 capture tap** | 把引擎交还 AudioFlinger 的样本直接落 WAV（IN/OUT 两路，同一次渲染调用内取，帧对齐） |
| 「同输出第二会话无声」（0005 修的 bug）只有厂商 AF40 dump 看得见，别的 ROM 没这手段 | **0008 mix 保真自检** | 在 accumulate 路径上断言 out_after == out_before + rendered，计数 > 0 就是「引擎吞了别人的混音」 |

两个都是**诊断设施**，不是产品功能：默认关，开了也不改 DSP 语义（见 §4、§7.2）。

---

## 2. 接口

### 2.1 采集 tap（0007，新增 jdsp_tap.c/h）

| 参数 | 方向 | 内容 |
|---|---|---|
| 32001 | SET | jdsp_tap_config_t（192 B）：magic "TAP1"(0x31504154) / lanes(bit0=IN, bit1=OUT) / frames(环形帧数, clamp 1024..2^18) / limit_frames(0=不限) / sample_rate / flags(bit0=写 WAV) / reserved[2] / **path[160]** |
| 32002 | GET | jdsp_tap_status_t（112 B）：active / mode / sample_rate / frame_bytes / ring_frames + 每路 captured / written / dropped / read_by_caller + overruns / files |

- lane0 = effect 输入（AudioFlinger 给我们混的），lane1 = generic_process() 交回的 out buffer
  （= 厂商 AF35 dump 的同一个对象，逐字节相同）。
- 渲染线程只做「边界检查 + memcpy 进预分配环形」；writer 线程排空成 44 字节头 WAV；
  环形满丢最旧并计数，**绝不阻塞音频线程**。
- 关：发一个 lanes = 0 的同款 config（writer join、WAV 头回填、环形保留）。

### 2.2 mix 保真自检（0008，新增 jdsp_mixcheck.c/h）

| 参数 | 方向 | 含义 |
|---|---|---|
| 30023 | GET | **broken**：覆盖了别人混音的 buffer 数（正常必须 0） |
| 30024 | GET | **runs**：已检查的 buffer 数（正常应持续增长） |
| 30025 | SET | 武装(1)/解除(0) |
| 30026 | SET | 清零计数 |
| 30027 | GET | 当前是否武装 |
| 30028 | GET | 上一次不一致的样本数 |

- 只在 **accumulate 路径**（框架给 EFFECT_BUFFER_ACCESS_ACCUMULATE，即 in/out 不别名）上生效；
  别名缓冲（ACCESS_WRITE）没有共享 mix 可保护，不做检查。
- 覆盖 float32 / int32 / int16（整型按饱和）；packed-24 与 int8_24 不覆盖。
- 计数是**进程级**的：audioserver 里任何实例造成损坏都会加进来（验收想要的语义）。

---

## 3. 武装方法

两个补丁的参数都在 generic_command 的**写锁 gate 之前**处理：不需要 engine key、
不进延迟参数队列、不写 state.bin、不参与 DSP。

jdspcli 的 --hex/--int/--short/--raw 是**取值选项**（--x VALUE），写在命令后面：

```bash
CLI="sh /data/local/tmp/jdspcli.sh"
SID=<session>          # jdspcli sessions 里那一行

# 1) 武装 tap：192 字节 config 的十六进制（path[160] 必须补零到 160）
$CLI set $SID 32001 --raw 5441503103000000004000000000000080bb00000100000000…（共 384 hex 字符）
# 2) 读 tap 状态（vsize 必须是 112，否则引擎只回长度不写数据）
$CLI get $SID 32002 112
# 3) 解除 tap（lanes=0）
$CLI set $SID 32001 --raw 5441503100000000004000000000000080bb0000…

# mix 自检
$CLI set $SID 30025 --int 1     # 武装
$CLI set $SID 30026 --int 1     # 清零
$CLI get $SID 30024 4           # runs
$CLI get $SID 30023 4           # broken
$CLI set $SID 30025 --int 0     # 解除
```

> **两个坑（实测踩过）**
> 1. config 的 path[160] 不补齐就不是 192 字节，jdsp_tap_configure 会拒绝（rc=-1）。
> 2. tap 的 WAV 要写进 **audioserver 可写**的目录：/data/vendor/audio/tap
>    （chown 1041:1005、chmod 770，SELinux 继承 vendor_audio_data_file）。
>
> 另外：本机 jdspd 2.1.4 的 jdspcli get 对**任何**参数都返回 no data
> （param 20000 也一样，与这两个补丁无关：in-process 走同一 handler 的 GET 是好的）。
> 所以上表里 get 只能作为接口说明；**读计数请用 in-process 探针**（§6.3）或等 jdspd 修 GET。

---

## 4. 默认关闭保证（零行为改变）

| 位置 | 未武装时的行为 |
|---|---|
| generic_process() | 每路一个 e->tap != NULL 判断（0007）/ 一次 relaxed 原子读 + 一个分支（0008） |
| 分配 | 无。tap 的环形/线程只在 32001 成功时创建；mix 自检的快照缓冲**首次武装时**才按实例惰性分配，随实例释放 |
| memcpy | 无 |
| 日志 / 文件 | 无（tap 的文件只在 flags bit0 且武装时开；自检不打日志） |
| 参数/状态 | 诊断 id 不进 state.bin、不进延迟队列、不被写锁拦（也不解别人的锁） |

**逐字节证据**（设备上 tap_harness，同输入 + 同持久化状态，tap 未武装）：

```
0001–0006 出货 .so  ref-out.raw md5 89f89ed362bcd14be217f91b5e708dde
0001–0008 新   .so  ref-out.raw md5 89f89ed362bcd14be217f91b5e708dde   <- 相同
cmp: 无差异（fed-in.raw 也相同）
唯一输出差别：TAP_CONFIG / TAP_OFF 从 rc=-1（没有这个接口）变成 rc=0
```

---

## 5. 开销

| 场景 | 实测 |
|---|---|
| 未武装 | 与 0001–0006 **逐字节相同**，process 耗时差异在噪声内（同机 321–357 µs/块 vs 332–357 µs/块） |
| mix 自检武装 | 每 buffer 一次快照 memcpy + 一趟比较：**4800 帧 float 比较 5.7 µs**（host 微基准），相对引擎 ~330 µs/块 ≈ **1.7%**；480 帧块约 0.6 µs |
| tap 武装 | 渲染线程多 1–2 次 memcpy（每帧 4–8 B 到环形）；writer 线程每 4 ms 排空一次（空则睡）；磁盘慢/满只会丢帧计数，不阻塞音频 |

---

## 6. 测试方法

### 6.1 主机单测（native/test/run_tests.sh，8 组全绿）

```
test_state ×2  test_watch  test_writelock  test_paramq  test_accum  test_async_save
test_tap       五种格式逐字节回读 / 环形溢出策略 / limit_frames / WAV 头 / 并发生产者消费者 / NULL no-op
test_mixcheck  覆盖回归、静音实例清空整条 mix、int16/int32 饱和、arm/reset/status、未武装默认值
```

### 6.2 补丁链可复现

```bash
cp -a <0001..0006 源码> /tmp/verify-chain
patch -p1 -d /tmp/verify-chain < native/patches/0007-engine-capture-tap.patch
patch -p1 -d /tmp/verify-chain < native/patches/0008-engine-mix-integrity-check.patch
diff -r /tmp/verify-chain/Main/libjamesdsp/jni/jamesdsp <构建用源码>   # 无差异
```

### 6.3 真机 in-process 探针（out/tap/src/mixcheck_probe.c）

```bash
adb push mixcheck_probe /data/local/tmp/tapcheck/ && adb shell chmod 755 …
./mixcheck_probe ./libjamesdsp.so ./out            # 未武装
./mixcheck_probe ./libjamesdsp.so ./out-on arm     # 武装
```

探针把 out 配成 ACCESS_ACCUMULATE + 独立缓冲，并**预填 3 kHz「别人的会话」**，
然后 100 块 1 kHz 让引擎累加进去 —— 正是 0005 契约的运行形态。

### 6.4 验收一键

```bash
NDK=<ndk-r30> SRC=<打了 0001–0008 的树> ./native/build.sh
SRC=<…> ./native/test/run_tests.sh
# 设备：替换 module .so → stop/start audioserver → 播 tone → 按 §3 武装 → 收 WAV/读计数
```

---

## 7. 真机证据（2026-10-31，OnePlus OPD2404）

安装：module .so 与 /vendor/lib64/soundfx/libjamesdsp.so 同 inode 绑定挂载
（inode 585620），原地替换 + stop/start audioserver 生效，**未 reboot**。

### 7.1 tap：1 kHz 陷波可见

会话 sid=937 播 1 kHz，武装 tap 后 sid 1300 设 PEQ 1 kHz / −20 dB / Q=4 并 1214 使能：

```
IN   jdsp-tap-in-48000Hz-m1-…wav    1kHz = -70.19 dBFS   rms = -73.20
OUT  jdsp-tap-out-48000Hz-m1-…wav   1kHz = -91.93 dBFS   rms = -94.85
gain out-in @1kHz = -21.74 dB      （期望 ≈ -20 dB 陷波 ✅）
3 kHz 两路都在 -150 dBFS 以下（本底，无可比增益）
```

落盘：/data/vendor/audio/tap/jdsp-tap-{in,out}-48000Hz-m1-<ts>-<pid>.wav（float32 立体声），
解除武装后文件头回填、大小从 2.88 MB 长到 3.18 MB（writer join + flush 正常）。
样本已拉回 out/tap/evidence/live-tap/。

### 7.2 mix 自检：正常播放计数 0

```
未武装：  armed=0  runs=0     broken=0
武装：    armed=1  runs=99    broken=0    last_bad=0
两种模式下 ref-out.raw md5 都是 e70adb3c…（武装检查不改音频）
```

runs=99/100：第 1 块发生在 30025 生效之前，符合预期。

### 7.3 设备收尾

| 项 | 值 |
|---|---|
| 设备上的 .so | 658aff86ad4ea7ff6baa5240a60c049b（= module/src/common/files/arm64/，inode 585620，模块与挂载点一致） |
| state.bin | 恢复为测试前备份，md5 7998d1da…，空闲 3 s 后不变 |
| 参数基线 | jdspcli baseline → applied=26 |
| tap 目录 | WAV 已清扫（样本已拉回 out/tap/evidence/live-tap/） |
| 其它 | mode=backend、audioserver/jdspd 正常、voiplog 空、ota 原值、dump 目录 0 文件、音量 8 |

---

## 8. 局限

1. tap 落盘目录必须对 audioserver 可写；生产化建议模块安装时就建好 …/audio/tap 并给对属主。
2. 每个实例一个 tap：多个会话同时武装会在同一目录各写一份（文件名含 pid/时间戳，可区分）。
3. mix 自检只覆盖 accumulate 路径（float/int32/int16）；别名缓冲没有共享 mix 可保护，属于「检查不到」而不是「通过」。
4. 计数是进程级聚合，不区分实例；需要按实例定位时配合 0007 的 tap 一起用。
5. 本机 jdspcli get 不通（见 §3 注），读计数需要 in-process 探针或修 jdspd 的 GET 反射。
6. 两个补丁都不改变 DSP 语义，但它们**会**在武装时占用少量 CPU/磁盘（见 §5）；验收完请解除武装。
