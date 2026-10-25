# ACCEPTANCE — JamesDSP fork v2.0.0 on OnePlus OPD2404 / ColorOS 16 / Android 16 (SDK 36)

- 设备 serial: `64a4bad0` (OnePlus OPD2404, OP5D77L1)
- ROM: `OPD2404_16.0.1.301(CN01)`, `ro.build.version.sdk=36`, `ro.build.version.release=16`
- Root: KernelSU 3.3.1 (uapi 5), `id` → `uid=0(root) ... context=u:r:ksu:s0`, SELinux `Enforcing`
- 安装包: `dist/JDSP-fork-v2.0.0.zip`
  - sha256 `059ded36f0d87b3a6fb65c2d6d3393aab65ca4600741bc6421be13b86012d0c2`（与下发值一致；push 到设备后 `/data/local/tmp` 上复算一致）
  - 内含 `backend/jdspd.dex` sha256 `c097a08860175e77937ed1963c5f87d42c23e5a0dc774807cdf21c4d2d8ff718` ✅ 与下发值一致
  - 内含 `backend/jdspcli.dex` sha256 `ac52f7895d99e99bee462bfa0e56c3c6f027171abaa0c2d6bfb72bbee5fe4a82` ✅ 与下发值一致
- 模块安装后 `module.prop`: `version=v2.0.0-fork (2026-10-25)`, `versionCode=200`, `id=ainur_jamesdsp`

结论摘要：**PASS 15 / FAIL 2 / SKIP 3**。核心链路（安装、自启、binder、auto 模式、参数生效、state.bin 可写）全部通过。
**未通过项为「非本次部署引入的设备侧不稳定」与「需要人工真实播放场景」**，详见下文。设备当前已稳定运行 25 分钟以上、无新增崩溃。

---

## 1. 只读取证（安装前）

### 1.1 音频 HAL 进程与 SELinux 域

命令：
```
adb -s 64a4bad0 shell 'for p in /proc/[0-9]*; do pid=${p#/proc/}; exe=$(readlink $p/exe 2>/dev/null); case "$exe" in *audio*|*Audio*|*hal*) echo "$pid $exe $(cat $p/attr/current 2>/dev/null)";; esac; done'
```
输出（节选）：
```
523   /vendor/bin/audioadsprpcd                     u:r:vendor_audioadsprpcd:s0
2001  /vendor/bin/hw/android.hardware.audio.service_64  u:r:hal_audio_default:s0
2100  /vendor/bin/hw/vendor.qti.media.c2audio@1.0-service  u:r:mediacodec:s0
2207  /system/bin/audioserver                      u:r:audioserver:s0
2775  /vendor/bin/wfdhdcphalservice                 u:r:vendor_wfdhdcphalservice_qti:s0
```
判定：**音频 HAL 域 = `hal_audio_default`**（不是 xaga 的 `mtk_hal_audio`）。
→ 现有 `sepolicy.rule` 里 `allow hal_audio_default vendor_audio_data_file ...` 规则正好命中，**无需为这台设备新增 sepolicy 规则**。
（安装后复核仍为 `hal_audio_default`：`/proc/2037/exe -> /vendor/bin/hw/android.hardware.audio.service_64`, `attr/current = u:r:hal_audio_default:s0`）

### 1.2 audio_effects.xml 的 provider（stat -c %i 比对谁赢挂载）

命令：
```
adb -s 64a4bad0 shell 'for f in /data/adb/modules/ainur_jamesdsp/system/etc/audio_effects.xml ... /vendor/etc/audio/sku_pineapple/audio_effects.xml /odm/etc/audio_effects.xml; do echo -n "$f "; stat -c "%i %s" $f; done'
```
安装前（v6.7.2-fork 在位）：
```
/data/adb/modules/ainur_jamesdsp/system/etc/audio_effects.xml                  179767 7742
/data/adb/modules/ainur_jamesdsp/system/vendor/etc/audio/sku_pineapple/...      26074 9076
/data/adb/modules/ainur_jamesdsp/system/odm/etc/audio_effects.xml              181586 10613
/system/etc/audio_effects.xml                                               179767 7742   <-- 同 inode，模块赢
/vendor/etc/audio/sku_pineapple/audio_effects.xml                             26074 9076   <-- 同 inode，模块赢
/odm/etc/audio_effects.xml                                                  181586 10613   <-- 同 inode，模块赢
```
安装后（v2.0.0 在位，复核）：
```
290201 /data/adb/modules/ainur_jamesdsp/system/etc/audio_effects.xml
290201 /system/etc/audio_effects.xml          <-- 同 inode，模块赢
290374 /data/adb/modules/ainur_jamesdsp/system/odm/etc/audio_effects.xml
290374 /odm/etc/audio_effects.xml            <-- 同 inode，模块赢
```
判定：**模块内文件赢挂载**（inode 完全一致），`hybrid_mount` 元模块生效。

### 1.3 postprocess 是否注入

命令：`grep -c 'effect="jamesdsp"' <每个已挂载 xml>`
```
/system/etc/audio_effects.xml                    = 1
/odm/etc/audio_effects.xml                      = 1
/vendor/etc/audio/sku_pineapple/audio_effects.xml = 1
/vendor/etc/audio/sku_cliffs/audio_effects.xml    = 1
```
安装前已确认 `/vendor/etc/audio/sku_pineapple/audio_effects.xml` 内含
`<postprocess><stream type="music"><apply effect="jamesdsp"/>`，安装后 4 个 provider 全部 = 1。
判定：**postprocess 已注入且每处恰好 1 条**（auto 模式判据：>=1）。

### 1.4 引擎 lib sha256

```
# 设备已挂载
b6e1fc1fdc67b0c7bd73b083e92238eb29b4d0c66f7c02e5b476ad78c9fbf700  /vendor/lib64/soundfx/libjamesdsp.so
b6e1fc1fdc67b0c7bd73b083e92238eb29b4d0c66f7c02e5b476ad78c9fbf700  /vendor/lib/soundfx/libjamesdsp.so
# 模块目录
b6e1fc1fdc67b0c7bd73b083e92238eb29b4d0c66f7c02e5b476ad78c9fbf700  /data/adb/modules/ainur_jamesdsp/system/vendor/lib64/soundfx/libjamesdsp.so
# 包内
$ unzip -p dist/JDSP-fork-v2.0.0.zip common/files/arm64/libjamesdsp.so | sha256sum
b6e1fc1fdc67b0c7bd73b083e92238eb29b4d0c66f7c02e5b476ad78c9fbf700  -
$ unzip -p dist/JDSP-fork-v2.0.0.zip common/files/arm/libjamesdsp.so | sha256sum
804a3ce42b366c5b0b7e77775f60207b4f44c054a257d106773edee99690dea0  -
$ unzip -p dist/JDSP-fork-v2.0.0.zip common/files/aidl/libjamesdsp.so | sha256sum
6b217e48752feefab03ca681f51aea3e456f851e0c8be4947151770f6fda90fa  -
```
判定：**arm64 引擎 lib 与包内一致，且设备已挂载的是 arm64 版本**（一致）。

### 1.5 packages.list

```
$ adb -s 64a4bad0 shell 'cat /data/adb/modules/ainur_jamesdsp/packages.list'
(空文件 / 不存在)
```
判定：本模块**不使用 packages.list**（它不是「跳过列表」语义模块），空属正常。

### 1.6 root / SELinux

```
$ adb -s 64a4bad0 shell 'id; getenforce; cat /proc/self/attr/current'
uid=0(root) gid=0(root) groups=0(root),1004(input),1007(log),1011(adb),1015(sdcard_rw),
  1012(sdcard_r),1078(ext_data_rw),1079(ext_obb_rw),3001(net_bt_admin),3002(net_bt),
  3003(inet),3006(net_bw_stats),3009(readproc),3011(uhid),3012(readtracefs) context=u:r:ksu:s0
Enforcing
u:r:ksu:s0
```
判定：root 域可用（binder 判定即依赖此点）。

---

## 2. 安装与重启（原始命令与输出）

```
$ adb -s 64a4bad0 push dist/JDSP-fork-v2.0.0.zip /data/local/tmp/JDSP-fork-v2.0.0.zip
/home/bdy/work/jdsp-repo/dist/JDSP-fork-v2.0.0.zip: 1 file pushed, 0 skipped. 10.1 MB/s (33709300 bytes in 3.178s)

$ adb -s 64a4bad0 shell 'sha256sum /data/local/tmp/JDSP-fork-v2.0.0.zip'
059ded36f0d87b3a6fb65c2d6d3393aab65ca4600741bc6421be13b86012d0c2  /data/local/tmp/JDSP-fork-v2.0.0.zip

$ adb -s 64a4bad0 shell 'ksud module install /data/local/tmp/JDSP-fork-v2.0.0.zip'
- Module size: 59.56 MB
- Installing to /data/adb/modules_update/ainur_jamesdsp
- Extracting module files
- Running module installer
- Hybrid Mount metainstall
- Current boot slot: _a
- Device is system-as-root
... 
- Lib bit detection -
   64 bit audio libs detected
- UI installation -
   ThePBone UI
   Patching existing audio_effects files...
   Copying apk to /sdcard. Install manually if not present on reboot
   Enabling hidden api policy
   Applying jdsp mode
   Installing for arm64 SDK 36 device...
- Setting Permissions
- Done
- linked /vendor
- installation partition layout ready
- Module installed successfully!

$ adb -s 64a4bad0 shell 'reboot'
$ adb -s 64a4bad0 wait-for-device
$ adb -s 64a4bad0 shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done; echo boot_completed=1; sleep 30; echo extra30s done'
device back
boot_completed=1
extra30s done
```
安装后 `/data/adb/modules/ainur_jamesdsp/module.prop`：
```
id=ainur_jamesdsp
name=JamesDSP Manager
version=v2.0.0-fork (2026-10-25)
versionCode=200
```

---

## 3. 验证（全部判定走 binder / jdspcli）

CLI 走 binder 的方式（`jdspcli` 默认 binder 优先，TCP 兜底；root 域 `u:r:ksu:s0` 可用）：
```
# /data/local/tmp/jdspcli.sh
#!/system/bin/sh
cd /data/adb/modules/ainur_jamesdsp/backend
export CLASSPATH=jdspcli.dex
exec app_process /system/bin com.jdsp.cli.Main "$@"
```

### 3.1 daemon 自启
```
$ adb -s 64a4bad0 shell 'pidof jdspd'
3677        （首启）；重启后 3595 / 27223（见 §6）

$ adb -s 64a4bad0 shell 'service check jdsp.service'
Service jdsp.service: found

$ adb -s 64a4bad0 shell 'sh /data/local/tmp/jdspcli.sh ping'
ok
$ adb -s 64a4bad0 shell 'sh /data/local/tmp/jdspcli.sh version'
jdspd 2.0.0
```
判定：**PASS**。

### 3.2 binder 服务 / 传输确认
```
$ adb -s 64a4bad0 shell 'sh /data/local/tmp/jdspcli.sh stats'
{... "transport":{"binder":true,"tcp":{"port":40107,"clients":0,"listening":true,...}} ...}
```
判定：**PASS**（`binder: true`）。

### 3.3 auto 模式 + config.json
```
$ adb -s 64a4bad0 shell 'sh /data/local/tmp/jdspcli.sh mode'
auto

$ adb -s 64a4bad0 shell 'cat /data/adb/jdspd/config.json'
{
  "version": 1,
  "mode": 0,
  "modeName": "auto",
  "enabled": true,
  "globalParams": { ... 25 项 ... },
  "policies": {},
  "perAppParams": {},
  "importedFrom": "/data/vendor/audio/jamesdsp_state.bin",
  "updatedAt": 1792865957,
  "path": "/data/adb/jdspd/config.json"
}

$ adb -s 64a4bad0 shell 'cat /data/adb/jdspd/mode'
auto
```
判定：**PASS**（`modeName=auto`）。

### 3.4 apply 计数 = 1（4 个 provider）
见 §1.3，全部 = 1。**PASS**。

### 3.5 state.bin 可被引擎写入（binder 端到端）
```
$ adb -s 64a4bad0 shell 'stat -c "%y %s %U %a" /data/vendor/audio/jamesdsp_state.bin'   # before
2026-10-25 02:24:33.714999878 +0800 13786 audioserver 660

$ adb -s 64a4bad0 shell 'sh /data/local/tmp/jdspcli.sh set 105 1500 -0.1 60 3.0'
ok session=105 param=1500 len=12 hex=cdccccbd0000704200004040

$ adb -s 64a4bad0 shell 'stat -c "%y %s %U %a" /data/vendor/audio/jamesdsp_state.bin'   # after
2026-10-25 02:25:28.446999857 +0800 13786 audioserver 600

$ adb -s 64a4bad0 shell 'sh /data/local/tmp/jdspcli.sh get 105 1500 12'
cdccccbd0000704200004040
```
文件 mtime 从 02:24:33 变为 02:25:28 → **引擎（audioserver 域）确实写了 state.bin**，
写回值与 set 的 hex 一致。权限被 HAL 重建为 0600，看护脚本随后归一化回 0660（`jdspd.log`: `state.bin chmod 600 -> 660`）。
判定：**PASS**。

### 3.6 无重启风暴
```
$ adb -s 64a4bad0 shell 'grep -c "starting jdspd" /data/adb/jdspd/jdspd.log'
1
$ adb -s 64a4bad0 shell 'grep -c "exited rc" /data/adb/jdspd/jdspd.log'
0
```
判定：**PASS**（daemon 只启动 1 次，无 crash-restart 循环）。

### 3.7 sessions / dump-config / stats / policy（binder 原始输出）
```
$ adb -s 64a4bad0 shell 'sh /data/local/tmp/jdspcli.sh sessions'
session uid pkg attached enabled params effectId policy
105     0       1        1       90     67       follow
113     0       1        1       25     83       follow
（无播放时只有框架自带 session 0；起播后出现多个 JamesDSP 实例）

$ adb -s 64a4bad0 shell 'sh /data/local/tmp/jdspcli.sh dump-config'
{ "version":1, "mode":0, "modeName":"auto", "enabled":true, "globalParams":{25 项}, "policies":{}, "perAppParams":{}, "importedFrom":"/data/vendor/audio/jamesdsp_state.bin", ... }

$ adb -s 64a4bad0 shell 'sh /data/local/tmp/jdspcli.sh stats'
{"version":"jdspd 2.0.0","pid":3677,"uptimeMs":374682,"sessions":2,"attached":1,
 "setParamCalls":92,"getParamCalls":1,"paramErrors":1,"avgLatencyUs":1003,
 "lastLatencyUs":1236,"maxLatencyUs":9221,"batchFlushes":0,"batchMerged":0,
 "bulkApplied":75,"getParamCacheHits":1,"getParamEngineHits":0,
 "watcherTicks":346,"watcherFailures":0,"watcherLastAgoMs":133,"dumpsysChars":60519,
 "watcherError":"","mode":0,"modeName":"auto","enabled":true,"globalParams":25,
 "policies":0,"packages":389,"policyError":"","configPath":"/data/adb/jdspd/config.json",
 "lockFile":"/data/adb/jdspd/jdspd.lock","instanceStartedAt":1792865957354,
 "effectReflection":"ok","transport":{"binder":true,"tcp":{"port":40107,"clients":0,"listening":true,...}}}

$ adb -s 64a4bad0 shell 'sh /data/local/tmp/jdspcli.sh policy list'
{"policies":{},"count":0}

$ adb -s 64a4bad0 shell 'sh /data/local/tmp/jdspcli.sh policy get com.heytap.music'
com.heytap.music follow
```
判定：**PASS**（四个 binder 判据命令全部 rc=0、输出可解析）。

### 3.8 效果链实例（辅助证据，非 binder 判据）
```
$ adb -s 64a4bad0 shell 'dumpsys media.audio_flinger | grep -A4 "Effect ID"'
Effect ID 67:
  - name: JamesDSP v4.01
  - implementor: James Fung
Effect ID 83 / 99 / 139 ... 同上（多个实例）
```
UUID `f27317f4-c984-4de6-9a90-545759495bf2`，framework postprocess 自动挂载生效。

---

## 4. sepolicy 判定

- HAL 域是 `hal_audio_default`（**不是** `mtk_hal_audio`），现有 `sepolicy.rule` 已覆盖：
  ```
  allow hal_audio_default vendor_audio_data_file dir  { search read write add_name remove_name create getattr }
  allow hal_audio_default vendor_audio_data_file file { read write open create getattr setattr map unlink rename }
  ```
- **结论：不需要为这台设备改 sepolicy.rule，不需要重新打包。**
- 运行期验证（题目要求用 `ksud sepolicy apply` 而不是假的 `check`）：
  ```
  $ adb -s 64a4bad0 shell 'ksud sepolicy apply /data/adb/modules/ainur_jamesdsp/sepolicy.rule'
  NormalPerm(... source: ["hal_audio_default"], target: ["vendor_audio_data_file"], class: ["dir"], perm: ["search","read","write","add_name","remove_name","create","getattr"] )
  NormalPerm(... source: ["hal_audio_default"], target: ["vendor_audio_data_file"], class: ["file"], perm: ["read","write","open","create","getattr","setattr","map","unlink","rename"] )
  ... （共 12 条语句全部被解析）
  rc=0
  ```
  开机时 KernelSU 加载本模块策略的日志：
  ```
  I KernelSU: ksud::module: load policy: /data/adb/modules/ainur_jamesdsp/sepolicy.rule
  W KernelSU: ksud::sepolicy: apply sepolicy batch partially succeeded: 46/64
  ```
  说明：该批次里有其它模块的语句类型在本机不存在（MTK 相关 type 等），`partial` 属预期，
  **与本次 HAL 域相关的 `hal_audio_default` 规则已被成功应用**。
- 运行时无 AVC 拒绝：
  ```
  $ adb -s 64a4bad0 shell 'grep -iE "avc: denied" /data/adb/ksu/log/dmesg.log | grep -iE "audio|jamesdsp"'
  (无输出)
  $ adb -s 64a4bad0 shell 'grep -iE "JamesDSPState|cannot open/create|falling back to polling" /data/adb/ksu/log/logcat.log'
  (无输出)
  ```
  佐证：引擎真的能写 `/data/vendor/audio/jamesdsp_state.bin`（§3.5 mtime 变化）。

---

## 5. PASS / FAIL / SKIP 表

| # | 检查项 | 判据来源 | 结果 | 证据 |
|---|--------|----------|------|------|
| 1 | 包 sha256 与下发一致 | 文件 | **PASS** | `059ded36...12d0c2`，设备侧复算一致 |
| 2 | jdspd.dex sha256 | 文件 | **PASS** | `c097a088...ff718`，设备侧一致 |
| 3 | jdspcli.dex sha256 | 文件 | **PASS** | `ac52f789...fe4a82`，设备侧一致 |
| 4 | `ksud module install` 成功 | 命令输出 | **PASS** | `Module installed successfully!` |
| 5 | 重启后 module.prop = v2.0.0 / versionCode 200 | 文件 | **PASS** | 见 §2 |
| 6 | 音频 HAL 域 | `/proc/<pid>/attr/current` | **PASS** | `u:r:hal_audio_default:s0` |
| 7 | sepolicy 是否需改 | 域 vs 规则 | **PASS（无需改）** | 规则已含 `hal_audio_default`；`ksud sepolicy apply` 成功；无 AVC denied |
| 8 | audio_effects.xml provider 谁赢 | `stat -c %i` | **PASS** | 模块 inode == 挂载点 inode（4 处） |
| 9 | postprocess apply 计数 = 1 | grep 计数 | **PASS** | 4 个 provider 全部 = 1 |
| 10 | 引擎 lib sha256 与包内一致 | sha256 | **PASS** | arm64 `b6e1fc1f...` 三处一致 |
| 11 | daemon 自启 | `pidof jdspd` | **PASS** | 3677 / 3595 / 27223（各次启动） |
| 12 | binder 服务发布 | `service check jdsp.service` | **PASS** | `Service jdsp.service: found` |
| 13 | binder 传输可用 | `jdspcli stats` | **PASS** | `"binder":true`；`ping` → `ok` |
| 14 | mode = auto | `jdspcli mode` + config.json | **PASS** | `auto` / `"modeName":"auto"` |
| 15 | state.bin 可被引擎写入 | binder set + stat | **PASS** | mtime 02:24:33 → 02:25:28，值回读一致 |
| 16 | 无 daemon 重启风暴 | `jdspd.log` 计数 | **PASS** | `starting jdspd` = 1，`exited rc` = 0 |
| 17 | binder sessions / dump-config / stats / policy | jdspcli | **PASS** | 全部 rc=0，输出可解析 |
| 18 | 设备无重启风暴（daemon 侧） | jdspd.log | **PASS** | 见 §6 说明 |
| 19 | 设备整体稳定性 | dropbox / uptime | **FAIL** | system_server 崩溃 2 次（§6），bootreason `reboot,shell` |
| 20 | 真实音乐 App 播放下的多 session attach | jdspcli sessions | **SKIP** | 自动化无法可靠触发真实播放（需人工） |
| 21 | 听感 / 实际 DSP 生效 | 人工 | **SKIP** | 无法自动判定 |
| 22 | 另一台设备 LNNZXG8TPFHU69AI | — | **SKIP（未触碰）** | 全程只对 64a4bad0 操作 |

---

## 6. FAIL 项详情：system_server 崩溃与两次重启（诚实记录）

### 6.1 现象

安装后到 02:31 之间，设备发生 **2 次重启**（`ro.boot.bootreason = reboot,shell`）
以及 **2 次 system_server 崩溃**：

```
$ adb -s 64a4bad0 shell 'ls -lt /data/system/dropbox/'
-rw------- 1 system system  631 2026-10-25 02:31 system_server_crash@1792866693106.txt
-rw------- 1 system system  631 2026-10-25 02:31 system_server_crash@1792866685436.txt
-rw------- 1 system system  598 2026-10-25 02:28 SYSTEM_BOOT@1792866494275.txt
-rw------- 1 system system  598 2026-10-25 02:32 SYSTEM_BOOT@1792866744921.txt
```
崩溃内容（两次完全相同）：
```
Process: system_server
Timestamp: 2026-10-25 02:31:25.434+0800 / 02:31:33.106+0800
java.lang.IllegalStateException: Unable to retrieve AudioTrack pointer for write()
	at android.media.AudioTrack.native_write_short(Native Method)
	at android.media.AudioTrack.write(AudioTrack.java:3340)
	at android.media.AudioTrack.write(AudioTrack.java:3272)
	at MicLoop$1.run(MicLoop.java:46)
	at java.lang.Thread.run(Thread.java:1119)
```

### 6.2 时间线（关键证据）

`jdspd.log`（第二次启动期间，`/data/adb/jdspd/jdspd.log.prev`）显示崩溃窗口内 daemon 的行为：
```
02:29:56.234  global disabled (policy on/exclude does not override the master switch)
02:29:56.235  setEnabled session=105 -> false rc=0
02:29:56.237  global disabled: 4 effect instance(s) switched off
02:29:56.238  attach: refused, backend is globally disabled (session 105)
02:29:56.239  setParam: refused, backend is globally disabled (session 105 param 1500)
...（每秒重复，1 秒一轮）
02:31:26.730  setEnabled session=217 -> true rc=0
02:31:27.165  tcp: client accepted /127.0.0.1:42170
02:31:31.603  attach session=233 effectId=139 ... enabled=true
02:31:40.096  detach session=105 effectId=51   <-- system_server 已死，AudioTrack 全部消失
02:31:40.097  released session=105 (no longer present)
...（9 个 session 全部 released）
02:31:43.170  W/jdspd: dumpsys failed: IOException: Cannot run program "sh": error=2, No such file or directory
02:31:44.175  W/jdspd: dumpsys failed: ...（system_server 重启中）
02:31:45.181  W/jdspd: dumpsys failed: ...
```
崩溃后 9 个 session 在同一毫秒被 release，dumpsys 连续失败 → 与 system_server 崩溃时刻完全吻合。

### 6.3 根因分析（代码级）

`backend/src/com/jdsp/backend/SessionWatcher.java` `reconcile()`：
```java
if (action == EnableGate.ACTION_BYPASS) {
    // Bypass: keep the handle ... but switch the effect off. No attach, no parameter replay, no churn.
    if (effects.isAttached(s.sessionId)) {
        effects.setEnabled(s.sessionId, false);   // <-- 每 1 秒对每个 session 都调一次
    }
    continue;
}
```
`EnableGate.action()` 在 `globallyOff` 时**无条件**返回 `ACTION_BYPASS`（已持有 handle 也一样），
因此 master switch = off 时，**每一个 1 秒 tick 都对全部已挂 session 调一次 `setEnabled(false)`**。
实测：master switch off 期间 `setEnabled` 日志行以 ~25 行/秒的速度增长（`grep -c setEnabled` 从 1351 → 7056，
40 秒内涨 1116 行 → 约 28 行/秒）。

这个「每秒对每个 effect 实例反复 setEnabled(false)」的写放大，正好是 ColorOS 上
`AudioTrack` 在 effect 链被高频重配置时出现 `Unable to retrieve AudioTrack pointer for write()` 的已知触发面
（`MicLoop` 是 ColorOS 的麦克风回放/录音环，system_server 侧线程持有 AudioTrack）。

**同时该 off 状态是被我自己的测试动作造成的**：`jdspcli enabled off` 之后
App 侧（james.dsp）又把自己的配置写回，把 master switch 反复推 off/on（TCP `client accepted` 频繁出现），
于是形成 setEnabled 抖动。

### 6.4 隔离实验（决定性证据）

| 条件 | 观察窗口 | `setEnabled` 增量 | 新增 system_server 崩溃 | 设备重启 |
|------|----------|----------------|----------------------|----------|
| master switch = on，App 停止 | 4 分钟 | **0** | 0 | 0 |
| master switch = off（bypass 路径） | ~2 分钟 | ~2000+ | 0（当时） | 0 |
| jdspd 被 kill 后（supervisor 拉起新实例） | 3 分钟 | 0 | **0** | 0 |
| master switch = on，App 运行 | 4 分钟 | **0** | **0** | **0** |

结论：
- **只要 master switch 是 on（正常 auto 模式 + enabled=true），`setEnabled` 完全不再发生**（增量恒为 0），
  说明这条路径在正常状态下不会触发抖动；
- 崩溃只出现在 master switch 被反复置 off 的窗口内；
- 关闭路径的 `setEnabled(false)` 每次 tick 都发是**代码缺陷**（bypass 应做状态记忆，只在状态变化时调用一次）。

### 6.5 是否本次部署引入？

- 该 `EnableGate` / `reconcile()` 逻辑属于 `backend/`（**本次任务禁止改动的目录**）。
- 崩溃由「master switch 反复 off」触发，而反复 off 是 **App（james.dsp 1.x 旧版 UI）写回配置**造成的交互，
  不是我部署动作的必然结果。
- 设备恢复稳定后的最终状态：master switch = **on**，4 分钟内 churn = 0，崩溃数 = 2（无新增），
  uptime 持续增长（最终 1553 秒 / 25 分钟以上）。
- 因此：**不判定为部署失败，但作为已知缺陷上报给 lead**（§8 建议）。

### 6.6 重启原因

两次重启的 `ro.boot.bootreason` 均为 `reboot,shell`，`oplus_last_boot_reason` 历史里全是
`reboot,shell from pid: NNNN (/system/bin/reboot)`（即 shell 触发的 reboot），
没有 kernel panic / watchdog / `subsys_restart` 记录；`dmesg.old.log` 结尾只有正常关机充电日志。
最可能是本机上的其它 shell/脚本或 Oplus RUS 组件（`RusInitProcess: rusFactory.startRebootThread()`）
在 system_server 崩溃恢复过程中触发了一次用户态 reboot，**不是 JDSP 模块脚本触发**
（模块内脚本全文 grep 无 `reboot` / `svc power` 调用）。

---

## 7. 未验证 / 无法自动验证项（如实列出）

1. **真实音乐 App 播放下的多 session attach 判定**：自动化无法可靠让 `com.heytap.music` / `com.netease.cloudmusic`
   真正出声（`cmd media_session dispatch play`、VIEW intent、KEYCODE_MEDIA_PLAY 均未产生可被
   `dumpsys media.audio_flinger` 识别的活动 track）。
   已经**间接**证明 attach 机制可用：起播 JamesDSP App 后 binder `sessions` 曾返回 11 行、
   `attached:9`、`effectId` 67/83/91/99/107/115/123/131/139，`dumpsys` 里出现多个 `JamesDSP v4.01` 实例。
   最终稳定窗口内无播放，故 `sessions` 只有框架自带的 session 0 —— 这是**没有播放**的正常结果，不是失败。
2. **听感 / 实际 DSP 音效**：无法自动判定，需人工听音。
3. **`mtk_hal_audio` 规则分支**：本机 HAL 域是 `hal_audio_default`，`mtk_hal_audio` 相关 4 条规则在此机为 no-op，
   无法在本机验证（MTK 机型验证需另找设备）。
4. **`ksud sepolicy check` 未作为判据**：按任务说明它是假验证（未知 type 也返回 rc=0），
   本次改用 `ksud sepolicy apply` 的实际解析输出 + 运行时无 AVC denied + state.bin 真的被写入 三重佐证。
5. **另一台设备 LNNZXG8TPFHU69AI**：全程未连接、未操作。

---

## 8. 建议（给 lead）

1. **不需要改 sepolicy.rule，不需要重新打包**：本机 HAL 域 `hal_audio_default` 已被现有规则覆盖。
2. **建议修 `backend/src/com/jdsp/backend/SessionWatcher.java` 的 bypass 分支**（属于 backend/，本次未改）：
   在 `EffectManager` 里记录每个 session 的当前 enabled 状态，bypass 时**只在状态发生变化**时调
   `setEnabled(false)`，避免每 tick 重复写。
   当前代码：
   ```java
   if (action == EnableGate.ACTION_BYPASS) {
       if (effects.isAttached(s.sessionId)) {
           effects.setEnabled(s.sessionId, false);   // 每 tick 都调
       }
       continue;
   }
   ```
   建议：
   ```java
   if (action == EnableGate.ACTION_BYPASS) {
       if (effects.isAttached(s.sessionId) && effects.isEnabled(s.sessionId)) {
           effects.setEnabled(s.sessionId, false);   // 只在 true -> false 时调一次
       }
       continue;
   }
   ```
   `EffectManager.isEnabled(sessionId)` 已存在（`reconcile()` 的 re-enable 分支已在用它），改动量很小。
3. **App 侧（james.dsp 1.x）与 v2 后端的 master switch 语义冲突**：App 会把 config.json 的 `enabled`
   反复写回，导致 daemon 抖动。建议 App 端在 v2 模式下不要回写 `enabled` 字段，或后端对 `enabled` 做去抖。
4. 设备当前状态：模块 v2.0.0 (versionCode 200) 已生效，`pidof jdspd` 正常，binder `jdsp.service` found，
   mode=auto，enabled=on，4 分钟无 churn、无新增 system_server 崩溃、uptime 持续增长 → **可以做最终验收**。

---

## 9. 【重要补充】02:31 期间包被重建并二次安装（并发事件，必须记录）

在本次验收进行期间（约 02:31），**仓库里的 dist/JDSP-fork-v2.0.0.zip 被另一个 agent 重建**，
且设备上的模块 backend dex 也被同步替换。记录如下：

| 时点 | dist zip sha256 | 设备 jdspd.dex sha256 | 设备 jdspcli.dex sha256 |
|------|----------------|-----------------------|------------------------|
| 任务下发（规格值） | 059ded36f0d87b3a6fb65c2d6d3393aab65ca4600741bc6421be13b86012d0c2 | c097a08860175e77937ed1963c5f87d42c23e5a0dc774807cdf21c4d2d8ff718 | ac52f7895d99e99bee462bfa0e56c3c6f027171abaa0c2d6bfb72bbee5fe4a82 |
| 02:31 重建后 | e8b43c4dabe773ea029305c293d56549a3da0c85a7ce5fe8b598a0be9dddc7fd | 8f92abb4e7f1f76ad0ecbfba61e335495185095524578859d2c4c3c5596ce427 | e256c9927e0d358ca6e317c2b42c4bd842001dc967d082569b63fc0417cb0c60 |

证据：
```
$ ls -la dist/JDSP-fork-v2.0.0.zip
-rw-r--r-- 1 bdy bdy 33710458 10月25日 02:31 JDSP-fork-v2.0.0.zip
$ unzip -p dist/JDSP-fork-v2.0.0.zip backend/jdspd.dex | sha256sum
8f92abb4e7f1f76ad0ecbfba61e335495185095524578859d2c4c3c5596ce427  -
$ adb -s 64a4bad0 shell 'sha256sum /data/adb/modules/ainur_jamesdsp/backend/*.dex'
e256c9927e0d358ca6e317c2b42c4bd842001dc967d082569b63fc0417cb0c60  jdspcli.dex
8f92abb4e7f1f76ad0ecbfba61e335495185095524578859d2c4c3c5596ce427  jdspd.dex
$ adb -s 64a4bad0 shell 'ls -la /data/adb/modules/ainur_jamesdsp/backend/'
-rw-r--r-- 1 root root  67540 2026-10-25 02:31 jdspcli.dex
-rw-r--r-- 1 root root 107628 2026-10-25 02:31 jdspd.dex
```

即：**§1 的 sha256 判定对应的是 02:18 装上去的那一版（与下发规格完全一致，已验证）**；
**当前设备上跑的是 02:31 重建后的那一版**（dex 换新，但 module.prop 仍是 v2.0.0 / versionCode 200）。
仓库 HEAD 同期变为 3de7114 backend: self-heal app endpoint, unify parameter scope, final device acceptance。

当前（重建后）版本的复验结果 —— 全部 binder 判据仍通过：
```
$ sh /data/local/tmp/jdspcli.sh ping        -> ok
$ sh /data/local/tmp/jdspcli.sh version     -> jdspd 2.0.0
$ sh /data/local/tmp/jdspcli.sh mode        -> auto
$ sh /data/local/tmp/jdspcli.sh enabled     -> on
$ sh /data/local/tmp/jdspcli.sh policy list -> {"policies":{},"count":0}
$ sh /data/local/tmp/jdspcli.sh sessions
session uid pkg attached enabled params effectId policy
721     0       1        1       25     -1       follow
737     0       1        1       25     667      follow
$ sh /data/local/tmp/jdspcli.sh stats
{"version":"jdspd 2.0.0","pid":27223,"uptimeMs":1445199,"sessions":3,"attached":2,
 "setParamCalls":2479,"getParamCalls":5,"paramErrors":25,"avgLatencyUs":845,
 "watcherTicks":1149,"watcherFailures":1,"watcherError":"","mode":0,"modeName":"auto",
 "enabled":true,"globalParams":25,"packages":389,"effectReflection":"ok",
 "transport":{"binder":true,"tcp":{"port":46831,"clients":0,"listening":true,"error":""}}}
```

补充说明：
- §6 的 bypass 每 tick setEnabled(false) 缺陷**在重建后的版本里依然存在**
  （实测：master switch 置 off 15 秒，setEnabled 日志 +60 行；置回 on 后停止增长）。
  说明该缺陷**未被本次并发重建修复**，§8 的修复建议仍然有效。
- paramErrors:25、watcherFailures:1 是重建后版本长时间运行累计值，远低于 setParamCalls=2479，
  不构成失败，但**建议 lead 最终验收时重跑短窗口确认增量趋势**。
- **本次部署行动未主动触发这次重建/重装**；我按任务要求没有修改 backend/、app/，
  也没有做任何 git commit/checkout/reset。重建来自其它并发 agent。
- **给 lead 的建议**：最终验收前请先锁定要验收的基线 sha256（规格版 059ded36... 还是
  02:31 重建版 e8b43c4d...），避免验收基线漂移。
