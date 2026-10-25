#!/system/bin/sh
# soak_device.sh — 设备侧长稳监测（72 h），由 scripts/soak.sh 推送并 setsid 启动。
#
# 每轮（默认 10 min）记录一行 TSV：
#   time  jdspd_procs  daemon_pid  uptimeMs  watcherFailures  setParamCalls
#   paramErrors  fatal_cnt  anr_cnt  audiotrack_crash_cnt  tcp_err_lines
# 计数取的是 logcat 环形缓冲的累计值（会翻转，翻转时该轮会看到计数变小，记 0 并标 r）。
# 每 6 h 放一次 5 s tone 驱动 attach/参数链路。
#
# 输出：/data/local/tmp/jdsp-soak/report.tsv（追加），进程死循环直到 SOAK_HOURS 满。
DIR=/data/local/tmp/jdsp-soak
OUT=$DIR/report.tsv
TONE_DEX=/data/local/tmp/jdsp-signal/tone.dex
CLI="CLASSPATH=/data/adb/modules/ainur_jamesdsp/backend/jdspcli.dex app_process /system/bin com.jdsp.cli.Main"
INTERVAL=${SOAK_INTERVAL_S:-600}
HOURS=${SOAK_HOURS:-72}
# SOAK_SECONDS 优先（$(( )) 不支持浮点，短窗口干跑用秒更方便）
if [ -n "${SOAK_SECONDS:-}" ]; then
    TOTAL_S=$SOAK_SECONDS
else
    TOTAL_S=$(( HOURS * 3600 ))
fi
TONE_EVERY=$(( 6 * 3600 ))

mkdir -p "$DIR"
[ -f "$OUT" ] || echo "time\tprocs\tpid\tuptimeMs\twatcherFail\tsetParams\tparamErrors\tfatal\tanr\taudiotrackCrash\tlogTcpErr" > "$OUT"

START=$(date +%s)
END=$(( START + TOTAL_S ))
LAST_TONE=0

while [ "$(date +%s)" -lt "$END" ]; do
    NOW=$(date +%s)

    PROCS=$(pidof jdspd 2>/dev/null | wc -w)
    PID=$(pidof jdspd 2>/dev/null | awk '{print $1}')
    STATS=$(su -c "$CLI stats" 2>/dev/null)
    UPT=$(printf '%s' "$STATS" | grep -o '"uptimeMs":[0-9]*' | cut -d: -f2)
    WFAIL=$(printf '%s' "$STATS" | grep -o '"watcherFailures":[0-9]*' | cut -d: -f2)
    SSET=$(printf '%s' "$STATS" | grep -o '"setParamCalls":[0-9]*' | cut -d: -f2)
    PERR=$(printf '%s' "$STATS" | grep -o '"paramErrors":[0-9]*' | cut -d: -f2)
    FATAL=$(logcat -d -b crash 2>/dev/null | grep -c "FATAL EXCEPTION")
    ANR=$(logcat -d -b events 2>/dev/null | grep -c "am_anr.*james\|am_anr.*jdsp")
    ATCRASH=$(logcat -d 2>/dev/null | grep -c "FATAL EXCEPTION.*AudioTrack\|AudioTrack.*FATAL")
    TCPERR=$(grep -c "tcp.*cannot write\|tcp: cannot create" /data/adb/jdspd/jdspd.log 2>/dev/null)

    echo "$(date '+%m-%d %H:%M:%S')\t${PROCS}\t${PID}\t${UPT:-x}\t${WFAIL:-x}\t${SSET:-x}\t${PERR:-x}\t${FATAL}\t${ANR}\t${ATCRASH}\t${TCPERR}" >> "$OUT"

    # 每 6 h 放 5 s tone（驱动 session attach / 参数回放）
    if [ $(( NOW - LAST_TONE )) -ge $TONE_EVERY ] && [ -f "$TONE_DEX" ]; then
        LAST_TONE=$NOW
        (CLASSPATH=$TONE_DEX app_process /system/bin Tone 5 soak >/dev/null 2>&1) &
    fi

    sleep "$INTERVAL"
done
echo "soak complete at $(date)" >> "$OUT"
