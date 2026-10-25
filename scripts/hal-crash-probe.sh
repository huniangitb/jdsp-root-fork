#!/usr/bin/env bash
#
# hal-crash-probe.sh — bounded HAL-crash reproduction loop for the JamesDSP
# backend stack (host side; drives the device over adb).
#
# What it does, once per round (~25 s):
#   1. runs multiapi.dex in "stress" or "all" mode as uid 10243 (salt) or
#      10232 (qq), alternating, to hammer session create/teardown;
#   2. flips the daemon master switch (enabled off -> on) between phases so
#      every live chain is torn down and rebuilt;
#   3. after each phase compares `pidof audioserver` against the baseline and
#      scans /data/system/dropbox for SYSTEM_TOMBSTONE entries newer than the
#      marker taken at start.  On an audioserver restart or a new tombstone it
#      records the evidence (pid file, tombstone list, logcat tail) and stops.
#
# Usage:
#   hal-crash-probe.sh [max_seconds] [serial]
#     max_seconds  hard stop (default 1800 = 30 min); 0 = single round only
#     serial       adb serial (default LNNZXG8TPFHU69AI)
#
# Exit codes: 0 = finished without incident, 7 = HAL crash / tombstone found,
#             8 = audioserver restart observed without tombstone.
# On every exit path the master switch is restored to `enabled on`.

set -u
SERIAL=${2:-LNNZXG8TPFHU69AI}
MAX_SECS=${1:-1800}
ADB="adb -s $SERIAL"
CLI="/data/adb/modules/ainur_jamesdsp/backend/jdspcli.dex app_process /system/bin com.jdsp.cli.Main"
MULTI=/data/local/tmp/multiapi.dex
OUT=${HAL_PROBE_OUT:-/tmp/hal-probe}
mkdir -p "$OUT"
LOG="$OUT/probe.log"
: > "$LOG"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }

cli() { $ADB shell su -c "CLASSPATH=$CLI $*" 2>&1; }

# ---------- baseline ----------
START_MS=$(date +%s)
TOMB_MARK=$(date +%s)
PID0=$($ADB shell su -c 'pidof audioserver' | tr -d '\r')
log "baseline audioserver pid=$PID0, marker=$TOMB_MARK"
cli "enabled on" >/dev/null
# Restore the canonical parameter baseline (jdspcli baseline --all) before and after
# the probe (incl. the abnormal-exit paths): stress residue must not leak into the
# next configuration (2026-10-25 regression class). Best effort with an old jdspcli.
cli_baseline() { cli "baseline --all" >/dev/null 2>&1 || log "warn: baseline failed (old jdspcli?)"; }
trap cli_baseline EXIT
cli_baseline

new_tombstones() {
    $ADB shell su -c "ls /data/system/dropbox/ 2>/dev/null" \
        | tr -d '\r' | grep '^SYSTEM_TOMBSTONE' | while read -r f; do
            ts=$($ADB shell su -c "stat -c %Y /data/system/dropbox/$f 2>/dev/null" | tr -d '\r')
            [ -n "$ts" ] && [ "$ts" -gt "$TOMB_MARK" ] && echo "$f"
        done
}

record_incident() {
    local kind=$1
    log "!! INCIDENT ($kind) — recording and stopping"
    echo "$kind" > "$OUT/incident.txt"
    $ADB shell su -c 'pidof audioserver' | tr -d '\r' > "$OUT/pid_after.txt"
    new_tombstones > "$OUT/tombstones.txt"
    cat "$OUT/tombstones.txt" | while read -r f; do
        [ -n "$f" ] || continue
        log "tombstone: $f"
        $ADB shell su -c "cat /data/system/dropbox/$f" \
            | gzip > "$OUT/${f}.gz" 2>/dev/null
    done
    $ADB shell su -c 'logcat -d -b main -b system -t 500' > "$OUT/logcat_tail.txt" 2>&1
    cli "sessions" > "$OUT/sessions_after.txt" 2>&1
}

# ---------- rounds ----------
ROUND=0
while :; do
    NOW=$(date +%s)
    if [ "$MAX_SECS" -gt 0 ] && [ $((NOW - START_MS)) -ge "$MAX_SECS" ]; then
        log "time budget exhausted ($MAX_SECS s), stopping"
        break
    fi
    if [ "$MAX_SECS" -eq 0 ] && [ "$ROUND" -ge 1 ]; then
        log "single-round mode (max_seconds=0), stopping"
        break
    fi
    ROUND=$((ROUND + 1))
    UID_TGT=10243; UMASK_NAME=salt
    if [ $((ROUND % 2)) -eq 0 ]; then UID_TGT=10232; UMASK_NAME=qq; fi
    MODE=stress
    if [ $((ROUND % 4)) -ge 2 ]; then MODE=all; fi
    log "round $ROUND uid=$UMASK_NAME($UID_TGT) mode=$MODE"

    # phase A: chain churn with the master switch on (player runs under the
    # target app's uid, attached to a live host-side adb process so KernelSU
    # cannot reap it as an orphan)
    $ADB shell su $UID_TGT -c "CLASSPATH=$MULTI app_process /system/bin MultiApi $MODE 10 $UMASK_NAME" \
        > "$OUT/round_${ROUND}_${UMASK_NAME}.log" 2>&1 &
    PLAYER=$!
    sleep 10
    # phase B: master switch off (tears every chain down) then back on
    cli "enabled off" >/dev/null
    sleep 3
    cli "enabled on" >/dev/null
    sleep 6
    # let the player finish (bounded; it never outlives ~40 s)
    for i in $(seq 1 20); do
        kill -0 "$PLAYER" 2>/dev/null || break
        sleep 1
    done
    kill "$PLAYER" 2>/dev/null
    wait "$PLAYER" 2>/dev/null

    # checks
    PID_NOW=$($ADB shell su -c 'pidof audioserver' | tr -d '\r')
    if [ -n "$PID0" ] && [ "$PID_NOW" != "$PID0" ]; then
        log "audioserver pid changed: $PID0 -> $PID_NOW"
        record_incident "audioserver-restart"
        cli "enabled on" >/dev/null
        exit 8
    fi
    TOMBS=$(new_tombstones)
    if [ -n "$TOMBS" ]; then
        log "new tombstone(s): $TOMBS"
        record_incident "tombstone"
        cli "enabled on" >/dev/null
        exit 7
    fi
done

cli "enabled on" >/dev/null
log "finished: $ROUND round(s), no incident"
echo "rounds=$ROUND incidents=0 log=$LOG"
exit 0
