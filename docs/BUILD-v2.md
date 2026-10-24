# BUILD-v2 — v2.0.0 模块打包与部署

- 日期：2026-10-25
- 产物：`dist/JDSP-fork-v2.0.0.zip`（KernelSU / Magisk 模块包，v2.0.0-fork / versionCode 200）
- 前置：`docs/MODE-SWITCH.md`（mode 方案）、`docs/MODULE-INTEGRATION.md`（集成设计）、`docs/ACCEPTANCE-P0.md`（基线验收）

---

## 1. 一条命令构建

```sh
cd /home/bdy/work/jdsp-repo
backend/build.sh                 # AIDL → javac → d8，产出 backend/out/{jdspd,jdspcli}.dex
module/package.sh                # 拷 dex → 打 zip → 结构/内容自检 → 打印 sha256
```

`module/package.sh` 做的事：

1. 把 `backend/out/{jdspd,jdspcli}.dex` 拷到 `module/src/backend/`（0644），打印各自 sha256；
2. `zip -r -X dist/JDSP-fork-v2.0.0.zip`（工作树内容决定归档，不写额外属性）；
3. **结构自检**：module.prop / customize.sh / update-binary / functions.sh / install.sh /
   jdsp_postprocess.sh / service.sh / post-fs-data.sh / uninstall.sh / sepolicy.rule /
   jdsp_mode.sh / jdspd.sh / backend/jdspd.dex / backend/jdspcli.dex / arm64 lib / 内置 APK；
4. **内容自检**：versionCode=200、version=v2.0.0-fork、sepolicy 里带 4 条 mtk_hal_audio 规则、
   jdspd.sh 带 state.bin 归一化、jdsp_mode.sh 读 config.json、service.sh 挂了 jdspd.sh；
5. 打完删掉 `module/src/backend/`（避免 dex 二进制进 git；`--keep` 可保留，`--check` 只自检）。

任一项失败 `exit 1`，不会产出半成品包。

## 2. 产物

| 项 | 值 |
|---|---|
| 文件 | `dist/JDSP-fork-v2.0.0.zip` |
| 大小 | 33,703,634 B |
| sha256 | `25fd539c92f1df17f4da14c2e39b21179c5807b4429c38a97d3a88de3ff5aed8` |
| `backend/jdspd.dex` | 99,200 B / `d43d1d0441c349032609f0a0e7eeda5dc098be6fc93edb29c060c750b9a8b498` |
| `backend/jdspcli.dex` | 62,208 B / `4f0f5a708b1a99bb31d3729b5805627a9f10bb677b649588d92cf1f742423432` |
| 构建时间 / 环境 | 2026-10-25 00:27 CST（重新打包：含日志轮转 + 挂载归属检测）/ xaga A15 SDK35 / build-tools 37.0.0 + cmdline-tools d8 / JDK 21 |
| 装机验证 | Lead 装完重启后 `module/accept-v2.sh --tone` **12/12 PASS**；backend 判据见 `docs/ACCEPTANCE-V2.md` |
| arm64 引擎 `libjamesdsp.so` | `b6e1fc1fdc67b0c7bd73b083e92238eb29b4d0c66f7c02e5b476ad78c9fbf700`（与 P0 基线一致，v2 不动引擎） |

## 3. 与 v6.7.2 基线的差异（全部为新增，不改引擎）

| 变化 | 文件 |
|---|---|
| 新增 jdspd 看护循环（等 AudioFlinger → daemon → 2s 重启 → 每 60s 归一化 state.bin） | `jdspd.sh`（新） |
| 新增 mode 开关实现（get/set/apply/verify，config.json 为真值） | `jdsp_mode.sh`（新） |
| service.sh：2.5 启看护、2.6 mode 校验 | `service.sh` |
| post-fs-data.sh：`jdsp_mode_apply`（自愈，下次开机生效） | `post-fs-data.sh` |
| install.sh：安装末尾 `jdsp_mode_apply install`（升级不把 backend 切回 auto） | `common/install.sh` |
| uninstall.sh：停 daemon/看护 + `rm -rf /data/adb/jdspd` | `uninstall.sh` |
| sepolicy：+4 条 `mtk_hal_audio`（dir/file/watch） | `sepolicy.rule` |
| versionCode 112 → 200 | `module.prop` |
| 内置 `backend/{jdspd,jdspcli}.dex` | 新目录 |
| **日志轮转**：`jdspd.sh` 每次开机把 `jdspd.log` 轮成 `jdspd.log.prev`（`jdspd_rotate_log`），否则跨重启累积的 `starting jdspd` 行会被误判成"重启风暴" | `jdspd.sh` |
| **挂载归属检测**：`jdsp_mode.sh verify` 增加 inode 对比，检出"另一个模块赢了 `/vendor/etc/audio_effects.xml` 的挂载"（backend 模式不生效的根因，见 `docs/MODE-SWITCH.md` §6） | `jdsp_mode.sh` + `module/accept-v2.sh` |

## 4. 安装与验收

```sh
export PATH=$PATH:/home/bdy/Android/Sdk/platform-tools
adb -s LNNZXG8TPFHU69AI push dist/JDSP-fork-v2.0.0.zip /data/local/tmp/
adb -s LNNZXG8TPFHU69AI shell su -c '/data/adb/ksud module install /data/local/tmp/JDSP-fork-v2.0.0.zip'
adb -s LNNZXG8TPFHU69AI reboot && adb -s LNNZXG8TPFHU69AI wait-for-device
# 等 sys.boot_completed=1，再等 30s（service.sh 里有 sleep 8 + 看护脚本启动）
```

验收清单（12 条，见 `docs/MODULE-INTEGRATION.md` §7）：引擎 sha 不变、jdspd 进程在、binder 服务在、
apply 数量与 mode 一致、日志无 restart 风暴、boot.log 有 mode OK 与 state.bin chmod、杀 daemon 2s 内重启、
socket 存在且 owner=App uid、tone 下 jdspcli sessions attached=1、state.bin 可写，新增两条：

- **#11 mode 必须被 daemon 读回（Lead 最担心的失败模式）**：
  `/data/adb/jdspd/config.json` 里 `modeName=backend` 时，重启后 daemon **不能**把它当 auto；
  判据 = daemon 日志有 `config loaded: ... mode=1`（或 `modeName=backend`）、config.json **没有被改回** mode=0、
  且 `dumpsys media.audio_flinger` 里 JamesDSP 实例的 `1 Clients: Pid <jdspd 的 pid>`（说明是 daemon 建的）。
  这一条不成立就等于"模块按 backend 关掉 postprocess + daemon 按 auto 不创建" → **完全没有效果**。
- **#12 mode=backend 关掉 postprocess 后仍然出声且有实例**（P2 正式判据，优先验）：
  在 `mode=backend` + 重启后，`/vendor/etc/audio_effects.xml` 的 `apply` 计数必须为 0，
  播 tone 后仍能看到 JamesDSP 实例（且 client 是 jdspd），`jdspcli sessions` 显示 `attached=1`。

## 5. 方法学：本轮踩过的 4 个坑（后人也别再踩）

### 5.1 `ksud sepolicy check` 是**假验证**，别拿它当证据

```console
$ su -c '/data/adb/ksud sepolicy check "allow foo_bar_baz nosuchtype file { read }"'
（无输出）rc=0                     ← 不存在的 type 也照样通过
```

真验证是 `ksud sepolicy apply <file>`：它会逐条打印解析后的 `NormalPerm { ... }`，未知 type/perm 会报错。
本轮最终用 `apply` 验证整份 `sepolicy.rule`（12 条全部被接受，rc=0）。验证完可以重启让运行时补丁失效。

### 5.2 安装器会删掉所有 `#` 行（**含 shebang**）

`common/functions.sh` 在安装尾声对模块内所有 `*.sh/*.prop/*.rule` 执行
`sed -i -e "/^#/d" -e "/^ *$/d"`。实测：仓库 `jdsp_state.sh` 7009 B → 设备上 4854 B；
`sepolicy.rule` 1878 B → 设备上 651 B。

⇒ 新脚本必须用 `sh xxx.sh` 或 `.`(source) 调用，不能靠 shebang/可执行位；不能靠注释关功能；
行首 `#` 不能出现在字符串/here-doc 里。已按此约束写 `jdspd.sh` / `jdsp_mode.sh`，
并做过"strip 后 `sh -n` + 功能测试"（239→194 行仍工作，往返逐字节一致）。

### 5.3 hybrid_mount 的挂载是 **ro 快照**，运行期改 XML 无效

`/vendor/etc/audio_effects.xml` 是独立 `ro` mount，inode 与模块副本不同（1485135 vs 1500400）：
metamount 阶段把模块文件拷进分区镜像再挂。加上 audioserver 只在启动时读一次 XML ⇒
**切换 mode 必须重启**，杀 audioserver 也没用。详见 `docs/MODE-SWITCH.md`。

### 5.4 App↔daemon 传输：SELinux 不用加规则，坑在 **DAC**

- 早期方案（unix socket 在 `app_data_file`）：SELinux **一条规则都不用加**（平台已给
  `untrusted_app_all app_data_file sock_file { create read write ... }`），但 root bind 出来的 socket 是
  `root:root 0755`，`connect()` 需要对 socket 有**写**权限 ⇒ App 拿到 EACCES 且**没有 AVC**（极易误判）。
- **最终方案（Lead 拍板）**：loopback TCP + token 文件，
  `/data/data/james.dsp/files/jdsp.port` 与 `jdsp.token`（daemon 写完 chown 到 App uid、**0400**）。
  这里没有 socket 文件权限问题，授权靠 token（64 hex，首行 hello 握手）。
- 教训（对两种方案都成立）：**App 连不上先怀疑 DAC/文件属主，再怀疑 SELinux**；`dmesg` 没有 AVC ≠ 没问题。

### 5.5 daemon 没有单实例保护 → 多实例抢句柄（Lead 实测）

`app_process` 起的进程**没有天然互斥**：同时跑 3 个 `com.jdsp.backend.Daemon` 时，各自都会
SessionWatcher 轮询 + 各自 attach 同一批 session，现象是**参数被反复覆盖、日志刷屏、句柄互相顶掉**
（因为一个 session 上同 uuid 的 effect 句柄语义是"返回既有实例"，多进程抢到的是同一个实例的控制权）。

- **修复**：daemon 启动时用 `FileChannel.tryLock` 对 `/data/adb/jdspd/jdspd.lock` 加**排他锁**，
  抢不到就打印 `another instance is running` 并**退出 0**（不是崩溃，让看护循环别再重启它）；
- **现状**：daemon-core 已实现 `/data/adb/jdspd/jdspd.lock`（`FileChannel.tryLock`），
  第二个实例打印 `another jdspd is running (pid=...), exiting` 并 **exit 0**；
- **看护循环侧的配合（已落盘）**：`jdspd.sh` 区分退出码——`rc=0` 视为"正常退出/已有实例"退避 **30 s**，
  `rc!=0` 视为崩溃按任务要求 **2 s** 重启，两条路径都写日志；
- **手工排障**：`ps -A | grep jdspd` 只应有一个；有多个先全杀再起（`kill $(pidof jdspd)`）。

## 6. 清理与"首启默认值"语义

打包/重启前做一次干净化（**测试残留不能变成用户默认值**）：

```sh
# 1) 杀掉手工起的 daemon / selftest（否则会和开机看护抢 jdsp.service 注册名）
su -c 'kill $(pidof jdspd) 2>/dev/null; am force-stop com.jdsp.selftest'
# 2) 删引擎状态文件：重启后引擎从内置默认值开始，第一次改参数时重新写
su -c 'rm -f /data/vendor/audio/jamesdsp_state.bin'
# 3) 删 daemon 运行配置（含测试参数与测试 mode）：daemon 首启会重新生成默认 config.json
su -c 'rm -rf /data/adb/jdspd'
```

⇒ **首启状态**：`mode=auto`（= v6.7.2 行为，框架 postprocess 自动挂载）、参数为引擎内置默认值、
`/data/adb/jdspd` 由 `jdspd.sh` 重新 mkdir 0700。

## 7. 回退

| 场景 | 做法 |
|---|---|
| 只退回 auto | `sh /data/adb/modules/ainur_jamesdsp/jdsp_mode.sh set auto` → 重启（或删 `/data/adb/jdspd/config.json` 里的 mode → 缺省 auto） |
| 回到 v6.7.2 基线 | `ksud module install dist/JDSP-fork-v6.7.2.zip` → 重启（P0 验收过的那个包） |
| 完全停用后端 | 删模块或 `touch /data/adb/modules/ainur_jamesdsp/disable` → 重启 |

## 8. 已知遗留

1. `watch` 规则（2 条）本轮首次随包发布，**重启后要确认 `JamesDSPState: cannot watch` 是否消失**；
   消失则转正，仍出现说明不是 SELinux 权限问题（可能是 inotify 其它限制）。
2. state.bin 每次被 HAL 重写会变回 0600，靠 `jdspd.sh` 每分钟归一化（已实现，实测 600→660）。
3. `/vendor/etc/audio_effects.xml.jdsp.bak` 是 6.7.3~6.7.6 实验版残留在**别的模块**目录里的文件，
   可以删（见 `docs/MODE-SWITCH.md` §5）。
