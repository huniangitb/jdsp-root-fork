#!/system/bin/sh
# 把 App 的总开关偏好同步成引擎的全局开关（cmd400）。
#
# 为什么不在 App 里发参数：JamesDspRemoteEngine.setEnabled() 既被用户开关调用，
# 也被服务/会话生命周期调用，杀死前端时会触发一次 setEnabled(false)，
# 那样会把全局音效一起关掉。偏好文件 powered_on 只反映用户意图，所以由模块
# 侧（root）读取并写入 cmd400，前端死活都不影响。
MODPATH=${0%/*}
[ -d "$MODPATH/tools" ] || MODPATH=/data/adb/modules/ainur_jamesdsp
PREFS=/data/data/james.dsp/shared_prefs/application.xml
STATE=/data/vendor/audio/jamesdsp_state.bin
SWITCH="$MODPATH/jdsp_switch.sh"

last=""
while true; do
	cur=""
	if [ -f "$PREFS" ]; then
		if grep -q 'name="powered_on" value="true"' "$PREFS"; then
			cur="on"
		elif grep -q 'name="powered_on" value="false"' "$PREFS"; then
			cur="off"
		fi
	fi
	if [ -n "$cur" ] && [ "$cur" != "$last" ]; then
		if [ -f "$STATE" ]; then
			[ "$last" = "" ] || /system/bin/sh "$SWITCH" "$cur" >/dev/null 2>&1
		else
			/system/bin/sh "$SWITCH" "$cur" >/dev/null 2>&1
		fi
		last="$cur"
		echo "[$(date)] powered_on=$cur synced" >> "$MODPATH/logs/appsync.log"
	fi
	sleep 5
done
