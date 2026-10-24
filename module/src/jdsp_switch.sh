#!/system/bin/sh
# 全局开关：把 SET_PARAM(cmd=400) 写进引擎状态文件。
# 走普通参数通道并持久化，因此不受框架“每个会话自动 ENABLE”的影响。
# 用法：sh jdsp_switch.sh on|off
MODPATH=${0%/*}
[ -d "$MODPATH/tools" ] || MODPATH=/data/adb/modules/ainur_jamesdsp

case "$(getprop ro.product.cpu.abi)" in
  arm64*) BIN="$MODPATH/tools/bin/jdspswitch.arm64" ;;
  *)      BIN="$MODPATH/tools/bin/jdspswitch.arm" ;;
esac

[ -f "$BIN" ] || BIN="$MODPATH/tools/bin/jdspswitch"
[ -f "$BIN" ] || { echo "jdspswitch binary not found under $MODPATH/tools"; exit 1; }
chmod 755 "$BIN" 2>/dev/null
exec "$BIN" "$1"
