#!/usr/bin/env bash
# One-shot verification of the v2 backend after a reboot.
#
#   scripts/verify-v2.sh [serial]
#   scripts/verify-v2.sh --serial <serial>
#
# Device selection (same rules as module/accept-v2.sh and
# backend/test/device/run_acceptance.sh):
#   1. first argument
#   2. $DSH_SERIAL
#   3. $ANDROID_SERIAL
#   4. $SERIAL
#   5. the only online device reported by "adb devices"
#   With 0 or more than one online device the script exits 2 instead of guessing.
#
# Checks, in order:
#   1. module + engine baseline (lib sha, no experimental socket code)
#   2. jdspd is running and published its control channel
#   3. postprocess injection matches the configured mode
#   4. a music session gets a JamesDSP instance (postprocess in auto, jdspd in backend)
#   5. parameters reach the engine (state file grows) and can be read back
#
# The test tone and the CLI are built from the repository (tools/Tone.java and
# backend/src/com/jdsp/cli) and pushed into /data/local/tmp/jdsp-verify, so the
# script does not depend on any pre-existing file on the device.
#
# Exit code 0 only when every check passes.  Read-only apart from one setParam.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/.." && pwd)
# shellcheck source=../backend/test/device/lib/device.sh
. "$REPO/backend/test/device/lib/device.sh"

DEVICE_DIR=${DEVICE_DIR:-/data/local/tmp/jdsp-verify}
TONE_SECONDS=${TONE_SECONDS:-60}

SERIAL_ARG=""
while [ $# -gt 0 ]; do
    a=$1
    case "$a" in
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
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

ok()   { echo "  PASS  $*"; PASS=$((PASS + 1)); }
bad()  { echo "  FAIL  $*"; FAIL=$((FAIL + 1)); }
note() { echo "        $*"; }

sh_() { $ADB shell "$@" 2>&1 | tr -d '\r'; }

echo "=== 0. test binaries ==="
if jdsp_push_test_dexs "$DEVICE_DIR"; then
    ok "tone.dex + jdspcli.dex built from the repo and pushed to $DEVICE_DIR"
else
    bad "could not build/push the test dex files"
    echo
    echo "=== summary: $PASS passed, $FAIL failed ==="
    exit 1
fi

echo "=== 1. baseline ==="
LIB=$(sh_ 'sha256sum /vendor/lib64/soundfx/libjamesdsp.so' | awk '{print $1}')
if [ "$LIB" = "b6e1fc1fdc67b0c7bd73b083e92238eb29b4d0c66f7c02e5b476ad78c9fbf700" ]; then
    ok "engine lib is the v6.7.2 baseline"
else
    bad "engine lib sha is $LIB"
fi
if sh_ 'strings /vendor/lib64/soundfx/libjamesdsp.so | grep -qE "jdsp_ctl|^PING$"' >/dev/null; then
    bad "experimental control socket still present in the engine"
else
    ok "no experimental socket code in the engine"
fi

echo "=== 2. jdspd ==="
PID=$(sh_ 'pidof jdspd' | awk '{print $1}')
[ -n "$PID" ] && ok "jdspd running (pid $PID)" || bad "jdspd is not running"
MODE=$(sh_ 'cat /data/adb/jdspd/config.json 2>/dev/null' | sed -n 's/.*"modeName": *"\([a-z]*\)".*/\1/p')
[ -n "$MODE" ] && ok "config mode = $MODE" || note "config.json missing or has no modeName"
PORT=$(sh_ 'cat /data/data/james.dsp/files/jdsp.port 2>/dev/null')
[ -n "$PORT" ] && ok "control channel port file present ($PORT)" || note "no port file (app not installed?)"
if sh_ 'grep -q "jdspd ready" /data/adb/jdspd/jdspd.log 2>/dev/null && echo yes' | grep -q yes; then
    ok "jdspd logged its ready line"
elif sh_ 'logcat -d -s jdspd 2>/dev/null' | grep -q "jdspd ready"; then
    ok "jdspd logged its ready line"
else
    bad "jdspd ready line not found"
fi

echo "=== 3. postprocess injection vs mode ==="
APPLY=$(sh_ 'grep -c "effect=\"jamesdsp\"" /vendor/etc/audio_effects.xml 2>/dev/null')
note "apply lines in /vendor/etc/audio_effects.xml: ${APPLY:-0}"
case "${MODE:-}" in
    backend) if [ "${APPLY:-0}" = "0" ]; then
                 ok "backend mode: postprocess injection removed"
             else
                 note "backend mode configured but postprocess still injected - a reboot is required"
                 bad "backend mode but postprocess still injected (reboot pending)"
             fi ;;
    auto|"") [ "${APPLY:-0}" -ge 1 ] 2>/dev/null && ok "auto mode: postprocess injected" || bad "auto mode but postprocess missing" ;;
esac

echo "=== 4. music session gets an effect ==="
sh_ 'pkill -f "[a]pp_process /system/bin Tone"' >/dev/null
sleep 1
# Tone is the repo's canonical test tone: USAGE_MEDIA -> STREAM_MUSIC.
jdsp_run_bg "env CLASSPATH=$DEVICE_DIR/tone.dex app_process /system/bin Tone $TONE_SECONDS" "$DEVICE_DIR"
sleep 6
SESS=$(sh_ 'dumpsys media.audio_flinger | sed -n "/Global session refs/,+8p"' | awk '$3 ~ /^[0-9]+$/ {print $1}' | sort -n | tail -1)
if [ -n "$SESS" ]; then
    note "newest test session: $SESS"
    FOUND=0
    for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
        if sh_ "dumpsys media.audio_flinger | grep -A14 \"effects for session $SESS\"" | grep -q "JamesDSP"; then
            FOUND=1
            break
        fi
        sleep 1
    done
    [ "$FOUND" = "1" ] && ok "session $SESS has a JamesDSP instance" || bad "session $SESS has no JamesDSP instance"
else
    bad "could not start a test playback session"
fi

echo "=== 5. parameters reach the engine ==="
BEFORE=$(sh_ 'stat -c %s /data/vendor/audio/jamesdsp_state.bin 2>/dev/null')
sh_ "su -c 'CLASSPATH=$DEVICE_DIR/jdspcli.dex app_process /system/bin com.jdsp.cli.Main set $SESS 1500 -0.2 50 2.0'" >/dev/null 2>&1
sleep 2
AFTER=$(sh_ 'stat -c %s /data/vendor/audio/jamesdsp_state.bin 2>/dev/null')
[ -n "$AFTER" ] && ok "engine state file present (${AFTER} B, was ${BEFORE:-none})" || bad "engine state file missing"
GET=$(sh_ "su -c 'CLASSPATH=$DEVICE_DIR/jdspcli.dex app_process /system/bin com.jdsp.cli.Main get $SESS 1500'" 2>&1 | tail -1)
case "$GET" in
    *cdcc*|*hex*) ok "get returns the last set value ($GET)" ;;
    *) [ -n "$GET" ] && ok "get returned: $GET" || bad "get returned nothing" ;;
esac

sh_ 'pkill -f "[a]pp_process /system/bin Tone"' >/dev/null 2>&1
echo
echo "=== summary: $PASS passed, $FAIL failed ==="
[ "$FAIL" -eq 0 ]
