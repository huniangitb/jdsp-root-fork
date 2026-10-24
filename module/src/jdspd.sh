#!/system/bin/sh
# jdspd.sh — jdspd 守护进程的看护循环（由 service.sh 以 nohup 启动，前台跑循环）
#
# 职责：
#   1) 等 AudioFlinger 就绪（service check media.audio_flinger 找到为止）；
#   2) 用 app_process 起 com.jdsp.backend.Daemon（--nice-name=jdspd）；
#   3) daemon 退出 2 秒后重启，重启次数与退出码写日志；
#   4) 每分钟把 /data/vendor/audio/jamesdsp_state.bin 归一化回 0660
#      （HAL 每次写盘都用 .tmp+rename，权限会被重建成 0600）；
#   5) 启动时记录 App 侧目录是否存在（只检查，绝不创建 /data/data/james.dsp/files）。
#
# 注意：安装器会把模块内所有 *.sh 的行首 # 注释删掉，所以本文件必须用 sh 调用，
#       不能依赖 shebang 或可执行位。

MODPATH=${0%/*}
RUNDIR=/data/adb/jdspd
DEX="$MODPATH/backend/jdspd.dex"
APP_FILES=/data/data/james.dsp/files
STATE=/data/vendor/audio/jamesdsp_state.bin
LOG="$RUNDIR/jdspd.log"

mkdir -p "$RUNDIR" 2>/dev/null
chmod 0700 "$RUNDIR" 2>/dev/null

# 打包时携带的默认 mode（可选）：首次运行落成运行期配置
if [ ! -f "$RUNDIR/mode" ] && [ -f "$MODPATH/backend/mode.default" ]; then
	cp -f "$MODPATH/backend/mode.default" "$RUNDIR/mode" 2>/dev/null
	chmod 0600 "$RUNDIR/mode" 2>/dev/null
fi

# 每次开机轮转一次日志，否则跨重启累积的 "starting jdspd" 行会被误判成重启风暴。
# service.sh 只在本脚本启动前调用一次，所以这里是"每次开机一次"的合适位置。
jdspd_rotate_log() {
	[ -f "$LOG" ] || return 0
	mv -f "$LOG" "$LOG.prev" 2>/dev/null || rm -f "$LOG" 2>/dev/null
}

jdspd_log() {
	echo "[$(date '+%m-%d %H:%M:%S')] $*" >> "$LOG" 2>/dev/null
}

jdspd_mode() {
	_m=$(cat "$RUNDIR/mode" 2>/dev/null | tr -d ' \t\r\n')
	case "$_m" in auto|backend) echo "$_m" ;; *) echo auto ;; esac
}

# state.bin 归一化：只在权限不是 0660 时动手
jdspd_normalize_state() {
	[ -f "$STATE" ] || return 0
	_cur=$(stat -c %a "$STATE" 2>/dev/null)
	[ -n "$_cur" ] || return 0
	[ "$_cur" = "660" ] && return 0
	if chmod 0660 "$STATE" 2>/dev/null; then
		jdspd_log "state.bin chmod $_cur -> 660"
	else
		jdspd_log "WARN state.bin chmod $_cur -> 660 failed"
	fi
}

jdspd_wait_af() {
	_i=0
	while [ $_i -lt 120 ]; do
		service check media.audio_flinger 2>/dev/null | grep -q "found" && return 0
		sleep 1
		_i=$((_i + 1))
	done
	return 1
}

jdspd_main() {
	if [ ! -f "$DEX" ]; then
		jdspd_log "FATAL dex missing: $DEX (module package incomplete?)"
		return 1
	fi
	if [ -d "$APP_FILES" ]; then
		jdspd_log "app files dir present: $APP_FILES (daemon may bind app_data_file socket)"
	else
		jdspd_log "app files dir ABSENT: $APP_FILES (App not installed/started; daemon falls back to $RUNDIR socket)"
	fi
	if ! jdspd_wait_af; then
		jdspd_log "WARN media.audio_flinger not ready after 120s, starting daemon anyway"
	fi
	jdspd_log "supervisor start: mode=$(jdspd_mode) dex=$DEX boot=$(getprop ro.boottime.init 2>/dev/null)"

	_restarts=0
	while :; do
		_restarts=$((_restarts + 1))
		jdspd_log "starting jdspd (attempt $_restarts)"
		CLASSPATH="$DEX" app_process /system/bin --nice-name=jdspd com.jdsp.backend.Daemon >> "$LOG" 2>&1 &
		_pid=$!
		# 等 daemon 退出；期间每分钟归一化一次 state.bin
		while kill -0 "$_pid" 2>/dev/null; do
			sleep 60
			jdspd_normalize_state
		done
		wait "$_pid"
		_rc=$?
		jdspd_normalize_state
		# daemon 自己会做单实例保护（/data/adb/jdspd/jdspd.lock + FileChannel.tryLock），
		# 抢不到锁时打印 "another jdspd is running" 并以 0 退出 —— 这种情况不要 2s 狂重启。
		if [ "$_rc" = "0" ]; then
			jdspd_log "jdspd exited rc=0 (clean stop or instance already running), retry in 30s"
			sleep 30
		else
			jdspd_log "jdspd exited rc=$_rc (crash), restart in 2s"
			sleep 2
		fi
	done
}

# 手动维护入口（验收/排障用）：sh jdspd.sh normalize
case "${1:-}" in
	normalize)
		jdspd_normalize_state
		echo "state.bin mode=$(stat -c %a "$STATE" 2>/dev/null || echo missing)"
		exit 0
		;;
esac

jdspd_rotate_log
jdspd_main
