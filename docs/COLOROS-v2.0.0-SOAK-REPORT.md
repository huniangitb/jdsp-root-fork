# ColorOS 机 (64a4bad0) 旧栈 v2.0.0 · 72h soak 中断取证报告

- 日期：2026-10-26
- 设备：OnePlus OPD2404 / OP5D77L1，ColorOS（Android 16，UKQ1.231108.001），高通 pineapple，KernelSU（uid=0 可读）
- 取证对象：`ainur_jamesdsp` **v2.0.0-fork**（versionCode 200）——即"旧栈"
- 取证任务：task-2；结论用于回答 PLAN-next-device.md §4/§6.1 的"10-28 12:19 收 soak 报告"是否还可执行
- 约束：全程只读 adb（`shell` / `exec-out` / `dumpsys`），未 push、未 install、未 reboot；未改 `out/verify/` 与仓库代码
- 设备侧数据一次性拉取时间窗：**2026-10-26 01:20–01:27**（在 01:24 的模块升级之前完成关键采集）

---

## 0. 结论（先行）

1. **旧栈 72h soak 已不可达，且已不可补。** 证据链到 **2026-10-25 20:00:42** 为止；soak 采样进程在 **20:08:14** 的设备重启中消失，设备侧现无任何 soak 进程，`report.tsv` mtime 停在 **20:00**（2880 B）、`launcher.log` 为 **0 B**。
2. **实际只覆盖 7 h 41 m（12:19:27→20:00:42）/ 72 h = 10.7%**，52 条采样（含 1 条撕裂记录）对预期 433 条 = **12.0%**；距计划终点 10-28 12:19:27 还差 **2 天 16 小时 18 分 45 秒**。
3. **重启原因判定：用户态发起的正常重启，非崩溃。** `ro.boot.bootreason` / `sys.boot.reason` / `sys.boot.reason.last` / `vendor.oplus.boot.bootreason` 四路均为 `reboot,`；Shutdown 检查点明确记录 **20:08:00.859 由 binder（pid 6692）调用 `PowerManagerService$BinderService.reboot`**；`critical_event.log` 在 03:25:22 与 20:08:36 之间**没有任何 boot 记录、也没有 systemserver crash 记录**。与 **JDSP 无关**。
4. **`fatal=3` 与 JDSP 完全无关**：3 条全部是 **`com.tencent.soter.soterserver`**（腾讯 SOTER 指纹服务）在 15:37:19、15:37:39、17:25:42 的同签名 NPE（`GraphicsEnvironment.queryAngleChoice`）。重启后任务点名的 5 条 `system_app_crash`（20:16 / 21:53 / 21:54 / 22:48×2）**同样是 soter**，另有 20:08:52、20:09:04、20:15:55 共 3 条，合计 8 条；`logcat -b crash` 中 10 条 FATAL = 8 条 soter + 2 条 `tw.nekomimi.nekogram`。**零条命中 james.dsp / jdspd / audioserver / libjamesdsp**。
5. **`paramErrors` 从 353969 掉到 420 → 0 → 2 → 1066 不是翻转、不是重启**：它是 `jdspd` **进程内累计计数器**，随 daemon 进程重启归零。中间发生的是 **13:14:27 / 13:22:28 / 13:25:02 / 13:31:38 四次 jdspd 崩溃**（dropbox `SYSTEM_TOMBSTONE`）+ `jdspd.sh` 看护重启，PID 21612→13875→17722→20048 与 `uptimeMs` 归零同步佐证。
6. **附带重大发现（比 fatal 列更值得关注）**：旧栈在 soak 开始前约 1 小时 20 分内**把 audio HAL 打崩 3 次**，崩溃帧全在 `/vendor/lib64/soundfx/libjamesdsp.so`（`jdsp_state_set_path+116` / `BS2BInit+160` / `convBench+20`，跳转目标为未重定位的低地址 `0x16xxxx`）；另外 jdspd 自身在 09:36–13:31 崩溃 **7 次**（ART/dex 层）。
7. **建议：不要为旧栈续跑 72h soak。** 且 **10-26 01:24 设备模块已被替换为 v2.1.0**（`module.prop`=210，jdspd.dex `83bc5808` / cli `2483ecd7` / libjamesdsp `265544f5`），旧栈在设备上**已不存在**。新的 72h 窗口只能从现在起算，且应先修好 soak 判据（见 §10.3）。

> 一句话：**旧栈 soak 不是"跑完了"，是被一次人工重启掐断在 10.7% 处；`fatal=3` 是机器上其它 App 的噪声，不是 JDSP 的健康指标；真正的旧栈缺陷在 audio HAL 的 3 次引擎崩溃里。**

---

## 1. 旧栈身份基线（取证对象确认）

| 项 | 值 | 来源 |
|---|---|---|
| module.prop | `version=v2.0.0-fork (2026-10-25)` / `versionCode=200` | `module.prop`（01:19 拉取） |
| backend/jdspd.dex | `026bb00650c999b9216f377430ec7826` | `md5-oldstack.txt:3` |
| backend/jdspcli.dex | `a8b9a502f3e9548931c384a27c4ed7bb` | `md5-oldstack.txt:4` |
| libjamesdsp.so (lib64 & lib) | `9bbb05d89d78e652ea3376a7407693ab` | `md5-oldstack.txt:1-2` |
| JamesDSPManager.apk | `a73079b9ea831bc1ebf6a41660cb293e` | `md5-oldstack.txt:5` |
| 运行模式 | `mode=auto`（框架 postprocess 自动挂载） | `boot-forensics.txt`（`boot.log` 显示 `[mode] service mode=auto ... OK`） |

**取证对象在 01:24 被替换**（见 §9.4），因此本报告全部结论只对本表中哈希成立。

---

## 2. soak 时间线（设备侧独立证据）

| 时刻 (CST) | 事件 | 证据 |
|---|---|---|
| 03:25:22 | 设备启动（本次 soak 所在的 boot） | `critical_event-tail.txt` |
| 11:15:00 | jdspd 进程代 1 启动（由 `uptimeMs` 反推） | `report.tsv` L3–L15 |
| 11:20:06 | 一次**零样本** soak 运行（只写表头即结束） | `report.tsv:2` |
| 11:21:40–11:22:13 | 干跑 A：4 条样本，间隔 11 s | `report.tsv` L3–L6 |
| 11:22:23 | 干跑 A 结束 | `report.tsv:7` |
| **12:19:27** | **"72h" 正式窗口开始**（`launcher.log` 0 B，mtime 12:19） | `soak-dir-ls.txt` |
| 13:14:27 / 13:22:28 / 13:25:02 / 13:31:38 | jdspd 崩溃（SIGSEGV/SIGTRAP）→ 看护重启 | `dropbox-print.txt` SYSTEM_TOMBSTONE |
| 13:32:18 | jdspd 进程代 4 启动，此后**再无 jdspd 崩溃** | `report.tsv` L18+ |
| 15:37:19 / 15:37:39 / 17:25:42 | soter 3 次 Java 崩溃（= `fatal` 0→2→3） | `dropbox-prereboot-crashes.txt` |
| **20:00:42** | **最后一条 soak 样本**（uptimeMs 23303602） | `report.tsv:56` |
| 20:05:33 | KernelSU 管理器 App 崩溃（window_resize，`NoSuchFieldException: CREATOR`） | `dropbox-prereboot-crashes.txt` |
| **20:08:00.859** | **Shutdown 请求：binder → `PowerManagerService$BinderService.reboot`（pid 6692）** | `shutdown-checkpoint-2008.txt` |
| 20:08:07.372 / 20:08:08.380 | jdspd 记录 `dumpsys failed ... "sh": error=2`（用户态拆除中） | `jdspd.log.prev` 末尾 |
| **20:08:14** | 新内核启动（`date - uptime` 原子测得）；jdspd.log.prev 最后一行 20:08:08 后无任何日志 | 见 §5.4 |
| 20:08:36（系统时钟校正后） | system_server 启动（boot+19 s） | `critical_event-tail.txt` |
| 20:08:47.497 | `SYSTEM_BOOT` dropbox 落盘；`ro.runtime.firstboot=1792930127479` | `dropbox-ls.txt` / `getprop-boot.txt` |
| **20:08 之后** | **`report.tsv` 再无写入；无任何 soak 进程** | `soak-procs.txt` / `soak-dir-ls.txt` |

### 2.1 soak 进程确实不存在（当前态）

- `pgrep -af "jdspd|soak"` 只剩 `sh .../jdspd.sh`(3589) 与 `jdspd`(3675)，**没有 `sh jdsp-soak_device.sh`** → `soak-procs.txt`
- `/data/local/tmp/jdsp-soak/`：`report.tsv` **2880 B / mtime 2026-10-25 20:00**，`launcher.log` **0 B / mtime 12:19** → `soak-dir-ls.txt`
- `report.tsv` 归档副本 md5 `511fadf651305856cb08d1ff7ac3e791`，与 01:21 从设备重取的 `report-device.tsv` **逐字节一致**（证明归档副本成立于 01:24 升级之前）
- soak 由主机侧 `scripts/soak.sh` 经 `setsid` 拉起（`jdsp-soak_device.sh:2`），**不随开机自启**，因此重启即永久丢失

---

## 3. report.tsv 量化

### 3.1 文件构成

| 项 | 值 |
|---|---|
| 总行数 | 57（含末尾空行）→ 1 行表头 + 2 行 `soak complete` + **52 条数据记录**（51 条完整 + 1 条撕裂） |
| 字段 | `time, procs, pid, uptimeMs, watcherFail, setParams, paramErrors, fatal, anr, audiotrackCrash, logTcpErr` |
| 撕裂记录 | 第 8–9 行，一条记录被拆成 `10-25 12:19:27` + `\t0` 与 `0\t\t3868168\t0\t355408\t353969\t0\t0\t0\t0` 两行（12 字段，预期 11）。可复原为 `procs=0, pid=空, uptimeMs=3868168, watcherFail=0, setParams=355408, paramErrors=353969, fatal=0`。**不影响结论**，但它是 Run B 起点的文件完整性缺陷 |

### 3.2 采样节奏与缺口

| 段 | 区间 | 条数 | 间隔 | 说明 |
|---|---|---|---|---|
| 零样本运行 | 11:20:06 | 0 | — | 只写表头即退出（SOAK_SECONDS 配置失误） |
| 干跑 A | 11:21:40→11:22:13 | 4 | **11 s** | 33 s 窗口 |
| **监测空洞** | 11:22:13→12:19:27 | 0 | **3434 s = 57 m 14 s** | 两段之间完全无采样 |
| Run B（"72h"） | 12:19:27→20:00:42 | 47 | 首对 **30 s**，其后 **600–602 s** | 首对 30 s 说明 Run B 起跑被触碰过两次（与撕裂记录同源） |
| Run B 尾部缺口 | 20:00:42→EOF | 0 | ≥ 5 h 20 m（至今） | 采样进程已死 |

- Run B 最大采样间隔 **602 s**，中位 **601 s**（脚本 `sleep 600` + `jdspcli stats` 与 `logcat` 调用开销约 1 s）
- **600 s 节奏本身是达标的**：无漂移、无堆积、无重复行

### 3.3 覆盖率（直接证伪 72h）

| 指标 | 实际 | 计划 | 达成 |
|---|---|---|---|
| 挂钟覆盖 | 7 h 41 m 15 s（27 675 s） | 72 h（259 200 s） | **10.7%** |
| 样本数 | 52 | 433 | **12.0%** |
| 最长连续无缺口窗口 | 6 h 20 m 37 s（13:40:05→20:00:42，39 条） | 72 h | 8.8% |
| 距计划终点 | 10-28 12:19:27 | — | **缺 2 d 16 h 18 m 45 s** |

### 3.4 daemon 进程代（由 `uptimeMs` 反推 `start = time - uptimeMs`）

| 代 | daemon 启动时刻 | 样本 | paramErrors | setParams | fatal |
|---|---|---|---|---|---|
| 1 | 11:14:59 | 10 | 29 859 → **360 044** | 29 922 → 382 883 | 0 |
| 2 | 13:15:11 | 1 | 420 | 420 | 0 |
| 3 | 13:25:16 | 1 | 0 | 112 | 0 |
| 4 | 13:32:18 | **39** | 2 → **1 066**（14:30:10 后冻结 **5 h 30 m**） | 1 338 → 22 293（16:30:21 后冻结） | 0→2→3 |

- **代 4 的 `uptimeMs` 单调性可作时钟级佐证**：13:40:05→20:00:42 挂钟差 22 837 000 ms，`uptimeMs` 差 22 837 426 ms，**漂移 +426 ms（18.7 ppm）** → 这 6 h 20 m 内 jdspd **没有重启、没有被冻结**。
- 代 4 从 13:32:18 存活到 20:08:14 重启，**连续 6 h 35 m 56 s**。

---

## 4. `procs` 列不可作为存活判据（勘误）

干跑 A 的 4 行 `procs=0` 且 `pid` 为空，但同一行的 `uptimeMs` / `setParams` / `paramErrors` 都在连续增长——**daemon 明明活着**。原因：脚本 `PROCS=$(pidof jdspd)` / `PID=$(pidof jdspd ...)` **不经 su**（`jdsp-soak_device.sh:35-36`），而 `STATS=$(su -c "$CLI stats")` 走 su（:37）。前者在受限上下文里看不到 root 进程，后者能。

→ **`procs` / `pid` 为 0 不能推出 daemon 已死**；存活判定应以 `uptimeMs` 单调性 + `jdspcli stats` 可回应为准。

---

## 5. 中断判定：2026-10-25 20:08 重启（原因 = 用户态正常重启）

### 5.1 四路 bootreason 一致：`reboot,`

    ro.boot.bootreason           = reboot,
    sys.boot.reason              = reboot,
    sys.boot.reason.last         = reboot,
    vendor.oplus.boot.bootreason = reboot,

（`getprop-boot.txt`；`/proc/cmdline` 另见 `oplusboot.mode=reboot`）

在 Qualcomm/Android 上 `reboot,` 是"内核正常重启、无 panic/watchdog/hard_reset"的默认值；若为看门狗或内核崩溃，此处会是 `watchdog` / `kernel_panic` / `hard_reset` 等。**无 pstore 证据**（`/sys/fs/pstore` 为空、`/proc/last_kmsg` 不存在 → `pstore-ls.txt`），即没有内核崩溃残留。

### 5.2 关机发起者被点名（决定性证据）

`/data/system/shutdown-checkpoints/checkpoints-1792930081001`（mtime 20:08）：

    Shutdown request from BINDER at 2026-10-25 20:08:00.859 GMT+08:00 (epoch=1792930080859)
    com.android.server.power.PowerManagerService$BinderService.reboot
    From process ? (pid=6692)

    Shutdown request from SYSTEM at 2026-10-25 20:08:00.891 GMT+08:00 (epoch=1792930080891)
    com.android.server.power.ShutdownThread.shutdownInner

- 走的是 **`PowerManagerService.reboot` → `ShutdownThread`** 的**有序关机**路径，不是内核/看门狗路径。
- 发起者是 **pid 6692**（名字未解析为 `?`，因该进程在记录补全前已退出）；对照 10-24 00:15 那次，同一文件能解析出 `com.android.systemui` + `reason userrequested` —— 说明该记录格式可靠，20:08 这次确实是**一个未具名的特权进程通过 binder 请求重启**（典型为 `svc power reboot` / `cmd power reboot` / 持 REBOOT 权限的管理器 App）。
- 结合 **20:05:33 KernelSU 管理器 App 崩溃**（`data_app_crash`，window_resize），现场画像为：**有人在 20:05 前后操作模块管理器 UI，2.5 分钟后发起了重启**。
- **注**：`adb reboot` 通常直接写 `sys.powerctl`、不经过 PowerManagerService；本次走了 PMS，故更可能是 shell 侧 `svc power reboot` 或 App 内重启按钮。pid 6692 身份在重启后已不可恢复（`logcat` 非持久化），**这是本报告唯一没能闭合的点**。

### 5.3 `critical_event.log`：没有"非预期"标记

    [2026-10-25 03:25:22][19s]system bootup;systemserver pid:3801
    ...
    #system bootup====>
    [2026-10-25 20:08:36][19s]system bootup;systemserver pid:3744

- 03:25:22 与 20:08:36 之间**没有任何 boot 记录** → **不存在 11:15 / 13:15 / 13:25 / 13:32 的重启**（§3.4 那些 `uptimeMs` 归零是 **daemon 重启**，不是设备重启）。
- 对比 02:31 那次：`critical_event.log` 明确写了 `systemserver java crash`（2 次）后才在 02:32:10 重启；**20:08 这次没有任何 crash 前置记录** → 分类器工作正常，本次是干净的正常重启。
- `dropbox` 的 `SYSTEM_BOOT` 也只在 5 个时刻出现：02:19:37 / 02:28:14 / 02:32:24 / 03:25:35 / **20:08:47**。

### 5.4 daemon 视角：被打断而非"崩溃退出"

`jdspd.log.prev`（12:53:50→20:08:08，设备侧已被 01:25 轮转覆盖，**归档副本为权威**）末尾：

    10-25 20:08:01.058 I/jdspd: tcp: client accepted /127.0.0.1:43544
    10-25 20:08:02.125 I/jdspd: policy: 453 package entries, 388 uids
    10-25 20:08:03.073 I/jdspd: tcp: client accepted /127.0.0.1:43552
    10-25 20:08:07.372 W/jdspd: dumpsys failed: IOException: Cannot run program "sh": error=2, No such file or directory
    10-25 20:08:08.380 W/jdspd: dumpsys failed: IOException: Cannot run program "sh": error=2, No such file or directory

- **无 shutdown / SIGTERM 记录，也无任何 JDSP 异常**；日志在系统拆除 `/system/bin` 之后被 init 直接收割（"sh" 消失即 teardown 迹象）。
- soak 死亡区间可精确夹逼：**20:08:08 还活着，20:08:14 已随内核重启消失**；最后样本 20:00:42，**尾部 7 m 26 s 无采样但 daemon 确实存活**。

### 5.5 重启原因判定小结

| 候选 | 判定 | 依据 |
|---|---|---|
| 内核 panic / watchdog | 排除 | 四路 bootreason=`reboot,`；pstore 空；`critical_event.log` 无 crash 前置 |
| 掉电 / 硬复位 | 排除 | 有序关机路径（PMS→ShutdownThread），shutdown 检查点写全 |
| system_server 崩溃 | 排除 | 无 `system_server_crash` / `SYSTEM_RESTART` 记录（对比 02:31 有） |
| OOM / 过热 | 无证据 | 无相关 dropbox 条目 |
| **用户态主动重启** | **成立** | 20:08:00.859 binder→`PowerManagerService$BinderService.reboot`（pid 6692） |
| JDSP 诱发 | 排除 | 重启前后无任何 jdspd / james.dsp / libjamesdsp 崩溃；daemon 已连续稳定 6 h 35 m |

---

## 6. `fatal=3` 的归属（结论：与 JDSP 无关）

### 6.1 先把口径说清楚 —— 本次取证最关键的勘误

`jdsp-soak_device.sh:42`：

    FATAL=$(logcat -d -b crash 2>/dev/null | grep -c "FATAL EXCEPTION")

**`fatal` 列 = 整个系统 `crash` 环形缓冲里 "FATAL EXCEPTION" 的行数，与 JDSP 毫无关系。** 它既不是"JDSP 致命错误计数"，也不是累计值：缓冲被清空/轮转时它会**变小甚至归零**（脚本自身注释也承认"会翻转"，:7）。

### 6.2 3 条 FATAL 的归属

`fatal` 0→2 发生在 15:30:15–15:40:16 之间，2→3 发生在 17:20:25–17:30:26 之间。dropbox 精确对上：

| 时间 | fatal 列 | dropbox 条目 | 归属包 | 崩溃签名 |
|---|---|---|---|---|
| 15:37:19.412 | →2 | `system_app_crash@1792913839415` | **com.tencent.soter.soterserver**（pid 30398） | NPE `GraphicsEnvironment.queryAngleChoice` |
| 15:37:39.795 | →2 | `system_app_crash@1792913859800` | **com.tencent.soter.soterserver**（pid 31060） | 同上 |
| 17:25:42.364 | →3 | `system_app_crash@1792920342369` | **com.tencent.soter.soterserver**（pid 19066） | 同上 |

→ **3 条全部是腾讯 SOTER 指纹服务的启动期 NPE**，与 james.dsp / jdspd / audioserver 无关（依据：`dropbox-prereboot-crashes.txt`）。

### 6.3 "为什么只有 3 条"本身也证明该列不可用

本次 boot（03:25:35 起）到 17:25 为止，dropbox 记录的 soter Java 崩溃共 **9 条**（03:27:15、03:27:27、03:57:27、04:57:27、06:27:28、08:28:25、15:37:19、15:37:39、17:25:42）。若 `fatal` 真是累计崩溃数，17:30 时应为 **9** 而不是 **3**。

结论：**期间 `crash` 缓冲被清空/裁剪过至少一次**（项目自身脚本 `scripts/verify-src-app.sh:48` 即含 `logcat -c`），所以 `fatal` 只反映"当前缓冲内容"，**既不能当累计数，也不能当 JDSP 判据**。对比校验：当前 boot 缓冲 10 条 FATAL = dropbox 同期 8 条 soter + 2 条 `tw.nekomimi.nekogram`（20:17:06 / 20:17:44），口径一致性成立。

---

## 7. 重启后 `system_app_crash` 的归属（结论：与 JDSP 无关）

任务点名的 4 个时刻全部命中 **`com.tencent.soter.soterserver`**（22:48 有两条）：

| 时间 | 条目 | 归属包 | 进程态 | 签名 |
|---|---|---|---|---|
| 20:16:05 | `system_app_crash@1792930565524` | com.tencent.soter.soterserver（uid 10259） | 非前台，Process-Runtime 89 ms | NPE `queryAngleChoice` |
| 21:53:45 | `system_app_crash@1792936425283` | 同上（pid 3133） | 非前台，370 ms | 同上 |
| 21:54:00 | `system_app_crash@1792936440896` | 同上（pid 3484） | 非前台，142 ms | 同上 |
| 22:48:20 | `system_app_crash@1792939700209` | 同上（pid 32427） | 非前台，108 ms | 同上 |
| 22:48:21 | `system_app_crash@1792939701342` | 同上（pid 32569） | 非前台，89 ms | 同上 |

补充（同一 boot 内、任务未点名但一并归档）：20:08:52(pid 12901)、20:09:04(pid 13835)、20:15:55(pid 21834) 亦为 soter → **重启后 soter 共 8 条**。

**判定**：`system_app_crash` 只由 system UID / 特权预装应用产生；soter 在**绑定应用时**于 `ActivityThread.setupGraphicsSupport → GraphicsEnvironment.setupAngle → queryAngleChoice` 取不到 OEM 资源配置而 NPE，属 **ColorOS 预装组件问题**，进程存活仅 89–370 ms（启动即崩），与音频路径、JDSP 模块、jdspd 均无交集。整个 dropbox（308 条）中**没有任何条目的 Process 是 james.dsp / jdspd / audioserver**。

> 说明：dropbox 中确有 `libjamesdsp` 相关条目（14 条 tombstone），但全部落在 §9.1 的 HAL/离线渲染崩溃里，与本节 Java 崩溃无关。

---

## 8. `paramErrors` 跳变的解释（结论：进程内计数器随 daemon 重启归零）

### 8.1 事实

| 时刻 | pid | paramErrors | setParams |
|---|---|---|---|
| 11:21:40–11:22:13 | （pidof 不可见） | 29 859 → 34 769 | 29 922 → 34 882 |
| 12:19:27（撕裂行） | — | **353 969** | 355 408 |
| 13:00:01 / 13:10:02 | 21612 | **360 044**（峰值） | 382 883 |
| 13:20:03 | **13875** | **420** | 420 |
| 13:30:04 | **17722** | **0** | 112 |
| 13:40:05 | **20048** | **2** | 1 338 |
| 14:30:10 | 20048 | **1 066** | 17 032 |
| 14:40:11 → 20:00:42 | 20048 | **1 066（冻结 5 h 30 m）** | 22 293（16:30 后冻结） |

### 8.2 机制

1. **`paramErrors` 来自 `jdspcli stats` 的 JSON（脚本 :41），是 jdspd 的进程内累计计数**，随进程消亡清零。`uptimeMs` 同源同进程，故两者必须同步跳变——实测正是如此（`start = time - uptimeMs` 恰在 13:15:11 / 13:25:16 / 13:32:18 三次跳变）。
2. **这三次不是设备重启**：`critical_event.log` 与 dropbox `SYSTEM_BOOT` 都证明 03:25:35→20:08:47 之间无 boot。而是 **`jdspd` 崩溃 + `jdspd.sh` 看护重启**：
   - `13:14:27 jdspd SIGSEGV` → 13:15:11 新代
   - `13:22:28 jdspd SIGTRAP` / `13:25:02 jdspd SIGSEGV` → 13:25:16 新代
   - `13:31:38 jdspd SIGTRAP` → 13:32:18 新代（此代稳定到重启）

   `jdspd.sh` 的 supervisor 循环（"jdspd exited rc=\$rc (crash), restart in 2s"）正是重启来源。
3. **353 969 不是"翻转"而是真增长**：11:15–13:00 期间存在 **"backend globally disabled" 的 setParam 拒绝风暴**。`jdspd.log.1` 11 096 行中 **8 248 行是 `setParam: refused, backend is globally disabled`**（10 点 2 287 / 11 点 371 / 12 点 8 405），`jdspd.log.prev` 另有 2 904 行（全在 12 点）。`paramErrors` 累计的就是这类被拒参数写。风暴在 13:00 前停止后，计数只从 360 044 缓慢爬到峰值（13:00→13:10 已完全不动）。
4. **代 4 的 `2 → 1 066`（14:30:10）**：redeploy 之后新一轮参数提交产生的错误总数；**14:30:10 之后 5 h 30 m 恒定 1 066**，且 16:30 之后连 `setParams` 也停在 22 293 —— **最后 3.5 h 系统完全空闲、零参数错误**，这是旧栈最有力的"健康"证据。

### 8.3 对任务描述"31366 跳到 1066"的订正

- `31 366` 是 **11:21:51 的早期读数**，`1 066` 是 **14:30 之后代 4 的稳定平台**；两者之间隔着的不是一次跳变，而是**约 35 万次错误累计 + 3 次 daemon 崩溃重启**。
- 若要引用"重启前 vs 重启后"的正确一对，是 **360 044 → 420**（13:10:02 → 13:20:03），这才是同一个跳变边界上的两个值。

---

## 9. 附带发现：旧栈真正的缺陷（`fatal` 列看不到的那些）

### 9.1 audio HAL 被旧栈引擎打崩 3 次（11:00–11:15，soak 开始前）

崩溃进程均为 `/vendor/bin/hw/android.hardware.audio.service_64`，**崩溃帧全在 `/vendor/lib64/soundfx/libjamesdsp.so`**，信号统一 `SIGSEGV / SEGV_MAPERR`：

| 时间 | 故障线程 | #00（未解析） | #01（引擎符号） |
|---|---|---|---|
| 11:00:24.019 | `HwBinder:2061_2` | `pc 0x170a60 <unknown>` | `jdsp_state_set_path+116` |
| 11:14:28.490 | `HwBinder:17589_` | `pc 0x16fcb0 <unknown>` | `BS2BInit+160` |
| 11:14:58.927 | `HwBinder:21378_` | `pc 0x16fcb0 <unknown>` | `convBench+20` |

- 三次的 `#02` 都是 `__pthread_start` → **是 libjamesdsp 自己 `pthread_create` 出来的线程**，在跑引擎初始化/状态重放时崩的。
- `#00` 的地址都是 `0x16xxxx` 这种**极低地址**（量级恰等于函数在 .so 内的未重定位偏移），而非正常加载地址（该库映射在 `0x73xxxxxxxx` 段）→ 强指向"**线程入口/函数指针未重定位即被调用**"这一类问题（可能来自 .so 的构建/裁剪/重打包，或同一库被两条路径各加载一份导致指针混用）。
- 影响面：`jdsp_state_set_path` 就是**热加载状态重放路径**，`BS2BInit` / `convBench` 是初始化路径 → 与 v2.1.0 的 0003（参数队列+块边界）、0004（AsyncPersister）针对的问题域高度重合。**这是"旧栈是否值得继续 soak"真正的答案所在**，也是 v2.1.0 需要在 ColorOS/高通平台重点复验的点。
- **注意时间**：3 次全在 11:00–11:15，soak 窗口（12:19 起）内 **HAL 零崩溃**。

### 9.2 jdspd 自身崩溃 7 次（09:36–13:31），均不在 soak 稳定期内

| 时间 | 信号 | 崩溃点 |
|---|---|---|
| 09:36:46 | SIGSEGV SEGV_ACCERR | libart `FindFieldImpl` / `ResolveFieldWithAccessChecks` ← `jdspd.dex` |
| 11:00:24 | SIGTRAP TRAP_BRKPT | `jdspd-watcher` 线程，`jdspd.dex` |
| 11:14:28 | SIGSEGV SEGV_MAPERR | `jdspd-watcher` → `SessionWatcher.reconcile+994 / +836`、`SessionWatcher.loop+172` |
| 13:14:27 | SIGSEGV | `jdspd.dex` |
| 13:22:28 | SIGTRAP | `jdspd.dex` |
| 13:25:02 | SIGSEGV | `jdspd.dex` |
| 13:31:38 | SIGTRAP | `jdspd.dex` |

- **崩溃点全在 dex/ART 层，7 条 tombstone 的内存映射里都没有 `libjamesdsp.so`**（jdspd 不加载引擎库，只经 binder 驱动 HAL）→ 与"引擎不稳"是两类独立问题。
- **13:31:38 之后到 20:08 重启，jdspd 零崩溃、零 tombstone**。
- 归档里另有 11 条 `libjamesdsp` 崩溃（02:49–03:00，`app_process` / `offlinerender` / `offline_render`，`EffectCreate+308`、`EffectDSPMainCommand+188/+580`）——那是**离线渲染/验收压测工具自崩**，不属 soak。

### 9.3 旧栈在设备上"最后 6 h 35 m"是干净的

- jdspd 代 4：13:32:18 → 20:08:14，无崩溃、`watcherFailures=0`、`paramErrors` 冻结、`uptimeMs` 漂移 18.7 ppm。
- 同期：**零 tombstone、零原生崩溃**，仅 3 条 soter Java NPE（§6）。
- 结论：**旧栈不是"不稳定跑不完"，而是"已经稳了但被重启掐断"**。这个区分对下一阶段很重要：不必为旧栈的稳定性焦虑，但确实**没有拿到 72h 的证据等级**。

### 9.4 结案时的设备状态变化（必须记录）

**2026-10-26 01:24**，设备模块被替换为 **v2.1.0-fork（versionCode 210）**：

| 项 | 旧栈 (v2.0.0) | 现在 (v2.1.0) |
|---|---|---|
| module.prop | versionCode 200 | **210**（mtime 01:24） |
| backend/jdspd.dex | 026bb006 | **83bc5808**（与 PLAN §7 期望一致） |
| backend/jdspcli.dex | a8b9a502 | **2483ecd7** |
| libjamesdsp.so | 9bbb05d8 | **265544f5** |
| jdspd 版本自报 | 2.0.x | `jdspcli stats` → **"jdspd 2.1.1"**，pid 3714，`instanceStartedAt` 2026-10-26 01:25:42 |
| mode | auto（soak 期） | backend（01:26） |

- **含义**：旧栈已在设备上不存在；即使想"补跑旧栈 72h"也必须回滚，不可行也不建议。
- **副作用**：设备侧 `jdspd.log.prev` 于 01:25 被 `jdspd.sh` 的 `jdspd_rotate_log` 覆盖（700 577 B → 52 656 B）。**归档副本（01:19，700 577 B，末尾 20:08:08）是唯一权威版本**，后续再分析必须用归档，不能再用设备现文件。
- **对 PLAN 的影响**：PLAN-next-device.md §6.1 的"10-28 收 soak 报告 → 再升级"顺序已被实际执行反向进行；§4 的"首日零 fatal"门槛在本次升级前**从未被满足过**（且按 §6.3 该门槛本身不成立）。

---

## 10. 对"旧栈是否还值得继续 soak"的建议

### 10.1 结论

**不值得，也不可能。** 三条独立理由：

1. **物理上不可能**：旧栈 01:24 已被 v2.1.0 覆盖，需回滚才能续跑；回滚 = 又一轮装/重启，与"长稳"互斥。
2. **证据上不可补**：soak 进程不随开机自启，窗口已断在 10.7%；补跑得到的是"另一个窗口"，不能声称"10-25 12:19 起的 72h 通过"。
3. **信息上无增益**：旧栈真正的不稳定（§9.1/§9.2）已在 13:31 之前全部暴露，并被 v2.1.0 的 0003/0004 + jdspd 2.1.x 针对性覆盖；再拿旧栈跑 72h，最好结果也只是"没再复现"，无法推进结论。

### 10.2 该做的（替代动作）

1. **不宣称 v2.0.0 通过 72h soak**；验收口径统一为"v2.0.0 在 ColorOS 上获得 **6 h 35 m 无崩溃连续运行 + 7 h 41 m 采样（10.7%）**，因 10-25 20:08 人工重启终止"。
2. **把 §9.1 的 3 条 HAL 崩溃当作 v2.1.0 的重点复验项**，而不是旧栈的"历史噪声"：
   - 用例：`jdsp_state_set_path` 热加载路径（播放中改参数/换状态文件）+ `BS2BInit` / `convBench` 初始化路径，在**播放态反复重放状态**，观察 audioserver/HAL 是否重启（`dumpsys media.audio_flinger` 的实例代数 + dropbox 是否新增 tombstone）。
   - 判据要盯 **libjamesdsp.so 符号级帧**，不要只盯进程是否活着。
3. **重启前先确认"新栈的 72h 窗口起点"**：本轮窗口最早只能从 **10-26 01:24（v2.1.0 安装）之后**的某次 boot 起算；原计划 10-28 12:19 的收报告时点作废，新终点 = 起点 + 72 h（若从 01:24 起算即 **10-29 01:24**）。

### 10.3 soak 工具必须先修的 3 处（否则下一个 72h 同样白跑）

| 问题 | 现状 | 建议 |
|---|---|---|
| **`fatal` 判据无效** | `logcat -d -b crash \| grep -c "FATAL EXCEPTION"`：全系统口径、随缓冲清空而下降、被 soter 噪声长期污染 | 改为 **JDSP 定向**：统计窗口起点后 dropbox 中 `Process ∈ {james.dsp, jdspd, audioserver, android.hardware.audio.service_64}` 的条目数；或统计 tombstone 中出现 `libjamesdsp` / `jdspd` 的条目数。并保留人工 review 入口 |
| **重启/daemon 重启不可见** | 只记 `uptimeMs`（daemon 自述），不记 boot_id；重启后无人续跑，报告"静默死亡" | 每行追加 `boot_id`（`/proc/sys/kernel/random/boot_id`）、daemon pid、daemon start epoch、设备 uptime；缺任一即判该行"跨重启" |
| **进程不存活** | `setsid` 一次性拉起，重启即丢 | 用 `/data/adb/service.d/` 开机自启 + 幂等（已有 `report.tsv` 时追加而非覆盖），或主机侧 watchdog 定时校验 `report.tsv` mtime，超过 2×interval 未更新即告警/重启 |

其他小修：`procs` / `pid` 走 su（§4）；修掉 Run B 起点的撕裂写（先写临时文件再原子 mv，或单次 `printf` 全行）；把 6 h 一次 tone 与"谁拥有设备"的约定写进报告头部。

---

## 11. 证据边界（明确没做到的）

1. **20:08:00 的发起进程身份未闭合**：shutdown 检查点只留下 `pid=6692`（名字为 `?`）。重启后 `logcat` 缓冲不持久，无法回溯；建议后续启用持久化 logcat（`persist.log.tag` / logd persistent buffer）以闭合此类问题。
2. **旧 boot 的 `logcat -b crash` 已随重启丢失**：§6 的 3 条 FATAL 只能靠 dropbox 反推 + 时间窗对齐，无法给出 logcat 原文（当前 `crash-logcat.txt` 只覆盖 20:08 之后）。
3. **`fatal` 缓冲被清空的具体触发者未知**：只证明"必然被清过"（§6.3）与"项目脚本确有 `logcat -c` 用法"，未找到该次清空的直接记录。
4. **`dmesg` 只覆盖当前 boot**（17989 s 起），不含重启前，故 §5 未用 dmesg 佐证。
5. **§9.1 的根因是推断**：未解析 `libjamesdsp.so` 的符号表/重定位表（需要离线分析该 .so；本任务禁改仓库，故未做）。建议在 v2.1.0 验收里补一次 `readelf -r` / `llvm-dwarfdump --debug-line` 级别的核对。

---

## 12. 证据索引（`out/oldstack-evidence/`）

| 文件 | 大小 | 支撑的结论 |
|---|---|---|
| `report.tsv` / `report-device.tsv` | 2 880 B（md5 `511fadf6…`） | §2 §3 §8（两份逐字节一致，成立于升级前） |
| `jdsp-soak_device.sh` | 2 657 B | §4 §6.1 §10.3（fatal / procs 口径） |
| `jdspd.log.prev` | 700 577 B | §5.4 §8.2（preboot 权威日志，设备侧已被覆盖） |
| `jdspd.log.1` | 1 049 783 B | §8.2（refused 风暴时间分布） |
| `jdspd.log` | 51 258 B | 旧栈 boot 期日志（01:19 拉取） |
| `dropbox-print.txt` | 15 268 468 B | §6 §7 §8 §9（308 条全量，含 tombstone 帧与包名） |
| `dropbox-ls.txt` | 27 297 B | §5.3（SYSTEM_BOOT 5 次） |
| `dropbox-prereboot-crashes.txt` | 13 209 B | §6.2（3 条 soter） |
| `dropbox-postreboot-crashes.txt` | 8 188 B | §7（5 条 soter） |
| `crash-logcat.txt` / `logcat-all.txt` | 25 461 / 4 074 084 B | §7（重启后 FATAL 归属） |
| `shutdown-checkpoint-2008.txt` | — | §5.2（决定性：PMS.reboot / pid 6692） |
| `critical_event-tail.txt` | — | §5.3（boot 序列与 crash 前置对比） |
| `getprop-boot.txt` / `getprop.txt` | — | §5.1（四路 bootreason） |
| `pstore-ls.txt` | — | §5.1（无 panic 残留） |
| `soak-dir-ls.txt` / `soak-procs.txt` | — | §2.1（无 soak 进程） |
| `jdspd.sh` | 2 834 B | §8.2 §10.3（看护重启机制） |
| `jdspcli-stats-now.txt` | 919 B | §9.4（现为 2.1.1） |
| `device-state-now.txt` | — | §9.4（01:24 升级前后哈希） |
| `modules-ls.txt` / `proc-misc.txt` / `dmesg.txt` / `ps.txt` / `logcat-buffers.txt` | — | 辅助/交叉校验 |
| `md5-oldstack.txt` / `module.prop` / `boot.log` / `boot-forensics.txt` | — | §1 旧栈身份 |

> 备注：`dropbox-print.txt`（15 MB）与 `dmesg.txt`（2.1 MB）为离线分析原始件，可按需删除；其余均为结论直接引用件，建议随本报告一并留存。
