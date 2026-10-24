# AIDL 变体构建进展（2026-10-24）

## 结论摘要

用「拉一部分 AOSP 源码」的方式，**AOSP 平台内部头的问题已经基本解决**：
从「缺 binder_interface_utils.h，一行都编不过」推进到「只剩 2 个源码级版本错配错误」。
剩下的不是缺头，而是 **AOSP 分支版本错配**（effect-impl 与 AIDL 生成头不同代）。

## 已拉取的 AOSP 源码（sparse clone，全部 Apache-2.0）

| 仓库 | 稀疏目录 | 提供的头 |
|---|---|---|
| platform/frameworks/native | `libs/binder/ndk`, `libs/nativebase/include` | `android/binder_interface_utils.h`、`binder_auto_utils.h`、`binder_parcelable_utils.h`、`binder_internal_logging.h`、`binder_shell.h` |
| platform/system/core @ android13-release | `libutils/include` | `utils/Errors.h`、`utils/Trace.h`、`utils/RefBase.h` 等（**必须用 android13 分支**，main 里这些是断链符号链接） |
| platform/system/core | `libcutils/include` | `cutils/native_handle.h` |
| platform/system/libbase | `include` | `android-base/unique_fd.h` 等 |
| platform/system/logging | `liblog/include` | `log/log.h` |
| platform/system/libfmq | `include`, `base` | `fmq/AidlMessageQueue.h`、`AidlMQDescriptorShim.h`、`MQDescriptorBase.h` |
| platform/hardware/libhardware | `include`, `include_all` | `hardware/audio_effect.h`（include 里是符号链接，必须同时拉 include_all） |
| platform/system/media | `audio/include` | `system/audio_effect.h`、`system/audio.h` |
| platform/frameworks/av | `media/liberror/include`, `media/audioaidlconversion/include` | `error/Result.h`、`media/AidlConversion*.h`（含 `-impl.h`） |
| platform/hardware/interfaces | `audio/aidl` | `.aidl` 源 + `default/include/effect-impl/{EffectImpl,EffectContext,EffectThread,EffectTypes}.h` |

可复现的 include 集合：

```sh
EXTRA_INCS="\
 -I/tmp/aosp-native/libs/binder/ndk/include_cpp \
 -I/tmp/aosp-native/libs/binder/ndk/include_ndk \
 -I/tmp/aosp-native/libs/binder/ndk/include_platform \
 -I/tmp/aosp-native/libs/nativebase/include \
 -I/tmp/aosp-core13/libutils/include \
 -I/tmp/aosp-core/libcutils/include \
 -I/tmp/aosp-libbase/include \
 -I/tmp/aosp-logging/liblog/include \
 -I/tmp/aosp-libhardware/include \
 -I/tmp/aosp-libhardware/include_all \
 -I/tmp/aosp-media/audio/include \
 -I/tmp/aosp-av/media/liberror/include \
 -I/tmp/aosp-hal/audio/aidl/default/include"
```

## 当前剩余错误（2 个，都是版本错配而非缺头）

```
EffectImpl.h:95: error: no member named 'kEventFlagDataMqNotEmpty'
                 in namespace 'aidl::android::hardware::audio::effect'
jamesdsp_aidl.cpp:170: error: use of undeclared identifier
                 'aidl2legacy_ParameterCommon_effect_config_t'
```

- **第一个**：`kEventFlagDataMqNotEmpty` 是 effect AIDL **V2+** 生成头里的常量。当前 `aidl-headers/`
  是用冻结的旧 .aidl 集生成的，没有这个常量；而 effect-impl 用的是较新的实现 → 错配。
  解法：`.aidl` 与 `effect-impl` 必须来自**同一个 AOSP 分支 tag**（如 `android14-release`），
  然后重新生成头。
- **第二个**：移植时把 `aidl2legacy_ParameterCommon_effect_config_t` 换成本地实现，但调用点没改完。
  30 行内可修。

## 下一步（建议顺序）

1. 用 **android14-release** 分支同时拉 `hardware/interfaces:audio/aidl` 与 `default/effect`，
   重新 `./build-aidl.sh headers`，消除第一个错误；
2. 修掉 `jamesdsp_aidl.cpp:170` 的调用；
3. 链接需要设备端 stub 库（`effect-V<n>-ndk`、`types-V<m>-ndk`、`common-V2-ndk`、`fmq-V1-ndk`），
   从目标机 `/system/lib64` 拷出来放 `aidl-vendor/device-libs/`；
4. 仍然**只能在有 AIDL effects HAL 的设备上做端到端验证**（c16 无该服务）。

## 已确认的硬约束

- AIDL effect 库与平台 AIDL 版本**强绑定**：c16 只有 `effect-V3` + `types-V1/2/4/5`，
  参考库要的 `effect-V2`/`types-V3` 在 c16 不存在 → 构建必须按目标平台版本参数化。
- 引擎本体已全部编译通过（92 个 C 文件，0 error），移植部分（peq/loudness/state/builder）无阻塞。

## 进展更新（17:xx）

### 已解决：全部「缺平台头」问题

按「拉一部分 AOSP 源码」的方式，**绑定层已经能编译通过**（`jamesdsp_aidl.cpp` 无错）。
新增拉取/修正：

| 项 | 说明 |
|---|---|
| system/media `audio/include` | `system/audio_effects/aidl_effects_utils.h` 里有 `kEventFlagDataMqNotEmpty`（原来被空 shim 顶掉，导致 EffectImpl 报未定义） |
| system/media `audio_utils/include` | `audio_utils/template_utils.h` 等 |
| C++ 标准 | 必须 `-std=c++20`（这些头用了概念 concepts，c++17 编不过） |
| hardware/interfaces `audio/aidl/default` | `include/effect-impl/*.h`，注意 .cpp 里写的是 `"include/effect-impl/EffectTypes.h"`，需要把 default 目录本身加入 include path |
| 避免重复定义 | 不能同时把 `$VENDOR/include/effect-impl` 和 AOSP 的 `default/include/effect-impl` 放进 include path |

### 剩余唯一阻塞：AIDL 头与 effect-impl 的代次不一致

我们的 `aidl-headers/`（366 个，来自冻结的 `.aidl` 集）比 AOSP main 的 effect-impl 老，缺这些成员：

```
BnEffect::getInterfaceVersion        ← EffectImpl.cpp:59 / 94 需要
common::getFrameSizeInBytes          ← hal14 的 EffectContext.h 需要
EffectImpl::mImplMutex               ← main 的 EffectImpl.h 有，hal14 没有（我们的绑定用的是 main 的名字）
```

也就是：**生成头、EffectImpl/EffectContext、我们移植的绑定代码，三者必须来自同一个 AOSP 版本**。

### 关键发现：`android.media.audio.common` 的 .aidl 不在已拉取的仓库里

要重新生成一套 main 对齐的头，需要把 `.aidl` 根目录按 AOSP 布局重组，其中
`android/media/audio/common/*` 这个包**不在** frameworks/native、system/core、system/media、
frameworks/av、hardware/interfaces（main 与 android14-release）里找到 —— 还需要再定位它的宿主仓库。

### 下一步（二选一）

- **A. 继续本地化**：定位 `android.media.audio.common` 的 .aidl 宿主仓库 → 组装 main 对齐的 .aidl 根
  → 重新 `./build-aidl.sh headers` → 用同版本 effect-impl → 链接设备 stub 库 → 出 .so。
- **B. 走 Soong**：把这棵树放进 AOSP/CLO 源码里 `m libjamesdspaidl`，一条命令出 .so（上游的原生方式）。

### 仍未变的前提

- c16 无 AIDL effects HAL 服务，本地只能验证「编得出来 + dlopen 成功」，端到端要 c17。
- 目标平台 AIDL 版本决定一切：c17 的 `/system/lib64` 里 `audio.effect-V*` 与 `common.types-V*` 才能定版本。

## 联网调研结论（17:30 前后）

### 有没有现成的 AIDL 工具链？——没有

- 社区/厂商做法分两种：
  1. **在 AOSP/CLO 树里用 Soong 编**（上游匿名作者的 `Android.bp` 就是这条路）；
  2. **直接内置预编译 .so**：`Genesis-Devices/vendor_JamesDSP` 用的就是
     `cc_prebuilt_library_shared`，只依赖 `liblog/libc/libm/libdl`（那是 legacy AELI 库）。
- 没有找到任何"脱离 AOSP 树构建 AIDL audio effect HAL"的第三方工具链或脚本。

### 但找到可行的替代路径（已实测跑通到链接）

关键发现：AOSP 每个 AIDL 接口仓库里都有 **`aidl_api/` 冻结快照**，
配合 SDK 自带 `aidl` 编译器的 **`--version=N`** 参数，就能生成与设备
`android.hardware.audio.effect-V<N>-ndk.so` 完全对齐的头（含 `getInterfaceVersion`）。

实测：
```sh
aidl --lang=ndk --structured --stability=vintf --min_sdk_version=31 --version=3 \
     --out=... --header_out=... -I <frozen V3 根> <所有 .aidl>
# -> 366 个头，BnEffect.h 里出现 getInterfaceVersion
```

需要合并的 AIDL 源码根（已全部定位）：
| 包 | 仓库 |
|---|---|
| `android.hardware.audio.effect`、`android.hardware.audio.common` | `hardware/interfaces` → `audio/aidl/`（含 `aidl_api/…/{1,2,3,current}`） |
| `android.hardware.common`、`android.hardware.common.fmq` | `hardware/interfaces` → `common/aidl/`、`common/fmq/aidl/` |
| `android.media.audio.common` | **`system/hardware/interfaces`** → `media/aidl/`（之前找不到的包就在这里） |

### 当前产物状态

```
/home/bdy/work/jdsp-state/out-aidl/libjamesdspaidl.so
  95,744 B  sha256=3f190230048a83b5ef598cc4268fa4e8c892c053d15be94446cd6d07ab2e3397
  NEEDED: android.hardware.audio.effect-V3-ndk / common-V2-ndk / common.fmq-V1-ndk /
          libbinder_ndk / libfmq / libbase / libutils / libcutils / liblog / libc++ / ...
  导出符号: 2（createEffect + queryEffect，符号可见性符合上游做法）
```

设备侧 `linker64 --list` 已验证：**所有依赖在 c16 上都能解析**。

### 遗留问题（下一步）

引擎的 92 个 .o 没被链进最终 .so（产物只有 95 KB，`strings` 里没有 `jdsp_state`）。
编译阶段确实执行了（日志 "compiled 92 engine sources"），但链接时 `$OUT/obj` 里只剩
`jamesdsp_aidl.o / EffectImpl.o / EffectThread.o` 三个 —— 需要排查构建脚本里对象目录被
覆盖/清理的时机（很可能是后续步骤重新建了 obj 目录）。

## 修复完成（17:58）

### 根因：对象文件名丢了扩展名

构建脚本用
`-o "$OUT/obj/$(echo "$f" | tr '/' '_' | sed 's/\\.c$/.o/')`
生成对象名，但 sed 实际没生效 —— 92 个引擎对象被写成
`jdsp_Effects_dynamic.c`、`jdsp_state.c` 这类**没有 .o 后缀**的名字，
而链接用的是 `"$OUT"/obj/*.o`，所以只把 3 个真正的 .o（绑定 + effect-impl）链进去，
产物 95 KB、引擎全丢。

修法：编译循环后把 `obj/*.c` 重命名为 `*.o`，再做链接。

### 顺带修掉的两处

1. **`-Wl,--gc-sections` 去掉**：引擎符号在 AIDL 绑定里只通过 slot 间接引用，
   section GC 会把它们当死代码剪掉。
2. **入口符号可见性**：`-fvisibility=hidden` 把 `createEffect`/`queryEffect` 也藏了，
   HAL 框架 dlopen 后按名字查不到。按上游 AOSP 的做法给这两个函数加
   `__attribute__((visibility("default")))`。

### 最终产物

```
/home/bdy/work/jdsp-state/out-aidl/libjamesdspaidl.so
  2,255,896 B
  sha256=9db0706251bb0dbcc878326cf73f828d06f5470e04a4485e72045d31c746fa7b

导出：createEffect (T), queryEffect (T)        ← 与 AIDL HAL 契约一致
NEEDED：13 项（effect-V3-ndk / common-V2-ndk / common.fmq-V1-ndk / libbinder_ndk / ...）
引擎：peq_cascade、jamesdsp_state.bin、state reloaded 等字符串均在
设备：linker64 --list 无 not found
```

### 已知限制

- 该产物按 **effect V3 + types V4** 编译（c16 的版本）。c17 若为其它版本，
  用 `AIDL_EFFECT_V=n AIDL_TYPES_V=m` + 对应冻结 `aidl_api` 重新生成头即可。
- c16 无 AIDL effects HAL，无法端到端验证；需要在 c17（或任何 AIDL-only 设备）上刷入测试。
