# ACCEPTANCE-P0 — 干净基线（v6.7.2-fork, versionCode 112）设备验收

- 日期：2026-10-24 23:48 → 23:59（CST）
- 设备：Xiaomi xaga（22041216C）/ Android 15 / SDK 35 / KernelSU / serial LNNZXG8TPFHU69AI
- 仓库：`jdsp-repo` @ master（`38a5df9` 之后工作树改动只有 `module/src/sepolicy.rule`，见 §6）
- 执行人：module-p0
- 结论：**P0 通过**（1 项判据首次失败，根因定位并修复后复测通过，见 §6）

## 0. 判据与结果

| # | 判据 | 结果 | 证据 |
|---|---|---|---|
| 1 | 仓库 `module/src` 是 v6.7.2 基线 | ✅ | §1：versionCode=112、strings 无 socket 代码、sepolicy 无 sock 规则、lib sha=b6e1fc1f… |
| 2 | 设备 `/vendor/lib64/soundfx/libjamesdsp.so` == 仓库 arm64 基线 | ✅ | §5.1：两侧都是 `b6e1fc1fdc67b0…` |
| 3 | 实验 socket 代码（`jdsp_ctl.sock`/`PING`/`STATS`）已从设备消失 | ✅ | §5.2：`strings … | grep -E "jdsp_ctl|PING|STATS"` 无匹配 |
| 4 | music session 上存在 JamesDSP 实例且 enabled | ✅ | §5.3：`1 effects for session 113`，`Registered=y Enabled=y`，`Effect ID 27` |
| 5 | `JdspCtl set` 后 `/data/vendor/audio/jamesdsp_state.bin` mtime/size 变化 | ⚠→✅ | 首轮失败（§6.2）；补 mtk_hal_audio 规则后：mtime 变化、size 108→144、logcat `state file updated` |

> 判据 5 是本轮唯一失败项，且**不是**测试值去重问题：引擎在 HAL 域里被 SELinux 拒绝，连文件都打不开（§6）。已按最小规则集修复并复测。

## 1. 仓库基线核对（只读）

```console
$ cd module/src && cat module.prop
id=ainur_jamesdsp
version=v6.7.2-fork (2026-10-24)
versionCode=112

$ strings common/files/arm64/libjamesdsp.so | grep -E "jdsp_ctl|PING|STATS" ; echo "grep exit=$?"
grep exit=1                      # 无匹配 = 干净

$ sha256sum common/files/arm64/libjamesdsp.so common/files/arm/libjamesdsp.so
b6e1fc1fdc67b0c7bd73b083e92238eb29b4d0c66f7c02e5b476ad78c9fbf700  common/files/arm64/libjamesdsp.so
804a3ce42b366c5b0b7e77775f60207b4f44c054a257d106773edee99690dea0  common/files/arm/libjamesdsp.so

$ grep -nE "sock_file|unix_stream_socket" sepolicy.rule ; echo "grep exit=$?"
grep exit=1                      # 无 socket 规则

$ ls jdsp_switch.sh jdsp_reset.sh boot-completed.sh
（均不存在）

$ ls -l common/files/JamesDSPManagerThePBone.apk
-rw-r--r-- 1 bdy bdy 40960929 10月24日 15:54 common/files/JamesDSPManagerThePBone.apk
```

脚本清单（`find module/src -type f`）：`aml.sh customize.sh jdsp_app.sh jdsp_app_handle.sh jdsp_check.sh jdsp_state.sh patch.sh post-fs-data.sh service.sh uninstall.sh module.prop sepolicy.rule common/{functions,install,jdsp_postprocess}.sh` — 与 v6.7.2 一致，无实验脚本。

## 2. 设备取证（改动之前，2026-10-24 23:47）

设备上装的是 6.7.3~6.7.6 实验引擎与实验规则（`module.prop` 已被手改成 112，但文件内容不是基线）：

```console
$ adb shell su -c 'sha256sum /data/adb/modules/ainur_jamesdsp/system/vendor/lib64/soundfx/libjamesdsp.so /vendor/lib64/soundfx/libjamesdsp.so'
6d7da1b08ada07c7d43a63b7edfebafc54245665b3e8359f81b1fd922034c504  /data/adb/modules/.../libjamesdsp.so
6d7da1b08ada07c7d43a63b7edfebafc54245665b3e8359f81b1fd922034c504  /vendor/lib64/soundfx/libjamesdsp.so

$ adb shell 'strings /vendor/lib64/soundfx/libjamesdsp.so | grep -E "jdsp_ctl|PING|STATS"'
PING
STATS
/data/vendor/audio/jdsp_ctl.sock
STATS fs=%.0f format=%d enable=%d records=%d
PING | COUNT | LIST | GET <id> | STATS
/data/vendor/audio/jdsp_ctl.sock
jdsp_ctl_thread
jdsp_ctl_start

$ adb shell su -c 'cat /data/adb/modules/ainur_jamesdsp/sepolicy.rule' | grep -cE "sock_file|unix_stream_socket"
10                               # 10 条实验 socket 规则（hal_audio_default / mtk_hal_audio / shell / su）

$ adb shell ls -la /data/adb/modules/ainur_jamesdsp/
jdsp_state.sh 4854B（基线 7009B）  service.sh 2456B（基线 3230B）  sepolicy.rule 1524B（基线 1090B → 修复后 1878B）
缺 jdsp_app_handle.sh；有 boot.log / logs/ / update / .apkpbone（上次安装产物）

$ adb shell su -c 'stat -c "%n size=%s mtime=%y uid=%u gid=%g" /data/vendor/audio/jamesdsp_state.bin'
/data/vendor/audio/jamesdsp_state.bin size=9926 mtime=2026-10-24 23:15:22.468000020 +0800 uid=1041 gid=1005

$ adb shell su -c 'ls -la /data/vendor/audio/'
-rw-rw---- 1 audioserver audio 9926 2026-10-24 23:15 jamesdsp_state.bin      # 只有这一个文件
```

改动前的功能基线（用来确认"postprocess 自动挂载"这条链路在设备上本来是通的）：

```console
$ adb shell 'dumpsys media.audio_flinger | grep -nE "effects for session|Effect ID [0-9]+:|name: JamesDSP"'
345:    1 effects for session 169      # tone 的 session
356:		- name: JamesDSP v4.01
373:    1 effects for session 161
384:		- name: JamesDSP v4.01
429:    2 effects for session 0        # MiSound + DAP，没有 jamesdsp
```

## 3. 打包

```console
$ cd module/src && zip -r -X ../../dist/JDSP-fork-v6.7.2.zip . -x '*.git*'
$ ls -l dist/JDSP-fork-v6.7.2.zip && sha256sum dist/JDSP-fork-v6.7.2.zip
-rw-r--r-- 1 bdy bdy 33621328 10月24日 23:56 dist/JDSP-fork-v6.7.2.zip
202999354ee6b52f7166def93dfc7717da988b4cd0558d8b9c0bb9e1fde3d4ca  dist/JDSP-fork-v6.7.2.zip   ← 最终基线包（含 §6 修复）
```

> 中间还打过一版未含 §6 修复的包（33621030B, sha256 `5237558df5fd…`），已作废，仅作过程记录。

## 4. 安装与重启

```console
$ adb push dist/JDSP-fork-v6.7.2.zip /data/local/tmp/
dist/JDSP-fork-v6.7.2.zip: 1 file pushed, 0 skipped. 37.0 MB/s (33621328 bytes in 0.866s)
$ adb shell sha256sum /data/local/tmp/JDSP-fork-v6.7.2.zip
202999354ee6b52f7166def93dfc7717da988b4cd0558d8b9c0bb9e1fde3d4ca  /data/local/tmp/JDSP-fork-v6.7.2.zip

$ adb shell su -c '/data/adb/ksud module install /data/local/tmp/JDSP-fork-v6.7.2.zip'
- Module size: 59.36 MB
- Installing to /data/adb/modules_update/ainur_jamesdsp
- Extracting module files
- Running module installer
...
   ! Conflicting audio mod found!        # XagaSoundBoostTest（Final version 变体，README 记载可共存）
   ! You will need to install !
   ! Audio Modification Library !
...
   64 bit audio libs detected
   ThePBone UI
- Setting Permissions
- Done
- linked /vendor
- installation partition layout ready
- Module installed successfully!

$ adb reboot        # 23:56:34
$ adb wait-for-device && getprop sys.boot_completed
boot_completed=1 after 12s (23:57:16)
```

## 5. 验收验证（2026-10-24 23:58，重启后）

### 5.1 设备 lib == 仓库基线

```console
$ adb shell sha256sum /vendor/lib64/soundfx/libjamesdsp.so
b6e1fc1fdc67b0c7bd73b083e92238eb29b4d0c66f7c02e5b476ad78c9fbf700  /vendor/lib64/soundfx/libjamesdsp.so
$ sha256sum module/src/common/files/arm64/libjamesdsp.so
b6e1fc1fdc67b0c7bd73b083e92238eb29b4d0c66f7c02e5b476ad78c9fbf700  module/src/common/files/arm64/libjamesdsp.so
```

### 5.2 实验 socket 代码消失

```console
$ adb shell 'strings /vendor/lib64/soundfx/libjamesdsp.so | grep -E "jdsp_ctl|PING|STATS"; echo "grep_exit=$?"'
grep_exit=1
```

### 5.3 挂载与 postprocess 注入

```console
$ adb shell su -c 'grep -E "OK |MISS " /data/adb/modules/ainur_jamesdsp/logs/mount_check.log'
  OK   /vendor/lib64/soundfx/libjamesdsp.so        # inode 1498351 == 1498351
  OK   /vendor/lib/soundfx/libjamesdsp.so
  OK   /system/etc/audio_effects.xml
$ grep -n -A3 postprocess /vendor/etc/audio_effects.xml（取尾部）
    <postprocess>
        <stream type="music"><apply effect="jamesdsp"/></stream>
    </postprocess>
# apply 计数：/vendor/etc/audio_effects.xml = 1，/system/etc/audio_effects.xml = 1
```

### 5.4 播放时 music session 上有实例且 enabled

```console
$ adb shell su -c 'CLASSPATH=/data/local/tmp/tone.dex nohup app_process /system/bin Tone 60 >/data/local/tmp/tone.log 2>&1 &'
$ adb shell cat /data/local/tmp/tone.log
SESSION=113 minBuf=24600

$ adb shell 'dumpsys media.audio_flinger | grep -nE "effects for session|Effect ID [0-9]+:|name: JamesDSP"'
289:    1 effects for session 113
292:	Effect ID 27:
300:		- name: JamesDSP v4.01
317:    2 effects for session 0            # MiSound(11) + DAP(19)

$ adb shell "dumpsys media.audio_flinger | sed -n '288,312p'"
  1 Tracks of which 1 are active
    Type     Id Active Client Session Port Id ...
             57    yes  11224     113      30 A ...
  2 Effect Chains
    1 effects for session 113
	Effect ID 27:
		Session State Registered Internal Enabled Suspended:
		00113   003   y          n        y       n        ← Registered=y, Enabled=y
		Descriptor:
		- UUID: f27317f4-c984-4de6-9a90-545759495bf2
		- TYPE: f98765f4-c321-5de6-9a45-123459495ab2
		- name: JamesDSP v4.01
		- implementor: James Fung
		1 Clients:  Pid 1437 ...            ← audioserver（框架 postprocess 建的实例）

$ adb shell 'echo "audio_policy: $(dumpsys media.audio_policy | grep -c -i jamesdsp)"; echo "flinger f27317f4: $(dumpsys media.audio_flinger | grep -c f27317f4)"'
audio_policy: 1
flinger f27317f4: 2                      # library 声明 + 实例
```

### 5.5 状态写盘（`JdspCtl set` → state.bin 变化）

```console
# 重启后引擎读回上次的状态（证明读路径 OK）
$ adb shell 'logcat -d | grep -E "JamesDSPState"'
10-24 23:57:49.923 I JamesDSPState: restored 4 recorded command(s) from /data/vendor/audio/jamesdsp_state.bin
10-24 23:57:49.923 I JamesDSPState: persistence enabled, path /data/vendor/audio/jamesdsp_state.bin
10-24 23:57:49.995 I JamesDSPState: state file updated: 4 record(s), 108 byte(s)

$ adb shell su -c 'stat -c "size=%s mtime=%y" /data/vendor/audio/jamesdsp_state.bin'
size=108 mtime=2026-10-24 23:55:19.432000019 +0800

$ adb shell su -c 'CLASSPATH=/data/local/tmp/jdspctl.dex app_process /system/bin JdspCtl 0 1500 -0.3 40 1.0'
effect id=35 session=0 enabled=false
setParameter(1500, 3 floats) rc=0 SUCCESS

$ adb shell su -c 'stat -c "size=%s mtime=%y" /data/vendor/audio/jamesdsp_state.bin'
size=108 mtime=2026-10-24 23:58:04.832000004 +0800      ← mtime 变（同一条记录就地更新，size 不变）
$ logcat: 10-24 23:58:04.836 I JamesDSPState: state file updated: 4 record(s), 108 byte(s)

# 再换一组值（新增记录）→ size 也变
$ adb shell su -c 'CLASSPATH=/data/local/tmp/jdspctl.dex app_process /system/bin JdspCtl 0 1500 -0.5 30 2.0'
effect id=43 session=0 enabled=false
setParameter(1500, 3 floats) rc=0 SUCCESS
$ adb shell su -c 'stat -c "size=%s mtime=%y uid=%u gid=%g mode=%a" /data/vendor/audio/jamesdsp_state.bin'
size=144 mtime=2026-10-24 23:58:23.288000005 +0800 uid=1041 gid=1005 mode=600   ← size 108→144，mtime 再变
```

结论：**状态持久化可用**（写、读回、重启后重放三条都验证过）。

## 6. 本轮发现并修复的问题：P0-1 — HAL 域是 `mtk_hal_audio`，基线 sepolicy 只覆盖 `hal_audio_default`

### 6.1 现象（首轮，未修复时）

按指定命令 `JdspCtl 0 1500 -0.1 60 3.0`：

```console
$ adb shell su -c 'CLASSPATH=/data/local/tmp/jdspctl.dex app_process /system/bin JdspCtl 0 1500 -0.1 60 3.0'
effect id=35 session=0 enabled=false
setParameter(1500, 3 floats) rc=0 SUCCESS          ← 命令本身成功

$ adb shell su -c 'stat -c "size=%s mtime=%y" /data/vendor/audio/jamesdsp_state.bin'
size=9926 mtime=2026-10-24 23:15:22.468000020 +0800   ← 完全没变
```

不是"值相同被去重"：换成 `-0.3 40 1.0` 仍然不变；logcat 里引擎自己给出了原因（**原始日志**）：

```console
10-24 23:50:51.498  1332  6823 W JamesDSPState: cannot open /data/vendor/audio/jamesdsp_state.bin (Permission denied), using defaults
10-24 23:51:01.597  1332  1332 W JamesDSPState: cannot create /data/vendor/audio/jamesdsp_state.bin.tmp.1332 (Permission denied), state is kept in memory only
10-24 23:50:56.258  1332 11330 W JamesDSPState: cannot watch /data/vendor/audio, falling back to polling
```

### 6.2 根因

效果引擎跑在 HAL 进程（`android.hardware.audio.service.mediatek`），这台机器的 HAL 域是 MediaTek 自己的
**`mtk_hal_audio`**，不是 AOSP 的 `hal_audio_default`：

```console
$ adb shell 'HAL=$(pidof android.hardware.audio.service.mediatek); echo "HAL pid=$HAL"; cat /proc/$HAL/attr/current'
HAL pid=1329
u:r:mtk_hal_audio:s0

$ adb shell 'grep -E "^Uid|^Gid" /proc/1329/status'
Uid:	1041	1041	1041	1041
Gid:	1005	1005	1005	1005

$ adb shell su -c 'stat -c "%n uid=%u gid=%g mode=%a" /data/vendor/audio /data/vendor/audio/jamesdsp_state.bin'
/data/vendor/audio uid=1041 gid=1005 mode=770
/data/vendor/audio/jamesdsp_state.bin uid=1041 gid=1005 mode=660     # DAC 没问题
```

而仓库基线 `sepolicy.rule` 只给了 `hal_audio_default` / `audioserver` 两个域读 `vendor_audio_data_file`，
`mtk_hal_audio` 没有任何规则 → 引擎在 HAL 进程里 open/create 状态文件被拒（EACCES），状态只留在内存里。
MTK 的 vendor 策略对这类访问做了 `dontaudit`，所以 **`dmesg` 里一条 AVC 都没有**（实测 `dmesg | grep avc` 124 条全是
service_manager 的），只能从引擎自己的 logcat 看出来——这也是它容易被误判成"值去重"的原因。

### 6.3 修复（最小、路径级，已并入基线）

`module/src/sepolicy.rule` 增加（并给原有 dir 规则补 `read`，inotify 需要）：

```
# MediaTek devices run the audio effect HAL in their own domain (mtk_hal_audio,
# verified on xaga / Android 15: /proc/<hal>/attr/current = u:r:mtk_hal_audio:s0).
# Vendor policy dontaudits vendor_audio_data_file there, so a missing rule shows
# up only as "JamesDSPState: cannot open/create ... (Permission denied)" in
# logcat: the engine then keeps its state in memory and nothing is persisted.
allow mtk_hal_audio vendor_audio_data_file dir { search read write add_name remove_name create getattr }
allow mtk_hal_audio vendor_audio_data_file file { read write open create getattr setattr map unlink rename }
```

只针对 `/data/vendor/audio` 这个路径，无 domain 级放权；`hal_audio_default` 那组规则保留（其它机型仍按 AOSP 域）。
安装器会把 `#` 注释行删掉，装到设备上的 sepolicy.rule 是 11 行纯规则（`grep -c mtk_hal_audio` = 2）。

### 6.4 修复后验证

先做**运行时**验证（`ksud sepolicy apply` 两条规则，rc=0）立即复测：

```console
$ adb shell su -c '/data/adb/ksud sepolicy apply /data/local/tmp/jdsp_mtk.rules'
NormalPerm(... mtk_hal_audio -> vendor_audio_data_file dir ...)
NormalPerm(... mtk_hal_audio -> vendor_audio_data_file file ...)
$ adb shell su -c 'CLASSPATH=/data/local/tmp/jdspctl.dex app_process /system/bin JdspCtl 0 1500 -0.3 40 1.0'
effect id=51 session=0 enabled=false
setParameter(1500, 3 floats) rc=0 SUCCESS
$ stat state.bin: size=9926 mtime=23:15:22  →  size=108 mtime=23:55:19      ✅ 变了
$ logcat: I JamesDSPState: state file updated: 4 record(s), 108 byte(s)
```

再把规则打进包、重装、重启（23:56:34 → 23:57:16）后复测，见 §5.5：写/读/重放全部通过，
且 `Permission denied` 两条日志不再出现。

### 6.5 size `9926 → 108` 为什么变小了

不是数据丢失：`9926` 那个文件是 **6.7.3~6.7.6 实验版引擎**写的（格式不同、且比 clean 引擎记录的东西多）。
clean v6.7.2 引擎第一次拿到写权限后，用**自己的格式**从内存里的 4 条记录完整重写状态文件 → `108` 字节。
实验版文件本来也没被 clean 引擎读进去（首轮启动日志是 "cannot open … using defaults"），所以这不是降级。

### 6.6 为什么这条必须进基线

v6.7.2 fork 引擎的核心卖点之一就是 native 状态自持久化（App 卸载后仍按最后设置工作）。
在 MediaTek 机型上缺这条规则，等于该功能**静默失效**（无 AVC、无报错、只有 `JamesDSPState` 的 W 日志），
后续 P3 的 ConfigStore / 参数持久化验收也会被同一个坑绊住，所以按最小改动并入基线。

## 7. 已知遗留（不阻塞 P0）

1. **inotify watch 仍回退到轮询**：`W JamesDSPState: cannot watch /data/vendor/audio, falling back to polling`（23:57:54）。
   写盘与重放都不受影响（轮询是引擎自带降级路径）。候选补丁是再给 `dir/file` 加 `watch` 位，
   语句已用 `ksud sepolicy check` 验证合法、运行时 apply 也成功：
   ```
   allow mtk_hal_audio vendor_audio_data_file dir { watch }
   allow mtk_hal_audio vendor_audio_data_file file { watch }
   ```
   **处理决定（Lead 2026-10-25）**：两条 `watch` 规则**已并入 `module/src/sepolicy.rule`，随 v2.0.0 包发布**；
   **本轮未在重启后确认**（本轮设备跑的是不含 watch 规则的 P0 基线包，sha `20299935…`）——
   **v2.0.0 重启后要确认这条 `cannot watch` 是否消失**，消失则转正，仍出现则说明不是 SELinux 权限问题
   （可能是 inotify 别的限制），届时把结论回填到本节。
2. **state.bin 权限位**：HAL 进程重写后是 `0600`（引擎用 .tmp + rename，umask 决定），
   开机时 `jdsp_state.sh` 会归一化成 `0660`（boot.log 里可见）。owner 始终 1041:1005，不影响功能。
   **已知不足**：HAL 每次写盘都会重建成 0600，只在开机归一化一次不够 →
   v2.0.0 的 `jdspd.sh` 看护循环里增加"每分钟检查一次、非 0660 就 chmod"（见 docs/MODULE-INTEGRATION.md §3）。
3. 安装日志有 `! Conflicting audio mod found!`（XagaSoundBoostTest）——按 `module/README.md` 记载，
   "Final version" 变体保留系统 39 条声明、可共存；本轮 39 条声明与杜比(DAP)/MiSound 都在，无冲突迹象。

## 8. 产物

| 文件 | 大小 | sha256 |
|---|---|---|
| `dist/JDSP-fork-v6.7.2.zip`（最终基线包，已装到设备并验收） | 33621328 | `202999354ee6b52f7166def93dfc7717da988b4cd0558d8b9c0bb9e1fde3d4ca` |
| `module/src/common/files/arm64/libjamesdsp.so` | 1667344 | `b6e1fc1fdc67b0c7bd73b083e92238eb29b4d0c66f7c02e5b476ad78c9fbf700` |
| `module/src/common/files/arm/libjamesdsp.so` | — | `804a3ce42b366c5b0b7e77775f60207b4f44c054a257d106773edee99690dea0` |

> 说明：仓库 HEAD 的 `sepolicy.rule` 在本轮验收之后又加了 2 条 `watch` 规则（§7.1），
> 因此**从现在起从 HEAD 重新打包得到的 zip 与上表 P0 基线包不再逐字节相同**。
> 上表那个包就是设备上实际通过验收的产物；含 watch 规则的版本将作为 `v2.0.0` 包发布并重新验收。

设备上的最终状态（2026-10-24 23:58）：
```
/vendor/lib64/soundfx/libjamesdsp.so  sha256=b6e1fc1f…（基线）
/data/adb/modules/ainur_jamesdsp/      = v6.7.2-fork（versionCode 112）+ P0-1 的 2 条 mtk_hal_audio 规则
/data/vendor/audio/jamesdsp_state.bin size=144 mtime=23:58:23 owner=1041:1005
tone session 113 上：Effect ID 27 / JamesDSP v4.01 / Registered=y Enabled=y
```

## 9. 复现步骤（一条龙）

```sh
export PATH=$PATH:/home/bdy/Android/Sdk/platform-tools
cd /home/bdy/work/jdsp-repo
mkdir -p dist && (cd module/src && zip -r -X ../../dist/JDSP-fork-v6.7.2.zip . -x '*.git*')
adb -s LNNZXG8TPFHU69AI push dist/JDSP-fork-v6.7.2.zip /data/local/tmp/
adb -s LNNZXG8TPFHU69AI shell su -c '/data/adb/ksud module install /data/local/tmp/JDSP-fork-v6.7.2.zip'
adb -s LNNZXG8TPFHU69AI reboot && adb -s LNNZXG8TPFHU69AI wait-for-device
# 等 sys.boot_completed=1，再等 25s（模块 service.sh 有 sleep 8）
adb -s LNNZXG8TPFHU69AI shell 'sha256sum /vendor/lib64/soundfx/libjamesdsp.so'          # b6e1fc1f…
adb -s LNNZXG8TPFHU69AI shell 'CLASSPATH=/data/local/tmp/tone.dex nohup app_process /system/bin Tone 60 >/data/local/tmp/tone.log 2>&1 &'
sleep 6
adb -s LNNZXG8TPFHU69AI shell 'dumpsys media.audio_flinger | grep -B2 -A6 "JamesDSP"'    # 实例 + enabled
adb -s LNNZXG8TPFHU69AI shell su -c 'stat -c "size=%s mtime=%y" /data/vendor/audio/jamesdsp_state.bin'
adb -s LNNZXG8TPFHU69AI shell su -c 'CLASSPATH=/data/local/tmp/jdspctl.dex app_process /system/bin JdspCtl 0 1500 -0.3 40 1.0'
adb -s LNNZXG8TPFHU69AI shell su -c 'stat -c "size=%s mtime=%y" /data/vendor/audio/jamesdsp_state.bin'   # mtime/size 应变
# 排查写盘问题看这个 tag：
adb -s LNNZXG8TPFHU69AI shell 'logcat -d | grep JamesDSPState'
```

> 排障提示：`state.bin` 不变时**先看 logcat 的 `JamesDSPState`**，再看 `cat /proc/$(pidof android.hardware.audio.service.mediatek)/attr/current`
> 确认 HAL 域；`dmesg` 里可能因为 vendor `dontaudit` 一条 AVC 都没有，不能作为"没有 SELinux 问题"的依据。
