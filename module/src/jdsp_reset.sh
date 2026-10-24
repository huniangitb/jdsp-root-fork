#!/system/bin/sh
# 清空后端状态：删除持久化的参数文件，并重启音频 HAL 让所有效果实例重建。
# 用法： sh jdsp_reset.sh
MODPATH=${0%/*}
[ -d "$MODPATH/tools" ] || MODPATH=/data/adb/modules/ainur_jamesdsp
STATE=/data/vendor/audio/jamesdsp_state.bin

rm -f "$STATE" "$STATE".tmp.* 2>/dev/null
echo "removed $STATE"
echo "[$(date)] state cleared" >> "$MODPATH/logs/reset.log"
setprop ctl.restart vendor.audio-hal 2>/dev/null
echo "audio HAL restarted; the app will repopulate the state on next start"
