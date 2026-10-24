#!/usr/bin/env bash
#
# 从官方 root-full APK 生成汉化版：资源补丁 + smali 补丁 + 重签。
#
# 用法： ./patch_app.sh <输入.apk> <输出.apk> <keystore> <ks-pass> <key-pass>
# 依赖： apktool 3.x, Android build-tools (zipalign, apksigner), java
#
# 改动内容（不改任何业务逻辑）：
#   1. res/values/bools.xml           default_device_profiles_enable false -> true
#   2. res/values-zh-rCN/strings.xml  追加 389 条缺失的中文翻译（strings_map.json）
#   3. res/layout/*.xml               把硬编码英文（Psychoacoustic / Full Range /
#                                     Predicted / Phase / IR settings ...）换成中文
#   4. smali                          把硬编码英文/俄文字面量换成中文：
#                                       SmoothingType / TargetType 的 displayName、
#                                       测量页对话框标题、声道间延迟提示等
#   5. MainActivity.onResume         不再自动申请 SYSTEM_ALERT_WINDOW
#   6. PresetOverlayManager.show     无悬浮窗权限时改用 in-app Activity
#                                     （PresetSelectionActivity 自带普通对话框回退）
set -euo pipefail

IN=${1:?input apk}
OUT=${2:?output apk}
KS=${3:?keystore}
KS_PASS=${4:?keystore pass}
KEY_PASS=${5:?key pass}

HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "== decode"
apktool d -f -q -o "$WORK/app" "$IN"

echo "== resources: device profiles on by default"
python3 - "$WORK/app" <<'PY'
import sys, pathlib
root = pathlib.Path(sys.argv[1])
p = root / "res/values/bools.xml"
s = p.read_text(encoding="utf-8")
s = s.replace('<bool name="default_device_profiles_enable">false</bool>',
              '<bool name="default_device_profiles_enable">true</bool>')
p.write_text(s, encoding="utf-8")
print("  bools.xml patched")
PY

echo "== resources: zh-rCN strings"
python3 - "$WORK/app" "$HERE/strings_map.json" <<'PY'
import json, sys, pathlib
root = pathlib.Path(sys.argv[1]); m = json.load(open(sys.argv[2], encoding="utf-8"))
p = root / "res/values-zh-rCN/strings.xml"
s = p.read_text(encoding="utf-8")
def esc(v):
    import re
    v = re.sub(r'&(?!(amp|lt|gt|quot|#\d+);)', '&amp;', v)
    return v.replace('"', '\\"').replace("'", "\\'")
add = "\n".join('    <string name="%s">%s</string>' % (k, esc(v)) for k, v in m.items())
i = s.rindex("</resources>")
p.write_text(s[:i] + add + "\n" + s[i:], encoding="utf-8")
print("  %d strings added" % len(m))
PY

echo "== resources: hardcoded layout text"
python3 - "$WORK/app" <<'PY'
import sys, pathlib
root = pathlib.Path(sys.argv[1])
M = {
 "fragment_measurement.xml": [
   ('android:text="IR settings"','android:text="IR 设置"'),
   ('android:text="Psychoacoustic"','android:text="心理声学"'),
   ('android:text="Abs"','android:text="绝对值"'),
   ('android:text="Export EQ"','android:text="导出 EQ"'),
   ('android:text="Full Range"','android:text="全频段"'),
   ('android:text="Export Debug"','android:text="导出调试数据"'),
   ('android:text="Predicted"','android:text="预测"'),
   ('android:text="Phase"','android:text="相位"')],
 "dialog_geq_to_peq_result.xml": [
   ('android:text="Filters"','android:text="滤波器"'),
   ('android:text="Preamp"','android:text="前置增益"'),
   ('android:text="Quality"','android:text="质量"'),
   ('android:text="Max: —"','android:text="最大：—"'),
   ('android:text="RMS: —"','android:text="RMS：—"'),
   ('android:text="Generated Filters"','android:text="生成的滤波器"'),
   ('android:text="Type"','android:text="类型"'),
   ('android:text="Freq"','android:text="频率"'),
   ('android:text="Gain"','android:text="增益"')],
 "custom_dialog.xml": [('android:text=" Ok "','android:text=" 确定 "')],
}
for f, pairs in M.items():
    p = root / "res/layout" / f
    if not p.exists(): continue
    s = p.read_text(encoding="utf-8")
    for a,b in pairs: s = s.replace(a,b)
    p.write_text(s, encoding="utf-8")
print("  layouts patched")
PY

echo "== smali: hardcoded strings"
python3 - "$WORK/app" "$HERE/smali_map.json" <<'PY'
import json, sys, pathlib, re
root = pathlib.Path(sys.argv[1]); m = json.load(open(sys.argv[2], encoding="utf-8"))
def esc(s): return ''.join('\\u%04x' % ord(c) if ord(c) > 127 else c for c in s)
n = 0
for f in root.rglob("*.smali"):
    s = f.read_text(encoding="utf-8"); o = s
    for en, zh in m.items():
        for form in ('"%s"' % esc(en), '"%s"' % en):
            if form in s:
                s = s.replace(form, '"%s"' % zh); n += 1
    if s != o: f.write_text(s, encoding="utf-8")
print("  %d literals replaced" % n)
PY

echo "== smali: no overlay permission"
python3 - "$WORK/app" <<'PY'
import sys, pathlib
root = pathlib.Path(sys.argv[1])
# MainActivity: never auto-request the overlay permission
p = next(root.glob("smali*/me/timschneeberger/rootlessjamesdsp/activity/MainActivity.smali"))
s = p.read_text(encoding="utf-8")
old = """    invoke-static {v0}, Landroid/provider/Settings;->canDrawOverlays(Landroid/content/Context;)Z

    move-result v2

    if-nez v2, :cond_0"""
new = """    invoke-static {v0}, Landroid/provider/Settings;->canDrawOverlays(Landroid/content/Context;)Z

    const/4 v2, 0x1

    if-nez v2, :cond_0"""
assert old in s, "MainActivity pattern changed"
p.write_text(s.replace(old, new, 1), encoding="utf-8")

# PresetOverlayManager: fall back to the in-app activity
p = next(root.glob("smali*/me/timschneeberger/rootlessjamesdsp/utils/PresetOverlayManager.smali"))
s = p.read_text(encoding="utf-8")
old = """    invoke-virtual {v0, v1, v2}, Ltimber/log/Timber$Forest;->w(Ljava/lang/String;[Ljava/lang/Object;)V

    return v8
"""
new = """    invoke-virtual {v0, v1, v2}, Ltimber/log/Timber$Forest;->w(Ljava/lang/String;[Ljava/lang/Object;)V

    move-object/from16 v3, p1

    move-object/from16 v4, p2

    invoke-direct/range {p0 .. p0}, Lme/timschneeberger/rootlessjamesdsp/utils/PresetOverlayManager;->getContext()Landroid/content/Context;

    move-result-object v0

    new-instance v1, Landroid/content/Intent;

    const-class v2, Lme/timschneeberger/rootlessjamesdsp/activity/PresetSelectionActivity;

    invoke-direct {v1, v0, v2}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V

    const/high16 v2, 0x10000000

    invoke-virtual {v1, v2}, Landroid/content/Intent;->addFlags(I)Landroid/content/Intent;

    const-string v2, "extra_device_id"

    invoke-virtual {v1, v2, v3}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;

    const-string v2, "extra_device_name"

    invoke-virtual {v1, v2, v4}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;

    invoke-virtual {v0, v1}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V

    return v8
"""
assert old in s, "PresetOverlayManager pattern changed"
p.write_text(s.replace(old, new, 1), encoding="utf-8")
print("  overlay patches applied")
PY

echo "== smali: global on/off switch drives the engine parameter"
python3 - "$WORK/app" <<'PY'
import sys, pathlib
root = pathlib.Path(sys.argv[1])
p = next(root.glob("smali*/me/timschneeberger/rootlessjamesdsp/interop/JamesDspRemoteEngine.smali"))
s = p.read_text(encoding="utf-8")
old = """.method public setEnabled(Z)V
    .locals 1

    .line 52
    iget-object v0, p0, Lme/timschneeberger/rootlessjamesdsp/interop/JamesDspRemoteEngine;->effect:Landroid/media/audiofx/AudioEffect;

    if-eqz v0, :cond_0

    invoke-virtual {v0, p1}, Landroid/media/audiofx/AudioEffect;->setEnabled(Z)I

    :cond_0
    return-void
.end method"""
new = """.method public setEnabled(Z)V
    .locals 5

    .line 52
    iget-object v0, p0, Lme/timschneeberger/rootlessjamesdsp/interop/JamesDspRemoteEngine;->effect:Landroid/media/audiofx/AudioEffect;

    if-eqz v0, :cond_0

    invoke-virtual {v0, p1}, Landroid/media/audiofx/AudioEffect;->setEnabled(Z)I

    # JamesDSP 全局开关：框架在每个新输出会话都会重新 enable 效果实例，
    # 所以总开关同时写入引擎参数 SET_PARAM(cmd=400)，由引擎持久化并全局旁路。
    const/4 v1, 0x4

    new-array v1, v1, [B

    fill-array-data v1, :array_master_param

    const/4 v2, 0x2

    new-array v2, v2, [B

    if-eqz p1, :cond_master_off

    const/4 v3, 0x1

    goto :goto_master_set

    :cond_master_off
    const/4 v3, 0x0

    :goto_master_set
    const/4 v4, 0x0

    aput-byte v3, v2, v4

    invoke-static {v0, v1, v2}, Lme/timschneeberger/rootlessjamesdsp/utils/extensions/AudioEffectExtensions;->\$r8\$lambda\$2Nm4p6RuTTrFUzd0cCb_ALugltY(Landroid/media/audiofx/AudioEffect;[B[B)I

    :cond_0
    return-void

    :array_master_param
    .array-data 1
        0x90t
        0x1t
        0x0t
        0x0t
    .end array-data
.end method"""
if "array_master_param" not in s:
    assert old in s, "JamesDspRemoteEngine.setEnabled pattern changed"
    p.write_text(s.replace(old, new, 1), encoding="utf-8")
    print("  setEnabled -> SET_PARAM(cmd=400) patched")
else:
    print("  already patched")
PY

echo "== build + sign"
JAR=$(ls /usr/share/java/android-apktool/apktool.jar 2>/dev/null || echo "")
if [ -n "$JAR" ]; then
  java -Xmx4g -jar "$JAR" b "$WORK/app" -o "$WORK/unsigned.apk" -j 1
else
  apktool b "$WORK/app" -o "$WORK/unsigned.apk"
fi
zipalign -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --out "$OUT" "$WORK/aligned.apk"
echo "== done: $OUT"
ls -l "$OUT"
