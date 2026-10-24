#!/system/bin/sh
MODPATH=${0%/*}
MODDIR=$MODPATH
mkdir -p "$MODPATH/logs"
exec 2>"$MODPATH/logs/service_debug.txt"
set -x

# ============================================================
# 挂载完全交给元模块（hybrid_mount / Magisk / KernelSU 的 magic mount）：
#   system/vendor/lib{,64}/soundfx/libjamesdsp.so   -> /vendor/lib{,64}/soundfx/
#   system/vendor/etc/audio/sku_*/audio_effects.*   -> /vendor/etc/audio/sku_*/
#   system/odm/etc/audio_effects.xml                -> /odm/etc/
#   system/etc/audio_effects.xml                    -> /system/etc/
# 因此这里不再做任何 mount / killall audioserver，只保留：
#   1) 纠正 ro.audio.ignore_effects
#   2) 启动 app 安装/启用脚本
#   3) 开机自检（只读）
# ============================================================

if [ -d "$MODPATH/system/vendor" ] && [ ! -L "$MODPATH/system/vendor" ]; then
	MOD_VENDOR="$MODPATH/system/vendor"
	MOD_ODM="$MODPATH/system/odm"
else
	MOD_VENDOR="$MODPATH/vendor"
	MOD_ODM="$MODPATH/odm"
fi

# 0) SELinux：KernelSU 3.3.1 不会自动应用模块的 sepolicy.rule，而 HAL 侧
#    inotify_add_watch 需要 vendor_audio_data_file 的读取权限。post-fs-data
#    阶段应用会被随后的策略加载覆盖，所以放到 late_start（此时策略已定型）。
#    规则幂等，重复应用无副作用。
# 0b) App 总开关同步：读 App 的 powered_on 偏好写入引擎 cmd400。
#     放在 service 阶段的后台循环里，前端被杀死也不影响音效。
if [ -f "$MODPATH/jdsp_appsync.sh" ]; then
	( /system/bin/sh "$MODPATH/jdsp_appsync.sh" & ) >/dev/null 2>&1
fi

if [ -f "$MODPATH/sepolicy.rule" ]; then
	if [ -x /data/adb/ksud ]; then
		/data/adb/ksud sepolicy apply "$MODPATH/sepolicy.rule" >>"$MODPATH/logs/sepolicy.log" 2>&1
	elif command -v ksud >/dev/null 2>&1; then
		ksud sepolicy apply "$MODPATH/sepolicy.rule" >>"$MODPATH/logs/sepolicy.log" 2>&1
	elif command -v magiskpolicy >/dev/null 2>&1; then
		magiskpolicy --live "$(cat "$MODPATH/sepolicy.rule")" >>"$MODPATH/logs/sepolicy.log" 2>&1
	fi
fi

# 0) fork 引擎的 native 状态自持久化：只准备 /data/vendor/audio（目录/属主/0660/标签/日志），
#    不 mount、不建软链、不批量 chcon；挂载与分区上下文由元模块负责。
if [ -f "$MODPATH/jdsp_state.sh" ]; then
	. "$MODPATH/jdsp_state.sh"
	jdsp_state_prepare service
fi

# 1) 防止系统忽略 effect
(
counter=0
while [ "$(getprop ro.audio.ignore_effects)" = "true" ] && [ $counter -le 20 ]; do
	resetprop ro.audio.ignore_effects false
	sleep 1
	counter=$(($counter+1))
done
) >/dev/null 2>&1 &

# 2) app 安装/启用
[ -f "$MODPATH/jdsp_app.sh" ] && . "$MODPATH/jdsp_app.sh" 2>/dev/null &

# 3) 自检（关掉 set -x 以免日志噪音）
sleep 8
{
  echo "=== JDSP mount check $(date)"
  for pair in "$MOD_VENDOR/lib64/soundfx/libjamesdsp.so:/vendor/lib64/soundfx/libjamesdsp.so" \
              "$MOD_VENDOR/lib/soundfx/libjamesdsp.so:/vendor/lib/soundfx/libjamesdsp.so" \
              "$MOD_ODM/etc/audio_effects.xml:/odm/etc/audio_effects.xml" \
              "$MODPATH/system/etc/audio_effects.xml:/system/etc/audio_effects.xml"; do
    SRC=$(echo "$pair" | cut -d: -f1); DST=$(echo "$pair" | cut -d: -f2)
    [ -f "$SRC" ] || continue
    I1=$(stat -c %i "$SRC" 2>/dev/null); I2=$(stat -c %i "$DST" 2>/dev/null)
    if [ -n "$I1" ] && [ "$I1" = "$I2" ]; then echo "  OK   $DST"; else echo "  MISS $DST (src=$I1 dst=$I2)"; fi
  done
  echo "--- jdsp registered in effects: $(dumpsys media.audio_policy 2>/dev/null | grep -c -i jamesdsp)"
  echo "--- jdsp effect instances: $(dumpsys media.audio_flinger 2>/dev/null | grep -c 'f27317f4')"
  echo "--- postprocess apply lines in mounted xml (need >=1):"
  for f in /odm/etc/audio_effects.xml /vendor/etc/audio_effects.xml /system/etc/audio_effects.xml /vendor/etc/audio/sku_*/audio_effects.xml; do
    [ -f "$f" ] || continue
    echo "    $f: $(grep -c 'effect="jamesdsp"' "$f" 2>/dev/null)"
  done
  echo "--- $(dumpsys media.audio_flinger 2>/dev/null | grep -m1 'effects for session 0')"
} > "$MODPATH/logs/mount_check.log" 2>&1

# 4) 详细自检（postprocess 是否注入成功、audioserver 是否拒绝挂载）
[ -f "$MODPATH/jdsp_check.sh" ] && sh "$MODPATH/jdsp_check.sh" >/dev/null 2>&1

set +x