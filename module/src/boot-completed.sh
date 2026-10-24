#!/system/bin/sh
MODPATH=${0%/*}
mkdir -p "$MODPATH/logs"
LOG="$MODPATH/logs/sepolicy.log"

[ -f "$MODPATH/sepolicy.rule" ] || exit 0

apply_once() {
	/data/adb/ksud sepolicy apply "$MODPATH/sepolicy.rule" >>"$LOG" 2>&1 && return 0
	command -v magiskpolicy >/dev/null 2>&1 && magiskpolicy --live "$(cat "$MODPATH/sepolicy.rule")" >>"$LOG" 2>&1
}

# KernelSU 在开机后的一段时间里可能仍在收敛策略，越早应用越容易被后续加载覆盖。
# boot-completed 之后分多次重试（规则幂等），保证最终生效。
i=0
while [ $i -lt 8 ]; do
	i=$((i+1))
	apply_once
	echo "--- attempt $i: $(date) ---" >>"$LOG"
	sleep 15
done
