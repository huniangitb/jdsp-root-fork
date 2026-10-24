#!/system/bin/sh
# JamesDSP postprocess 自检（只读，不改任何状态）。
# 用法（root）：sh /data/adb/modules/ainur_jamesdsp/jdsp_check.sh
# 请在「正在播放音乐」时执行，否则看不到效果链。

OUT=/data/local/tmp/jdsp_check.txt

{
echo "=== JamesDSP check $(date)"

echo
echo "--- 1) 生效的 audio_effects.xml 是否已声明 <apply effect=\"jamesdsp\"/>"
echo "    apply = 自动挂载声明数（必须 >=1）；decl = 效果声明数"
for f in /odm/etc/audio_effects.xml /vendor/etc/audio_effects.xml /system/etc/audio_effects.xml /vendor/etc/audio/sku_*/audio_effects.xml; do
	[ -f "$f" ] || continue
	printf '    %-48s apply=%s decl=%s\n' "$f" "$(grep -c 'effect="jamesdsp"' "$f" 2>/dev/null)" "$(grep -c 'name="jamesdsp"' "$f" 2>/dev/null)"
done

echo
echo "--- 2) 框架里 jamesdsp 的注册与实例数"
echo "    audio_policy  : $(dumpsys media.audio_policy  2>/dev/null | grep -c -i jamesdsp)"
echo "    audio_flinger : $(dumpsys media.audio_flinger 2>/dev/null | grep -c 'f27317f4')"

echo
echo "--- 3) 输出线程 / 效果链"
dumpsys media.audio_flinger 2>/dev/null | grep -E '^Output thread |AudioStreamOut:|Effect Chains|effects for session|No FastMixer|FastMixer thread'

echo
echo "--- 4) jamesdsp 实例详情（session 0 = App 挂的；非 0 = postprocess 挂的）"
dumpsys media.audio_flinger 2>/dev/null | grep -A7 'UUID: f27317f4'

echo
echo "--- 5) 进程"
ps -A -o PID,NAME 2>/dev/null | grep -E 'audioserver|james.dsp'

echo
echo "--- 6) audioserver 拒绝原因（关键）"
echo "    Failed to add effects / non HW effect ... fast mode = 该曲目走 fast 链路，软件效果被框架拒绝"
logcat -d 2>/dev/null | grep -iE 'addOutputSessionEffects|Failed to add effects|non HW effect|non post processing effect' | tail -25
} > "$OUT" 2>&1

cat "$OUT"
echo
echo "已保存：$OUT"
