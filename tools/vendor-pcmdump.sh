#!/usr/bin/env bash
#
# vendor-pcmdump.sh - drive the ColorOS/OPPO vendor AudioFlinger PCM dump
# (OplusAudioPcmDump, the "AF34_EffectIn / AF35_EffectOut" WAVs) as an
# ACCEPTANCE / FORENSICS tool.  Not a product feature: it needs resetprop on a
# read-only property plus an audioserver restart.
#
#   ./vendor-pcmdump.sh on                    # open the gate, restart audioserver, mask=8
#   ./vendor-pcmdump.sh mask 8                # change the dump mask (bit3 = AF34/AF35)
#   ./vendor-pcmdump.sh capture 8 ./samples   # play the repo tone, pull + analyse the WAVs
#   ./vendor-pcmdump.sh off                   # mask 0, restore the ro properties, restart
#   ./vendor-pcmdump.sh status
#
# Mask bits (enum OplusAudioPcmDump; verified by disassembly of this ROM):
#   0x001 bit0 AF10_Track_output      0x010 bit4 AF40_ThreadWrite
#   0x002 bit1 AF20_Track_Resampled   0x040 bit6 AF50_ThreadFast_Write
#   0x004 bit2 AF31_Track_Mixed       0x080 bit7 AudioRecord_ThreadRead
#   0x008 bit3 AF34_Effect_In + AF35_Effect_Out     <-- the pair we want
#
# Gate (AudioFlingerExtImpl+0x600, read once in the constructor):
#   property_get_bool("ro.audio.voiplog.on", 0) && strstr(getprop("ro.build.version.ota"), "PRE")
#
# REQUIREMENTS
#   * root (KernelSU), resetprop on PATH (/data/adb/ksu/bin/resetprop).
#   * a free audioserver restart window: on/off each interrupt audio for ~1 s.
#   * FIXED MEDIA VOLUME: the dump point sits after the stream volume, so the
#     recorded level follows the volume.  The script records / fixes / restores
#     it so two runs stay comparable.
#
# Disk: 48000 Hz * 2 ch * 4 B = 384 kB/s per lane (~1.38 GB/h for both lanes).
set -uo pipefail

ADB=${ADB:-adb}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DUMP=/data/persist_log/TMP/pcm_dump
TMP=/data/local/tmp
EVID=${EVID:-$ROOT/out/tap/evidence}
SAMPLES=${SAMPLES:-$ROOT/out/tap/samples}
OTA_KEEP=$TMP/vendor-pcmdump.ota.orig

su_() { $ADB shell su -c "$*" 2>&1; }
prop() { $ADB shell getprop "$1" | tr -d '\r'; }

need_resetprop() {
    if ! $ADB shell 'command -v resetprop' >/dev/null 2>&1 && \
       ! $ADB shell su -c 'ls /data/adb/ksu/bin/resetprop' >/dev/null 2>&1; then
        echo "ERROR: resetprop not found (KernelSU/Magisk root required)" >&2
        exit 3
    fi
}

gate_status() {
    echo "ro.build.version.ota       = $(prop ro.build.version.ota)"
    echo "ro.audio.voiplog.on        = $(prop ro.audio.voiplog.on)"
    echo "ro.audio.asyncdump.support = $(prop ro.audio.asyncdump.support)"
    echo "audioserver pid            = $($ADB shell pidof audioserver | tr -d '\r')"
    echo "dump dir                   = $(su_ "ls $DUMP 2>/dev/null | wc -l" | tr -d '\r') file(s)"
}

volume_fix_and_record() {
    mkdir -p "$EVID"
    local before
    before=$(su_ "dumpsys audio | grep -A4 'STREAM_MUSIC' | grep -m1 'streamVolume:'" | tr -d '\r')
    echo "$before" > "$EVID/vendor-pcmdump.volume.before"
    su_ "cmd media_session volume --stream 3 --set 8" >/dev/null 2>&1 || \
        su_ "media volume --stream 3 --set 8" >/dev/null 2>&1
    echo "media volume before=[$before] (now 8)"
}

volume_restore() {
    [ -s "$EVID/vendor-pcmdump.volume.before" ] || return 0
    local idx
    idx=$(grep -oE 'streamVolume:[0-9]+' "$EVID/vendor-pcmdump.volume.before" | head -1 | grep -oE '[0-9]+')
    [ -n "$idx" ] && su_ "cmd media_session volume --stream 3 --set $idx" >/dev/null 2>&1
    echo "media volume restored to ${idx:-unknown}"
}

cmd_mask() {
    local mask=${1:-8}
    su_ "LD_LIBRARY_PATH=$TMP $TMP/pcmdump 'qcom_atlas_pcm_dump=$mask'"
}

cmd_on() {
    need_resetprop
    mkdir -p "$EVID" "$SAMPLES"
    gate_status | tee "$EVID/vendor-pcmdump.status-before.txt"
    prop ro.build.version.ota > "$OTA_KEEP"
    su_ "resetprop ro.audio.voiplog.on true"
    su_ "resetprop ro.build.version.ota '$(cat "$OTA_KEEP")_PRE'"
    echo "-- gate opened:"; gate_status
    su_ "stop audioserver; start audioserver; sleep 3"
    echo "-- audioserver restarted: pid=$($ADB shell pidof audioserver | tr -d '\r')"
    cmd_mask 8
    echo "== dumping is ON (mask 8).  Next: $0 capture <seconds> [outdir]"
}

cmd_off() {
    cmd_mask 0
    local ota
    ota=$(cat "$OTA_KEEP" 2>/dev/null)
    [ -n "$ota" ] && su_ "resetprop ro.build.version.ota '$ota'"
    su_ "resetprop --delete ro.audio.voiplog.on"
    echo "-- gate props restored:"; gate_status
    su_ "stop audioserver; start audioserver; sleep 2"
    echo "-- audioserver back: pid=$($ADB shell pidof audioserver | tr -d '\r')"
}

cmd_capture() {
    local secs=${1:-8}
    local outdir=${2:-$SAMPLES/vendor-$(date +%H%M%S)}
    mkdir -p "$outdir"
    volume_fix_and_record
    gate_status | tee "$outdir/status.txt"
    su_ "date" > "$outdir/device-time.txt"
    su_ "rm -f $DUMP/*.wav"
    if [ -f "$TMP/tone1k.dex" ]; then
        $ADB shell "cd $TMP; setsid env CLASSPATH=$TMP/tone1k.dex app_process /system/bin Tone1k $secs 8000 1000 > $TMP/vendor-pcmdump.tone.log 2>&1 < /dev/null &"
    else
        $ADB shell "cd $TMP; setsid env CLASSPATH=$TMP/tone.dex app_process /system/bin Tone $secs > $TMP/vendor-pcmdump.tone.log 2>&1 < /dev/null &"
    fi
    sleep $((secs + 1))
    cmd_mask 0
    su_ "ls -la $DUMP" | tee "$outdir/dumplist.txt"
    for f in $(su_ "ls $DUMP" | tr -d '\r'); do
        $ADB pull "$DUMP/$f" "$outdir/$f" >/dev/null 2>&1 && echo "pulled $f"
    done
    su_ "rm -f $DUMP/*.wav"
    volume_restore
    if command -v python3 >/dev/null 2>&1 && [ -f "$ROOT/out/tap/src/analyze_wav.py" ]; then
        ( cd "$outdir" && python3 "$ROOT/out/tap/src/analyze_wav.py" > fft.txt 2>&1 && echo "-- analysis in $outdir/fft.txt" ) || true
    fi
    echo "== captured into $outdir"
}

cmd_status() { gate_status; }

case "${1:-status}" in
    on) cmd_on ;;
    off) cmd_off ;;
    mask) shift; cmd_mask "$@" ;;
    capture) shift; cmd_capture "$@" ;;
    status) cmd_status ;;
    *) sed -n '2,30p' "$0"; exit 2 ;;
esac
