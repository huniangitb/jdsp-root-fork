# App 改动（资源 + smali 补丁，不改业务逻辑）

官方 fork APK 在 ColorOS 上功能正常，但存在三个问题，这里用**资源与 smali 补丁**修掉，
不重新编译代码（Gradle 全量构建在本机不可行：buildSrc 插件需联网、内存不足）。

## 改动清单

| # | 文件 | 改动 |
|---|---|---|
| 1 | `res/values/bools.xml` | `default_device_profiles_enable` false → **true**（按设备配置默认开启） |
| 2 | `res/values-zh-rCN/strings.xml` | 追加 **389 条**中文翻译（567 → 956，与英文零缺口） |
| 3 | `res/layout/*.xml` | 硬编码英文改中文：Psychoacoustic / Full Range / Predicted / Phase / IR settings / Abs / Export EQ / Export Debug；GEQ→PEQ 结果对话框的 Filters / Preamp / Quality / Type / Freq / Gain 等 |
| 4 | `smali` 字面量 | `SmoothingType.displayName`（11 条）、`TargetType.displayName`（5 条）、MEOW 各对话框标题（IR 设置 / 窗函数 / IR 长度 / 平均次数 / 目标类型 / 平滑方式 / 测量模式 / MEOW 音频输入）、声道间延迟提示、响度校准失败提示 |
| 5 | `MainActivity.onResume` | **不再**自动申请 `SYSTEM_ALERT_WINDOW` |
| 6 | `PresetOverlayManager.show` | 无悬浮窗权限时改为启动 `PresetSelectionActivity`（它自带普通对话框回退），因此**预设选择完全在 App 界面内完成，不需要任何权限** |

## 用法

```sh
./patch_app.sh <官方root-full.apk> <输出.apk> <keystore> <ks-pass> <key-pass>
```

首次需要生成签名（签名与官方不同，装机前必须先卸载旧版；音效参数在模块的状态文件里，不会丢）：

```sh
keytool -genkeypair -keystore jdsp.keystore -alias jdsp -keyalg RSA -keysize 2048 \
        -validity 10000 -storepass jdsp123 -keypass jdsp123 \
        -dname "CN=JamesDSP Fork, O=Local, C=CN"
```

## 备注

- `SmoothingType` 的 chip 标签会用 `.replace(" smoothing", "")` 截短，中文译名不含该子串，
  因此 chip 上显示的是完整译名（如「1/1 平滑」），这是预期行为。
- 界面截图见 `docs/screenshots/`。
