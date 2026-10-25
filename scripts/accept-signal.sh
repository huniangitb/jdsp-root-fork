#!/usr/bin/env bash
# accept-signal.sh — signal level acceptance for JamesDSP v2.
#
#   scripts/accept-signal.sh [serial]
#   scripts/accept-signal.sh --serial <serial>
#
# Device selection follows the same rules as scripts/verify-v2.sh and
# backend/test/device/run_acceptance.sh:
#   1. first positional argument / --serial
#   2. $DSH_SERIAL
#   3. $ANDROID_SERIAL
#   4. $SERIAL
#   5. the only online device reported by "adb devices"
#   With 0 or more than one online device the script exits 2 instead of guessing.
#
# The script works on any device: nothing about the serial, the Android
# version or the model is hard coded.  It is safe to run it on the new device
# and on the old one back to back.
#
# What it proves
# --------------
# The clean v6.7.2 engine has no GET_PARAM, so "the parameter was written" is
# not evidence that the effect is audible.  This script therefore proves the
# effect at the *signal* level, using the vendor engine itself:
#
#   1. capability probe: can AudioRecord(REMOTE_SUBMIX) capture the output
#      mix at all?  (initialise, data, all-zero?)
#   2. when the capture path is usable, the captured signal is measured while
#      the parameter under test is changed, and the measurement must move in
#      the expected direction.
#   3. when the capture path is NOT usable (which is the case on the OnePlus
#      OPD2404: REMOTE_SUBMIX initialises and returns data, but every sample
#      is zero), the script falls back to *offline rendering* of the very same
#      vendor library:
#        - the engine library is loaded with dlopen(),
#        - an effect instance is created through its exported EffectCreate(),
#        - the parameter under test is applied through EFFECT_CMD_SET_PARAM,
#        - the identical PCM block is rendered twice (neutral vs. the value
#          under test) and the two outputs are compared by RMS, peak and a
#          440 Hz Goertzel magnitude.
#      The offline render is driven from the *live* engine state file
#      (/data/vendor/audio/jamesdsp_state.bin), so the measurement reflects
#      exactly the configuration the device is running.
#
# The parameter that is used is 1500 (limiter threshold / release / post gain
# in dB).  It is the parameter the engine documents, it is accepted by the
# clean engine and its effect is trivially predictable in sign: the post gain
# scales the whole output, so -3 dB must lower the output by 3 dB and -12 dB
# must lower it by 12 dB relative to the baseline.
#
# Every parameter change is reversible and the script always restores the
# original value before it exits (also on error).
#
# Exit code 0 only when every check passes.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/.." && pwd)
# shellcheck source=../backend/test/device/lib/device.sh
. "$REPO/backend/test/device/lib/device.sh"

# A private build directory: the shared backend/test/build tree is used by
# other acceptance scripts and by the daemon build, and a concurrent build
# there must not break this run.
JDSP_BUILD_DIR=${JDSP_BUILD_DIR:-${TMPDIR:-/tmp}/jdsp-accept-signal-build}
mkdir -p "$JDSP_BUILD_DIR"
jdsp_build_dir() { printf '%s\n' "$JDSP_BUILD_DIR"; }

DEVICE_DIR=${DEVICE_DIR:-/data/local/tmp/jdsp-accept-signal}
STATE_FILE=${STATE_FILE:-/data/vendor/audio/jamesdsp_state.bin}
CLI_DEX=${CLI_DEX:-$DEVICE_DIR/jdspcli.dex}
RENDER_BIN=${RENDER_BIN:-$DEVICE_DIR/offlinerender}
TONE_DEX=${TONE_DEX:-$DEVICE_DIR/tone.dex}

# The parameter under test: 1500 = limiter threshold, release, post gain (dB).
PARAM_ID=${PARAM_ID:-1500}
PARAM_THRESHOLD=${PARAM_THRESHOLD:--0.09}
PARAM_RELEASE=${PARAM_RELEASE:-50}
# The two post gain values the test uses.  The second one must be 9 dB lower
# than the first one, so the expected signal delta is exactly -9 dB.
GAIN_A=${GAIN_A:--3.0}
GAIN_B=${GAIN_B:--12.0}
EXPECT_DELTA_DB=${EXPECT_DELTA_DB:--9.0}
# A change is only accepted when it is at least this far away from zero.
MIN_DELTA_DB=${MIN_DELTA_DB:-3.0}
TONE_SECONDS=${TONE_SECONDS:-90}

SERIAL_ARG=""
while [ $# -gt 0 ]; do
    a=$1
    case "$a" in
        -h|--help) sed -n '2,45p' "$0"; exit 0 ;;
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
ADB="${JDSP_ADB_SN[*]}"

PASS=0
FAIL=0
SKIP=0

ok()   { echo "  PASS  $*"; PASS=$((PASS + 1)); }
bad()  { echo "  FAIL  $*"; FAIL=$((FAIL + 1)); }
skip() { echo "  SKIP  $*"; SKIP=$((SKIP + 1)); }
note() { echo "        $*"; }

sh_() { $ADB shell "$@" 2>&1 | tr -d '\r'; }

# ---------------------------------------------------------------- helpers --

# Run the CLI on the device: jdspcli <args...>
cli() {
    sh_ "CLASSPATH=$CLI_DEX app_process /system/bin com.jdsp.cli.Main --tcp $*"
}

# The offline renderer on the device.  Its stderr contains noise from the
# engine, so only stdout is used.
render() {
    sh_ "$RENDER_BIN $*"
}

# Extract a key=value field from a "KEY a=1 b=2" line.
field() {
    printf '%s\n' "$1" | tr ' ' '\n' | sed -n "s/^$2=//p" | head -1
}

# ------------------------------------------------------------------ setup --

echo "=== 0. test binaries ==="
if jdsp_push_test_dexs "$DEVICE_DIR"; then
    ok "jdspcli.dex built from the repo and pushed to $DEVICE_DIR"
else
    bad "could not build/push the test dex files"
    echo
    echo "=== summary: $PASS passed, $FAIL failed, $SKIP skipped ==="
    exit 1
fi
CLI_DEX="$DEVICE_DIR/jdspcli.dex"
TONE_DEX="$DEVICE_DIR/tone.dex"

# The offline renderer is a small native binary; it is built from
# tools/OfflineRender.c with the NDK and pushed to the device.
if [ -x "$RENDER_BIN" ] && sh_ "test -x $RENDER_BIN && echo yes" | grep -q yes; then
    ok "offline renderer present at $RENDER_BIN"
else
    note "offline renderer missing; building it from tools/OfflineRender.c"
    RENDER_SRC="$REPO/tools/OfflineRender.c"
    NDK=${ANDROID_NDK_HOME:-${NDK:-}}
    if [ -z "$NDK" ]; then
        for c in "$ANDROID_HOME/ndk" "$ANDROID_SDK/ndk" "$HOME/Android/Sdk/ndk"; do
            [ -d "$c" ] && NDK="$c" && break
        done
    fi
    CC=""
    # The NDK may be given as a versioned directory or as a directory that
    # holds several versions, so both layouts are probed.
    for base in "$NDK" "$NDK"/*; do
        [ -d "$base" ] || continue
        for host in linux-x86_64 linux-x86_64 darwin-x86_64; do
            cand="$base/toolchains/llvm/prebuilt/$host/bin/aarch64-linux-android26-clang"
            if [ -x "$cand" ]; then
                CC="$cand"
                break 2
            fi
        done
    done
    if [ -n "$CC" ] && [ -f "$RENDER_SRC" ]; then
        if "$CC" -O2 -o /tmp/jdsp-offlinerender "$RENDER_SRC" -ldl -lm 2>/tmp/jdsp-or-build.log; then
            $ADB push /tmp/jdsp-offlinerender "$RENDER_BIN" >/dev/null &&
                sh_ "chmod 755 $RENDER_BIN" >/dev/null &&
                ok "offline renderer built and pushed" ||
                bad "could not push the offline renderer"
        else
            note "renderer build failed:"
            sed 's/^/        /' /tmp/jdsp-or-build.log
            bad "offline renderer could not be built"
        fi
    else
        bad "no NDK / aarch64 clang available for tools/OfflineRender.c"
    fi
fi

echo
echo "=== 1. baseline ==="
LIB=$(sh_ 'sha256sum /vendor/lib64/soundfx/libjamesdsp.so' | awk '{print $1}')
if [ -n "$LIB" ]; then
    ok "engine library present (sha256 $LIB)"
else
    bad "engine library /vendor/lib64/soundfx/libjamesdsp.so not found"
fi
PID=$(sh_ 'pidof jdspd' | awk '{print $1}')
if [ -n "$PID" ]; then
    ok "jdspd running (pid $PID)"
else
    bad "jdspd is not running"
fi
if sh_ "test -f $STATE_FILE && echo yes" | grep -q yes; then
    ok "engine state file present ($STATE_FILE)"
else
    note "no engine state file yet ($STATE_FILE) - it is created on the first SET_PARAM"
fi

echo
echo "=== 2. signal capture capability (AudioRecord REMOTE_SUBMIX) ==="
# The probe uses tools/SignalProbe.java, built from the repository.
PROBE_DEX="$DEVICE_DIR/signalprobe.dex"
PROBE_HOST_DEX=""
B=/tmp/jdsp-sigprobe
rm -rf "$B"; mkdir -p "$B/c" "$B/d"
AJ=$(ls "${ANDROID_SDK:-${ANDROID_HOME:-$HOME/Android/Sdk}}"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)
D8=$(ls -d "${ANDROID_SDK:-${ANDROID_HOME:-$HOME/Android/Sdk}}"/build-tools/*/d8 2>/dev/null | sort -V | tail -1)
if [ -n "$AJ" ] && [ -n "$D8" ] &&
   javac --release 8 -nowarn -classpath "$AJ" -d "$B/c" "$REPO/tools/SignalProbe.java" 2>/dev/null &&
   find "$B/c" -name '*.class' > "$B/f" &&
   "$D8" --lib "$AJ" --min-api 29 --output "$B/d" $(cat "$B/f") >/dev/null 2>&1; then
    PROBE_HOST_DEX="$B/d/classes.dex"
fi
if [ -n "$PROBE_HOST_DEX" ]; then
    $ADB push "$PROBE_HOST_DEX" "$PROBE_DEX" >/dev/null &&
        sh_ "chmod 644 $PROBE_DEX" >/dev/null
fi
CAPTURE_MODE=offline
if [ -n "$PROBE_HOST_DEX" ] && sh_ "test -s $PROBE_DEX && echo yes" | grep -q yes; then
    PROBE_OUT=$(sh_ "CLASSPATH=$PROBE_DEX app_process /system/bin SignalProbe probe")
    note "$PROBE_OUT" | tr '\n' ' '
    echo
    if printf '%s' "$PROBE_OUT" | grep -q 'result=available'; then
        ok "REMOTE_SUBMIX capture is usable on this device"
        CAPTURE_MODE=capture
    else
        REASON=$(printf '%s' "$PROBE_OUT" | sed -n 's/.*reason=\([a-z_]*\).*/\1/p' | head -1)
        skip "REMOTE_SUBMIX capture is not usable (reason=${REASON:-unknown}); falling back to offline rendering"
        note "the offline renderer drives the very same vendor engine through"
        note "its exported effect API, so the effect is still measured at signal level."
    fi
else
    skip "could not build tools/SignalProbe.java; falling back to offline rendering"
fi

echo
echo "=== 3. offline renderer works ==="
if [ "$CAPTURE_MODE" = "offline" ]; then
    PROBE=$(render --probe 2>/dev/null | grep -E '^(ENGINE|RESULT)')
    echo "$PROBE" | sed 's/^/        /'
    if printf '%s' "$PROBE" | grep -q 'RESULT  ok'; then
        ok "the vendor engine loads and creates an effect instance"
    else
        bad "the vendor engine could not be loaded / instantiated"
        echo
        echo "=== summary: $PASS passed, $FAIL failed, $SKIP skipped ==="
        exit 1
    fi
fi

echo
echo "=== 4. a playback session with a JamesDSP instance ==="
sh_ 'pkill -f "[a]pp_process /system/bin Tone"' >/dev/null
sleep 1
jdsp_run_bg "env CLASSPATH=$TONE_DEX app_process /system/bin Tone $TONE_SECONDS" "$DEVICE_DIR"
sleep 6
SESS=""
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
    SESS=$(sh_ 'dumpsys media.audio_flinger | sed -n "/effects for session/,+3p" | grep -oE "effects for session [0-9]+" | grep -oE "[0-9]+" | sort -n | tail -1')
    if [ -n "$SESS" ] && [ "$SESS" != "0" ] &&
       sh_ "dumpsys media.audio_flinger | grep -A14 \"effects for session $SESS\"" | grep -q JamesDSP; then
        break
    fi
    sleep 1
done
if [ -n "$SESS" ] && [ "$SESS" != "0" ]; then
    ok "session $SESS carries a JamesDSP instance"
else
    bad "no playback session with a JamesDSP instance was found"
    sh_ 'pkill -f "[a]pp_process /system/bin Tone"' >/dev/null
    echo
    echo "=== summary: $PASS passed, $FAIL failed, $SKIP skipped ==="
    exit 1
fi

echo
echo "=== 4b. the measurement runs without the frontend ==="
# The frontend is stopped *before* the measurement, so everything below is
# measured on the backend alone.  The frontend is the component that writes
# the daemon master switch and its own parameter set, so leaving it running
# would make the measurement depend on the app.
APP_PID=$(sh_ 'pidof james.dsp' | awk '{print $1}')
if [ -n "$APP_PID" ]; then
    note "frontend james.dsp is running (pid $APP_PID); stopping it"
    sh_ 'am force-stop james.dsp' >/dev/null
    sleep 3
    if sh_ 'pidof james.dsp' | grep -q .; then
        bad "the frontend is still running after force-stop"
    else
        ok "the frontend is stopped, the rest of the run is headless"
    fi
else
    ok "the frontend was not running (already headless)"
fi

echo
echo "=== 5. the parameter reaches the engine and changes the signal ==="

# The global master switch of the daemon.  A parameter write is refused while
# it is off ("backend is globally disabled"), so the acceptance needs it on.
# The original state is remembered and restored at the end of the run.
ORIG_ENABLED=$(cli enabled 2>/dev/null | tail -1)
note "the daemon global master switch is: ${ORIG_ENABLED:-<unknown>}"
if [ "$ORIG_ENABLED" != "on" ]; then
    note "the master switch is off; turning it on for the acceptance"
    cli enabled on >/dev/null 2>&1
    sleep 2
    NOW_ENABLED=$(cli enabled 2>/dev/null | tail -1)
    if [ "$NOW_ENABLED" = "on" ]; then
        ok "the daemon master switch is on"
    else
        bad "the daemon master switch could not be turned on"
    fi
fi

# Remember the value that is currently configured, so it can be restored.
# The daemon's own "get" is preferred, but it only answers while the session
# still has a handle and it truncates to the requested vsize, so the
# configuration file is used as the authoritative fallback.  Either way the
# *full* value (12 bytes for param 1500) is needed, otherwise the restore would
# write a truncated parameter.
ORIG_HEX=$(cli get "$SESS" "$PARAM_ID" 12 2>/dev/null | tail -1)
if [ -z "$ORIG_HEX" ] || [ "$ORIG_HEX" = "none" ] ||
   ! printf '%s' "$ORIG_HEX" | grep -qE '^[0-9a-f]+$'; then
    ORIG_HEX=$(sh_ 'cat /data/adb/jdspd/config.json 2>/dev/null' |
        sed -n 's/.*"'"$PARAM_ID"'" *: *"\([0-9a-f]*\)".*/\1/p' | head -1)
fi
note "original value of $PARAM_ID: ${ORIG_HEX:-<none>}"

restore() {
    if [ -n "${ORIG_HEX:-}" ]; then
        note "restoring $PARAM_ID to the original value ($ORIG_HEX)"
        cli set "$SESS" "$PARAM_ID" --hex "$ORIG_HEX" >/dev/null 2>&1
    fi
    if [ -n "${ORIG_ENABLED:-}" ] && [ "$ORIG_ENABLED" != "on" ]; then
        note "restoring the daemon master switch to '$ORIG_ENABLED'"
        cli enabled "$ORIG_ENABLED" >/dev/null 2>&1
    fi
    sh_ 'pkill -f "[a]pp_process /system/bin Tone"' >/dev/null
}
trap restore EXIT

# The offline renderer reads the *live* engine state file, so the measurement
# follows whatever the daemon wrote into the engine.
SNAP_N=0
snapshot_state() {
    # The copy is verified on the *device*; the file name is unique per call so
    # that two snapshots are never confused with each other.
    SNAP_N=$((SNAP_N + 1))
    local dst="$DEVICE_DIR/state-snap-$SNAP_N.bin"
    sh_ "cp $STATE_FILE $dst" >/dev/null 2>&1
    if sh_ "test -s $dst && echo yes" | grep -q yes; then
        echo "$dst"
    else
        echo ""
    fi
}

# Render the given engine state with the parameter under test set to the given
# value.  The renderer replays the state file (so every other parameter of the
# device is exactly as it is) and then applies the parameter on top of it.
# Rendering the *same* state file with two different values of the parameter
# therefore isolates that parameter completely.
measure() {
    local value=$1
    local state=$2
    render --state "$state" --param "$PARAM_ID" "$PARAM_THRESHOLD" "$PARAM_RELEASE" "$value" 2>/dev/null |
        grep -E '^(NEUTRAL|TESTED|DELTA)'
}

# The "NEUTRAL" line of a measurement is the output level of the state file
# itself (before the parameter under test is applied on top of it).
level_of() {
    printf '%s' "$1" | grep '^NEUTRAL' | sed -n 's/.*rmsDbfs=\([-0-9.]*\).*/\1/p' | head -1
}

# The "DELTA" line is how much the parameter under test moved the signal.
delta_of() {
    printf '%s' "$1" | grep '^DELTA' | sed -n 's/.*rmsDb=\([-0-9.]*\).*/\1/p' | head -1
}

set_param() {
    local value=$1
    local out
    out=$(cli set "$SESS" "$PARAM_ID" "$PARAM_THRESHOLD" "$PARAM_RELEASE" "$value")
    printf '%s\n' "$out"
}

echo "  -- 5a. write post gain $GAIN_A --"
OUT=$(set_param "$GAIN_A")
echo "$OUT" | sed 's/^/        /'
if printf '%s' "$OUT" | grep -q '^ok '; then
    ok "the daemon accepted $PARAM_ID = $GAIN_A dB on session $SESS"
else
    bad "the daemon rejected $PARAM_ID = $GAIN_A dB on session $SESS"
fi
sleep 2
STATE_A=$(snapshot_state)
if [ -n "$STATE_A" ]; then
    ok "the engine persisted the new value (state file $STATE_A)"
else
    bad "the engine state file was not updated"
fi

echo "  -- 5b. write post gain $GAIN_B --"
OUT=$(set_param "$GAIN_B")
echo "$OUT" | sed 's/^/        /'
if printf '%s' "$OUT" | grep -q '^ok '; then
    ok "the daemon accepted $PARAM_ID = $GAIN_B dB on session $SESS"
else
    bad "the daemon rejected $PARAM_ID = $GAIN_B dB on session $SESS"
fi
sleep 2
STATE_B=$(snapshot_state)

if [ "$CAPTURE_MODE" = "offline" ]; then
    echo "  -- 5c. signal level --"
    #   MEAS_A: the state as it is after the daemon wrote $GAIN_A, rendered
    #           with $GAIN_A applied again.
    #           -> the delta must be 0 dB.  If it were not, the engine would
    #              not be running the value the daemon wrote.
    #   MEAS_B: the state as it is after the daemon wrote $GAIN_B, rendered
    #           with $GAIN_A applied.
    #           -> the delta must be (GAIN_A - GAIN_B), with the right sign.
    MEAS_A=$(measure "$GAIN_A" "$STATE_A")
    MEAS_B=$(measure "$GAIN_A" "$STATE_B")
    echo "$MEAS_A" | sed 's/^/        A /'
    echo "$MEAS_B" | sed 's/^/        B /'

    DELTA_A=$(delta_of "$MEAS_A")
    DELTA_B=$(delta_of "$MEAS_B")
    LEV_A=$(level_of "$MEAS_A")
    LEV_B=$(level_of "$MEAS_B")
    if [ -n "$DELTA_A" ] && [ -n "$DELTA_B" ]; then
        # 5c.1 the engine really runs the value the daemon wrote
        if awk -v d="$DELTA_A" -v t="0.2" 'BEGIN{x=d; if(x<0)x=-x; exit !(x<=t)}'; then
            ok "the engine runs exactly the value the daemon wrote ($GAIN_A dB): re-applying it changes nothing (${DELTA_A} dB)"
        else
            bad "the engine does not run the value the daemon wrote: re-applying $GAIN_A dB changed the signal by ${DELTA_A} dB"
        fi
        # 5c.2 the parameter has a large, correctly signed effect
        EXPECT_B=$(awk -v a="$GAIN_A" -v b="$GAIN_B" 'BEGIN{printf "%.2f", a-b}')
        if awk -v d="$DELTA_B" -v e="$EXPECT_B" -v t="0.5" 'BEGIN{diff=d-e; if(diff<0)diff=-diff; exit !(diff<=t)}'; then
            ok "the direction and the size of the change are correct (expected ${EXPECT_B} dB, measured ${DELTA_B} dB)"
        else
            bad "the signal change is ${DELTA_B} dB, expected ${EXPECT_B} dB"
        fi
        if awk -v d="$DELTA_B" -v m="$MIN_DELTA_DB" 'BEGIN{x=d; if(x<0)x=-x; exit !(x>=m)}'; then
            ok "the change is significant (|${DELTA_B}| dB >= ${MIN_DELTA_DB} dB)"
        else
            bad "the change is not significant (|${DELTA_B}| dB < ${MIN_DELTA_DB} dB)"
        fi
        # 5c.3 the two levels really differ (the signal, not just a number)
        if [ -n "$LEV_A" ] && [ -n "$LEV_B" ] &&
           awk -v a="$LEV_A" -v b="$LEV_B" 'BEGIN{d=a-b; if(d<0)d=-d; exit !(d>=1.0)}'; then
            ok "the measured output level differs between the two configurations (${LEV_A} dBFS vs ${LEV_B} dBFS)"
        else
            bad "the measured output level did not differ (${LEV_A:-?} dBFS vs ${LEV_B:-?} dBFS)"
        fi
    else
        bad "the offline renderer produced no measurement"
    fi
else
    echo "  -- 5c. signal level: capture the output mix --"
    CAP_A=$(sh_ "CLASSPATH=$DEVICE_DIR/signalprobe.dex app_process /system/bin SignalProbe capture 6 gainA")
    CAP_B=$(sh_ "CLASSPATH=$DEVICE_DIR/signalprobe.dex app_process /system/bin SignalProbe capture 6 gainB")
    echo "$CAP_A" | grep CAPTURE | sed 's/^/        A /'
    echo "$CAP_B" | grep CAPTURE | sed 's/^/        B /'
    R_A=$(printf '%s' "$CAP_A" | sed -n 's/.*rmsDbfs=\([-0-9.]*\).*/\1/p' | head -1)
    R_B=$(printf '%s' "$CAP_B" | sed -n 's/.*rmsDbfs=\([-0-9.]*\).*/\1/p' | head -1)
    if [ -n "$R_A" ] && [ -n "$R_B" ]; then
        D=$(awk -v a="$R_A" -v b="$R_B" 'BEGIN{printf "%.2f", a-b}')
        EXPECT_B=$(awk -v a="$GAIN_A" -v b="$GAIN_B" 'BEGIN{printf "%.2f", a-b}')
        if awk -v d="$D" -v e="$EXPECT_B" -v t="1.0" 'BEGIN{diff=d-e; if(diff<0)diff=-diff; exit !(diff<=t)}'; then
            ok "the captured signal moved by ${D} dB, as expected (${EXPECT_B} dB)"
        else
            bad "the captured signal moved by ${D} dB, expected ${EXPECT_B} dB"
        fi
    else
        bad "the capture produced no measurement"
    fi
fi

echo
echo "=== 6. restore returns to the baseline ==="
OUT=$(set_param "$GAIN_A")
echo "$OUT" | sed 's/^/        /'
sleep 2
STATE_R=$(snapshot_state)
if [ "$CAPTURE_MODE" = "offline" ]; then
    # The restored state must render exactly like the state that was captured
    # right after the first write of $GAIN_A.
    MEAS_R=$(measure "$GAIN_A" "$STATE_R")
    echo "$MEAS_R" | sed 's/^/        restored /'
    R_R=$(level_of "$MEAS_R")
    R_A=$(level_of "$MEAS_A")
    D_R=$(delta_of "$MEAS_R")
    if [ -n "$R_R" ] && [ -n "$R_A" ]; then
        D=$(awk -v a="$R_R" -v b="$R_A" 'BEGIN{printf "%.2f", a-b}')
        if awk -v d="$D" -v t="0.2" 'BEGIN{x=d; if(x<0)x=-x; exit !(x<=t)}'; then
            ok "after restoring, the signal is back at the baseline (${D} dB away from ${R_A} dBFS)"
        else
            bad "after restoring, the signal is ${D} dB away from the baseline"
        fi
    else
        bad "the restore could not be measured"
    fi
    if [ -n "$D_R" ] && awk -v d="$D_R" -v t="0.2" 'BEGIN{x=d; if(x<0)x=-x; exit !(x<=t)}'; then
        ok "after restoring, the engine runs the restored value again (${D_R} dB)"
    else
        bad "after restoring, the engine does not run the restored value (${D_R:-none} dB)"
    fi
fi

echo
echo "=== 7. headless: the backend works without the frontend ==="
if sh_ 'pidof james.dsp' | grep -q .; then
    bad "the frontend is running again during the headless phase"
    sh_ 'am force-stop james.dsp' >/dev/null
    sleep 2
else
    ok "the frontend is not running (the whole measurement was headless)"
fi

if sh_ 'pidof jdspd' | grep -q .; then
    ok "jdspd is still running without the frontend"
else
    bad "jdspd died with the frontend"
fi

# The daemon must still hold the session and still know the parameter.
SESSIONS=$(cli sessions)
echo "$SESSIONS" | sed 's/^/        /'
if printf '%s' "$SESSIONS" | awk -v s="$SESS" '$1==s && $4==1 {found=1} END{exit !found}'; then
    ok "the daemon still holds session $SESS (attached) without the frontend"
else
    bad "the daemon lost session $SESS after the frontend was stopped"
fi

# And the parameter must still be there.
HEX_AFTER=$(cli get "$SESS" "$PARAM_ID" 12 2>/dev/null | tail -1)
if [ -n "$HEX_AFTER" ] && [ "$HEX_AFTER" != "none" ]; then
    ok "the parameter is still readable through the daemon ($HEX_AFTER)"
else
    bad "the parameter is gone after the frontend was stopped"
fi

# The signal level effect must still hold.
if [ "$CAPTURE_MODE" = "offline" ]; then
    OUT=$(set_param "$GAIN_B")
    echo "$OUT" | sed 's/^/        /'
    sleep 2
    STATE_H=$(snapshot_state)
    MEAS_H=$(measure "$GAIN_A" "$STATE_H")
    echo "$MEAS_H" | sed 's/^/        headless /'
    DELTA_H=$(delta_of "$MEAS_H")
    EXPECT_B=$(awk -v a="$GAIN_A" -v b="$GAIN_B" 'BEGIN{printf "%.2f", a-b}')
    if [ -n "$DELTA_H" ] &&
       awk -v d="$DELTA_H" -v e="$EXPECT_B" -v t="0.5" 'BEGIN{diff=d-e; if(diff<0)diff=-diff; exit !(diff<=t)}'; then
        ok "the signal level effect still holds without the frontend (${DELTA_H} dB, expected ${EXPECT_B} dB)"
    else
        bad "the signal level effect does not hold without the frontend (measured ${DELTA_H:-none} dB, expected ${EXPECT_B} dB)"
    fi
fi

echo "=== 8. no underrun and no crash ==="
# The underrun counters appear in two places: the per track summary line
# ("numTracks=.. writeErrors=.. underruns=.. overruns=..") and the track
# table, whose "Underruns" column is the 4th column from the right of the
# data rows.  The table header is skipped, otherwise its "Underruns" label
# would look like a counter.
AF=$(sh_ 'dumpsys media.audio_flinger')
SUM=$(printf '%s' "$AF" | grep -oE 'underruns=[0-9]+' | sort -u | tr '\n' ' ')
note "audio_flinger underrun counters: ${SUM:-<none>}"
if printf '%s' "$SUM" | grep -qE 'underruns=[1-9]'; then
    bad "the audio path reported underruns ($SUM)"
else
    ok "no underrun was reported"
fi
if sh_ 'logcat -b crash -d -t 200' | grep -qiE 'jamesdsp|offlinerender|jdspd'; then
    bad "a crash of the engine, the daemon or the renderer is in the crash buffer"
    sh_ 'logcat -b crash -d -t 200' | grep -iE 'jamesdsp|offlinerender|jdspd' | head -10 | sed 's/^/        /'
else
    ok "no crash of the engine, the daemon or the renderer"
fi
if sh_ 'pidof jdspd' | grep -q .; then
    ok "jdspd survived the whole run"
else
    bad "jdspd is gone at the end of the run"
fi

echo
echo "=== summary: $PASS passed, $FAIL failed, $SKIP skipped ==="
[ "$FAIL" -eq 0 ]
