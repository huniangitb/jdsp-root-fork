#!/usr/bin/env bash
# UI smoke: open every entry of every screen the App offers and fail on a crash.
#
# Why this exists: the 1.6.21 build shipped a preference screen that threw while it was inflated
# (FileLibraryPreference.onSetInitialValue on a stored absolute path while the external files dir
# was unavailable), and afterwards the App could not be opened at all - the crash happened while
# restoring the last screen, before any UI was drawn. The 1.6.21 acceptance launched the App and
# opened the DSP screens by hand, so the screen that threw was never inflated. This script walks
# the tree instead: every label it can see is tapped, and the run fails on the first crash.
#
# Taps land on labels, so a switch row may get toggled; the script snapshots shared_prefs first and
# restores them at the end (needs root, which the module install requires anyway).
#
# Usage: scripts/ui_smoke.sh [-d <serial>] [-n <max taps>] [-t <x,y>]...
#   -t  tap this point before crawling (repeatable), e.g. the settings gear of the main screen
#       which carries no label of its own
# Exit: 0 no crash, 1 crash, 2 the App could not be started.
set -u

SERIAL_ARG=""
MAX_TAPS=20
PRE_TAPS=""
while [ $# -gt 0 ]; do
    case "$1" in
        -d) SERIAL_ARG="-s $2"; shift 2 ;;
        -n) MAX_TAPS="$2"; shift 2 ;;
        -t) PRE_TAPS="$PRE_TAPS $2"; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

PKG=james.dsp
ACT="$PKG/me.timschneeberger.rootlessjamesdsp.activity.MainActivity"
PREFS="/data/data/$PKG/shared_prefs"
DUMP=/sdcard/ui-smoke.xml
LOCAL=${TMPDIR:-/tmp}/ui-smoke-$$.xml
TAPS=0
OPENED=0

adb_() { adb $SERIAL_ARG "$@"; }
say() { printf '%s\n' "$*"; }
sh_() { adb_ shell su -c "$1" 2>/dev/null; }

crash() { adb_ shell "logcat -d -b crash" 2>/dev/null | grep -q "$PKG"; }
alive() { [ -n "$(adb_ shell "pidof $PKG" 2>/dev/null | tr -d '\r')" ]; }

dump() {
    adb_ shell "uiautomator dump $DUMP >/dev/null 2>&1" >/dev/null 2>&1
    adb_ pull "$DUMP" "$LOCAL" >/dev/null 2>&1
    [ -s "$LOCAL" ]
}

# "x|y|label" for every labelled node of the app; rows carry their label in a child view, so the
# label's own bounds are what gets tapped (that is how a user opens a row as well).
entries() {
    sed 's/></>\n</g' "$LOCAL" \
        | grep 'package="'"$PKG"'"' \
        | grep -v 'class="android.widget.SeekBar"' \
        | grep -v 'class="android.widget.EditText"' \
        | sed -n 's/.*text="\([^"]\{1,\}\)".*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\2 \3 \4 \5 \1/p' \
        | awk '{x=($1+$3)/2; y=($2+$4)/2; label=substr($0, index($0,$5)); if (label ~ /^-?[0-9]/) next; if (!(label in seen)) { seen[label]=1; printf "%d|%d|%s\n", x, y, label }}'
    # Icons and rows without a label of their own (the settings gear, a device card) are entries
    # too; a minimum size keeps padding and dividers out.
    sed 's/></>\n</g' "$LOCAL" \
        | grep 'package="'"$PKG"'"' \
        | grep 'clickable="true"' \
        | grep 'text=""' \
        | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' \
        | awk '{w=$3-$1; h=$4-$2; if (w < 60 || h < 60) next; printf "%d|%d|icon %dx%d\n", ($1+$3)/2, ($2+$4)/2, w, h}'
}

sh_ "rm -rf /data/local/tmp/ui-smoke-prefs; cp -a $PREFS /data/local/tmp/ui-smoke-prefs" >/dev/null
adb_ shell "am force-stop $PKG" >/dev/null 2>&1
adb_ shell "logcat -b crash -c" >/dev/null 2>&1
adb_ shell "am start -n $ACT" >/dev/null 2>&1 || exit 2
sleep 4
alive || { say "FAIL the App did not start"; exit 2; }
crash && { say "FAIL crash on start"; adb_ shell "logcat -d -b crash" | tail -30; exit 1; }
say "OK   cold start"

for point in $PRE_TAPS; do
    adb_ shell "input tap ${point%%,*} ${point##*,}" >/dev/null 2>&1
    TAPS=$((TAPS + 1))
    sleep 2
    crash && { say "FAIL crash after tapping $point"; adb_ shell "logcat -d -b crash" | tail -30; exit 1; }
    alive || { say "FAIL the App died after tapping $point"; exit 1; }
    say "OK   tapped $point"
done

for depth in 1 2 3; do
    dump || { say "WARN no dump at depth $depth"; break; }
    mapfile -t ROWS < <(entries)
    [ "${#ROWS[@]}" -eq 0 ] && { say "WARN no labels at depth $depth"; break; }
    for row in "${ROWS[@]}"; do
        [ "$TAPS" -ge "$MAX_TAPS" ] && break 3
        x=${row%%|*}; rest=${row#*|}; y=${rest%%|*}; label=${rest#*|}
        adb_ shell "input tap $x $y" >/dev/null 2>&1
        TAPS=$((TAPS + 1))
        sleep 2
        if crash; then
            say "FAIL crash after tapping '$label'"
            adb_ shell "logcat -d -b crash" | tail -30
            sh_ "rm -rf $PREFS; cp -a /data/local/tmp/ui-smoke-prefs $PREFS" >/dev/null
            exit 1
        fi
        alive || { say "FAIL the App died after tapping '$label'"; exit 1; }
        OPENED=$((OPENED + 1))
        say "OK   tapped '$label'"
        adb_ shell "input keyevent 4" >/dev/null 2>&1
        sleep 1
        dump >/dev/null 2>&1
    done
done

sh_ "rm -rf $PREFS; cp -a /data/local/tmp/ui-smoke-prefs $PREFS" >/dev/null
say "PASS $OPENED label(s) tapped, no crash"
exit 0
