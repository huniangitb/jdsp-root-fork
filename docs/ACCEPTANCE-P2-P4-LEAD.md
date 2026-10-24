# v2 后端派发 —— P2/P3/P4 真机验收记录（Lead 实测）

设备：Xiaomi xaga / Android 15 / SDK35 / KernelSU，2026-10-25 00:06–00:10
被测物：`backend/out/jdspd.dex`（jdspd 2.0.0），`/data/local/tmp/toneusage.dex`（可控 usage 的测试音）

判据来自 `docs/PLAN-v2.md` §4（P2/P3/P4 验收列）与 §6（可自动化验收）。

---

## 0. 测试手法：怎么造出"没有框架实例的会话"

`<postprocess>` 只对 **music 流**生效，所以用 **ALARM 流**（`USAGE_ALARM`）播放测试音，
得到的 session **没有任何 JamesDSP 实例**——这正是"后端自己挂载"要覆盖的场景，且不需要改 XML/重启。

```sh
adb shell su -c 'CLASSPATH=/data/local/tmp/toneusage.dex nohup app_process /system/bin ToneUsage 3 180 >/dev/null 2>&1 &'
```

---

## 1. P2：后端主动挂载（auto vs backend）

### 1.1 起会话（此时无实例）

```
$ adb shell 'dumpsys media.audio_flinger | sed -n "/Global session refs/,+5p"'
  session  cnt     pid    uid  name
      177    1   17096      0  root
$ adb shell 'dumpsys media.audio_flinger | grep -E "effects for session"'
    2 effects for session 0          ← 只有全局链上的 MiSound + DAP，177 没有任何效果
```

### 1.2 auto 模式：只接管框架实例，不主动创建

```
$ adb shell su -c 'CLASSPATH=/data/local/tmp/jdspd.dex nohup app_process /system/bin --nice-name=jdspd com.jdsp.backend.Daemon &'
$ adb shell 'dumpsys media.audio_flinger | grep -E "effects for session"'
    2 effects for session 0          ← 仍然没有为 177 创建（符合 auto 语义）
$ # TCP 控制通道查询
{"cmd":"sessions"} -> sessionId 177, attached=false, effectId=-1
```

### 1.3 切 backend 模式：后端自己 createEffect

```
{"cmd":"mode","mode":"backend"} -> {"ok":true,"mode":1,"modeName":"backend"}
（6 秒后）
$ adb shell 'dumpsys media.audio_flinger | grep -E "effects for session"'
    1 effects for session 177        ← 出现了
    2 effects for session 0
$ adb shell 'dumpsys media.audio_flinger | grep -A3 "effects for session 177"'
    1 effects for session 177
	In buffer    Out buffer                           Active tracks:
	0x77c265b000 0x77c29cc000 -> 0xb4000076a9c18220   1
	Effect ID 115:
```

jdspd 日志（`logcat -s jdspd`）：

```
I/jdspd: mode -> backend
I/jdspd: attach session=177 effectId=115 (dump said -1) enabled=false hasControl=true in 93732us
I/jdspd: applyAll session=177: 1 parameter(s) applied
I/jdspd: setEnabled session=177 -> true rc=0
I/jdspd: attached session=177 uid=0 pkg= (backend mode)
```

- `dump said -1` = 挂载前该 session 确实没有实例，`effectId=115` 是 jdspd 建出来的。
- 建链到启用共 **93 ms**（含首次 binder 建链），之后参数直通。
- **underrun 计数全程为 0**：`Normal mixer raw underrun counters: partial=0 empty=0`。

**结论：P2 通过**（后端可以自己创建并启用效果，不依赖 postprocess）。

---

## 2. P3：实时调参

### 2.1 setParam 直通

```
{"cmd":"setParam","session":177,"param":1500,"value":"cdccccbd00007042000070c1"}
  -> {"ok":true,"status":0}
```

HAL 侧证据（参数确实到了引擎）：

```
V EffectHandle: command(), cmdCode: 5, mHasControl: 1
V EffectModule: command, JamesDSP v4.01 cmdCode: 5
I JamesDSPState: state file updated: 7 record(s), 216 byte(s)
```

延迟（jdspd `stats`，含 202 次批量下发）：`avgLatencyUs=1130, maxLatencyUs=5119` → **平均 1.13 ms**，
远优于 PLAN-v2 的 "<20 ms 生效"目标。

### 2.2 getParam：**引擎不支持**（设计变更，见 §4）

修复前（00:10 实测）：

```
{"cmd":"getParam","session":177,"param":1500,"vsize":12}
  -> {"ok":false,"error":"getParam returned no value","code":-1001}
```

clean v6.7.2 引擎对 `EFFECT_CMD_GET_PARAM` 只实现固定查询号（19998/19999/20000/20001/20002/30000…），
其余参数一律回 `status=-EINVAL`（`jamesdsp.c` 的 default 分支）。**这是引擎能力限制，不是 jdspd 的 bug**。

修复后（daemon-core 已实装三层回退，见 §4.1）：

```
# 该会话此前 set 过 1500 = cdccccbd00007042000070c1
{"cmd":"getParam","session":177,"param":1500,"vsize":12}
  -> {"ok":true,"value":"cdccccbd00007042000070c1","vsize":12}     ← 与最后一次设置一致
# 改值后立刻读回
{"cmd":"setParam","session":177,"param":1500,"value":"cdccccbd0000704200004040"}
  -> {"ok":true,"status":0}
{"cmd":"getParam","session":177,"param":1500,"vsize":12}
  -> {"ok":true,"value":"cdccccbd0000704200004040","vsize":12}
```

`stats` 里会看到 `getParamCacheHits`（来自 daemon 内存缓存）与 `getParamEngineHits`（引擎真的回读了）；
clean 引擎下后者恒为 0，前者是常态。

---

## 3. P4：按应用策略（排除列表）

两个不同 uid 的播放会话同时存在：session 177（uid 0）与 session 185（uid 10123 = com.google.android.gms，用 `su 10123` 起）。

```
$ {"cmd":"sessions"}
  sessionId 177, uid 0,      pkg "",                       attached=true, effectId=115
  sessionId 185, uid 10123,  pkg "com.google.android.gms", attached=true, effectId=123

$ {"cmd":"policy","pkg":"com.google.android.gms","mode":2}
  -> {"ok":true,"pkg":"com.google.android.gms","mode":2}

（5 秒后）
$ {"cmd":"sessions"}
  sessionId 177, uid 0,     attached=true,  effectId=115, policyMode=0
  sessionId 185, uid 10123, attached=false, effectId=-1,  policyMode=2

$ adb shell 'dumpsys media.audio_flinger | grep -E "effects for session"'
    1 effects for session 177        ← 只剩 177，185 的实例已从链上移除
    2 effects for session 0
```

jdspd 日志：

```
I/jdspd: policy com.google.android.gms -> 2
I/jdspd: setEnabled session=185 -> false rc=0
I/jdspd: detach session=185 effectId=123
I/jdspd: detached session=185 pkg=com.google.android.gms (excluded by policy)
```

**结论：P4 通过**（排除的应用实时摘除，其它会话不受影响；uid→pkg 映射来自 /data/system/packages.list）。

---

## 4. 本轮发现的两个设计变更（已转给 daemon-core 修，**已实装并验证**）

| 项 | 现象 | 处理 | 状态 |
|---|---|---|---|
| `getParam` | 引擎只支持固定查询号，任意参数回 EINVAL | jdspd 侧维护 last-known-value 缓存 + ConfigStore 兜底，`get` 返回最后一次 set 的值（PLAN-v2 §6.2 的判据改为"与最后一次设置一致"） | ✅ 已实装（v2.0.0 之前合入） |
| ConfigStore 持久化 | `setParam` 已到引擎并写进 state.bin，但 jdspd 自己的 config.json 未同步更新 | setParam 成功后同步更新 ConfigStore（防抖落盘），保证重启后由 jdspd 回放 | ✅ 已实装（v2.0.0 之前合入） |

### 4.1 getParam 的三层回退（实现细节，与代码一一对应）

`CoreApi.getParam()` → `ParamBus.getParam()` → `EffectManager.getParam()`，取值顺序：

1. **引擎**：`AudioEffect.getParameter(paramId, reply)`。clean v6.7.2 引擎对任意参数回 `-EINVAL`，只有
   它实现的那几个固定查询号才有值。成功时返回引擎原样回填的 `reply`。
2. **daemon 内存缓存**：`ParamBus` 记录每一次**成功** `setParam` 的 (session, param) → value。
   引擎回读失败时用缓存应答（`stats.getParamCacheHits`）。
3. **ConfigStore**：会话还没有缓存（daemon 刚重启、还没重放过）时，用 `config.json` 里该会话
   生效的参数值（全局值 + 该 pkg 的 per-app 覆盖）应答。

三层都拿不到才回 `{"ok":false,"error":"getParam returned no value","code":-1001}`。

**语义边界（验收脚本要知道）**：

- 返回的是**最后一次经 jdspd 下发**的值，不是引擎内部的真值（引擎不支持回读）。
- 绕过 jdspd 直接改引擎（例如 App 自己的 AudioEffect 实例改了参数）**不会**反映到 `getParam`。
- daemon 重启后内存缓存清空，第 3 层（config.json）接管；因此"重启后 get 仍等于最后一次 set"
  依赖 setParam 已经把值写进 config.json（见 §4.2）。
- `vsize` 会被归一化：缺省 4、上限 4096、向上对齐到 4 字节。

### 4.2 ConfigStore 同步（实现细节）

`CoreApi.setParam()` 在引擎返回 0 之后：

- **按会话归属写**：会话能映射到 pkg → 写 `perAppParams[pkg][paramId]`；否则写 `globalParams[paramId]`。
- **不写分片参数**：12000/12001/8888/9999 及 10004/10006/10009/10010/25000-25003（卷积 IR / 字符串 EQ 的
  分片与提交号）和 >64 KiB 的值**只转发给引擎，不落 config.json**（落盘会把分片当成普通参数重放）。
- **200 ms 防抖落盘**：`ConfigStore.saveThrottled(200)`——单次 set 最多 200 ms 后落盘（tmp+fsync+rename，
  mode 0600），滑杆拖动不会每个事件都 fsync；被防抖挡下的部分由 watcher tick（≤1 s）补写。
  ⇒ **判据**：set 之后**最迟 1 秒**内 `/data/adb/jdspd/config.json` 里能看到新值。

`stats` 里有 `configPath`、`mode`/`modeName`、`globalParams`（数量）等字段，验收脚本可直接断言。

## 5. P2 正式判据：真正关掉 postprocess 之后（v2.0.0 包，00:23 实测）

前置：`sh jdsp_mode.sh set backend` + 临时禁用 XagaSoundBoostTest（见 §6）+ 重启。

```
$ adb shell 'stat -c "%i %n" /vendor/etc/audio_effects.xml /data/adb/modules/ainur_jamesdsp/system/vendor/etc/audio_effects.xml'
1502537 /vendor/etc/audio_effects.xml              ← inode 相同 = 挂载的是我们那份
1502537 /data/adb/modules/ainur_jamesdsp/system/vendor/etc/audio_effects.xml
$ adb shell 'grep -c "effect=\"jamesdsp\"" /vendor/etc/audio_effects.xml'
0                                                  ← postprocess 注入确实为 0
$ adb shell 'cat /data/adb/jdspd/mode; pidof jdspd'
backend
2410
```

播放音乐流（`ToneUsage 0`，USAGE_MEDIA → STREAM_MUSIC）：

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

**`dump said -1`** 是关键：框架**没有**为该会话创建实例（postprocess 已关），实例是 jdspd 自己建的；
建完即启用、参数已重放，播放无 underrun。

**结论：P2 正式判据通过** —— 关掉 postprocess 后，music 会话仍出声且有效果，且完全由后端派发。

验收后已恢复：`jdsp_mode.sh set auto` + 重新启用 XagaSoundBoostTest + 重启，
`module/accept-v2.sh --tone` **12/12 PASS**（见 §7）。

## 6. 共存约束：谁能赢 /vendor/etc/audio_effects.xml 的挂载

这台设备上 **XagaSoundBoostTest 模块也提供 `/vendor/etc/audio_effects.xml`**，并且它的副本会赢得挂载：

```
$ adb shell 'find /data/adb/modules -name "audio_effects.xml"'
/data/adb/modules/XagaSoundBoostTest/system/vendor/etc/audio_effects.xml      apply=1
/data/adb/modules/ainur_jamesdsp/system/vendor/etc/audio_effects.xml          apply=0（我们改成 backend 后）
$ adb shell 'stat -c %i /vendor/etc/audio_effects.xml'      → 1485135（= Xaga 的）
```

⇒ 只要 Xaga 模块在，**无论我们把模块副本改成什么，实际挂载的仍是它的文件**，backend 模式不生效。
`jdsp_mode.sh verify` 与 `boot.log` 都能检出这种不一致：

```
[mode] service mode=backend /vendor/etc/audio_effects.xml=1 /system/etc/audio_effects.xml=0 WARN mismatch: 已挂载的 xml 与 mode 不一致（需要重启才能生效）
```

**使用 backend 模式的前置条件**：让本模块赢得该文件的挂载（禁用/卸载其它提供同一文件的模块），
否则保持 auto（默认值，功能完整：框架挂载 + jdspd 实时调参）。

## 7. 最终交付状态（00:26 实测）

```
=== v2 module acceptance (mode=auto) ===
PASS  engine sha / jdspd process pid=2354 single / binder jdsp.service / apply=1 (auto) ×2
PASS  boot.log mode / jdspd.log health starts=1 / config.json mode / transport port=44927 token_len=64
PASS  state.bin mode / tone instance session=129 / instance owner
PASS=12 FAIL=0 SKIP=0

=== scripts/verify-v2.sh ===
PASS=10 FAIL=0
```

## 8. 尚未验证

1. 72 h 长稳（见 cli-harness 的 soak 脚本，本轮未跑满）。
2. 其它机型（c16 / c17）——本轮只有 xaga / Android 15 一台。
3. App 侧（P5）端到端补丁包的完整验收，见 docs/P5-APP-INTEGRATION.md。

## 9. 附：jdspd 日志跨重启累积的处理（daemon-core 复核，00:46）

模块的看护脚本 `module/src/jdspd.sh` 在每次开机时执行 `mv jdspd.log jdspd.log.prev`，用于避免
"starting jdspd" 行跨重启累积被误判成重启风暴。

**这个改法对 daemon 侧有副作用，已修**：

- `mv` 只改目录项，**不改变 inode**，daemon 已经打开的 fd 仍然指向那个被改名的 inode →
  继续 `write()` 会**写进 .prev 文件**，而新的 `jdspd.log` 根本不会被写（主机复现见
  `/home/bdy/work/jdsp-work/RotateSemantics.java`：rename 之后旧 fd 的写入全部落进 `jdspd.log.prev`，
  重新 open 之后新写入才回到 `jdspd.log`）。
- 修法（`backend/src/com/jdsp/backend/Log.java`）：每次写之前校验 **inode/dev**
  （`android.system.Os.stat`），发现路径上的文件已经不是自己打开的那个 inode 就 **close + reopen**；
  拿不到 stat 时退化为"文件比已写字节数还短 = 被替换/截断"的兜底判断。
  ⇒ daemon 始终写当前的 `jdspd.log`，被轮转走的内容留在 `.prev` 里，不会再分叉。
- daemon 自己的 1 MiB 轮转（`jdspd.log` → `jdspd.log.1`）保持不变，轮转后也会刷新 inode 记录。

**结论**：模块侧 `jdspd_rotate_log` 的改法**可以保留**（daemon 侧已能自愈），无需改成 daemon 侧 reopen 协议
（daemon 已经是每次写都自检，不需要外部信号）。
