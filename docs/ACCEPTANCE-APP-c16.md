# ACCEPTANCE-APP-c16 — App 部署验证 (phase2 APK)

- 日期：2026-10-25 02:22 – 03:15 CST
- 设备：serial=64a4bad0，OnePlus OPD2404 / ColorOS16 / Android 16 (SDK 36)，KernelSU root
- 后端：jdspd 2.0.0（模块 /data/adb/modules/ainur_jamesdsp）
- App：james.dsp / me.timschneeberger.rootlessjamesdsp.activity.MainActivity
- 执行者：App 部署验证代理 (deepseek)

## 结论速览

| # | 项目 | 结果 |
|---|------|------|
| 1 | 只读取证（包已装 / 版本 / 证书 / files 目录） | PASS |
| 2 | 备份原 APK + 安装 phase2 APK | PASS |
| 3 | 启动 App → files 目录存在 → jdspd 自愈写端点文件（0400 / owner=App uid） | PASS |
| 4 | 写路径：App UI 参数下发到 jdspd（dump-config 出现 + setParamCalls 增加） | PASS |
| 5 | TCP vs binder 一致性 | PASS |
| 6 | App 已装好、端点文件正常 | PASS |
| 7 | phase2 APK 崩溃 → 回滚 | **不适用**（phase2 未崩溃；但见 §6 附带事件） |
| 8 | 前端可用性（无前端可用时由验收工具复测） | SKIP（按任务说明由验收工具代理复测） |

无回滚动作执行：phase2 APK 安装后未崩溃，保留安装。

---

## 1. 只读取证（PASS）

```
$ adb -s 64a4bad0 shell pm list packages | grep james.dsp
package:james.dsp

$ adb -s 64a4bad0 shell dumpsys package james.dsp | grep -E 'versionCode|versionName|firstInstallTime|lastUpdateTime|codePath'
    flags=0x0
    codePath=/data/app/~~lKmG5p7edIiVwRKNEJW2fA==/james.dsp-hZJivIWol3L8s_-_GgIt4A==
    versionCode=61 minSdk=26 targetSdk=35
    versionName=1.6.15-zh3
    flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
    lastUpdateTime=2026-10-24 15:53:32
      firstInstallTime=2026-10-24 15:53:32

$ adb -s 64a4bad0 shell pm path james.dsp
package:/data/app/~~lKmG5p7edIiVwRKNEJW2fA==/james.dsp-hZJivIWol3L8s_-_GgIt4A==/base.apk

$ adb -s 64a4bad0 shell ls -ld /data/data/james.dsp /data/data/james.dsp/files
drwx------ 8 u0_a383 u0_a383 3440 2026-10-24 15:53 /data/data/james.dsp
drwxrwx--x 6 u0_a383 u0_a383 3440 2026-10-25 02:19 /data/data/james.dsp/files
```

说明：本次开始时 App **已经安装且已启动过**（files 目录已存在），这与"目录不存在"的旧前提不同；
所以本轮验证的重点从"目录缺失"转为"端点文件写入 + 写路径语义"。

## 2. 备份 + 安装 phase2 APK（PASS）

### 2.1 备份（安装前）

```
$ adb -s 64a4bad0 shell 'mkdir -p /data/local/tmp/jdspbak && cp /data/app/~~lKmG5p7edIiVwRKNEJW2fA==/james.dsp-hZJivIWol3L8s_-_GgIt4A==/base.apk /data/local/tmp/jdspbak/base.apk && ls -l /data/local/tmp/jdspbak && sha256sum /data/local/tmp/jdspbak/base.apk'
-rw-r--r-- 1 root root 40960929 2026-10-25 02:23 base.apk
af1db8e13e7f200758eb6fc84d2f61f2a009e05914e2db3c0a57594363b33854  /data/local/tmp/jdspbak/base.apk
```

原始 pm path（回滚用）：
`/data/app/~~lKmG5p7edIiVwRKNEJW2fA==/james.dsp-hZJivIWol3L8s_-_GgIt4A==/base.apk`

### 2.2 phase2 APK 指纹

```
$ sha256sum app/p5/out/james.dsp-p5-phase2.apk
00802267edeb761c51badce8b54e56431991fb1ff2a515f15a04d1229da12423  app/p5/out/james.dsp-p5-phase2.apk

$ ls -l app/p5/out/james.dsp-p5-phase2.apk
-rw-r--r-- 1 bdy bdy 40977371 10月25日 02:18 app/p5/out/james.dsp-p5-phase2.apk

$ apksigner verify --print-certs app/p5/out/james.dsp-p5-phase2.apk
V3.0 Signer: certificate DN: CN=JamesDSP Fork, O=Local, C=CN
V3.0 Signer: certificate SHA-256 digest: ac4af82bc33f5fea60c9706ba465e49ba80c1361c8a973826d9cbe217e301b4d
V3.0 Signer: certificate SHA-1 digest: 584a79afca41eb609283d1af6224db62931e3810
V3.0 Signer: certificate MD5 digest: 6d095fa3f22851be6c70039675090f11

$ aapt2 dump badging app/p5/out/james.dsp-p5-phase2.apk | head -3
package: name='james.dsp' versionCode='61' versionName='1.6.15-zh3' platformBuildVersionName='15' ...
minSdkVersion:'26'
targetSdkVersion:'35'
```

### 2.3 安装

```
$ adb -s 64a4bad0 install -r app/p5/out/james.dsp-p5-phase2.apk
Performing Incremental Install
Success
Install command complete in 836 ms

$ adb -s 64a4bad0 shell pm path james.dsp
package:/data/app/~~l27mq2tiLbmSj15vDVy1jg==/james.dsp-jCwHUjCkcbHDmP_u7EzEqQ==/base.apk

$ adb -s 64a4bad0 shell sha256sum /data/app/~~l27mq2tiLbmSj15vDVy1jg==/james.dsp-jCwHUjCkcbHDmP_u7EzEqQ==/base.apk
00802267edeb761c51badce8b54e56431991fb1ff2a515f15a04d1229da12423  /data/app/~~l27mq2tiLbmSj15vDVy1jg==/james.dsp-jCwHUjCkcbHDmP_u7EzEqQ==/base.apk
```

安装后 base.apk 的 sha256 与本地 phase2 产物 **完全一致**（00802267…），覆盖安装成功。
versionCode/versionName 与原包相同（61 / 1.6.15-zh3），证书相同（ac4af82b…），同 keystore。

## 3. 启动 App + 端点文件自愈（PASS）

### 3.1 启动

```
$ adb -s 64a4bad0 shell cmd package resolve-activity --brief james.dsp
james.dsp/me.timschneeberger.rootlessjamesdsp.activity.MainActivity

$ adb -s 64a4bad0 shell am start -W -n james.dsp/me.timschneeberger.rootlessjamesdsp.activity.MainActivity
Starting: Intent { cmp=james.dsp/me.timschneeberger.rootlessjamesdsp.activity.MainActivity }
Status: ok
LaunchState: COLD
Activity: james.dsp/me.timschneeberger.rootlessjamesdsp.activity.MainActivity
TotalTime: 658
WaitTime: 665
Complete

$ adb -s 64a4bad0 shell ps -A | grep james.dsp
u0_a383      18221   1961  10438124 287384 binder_thread_read  0 S james.dsp
```

logcat 确认无 FATAL / AndroidRuntime 崩溃。

### 3.2 端点文件

App 启动后 daemon 每 30s 自愈重写端点文件。实测（重启 daemon 后的日志）：

```
$ adb -s 64a4bad0 shell tail -20 /data/adb/jdspd/jdspd.log
[10-25 02:32:03] app files dir ABSENT: /data/data/james.dsp/files (App not installed/started; ...)
...
10-25 02:32:03.535 W/jdspd: tcp: cannot write port/token files into /data/data/james.dsp/files (port=40735)
10-25 02:32:34.488 I/jdspd: tcp: endpoint files (re)written: /data/data/james.dsp/files/jdsp.port port=40735
```

即：目录出现后 ≤30s 内自愈写入成功。

权限与属主：

```
$ adb -s 64a4bad0 shell ls -l /data/data/james.dsp/files/jdsp.port /data/data/james.dsp/files/jdsp.token
-r-------- 1 u0_a383 u0_a383  6 2026-10-25 02:46 /data/data/james.dsp/files/jdsp.port
-r-------- 1 u0_a383 u0_a383 65 2026-10-25 02:46 /data/data/james.dsp/files/jdsp.token

$ adb -s 64a4bad0 shell stat -c %a,%U,%u /data/data/james.dsp/files/jdsp.port
400,u0_a383,10383
$ adb -s 64a4bad0 shell stat -c %a,%U,%u /data/data/james.dsp/files/jdsp.token
400,u0_a383,10383
```

- 权限 = **0400** ✔
- owner = **u0_a383 / uid 10383** = App 的 uid ✔

### 3.3 daemon 侧确认

```
$ jdspcli stats
{"version":"jdspd 2.0.0","pid":27223,...,"transport":{"binder":true,
 "tcp":{"port":46831,"clients":0,"listening":true,"error":""},
 "portFile":"/data/data/james.dsp/files/jdsp.port",
 "tokenFile":"/data/data/james.dsp/files/jdsp.token"}}
```

daemon 自报的 `transport.tcp.error` 由 `"cannot write port/token files into …"` 变为 **空字符串**
（endpoint files written=true），即原来的报错已消除。

## 4. 写路径验证（PASS）

### 4.1 方法说明

- `jdspcli`（binder 通道）位于设备上 `/data/adb/modules/ainur_jamesdsp/backend/jdspcli.dex`，
  通过 `app_process` 调用：`cd /data/adb/modules/ainur_jamesdsp/backend && CLASSPATH=jdspcli.dex app_process /system/bin com.jdsp.cli.Main …`
- App 侧用 uiautomator 定位真实控件（不能用盲点坐标）：
  `uiautomator dump` 得到的 SeekBar 拇指节点 bounds 与 content-desc（当前值）。
- 为让 jdspd 有可挂载的音频会话，用仓库内 `tools/Tone.java`（AudioTrack）编译成 `classes.dex`
  推到设备并以 `app_process` 运行，产生真实 AudioFlinger session。

### 4.2 决定性证据（App UI 滑块 → jdspd 全局参数）

App 前台 + 存在活动音频会话时，拖动"限制器释放时间"滑块（拇指真实坐标 x=400,y=1309 → x=900,y=1309）：

```
=== BEFORE ===
BEFORE:     "1500": "ec51b8bd00004842000040c0"
BEFORE setParamCalls 1477

--- DRAG slider2 thumb 400 -> 900 (释放时间 up) ---
=== AFTER ===
AFTER:      "1500": "ec51b8bd00004842000040c1"
setParamCalls 1477 -> 1504 delta 27

--- diff ---
7c7
<     "1500": "ec51b8bd00004842000040c0",
---
>     "1500": "ec51b8bd00004842000040c1",
36c36
<   "updatedAt": 1792868827,
---
>   "updatedAt": 1792868833,
```

判读：
- App 滑动一个滑块 → jdspd 的 `globalParams["1500"]` 字节发生变化 ✔
- `stats.setParamCalls` 从 1477 增加到 1504（+27）✔
- `dump-config` 中确实出现 App 下发的参数值 ✔

另一组独立证据（更早、限制器阈值滑块）：

```
BEFORE:     "1500": "833e0ac2338b0543000070c1"
BEFORE setParamCalls 11778
--- DRAG slider1 thumb 1200 -> 500 ---
AFTER:      "1500": "74404dc2338b0543000070c1"
AFTER setParamCalls 12706 delta 928
```

### 4.3 App 走的是 TCP 通道（而非本地 AudioEffect）

App 侧日志：

```
$ adb -s 64a4bad0 shell logcat -d -s JdspBridge
10-25 02:41:49.622 24258 24382 I JdspBridge: transport ready: tcp(127.0.0.1:40735)
10-25 02:41:49.625 24258 24382 I JdspBridge: replayed pending global enable=false
```

- transport = **tcp(127.0.0.1:<port>)**，端口来自 App 私有目录的 jdsp.port ✔
- 没有任何 "no usable transport, mirroring disabled" 记录（本地镜像回退未触发）✔
- 打包内容确认 phase2 代码在位：`classes4.dex` 内含
  `me/timschneeberger/rootlessjamesdsp/p5/JdspBridge`、`…/p5/TcpTransport`、`…/p5/JdspRemoteOps`

### 4.4 观察到的注意事项（非阻塞）

- App 的 `JdspBridge` 在**没有任何活动音频会话**时，参数会留在队列里不写
  （`CoreApi.setParam` 需要真实 session；App 侧 session<=0 走广播，广播需要已挂载会话）。
  这解释了若干次"UI 变了但 daemon 无变化"的现象，**不是**写路径失效——
  有会话时写路径稳定生效（见 §4.2）。
- `App` 的 `setEnabled` 走后端时，daemon 日志会出现 `setEnabled session=… -> false/true rc=0`，
  符合 phase2 语义。

## 5. TCP vs binder 一致性（PASS）

```
$ jdspcli ping
ok
$ jdspcli --tcp ping
ok

$ jdspcli --json dump-config > b.json ; jdspcli --tcp --json dump-config > t.json
EQUAL                       # python: json.load(b)==json.load(t) -> True

$ jdspcli sessions > sb.txt ; jdspcli --tcp sessions > st.txt
IDENTICAL                  # diff 无差异

$ jdspcli --json policy list > pb.json ; jdspcli --tcp --json policy list > pt.json
EQUAL
```

同一查询在 binder 与 loopback TCP 两条通道上返回完全相同的结果。
`--tcp` 走的是 App 用的同一份端点文件（jdsp.port + jdsp.token），因此该组数据同时覆盖
"用 `jdspcli --tcp` 模拟 App 通道"的要求。

## 6. 崩溃 / 回滚

### 6.1 phase2 APK 崩溃

**无。** 安装后多次冷启动（`LaunchState: COLD`，pid 18221 / 25871 / 31010）全部成功，
logcat 中无 `FATAL EXCEPTION` / `AndroidRuntime` 崩溃栈（仅见 FirebaseCrashlytics 的
网络超时 `SocketTimeoutException`，属外部网络问题，非崩溃）。

因此**未执行 APK 回滚**，设备保留 phase2。

### 6.2 附带发现：daemon 一次 rc=137 重启（与 App 无关）

```
[10-25 02:46:04] jdspd exited rc=137 (crash), restart in 2s
[10-25 02:46:06] starting jdspd (attempt 2)
10-25 02:46:07.143 I/jdspd: jdspd ready: binder=true tcp=46831 config=/data/adb/jdspd/config.json
```

daemon 自行重启（supervisor 自愈）后端点文件在 `endpoint files written=true` 下重建，
App 随后 `transport ready: tcp(127.0.0.1:46831)` 自动重连成功。
**这一条需要上报给 lead：rc=137 的 daemon 崩溃原因尚未定位**（dmesg 未见 OOM 记录，
logcat 也没有 jdspd 的异常栈），且 `dumpsys failed: IOException: Cannot run program "sh"`
在日志中反复出现，可能是同一根因的线索。

### 6.3 附带发现：App 组件曾被系统置为 disabled

一次 `am force-stop` 之后 `dumpsys package james.dsp` 显示
`User 0: … stopped=true … enabled=2`，导致 `am start` 报
`Activity class {james.dsp/…MainActivity} does not exist`。已用
`pm enable james.dsp/me.timschneeberger.rootlessjamesdsp.activity.MainActivity` 与
`pm enable james.dsp` 恢复。**这不是 phase2 的 bug**，但如果验收工具复测时遇到
"Activity 不存在"，先跑这两条 pm enable。

## 7. 环境恢复（把测试改动还原）

测试中为驱动写路径改动了限制器阈值/释放时间/后置增益。已全部还原：

- jdspd `config.json` 的 `globalParams["1500"]` 恢复为 `cdccccbd000070425e8213c0`
  （= -0.10dB / 60.0ms / -2.30dB，与测试前 dump-config 完全一致）
- App 私有 `shared_prefs/dsp_output_control.xml` 恢复为

  ```xml
  <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
  <map>
      <float name="output_postgain" value="-15.0" />
      <float name="limiter_threshold" value="-0.1" />
      <float name="limiter_release" value="60.0" />
  </map>
  ```

- jdspd 全局开关恢复 `enabled on`
- 备份：`/data/local/tmp/jdspbak/config.json.bak`（测试中期的 config 快照）

最终状态确认：

```
$ adb -s 64a4bad0 shell ps -A | grep -E 'jdspd|james.dsp'
root         27223   3598  7378160  96500 futex_wait_queue  0 S jdspd
u0_a383      31010   1925  9698540 263108 do_epoll_wait    0 S james.dsp

$ jdspcli dump-config | grep 1500
    "1500": "cdccccbd000070425e8213c0",
```

## 8. 回滚方法（如需回退到原 APK）

```bash
# 1) 备份仍在设备上
adb -s 64a4bad0 shell ls -l /data/local/tmp/jdspbak/base.apk
# af1db8e13e7f200758eb6fc84d2f61f2a009e05914e2db3c0a57594363b33854

# 2) 卸载 phase2（保持数据）后装回原包
adb -s 64a4bad0 uninstall james.dsp
adb -s 64a4bad0 push /data/local/tmp/jdspbak/base.apk /data/local/tmp/base.apk
adb -s 64a4bad0 install -r /data/local/tmp/base.apk

# 3) 校验
adb -s 64a4bad0 shell sha256sum /data/app/*/james.dsp*/base.apk
# 应为 af1db8e13e7f200758eb6fc84d2f61f2a009e05914e2db3c0a57594363b33854

# 4) 恢复端点文件（daemon 会自愈，但可手动触发）
adb -s 64a4bad0 shell am start -n james.dsp/me.timschneeberger.rootlessjamesdsp.activity.MainActivity
sleep 35
adb -s 64a4bad0 shell ls -l /data/data/james.dsp/files/jdsp.port /data/data/james.dsp/files/jdsp.token
```

注意：`uninstall` 会删除 App 私有目录（含端点文件），daemon 会在目录重新出现后
≤30s 自愈重写。**不要**用 `pm clear`，除非连同配置一起重置。

## 9. 尚未满足 / 需要他人跟进

1. **前端可用性（任务 6）** — 按任务说明由验收工具代理复测，本代理未做功能性 UI 验收。
2. **daemon rc=137 崩溃（§6.2）** — 根因未定位，发生在本轮验证期间。
   同源可疑日志：`dumpsys failed: IOException: Cannot run program "sh"`（反复出现）。
3. **App 写路径在没有活动音频会话时静默排队** — 属 phase2 设计（session<=0 广播需要已挂载会话），
   但用户在没有播放时调整参数会看到"UI 变了、后台没变"，建议产品侧确认是否为期望行为。
4. **App 组件可能被系统置为 disabled**（§6.3）— 复测前建议先 `pm enable`。
5. 设备 `adb` 链路在本轮中曾短暂掉线一次（约 3 分钟，02:32–02:33），后自动恢复；
   与本次改动无关，但复测时请注意 adb 稳定性。

## 附：本次使用的关键命令速查

```bash
# jdspcli（设备上）
adb -s 64a4bad0 shell "cd /data/adb/modules/ainur_jamesdsp/backend && \
  CLASSPATH=jdspcli.dex app_process /system/bin com.jdsp.cli.Main <cmd>"

# 端点文件
adb -s 64a4bad0 shell ls -l /data/data/james.dsp/files/jdsp.port /data/data/james.dsp/files/jdsp.token
adb -s 64a4bad0 shell stat -c %a,%U,%u /data/data/james.dsp/files/jdsp.token

# App 控件定位（勿盲点）
adb -s 64a4bad0 shell uiautomator dump /sdcard/ui.xml
adb -s 64a4bad0 pull /sdcard/ui.xml ./ui.xml
# SeekBar 拇指节点：bounds="[x1,y1][x2,y2]" content-desc="Value, <当前值>"

# daemon 日志
adb -s 64a4bad0 shell tail -40 /data/adb/jdspd/jdspd.log
adb -s 64a4bad0 shell tail -40 /data/adb/jdspd/jdspd.log.prev
```
