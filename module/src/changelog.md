### v6.7.9-fork

* aml.sh 现在同时负责 <library>/<effect> 声明与 <postprocess><apply> 条目：
  此前它在声明已存在时整文件 continue，导致 apply 永远补不上。
* apply 条目严格归一化为一条：重复条目会让 AudioFlinger 建链失败，
  并连带把厂商全局效果（杜比 DAP）带掉 —— 已在小米/联发科实测确认单条共存。
* 引擎：inotify 监听失败时打印 errno，便于现场定位（本机为 errno 13 EACCES）。

### v6.7.6-fork（修回杜比）

* 不再改动其它模块的 audio_effects.xml。此前会往 XagaSoundBoostTest（杜比增强）的配置里
  插入 postprocess + apply，导致该设备的杜比链路失效；现在只做检测并在安装日志里提示。
* 原因：该机 vendor 把 postprocess 整段注释掉了，杜比走全局效果（session 0）；
  我们新增 postprocess 会改变音乐会话的效果链，杜比随之失效。

### v6.7.5-fork

* 新增 merge-only 模式（仅在已存在活动 music postprocess 时才并入 apply）。

### v6.7.4-fork（联发科 / 小米设备适配）

* sepolicy：补 mtk_hal_audio 域，并在目录权限里加 read/open/getattr（状态文件写入 + inotify）。
* audio_effects.xml 去重：先删掉所有 apply effect=jamesdsp 再插入一条，避免历史残留导致重复挂载。

### v6.7.3-fork

* 安装时同时修补其它模块里的 audio_effects.xml 副本：多个音频模块争抢同一文件时，
  谁赢得挂载都带着我们的声明（例如 XagaSoundBoostTest 会覆盖 /vendor/etc/audio_effects.xml）。
* sepolicy：新增 mtk_hal_audio 域的状态文件访问规则。

### v6.7.2-fork

* 安装器新增 AIDL 版本校验：只有当设备同时具备 `android.hardware.audio.effect.IFactory`
  服务 **且** 存在 effect-V3 / common.types-V5 / common-V2 / common.fmq-V1 这四个 NDK 库时
  才安装 AIDL 变体；否则回退 legacy 并提示重新构建。
* 避免在 Android 13/14 的 AIDL 设备（effect V2）上装到无法加载的 .so 导致完全无声。

### v6.6.5-fork（内置 APK 换成汉化版：按设备配置默认开启 + 界面汉化）

* 内置 APK 更新为 `JamesDSP-root-full-v1.6.15-59-zh.apk`（versionCode 59 / 1.6.15-zh）：
  - 「按设备配置」（Device profiles）默认开启：`default_device_profiles_enable` false → true，
    连接过的耳机/蓝牙设备会自动出现在主界面「设备」区，可绑定预设。
  - 简体中文补全 389 条缺失字符串（PEQ、响度补偿、卷积器按采样率分配、谐波/次谐波扩展器、
    NOS R2R、Smooth Volume、Squig Live、MEOW 测量向导、目标曲线、Auto-EQ、Direct Mode、
    Low-latency 模式、缓冲区建议、Android 15 录屏提示等），中文条目从 567 → 956，与英文条目零缺口。
  - 该 APK 与旧版签名不同，安装前需卸载旧版；音效参数保存在模块的状态文件里，卸载 App 不会丢设置。

### v6.6.4-fork（重加载不再占用渲染线程：后台构建 + 原子切换 + 单 buffer 交叉淡化）

* 彻底消除重加载卡顿：**渲染回调不再应用任何参数**。
  - 新增 builder 线程（每 20 ms 轮询一次 store 代次，带 20 ms 突发去抖，App 启动时几十条参数只重建一次）。
  - 代次变化时，builder 在**自己线程上**新建一个完整引擎（`EffectDSPMainConstructor` + 复制采样率/格式 + 重放全部记录），
    全部应用完成后才通过原子指针发布到实例的 `pending`。
  - 渲染线程在**回调开头**用一次 `__atomic_exchange` 取走 `pending`，指针切换即为生效点：
    要么整份新参数，要么整份旧参数，永远不会看到半应用状态。
  - 被替换的旧引擎通过一把只做两次指针写的小锁交给 builder 销毁（销毁本身在 builder 线程，绝不在音频线程）。
* 切换处做一个**单 buffer 线性交叉淡化**：旧引擎把这一 buffer 渲染到预分配的 scratch，新引擎渲染到输出，
  两者按 0→1 交叉，掩盖新引擎滤波器/卷积器历史清零的瞬态。scratch 在 `EffectCreate` 一次分配（8192 帧立体声 int32），
  帧数超限或格式不支持（packed 24-bit）时退化为直接切换。
* 顺带修复 stamp 竞态：watcher 与轮询 API 的 `stat()` 现在都在 `g_lock` 内完成。之前可能在 `state_save()`
  的 rename 与 stamp 刷新之间取到中间态，把自己的写盘误判成外部变更（会触发一次多余的重建）。
  该问题由主机单测 `test_watch` 偶发失败暴露，修复后连跑 3 轮全绿。
* 采样率变化（SET_CONFIG）不再就地重放：builder 通过 `built_fs != cur->mSamplingRate` 感知并重建，
  期间旧引擎继续用旧系数工作，不会出现 EQ 断档。
* 状态文件格式不变；导出符号仍 1023。

### v6.6.3-fork（修复：重发 ENABLE/DISABLE 会清空已保存的参数）

* 修复状态去重逻辑的数据丢失 bug：`state_dedupe()` 命中重复记录时原本调用
  `state_evict_prefix(i+1)`，会把**重复记录之前的所有记录一起删掉**。框架在每次
  effect 创建/释放时都会重发 ENABLE/DISABLE，而它们的记录恒定相同，于是一旦命中，
  用户已保存的全部参数（PEQ/limiter 等）会被连带清除——真机表现为状态文件从 N 条
  掉到只剩 ENABLE+DISABLE 两条（36 字节）。
* 改为新增的 `state_evict_at(idx)`：只删除命中的那一条重复记录，其余记录与其顺序
  全部保留；新记录仍追加在末尾，因此重放顺序依旧“最后写入的生效”。
* 主机单测新增 `test_dedupe_keeps_unrelated_records`（重发 ENABLE 后计数不变、
  两条参数仍在重放序列中、重发非末尾参数同样不丢记录）；该用例在修复前的代码上
  会失败 4 项，可复现原 bug。
* 状态文件格式不变。

### v6.6.2-fork（inotify 热加载：毫秒级实时生效，文件 IO 离开音频线程）

* 引擎内新增状态文件监听线程：inotify 监听状态文件所在目录（`IN_CLOSE_WRITE|IN_MOVED_TO|IN_CREATE`），
  用单个 `poll()` 循环等待事件（只监听一个 fd，epoll 在此场景没有优势，故不用）。
  文件被替换时由该线程读文件并解析，**渲染线程不再做任何文件 IO**。
* 渲染线程每处理一个 buffer 只做一次 relaxed 原子读（`jdsp_state_generation()`）比较代次，
  代次变化即重放，因此从写盘到生效由“最坏 ~1.3 s”降到“下一个回调”（~40 ms）。
* 兜底不变：inotify 不可用 / 监听被拒 / 线程创建失败 / 目录尚不存在（重试 20 次后放弃）时，
  自动回到 v6.6.1 的 stat() 轮询路径，功能完全一致，只是延迟回到 ~1.3 s。
  可用 `JDSP_STATE_NO_INOTIFY=1` 强制走轮询（测试用）。
* 防自激：自己 `state_save()` 产生的 inotify 事件用 size+mtime+inode 的 stamp 过滤，不会触发重载。
* 日志区分来源：`state reloaded: N record(s) [inotify]` / `[poll]` / `(in-process update)`。
* 状态文件格式不变，旧 state.bin 直接沿用；导出符号仍为 1023（新增函数全部 hidden）。
* 主机单测新增 `test/test_watch.c`：watcher 生效、外部 rename 覆盖可被感知、自身写盘不自激、
  `JDSP_STATE_NO_INOTIFY=1` 时轮询路径仍可用。

### v6.6.1-fork（状态热加载：播放中实时生效）

* native 引擎新增热加载：渲染回调里每 ~32 个 buffer 做一次 stat()，用 size+mtime+inode 组成的
  stamp 与上次 load/save 比较；文件被替换时当场重新解析并重放，**播放不中断、不用重启音频**。
* 状态日志降噪：只有真正写盘时打一行 INFO \`state file updated: N record(s)\`；热加载成功打一行
  INFO \`state reloaded: N record(s)\`；回放打一行 INFO \`replayed N persisted command(s)\`。
  默认构建不再带 -DDEBUG，\`EffectDSPMain:\` 的创建/回放调试日志全部消失。
* 状态文件格式不变（magic/version/CRC32 与 v6.6.0 完全兼容），旧 state.bin 直接沿用。

### v6.6.0-fork（元模块版 + fork 引擎 + 状态持久化）

* 内置 fork PEQ+loudness 引擎（来自 evoeram/Loundess-PEQ-RootlessJamesDSP）：
  替换 common/files/arm64 与 common/files/arm 两个 ABI；aidl / arm64-sve / x86 仍为上游库。
* 接入 native 状态自持久化：新增 jdsp_state.sh（准备 /data/vendor/audio 的目录/属主/0660/标签/日志，
  幂等、只碰这一个路径），post-fs-data.sh 与 service.sh 各调用一次。
* sepolicy.rule 增加 hal_audio_default 对 vendor_audio_data_file 的路径级规则。
* 元模块（hybrid_mount）负责分区挂载与 SELinux 上下文：模块脚本不再自行 mount、
  不建 vendor 软链、不批量 chcon 分区文件。

### v6.5 RC3-test4 + pp1（postprocess 实验版）

* 安装时向 audio_effects.xml 的 <postprocess> 注入 `<apply effect="jamesdsp"/>`（music 流）。
  由 audioserver 的 AudioPolicyEffects 在每次 startOutput() 时自动创建并启用效果，
  不再依赖 App 主动 AudioEffect()，也不会落错输出线程。
* 新增 common/jdsp_postprocess.sh：注释感知、幂等；无可用 <postprocess> 时自动新建；
  已有未注释 <stream type="music"> 时把 <apply> 插进内部以保留原有效果。
* 新增 jdsp_check.sh 自检脚本；service.sh 开机自检增加 apply 行数与 flinger 实例数统计。

### v6.5 RC- 12.29.2025

* Created post-fs-data.sh with apex mounting support if needed
* Service.sh will now seek inside AIDL lib dependency, and modify it to match files in system (not need to add additional dependency files which might break functions of phone)
* JDSP will now automatically launch itself, minimize, grant notification permissions, launch notif. service

### v6.4 - 04.28.2025

* Updated AIDL lib
* Updated PBone APK
* Updated patching script
* AML compatibility for non-pixel AIDL devices

### v6.3 - 03.18.2025

* Force mount if AML is not flashed
* Better compatibility with AML module

### v6.2 - 03.19.2025

* Updated AIDL lib
* Fix odm partition mount for KSU and KSU Next

### v6.1 fix + AIDL - 11.26.2024

* Added AIDL lib
* Changed installer to include devices with AIDL

### v6.1 fix - 10.12.2024

* mmtex fix for magisk 28

### v6.1 - 4.24.2024

* mmtex v3.7 update

### v6.0 - 2.16.2024

* mmtex v3.6 update
* Update pbone apk
* Update jdsp app/libs

### v5.8 - 9.11.2023

* Update pbone apk
* Update jdsp app/libs
* mmtex v3.5 update

### v5.7 - 9.3.2023

* Automatic 32/64 bit lib detection
* mmtex v3.4 update

### v5.6 - 8.24.2023

* Lib bug fix

### v5.5 - 8.21.2023

* Update official apk:

  * Introduce fully functional continuous wavelet transform to dynamic range compander as a experimental trial
  * Use custom implementation of libsamplerate, the file no longer bloat with coefficients

### v5.4 - 8.13.2023

* Update to mmtex v3.3
* Update the thepbone jdsp app

### v5.3 - 8.11.2023

* Update mmtex to v3.2

### v5.2 - 7.28.2023

* Bug fix with osp removal
* Add thepbone APK option

### v5.1 - 7.25.2023

* Bug fix for odm on KSU and magisk delta
* Updated to mmtex v3.1

  * Added support for odm in regular magisk (should fix oneplus device issues) - thanks to @ShadoV90 (https://t.me/ShadoV90) for the fix

### v5.0 - 7.15.2023

* JDSP app updated!:

  * LiveProg update: Clean up EEL RAM disk before new script being loaded
  * EEL engine update:

    * Unify string, thread pool, convolver in one virtual data type
    * Introduce efficient running min/max, median filter
    * Allow STFT window function to be specified
    * Correct STFT phase value(Yield no difference for any previous scripts that depends on relative phase / magnitude only spectral processing)

### v4.8 - 7.10.2023

* Update mmtex to v3.0 - adds support for ksu and magisk delta

### v4.7 - 5.22.2023

* Update mmtex v2.1 - fix for magisk v26+

### v4.6 - 8.4.2022

* Update jdsp libs

### v4.5 - 6.25.2022

* Update jdsp libs

### v4.4 - 1.31.2022

* Update to MMT-Ex v2.0 - support for magisk 24 module update method

### v4.3 - 11.21.2021

* MMT-EX v1.8 update - fix for magisk canary, minmagisk now 20.4

### v4.2 - 9.17.2021

* Always install as data app, no need for system app in older android versions

### v4.1 - 9.9.2021

* Only uninstall app if older than current
* Update mmtex to v1.7
* Fix app update process - uninstall app during zip flash, reboot to complete process

### v4.0 - 9.8.2021

* JDSP app updated!:

  * New app/drivers made open sourced
  * Frequency domain dynamic range compression, give you much crazier compression
  * New dynamic bass boost, proper implementation of signal power detection, proper sample delay apply to multirate analysis and deploy state space filter to achieve boosting filter parameters.
  * Greatly reduce power consumption for some cases, especially when Convolver and Equalizer is used
  * Add user manual to main app

* No more new/old options
* Removed old options

### v3.4 - 5.14.2021

* Update volkey addon

### v3.3 - 11.2.2020

* Update to MMT-Ex v1.6
* Update volkey addon

### v3.2 - 9.9.2020

* Added hidden\_api\_policy workaround for android 11

### v3.1 - 8.25.2020

* New JDSP app updated!:

  * Equalizer interface and actual frequency response mismatch
  * libsndfile replaced with much smaller dr\_libs for impulse response file loading
  * Uses Android 10 API as compiling
  * Additional features:

    * Shrinking impulse response according users thresholds
    * Uses minimum phase transform on impulse response if user needed
    * Customizable channel dependent sample delay at the impulse response beginning

### v3.0 - 4.9.2020

* Fix boot script
* Update to MMT-EX v1.5
* New JDSP app update!:

  * Reduce parameter commit
  * Junked Convolver benchmark system, improve device responsiveness when the device is booting
  * New Dynamic range compressor, full automatic internal parameter adjustment(BUT haven't fully adapt to level depend system(I mean audio framework)
  * New bass boost system, the system automatically detect "Interesting frequency", and adjust filter gain, bandwidth accordingly
  * New FIR Equalizer, enhance interpolator algorithms, more interpolation mode and faster filter generation
  * New convolution algorithm, much faster, much more power saving, remove impulse response cutting feature, however, algorithm not yet fully completed, there will still update that boost performance further.
  * New stereo widening algorithm
  * New BS2B Crossfeed system, with traditional BS2B and a few HRTF based crossfeed(Include the one provided by @Joe0Bloggs )
  * Remove computationally heavy load 7.1 surround simulation, if you still want one, go download Hiby music player!
  * More stable live programming virtual machine, more built-in features that allow user to build very complex audio effect.
  * Processing performance improvement for all effects
  * Arbitrary phase response
  * DDC, Reverb, Arbitrary magnitude response algorithm remain the same
  * Remove all GPL code, allow full close source implementation😂😂😂However live programming compiler/Virtual machine will still go open source, last but not least, all rewritten C API will still go open source for our reference implementation of processing system.

### v2.7 - 1.22.2020

* Update to MMT-Ex v1.3 (Fixes issues on Q)

### v2.6 - 1.17.2020

* Update to MMT-Ex v1.2

### v2.5 - 1.4.2020

* Switched from Unity to MMT-Ex

### v2.4 - 12.30.2019

* Unity v5.0 update

### v2.3 - 8.11.2019

* Unity v4.4 update

### v2.2 - 5.16.2019

* Update to Unity v4.2

### v2.1 - 5.3.2019

* Update to Unity v4.1

### v2.0 - 3.28.2019

* Don't prompt for lib workaround if device that needs it
* Update to unity v4.0

### v1.9.8 - 2.21.2019

* Added Samsung Galaxy S9 and Zuk Z2 Pro to lib workaround
* Made lib workaround a choice so I don't have to keep updating this
* Unity v3.3 update

### v1.9.7 - 1.16.2019

* Forgot to move apk back to priv-app (needed for unity 3.2)

### v1.9.6 - 1.15.2019

* Unity v3.2 update

### v1.9.5 - 1.11.2019

* Unity hotfix

### v1.9.4 - 1.10.2019

* Unity v3.1 update

### v1.9.3 - 1.5.2019

* Unity v3.0 update

### v1.9.2 - 12.23.2018

* Fixed hua/nhua zipname trigger for real this time
* Unity v2.2 update

### v1.9.1 - 12.20.2018

* Fixed hua/nhua zipname trigger
* Fix lib workaround
* Update to Unity v2.1

### v1.9 - 12.18.2018

* Unity v2.0 update
* Fixed limitation in zipname triggers - you can use spaces in the zipname now and trigger is case insensitive
* Improved boot script for user app install - should fix bootloop issues

### v1.8 - 11.21.2018

* Updated JDSP to 11.20.18 release (Change FFT to slower but a commercial friendly implementation, Improve TruCentre separation, Reduce TruCentre latency to 21 ms(from 32 ms), Fix arbitrary response equalizer memory leaks)

### v1.7.5 - 11.8.2018

* Add libstdc++ workaround for pixel 2's, 3's, and essential phone

### v1.7.4 - 10.29.2018

* Switch to manual huawei selection - too much variation with custom roms for consistent huawei device detection

### v1.7.3 - 10.24.2018

* Unity v1.7.2 update
* Bug fix for arm64 devices
* Bug fixes for huawei devices

### v1.7.2 - 10.23.2018

* Fixed awesome boot script for pie - magisk apk stuff is automated once again :)

### v1.7.1 - 10.22.2018

* Fix boot hanging on Pie roms

### v1.7 - 9.20.2018

* Update to unity 1.7.1
* Widen detection for huawei devices

### v1.6.9.1 - 9.16.2018

* Hotfix for Huawei devices

### v1.6.9 -9.13.2018

* Updated JDSP to 9.13 release (adds huawei support, adds auto DDC resampler for device running on 96k sample rate or above, fixes some device volume issue caused by bit depth)

### v1.6.8 - 9.3.2018

* Updated JDSP to 8.31 release

### v1.6.7 - 9.2.2018

* Unity v1.7 update
* Added auto-install/uninstall of apk for magisk only (works for disable/enabling mod in magisk manager as well)
* Add compression to save space/bandwidth

### v1.6.6 - 8.30.2018

* Unity v1.6.1 update

### v1.6.5 - 8.26.2018

* New JamesDSP build (Now supports HTC devices)
* Fixed bit depth problem from HTC and Android Pie
* Upgraded Fast Fourier Transform libraryand  Android NDK

### v1.6.4 - 8.24.2018

* Unity v1.6 update

### v1.6.3 - 7.17.2018

* Unity v1.5.5 update

### v1.6.2 - 5.7.2018

* Unity v1.5.4 update

### v1.6.1 - 4.26.2018

* Unity v1.5.3 update

### v1.6 - 4.16.2018

* Unity v1.5.2 update
* Add AML detection/notification
* Add auto install for apk if in bootmode

### v1.5.9 - 4.12.2018

* Reworking/fixing of audio file patching

### v1.5.8 - 4.12.2018

* Unity v1.5.1 update

### v1.5.7 - 4.12.2018

* Unity v1.5 update

### v1.5.6 - 4.9.2018

* Use dynamic effect removal

### v1.5.5 - 3.30.2018

* Fix effect removals

### v1.5.4 - 3.29.2018

* Unity v1.4.1 update

### v1.5.3 - 3.18.2018

* Unity v1.4 update

### v1.5.2 - 3.1.2018

* Real fix for vol key logic

### v1.5.1 - 2.26.2018

* Quick fix for vol key logic

### v1.5 - 2.25.2018

* Fixed vendor files in bootmode for devices with separate vendor partitions
* Bring back old keycheck method or devices that don't like the newer chainfire method
* Fix seg faults on system installs

### v1.4.2 - 2.17.2018

* Boot script not working, have user do it manually for oreo

### v1.4.1 - 2.16.2018

* Updated jdsp to new version

### v1.4 - 2.16.2018

* Add file backup on system installs
* Fine tune unity prop logic
* Update util\_functions with magisk 15.4 stuff
* Fix music\_helper/sa3d removal in xml files

### v1.3 - 2.12.2018

* Fix vendor cfg creation for devices that don't have it
* Fix sepolicy patching

### v1.2 - 2.10.2018

* Added sa3d removal for samsung devices

### v1.1 - 2.6.2018

* Fixes for xml cfg files

### v1.0 - 2.5.2018

* Initial rerelease

## Credits

* [James34602](https://forum.xda-developers.com/android/apps-games/app-reformed-dsp-manager-t3607970)

## Source Code

* Module [GitHub](https://github.com/therealahrion/JamesDSPManager)

App [GitHub](https://github.com/james34602/JamesDSPManager)

