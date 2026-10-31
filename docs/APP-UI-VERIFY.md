# App 侧端到端复测（v2.1.9 之后）

> 目的：v2.1.9 只改 daemon（会话发现/闸门），需确认 App 1.6.20-66 的写入与回读链路没有回归。
> 设备 64a4bad0（OnePlus OPD2404 / ColorOS 16 / Android 16），时间 2026-10-31 18:12–18:16。

## 1. 方法：用 overlay 虚拟屏驱动 App（物理屏不受影响）

会话里的 `android_vscreen_*` 工具在本机不可用（调用即报 harness 错误 `content.some is not a function`），
改用等价的系统能力：

```bash
# 建虚拟屏（SurfaceFlinger: Virtual display 11529215047637634986 / logical display 3）
adb -s 64a4bad0 shell 'settings put global overlay_display_devices "1080x1920/420"'
# 让 App 跑在虚拟屏上
adb -s 64a4bad0 shell 'am start --display 3 -n james.dsp/me.timschneeberger.rootlessjamesdsp.activity.MainActivity'
# 输入 / 截图都用 display id（物理屏 display 0 全程是用户自己的 App）
adb -s 64a4bad0 shell 'input -d 3 swipe 430 1342 690 1342 500'
adb -s 64a4bad0 shell 'screencap -p -d 11529215047637634986 /sdcard/v.png'
# 收尾
adb -s 64a4bad0 shell 'settings delete global overlay_display_devices'
```

截图证据：`out/app-ui-verify/{01-open,02-after-drag,03-cold-start,04-after-toggle}.png`。

## 2. 结果

| 用例 | 操作 | 观测 | 结论 |
|---|---|---|---|
| T1 滑杆写入 | 后增益（param `1500`）从 0.00dB 拖到 12.55dB | `JdspPrefWriter` 30 条写入，间隔 **16.7 ms**（≈60 fps，一帧一批）；后端 `1500` 落到 `…58e5d0be`=12.527；UI 显示 12.55dB；`setParamCalls` +26、`lastLatencyUs` **2564 µs** | 通过 |
| T2 冷启动回读 | `am force-stop` → 重新启动 | UI 直接显示 **12.55dB**（来自后端）；`JdspPrefWriter` 写入 **0 条**；config 与重启前逐字节一致 | 通过（"UI 不持有参数"仍成立） |
| T3 总开关 off | 点电源键（有 1 个播放会话） | jdspd 日志 `global disabled: 1 instance(s) bypassed`，距 tap **123 ms**（含 adb `input` 注入）；会话仍 attached、enabled=0 | 通过（bypass 不拆链） |
| T4 总开关 on | 再点一次 | `global enabled: 1 instance(s) un-bypassed`，距 tap **130 ms**；会话 enabled=1 | 通过 |
| T5 开关期间零污染 | 对比 config | 两次开关期间参数写入 **0 条**，仅 `enabled` 变化 | 通过 |

## 3. 还原与对账

- `1500` 已恢复基线 `cdccccbd0000704200000000`（daemon 停机改 config + 监督脚本拉起后 dump-config 校验）。
- **26 个全局参数与 v2.1.8 基线逐字节一致**，`perAppParams` 为空，`mode=backend`、`enabled=true`、`sessions=0`。
- 虚拟屏已删除（`overlay_display_devices` = null）、测试播放器已退出、App 已 force-stop；soak 继续（`jdsphits=0`）。

## 4. 说明

- 123/130 ms 是「adb 注入 tap → daemon 生效」的端到端值，包含 `input` 进程启动与 App→后端 TCP 往返；
  daemon 内部 apply 本身是 1-2 ms（日志同毫秒两条）。
- 滑杆链路时延由 App 侧日志给出：一帧一批（16.7 ms 节拍）+ daemon 侧 RTT 2.5 ms，即「UI → 引擎」≤ 1 帧 + RTT ≈ 20 ms。
