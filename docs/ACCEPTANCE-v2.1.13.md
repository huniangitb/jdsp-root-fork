# v2.1.13 验收记录（ColorOS 16 / OnePlus OPD2404 / Android 16 / jdspd 2.1.13 + App 1.6.22）

日期：2026-11-08。设备序列号 64a4bad0，模块目录 /data/adb/modules/ainur_jamesdsp。

## 1. 交付物

| 项 | 值 |
| --- | --- |
| 模块包 | dist/JDSP-fork-v2.1.13.zip，33,299,546 B，sha256 `1db282bbcb10cec543f41d7eb3c57f50932c188d2bcb53be65de6d394bc5763e` |
| module.prop | version=v2.1.13-fork (2026-11-08) / versionCode=223 |
| jdspd.dex | md5 `799832532b94778e2b4de945c43086e7`（jdspd 2.1.13）|
| 内置 App | JamesDSPManagerThePBone.apk md5 `31704ddf611405d6ded43cb9cd022a28`（james.dsp 1.6.22 / versionCode 68 / minSdk 26 / targetSdk 35）|
| 自检 | module/package.sh 结构 16 项 + 内容 6 项全绿；unzip -t 无错误 |

安装与开机状态（ksud module install + 重启后实测）：

- jdspd 2.1.13 在跑，mode=auto；
- /odm/etc/audio_effects.xml 与 /system/etc/audio_effects.xml 各 3 处 jamesdsp 标记（effect 声明 + 库 + postprocess 注入），即 insertion=framework；
- App 版本 1.6.22 / versionCode 68（模块内置 APK 已替换）。

> 首次 ksud module install 曾报 `Error: Failed to install module script`，重启后注入为 0 处、后端只能自己建链；
> 重新安装一次即成功（日志 `Module installed successfully!`），重启后注入 3/3。这是安装过程的一次性失败，
> 不是包的问题——但下次装机请确认 boot.log 里没有 `WARN mismatch: 已挂载的 xml 与 mode 不一致`。

## 2. “前端打不开”的崩溃（用户报障）

崩溃栈（AppErrorsTracking，1.6.21/67）：

```
java.lang.NullPointerException
  at ...preference.FileLibraryPreference.onSetInitialValue(FileLibraryPreference.kt:55)
  at androidx.preference.Preference.dispatchSetInitialValue(Preference.java:1596)
  at ...fragment.PreferenceGroupFragment.onCreatePreferences(PreferenceGroupFragment.kt:153)
  at ...activity.MainActivity.onStart(MainActivity.kt:447)
```

根因两条同时成立：

1. shared_prefs/dsp_liveprog.xml 里 liveprog_file 是绝对路径（/data/data/james.dsp/files/liveprog/test.eel，是 v2.1.12 验收用 `am start -e TargetFile <绝对路径>` 打开编辑器时写进去的）；
2. /data/media/0/Android/data/james.dsp 不存在 → getExternalFilesDir(null) 返回 null → File(init).toRelativeString(null!!) NPE。

崩溃发生在“恢复上次所在偏好屏”的 inflation 阶段，所以 App 冷启动就崩、完全打不开；每次再打开都会恢复同一屏、再崩一次。

修法（App 1.6.22）：

- FileLibraryPreference：新增 toRelativePath()——外部目录不可用时原样保留、toRelativeString 失败不抛；initFileList() 对 directory == null 直接返回；
- Preset / BackupManager / LiveprogParamsFragment 同类路径统一改 getExternalFilesDir(null) ?: filesDir；
- PreferenceGroupFragment.inflatePreferences()：inflation 抛异常时清掉本页保存值并重试一次，再失败只 toast（preference_inflation_failed），不再让一次脏数据把整个 App 打死。

真机复现验证（故意重建原始条件）：rm -rf /data/media/0/Android/data/james.dsp 且 liveprog_file 改回绝对路径 → 冷启动 `am start`：
topResumedActivity=james.dsp/...MainActivity，`logcat -b crash` 空；主界面限制器 −0.10 dB / 释放 60.00 ms / 后增益 6.13 dB 正常显示，
曾经越界崩溃的“动态低音提升 → 最大增益”显示 `Value, 1 / 1.00dB`。

### 为什么上一版验收没抓到

上一轮验收是“启动 App + 手点 DSP 主屏 + 滑条/开关”，没有打开过含 FileLibraryPreference 的那个偏好屏，
而崩溃点正好在那屏的 inflation 里；再加上崩溃是“恢复上次屏幕时才发生”，pm clear 之后的新装状态反而不复现。
本轮补了 scripts/ui_smoke.sh：冷启动后把当前屏能看到的每个标签逐个点开（可选 -t x,y 先点开设置齿轮），
每次点完检查 pidof james.dsp 与 logcat -b crash，一崩就失败退出。

## 3. 模式热切换（不重启 audioserver）

实测（设备日志原文）：

```
10:15:18.409 I/jdspd: mode -> backend
10:15:20.650 I/jdspd: mode -> auto
```

- App 设置→音频处理，两个开关是单选；点“增强处理”→ /data/adb/jdspd/mode=backend，点“传统模式”→ auto，状态行立即跟着变（当前生效：传统模式（全局挂载）），没有重启提示；
- audioserver pid 全程不变（没有 killall audioserver），XML 只在开机读一次这件事不再影响切模式；
- mode/getmode/setmode 回复：modeEffective 现在等于当前选择，新增 insertion=framework|daemon|unknown 报告插入路径，rebootRequired 恒为 false（只为老 App 保留字段）。

## 4. 传统模式零插入延迟

链路：框架在 track 创建时建实例 → jdspd 用 dumpsys 拿到该实例的 effect id → 用这个 id 构造 AudioEffect（AOSP EffectChain::addEffect_l 引用计数，只是多一个 client）。实测：

```
10:14:44.927 attach session=89 effectId=43 (dump said 43) enabled=true hasControl=true in 30528us
10:14:45.335 attached session=89 uid=0 pkg= (global)
10:15:09.219 attach session=97 effectId=51 (dump said 51) enabled=true hasControl=true in 2945us
10:16:05.100 attach session=105 effectId=59 (dump said 59) enabled=true hasControl=true in 5588us
```

对比：v2.1.13 早期“不传 id、靠 enabled 推断是否 join”的写法在新 track 上实测
`attach session=105 (auto: no framework instance, released the one this call created in 222240us)`——那 222 ms 里 createEffect_l 一直持有混音线程锁，就是爆音来源。
现在每个新会话只买一次 dump（限速 1/s），dump 里没有框架实例的会话按 1 s → 2 s → 4 s → … → 30 s 阶梯 park，不再按 tick 重试。

## 5. 所有参数热切换（ContentReplay）

- 不可持久化内容参数（8888/12000/12001/10004/10006/10009/10010/25000-25003、DDC/ArbEQ、脉冲响应）由 ContentReplay 记录，attach 后按到达顺序回放（8888 必须先于 12001）；
- 无挂载会话时写这类参数：`setParamGlobal: no mounted session for content param 8888 (vsize=4), queued for the next session` 且返回 0（v2.1.12 是 -1002 丢弃，用户必须“播着再点一次运行”）；
- 真机实测：连续写入 8888 / 12001(256 B) / 10010 → stats 里 `contentParams=3, contentBytes=262`；
- **未完成**：attach 时的回放日志（`content replay: 3 value(s) into session=N`）本轮没抓到——最后一次尝试时设备从 adb 掉线（USB，此前也发生过）。单测 7 例覆盖顺序/8888 清空/多会话/上限/拷贝语义，但真机回放这一条要补。

## 6. 静态检查

| 检查 | 结果 |
| --- | --- |
| ./backend/build.sh | 成功（jdspd.dex 169,264 B / jdspcli.dex 79,876 B）|
| ./backend/test/run_tests.sh | 307 passed / 0 failed + --selftest PASS=11 FAIL=0 |
| javac --release 8 -Xlint:all -Xlint:-options（src + test）| 0 告警 |
| ./module/package.sh | 结构 16 项 + 内容 6 项全绿 |
| unzip -t dist/JDSP-fork-v2.1.13.zip | No errors detected |
| git diff --check | 干净 |

## 7. 残留

- ContentReplay 的 attach 回放要再补一次真机确认（见 §5）。
- `jdspcli set 0 <param> --raw <hex>` 在主开关 enabled=off 时返回 -1003（ERR_DISABLED），错误信息不够直白；建议 CLI 把状态码翻译成文字。
- 18 个语种的“传统模式/增强处理”旧文案未跟改（中英已改）。
- JdspPrefWriter.defaultFloats(1305/1306) 的 mix=50f、defaultFloats(1303)[7]=0.432 仍与引擎默认不一致。
- 引擎侧 1303/1304（loudness tuning）参数依然不存在，12 个 loudness 控件不生效（已在 UI 置灰并加说明）。
