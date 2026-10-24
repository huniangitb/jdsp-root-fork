# JamesDSP root fork — ColorOS 16/17 (AIDL 时代) 全局音效

让 JamesDSP 在 **ColorOS 16 / Android 16（以及 17）** 上真正全局生效：
不依赖 App 运行、不需要 App 有网络、切换输出设备（扬声器 / 蓝牙 / 有线）自动跟随。

本仓库是 **个人 fork 的整合工作区**，不改动上游仓库。

---

## 适用条件

| 项 | 要求 |
|---|---|
| 系统 | Android 15+（AIDL 音效 HAL 时代），已在 ColorOS 16 / Android 16 实测 |
| Root | KernelSU 或 Magisk（用 hybrid_mount / magic mount 做分区挂载） |
| 效果配置 | 设备上存在 `audio_effects.xml`（`/odm/etc`、`/vendor/etc` 或 `/vendor/etc/audio/sku_*`） |
| 架构 | arm64-v8a（音频 HAL 为 64 位）；armv7a 库也会构建，供 32 位 HAL 使用 |
| 构建 | Android NDK r30（编引擎库）、apktool 3.x + build-tools（改 App） |

不支持：Android 14 及更早（那套走的是旧的 effect 加载路径，上游模块即可）、
纯 AIDL 且完全没有 legacy 配置文件的设备（效果无处注册）。

## 现状（一句话）

模块 + 内核态补丁都不需要，只要一个 **Magisk/KernelSU 元模块**：
它把 fork 版 DSP 引擎装进音频 HAL，并通过 `audio_effects.xml` 的
`<postprocess>` 让框架在每条音乐 track 的 session 上自动创建并启用效果。

App 只用于调参，**完全离线、甚至卸载都不影响音效**——参数由引擎自己持久化到
`/data/vendor/audio/jamesdsp_state.bin` 并在每个新实例上重放。

### v2.0.0：后端派发（`jdspd`）

v6.7.2 的框架实例**没有客户端句柄**，所以参数只能靠状态文件异步重放。v2.0.0 增加一个常驻的
root 守护进程 `jdspd`：它自己对每条播放会话持有 `AudioEffect` 句柄，参数**实时**下发（实测 1.1 ms），
并对外暴露 binder 服务 `jdsp.service` 与 loopback TCP 控制通道。两种模式：

| mode | 谁创建效果实例 | 适用 |
|---|---|---|
| `auto`（默认） | 框架 `<postprocess>` 创建，jdspd 接管该实例的句柄 | 与其它音效模块共存 |
| `backend` | **jdspd 自己** `createEffect`，框架完全不参与 | 想彻底摆脱 postprocess |

切换 mode 需要重启（挂载是 ro 镜像快照 + audioserver 只在启动时读一次 XML），
`sh /data/adb/modules/ainur_jamesdsp/jdsp_mode.sh set auto|backend`。
详见 `docs/PLAN-v2.md`（设计）、`docs/PLAN-v2-IMPL.md`（实施）、`docs/ACCEPTANCE-V2.md`（验收）。

---

## 目录

| 路径 | 内容 |
|---|---|
| `module/src/` | 元模块源码（安装器、postprocess 注入、状态目录准备、sepolicy、内置库） |
| `native/patches/` | 引擎源码补丁：状态持久化 + inotify 热加载 + 后台重建 + 去重修复 |
| `native/build.sh` | 引擎构建脚本（NDK 30，arm64-v8a / armeabi-v7a） |
| `native/test/` | 主机单元测试（状态文件格式、热加载、watcher、回退路径） |
| `app/` | App 侧改动：中文翻译表 + 补丁说明（资源与 smali） |
| `tools/` | 命令行调试工具 `JdspCtl`（免开 App 调参）与测试音生成器 |
| `backend/` | **v2 后端派发**：`jdspd` 守护进程 + `jdspcli`（Java/app_process，AIDL 契约 + 单测） |
| `scripts/` | `verify-v2.sh`：重启后一键验证（基线 / daemon / mode / 实例 / 参数） |
| `docs/` | 真机验收记录（P0 基线、v2 验收、mode 方案、打包方法学、P5 App 接入） |

---

## 关键设计

### 1. 为什么用 `<postprocess>`

Android 15+ 把音效移到 AIDL HAL 后，传统「在 audio_effects.xml 里声明 effect」
只是让它*可加载*，还需要有人去 attach。`<postprocess><stream type="music">`
让 `AudioPolicyEffects::addOutputSessionEffects()` 在每条 music track 自己的
session 上创建并启用效果——**不需要 App 参与，也不需要 hook 框架**。

### 2. 状态持久化 + 热加载

`<postprocess>` 创建的实例没有 client handle，App 无法 `setParameter`。
引擎因此自己记录收到的参数并重放：

- 录制：`generic_command()` 里每个成功的 SET_PARAM / ENABLE / DISABLE 落盘；
- 回放：新实例构造时把记录喂回同一个命令处理函数；
- 热加载：inotify 监听状态文件目录（`poll()` 单 fd，不用 epoll），
  文件变化时**在 watcher 线程**读取解析；
- 后台重建：builder 线程用新参数构造**完整的新引擎**，全部应用完成后原子发布，
  渲染线程只在回调开头做一次指针交换 —— 音频线程永远不应用参数、不读文件；
- 交叉淡化：切换的那一个 buffer 用旧引擎输出与新引擎输出做线性混合，
  掩盖滤波器/卷积器历史清零。

### 3. 状态文件位置

效果实例运行在 **audio HAL 进程**（`hal_audio_default`，uid 1041 / gid 1005），
不是 audioserver。所以状态文件放在 `/data/vendor/audio/`
（`vendor_audio_data_file`，`audio:audio 0770`），并需要对应的 sepolicy 规则。

---

## 验证方法

```sh
# 播放测试音（Tone.java 编译成 dex 后推入设备）
adb shell su -c 'CLASSPATH=/data/local/tmp/tone.dex app_process /system/bin Tone 8'

# 确认框架自动挂载了效果
adb shell 'dumpsys media.audio_flinger | grep -A6 "JamesDSP"'
#   -> 期望出现 "name: JamesDSP v4.01" 且状态行 Enabled 为 y

# 免开 App 调参（JdspCtl：session 0 + 参数 1500 + 3 个 float）
adb shell su -c 'CLASSPATH=/data/local/tmp/jdspctl.dex app_process /system/bin JdspCtl 0 1500 -0.1 60 3.0'

# 状态文件（引擎自录自放）
adb shell su -c 'ls -l /data/vendor/audio/jamesdsp_state.bin'
```

## v2 构建

```sh
cd backend && ./build.sh          # AIDL -> javac -> d8，产出 out/jdspd.dex / out/jdspcli.dex
cd .. && bash module/package.sh   # 打包 dist/JDSP-fork-v2.0.0.zip（内置两个 dex）
bash module/accept-v2.sh --tone   # 装机后验收（12-13 项）
bash scripts/verify-v2.sh         # 交叉验证（10 项）
```

## 分支说明

- `master`：**可复现二进制的源码**（无二进制、无设备日志、无截图）。
- 本地另有 `local-full` 分支保留真机验收日志、截图与编译产物，**不推送到远端**。

## 贡献 / 反馈

上游仓库：[evoeram/Loundess-PEQ-RootlessJamesDSP](https://github.com/evoeram/loundess-peq-rootlessjamesdsp)（App）
与 [evoeram/JamesDSPManager](https://github.com/evoeram/JamesDSPManager)（引擎，`extensions` 分支）。
本仓库只是把它们接到 ColorOS 16/17 上的一层适配 + 状态持久化/热加载改造，不修改上游行为。

## 许可

模块脚本沿用上游（JamesDSP / ainur_jamesdsp / KernelSU 模板）的许可；
本仓库新增部分（状态持久化、热加载、后台重建、汉化）同样以 GPL 发布，
遵循上游 JamesDSP 的许可证。
