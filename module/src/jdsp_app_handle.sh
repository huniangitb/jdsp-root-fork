#!/system/bin/sh
# 只负责跟随模块的启用/禁用状态，不再在模块被删除时卸载 app
(
	until [ "$(getprop sys.boot_completed)" = "1" ]; do
		sleep 1
	done
	sleep 5

	MODID="ainur_jamesdsp"
	MODPATH="/data/adb/modules/$MODID"
	PKG="james.dsp"

	[ -d "$MODPATH" ] || exit 0

	if [ -f "$MODPATH/disable" ]; then
		pm disable "$PKG" >/dev/null 2>&1
	else
		pm enable "$PKG" >/dev/null 2>&1
	fi
) &
