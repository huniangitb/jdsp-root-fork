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

# 2.5) jdspd 后端守护（看护循环：等 AudioFlinger → 起 daemon → 退出 2s 重启 →
#      每分钟把 state.bin 归一化回 0660，因为 HAL 每次写盘都会重建成 0600）
if [ -f "$MODPATH/jdspd.sh" ]; then
	mkdir -p /data/adb/jdspd 2>/dev/null
	chmod 0700 /data/adb/jdspd 2>/dev/null
	JDSP_LOG_SIZE=$(stat -c %s /data/adb/jdspd/jdspd.log 2>/dev/null || echo 0)
	[ "$JDSP_LOG_SIZE" -gt 1048576 ] && : > /data/adb/jdspd/jdspd.log
	nohup sh "$MODPATH/jdspd.sh" >/dev/null 2>&1 &
fi

# 2.6) mode 校验：这是唯一能发现"以为切了其实没切"的地方
#      （XML 由 audioserver 在启动时读一次，且 hybrid_mount 的挂载是 ro 快照）
if [ -f "$MODPATH/jdsp_mode.sh" ]; then
	. "$MODPATH/jdsp_mode.sh"
	jdsp_mode_verify service
fi

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