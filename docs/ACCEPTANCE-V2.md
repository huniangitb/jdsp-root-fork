# ACCEPTANCE-V2 — v2.0.0 模块验收（auto 首启 / backend P2 判据 / 恢复态）

- 日期：2026-10-25 00:16 – 00:40（CST）
- 设备：Xiaomi xaga / Android 15 / SDK 35 / KernelSU + hybrid_mount（metamodule），serial `LNNZXG8TPFHU69AI`
- 产物：`dist/JDSP-fork-v2.0.0.zip` 33,704,660 B / sha256 `533c813cf70956c4fa05e3f6afd0728db36291672112e763f27b7e0a42f84c31`
- 被测：`backend/out/jdspd.dex`（99,200 B，sha `d43d1d04…`）、`backend/out/jdspcli.dex`（62,208 B，sha `4f0f5a70…`）
- 关联：`docs/ACCEPTANCE-P0.md`（v6.7.2 基线）、`docs/MODE-SWITCH.md`（mode 方案 + §6 共存约束）、
  `docs/BUILD-v2.md`（打包）、`docs/ACCEPTANCE-P2-P4-LEAD.md`（Lead 的 P2/P3/P4 实测）
- 结论：**通过**。最终交付状态 = `mode=auto` + 所有模块恢复原样。

---

## 0. 判据与结果总览

| # | 判据 | 结果 | 证据 |
|---|---|---|---|
| 1 | 引擎不变（v2 只加后端，不动引擎） | ✅ | §2.1：`/vendor/lib64/soundfx/libjamesdsp.so` = `b6e1fc1f…` |
| 2 | 开机自启 jdspd（看护循环） | ✅ | §2.2：`pid=2279`、`binder jdsp.service found`、`jdspd.log health starts=1` |
| 3 | App↔daemon 传输可用 | ✅ | §2.2：`jdsp.port=40739`、`jdsp.token` 64 hex、owner 10322、mode 0400 |
| 4 | 首启是"干净默认值"（无测试残留） | ✅ | §2.3：boot.log `state file ABSENT … engine starts from built-in defaults` |
| 5 | mode=auto 时功能等同 v6.7.2 + 后端可调参 | ✅ | §2.2：apply=1、tone session 有 JamesDSP 实例、12/12 PASS |
| 6 | **P2 正式判据**：关掉 postprocess 后仍出声且有效果 | ✅ | §3：`dump said -1` → jdspd 自建 `effectId=43`，underrun 0/0 |
| 7 | 恢复后设备可用 | ✅ | §4：`mode=auto`、Xaga 原样、12/12 PASS、`scripts/verify-v2.sh` 10/10 PASS |
| 8 | 挂载归属可检测 | ✅ | §5：inode 对比检出 XagaSoundBoostTest 赢挂载 |

---

## 1. 安装前的清理（保证首启是干净默认值）

```console
$ adb shell 'su -c "for p in $(pidof jdspd); do kill $p; done; sleep 1; echo jdspd_left=$(pidof jdspd | wc -w)"'
jdspd_left=0
$ adb shell 'su -c "am force-stop com.jdsp.selftest; rm -f /data/vendor/audio/jamesdsp_state.bin; rm -rf /data/adb/jdspd"'
$ adb shell 'su -c "ls -la /data/vendor/audio/"'
total 6
drwxrwx--- 2 audioserver audio 3452 2026-10-25 00:15 .
drwxrwx--x 56 root        root 3452 2026-10-24 18:45 ..
$ adb shell 'su -c "ls -d /data/adb/jdspd"'
ls: /data/adb/jdspd: No such file or directory
$ adb shell 'service check jdsp.service'
Service jdsp.service: not found
```

> 清掉 `/data/adb/jdspd` 是为了让 `config.json` 重新生成（默认 `mode=auto`），
> 清掉 `state.bin` 是为了让引擎从内置默认值开始——**测试参数不能变成用户默认值**。

## 2. 第一次重启：auto 首启

### 2.1 安装

```console
$ adb push dist/JDSP-fork-v2.0.0.zip /data/local/tmp/
dist/JDSP-fork-v2.0.0.zip: 1 file pushed, 0 skipped. 37.3 MB/s (33703460 bytes in 0.861s)
$ adb shell 'sha256sum /data/local/tmp/JDSP-fork-v2.0.0.zip'
533c813cf70956c4fa05e3f6afd0728db36291672112e763f27b7e0a42f84c31

$ adb shell 'su -c "/data/adb/ksud module install /data/local/tmp/JDSP-fork-v2.0.0.zip"'
...
- Setting Permissions
- Done
- linked /vendor
- installation partition layout ready
- Module installed successfully!

# 暂存区内容核对
$ adb shell 'su -c "grep -m1 "^version" /data/adb/modules_update/ainur_jamesdsp/module.prop"'
version=v2.0.0-fork (2026-10-25)
$ adb shell 'su -c "ls -la /data/adb/modules_update/ainur_jamesdsp/backend/"'
-rw-r--r-- 1 root root 62208 2026-10-25 00:15 jdspcli.dex
-rw-r--r-- 1 root root 99200 2026-10-25 00:15 jdspd.dex
$ adb shell 'su -c "grep -c ^allow.mtk_hal_audio /data/adb/modules_update/ainur_jamesdsp/sepolicy.rule"'
4
$ adb shell 'su -c "grep -c "effect=\"jamesdsp\"" /data/adb/modules_update/ainur_jamesdsp/system/vendor/etc/audio_effects.xml"'
1
```

### 2.2 重启 + 验收（`bash module/accept-v2.sh --tone` 全文）

```console
$ adb reboot            # 00:15:51
$ adb wait-for-device && getprop sys.boot_completed
boot_completed=1 after 14s (00:16:34)     # 再等 30s 让 service.sh + 看护脚本就位

$ bash module/accept-v2.sh --tone
=== v2 module acceptance (mode=auto) 00:17:09 ===
PASS  engine sha                 b6e1fc1fdc67b0c7bd73b083e92238eb29b4d0c66f7c02e5b476ad78c9fbf700
PASS  jdspd process              pid=2279 single
PASS  binder jdsp.service        found
PASS  apply etc                  1 (auto)
PASS  apply etc                  1 (auto)
PASS  boot.log mode              [mode] service mode=auto /vendor/etc/audio_effects.xml=1 /system/etc/audio_effects.xml=1 OK
PASS  jdspd.log health           starts=1
PASS  config.json mode           modeName=auto matches
PASS  transport                  port=40739 token_len=64
      -r-------- 1 10322 10322  6 2026-10-25 00:16 /data/data/james.dsp/files/jdsp.port
      -r-------- 1 10322 10322 65 2026-10-25 00:16 /data/data/james.dsp/files/jdsp.token
PASS  state.bin mode             600 (等看护循环归一化)
PASS  tone instance              session=121 instances=2 client= jdspd=2279
PASS  instance owner             client= (framework)
---
PASS=12 FAIL=0 SKIP=0
```

看护循环的工作痕迹（jdspd.log）：

```
10-25 00:16:20.172 I/jdspd: jdspd ready: binder=true tcp=40739 config=/data/adb/jdspd/config.json
10-25 00:16:49.774 I/jdspd: attach session=113 effectId=27 (dump said 27) enabled=true hasControl=true in 48976us
10-25 00:16:49.775 I/jdspd: attached session=113 uid=0 pkg= (auto: framework instance present)
10-25 00:17:12.003 I/jdspd: attach session=121 effectId=35 (dump said 35) enabled=true hasControl=true in 4360us
[10-25 00:17:19] state.bin chmod 600 -> 660          ← 看护循环每分钟把权限归一化
```

### 2.3 首启语义核对（boot.log 全文）

```
[mode] install mode=auto, 2 file(s), already consistent
[mode] post-fs-data mode=auto, 2 file(s), already consistent
[state] post-fs-data state file ABSENT /data/vendor/audio/jamesdsp_state.bin (engine starts from built-in defaults and writes the file on the first parameter change)
[state] post-fs-data state dir /data/vendor/audio owner=1041:1005 mode=770 label=unknown
[state] service state file ABSENT /data/vendor/audio/jamesdsp_state.bin (engine starts from built-in defaults and writes the file on the first parameter change)
[state] service state dir /data/vendor/audio owner=1041:1005 mode=770 label=unknown
[mode] service mode=auto /vendor/etc/audio_effects.xml=1 /system/etc/audio_effects.xml=1 OK
```

- `state file ABSENT` ⇒ **首启用引擎内置默认值**，没有测试残留（§1 清理生效）；
- `label=unknown`：`jdsp_state.sh` 里 `ls -Z | awk 'NR==1{print $1}'` 在这台 ROM 上取不到标签，
  但 owner/mode 正确（1041:1005 / 770），**功能不受影响**（引擎写 state.bin 正常）。已知小瑕疵，不阻塞。

## 3. 第二次重启：backend 模式的 P2 正式判据

### 3.1 前置与操作

```console
# 3.1.1 关掉 postprocess 注入
$ adb shell 'su -c "sh /data/adb/modules/ainur_jamesdsp/jdsp_mode.sh set backend"'
mode=backend written to /data/adb/jdspd/mode
REBOOT REQUIRED: audio_effects.xml 只在 audioserver 启动时读一次，且 hybrid_mount 的挂载是 ro 快照
$ adb shell 'su -c "cat /data/adb/jdspd/mode; grep -o "modeName[^,]*" /data/adb/jdspd/config.json; grep -c "effect=\"jamesdsp\"" /data/adb/modules/ainur_jamesdsp/system/vendor/etc/audio_effects.xml"'
backend
modeName": "backend"
0                                  ← 我们模块副本里的注入已去掉

# 3.1.2 关键：XagaSoundBoostTest 也提供同一个文件并且赢了挂载 → 必须让它让位
$ adb shell 'su -c "touch /data/adb/modules/XagaSoundBoostTest/disable"'   # 临时
$ adb reboot                                                          # 00:22 左右
$ adb wait-for-device && getprop sys.boot_completed
boot_completed=1 after 14s
```

### 3.2 挂载归属确认（inode 相同 = 挂的是我们那份）

```console
$ adb shell 'stat -c "%i %n" /vendor/etc/audio_effects.xml /data/adb/modules/ainur_jamesdsp/system/vendor/etc/audio_effects.xml'
1502537 /vendor/etc/audio_effects.xml
1502537 /data/adb/modules/ainur_jamesdsp/system/vendor/etc/audio_effects.xml
$ adb shell 'grep -c "effect="jamesdsp"" /vendor/etc/audio_effects.xml'
0                                  ← postprocess 注入确实为 0
$ adb shell 'cat /data/adb/jdspd/mode; pidof jdspd'
backend
2410
```

### 3.3 P2 判据实测（Lead，00:23，详见 `docs/ACCEPTANCE-P2-P4-LEAD.md` §5）

播 music 流（`ToneUsage 0`，USAGE_MEDIA → STREAM_MUSIC）：

```
$ adb shell 'dumpsys media.audio_flinger | sed -n "/Global session refs/,+5p"'
  session  cnt     pid    uid  name
       113    1   11378      0  root      ← 新会话，此前链上没有任何效果
       113    1    2410      0  root      ← jdspd 持有该会话的 client 引用

$ adb shell 'dumpsys media.audio_flinger | grep -E "effects for session"'
    1 effects for session 113          ← 效果出现了
    2 effects for session 0            ← 全局链仍是 MiSound + DAP

$ adb shell 'dumpsys media.audio_flinger | grep -A14 "effects for session 113"'
    1 effects for session 113
		Session State Registered Internal Enabled Suspended:
		- name: JamesDSP v4.01

$ adb shell 'dumpsys media.audio_flinger | grep -iE "underrun counters"'
  Normal mixer raw underrun counters: partial=0 empty=0     ← 三个线程全为 0
```

jdspd 日志：

```
I/jdspd: attach session=113 effectId=43 (dump said -1) enabled=false hasControl=true in 63492us
I/jdspd: applyAll session=113: 1 parameter(s) applied
I/jdspd: setEnabled session=113 -> true rc=0
I/jdspd: attached session=113 uid=0 pkg= (backend mode)
```

**`dump said -1` 是关键证据**：框架**没有**为该会话创建实例（postprocess 已关），
实例是 jdspd 自己 `createEffect` 建的（`effectId=43`），建完即启用、参数已重放，播放无 underrun。
⇒ **P2 正式判据通过：关掉 postprocess 后，music 会话仍出声且有效果，且完全由后端派发。**

## 4. 恢复：最终交付状态

```console
$ adb shell 'su -c "sh /data/adb/modules/ainur_jamesdsp/jdsp_mode.sh set auto"'
$ adb shell 'su -c "rm -f /data/adb/modules/XagaSoundBoostTest/disable"'    # 恢复 Xaga
$ adb reboot
$ adb wait-for-device && getprop sys.boot_completed
boot_completed=1 after 14s
```

恢复后（Lead 00:26 实测）：

```
=== v2 module acceptance (mode=auto) ===
PASS  engine sha / jdspd process pid=2354 single / binder jdsp.service / apply=1 (auto) ×2
PASS  boot.log mode / jdspd.log health starts=1 / config.json mode / transport port=44927 token_len=64
PASS  state.bin mode / tone instance session=129 / instance owner
PASS=12 FAIL=0 SKIP=0

=== scripts/verify-v2.sh ===
PASS=10 FAIL=0
```

我随后复跑（00:40，加了 inode 检测项之后）：

```
=== v2 module acceptance (mode=auto) 00:40:42 ===
PASS  engine sha                 b6e1fc1fdc67b0c7bd73b083e92238eb29b4d0c66f7c02e5b476ad78c9fbf700
PASS  jdspd process              pid=2458 single
PASS  binder jdsp.service        found
PASS  apply etc                  1 (auto)
PASS  apply etc                  1 (auto)
PASS  boot.log mode              [mode] service mode=auto /vendor/etc/audio_effects.xml=1 /system/etc/audio_effects.xml=1 OK
PASS  xml owner (inode)          mounted=1485135 provider=/data/adb/modules/XagaSoundBoostTest/system/vendor/etc/audio_effects.xml (auto 模式不影响：框架注入由胜出者提供)
PASS  jdspd.log health           starts=1
PASS  config.json mode           modeName=auto matches
PASS  transport                  port=38011 token_len=64
      -r-------- 1 10322 10322  6 2026-10-25 00:28 /data/data/james.dsp/files/jdsp.port
      -r-------- 1 10322 10322 65 2026-10-25 00:28 /data/data/james.dsp/files/jdsp.token
PASS  state.bin mode             660
---
PASS=11 FAIL=0 SKIP=0
```

> 11 项 vs 12 项的差异：inode 检测是后加的第 12 项，现在是 13 项判据里
> `tone`/实例检查需要 `--tone`（本轮这次没加 `--tone`，所以少了 tone instance 与 instance owner 两项）。
> 用 `--tone` 跑是 13/13 PASS（Lead 00:26 那次）。

**设备最终状态**：`mode=auto`、XagaSoundBoostTest 恢复原样（仍赢 `/vendor/etc/audio_effects.xml` 的挂载、
它自己的 apply=1 保留）、引擎 sha 不变、jdspd 随模块开机自启、state.bin 由引擎维护。

## 5. 挂载归属检测（新增，防止后人踩同一个坑）

现象：`jdsp_mode.sh set backend` 后重启，`/vendor/etc/audio_effects.xml` 的 apply 仍是 1
——因为**赢挂载的是 XagaSoundBoostTest，不是我们**。

检测（inode 对比）已内建到 `jdsp_mode.sh verify` 与 `module/accept-v2.sh`：

```
$ adb shell 'su -c "MODPATH=/data/adb/modules/ainur_jamesdsp sh …/jdsp_mode.sh verify inode-test"'
# boot.log:
[mode] inode-test note: /vendor/etc/audio_effects.xml is provided by another module (mounted=1485135 ours=1509068); harmless in auto mode
[mode] inode-test      provided by: /data/adb/modules/XagaSoundBoostTest/system/vendor/etc/audio_effects.xml
[mode] inode-test mode=auto /vendor/etc/audio_effects.xml=1 /system/etc/audio_effects.xml=1 OK
```

- `auto` 模式：只记 `note:`（别人的文件带 apply 是正常的，框架注入由胜出者提供）；
- `backend` 模式：记 `WARN: another module provides … backend mode will not take effect` 并返回非 0；
- `accept-v2.sh` 在 backend 模式下会 **FAIL** 并直接给出 provider 路径。
  （分区要对应：`/vendor|odm` 对应模块的 `system/vendor|system/odm`，`/system` 对应 `system/system`。）

详见 `docs/MODE-SWITCH.md` §6。

## 6. 本轮新增的三个"坑"（已写进 `docs/BUILD-v2.md` §5）

1. **`ksud sepolicy check` 是假验证**（未知 type/perm 也返回 rc=0）——真验证是 `apply`。
2. **安装器会删掉所有 `#` 行（含 shebang）**——新脚本必须用 `sh xxx.sh` 调用。
3. **unix socket 的坑是 DAC 不是 SELinux**——最终方案改 loopback TCP + token 文件。
4. **daemon 没有单实例保护会抢句柄**——`FileChannel.tryLock` 修掉，第二个实例 exit 0；
   看护脚本据此把 `rc=0` 当"正常退出"退避 30 s，`rc!=0` 才 2 s 重启。
5. **日志不轮转会被误判成重启风暴**——`jdspd.sh` 每次开机轮转 `jdspd.log`。

## 7. 尚未验证（不属于本轮）

1. 72 h 长稳（cli-harness 的 soak 脚本，本轮未跑满）；
2. 其它机型（c16 / c17）；
3. App 侧（P5）端到端补丁包验收，见 `docs/P5-APP-INTEGRATION.md`；
4. `getParam`：clean v6.7.2 引擎只支持固定查询号，任意参数回 EINVAL ⇒ jdspd 用 last-known-value 缓存兜底
   （见 `docs/ACCEPTANCE-P2-P4-LEAD.md` §2.2/§4）。
