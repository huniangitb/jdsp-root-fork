# 引擎改动（native）

上游：`evoeram/JamesDSPManager` 分支 `extensions`（本模块的 fork 引擎源码）。
本目录只放**补丁 + 构建脚本 + 主机单测**，不放整棵上游源码。

## 补丁内容

| 补丁 | 主题 | 默认状态 |
|---|---|---|
| `0001-state-persistence-and-hot-reload` | 状态持久化 + inotify 热加载 + 后台重建/原子切换 | 常开 |
| `0002-engine-write-lock` | 调用方写保护（只有 jdspd 能写参数） | 常开 |
| `0003-deferred-param-apply-and-no-cross-instance-rebuild` | SET_PARAM 延迟到音频线程 + 消除跨实例重建风暴 | 常开 |
| `0004-dump-async-state-and-peq-heap-scratch` | DUMP 分支、禁用态返回 0、PEQ/loudness 堆缓冲、state 异步落盘 | 常开 |
| `0005-engine-accumulate-out` | 遵守 `EFFECT_BUFFER_ACCESS_ACCUMULATE`（修同输出第二会话静音） | 常开 |
| `0006-async-effect-create-passthrough-priming` | EffectCreate 异步构建 + 直通实例交叉淡化换入 | 常开 |
| `0007-engine-capture-tap` | 采集 tap：IN/OUT 两路 WAV（32001/32002） | **默认关**（未武装零开销） |
| `0008-engine-mix-integrity-check` | mix 保真自检：断言 `out_after == out_before + rendered`（30023–30028） | **默认关**（未武装零开销） |
0004–0006 的细节见 `module/src/changelog.md`（v2.1.0 段），0007/0008 见下。

`patches/0001-state-persistence-and-hot-reload.patch`（相对 `src-baseline`，即仅做了 NDK30 编译兼容修复的上游源码）：

1. **状态持久化** `jdsp_state.c/h`
   - 记录每个成功的 `SET_PARAM` / `ENABLE` / `DISABLE`（带 effect_param_t 头，重放时原样喂回同一处理函数）
   - 原子落盘（tmp + fsync + rename），CRC32 校验，损坏文件静默丢弃
   - 文件格式：`"JDSP" + u32 version + u32 flags + u32 count + {u32 cmd,u32 size,bytes}* + u32 crc32`
2. **inotify 热加载**（单 fd `poll()`，不用 epoll）
   - 监听状态文件所在目录，`IN_CLOSE_WRITE|IN_MOVED_TO|IN_CREATE`
   - 事件到达时在 watcher 线程读取解析，渲染线程不碰文件系统
   - 兜底：inotify 不可用时回到 stat 轮询；`JDSP_STATE_NO_INOTIFY=1` 可强制轮询
3. **后台重建 + 原子切换**（`jamesdsp.c`）
   - builder 线程每 20 ms 检查代次（含 20 ms 突发去抖）
   - 用新参数构造**完整新引擎**，全部应用后发布到 `pending`
   - 渲染线程在回调开头做一次指针交换；切换 buffer 用旧/新引擎输出交叉淡化
4. **去重修复**：`state_dedupe()` 只删除命中的那一条记录（原来会删掉它之前的全部记录）

`patches/0002-engine-write-lock.patch`（相对 0001 应用后的源码）：

**调用方写保护** `jdsp_writelock.c/h`——参数只允许 jdspd 写，App 即使持有
AudioEffect 句柄也写不进去：

- 每个 effect 实例创建即**锁定**；`generic_command` 在进入引擎（和状态记录器）之前
  先过 `jdsp_writelock_gate()`
- 解锁握手：jdspd 发一个 `SET_PARAM`，参数 id = `0x7A00`（31232，上游未使用），
  value = 32 字节 engine key；token 与密钥文件一致 → 该实例的写窗口打开（保持），
  否则保持锁定并返回错误
- 锁定期间收到任何其它 `SET_PARAM` 一律返回非 0（命令返回值和 reply status 双通道
  都带错误），DSP 完全不被触碰
- 解锁命令**不进 state.bin 记录**（`generic_command` 过滤 + 重放路径双保险跳过），
  所以热重载/引擎重建/实例重建后仍是锁定态，由 jdspd 重新解锁（attach/applyAll 前注入）
- 密钥文件按顺序尝试：`$JDSP_WRITELOCK_KEYFILE`（仅测试）→
  `/data/vendor/audio/jamesdsp_engine.key`（HAL 可读，与 state.bin 同一
  `vendor_audio_data_file` 上下文，**无需新增 sepolicy 规则**）→
  `/data/adb/jdspd/engine.key` → `-DJDSP_WRITELOCK_BUILTIN_KEY_HEX=<64hex>`
  编译期兜底（默认不用）。token 不匹配时会重读密钥文件一次，支持换 key
- `EFFECT_CMD_ENABLE/DISABLE` **不在**锁定范围内：那是 AudioFlinger 自己发的
  （postprocess 实例的启停走框架路径），App 与 jdspd 无法区分，锁了会直接打断
  正常音频。残余风险：持有句柄的 App 仍可 setEnabled()，见模块 README

`patches/0003-deferred-param-apply-and-no-cross-instance-rebuild.patch`（相对 0001+0002 应用后的源码）：

**SET_PARAM 延迟应用 + 消除跨实例重建风暴**——解决滑杆连续拖动（30-100 次/秒）
时的可闻噪声与切换毛刺（`jdsp_paramq.c/h` + `jamesdsp.c`）：

1. **值参数不再在命令线程原地应用**。`generic_command` 把校验通过的 SET_PARAM
   （新模块 `jdsp_param_classify()` 按「参数 id + 值尺寸」白名单分类）拷进每实例
   固定容量（64 槽）队列即返回成功；`generic_process` 在每块开头、渲染之前，在
   **音频线程**把队列命令按序应用到当前引擎——此刻无并发读者，现有 setter 直接可用，
   数据竞争消除。同一参数 id 的更新在队列内**合并**（最新值覆盖、位置保留），
   满时丢最旧并计数。
2. **不进队列**：chunked 传输（8888/9999/12000/12001/10004/10006/10009/10010，
   罕见且重，禁止合并/驱逐）、`ENABLE/DISABLE`、`GET_PARAM`/查询、写锁 0x7A00
   握手（gate 先消费；写锁编译关闭时 classify 也判 invalid → 与旧行为一致返回 -1）。
   形状非法/未知参数 id 立即返回 -1，与旧 handler 语义一致。
3. **落盘时机不变**：值参数在入队时 `jdsp_state_record`（内容与顺序与 0001 相同，
   进程在音频线程应用前崩溃也不丢记录），随后把**全部存活实例**的 `cur_gen` 同步到
   新代——daemon 会对每个实例各发一次直写，各实例经自己的队列收到值，builder 不再
   为其它实例整引擎重建 + 交叉淡化切换。**外部文件热重载**（inotify/mtime 检测）
   仍走「代际变化 → builder 重建」路径，不受影响。
4. 主机单测 `test_paramq.c` 覆盖分类白名单、FIFO、合并、满槽驱逐、并发生产者/
   渲染者压力（最新值必达、乱序必报）。

## 采集 tap（0007）与 mix 保真自检（0008）

### `patches/0007-engine-capture-tap.patch`（相对 0001–0006 应用后的源码）

**采不到麦克风也要能验收**：把引擎交给 AudioFlinger 的样本直接落 WAV（`jdsp_tap.c/h`）。

- 两路同一渲染调用内取，天然帧对齐：lane0 = effect 输入（AudioFlinger 给我们混的），
  lane1 = `generic_process()` 交回的 out buffer（= 厂商 AF35 dump 的同一个对象，bit-exact）
- 渲染线程只做「边界检查 + memcpy 进预分配环形」：无 malloc / 无锁 / 无 syscall / 无日志；
  writer 线程负责排空成 44 字节头 WAV；环形满则丢最旧并计数，**绝不阻塞音频线程**
- 参数：`32001` = `jdsp_tap_config_t`（magic/lanes/frames/limit_frames/path/flags），
  `32002` 读 `jdsp_tap_status_t`（每路 captured/written/dropped + overruns/files）
- **默认关闭**：`e->tap == NULL`，两处钩子各是一个 NULL 判断——不分配、不 memcpy、不落盘、不打日志
- 诊断参数在写锁 gate **之前**处理：不需要 engine key、不进延迟队列、不写入 state.bin
- 主机单测 `test_tap.c`：五种格式逐字节回读、环形溢出策略、`limit_frames`、
  WAV 头/负载、生产者+消费者并发 19.2 万帧零丢帧、NULL/未知格式 no-op

### `patches/0008-engine-mix-integrity-check.patch`（相对 0007 应用后的源码）

**把 0005 修的契约变成可读计数器**（`jdsp_mixcheck.c/h`）：

- accumulate 路径上：进 process 前快照共享 mix，累加后断言 `out_after == out_before + rendered`
  （float32 / int32 / int16，整型按饱和；packed-24 与 int8_24 不覆盖）
- 不一致就计数 ⇒ **即使没有厂商 dump，也能在设备上断言「引擎没有吞掉别人的混音」**
- 参数：`30023` broken / `30024` checked / `30025` arm(1/0) / `30026` reset /
  `30027` armed / `30028` 上次不一致的样本数；同样是「gate 之前、不持久、不进队列」
- **默认关闭**：未武装时每个 buffer 只多一次 relaxed 原子读 + 一次可预测分支；
  快照缓冲首次武装时才按实例惰性分配，随实例释放
- 主机单测 `test_mixcheck.c`：覆盖回归（覆盖非零 mix）、静音实例清空整条 mix、
  int16/int32 饱和、arm/reset/status 计数、未武装默认值、NULL 缓冲

> 两个补丁都是**诊断设施**：默认关闭，未武装时对 DSP 输出没有影响
> （`module/src/common/files/` 里的 .so 已用未武装路径与 0001–0006 版做过逐字节对比，见
> `docs/ENGINE-TAP-AND-MIX-CHECK.md`）。

---
## 应用补丁

推荐（自动处理上游漂移，见下）：

```sh
<repo>/native/setup-src.sh /path/to/work      # 产出 work/src-baseline 与 work/src
```

手动方式：

```sh
git clone -b extensions https://github.com/evoeram/JamesDSPManager.git jdsp-src
mkdir -p ../work && cp -a . ../work/src-baseline
cd ../work && cp -a src-baseline src
patch -p1 -d src < <repo>/native/patches/0001-state-persistence-and-hot-reload.patch
patch -p1 -d src < <repo>/native/patches/0002-engine-write-lock.patch
patch -p1 -d src < <repo>/native/patches/0003-deferred-param-apply-and-no-cross-instance-rebuild.patch
patch -p1 -d src < <repo>/native/patches/0004-dump-async-state-and-peq-heap-scratch.patch
patch -p1 -d src < <repo>/native/patches/0005-engine-accumulate-out.patch
patch -p1 -d src < <repo>/native/patches/0006-async-effect-create-passthrough-priming.patch
patch -p1 -d src < <repo>/native/patches/0007-engine-capture-tap.patch
patch -p1 -d src < <repo>/native/patches/0008-engine-mix-integrity-check.patch
```

> 上游漂移注意：当前 extensions 分支的 Android.mk 已经补齐了 0001 增加的三个
> effect 源文件，0001 的 Android.mk hunk（第 1 个）会失败——这是预期的，其余
> hunk 正常应用。`setup-src.sh` 会手工补上 0001 原本要做的 Android.mk 改动
> （jdsp_state.c 进源码列表 + clang 20 兼容 flag）；0002 的 Android.mk hunk
> 则把 `jdsp_writelock.c` 一起加进源码列表。

## 构建

```sh
NDK=/path/to/ndk/30.x SRC=<work>/src/Main/libjamesdsp/jni/jamesdsp ./build.sh
# 产出 out/libjamesdsp-arm64-v8a.so / -armv7a.so
```

## 主机单测

```sh
SRC=<work>/src/Main/libjamesdsp/jni/jamesdsp ./test/run_tests.sh
# test_state      状态文件格式 / 重放 / 损坏 / 驱逐 / 代次
# test_watch      inotify 生效、外部覆盖被感知、自身写盘不自激、NO_INOTIFY 回退
# test_writelock  锁定/解锁/拒绝语义：新实例锁定、错 token 拒绝、对 token 解锁、
#                 解锁后放行、重复解锁幂等、截断 token 拒绝、非 SET_PARAM 不拦、
#                 解锁命令识别（state 记录过滤依据）、密钥文件加载/校验/轮换重读
# test_paramq     0003 延迟参数队列：参数 id/值尺寸白名单分类（chunked 传输、
#                 0x7A00、未知 id、畸形命令全部不进队列）、FIFO 顺序、同 id 合并、
#                 满槽丢最旧计数、并发生产者/渲染者压力下最新值必达
```
