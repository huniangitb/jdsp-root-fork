# MODE-SWITCH — `mode=auto|backend` 的注入时机、挂载时机与切换方案

- 日期：2026-10-25
- 适用：xaga / Android 15 / KernelSU + hybrid_mount（metamodule），模块 v6.7.2 基线 + v2 后端
- 结论速查：**改 mode 必须重启**（同一次开机内没有任何可靠办法改已挂载的 audio_effects.xml）；
  回退不依赖 jdspd（纯脚本），最坏情况删一个文本文件即可回 `auto`。

---

## 1. 两件必须先搞清楚的事

### 1.1 `<apply effect="jamesdsp"/>` 是谁、在什么时候写进去的

注入发生在**安装时**，不是每次开机：

| 阶段 | 脚本 | 行为 | 实测证据 |
|---|---|---|---|
| 安装（`ksud module install`） | `customize.sh` → `common/functions.sh` → `common/install.sh` | 把系统 `audio_effects.{xml,conf}` 从安装期只读镜像 `$ORIGDIR` 拷成**模块内副本**（`$MODPATH/system/vendor/etc/…`），删掉旧的 jdsp 条目，插入 `<library name="jdsp">`+`<effect name="jamesdsp">`，最后调 `common/jdsp_postprocess.sh:jdsp_apply_music` 补 `<postprocess><stream type="music"><apply effect="jamesdsp"/></stream></postprocess>` | 安装完成后 `/data/adb/modules_update/ainur_jamesdsp/system/vendor/etc/audio_effects.xml`：`grep -c 'effect="jamesdsp"'` = **1** |
| 每次开机（早期） | `post-fs-data.sh` → `aml.sh:patchxml` | **只补** `<library>`/`<effect>` 声明（`grep -q "$LIBNAME" && continue`），**完全不碰 `<postprocess>`** | 装机后删掉 apply 行再开机，apply 计数仍为 0（aml.sh 不会加回来） |
| 每次开机（后期） | `service.sh` | 只读自检（`logs/mount_check.log`），不改 XML | `--- postprocess apply lines … /vendor/etc/audio_effects.xml: 1` |

**推论**：`<apply>` 只存在于**模块内的 XML 副本**里；上游系统文件永远是干净的。
所以"关掉 postprocess 注入"= 把模块副本里的那一行去掉，并且保证安装/开机流程不把它加回来。

### 1.2 hybrid_mount 什么时候、以什么方式挂上去

- 本机的挂载由 metamodule `hybrid_mount` 负责（`/data/adb/metamodule -> /data/adb/modules/hybrid_mount`），
  `metamount.sh` = `hybrid-mount boot metamount`，在 **post-fs-data 阶段的 metamount 钩子**里执行；
  配置 `default_mode = "overlay"`、`overlay_mode = "ext4"`。
- 实际挂载形态是**按文件**从镜像里挂出来的、且 **`ro`**：

```console
$ adb shell 'mount | grep -E "audio_effects|libjamesdsp"'
/dev/block/sdc86 on /vendor/etc/audio_effects.xml type f2fs (ro,lazytime,seclabel,...)
/dev/block/sdc86 on /system/etc/audio_effects.xml type f2fs (ro,...)
/dev/block/sdc86 on /system/etc/audio_effects.conf type f2fs (ro,...)
/dev/block/sdc86 on /vendor/lib/soundfx/libjamesdsp.so type f2fs (ro,...)

$ adb shell su -c 'stat -c "%i %n" /vendor/etc/audio_effects.xml \
    /data/adb/modules/ainur_jamesdsp/system/vendor/etc/audio_effects.xml'
1485135 /vendor/etc/audio_effects.xml                                  # 挂载出来的
1500400 /data/adb/modules/ainur_jamesdsp/system/vendor/etc/audio_effects.xml   # 模块里的
```

inode 不同 + 挂载点是独立的 `ro` mount ⇒ 挂载时把模块文件**拷进镜像**（快照），
**挂载之后再改模块副本对本次开机无效**。

- 时序：`metamount`（拷贝+挂载）→ 各模块 `post-fs-data.sh` → `late-init` → audioserver 启动
  ⇒ 模块 `post-fs-data.sh` 即使改了模块副本，也已经赶不上本次挂载；而 audioserver 又只在启动时
  读一次 `audio_effects.xml`（`AudioPolicyEffects` 在 `startOutput` 时按 XML 建 postprocess 实例）。

**两个"必须重启"的原因叠在一起**：镜像快照已固定 + XML 只读一次。所以：
> **只重启 audioserver（`kill $(pidof audioserver)`）也没用**——它重启后读到的还是镜像里那份旧 XML。

---

## 2. 结论：三个问题

### Q1. auto → backend 必须重启吗？

**是，必须重启（整机 reboot）。**

- 挂载是 `ro` 快照（§1.2），运行期改模块副本无效；
- audioserver 只在启动时读 XML，运行期改挂载点内容也不生效（且挂载点 `ro`，改不了）；
- 重启 audioserver 不重启整机同样无效（镜像内容没变）。

代价：一次重启（本机约 40 s 到 `boot_completed=1`，另有模块 `service.sh` 的 8 s sleep）。

### Q2. backend → auto 怎么回退？

三条路，从轻到重，**都不依赖 jdspd 能不能起来**（脚本是纯 shell）：

| 方式 | 命令 | 生效 |
|---|---|---|
| 正常回退 | `sh /data/adb/modules/ainur_jamesdsp/jdsp_mode.sh set auto` → 重启 | 下次开机注入恢复 |
| 兜底 1（daemon 坏了/配置被写坏） | `rm -f /data/adb/jdspd/mode` → 重启 | 缺省值就是 `auto` |
| 兜底 2（连模块脚本都不想用） | `rm -rf /data/adb/modules/ainur_jamesdsp/system/{vendor,odm}/etc/audio_effects.xml` → 重启 | 该分区的 XML 副本消失 ⇒ 回落到系统原文件（本来就无 jdsp 注入），音乐照常出声、只是没有 JamesDSP |

反过来 `backend` 模式下如果 jdspd 起不来 = **没有效果**（postprocess 被关掉了），所以 backend 模式必须在
`service.sh` 里做"daemon 存活 + 有实例"的健康检查并写进 boot.log（见 §3.4）。

### Q3. 安装/升级时怎么选？

`common/install.sh` 末尾调用 `jdsp_mode_apply install`：读 `/data/adb/jdspd/mode`（不存在 = `auto`），
按它决定本次安装出来的 XML 副本**要不要** apply 行。

- 全新安装且没有 mode 文件 → `auto` → 与 v6.7.2 原行为一致；
- 老用户升级（`ksud module install` 会重新从 `$ORIGDIR` 拷系统文件并注入 apply）→ 末尾这一步会把 apply 去掉，
  **升级不会偷偷把 backend 用户切回 auto**。

想在打包/首次安装时就指定 mode：安装前先 `echo backend > /data/adb/jdspd/mode`（或把该文件放进
`module/src/backend/mode.default`，`jdspd.sh` 首次运行时会拷成 `/data/adb/jdspd/mode`）。

---

## 3. 方案（v2 落地）

### 3.1 单一事实来源：`/data/adb/jdspd/mode`

```
/data/adb/jdspd/            0700 root:root      # jdspd 的工作目录（日志、socket、配置）
/data/adb/jdspd/mode        0600 root:root      # "auto" 或 "backend"，一行；缺省 auto
/data/adb/jdspd/config.json 0600 root:root      # jdspd 的权威配置（PLAN-v2 §3.1 ConfigStore）
/data/adb/jdspd/jdspd.log   0600 root:root      # 守护进程 stdout/stderr
```

为什么用裸文本而不是 config.json：`post-fs-data.sh` / `install.sh` 要在 **daemon 还没起来**时读它，
用 `cat` 一行最稳（不引入 JSON 解析器，坏文件也能降级成 auto）。

### 3.2 `module/src/jdsp_mode.sh`（新脚本，被 install/post-fs-data/service 复用）

职责：

| 函数 | 说明 |
|---|---|
| `jdsp_mode_get` | 读 mode 文件；非法/缺失 → 输出 `auto`；**永不失败**（`|| echo auto`） |
| `jdsp_mode_apply <tag>` | 枚举模块内 XML 副本（`system/vendor/etc`、`system/etc`、`system/odm/etc`、`vendor/etc`、`odm/etc`、`vendor/etc/audio/sku_*/…`），按 current mode 增删 `<apply effect="jamesdsp"/>`；**inode 保持**的写法（`sed > tmp && cat tmp > file`，不用 `sed -i`）；写 boot.log |
| `jdsp_mode_set <auto|backend>` | 写 mode 文件 → 调 `jdsp_mode_apply` → 打印"需要重启生效" |
| CLI | `sh jdsp_mode.sh get|set <mode>|apply [tag]`（给 App/CLI/debug 用） |

要点：

- **inode 保持**：即使某天有人把模块目录做成 bind mount，`truncate+write` 也能让挂载方看到新内容；
  `sed -i` 会换 inode，对 bind mount 会"改了但挂载点还是旧的"。
- **幂等**：apply 行存在就不再插；不存在才插，插的位置沿用 `jdsp_postprocess.sh` 的算法（注释块不算）。
- **不动上游文件**：只改 `$MODPATH` 下的副本，系统分区始终干净（§1.1）。
- **失败可见**：任何一步失败写 `boot.log`，不 `exit 1`（不要因为一个 XML 把整次开机搞坏）。

### 3.3 挂钩位置

| 文件 | 挂钩 | 作用 |
|---|---|---|
| `common/install.sh` | 末尾 `. "$MODPATH/jdsp_mode.sh"; jdsp_mode_apply install` | 安装/升级后立即符合当前 mode |
| `post-fs-data.sh` | `. "$MODPATH/aml.sh"` 之后 `. "$MODPATH/jdsp_mode.sh"; jdsp_mode_apply post-fs-data` | **自愈**：把模块副本纠正到 mode（本次挂载已快照，故对本次开机无效、对下次生效） |
| `service.sh` | 自检段里调 `jdsp_mode_verify` | 读**挂载后**的 `/vendor|/system|/odm/etc/audio_effects.xml`，比对 apply 数量与 mode；不一致写 `WARN … 需要重启`（这就是"用户以为切了其实没切"的唯一可靠检测点） |
| `jdspd`（AIDL `setMode`） | 写 mode 文件 → `Runtime.exec("sh $MODPATH/jdsp_mode.sh apply daemon")` → 返回 `reboot_required=true` | App 点开关后 UI 直接告诉用户"重启生效" |

### 3.4 backend 模式的健康检查（防止"没声音了"）

`service.sh` 在 mode=backend 时：

1. 等 `service check media.audio_flinger` 成功（daemon 起得来的前提）；
2. 起 `jdspd.sh`（见 docs/MODULE-INTEGRATION.md）；
3. 60 s 后写一条自检到 boot.log：
   `mode=backend jdspd=<pid|DOWN> instances=$(dumpsys media.audio_flinger | grep -c f27317f4)`；
4. **不自动切回 auto**（自动切换会在崩溃循环里来回抖），只在 `boot.log` 和 `jdspcli stats` 里暴露。

### 3.5 为什么不用"运行期 patch 挂载点"

- 挂载点 `ro`，且是镜像快照（§1.2）；
- 就算 remount rw 改了，audioserver 已经读过 XML，postprocess 实例已经建好，不会重建；
- 反向操作（卸载效果）还得逐个 effect 销毁，等于把 PLAN-v2 的 P2 用另一条路重做一遍。
  ⇒ **重启是唯一可靠路径**，和 PLAN-v2 §4 的结论一致。

---

## 4. 已验证 / 待验证

| 项 | 状态 |
|---|---|
| 注入只发生在安装时、aml.sh 不管 postprocess | ✅ 源码 + 装机实测（§1.1） |
| 挂载是 ro 快照、inode 与模块副本不同 | ✅ `mount` + `stat`（§1.2） |
| audioserver 只在启动时读 XML | ✅ AOSP `AudioPolicyEffects` 行为 + 本机"改 XML 不重启无效果"的历史现象 |
| `jdsp_mode.sh` 全链路（install/post-fs-data/service/CLI） | ⏳ task-4 落盘后随 v2.0.0 包验证 |
| `mode=backend` 关掉注入后 jdspd 自挂能出声 | ⏳ 依赖 daemon-core 的 EffectManager（PLAN-v2 §2.3 已验证 createEffect 可建链） |

## 5. 顺带发现的残留（不影响功能）

`/vendor/etc/audio_effects.xml.jdsp.bak` 仍被挂载 —— 它不是本模块的产物，而是 6.7.3~6.7.6 实验版
把备份写进了**别的模块**的目录（`/data/adb/modules/XagaSoundBoostTest/system/vendor/etc/audio_effects.xml.jdsp.bak`），
hybrid_mount 每次开机照挂。属于实验版残留，可安全删除：

```sh
rm -f /data/adb/modules/XagaSoundBoostTest/system/vendor/etc/audio_effects.xml.jdsp.bak   # 下次开机挂载点消失
```
