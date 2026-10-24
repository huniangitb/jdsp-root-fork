# MODULE-INTEGRATION — v2 模块集成

- 日期：2026-10-25
- 范围：task-4（模块内置 dex、jdspd 开机自启与看护、目录/sepolicy、mode 挂钩、打包 v2.0.0）
- 状态：**脚本与挂钩已落盘并自测通过**（见 §0）；**只差 `backend/out/*.dex` 拷贝 + 打包 + 真机验收**
- 依赖：`backend/out/jdspd.dex`、`backend/out/jdspcli.dex`（daemon-core / cli-harness 产出）
- 关联：`docs/MODE-SWITCH.md`（mode 方案）、`docs/ACCEPTANCE-P0.md`（基线验收）

## 0. 落盘与自测状态（2026-10-25 00:10）

| 文件 | 状态 | 自测 |
|---|---|---|
| `module/src/jdsp_mode.sh`（新） | ✅ 落盘 | 主机 + 真机（toybox）双测：backend→apply 1→0、auto→0→1、**往返逐字节相同**；config.json 读写后 JSON 仍合法 |
| `module/src/jdspd.sh`（新） | ✅ 落盘 | 真机：`normalize` 把 state.bin **600→660**；dex 缺失时 `FATAL` 秒退；`/data/adb/jdspd` 自动 0700 |
| `module/src/service.sh` | ✅ 2.5 启看护 / 2.6 mode 校验 | `sh -n` OK |
| `module/src/post-fs-data.sh` | ✅ `jdsp_mode_apply post-fs-data` | `sh -n` OK |
| `module/src/common/install.sh` | ✅ 末尾 `jdsp_mode_apply install` | 沙箱模拟安装钩子两种 mode 都正确 |
| `module/src/uninstall.sh` | ✅ 停进程 + `rm -rf /data/adb/jdspd` | `sh -n` OK；不依赖 `pkill`（Android 不一定有） |
| `module/src/module.prop` | ✅ v2.0.0-fork / versionCode 200 | 读回确认 |
| `backend/*.dex` + zip | ⏳ 等 dex | — |

### 0.1 mode 的真值来源（与 daemon-core 的契约）

daemon 的 ConfigStore 是 `/data/adb/jdspd/config.json`，里面有 `"mode":0|1, "modeName":"auto"|"backend"`。
模块脚本在 **daemon 起来之前**（install / post-fs-data）就要知道 mode，所以：

1. `jdsp_mode_get` **优先**从 config.json 用 sed 提取 `modeName`（其次 `mode` 0/1）；
   文件不存在 → 退回裸文本 `/data/adb/jdspd/mode` → 都没有则 `auto`。
2. `jdsp_mode_set` 同时写 config.json 的两个字段 + 文本文件，再改 XML 副本
   （实测 sed 改写后 `json.load` 仍然合法）。
3. **需要 daemon-core 配合**：AIDL `setMode` 成功后 exec
   `sh /data/adb/modules/ainur_jamesdsp/jdsp_mode.sh apply daemon` 并返回 `rebootRequired=true`，
   保证"App 改了配置但 XML 没跟上"不会发生（否则 backend 配置 + auto 注入 = 两个实例）。
4. 看护脚本**不给 daemon 传 `--mode`**（daemon 的 `--help` 没有这个参数，实测传了也被忽略），
   mode 全部走 config.json。

---

## 1. sepolicy 结论：**一条都不用加**（已实测）

### 1.0 最终传输：loopback TCP + token 文件（Lead 拍板，**不再用 unix socket**）

| 项 | 值（真机实测） |
|---|---|
| 监听 | `127.0.0.1:<port>`，非 loopback 连接直接断开 |
| 端口文件 | `/data/data/james.dsp/files/jdsp.port`（内容 `<port>\n`） |
| token 文件 | `/data/data/james.dsp/files/jdsp.token`（64 hex + `\n`） |
| 属主/权限 | `-r-------- 1 10322 10322`（= App uid u0_a322，0400，daemon 写完 chown） |
| 握手 | 首行 `{"op":"hello","token":"<hex>"}`，之后 JSON lines |
| App 未安装/未启动 | 目录不存在 → 跳过 TCP（`stats.transport.tcp.listening=false`），daemon 其它功能正常 |

- binder `jdsp.service` 仍然注册（root/CLI 用）；untrusted_app 连不上 binder 是预期。
- 停止：`Daemon --stop`（读 port/token → TCP hello → `{"cmd":"shutdown","confirm":true}`）
  或 `/data/adb/jdspd/stop` 文件；**`kill -TERM` 不会执行句柄释放逻辑**（ART 不跑 shutdown hook），只作兜底。

### 1.1 （历史记录）曾经考虑的 unix socket 方案

早期按 Lead 决定试过 `/data/data/james.dsp/files/jdsp.sock`（类型 `app_data_file`），
SELinux 侧一条规则都不用加；最终因传输方案改成 TCP+token 而未采用。
下面 §1.2–§1.4 的实测结论保留，作为"为什么不需要加规则"和"DAC 坑"的排障记录。

### 1.2 实测 1：root（`u:r:ksu:s0`）能在 app_data_file 下 bind 一个 socket

用一个 2 KB 的探针 dex（`android.net.LocalSocket` + `Namespace.FILESYSTEM`）在真机上跑：

```console
$ adb shell su -c 'CLASSPATH=/data/local/tmp/sockprobe.dex app_process /system/bin SockProbe \
      /data/data/james.dsp/files/.jdspd_sock_probe'
BIND OK /data/data/james.dsp/files/.jdspd_sock_probe
attrs={mode=49645, uid=0, gid=0, size=0}          # 0o140755 = socket | 0755
owner=root group=root perms=[OTHERS_EXECUTE, OWNER_EXECUTE, GROUP_EXECUTE,
        GROUP_READ, OWNER_WRITE, OTHERS_READ, OWNER_READ]
cleanup delete=true
```

⇒ 创建 socket 不需要任何 sepolicy 规则（`su`/`ksu` 域本来就宽），`dmesg` 无 AVC。

### 1.3 实测 2：平台策略本来就允许 untrusted_app 用 app_data_file 的 socket

直接查设备上的 SELinux CIL（比 `sesearch` 更可靠，且不需要额外工具）：

```console
$ adb shell su -c 'grep -oE "\(allow untrusted_app[a-z_]* app_data_file[a-z_]* \(sock_file[^)]*\)[^)]*\)" \
      /system/etc/selinux/plat_sepolicy.cil'
(allow untrusted_app_all app_data_file (sock_file (ioctl read write create getattr setattr lock append map
                                                  unlink rename open watch watch_reads)))
```

⇒ App 连自己 `files/` 下的 socket **不需要加规则**。`ksud sepolicy check` 那句候选规则可以彻底丢掉。

### 1.4 ⚠ 但有一个 DAC（权限位）坑，必须在 daemon 里处理

root bind 出来的 socket 文件是 **root:root 0755**（见 §1.2 的 `attrs`）。
unix socket 的 `connect()` 要求对 socket 文件**有写权限** ⇒ App（uid 10322）连不上，会拿到 `EACCES`，
而且**看起来像 SELinux 问题其实不是**（不会有 AVC）。

**daemon 侧要求（已同步给 daemon-core）**：`bind()` 成功后立刻

```java
// 1) 取 App uid/gid：/data/data/james.dsp 的 owner（root 可直接 stat）
// 2) chown <app_uid>:<app_gid> / chmod 0600 —— 只有该 App 和 root 能连
```

（CLI 走 su/root，不受影响；如果取不到 App uid，退化为 `chmod 0666`，并靠 `SO_PEERCRED`/
`LocalSocket.getPeerCredentials()` 校验对端 uid 必须是 james.dsp 的 uid。）

---

## 2. 目录与文件布局

```
/data/adb/modules/ainur_jamesdsp/
├── backend/
│   ├── jdspd.dex            0644  # 从 backend/out/jdspd.dex 拷（构建产物，gitignore）
│   └── jdspcli.dex          0644
├── jdspd.sh                 0755  # 看护循环（新）
├── jdsp_mode.sh             0755  # auto|backend（新，见 MODE-SWITCH.md）
├── sepolicy.rule                  # +mtk_hal_audio 的 file/dir/watch 规则
├── service.sh                     # +jdspd 启动 +mode 校验
├── post-fs-data.sh                # +jdsp_mode_apply（自愈）
├── uninstall.sh                   # +清理 /data/adb/jdspd
└── （其余 v6.7.2 原文件不动）

/data/adb/jdspd/             0700 root:root   # jdspd 运行目录（新）
├── mode                     0600/0640        # "auto"|"backend"（脚本写 0600，daemon 也写一份兜底）
├── config.json              0600             # ConfigStore 权威配置（mode/params/policies）
├── jdspd.log                0600             # 看护循环 + daemon 日志
├── jdspd.lock               0600             # 单实例锁（FileChannel.tryLock）
└── stop                     出现即优雅停机（daemon 每 1s 检查，处理后自删）

/data/data/james.dsp/files/  由 App 自己拥有    # **只检查不创建**（module 不 mkdir）
├── jdsp.port                0400 app_uid     # daemon 写：端口号（loopback TCP）
└── jdsp.token               0400 app_uid     # daemon 写：64 hex
/data/vendor/audio/jamesdsp_state.bin          # 引擎状态（1041:1005，开机归一化 0660）
```

注意：模块**不创建** `/data/data/james.dsp/files`——它由 App 首次启动时自己建。
daemon 只在"目录已存在"时写 `jdsp.port`/`jdsp.token` 并监听 loopback TCP；目录不存在就跳过 TCP
（`stats.transport.tcp.listening=false`），binder/其它功能照常。

---

## 3. `module/src/jdspd.sh`（草案全文）

```sh
#!/system/bin/sh
# jdspd 看护循环：等 AudioFlinger → 起 daemon → 退出 2s 后重启 → 每分钟归一化 state.bin。
# 由 service.sh 以 nohup 方式启动；本脚本自身在前台跑循环。
MODPATH=${0%/*}
RUNDIR=/data/adb/jdspd
DEX=$MODPATH/backend/jdspd.dex
LOG=$RUNDIR/jdspd.log
STATE=/data/vendor/audio/jamesdsp_state.bin

mkdir -p "$RUNDIR" 2>/dev/null
chmod 0700 "$RUNDIR" 2>/dev/null
# 首次运行：把打包时的默认 mode 落成运行期配置
if [ ! -f "$RUNDIR/mode" ] && [ -f "$MODPATH/backend/mode.default" ]; then
	cp -f "$MODPATH/backend/mode.default" "$RUNDIR/mode" 2>/dev/null
	chmod 0600 "$RUNDIR/mode" 2>/dev/null
fi

log() { echo "[$(date '+%m-%d %H:%M:%S')] $*" >> "$LOG"; }

# HAL 每次重写状态文件都是 0600（.tmp+rename+umask），开机只归一化一次不够
normalize_state() {
	[ -f "$STATE" ] || return 0
	cur=$(stat -c %a "$STATE" 2>/dev/null)
	[ -z "$cur" ] && return 0
	[ "$cur" = "660" ] && return 0
	if chmod 0660 "$STATE" 2>/dev/null; then
		log "state.bin chmod $cur -> 660"
	fi
}

wait_audio_flinger() {
	i=0
	while [ $i -lt 120 ]; do
		service check media.audio_flinger 2>/dev/null | grep -q "found" && return 0
		sleep 1
		i=$((i + 1))
	done
	return 1
}

main() {
	if [ ! -f "$DEX" ]; then
		log "FATAL dex missing: $DEX"
		return 1
	fi
	wait_audio_flinger || log "WARN media.audio_flinger not found after 120s, starting anyway"
	log "jdspd.sh supervisor start (mode=$(cat $RUNDIR/mode 2>/dev/null || echo auto))"

	restarts=0
	while :; do
		restarts=$((restarts + 1))
		log "starting jdspd (attempt $restarts)"
		CLASSPATH="$DEX" app_process /system/bin --nice-name=jdspd com.jdsp.backend.Daemon \
			>> "$LOG" 2>&1 &
		pid=$!
		# 等 daemon 退出；期间每分钟归一化一次 state.bin
		while kill -0 "$pid" 2>/dev/null; do
			sleep 60
			normalize_state
		done
		wait "$pid"
		rc=$?
		log "jdspd exited rc=$rc, restarting in 2s"
		normalize_state
		sleep 2
	done
}

main
```

设计要点：

- **看护在脚本侧**（不是 `while true; do app_process; done` 一行流），这样 60 s 的 state.bin 归一化
  不会因为 daemon 前台阻塞而跑不到。
- daemon 退出 **2 s** 后重启（任务要求），重启次数进日志，便于 `jdspcli stats`/排障。
- 不依赖 jdspd 是否健康：脚本自身只做 `kill -0` 轮询，最坏情况是每 62 s 重试一次，不会拖死开机。
- 日志单文件 `/data/adb/jdspd/jdspd.log`（0600）；不做 rotate，由 `service.sh` 每次开机 truncate 到 1 MB 以内。

### 3.1 ⚠ 安装期会剥掉所有 `#` 行（含 shebang）——新脚本必须不依赖注释

`common/functions.sh` 在安装尾声对模块内**所有** `*.sh` / `*.prop` / `*.rule` 执行：

```sh
sed -i -e "/^#/d" -e "/^ *$/d" $i
```

⇒ 装到设备上的脚本**没有注释、没有空行，连 `#!/system/bin/sh` 都被删掉**（实测：仓库 `jdsp_state.sh`
7009 B → 设备上 4854 B；`sepolicy.rule` 1878 B → 651 B）。因此：

- **新脚本一律用 `sh xxx.sh` 或 `.`(source) 调用，不依赖 shebang 可执行位**（`jdspd.sh` 由
  `service.sh` 以 `nohup sh … &` 启动，`jdsp_mode.sh` 一律 source）；
- 脚本逻辑不能靠"注释掉的代码"来关功能（会被删光后留下语法错误的风险）；
- 行首 `#` 不能出现在 here-doc/字符串里（会被当注释删掉）；
- 代码里的日志格式（上面草案的 `log()`）不受影响——`#` 不在行首。

## 4. `service.sh` 改动（在现有第 2 步之后插入）

```sh
# 2.5) jdspd 后端守护（看护循环）
if [ -f "$MODPATH/jdspd.sh" ]; then
	[ -f /data/adb/jdspd/jdspd.log ] && \
		[ "$(stat -c %s /data/adb/jdspd/jdspd.log 2>/dev/null || echo 0)" -gt 1048576 ] && \
		: > /data/adb/jdspd/jdspd.log
	nohup sh "$MODPATH/jdspd.sh" >/dev/null 2>&1 &
fi

# 2.6) mode 校验（唯一能发现"以为切了其实没切"的地方）
[ -f "$MODPATH/jdsp_mode.sh" ] && . "$MODPATH/jdsp_mode.sh" && jdsp_mode_verify service
```

`jdsp_mode_verify` 输出示例（写进 boot.log）：

```
[mode] service mode=backend  mounted_apply: /vendor/etc/audio_effects.xml=0 /system/etc/audio_effects.xml=0  OK
[mode] service mode=auto     mounted_apply: /vendor/etc/audio_effects.xml=1 /system/etc/audio_effects.xml=1  OK
[mode] service mode=backend  mounted_apply: /vendor/etc/audio_effects.xml=1  WARN mismatch, reboot required
```

## 5. `uninstall.sh` 追加

```sh
# 1) 先停看护循环（否则 2s/30s 后它会把 daemon 再拉起来）
for p in /proc/[0-9]*; do
    case "$(tr '\0' ' ' < "$p/cmdline" 2>/dev/null)" in *jdspd.sh*) kill "${p#/proc/}" ;; esac
done
# 2) 优雅停 daemon：--stop 会释放句柄、删 port/token（kill -TERM 不会，只作兜底）
CLASSPATH=/data/adb/modules/ainur_jamesdsp/backend/jdspd.dex \
    app_process /system/bin com.jdsp.backend.Daemon --stop
# 3) stop 文件兜底，最后才 kill
: > /data/adb/jdspd/stop; sleep 1
for p in $(pidof jdspd 2>/dev/null); do kill "$p" 2>/dev/null; done
rm -rf /data/adb/jdspd
```

保留 `/data/vendor/audio/jamesdsp_state.bin`（属主 1041，引擎状态；卸载模块后引擎不再加载，留着无害，
下次重装仍是用户最后一次设置）。

## 6. 打包

已脚本化（详见 `docs/BUILD-v2.md` §1）：

```sh
cd /home/bdy/work/jdsp-repo
backend/build.sh          # -> backend/out/{jdspd,jdspcli}.dex
module/package.sh         # 拷 dex + 打 dist/JDSP-fork-v2.0.0.zip + 16 项结构/内容自检 + 打印 sha256
```

`module/package.sh` 打完会删掉 `module/src/backend/`，避免 dex 二进制进 git（`--keep` 保留、`--check` 只自检）。

包内校验清单：

| 检查 | 期望 |
|---|---|
| `unzip -l dist/JDSP-fork-v2.0.0.zip backend/` | `jdspd.dex`、`jdspcli.dex` 都在 |
| `unzip -p … module.prop` | `versionCode=200` |
| `unzip -p … sepolicy.rule \| grep -c mtk_hal_audio` | 4（2 条 file/dir + 2 条 watch） |
| `unzip -p … module/src/jdspd.sh` 存在 | `jdspd.sh`、`jdsp_mode.sh` |
| 大小 | 约 33.6 MB + dex（jdspd ~200-400 KB） |

## 7. 装完后的验收（v2.0.0）

| # | 检查 | 期望 |
|---|---|---|
| 1 | `sha256sum /vendor/lib64/soundfx/libjamesdsp.so` | `b6e1fc1f…`（引擎不变） |
| 2 | `ps -A \| grep jdspd` | 有 `--nice-name=jdspd` 进程 |
| 3 | `service check jdsp.service\|jdsp.*` / `service list \| grep jdsp` | daemon 注册的 binder 服务可见 |
| 4 | `cat /data/adb/modules/ainur_jamesdsp/logs/mount_check.log` | apply=1（auto）或 0（backend）与 mode 一致 |
| 5 | `cat /data/adb/jdspd/jdspd.log` | 有 "supervisor start"、无 restart 风暴 |
| 6 | `cat /data/adb/modules/ainur_jamesdsp/boot.log` | `[mode] …OK`；`state.bin chmod … -> 660` |
| 7 | 杀 daemon：`kill <pid>` | 2 s 内重启，日志有 `exited rc=… restarting in 2s` |
| 8 | `ls -ln /data/data/james.dsp/files/jdsp.{port,token}` | 存在，owner = App uid(10322)，mode **0400**；port 内容是端口号，token 是 64 hex（**不是 unix socket**） |
| 9 | 播 tone + `jdspcli sessions` | `attached=1`（auto 模式下 attach 的是框架实例） |
| 10 | state.bin | `jdspcli set` 后 mtime/size 变化（回归 P0 判据） |
| **11** | **mode 被 daemon 读回**（Lead 指出的最危险失败模式）：`config.json` 是 `modeName=backend` 时重启 | daemon 日志 `config loaded: ... mode=1`；config.json **没被改回** mode=0 |
| **12** | **P2 正式判据（优先验）**：`mode=backend` + 重启 | `/vendor/etc/audio_effects.xml` 的 `apply` 计数 **0**，但播 tone 后仍有 JamesDSP 实例且 `1 Clients: Pid <jdspd>`、`jdspcli sessions` `attached=1` → "关掉 postprocess 也能出声且有效果" |

> **#11 不成立 = 完全没效果**：模块按 backend 把 postprocess 关了，daemon 却按 auto 以为框架会挂 →
> 谁都不创建实例。所以每次改 mode 都要同时确认"模块侧读到的 mode"和"daemon 读到的 mode"是同一个值。

## 8. 待别人交付

| 依赖 | 谁 | 用在哪 |
|---|---|---|
| `backend/out/jdspd.dex`（`com.jdsp.backend.Daemon`，含 AIDL 服务名 `jdsp.service`） | daemon-core | `backend/jdspd.dex` |
| `backend/out/jdspcli.dex`（`com.jdsp.cli.Main`） | cli-harness | `backend/jdspcli.dex` + 验收脚本 |
| daemon 侧 socket 的 chown/chmod + peer 校验（§1.4） | daemon-core | App 能连上 |
| `setMode` 的返回值里带 `reboot_required` | daemon-core | App UI 提示"重启生效" |
