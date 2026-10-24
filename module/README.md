# 模块源码

`src/` 是可直接用 `zip` 打包成 KernelSU/Magisk 模块的目录树，
安装后由 hybrid_mount 负责分区挂载与 SELinux 上下文，**模块脚本自身不做任何挂载**。

## 打包

```sh
cd src && zip -r -X ../JDSP-fork.zip . -x '*.git*'
```

## 内置 APK

`src/common/files/JamesDSPManagerThePBone.apk` **不在本仓库**（40 MB，且每次汉化都会变）。
安装器会把该文件复制到模块根目录并在开机时安装到 `/data`。

获取方式二选一：

1. 用 `app/patch_app.sh` 从官方 root-full APK 生成汉化版，放到
   `src/common/files/JamesDSPManagerThePBone.apk`；
2. 或者直接用官方 APK（此时界面是英文、且默认不开启按设备配置）。

模块缺少该文件时仍可正常工作——音效与参数持久化都在 native 引擎侧，App 只是调参界面。

## 关键脚本

| 文件 | 作用 |
|---|---|
| `customize.sh` | 安装器：按 ABI 落库、注入 `audio_effects.xml`（library + effect + postprocess） |
| `common/install.sh` | 上游安装流程（库选择、APK 安装、配置补丁） |
| `jdsp_state.sh` | 准备 `/data/vendor/audio`（目录/属主/0660/vendor_audio_data_file） |
| `post-fs-data.sh` / `service.sh` | 开机调用 `jdsp_state_prepare`，只读自检挂载点 |
| `sepolicy.rule` | `hal_audio_default` 对 `vendor_audio_data_file` 的读写规则 |
| `common/files/{arm64,arm}/libjamesdsp.so` | fork 引擎（状态持久化 + 热加载 + 后台重建） |

## 与 Xiaomi/MediaTek 音效模块共存（实测 2026-10）

社区有两个 `XagaSoundBoostTest` 变体（同为该 id，只能装一个）：

| 变体 | dax-default.xml | 是否自带 audio_effects.xml | 结论 |
|---|---|---|---|
| `xaga rubens 音频优化 4-251222` | 109 KB / format 3.5.1 | **是**，且只保留 17 条声明，
丢掉 aec/agc/bassboost/equalizer/reverb/virtualizer/visualizer/volume 等 22 条 | ⚠ 会破坏标准效果与杜比，不要与本模块同装 |
| `xaga＆rubens音效 Final version` | 86 KB / format 3.3.0 | 否（保留系统 39 条声明） | ✅ 可共存，推荐 |

本模块的 `aml.sh` 会在启动时向获胜的 `audio_effects.xml` 合并**恰好一条**
`<apply effect="jamesdsp"/>`，并做空文件/截断保护，因此与第二类模块可以共存。
