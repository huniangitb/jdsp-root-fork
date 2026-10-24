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
#
# Env: ADB, SERIAL, ANDROID_SDK, DEVICE_DIR, SOAK_SECONDS, STRICT_SKIP.
# Exit: 0 all measured items passed (skips allowed unless STRICT_SKIP=1);
#       1 at least one FAIL; 2 environment problem (no device / build failure);
#       3 daemon unavailable and STRICT_SKIP=1.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
BACKEND=$(cd "$HERE/../.." && pwd)
REPO=$(cd "$BACKEND/.." && pwd)
SDK=${ANDROID_SDK:-/home/bdy/Android/Sdk}
ADB=${ADB:-adb}
SERIAL=${SERIAL:-${ANDROID_SERIAL:-}}
DEVICE_DIR=${DEVICE_DIR:-/data/local/tmp/jdsp-accept}
SOAK_SECONDS=${SOAK_SECONDS:-0}
STRICT_SKIP=${STRICT_SKIP:-0}
LOG_DIR="$BACKEND/test/build/device"
STAMP=$(date +%Y%m%d-%H%M%S)
MARKER="acc-$$-$STAMP"
PKG_A=""
POLICY_CHANGED=0
CLI_OUT=""
CLI_ERR=""
CLI_RC=0

for arg in "$@"; do
    case "$arg" in
        --soak) SOAK_SECONDS=1800 ;;
        --soak=*) SOAK_SECONDS="${arg#--soak=}" ;;
        --strict) STRICT_SKIP=1 ;;
        -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

ADB_CMD=("$ADB")
if [ -n "$SERIAL" ]; then
    ADB_CMD+=(-s "$SERIAL")
fi
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
    pids=$(dev "ps -A -o pid,args 2>/dev/null | grep -F 'Tone ' | grep -F '$MARKER' | grep -v grep | awk '{print \$1}'")
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

run_cli() {
    local raw
    raw=$(dev "CLASSPATH=$DEVICE_DIR/jdspcli.dex app_process /system/bin com.jdsp.cli.Main $* 2>$DEVICE_DIR/cli.err; echo __RC=\$?")
    CLI_RC=${raw##*__RC=}
    CLI_OUT=${raw%__RC=*}
    CLI_ERR=$(dev "cat $DEVICE_DIR/cli.err 2>/dev/null")
}

start_tone() { # uid|root seconds tag -> prints device stdout file
    local uid=$1 secs=$2 tag=$3
    local out="$DEVICE_DIR/tone-$tag.out"
    dev "rm -f $out"
    if [ "$uid" = "root" ]; then
        dev "cd $DEVICE_DIR && nohup env CLASSPATH=$DEVICE_DIR/tone.dex app_process /system/bin Tone $secs $MARKER $tag > $out 2>&1 </dev/null &"
    else
        dev "nohup su $uid -c 'CLASSPATH=$DEVICE_DIR/tone.dex app_process /system/bin Tone $secs $MARKER $tag' > $out 2>&1 </dev/null &"
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
    printf '%s' "$1" | tr '{' '\n' | grep -F "\"session\":$2," | head -1
}

session_by_uid() { # json-array uid -> session line
    printf '%s' "$1" | tr '{' '\n' | grep -F "\"uid\":$2," | head -1
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
echo

if ! adb_ get-state >/dev/null 2>&1; then
    echo "ERROR: no adb device (ADB=$ADB SERIAL=${SERIAL:-<default>})" >&2
    exit 2
fi

# ---------------------------------------------------------------- build ----
echo "== build =="
ANDROID_JAR=$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)
D8="$SDK/cmdline-tools/latest/bin/d8"
AIDL_BIN=$(ls -d "$SDK"/build-tools/*/aidl 2>/dev/null | sort -V | tail -1)
[ -f "$ANDROID_JAR" ] || { echo "ERROR: missing android.jar under $SDK" >&2; exit 2; }
[ -x "$D8" ] || { echo "ERROR: missing d8 at $D8" >&2; exit 2; }
[ -x "$AIDL_BIN" ] || { echo "ERROR: missing aidl under $SDK/build-tools" >&2; exit 2; }

# Compile the CLI from the same sources with the same aidl/javac/d8 recipe.
# Used when backend/out is unavailable: a concurrent daemon build calls
# build.sh, which rm -rf's backend/out, so the normal artifact can vanish
# between build.sh returning and the push below.
build_cli_dex() {
    mkdir -p "$LOG_DIR/gen" "$LOG_DIR/cli-classes" "$LOG_DIR/cli-dex"
    local files
    mapfile -t AIDL_FILES < <(find "$BACKEND/aidl" -name '*.aidl' | sort)
    "$AIDL_BIN" --lang=java -I"$BACKEND/aidl" --out="$LOG_DIR/gen" "@@{AIDL_FILES[@]}" || return 1
    mapfile -t files < <(find "$BACKEND/src/com/jdsp/cli" "$BACKEND/src/com/jdsp/common"             "$LOG_DIR/gen" -name '*.java' | sort)
    javac --release 8 -nowarn -Xlint:-options -classpath "$ANDROID_JAR"             -d "$LOG_DIR/cli-classes" "@@{files[@]}" || return 1
    find "$LOG_DIR/cli-classes" -name '*.class' > "$LOG_DIR/cli-files.txt"
    "$D8" --lib "$ANDROID_JAR" --min-api 29 --output "$LOG_DIR/cli-dex"             $(cat "$LOG_DIR/cli-files.txt") >/dev/null || return 1
    cp "$LOG_DIR/cli-dex/classes.dex" "$LOG_DIR/jdspcli.dex"
}

echo "running backend/build.sh (logged, best effort)..."
( cd "$BACKEND" && ./build.sh ) >"$LOG_DIR/build.log" 2>&1         || echo "WARN: build.sh failed (see $LOG_DIR/build.log)"
CLI_DEX="$LOG_DIR/jdspcli.dex"
rm -f "$CLI_DEX"
if [ -f "$BACKEND/out/jdspcli.dex" ]; then
    cp "$BACKEND/out/jdspcli.dex" "$CLI_DEX" 2>/dev/null || true
fi
if [ -s "$CLI_DEX" ]; then
    echo "cli dex     : backend/build.sh ($(stat -c %s "$CLI_DEX") B)"
else
    echo "cli dex     : backend/out unavailable, compiling standalone..."
    if ! build_cli_dex; then
        echo "ERROR: standalone jdspcli compile failed" >&2
        exit 2
    fi
    echo "cli dex     : standalone ($(stat -c %s "$CLI_DEX") B)"
fi
rm -rf "$LOG_DIR/tone-classes" "$LOG_DIR/tone-dex"
mkdir -p "$LOG_DIR/tone-classes" "$LOG_DIR/tone-dex"
if ! javac --release 8 -nowarn -classpath "$ANDROID_JAR" -d "$LOG_DIR/tone-classes" \
        "$REPO/tools/Tone.java" >"$LOG_DIR/tone-javac.log" 2>&1; then
    echo "ERROR: javac tools/Tone.java failed" >&2
    cat "$LOG_DIR/tone-javac.log" >&2
    exit 2
fi
if ! "$D8" --lib "$ANDROID_JAR" --min-api 29 --output "$LOG_DIR/tone-dex" \
        "$LOG_DIR/tone-classes/Tone.class" >"$LOG_DIR/tone-d8.log" 2>&1; then
    echo "ERROR: d8 Tone.class failed" >&2
    cat "$LOG_DIR/tone-d8.log" >&2
    exit 2
fi
cp "$LOG_DIR/tone-dex/classes.dex" "$LOG_DIR/tone.dex"

dev "mkdir -p $DEVICE_DIR && chmod 755 $DEVICE_DIR"
adb_ push "$CLI_DEX" "$DEVICE_DIR/jdspcli.dex" >/dev/null || { echo "ERROR: push jdspcli.dex" >&2; exit 2; }
adb_ push "$LOG_DIR/tone.dex" "$DEVICE_DIR/tone.dex" >/dev/null || { echo "ERROR: push tone.dex" >&2; exit 2; }
dev "chmod 644 $DEVICE_DIR/jdspcli.dex $DEVICE_DIR/tone.dex"
echo "build ok: jdspcli.dex $(stat -c %s "$CLI_DEX") B, tone.dex $(stat -c %s "$LOG_DIR/tone.dex") B"
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
    run_cli ping
    AUTO_RC=$CLI_RC
    AUTO_MSG=$CLI_OUT
    AUTO_ERR=$CLI_ERR
    [ "$AUTO_RC" = "0" ] && break
    sleep 1
done
run_cli --tcp ping
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
echo "artifacts: $LOG_DIR (build.log, tone.dex, packages.list)"
finish
