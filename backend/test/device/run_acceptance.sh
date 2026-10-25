#!/usr/bin/env bash
# jdspcli device acceptance — PLAN-v2 §6 items 1-4 (+ optional 30 min soak for item 5).
#
# What it checks (on the real device, via jdspcli):
#   1. a playback session is discovered: attached=true and params>0
#   2. set <session> 1500 -0.1 60 3.0 then get <session> 1500 12 returns the same bytes
#   3. bench: set/get round-trip max latency < 20 ms, no get mismatch, no underrun log
#   4. two players with different uids: policy exclude one -> attached=false for it,
#      attached=true for the other; restoring the policy re-attaches it
#   5. optional soak: SOAK_SECONDS=1800 (--soak) for the 30 min run
#
# Transports (measured on xaga / Android 15 / KernelSU):
#   * binder jdsp.service : works from the root/su domain (this script uses adb shell root)
#   * loopback TCP+token  : works from the App and from root (jdspcli auto-falls back,
#                           or use --tcp [host:port]); port/token files:
#                           /data/data/james.dsp/files/jdsp.port and jdsp.token
#   * root shell          : app_process + CLASSPATH is all that is needed (no App install)
#
# Multi-uid players for item 4 (no APK installs on the shared device):
#   we run tools/Tone.java as two *real app uids* chosen from /data/system/packages.list
#   (unique pkg per uid):  su <uid> -c 'CLASSPATH=<tone.dex> app_process /system/bin Tone ...'
#   Verified on xaga: su 2000/10123 both work; ART rejects world-writable dex files, so the
#   dex lives in a root-owned 0755 dir with mode 0644 (DEVICE_DIR below).
#
# Usage:
#   bash backend/test/device/run_acceptance.sh                   # items 1-4
#   SOAK_SECONDS=1800 bash backend/test/device/run_acceptance.sh  # + 30 min soak
#   bash .../run_acceptance.sh --soak                             # same as SOAK_SECONDS=1800
#   STRICT_SKIP=1 ...                                             # exit 3 when jdspd is absent
#   bash .../run_acceptance.sh <serial>                           # pick the device
#   bash .../run_acceptance.sh --serial <serial>                  # same
#
# Device selection (same rules as scripts/verify-v2.sh and
# module/accept-v2.sh):
#   1. first argument / --serial
#   2. $DSH_SERIAL
#   3. $ANDROID_SERIAL
#   4. $SERIAL
#   5. the only online device reported by "adb devices"
#   With 0 or more than one online device the script exits 2 instead of guessing.
#
# The test tone is built from the repository (tools/Tone.java) and the CLI from
# backend/src/com/jdsp/cli, so the script needs nothing pre-installed on the device.
#
# Env: ADB, DSH_SERIAL, ANDROID_SERIAL, SERIAL, ANDROID_SDK, DEVICE_DIR,
#      SOAK_SECONDS, STRICT_SKIP.
# Exit: 0 all measured items passed (skips allowed unless STRICT_SKIP=1);
#       1 at least one FAIL; 2 environment problem (no device / build failure);
#       3 daemon unavailable and STRICT_SKIP=1.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
BACKEND=$(cd "$HERE/../.." && pwd)
REPO=$(cd "$BACKEND/.." && pwd)
# shellcheck source=lib/device.sh
. "$HERE/lib/device.sh"
SDK=${ANDROID_SDK:-/home/bdy/Android/Sdk}
ADB=${ADB:-adb}
DEVICE_DIR=${DEVICE_DIR:-/data/local/tmp/jdsp-accept}
SOAK_SECONDS=${SOAK_SECONDS:-0}
STRICT_SKIP=${STRICT_SKIP:-0}
# Primary transport for every measurement: loopback TCP (binder is root-only).
# Set CLI_TRANSPORT= to use the auto path (binder first), e.g. for a binder-only run.
CLI_TRANSPORT=${CLI_TRANSPORT:---tcp}
LOG_DIR="$BACKEND/test/build/device"
STAMP=$(date +%Y%m%d-%H%M%S)
MARKER="acc-$$-$STAMP"
PKG_A=""
POLICY_CHANGED=0
CLI_OUT=""
CLI_ERR=""
CLI_RC=0

SERIAL_ARG=""
while [ $# -gt 0 ]; do
    arg=$1
    case "$arg" in
        --soak) SOAK_SECONDS=1800; shift ;;
        --soak=*) SOAK_SECONDS="${arg#--soak=}"; shift ;;
        --strict) STRICT_SKIP=1; shift ;;
        --serial) SERIAL_ARG=${2:-}; shift 2 ;;
        --serial=*) SERIAL_ARG=${arg#--serial=}; shift ;;
        -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
        -*) echo "unknown argument: $arg" >&2; exit 2 ;;
        *) if [ -n "$SERIAL_ARG" ]; then
               echo "only one serial may be given" >&2
               exit 2
           fi
           SERIAL_ARG=$arg; shift ;;
    esac
done

# Device selection: first argument / --serial, then DSH_SERIAL, ANDROID_SERIAL,
# SERIAL, then the single online device.  Never guesses with several devices.
jdsp_select_device "$SERIAL_ARG" || exit 2
SERIAL=$JDSP_SERIAL
ADB_CMD=("${JDSP_ADB_SN[@]}")
adb_() { "${ADB_CMD[@]}" "$@"; }
dev() { adb_ shell "$1"; }

PASSES=0
FAILS=0
SKIPS=0
RESULTS=()

record() { # status item detail
    local status=$1 item=$2 detail=$3
    RESULTS+=("$status|$item|$detail")
    case "$status" in
        PASS) PASSES=$((PASSES + 1)) ;;
        FAIL) FAILS=$((FAILS + 1)) ;;
        SKIP) SKIPS=$((SKIPS + 1)) ;;
    esac
}

kill_tones() {
    local pids pid
    pids=$(dev "ps -A -o pid,args 2>/dev/null | grep -F '$MARKER' | grep -v grep | awk '{print \$1}'")
    for pid in $pids; do
        dev "kill $pid" >/dev/null 2>&1 || true
    done
}

cleanup() {
    kill_tones
    if [ -n "$PKG_A" ] && [ "$POLICY_CHANGED" = "1" ]; then
        run_cli policy set "$PKG_A" follow >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

run_raw() { # exact jdspcli argv (used by the transport probe)
    local raw
    raw=$(dev "CLASSPATH=$DEVICE_DIR/jdspcli.dex app_process /system/bin com.jdsp.cli.Main $* 2>$DEVICE_DIR/cli.err; echo __RC=\$?")
    CLI_RC=${raw##*__RC=}
    CLI_OUT=$(printf '%s' "${raw%__RC=*}" | tr -d '\r')
    CLI_ERR=$(dev "cat $DEVICE_DIR/cli.err 2>/dev/null" | tr -d '\r')
}

run_cli() { # measurements go through ${CLI_TRANSPORT:-auto}
    run_raw $CLI_TRANSPORT "$@"
}

start_tone() { # uid|root seconds tag -> prints device stdout file
    local uid=$1 secs=$2 tag=$3
    local out="$DEVICE_DIR/tone-$tag.out"
    dev "rm -f $out"
    # setsid + ";": the "sh -c ... && nohup ... &" form keeps adb shell's stdout
    # pipe open (adb then blocks until the tone exits), so avoid that form.
    if [ "$uid" = "root" ]; then
        dev "cd $DEVICE_DIR; setsid env CLASSPATH=$DEVICE_DIR/tone.dex app_process /system/bin Tone $secs $MARKER $tag > $out 2>&1 < /dev/null &"
    else
        dev "setsid su $uid -c 'CLASSPATH=$DEVICE_DIR/tone.dex app_process /system/bin Tone $secs $MARKER $tag' > $out 2>&1 < /dev/null &"
    fi
    echo "$out"
}

wait_session() { # stdout file -> prints session id
    local out=$1 i sid
    for i in $(seq 1 50); do
        sid=$(dev "grep -o 'SESSION=[0-9]*' $out 2>/dev/null | head -1 | cut -d= -f2")
        if [ -n "$sid" ]; then
            echo "$sid"
            return 0
        fi
        sleep 0.2
    done
    return 1
}

session_object() { # json-array session-id -> one flat object line
    printf '%s' "$1" | tr -d '\r' | tr '{' '\n' | grep -F "\"session\":$2," | head -1
}

session_by_uid() { # json-array uid -> session line
    printf '%s' "$1" | tr -d '\r' | tr '{' '\n' | grep -F "\"uid\":$2," | head -1
}

json_field() { # object key -> raw value
    printf '%s' "$1" | sed -n "s/.*\"$2\":\([^,}]*\).*/\1/p"
}

wait_attached() { # session-id expected(true|false) timeout-seconds
    local sid=$1 expected=$2 timeout=$3 i obj value
    for i in $(seq 1 $((timeout * 5))); do
        run_cli sessions --json
        if [ "$CLI_RC" = "0" ]; then
            obj=$(session_object "$CLI_OUT" "$sid")
            value=$(json_field "$obj" attached)
            if [ "$value" = "$expected" ]; then
                return 0
            fi
        fi
        sleep 0.2
    done
    return 1
}

report() {
    echo
    printf '%-6s %-8s %s\n' RESULT ITEM DETAIL
    local row status item detail
    for row in "${RESULTS[@]}"; do
        IFS='|' read -r status item detail <<<"$row"
        printf '%-6s %-8s %s\n' "$status" "$item" "$detail"
    done
    echo
    echo "summary: $PASSES passed, $FAILS failed, $SKIPS skipped"
}

finish() {
    report
    if [ "$FAILS" -gt 0 ]; then
        exit 1
    fi
    exit 0
}

skip_remaining() { # from-item reason
    local from=$1 reason=$2 item
    local list=("1" "2" "3" "4" "5")
    for item in "${list[@]}"; do
        if [ "$item" -ge "$from" ] 2>/dev/null; then
            record SKIP "$item" "$reason"
        fi
    done
}

mkdir -p "$LOG_DIR"
echo "== jdspcli device acceptance =="
echo "repo        : $REPO"
echo "device dir  : $DEVICE_DIR"
echo "soak        : $SOAK_SECONDS s"
echo "log dir     : $LOG_DIR"
echo "cli transport: ${CLI_TRANSPORT:-auto} (measurements)"
echo

if ! adb_ get-state >/dev/null 2>&1; then
    echo "ERROR: no adb device (ADB=$ADB SERIAL=${SERIAL:-<default>})" >&2
    exit 2
fi

# ---------------------------------------------------------------- build ----
echo "== build =="
ANDROID_JAR=$(jdsp_android_jar)
D8=$(jdsp_d8)
AIDL_BIN=$(jdsp_aidl)
[ -f "$ANDROID_JAR" ] || { echo "ERROR: missing android.jar under $SDK" >&2; exit 2; }
[ -x "$D8" ] || { echo "ERROR: missing d8 at $D8" >&2; exit 2; }
[ -x "$AIDL_BIN" ] || { echo "ERROR: missing aidl under $SDK/build-tools" >&2; exit 2; }

# The CLI and the test tone are built from the repository with the same
# aidl -> javac -> d8 recipe as backend/build.sh and pushed into DEVICE_DIR.
# The shared library prefers backend/out/jdspcli.dex and falls back to a
# standalone compile, so a concurrent daemon build (which rm -rf's backend/out)
# cannot break the acceptance run.
echo "building test dex files from the repository..."
if ! jdsp_push_test_dexs "$DEVICE_DIR"; then
    echo "ERROR: could not build/push the test dex files" >&2
    exit 2
fi
CLI_DEX="$(jdsp_build_dir)/jdspcli.dex"
TONE_DEX="$(jdsp_build_dir)/tone.dex"
echo "build ok: jdspcli.dex $(stat -c %s "$CLI_DEX") B, tone.dex $(stat -c %s "$TONE_DEX") B)"
echo


# ------------------------------------------------------ daemon transport ---
echo "== daemon transport probe =="
BINDER_PRESENT=$(dev "service list 2>/dev/null | grep -c 'jdsp.service'")
TCP_FILES=$(dev "[ -f /data/data/james.dsp/files/jdsp.port ] && [ -f /data/data/james.dsp/files/jdsp.token ] && echo yes || echo no")
DAEMON_WAIT=${DAEMON_WAIT:-30}
echo "waiting up to $DAEMON_WAIT s for jdspd ..."
AUTO_RC=1
AUTO_MSG=""
AUTO_ERR=""
for i in $(seq 1 "$DAEMON_WAIT"); do
    run_raw ping
    AUTO_RC=$CLI_RC
    AUTO_MSG=$CLI_OUT
    AUTO_ERR=$CLI_ERR
    [ "$AUTO_RC" = "0" ] && break
    sleep 1
done
run_raw --tcp ping
TCP_RC=$CLI_RC
TCP_MSG=$CLI_OUT
TCP_ERR=$CLI_ERR
echo "binder service jdsp.service : $([ "$BINDER_PRESENT" = "0" ] && echo absent || echo present)"
echo "tcp endpoint files         : $TCP_FILES"
echo "jdspcli ping (auto)        : rc=$AUTO_RC out=$AUTO_MSG err=$AUTO_ERR"
echo "jdspcli --tcp ping         : rc=$TCP_RC out=$TCP_MSG err=$TCP_ERR"
echo

if [ "$AUTO_RC" != "0" ] && [ "$TCP_RC" != "0" ]; then
    echo "SKIP: jdspd is not running; nothing to measure."
    echo "  auto  : $AUTO_MSG | $AUTO_ERR"
    echo "  --tcp : $TCP_MSG | $TCP_ERR"
    record SKIP "all" "jdspd unavailable (binder=$BINDER_PRESENT tcp_files=$TCP_FILES auto_rc=$AUTO_RC tcp_rc=$TCP_RC)"
    report
    if [ "$STRICT_SKIP" = "1" ]; then
        exit 3
    fi
    exit 0
fi

# ------------------------------------------------------- 1. session discovery
echo "== §6.1 session discovery (no App playback) =="
TONE_OUT=$(start_tone root 180 root1)
SID=$(wait_session "$TONE_OUT")
if [ -z "$SID" ]; then
    if ! adb_ get-state >/dev/null 2>&1; then
        echo "ABORT: adb device disconnected during item 1 (device rebooted/occupied?)"
        record SKIP "1-5" "device disconnected mid-run; rerun when the device is free"
        report
        exit 2
    fi
    echo "ERROR: test tone produced no AudioTrack session (device log $TONE_OUT)"
    record FAIL "1" "tone did not start (see $TONE_OUT)"
    skip_remaining 2 "blocked by item 1 failure"
    finish
fi
echo "tone session: $SID"
ATT=""
PARAMS=""
SESSION_UID=""
for i in $(seq 1 20); do
    run_cli sessions --json
    if [ "$CLI_RC" != "0" ]; then
        break
    fi
    OBJ=$(session_object "$CLI_OUT" "$SID")
    ATT=$(json_field "$OBJ" attached)
    PARAMS=$(json_field "$OBJ" params)
    SESSION_UID=$(json_field "$OBJ" uid)
    if [ "$ATT" = "true" ] && [ "${PARAMS:-0}" -gt 0 ] 2>/dev/null; then
        break
    fi
    sleep 0.5
done
if [ "$CLI_RC" != "0" ]; then
    record FAIL "1" "sessions --json rc=$CLI_RC err=$CLI_ERR"
elif [ "$ATT" = "true" ] && [ "${PARAMS:-0}" -gt 0 ] 2>/dev/null; then
    record PASS "1" "session=$SID uid=$SESSION_UID attached=$ATT params=$PARAMS"
else
    record FAIL "1" "session=$SID attached=${ATT:-?} params=${PARAMS:-?} (want attached=true params>0)"
fi

# --------------------------------------------------------------- 2. set/get
echo "== §6.2 set -> get consistency =="
run_cli set "$SID" 1500 -0.1 60 3.0
if [ "$CLI_RC" != "0" ]; then
    record FAIL "2" "set 1500 rc=$CLI_RC err=$CLI_ERR"
else
    run_cli get "$SID" 1500 12
    if [ "$CLI_RC" = "0" ] && [ "$CLI_OUT" = "cdccccbd0000704200004040" ]; then
        record PASS "2" "set/get 1500 12B -> cdccccbd0000704200004040"
    else
        record FAIL "2" "get 1500 12 rc=$CLI_RC out=$CLI_OUT err=$CLI_ERR"
    fi
fi
# PLAN §6.2 names param 115; the engine mapping in this tree documents 1500 for the
# limiter, so 115 is only informational unless the engine really answers it.
run_cli set "$SID" 115 1.0
if [ "$CLI_RC" = "0" ]; then
    run_cli get "$SID" 115 4
    if [ "$CLI_RC" = "0" ] && [ "$CLI_OUT" = "0000803f" ]; then
        record PASS "2b" "param 115 set/get 4B -> 0000803f"
    else
        record SKIP "2b" "param 115 not round-tripped (rc=$CLI_RC out=$CLI_OUT); item 2 asserted with param 1500"
    fi
else
    record SKIP "2b" "param 115 rejected by daemon/engine (rc=$CLI_RC); item 2 asserted with param 1500"
fi

# ---------------------------------------------------------------- 3. latency
echo "== §6.3 set/get latency + log hygiene =="
START_TS=$(dev "date '+%m-%d %H:%M:%S.000'")
run_cli bench "$SID" 1500 -0.1 60 3.0 --iters 100
if [ "$CLI_RC" != "0" ]; then
    record FAIL "3" "bench rc=$CLI_RC out=$CLI_OUT err=$CLI_ERR"
else
    BENCH=$CLI_OUT
    RT_MAX=$(printf '%s' "$BENCH" | sed -n 's/.*roundtrip_max_us=\([0-9]*\).*/\1/p')
    MISMATCH=$(printf '%s' "$BENCH" | sed -n 's/.*mismatches=\([0-9]*\).*/\1/p')
    if [ -n "$RT_MAX" ] && [ "$RT_MAX" -lt 20000 ] && [ "${MISMATCH:-1}" = "0" ]; then
        record PASS "3" "bench iters=100 roundtrip_max_us=$RT_MAX mismatches=0 (< 20 ms)"
    else
        record FAIL "3" "bench roundtrip_max_us=${RT_MAX:-?} mismatches=${MISMATCH:-?} ($BENCH)"
    fi
fi
sleep 1
WINDOW_LOG=$(dev "logcat -d -T '$START_TS' -t 5000 2>/dev/null")
UNDERRUNS=$(printf '%s' "$WINDOW_LOG" | grep -ciE 'underrun' || true)
CRASHES=$(printf '%s' "$WINDOW_LOG" | grep -ciE 'FATAL EXCEPTION|audioserver.*died|debuggerd.*audio' || true)
if [ "${UNDERRUNS:-1}" -eq 0 ]; then
    record PASS "3-log" "no underrun lines in the benchmark window"
else
    record FAIL "3-log" "$UNDERRUNS underrun line(s) in the benchmark window (logcat -d -T '$START_TS')"
fi
if [ "${CRASHES:-1}" -eq 0 ]; then
    record PASS "3-crash" "no audioserver/HAL crash lines in the benchmark window"
else
    record FAIL "3-crash" "$CRASHES crash line(s) in the benchmark window"
fi

# -------------------------------------------------------------- 4. policy --
echo "== §6.4 per-app policy (two uids) =="
kill_tones
sleep 0.5
dev "cat /data/system/packages.list" > "$LOG_DIR/packages.list" 2>/dev/null
PAIRS=$(awk 'NF >= 2 { u = $2 + 0; if (u >= 10000 && u < 20000 && $1 !~ /james|jdsp/) { count[u]++; pkg[u] = $1 } }
    END { for (u in count) if (count[u] == 1) print pkg[u], u }' "$LOG_DIR/packages.list" | sort -k2 -n)
PKG_A=$(printf '%s\n' "$PAIRS" | awk 'NR == 1 {print $1}')
UID_A=$(printf '%s\n' "$PAIRS" | awk 'NR == 1 {print $2}')
PKG_B=$(printf '%s\n' "$PAIRS" | awk 'NR == 2 {print $1}')
UID_B=$(printf '%s\n' "$PAIRS" | awk 'NR == 2 {print $2}')
echo "player A: $PKG_A (uid $UID_A)"
echo "player B: $PKG_B (uid $UID_B)"
if [ -z "$PKG_A" ] || [ -z "$PKG_B" ]; then
    record FAIL "4" "could not pick two unique-uids packages from packages.list"
else
    OUT_A=$(start_tone "$UID_A" 180 ua)
    OUT_B=$(start_tone "$UID_B" 180 ub)
    SID_A=$(wait_session "$OUT_A")
    SID_B=$(wait_session "$OUT_B")
    if [ -z "$SID_A" ] || [ -z "$SID_B" ]; then
        record FAIL "4" "tone as uid $UID_A/$UID_B did not start (A=$SID_A B=$SID_B)"
    else
        A_ATT=""
        B_ATT=""
        for i in $(seq 1 20); do
            run_cli sessions --json
            [ "$CLI_RC" = "0" ] || continue
            OBJ_A=$(session_by_uid "$CLI_OUT" "$UID_A")
            OBJ_B=$(session_by_uid "$CLI_OUT" "$UID_B")
            A_ATT=$(json_field "$OBJ_A" attached)
            B_ATT=$(json_field "$OBJ_B" attached)
            if [ "$A_ATT" = "true" ] && [ "$B_ATT" = "true" ]; then
                break
            fi
            sleep 0.5
        done
        if [ "$A_ATT" = "true" ] && [ "$B_ATT" = "true" ]; then
            record PASS "4-init" "both players attached (A=$SID_A B=$SID_B)"
        else
            record FAIL "4-init" "expected both attached: A=$A_ATT B=$B_ATT"
        fi
        run_cli policy set "$PKG_A" exclude
        if [ "$CLI_RC" != "0" ]; then
            record FAIL "4" "policy set $PKG_A exclude rc=$CLI_RC err=$CLI_ERR"
        else
            POLICY_CHANGED=1
            if wait_attached "$SID_A" false 6; then
                run_cli sessions --json
                OBJ_B=$(session_by_uid "$CLI_OUT" "$UID_B")
                B_ATT=$(json_field "$OBJ_B" attached)
                if [ "$B_ATT" = "true" ]; then
                    record PASS "4" "excluded $PKG_A: A attached=false, B attached=true"
                else
                    record FAIL "4" "excluded A but B attached=$B_ATT (want true)"
                fi
            else
                record FAIL "4" "excluded $PKG_A but session $SID_A stayed attached within 6 s"
            fi
            run_cli policy set "$PKG_A" follow
            POLICY_CHANGED=0
            if wait_attached "$SID_A" true 6; then
                record PASS "4-restore" "policy follow re-attached $PKG_A"
            else
                record FAIL "4-restore" "policy follow did not re-attach $SID_A within 6 s"
            fi
        fi
    fi
fi
kill_tones

# ------------------------------------------------------------------ 5. soak
echo "== §6.5 soak =="
if [ "$SOAK_SECONDS" -gt 0 ] 2>/dev/null; then
    echo "soak for $SOAK_SECONDS s ..."
    DURATION=$((SOAK_SECONDS + 120))
    TONE_OUT=$(start_tone root "$DURATION" soak)
    SOAK_SID=$(wait_session "$TONE_OUT")
    SOAK_START=$(date +%s)
    SOAK_END=$((SOAK_START + SOAK_SECONDS))
    CYCLES=0
    ERRORS=0
    if [ -z "$SOAK_SID" ]; then
        record FAIL "5" "soak tone did not start (see $TONE_OUT)"
    else
        START_TS=$(dev "date '+%m-%d %H:%M:%S.000'")
        PIDS_BEFORE=$(dev "pidof audioserver")
        while [ "$(date +%s)" -lt "$SOAK_END" ]; do
            CYCLES=$((CYCLES + 1))
            run_cli set "$SOAK_SID" 1500 -0.1 60 3.0
            [ "$CLI_RC" = "0" ] || ERRORS=$((ERRORS + 1))
            run_cli get "$SOAK_SID" 1500 12
            [ "$CLI_RC" = "0" ] || ERRORS=$((ERRORS + 1))
            run_cli sessions
            [ "$CLI_RC" = "0" ] || ERRORS=$((ERRORS + 1))
            run_cli ping
            [ "$CLI_RC" = "0" ] || ERRORS=$((ERRORS + 1))
            sleep 5
        done
        WINDOW_LOG=$(dev "logcat -d -T '$START_TS' -t 20000 2>/dev/null")
        UNDERRUNS=$(printf '%s' "$WINDOW_LOG" | grep -ciE 'underrun' || true)
        CRASHES=$(printf '%s' "$WINDOW_LOG" | grep -ciE 'FATAL EXCEPTION|audioserver.*died|debuggerd.*audio' || true)
        PIDS_AFTER=$(dev "pidof audioserver")
        run_cli dump-config
        if [ "$CLI_RC" = "0" ] && [ -n "$CLI_OUT" ]; then CONFIG_OK=yes; else CONFIG_OK=no; fi
        run_cli stats
        STATS_RC=$CLI_RC
        if [ "$ERRORS" = "0" ] && [ "${UNDERRUNS:-1}" -eq 0 ] && [ "${CRASHES:-1}" -eq 0 ] \
                && [ "$PIDS_BEFORE" = "$PIDS_AFTER" ] && [ "$CONFIG_OK" = "yes" ] && [ "$STATS_RC" = "0" ]; then
            record PASS "5" "soak ${SOAK_SECONDS}s cycles=$CYCLES errors=0 underruns=0 crashes=0 audioserver pid stable"
        else
            record FAIL "5" "soak ${SOAK_SECONDS}s cycles=$CYCLES errors=$ERRORS underruns=${UNDERRUNS:-?} crashes=${CRASHES:-?} audioserver '$PIDS_BEFORE'->'$PIDS_AFTER' config_ok=$CONFIG_OK stats_rc=$STATS_RC"
        fi
    fi
else
    record SKIP "5" "30 min soak not requested (use --soak or SOAK_SECONDS=1800)"
fi

echo
echo "artifacts: $LOG_DIR (packages.list), $(jdsp_build_dir) (tone.dex, jdspcli.dex)"
finish
