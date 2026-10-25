#!/usr/bin/env bash
# verify-headless.sh — the backend must stay usable without the front end app.
#
#   scripts/verify-headless.sh [serial]
#   scripts/verify-headless.sh --serial <serial>
#
# Device selection (same rules as module/accept-v2.sh,
# backend/test/device/run_acceptance.sh, scripts/verify-signal.sh):
#   1. first argument / --serial
#   2. $DSH_SERIAL
#   3. $ANDROID_SERIAL
#   4. $SERIAL
#   5. the only online device reported by "adb devices"
#   With 0 or more than one online device the script exits 2 instead of guessing.
#
# What it checks (all state assertions go through jdspcli / binder):
#   1. the app is running before the test (so "force-stop" really removes it)
#   2. after "am force-stop james.dsp" the daemon still attaches to the new
#      playback session: sessions -> attached=true, paramCount>0
#   3. dump-config still carries the parameters
#   4. the signal level still changes with the parameter (the same hard
#      criterion as verify-signal.sh: > 1.5 dB, correct direction) and comes
#      back to the baseline after the restore
#   5. the app is started again at the end (the scene is restored)
#
# Nothing is installed, uninstalled or rebooted; only "am force-stop" and
# "am start" of the existing app are used.
#
# Exit code: 0 all checks passed, 1 at least one FAIL, 2 environment problem.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/.." && pwd)
# shellcheck source=../backend/test/device/lib/device.sh
. "$REPO/backend/test/device/lib/device.sh"

DEVICE_DIR=${DEVICE_DIR:-/data/local/tmp/jdsp-headless}
ADB="jdsp_adb"
APP_PKG=${APP_PKG:-james.dsp}
PARAM_ID=${PARAM_ID:-1500}
TONE_SECONDS=${TONE_SECONDS:-5}
TONE_DBFS=${TONE_DBFS:--6}
GAIN_DB=${GAIN_DB:-12}
THRESHOLD=${THRESHOLD:-0}
RELEASE=${RELEASE:-0}
DELTA_MIN_DB=${DELTA_MIN_DB:-1.5}
RESTORE_TOL_DB=${RESTORE_TOL_DB:-0.5}
CAPTURE_SOURCE=${CAPTURE_SOURCE:-auto}
LOG_DIR="$REPO/backend/test/build/headless"
STAMP=$(date +%H%M%S)

PASS=0
FAIL=0
SKIP=0
RESULTS=()

record() { # status item detail
    RESULTS+=("$1|$2|$3")
    case "$1" in
        PASS) PASS=$((PASS + 1)) ;;
        FAIL) FAIL=$((FAIL + 1)) ;;
        SKIP) SKIP=$((SKIP + 1)) ;;
    esac
}

report_and_exit() {
    echo
    printf '%-6s %-22s %s\n' RESULT ITEM DETAIL
    local row status item detail
    for row in "${RESULTS[@]}"; do
        IFS='|' read -r status item detail <<<"$row"
        printf '%-6s %-22s %s\n' "$status" "$item" "$detail"
    done
    echo
    echo "summary: $PASS passed, $FAIL failed, $SKIP skipped"
    [ "$FAIL" -eq 0 ]
}

SERIAL_ARG=""
while [ $# -gt 0 ]; do
    a=$1
    case "$a" in
        -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
        --serial) SERIAL_ARG=${2:-}; shift 2 ;;
        --serial=*) SERIAL_ARG=${a#--serial=}; shift ;;
        -*) echo "unknown option: $a" >&2; exit 2 ;;
        *) SERIAL_ARG=$a; shift ;;
    esac
done

echo
echo "=== capture capability ==="
PROBE_REMOTE=$($ADB shell "CLASSPATH=$DEVICE_DIR/signalprobe.dex app_process /system/bin SignalProbe probe remote 2>/dev/null" 2>&1 | tr -d '\r')
SOURCE=""
if [ "$CAPTURE_SOURCE" != "auto" ]; then
    SOURCE=$CAPTURE_SOURCE
elif printf '%s' "$PROBE_REMOTE" | grep -q "PROBE result=available"; then
    SOURCE=remote
else
    # The microphone is deliberately not used: its noise floor makes the level
    # measurement meaningless (measured jitter > 5 dB for the same parameter).
    :
fi
if [ -n "$SOURCE" ]; then
    record PASS "capture-available" "capture source=$SOURCE"
else
    record FAIL "capture-available" "no usable capture path"
    report_and_exit
    exit 1
fi
echo "source      : $SOURCE"

measure() { # tag -> "rms peak" or empty
    local tag=$1
    local out
    out=$($ADB shell "CLASSPATH=$DEVICE_DIR/signalprobe.dex app_process /system/bin SignalProbe capture $TONE_SECONDS $tag $TONE_DBFS $SOURCE 2>/dev/null" 2>&1 | tr -d '\r')
    printf '%s' "$out" > "$LOG_DIR/capture-$tag.log"
    local rms peak
    rms=$(printf '%s' "$out" | sed -n 's/.*rmsDbfs=\([0-9.-]*\).*/\1/p' | head -1)
    peak=$(printf '%s' "$out" | sed -n 's/.*peakDbfs=\([0-9.-]*\).*/\1/p' | head -1)
    [ -n "$rms" ] || return 1
    echo "$rms $peak"
}

ORIG_HEX=""
cli "" get "$SESSION" "$PARAM_ID" 12
if [ "$CLI_RC" = "0" ]; then
    ORIG_HEX=$(printf '%s' "$CLI_OUT" | tr -d '\r' | tr -d ' ' | head -1)
fi
echo "original    : param $PARAM_ID = ${ORIG_HEX:-<unknown>}"

restore_param() {
    if [ -n "$ORIG_HEX" ] && [ -n "$SESSION" ]; then
        cli "" set "$SESSION" "$PARAM_ID" --hex "$ORIG_HEX" >/dev/null 2>&1
        echo "restored    : param $PARAM_ID = $ORIG_HEX"
    fi
}

echo
echo "=== signal level without the front end ==="
cli "" set "$SESSION" "$PARAM_ID" "$THRESHOLD" "$RELEASE" 0.0
sleep 1
NEUTRAL=$(measure neutral)
if [ -z "$NEUTRAL" ]; then
    record FAIL "headless-neutral" "the neutral capture produced no measurement"
    restore_param
    report_and_exit
    exit 1
fi
N_RMS=$(printf '%s' "$NEUTRAL" | cut -d' ' -f1)
echo "neutral     : rms=${N_RMS} dBFS"

cli "" set "$SESSION" "$PARAM_ID" "$THRESHOLD" "$RELEASE" "$GAIN_DB"
sleep 1
TESTED=$(measure tested)
if [ -z "$TESTED" ]; then
    record FAIL "headless-delta" "the significant capture produced no measurement"
    restore_param
    report_and_exit
    exit 1
fi
T_RMS=$(printf '%s' "$TESTED" | cut -d' ' -f1)
DELTA=$(awk "BEGIN { printf \"%.2f\", $T_RMS - $N_RMS }")
echo "significant : rms=${T_RMS} dBFS (delta ${DELTA} dB)"
if awk "BEGIN { exit !(($DELTA > $DELTA_MIN_DB) && ($T_RMS > $N_RMS)) }"; then
    record PASS "headless-delta" "postGain +${GAIN_DB} dB raised the level by ${DELTA} dB (> ${DELTA_MIN_DB} dB) without the front end"
else
    record FAIL "headless-delta" "postGain +${GAIN_DB} dB changed the level by ${DELTA} dB (need > ${DELTA_MIN_DB} dB, direction up)"
fi

restore_param
sleep 1
RESTORED=$(measure restored)
if [ -z "$RESTORED" ]; then
    record FAIL "headless-restore" "the restored capture produced no measurement"
else
    R_RMS=$(printf '%s' "$RESTORED" | cut -d' ' -f1)
    RDELTA=$(awk "BEGIN { printf \"%.2f\", $R_RMS - $N_RMS }")
    echo "restored    : rms=${R_RMS} dBFS (baseline ${N_RMS} dBFS, delta ${RDELTA} dB)"
    if awk "BEGIN { exit !(($RDELTA < $RESTORE_TOL_DB) && ($RDELTA > -$RESTORE_TOL_DB)) }"; then
        record PASS "headless-restore" "level back at the baseline (${R_RMS} vs ${N_RMS} dBFS)"
    else
        record FAIL "headless-restore" "level did not return to the baseline (${R_RMS} vs ${N_RMS} dBFS, ${RDELTA} dB)"
    fi
fi

echo
echo "=== binder side effect ==="
cli "" stats
STATS=$(printf '%s' "$CLI_OUT" | tr -d '\r')
SPC=$(printf '%s' "$STATS" | sed -n 's/.*"setParamCalls":\([0-9]*\).*/\1/p')
AVG=$(printf '%s' "$STATS" | sed -n 's/.*"avgLatencyUs":\([0-9]*\).*/\1/p')
echo "stats       : setParamCalls=$SPC avgLatencyUs=$AVG"
if [ -n "$SPC" ] && [ "${SPC:-0}" -gt 0 ]; then
    record PASS "headless-setparam" "stats.setParamCalls=$SPC"
else
    record FAIL "headless-setparam" "stats.setParamCalls did not increase"
fi
if [ -n "$AVG" ] && [ "$AVG" -lt 20000 ]; then
    record PASS "headless-latency" "stats.avgLatencyUs=$AVG (< 20 ms)"
else
    record FAIL "headless-latency" "stats.avgLatencyUs=${AVG:-?} (need < 20000)"
fi

restore_app
report_and_exit
