# App 总开关状态的权威性（v2.1.6 / App 1.6.20-66）

> 现象来自 release-eng 装机复验；修复在 loundess_git（App 侧），daemon 不需要改。

## 1. 现象

jdspd 刚重启时（App 在前台、**无任何人工操作**），master switch 自己翻了三次：

    14:03:55  off
    14:03:58  on
    14:04:01  off      <- 最终停在 off（用户看到的是"我自己关了 DSP"）

## 2. 根因：三条把"App 缓存值"当"用户意图"的路径

| # | 路径 | 为什么错 |
|---|---|---|
| 1 | `RootSessionDatabase.createSession()` 里 `effect.enabled = JdspStatusCache.enabled()` → `JdspParamSink.setEnabled` → `JdspBridge.setEnabledNow` → 写后端 | 每出现一个音频会话就把 App 的 **2 s 状态快照**推一次；daemon 重启时快照是旧值或抖动的，于是"新会话"把旧开关状态压回后端 |
| 2 | `JdspParamSink.setEnabled()` 把 AudioEffect 兼容层的 setEnabled 当成用户意图直投 | 兼容层语义是"本地句柄开关"，在这里却等于"改后端配置" |
| 3 | `JdspBridge.pendingEnabled`：一个纯**值**的待重放缓冲（任何失败路径都会写它），worker 每 500 ms 重放一次 | 重放的是"值"而不是"用户意图"，重连后第一发成功就把它推给新 daemon；配合路径 1 的抖动值，就出现 off→on→off |

## 3. 修复（App 1.6.20 / 66）

| # | 改动 | 位置 |
|---|---|---|
| 1 | 删除"新会话 → 推快照"：`createSession()` 不再碰后端开关 | `session/root/RootSessionDatabase.kt` |
| 2 | `JdspParamSink.setEnabled()` / `JdspBridge.onEnabled()` 改成 **no-op**（只记日志），AudioEffect 兼容层不再能改后端 | `interop/jdsp/JdspParamSink.java`、`JdspBridge.java` |
| 3 | `pendingEnabled` → **`userEnableIntent`**：只有 `JdspBridge.setEnabledByUser()` 会写它，唯一调用点是 MainActivity 的 FAB 点击；送达即清 | `JdspBridge.java`、`activity/MainActivity.kt` |
| 4 | 新增 `JdspBridge.refreshEnabledFromBackend(t)`：`ensureTransport()` 每次建好连接置 `stateRefreshNeeded`，worker 下一轮 `getEnabled()` **读**后端真值 → 写 `JdspStatusCache.applyBackendEnabled()` 与 `backendDisabled`。**只读不写** | `JdspBridge.java` |
| 5 | `JdspStatusCache.EnabledObserver`（只用于显示）：MainActivity 在 `onResume` 注册、`onPause` 注销，把 FAB 与 `key_powered_on` 对齐到后端值 | `JdspStatusCache.java`、`MainActivity.kt` |

不变量：**只有用户点击能写后端开关**；连上后端只读一次真值；2 s 快照（`JdspStatusCache`）只用于显示，永远不回写。

## 4. 真机验证

### 4.1 方法

真机 adb 64a4bad0，App 1.6.20 (66) 前台，daemon `jdspd 2.1.5`；`kill $(pidof jdspd)` 后看护循环 2 s 拉起。
判据用 daemon 自己的日志：`/data/adb/jdspd/jdspd.log` 里 `global enabled` / `global disabled` 行数
（一次转换产生 2 行：策略行 + `N instance(s) …` 行），用行号标记切出观察窗口。脚本：
`out/latency/task13/verify_switch.sh`（本机归档）。

### 4.2 结果

| 用例 | 操作 | 期望 | 实测 | 判定 |
|---|---|---|---|---|
| A 重启不翻转 | App 前台、enabled=on，kill jdspd，观察 80 s | 开关保持 on，窗口内 0 次转换 | enabled **on → on**，daemon 2 s 内重启（pid 17233），**0 次转换** | ✅ |
| B 重启后点一次 | kill jdspd → 等 daemon 回来 → App 前台点一次总开关 | 恰好 1 次转换、开关变 off、之后不被回推 | enabled **on → off**，窗口内**恰好 1 次** off 转换（14:48:18.925），再等 25 s 仍为 off、无第二次转换 | ✅ |
| C 重连只读不写 | 再 kill 一次（此时 enabled=on） | App 只读 `getEnabled`，日志出现读取记录，无 setEnabled | App 侧日志 `JdspBridge: transport ready` → `backend state after (re)connect: enabled=…`；daemon 侧 0 次转换 | ✅ |

对照：修复前（release-eng 复验）同一场景在 6 s 内出现 off→on→off 三次翻转并停在 off；
本次 80 s 窗口内 0 次。

### 4.3 复现命令

```sh
S=64a4bad0; B="CLASSPATH=/data/adb/modules/ainur_jamesdsp/backend/jdspcli.dex app_process /system/bin com.jdsp.cli.Main"
adb -s $S shell am start -W -n james.dsp/me.timschneeberger.rootlessjamesdsp.activity.MainActivity
M=$(adb -s $S shell su -c 'wc -l < /data/adb/jdspd/jdspd.log')
adb -s $S shell su -c 'kill $(pidof jdspd)'
sleep 80
adb -s $S shell su -c "tail -n +$((M+1)) /data/adb/jdspd/jdspd.log" | grep -c -e 'global enabled' -e 'global disabled'   # 期望 0
adb -s $S shell su -c "$B enabled"                                                                                        # 期望 on
```

（再做一次 kill + 在前台用 `input tap 1500 1921` 点一次总开关：期望日志里恰好一次 off 转换、`enabled` 变 off，
25 s 后仍为 off。）
