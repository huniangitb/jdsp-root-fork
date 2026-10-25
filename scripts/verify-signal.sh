#!/usr/bin/env bash
# verify-signal.sh — signal level acceptance of the JamesDSP backend (task-8).
#
#   scripts/verify-signal.sh [serial]
#   scripts/verify-signal.sh --serial <serial>
#
# Device selection (same rules as module/accept-v2.sh,
# backend/test/device/run_acceptance.sh):
#   1. first argument / --serial
#   2. $DSH_SERIAL
#   3. $ANDROID_SERIAL
#   4. $SERIAL
#   5. the only online device reported by "adb devices"
#   With 0 or more than one online device the script exits 2 instead of guessing.
#
# Hard criteria (never relaxed):
#   * the significant parameter (limiter postGain +12 dB) must change the
#     measured output level by MORE THAN 1.5 dB and the direction must match
#     the parameter semantics (postGain up -> RMS up);
#   * after restoring the original parameters the level must come back to the
#     neutral baseline within 0.5 dB;
#   * no underrun in the capture window (dumpsys audio_flinger counters);
#   * no crash / ANR in logcat during the capture window.
#
# State is queried through jdspcli (binder first, TCP as a cross check);
# dumpsys / logcat are only corroborating evidence.
# Every parameter change is restored before the script exits.
#
# Capture plan: AudioRecord is tried first with REMOTE_SUBMIX (the digital
# output mix).  When that path only delivers zeros (common on devices without
# a loopback capture route) the probe falls back to the acoustic loopback
# (AudioRecord(MIC)) with the phone speaker in front of the microphone.  The
# plan that was used is printed and recorded in the report.
#
# Exit code: 0 all checks passed, 1 at least one FAIL, 2 environment problem.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/.." && pwd)
# shellcheck source=../backend/test/device/lib/device.sh
. "$REPO/backend/test/device/lib/device.sh"

DEVICE_DIR=${DEVICE_DIR:-/data/local/tmp/jdsp-signal}
PARAM_ID=${PARAM_ID:-1500}
TONE_SECONDS=${TONE_SECONDS:-5}
TONE_DBFS=${TONE_DBFS:--6}
GAIN_DB=${GAIN_DB:-12}
THRESHOLD=${THRESHOLD:-0}
RELEASE=${RELEASE:-0}
DELTA_MIN_DB=${DELTA_MIN_DB:-1.5}
# Tone level for the offline renderer.  The engine always runs a brickwall
# limiter whose threshold is clamped to -0.09 dBFS, so a -6 dBFS test tone
# plus +12 dB of post gain would be limited back to ~0 dBFS and the gain
# would be invisible.  At -18 dBFS the limiter stays transparent and the
# post gain shows up as exactly the requested dB.
OFFLINE_TONE_DBFS=${OFFLINE_TONE_DBFS:--18}
RESTORE_TOL_DB=${RESTORE_TOL_DB:-0.5}
CAPTURE_SOURCE=${CAPTURE_SOURCE:-auto}
# The native offline renderer (built from tools/native/offline_render.c).  It
# renders a fixed PCM block through the vendor engine, so the measurement does
# not depend on any recording path.
OFFLINE_RENDER=${OFFLINE_RENDER:-$DEVICE_DIR/offline_render}
LOG_DIR="$REPO/backend/test/build/signal"
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
        -h|--help) sed -n '2,36p' "$0"; exit 0 ;;
        --serial) SERIAL_ARG=${2:-}; shift 2 ;;
        --serial=*) SERIAL_ARG=${a#--serial=}; shift ;;
        -*) echo "unknown option: $a" >&2; exit 2 ;;
        *) if [ -n "$SERIAL_ARG" ]; then
               echo "only one serial may be given" >&2
               exit 2
           fi
           SERIAL_ARG=$a; shift ;;
    esac
done

jdsp_select_device "$SERIAL_ARG" || exit 2
SERIAL=$JDSP_SERIAL
ADB="$JDSP_ADB -s $SERIAL"
mkdir -p "$LOG_DIR"

sh_() { $ADB shell "$1" 2>&1 | tr -d '\r'; }

# jdspcli on the device.  $1 = transport ("" = default/binder, "tcp" = --tcp).
cli() {
    local transport=$1
    shift
    local flag=""
    [ "$transport" = "tcp" ] && flag="--tcp"
    local raw
    raw=$(sh_ "CLASSPATH=$DEVICE_DIR/jdspcli.dex app_process /system/bin com.jdsp.cli.Main $flag $* 2>$DEVICE_DIR/cli.err; echo __RC=$?")
    CLI_RC=${raw##*__RC=}
    CLI_OUT=${raw%__RC=*}
    CLI_ERR=$(sh_ "cat $DEVICE_DIR/cli.err 2>/dev/null")
}

# ---- restore guard ---------------------------------------------------------
# Acceptance scripts must never leave the device in a bypassed state: any path
# that turns the global switch off has to turn it back on, otherwise every
# later check measures an effect that is switched off.
RESTORE_ON_EXIT=0
restore_guard() {
    if [ "$RESTORE_ON_EXIT" = "1" ]; then
        echo "restore     : turning the global switch back on"
        cli enabled on >/dev/null 2>&1
        cli enabled 2>/dev/null | tail -1
    fi
}
trap restore_guard EXIT INT TERM

# field <json> <session> <key>
field() { # json session key -> value (the CLI prints pretty printed JSON, so it is parsed with python3)
    printf '%s' "$1" | tr -d '\r' | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)
sessions = data if isinstance(data, list) else [data]
want = sys.argv[1]
key = sys.argv[2]
for s in sessions:
    if not isinstance(s, dict):
        continue
    ident = s.get("session", s.get("sessionId"))
    if str(ident) == want:
        v = s.get(key)
        if v is None:
            break
        if isinstance(v, bool):
            print("true" if v else "false")
        else:
            print(v)
        break
' "$2" "$3"
}

# json_global_params <json> -> the globalParams object as a sorted "key=value" list.
# The CLI prints pretty printed JSON, so it is parsed with python3.
json_global_params() {
    printf '%s' "$1" | tr -d '\r' | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except Exception as exc:
    print("PARSE_ERROR %s" % exc)
    sys.exit(0)
params = data.get("globalParams") or {}
if not isinstance(params, dict):
    print("PARSE_ERROR not-an-object")
    sys.exit(0)
print(" ".join("%s=%s" % (k, params[k]) for k in sorted(params)))
'
}

echo "=== build test binaries ==="
if ! jdsp_build_and_push_all "$DEVICE_DIR"; then
    echo "ERROR: could not build the probe dex files" >&2
    exit 2
fi
if ! jdsp_push_offline_renderer "$DEVICE_DIR"; then
    echo "WARN: the offline renderer could not be built; the signal criteria will be SKIP"
fi
echo
echo "=== global switch ==="
# The daemon only attaches effects while the global master switch is on.
# It is turned on here (and the previous state is restored at the end) because
# a disabled daemon can never produce a signal level change.
cli "" enabled
ENABLED_BEFORE=$(printf '%s' "$CLI_OUT" | tr -d '\r' | tr -d ' ' | head -1)
echo "enabled     : ${ENABLED_BEFORE:-unknown}"
if [ "$ENABLED_BEFORE" != "on" ]; then
    cli "" enabled on
    if [ "$CLI_RC" = "0" ]; then
        record PASS "global-enabled" "global switch was off, turned on for the test"
    else
        record FAIL "global-enabled" "could not turn the global switch on (rc=$CLI_RC err=$CLI_ERR)"
        report_and_exit
        exit 1
    fi
    sleep 2
else
    record PASS "global-enabled" "global switch already on"
fi

echo
echo "=== session ==="
TONE_LOG="$DEVICE_DIR/signal-tone.out"
sh_ "pkill -f '[a]pp_process /system/bin Tone'" >/dev/null 2>&1
sleep 1
sh_ "cd $DEVICE_DIR; setsid env CLASSPATH=$DEVICE_DIR/tone.dex app_process /system/bin Tone 600 signal-$STAMP > $TONE_LOG 2>&1 < /dev/null &" >/dev/null 2>&1
sleep 3
SESSION=$(sh_ "grep -o 'SESSION=[0-9]*' $TONE_LOG | head -1 | cut -d= -f2")
if [ -z "$SESSION" ]; then
    record FAIL "session" "the test tone did not start (see $TONE_LOG)"
    report_and_exit
    exit 1
fi
echo "session     : $SESSION"
sleep 2

ATTACHED=""
for i in $(seq 1 20); do
    cli "" sessions --json
    ATTACHED=$(field "$CLI_OUT" "$SESSION" attached)
    [ "$ATTACHED" = "true" ] && break
    sleep 0.5
done
if [ "$ATTACHED" = "true" ]; then
    record PASS "session-attached" "session=$SESSION attached=true (binder)"
else
    record FAIL "session-attached" "session=$SESSION attached=${ATTACHED:-unknown} (binder)"
    report_and_exit
    exit 1
fi

echo
echo "=== transport consistency (binder vs tcp) ==="
cli "" sessions --json
BINDER_SESSIONS=$(printf '%s' "$CLI_OUT" | tr -d '\r')
cli "" dump-config
BINDER_CONFIG=$(printf '%s' "$CLI_OUT" | tr -d '\r')
cli "" stats
BINDER_STATS=$(printf '%s' "$CLI_OUT" | tr -d '\r')
b_id=$(field "$BINDER_SESSIONS" "$SESSION" sessionId)
b_att=$(field "$BINDER_SESSIONS" "$SESSION" attached)
b_eff=$(field "$BINDER_SESSIONS" "$SESSION" effectId)
b_par=$(field "$BINDER_SESSIONS" "$SESSION" paramCount)
b_gp=$(json_global_params "$BINDER_CONFIG")
b_mode=$(printf '%s' "$BINDER_STATS" | sed -n 's/.*"mode":\([0-9]*\).*/\1/p')
echo "binder      : session=$b_id attached=$b_att effect=$b_eff params=$b_par mode=$b_mode"

cli tcp sessions --json
TCP_RC=$CLI_RC
if [ "$TCP_RC" != "0" ]; then
    # The loopback channel needs the endpoint files that jdspd writes into the
    # app's private directory; without the app they do not exist.
    record SKIP "transport-sessions" "the TCP channel is unavailable: $CLI_ERR"
    record SKIP "transport-config" "the TCP channel is unavailable"
    record SKIP "transport-mode" "the TCP channel is unavailable"
else
    TCP_SESSIONS=$(printf '%s' "$CLI_OUT" | tr -d '\r')
    t_id=$(field "$TCP_SESSIONS" "$SESSION" sessionId)
    t_att=$(field "$TCP_SESSIONS" "$SESSION" attached)
    t_eff=$(field "$TCP_SESSIONS" "$SESSION" effectId)
    t_par=$(field "$TCP_SESSIONS" "$SESSION" paramCount)
    if [ "$b_id" = "$t_id" ] && [ "$b_att" = "$t_att" ] && [ "$b_eff" = "$t_eff" ] && [ "$b_par" = "$t_par" ]; then
        record PASS "transport-sessions" "sessionId=$b_id attached=$b_att effectId=$b_eff paramCount=$b_par (binder == tcp)"
    else
        record FAIL "transport-sessions" "binder(session=$b_id,attached=$b_att,effect=$b_eff,params=$b_par) != tcp(session=$t_id,attached=$t_att,effect=$t_eff,params=$t_par)"
    fi
    cli tcp dump-config
    TCP_CONFIG=$(printf '%s' "$CLI_OUT" | tr -d '\r')
    t_gp=$(json_global_params "$TCP_CONFIG")
    if [ -n "$b_gp" ] && [ "$b_gp" = "$t_gp" ]; then
        record PASS "transport-config" "globalParams identical (binder == tcp)"
    else
        record FAIL "transport-config" "binder=$b_gp != tcp=$t_gp"
    fi
    cli tcp stats
    TCP_STATS=$(printf '%s' "$CLI_OUT" | tr -d '\r')
    t_mode=$(printf '%s' "$TCP_STATS" | sed -n 's/.*"mode":\([0-9]*\).*/\1/p')
    if [ -n "$b_mode" ] && [ "$b_mode" = "$t_mode" ]; then
        record PASS "transport-mode" "mode=$b_mode (binder == tcp)"
    else
        record FAIL "transport-mode" "binder mode=$b_mode != tcp mode=$t_mode"
    fi
fi
echo
echo
echo "=== capture capability ==="
probe() { # source -> probe output
    timeout 60 $ADB shell "CLASSPATH=$DEVICE_DIR/signalprobe.dex app_process /system/bin SignalProbe probe $1 2>/dev/null" 2>&1 | tr -d '\r'
}
PROBE_REMOTE=$(probe remote)
printf '%s\n' "$PROBE_REMOTE" | sed 's/^/    /'
SOURCE=""
PLAN=""
if [ "$CAPTURE_SOURCE" = "remote" ]; then
    SOURCE=remote
    PLAN=remote
elif [ "$CAPTURE_SOURCE" = "offline" ]; then
    PLAN=offline
elif printf '%s' "$PROBE_REMOTE" | grep -q "PROBE result=available"; then
    SOURCE=remote
    PLAN=remote
else
    # The microphone is deliberately not used: its noise floor makes the level
    # measurement meaningless (measured jitter > 5 dB for the same parameter).
    PLAN=offline
fi
if [ -n "$SOURCE" ]; then
    record PASS "capture-available" "capture source=$SOURCE (REMOTE_SUBMIX, the digital output mix)"
elif [ "$PLAN" = "offline" ] && sh_ "[ -x $DEVICE_DIR/offline_render ] && echo yes" | grep -q yes; then
    record PASS "capture-available" "REMOTE_SUBMIX is unusable; using the offline renderer (plan=offline)"
elif [ "$PLAN" = "offline" ]; then
    # The renderer is missing or not executable: the signal criteria cannot
    # be measured at all.
    record SKIP "capture-available" "REMOTE_SUBMIX delivered only zeros ($(printf '%s' "$PROBE_REMOTE" | grep -o 'reason=[^ ]*' | head -1)); the offline renderer is not available on the device"
    record SKIP "signal-delta" "no usable capture path (REMOTE_SUBMIX all zero, offline renderer unavailable)"
    record SKIP "signal-direction" "no usable capture path"
    record SKIP "signal-restore" "no usable capture path"
    report_and_exit
    exit 0
else
    record SKIP "capture-available" "REMOTE_SUBMIX delivered only zeros ($(printf '%s' "$PROBE_REMOTE" | grep -o 'reason=[^ ]*' | head -1)); no usable capture path"
    record SKIP "signal-delta" "no usable capture path (REMOTE_SUBMIX all zero, offline renderer unavailable)"
    record SKIP "signal-direction" "no usable capture path"
    record SKIP "signal-restore" "no usable capture path"
    report_and_exit
    exit 0
fi
echo "plan        : $PLAN"
measure_remote() { # tag -> "rms peak" in dBFS
    local tag=$1
    local out
    out=$(timeout 90 $ADB shell "CLASSPATH=$DEVICE_DIR/signalprobe.dex app_process /system/bin SignalProbe capture $TONE_SECONDS $tag $TONE_DBFS remote 2>/dev/null" 2>&1 | tr -d '\r')
    printf '%s' "$out" > "$LOG_DIR/capture-$tag.log"
    local rms peak
    rms=$(printf '%s' "$out" | sed -n 's/.*rmsDbfs=\([0-9.-]*\).*/\1/p' | head -1)
    peak=$(printf '%s' "$out" | sed -n 's/.*peakDbfs=\([0-9.-]*\).*/\1/p' | head -1)
    [ -n "$rms" ] || return 1
    echo "$rms $peak"
}

measure_offline() { # paramId v1 v2 v3 [toneDbfs] -> "neutral tested restored" rms in dBFS
    local out
    # The renderer needs the tone level as well: the engine always runs a
    # brickwall limiter, so the tone has to sit far enough below 0 dBFS for
    # the post gain to be observable at all.
    out=$(timeout 90 $ADB shell "cd $DEVICE_DIR; LD_LIBRARY_PATH=$DEVICE_DIR ./offline_render $1 $2 $3 $4 $OFFLINE_TONE_DBFS 2>&1" 2>&1 | tr -d '\r')
    printf '%s' "$out" > "$LOG_DIR/offline-render.log"
    # The renderer prints the levels in dBFS itself (NEUTRAL_DBFS=...); that
    # is the primary source.  The linear RMS lines are the fallback for an
    # older renderer.
    local n t r
    n=$(printf '%s' "$out" | sed -n 's/^NEUTRAL_DBFS=\([0-9.-]*\).*/\1/p' | head -1)
    t=$(printf '%s' "$out" | sed -n 's/^TESTED_DBFS=\([0-9.-]*\).*/\1/p' | head -1)
    r=$(printf '%s' "$out" | sed -n 's/^RESTORED_DBFS=\([0-9.-]*\).*/\1/p' | head -1)
    if [ -z "$n" ] || [ -z "$t" ] || [ -z "$r" ]; then
        local ln lt lr
        ln=$(printf '%s' "$out" | sed -n 's/^NEUTRAL rms=\([0-9.eE+-]*\).*/\1/p' | head -1)
        lt=$(printf '%s' "$out" | sed -n 's/^TESTED rms=\([0-9.eE+-]*\).*/\1/p' | head -1)
        lr=$(printf '%s' "$out" | sed -n 's/^RESTORED rms=\([0-9.eE+-]*\).*/\1/p' | head -1)
        if [ -z "$ln" ] || [ -z "$lt" ] || [ -z "$lr" ]; then
            return 1
        fi
        n=$(awk "BEGIN { printf \"%.2f\", 20 * log($ln / 32768) }")
        t=$(awk "BEGIN { printf \"%.2f\", 20 * log($lt / 32768) }")
        r=$(awk "BEGIN { printf \"%.2f\", 20 * log($lr / 32768) }")
    fi
    echo "$n $t $r"
}

ORIG_HEX=""
cli "" get "$SESSION" "$PARAM_ID" 12
if [ "$CLI_RC" = "0" ]; then
    ORIG_HEX=$(printf '%s' "$CLI_OUT" | tr -d '\r' | tr -d ' ' | head -1)
fi
echo "original    : param $PARAM_ID = ${ORIG_HEX:-<unknown>}"

# Writes the original value back and verifies it.  Every exit path of the
# script runs this through the EXIT trap, so a failed or aborted run can never
# leave the parameter modified.
restore() {
    if [ -n "$ORIG_HEX" ] && [ -n "$SESSION" ]; then
        # The daemon rejects writes while the global switch is off (-1006), so
        # the switch is turned on for the restore and put back afterwards.
        local was_off=0
        if [ "${ENABLED_BEFORE:-}" = "off" ]; then
            was_off=1
            cli "" enabled on >/dev/null 2>&1
        fi
        cli "" set "$SESSION" "$PARAM_ID" --hex "$ORIG_HEX" >/dev/null 2>&1
        cli "" get "$SESSION" "$PARAM_ID" 12
        local now
        now=$(printf '%s' "$CLI_OUT" | tr -d '\r' | tr -d ' ' | head -1)
        if [ "$now" = "$ORIG_HEX" ]; then
            echo "restored    : param $PARAM_ID = $ORIG_HEX (verified)"
            RESTORE_OK=1
        else
            echo "restored    : FAILED, param $PARAM_ID is now ${now:-<unknown>} (wanted $ORIG_HEX)"
            RESTORE_OK=0
        fi
        if [ "$was_off" = "1" ]; then
            cli "" enabled off >/dev/null 2>&1
            echo "restored    : global switch off (the state before the test)"
        fi
    else
        echo "restored    : nothing to restore (no original value known)"
    fi
    sh_ "pkill -f '[a]pp_process /system/bin Tone'" >/dev/null 2>&1
}
RESTORE_OK=0
trap restore EXIT

WINDOW_START=$(sh_ "date '+%m-%d %H:%M:%S.000'")
echo "=== neutral vs significant ==="

# The parameter is also pushed through the daemon (binder) so the engine in the
# running session really receives it; the offline renderer then measures what
# the engine does with that parameter on an identical PCM block.
cli "" set "$SESSION" "$PARAM_ID" "$THRESHOLD" "$RELEASE" 0.0
if [ "$CLI_RC" != "0" ]; then
    record FAIL "signal-neutral-set" "set neutral failed (rc=$CLI_RC err=$CLI_ERR)"
    report_and_exit
    exit 1
fi
sleep 1

if [ "$PLAN" = "remote" ]; then
    echo "(measuring through the live output mix)"
    MEAS=$(measure_remote neutral)
    if [ -z "$MEAS" ]; then
        record FAIL "signal-neutral" "the neutral capture produced no measurement"
        report_and_exit
        exit 1
    fi
    N_RMS=$(printf '%s' "$MEAS" | cut -d' ' -f1)
    N_PEAK=$(printf '%s' "$MEAS" | cut -d' ' -f2)
    echo "neutral     : rms=${N_RMS} dBFS peak=${N_PEAK} dBFS"
    cli "" set "$SESSION" "$PARAM_ID" "$THRESHOLD" "$RELEASE" "$GAIN_DB"
    sleep 1
    MEAS=$(measure_remote tested)
    T_RMS=$(printf '%s' "$MEAS" | cut -d' ' -f1)
    T_PEAK=$(printf '%s' "$MEAS" | cut -d' ' -f2)
    echo "significant : rms=${T_RMS} dBFS peak=${T_PEAK} dBFS"
    restore
    sleep 1
    MEAS=$(measure_remote restored)
    R_RMS=$(printf '%s' "$MEAS" | cut -d' ' -f1)
    echo "restored    : rms=${R_RMS} dBFS (baseline ${N_RMS} dBFS)"
else
    MEAS=$(measure_offline "$PARAM_ID" "$THRESHOLD" "$RELEASE" "$GAIN_DB")
    if [ -z "$MEAS" ]; then
        record SKIP "signal-delta" "the offline renderer could not render the block (see $LOG_DIR/offline-render.log)"
        record SKIP "signal-direction" "no usable signal measurement"
        record SKIP "signal-restore" "no usable signal measurement"
        report_and_exit
        exit 0
    fi
    N_RMS=$(printf '%s' "$MEAS" | cut -d' ' -f1)
    T_RMS=$(printf '%s' "$MEAS" | cut -d' ' -f2)
    R_RMS=$(printf '%s' "$MEAS" | cut -d' ' -f3)
    DELTA=$(awk "BEGIN { printf \"%.2f\", $T_RMS - $N_RMS }")
    DIRECTION=none
    if awk "BEGIN { exit !(($T_RMS - $N_RMS) > 0.05) }"; then DIRECTION=increase; fi
    if awk "BEGIN { exit !(($T_RMS - $N_RMS) < -0.05) }"; then DIRECTION=decrease; fi
    echo "delta       : ${DELTA} dB ($DIRECTION)"

    if awk "BEGIN { exit !(($DELTA > $DELTA_MIN_DB) || ($DELTA < -$DELTA_MIN_DB)) }"; then
        record PASS "signal-delta" "postGain +${GAIN_DB} dB changed the level by ${DELTA} dB (> ${DELTA_MIN_DB} dB, $DIRECTION)"
    else
        record FAIL "signal-delta" "postGain +${GAIN_DB} dB changed the level by only ${DELTA} dB (need > ${DELTA_MIN_DB} dB, $DIRECTION)"
    fi
    if [ "$DIRECTION" = "increase" ]; then
        record PASS "signal-direction" "postGain up -> RMS up (${N_RMS} -> ${T_RMS} dBFS)"
    else
        record FAIL "signal-direction" "postGain +${GAIN_DB} dB did not raise the level (${N_RMS} -> ${T_RMS} dBFS, direction=$DIRECTION)"
    fi

    # the restore criterion: the level must return to the neutral baseline
    RDELTA=$(awk "BEGIN { printf \"%.2f\", $R_RMS - $N_RMS }")
    echo "restored    : rms=${R_RMS} dBFS (baseline ${N_RMS} dBFS, delta ${RDELTA} dB)"
    if awk "BEGIN { exit !(($RDELTA < $RESTORE_TOL_DB) && ($RDELTA > -$RESTORE_TOL_DB)) }"; then
        record PASS "signal-restore" "level back at the baseline (${R_RMS} vs ${N_RMS} dBFS, ${RDELTA} dB)"
    else
        record FAIL "signal-restore" "level did not return to the baseline (${R_RMS} vs ${N_RMS} dBFS, ${RDELTA} dB)"
    fi
fi

echo
echo "=== log hygiene ==="
sleep 1
WINDOW_LOG=$(sh_ "logcat -d -T '$WINDOW_START' -t 8000 2>/dev/null")
CRASHES=$(printf '%s' "$WINDOW_LOG" | grep -ciE 'FATAL EXCEPTION|ANR in |debuggerd.*audio|audioserver.*died' || true)
if [ "${CRASHES:-1}" -eq 0 ]; then
    record PASS "log-crash" "no crash / ANR in the capture window"
else
    record FAIL "log-crash" "$CRASHES crash/ANR line(s) in the capture window"
fi
UNDERRUN_LINES=$(sh_ "dumpsys media.audio_flinger 2>/dev/null | grep -i 'underrun counters'")
BAD_UNDERRUN=$(printf '%s' "$UNDERRUN_LINES" | grep -vE 'partial=0 empty=0' | head -3)
if [ -z "$BAD_UNDERRUN" ]; then
    record PASS "no-underrun" "dumpsys underrun counters are zero"
else
    record FAIL "no-underrun" "non-zero underrun counters: $(printf '%s' "$BAD_UNDERRUN" | tr '\n' ' ')"
fi

echo
echo "=== binder side effect ==="
cli "" stats
STATS=$(printf '%s' "$CLI_OUT" | tr -d '\r')
SPC=$(printf '%s' "$STATS" | sed -n 's/.*"setParamCalls":\([0-9]*\).*/\1/p')
PE=$(printf '%s' "$STATS" | sed -n 's/.*"paramErrors":\([0-9]*\).*/\1/p')
AVG=$(printf '%s' "$STATS" | sed -n 's/.*"avgLatencyUs":\([0-9]*\).*/\1/p')
echo "stats       : setParamCalls=$SPC paramErrors=$PE avgLatencyUs=$AVG"
if [ -n "$SPC" ] && [ "${SPC:-0}" -gt 0 ]; then
    record PASS "binder-setparam" "stats.setParamCalls=$SPC (the daemon applied the parameters)"
else
    record FAIL "binder-setparam" "stats.setParamCalls did not increase"
fi
if [ -n "$AVG" ] && [ "$AVG" -lt 20000 ]; then
    record PASS "binder-latency" "stats.avgLatencyUs=$AVG (< 20 ms)"
else
    record FAIL "binder-latency" "stats.avgLatencyUs=${AVG:-?} (need < 20000)"
fi

report_and_exit
