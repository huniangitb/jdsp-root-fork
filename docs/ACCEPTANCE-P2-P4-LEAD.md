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

```
{"cmd":"getParam","session":177,"param":1500,"vsize":12}
  -> {"ok":false,"error":"getParam returned no value","code":-1001}
```

clean v6.7.2 引擎对 `EFFECT_CMD_GET_PARAM` 只实现固定查询号（19998/19999/20000/20001/20002/30000…），
其余参数一律回 `status=-EINVAL`（`jamesdsp.c` 的 default 分支）。**这是引擎能力限制，不是 jdspd 的 bug**。

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

## 4. 本轮发现的两个设计变更（已转给 daemon-core 修）

| 项 | 现象 | 处理 |
|---|---|---|
| `getParam` | 引擎只支持固定查询号，任意参数回 EINVAL | jdspd 侧维护 last-known-value 缓存 + ConfigStore 兜底，`get` 返回最后一次 set 的值（PLAN-v2 §6.2 的判据改为"与最后一次设置一致"） |
| ConfigStore 持久化 | `setParam` 已到引擎并写进 state.bin，但 jdspd 自己的 config.json 未同步更新 | setParam 成功后同步更新 ConfigStore（防抖落盘），保证重启后由 jdspd 回放 |

## 5. 尚未验证（需重启或打包后才能做）

1. **真正关掉 postprocess**（`mode=backend` 写进模块 + 重启）后，music 会话仍有效果——本轮只验证了"没有框架实例时后端能建"，
   "framework 完全不建"要等 v2.0.0 包（module-p0 的 jdsp_mode.sh）刷入重启后验证。
2. `jdspcli get` 的端到端（依赖 §4 的缓存修复）。
3. 72 h 长稳（见 cli-harness 的 soak 脚本）。
