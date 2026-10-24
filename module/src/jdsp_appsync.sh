#!/system/bin/sh
# 全局状态恒为 1：后端（引擎状态文件）是唯一权威。
#
# 背景：App 的 AudioEffect.setEnabled() 同时被用户开关和服务/会话生命周期调用；
# 一旦由前端驱动 cmd400，杀前端会写 0、多会话重放时又会命中 0，导致效果整体失效。
# 现在模块只保证状态文件里始终是“恰好一条 cmd400=1”，
# “关掉某个应用”的语义交给按应用排除（会话级 DISABLE），互不影响。
MODPATH=${0%/*}
[ -d "$MODPATH/tools" ] || MODPATH=/data/adb/modules/ainur_jamesdsp
STATE=/data/vendor/audio/jamesdsp_state.bin
SWITCH="$MODPATH/jdsp_switch.sh"

fix_once() {
	[ -f "$STATE" ] || return 1
	/system/bin/sh "$SWITCH" check >/dev/null 2>&1 && return 0
	/system/bin/sh "$SWITCH" on >/dev/null 2>&1
	echo "[$(date)] forced cmd400=1" >> "$MODPATH/logs/appsync.log"
	return 0
}

fix_once
i=0
while [ $i -lt 12 ]; do
	i=$((i+1))
	sleep 10
	fix_once
done
