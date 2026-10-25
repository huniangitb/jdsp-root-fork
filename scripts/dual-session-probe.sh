#!/usr/bin/env bash
# dual-session-probe.sh — P0 "second session goes silent" reproduction driver.
#
# Prereq: adb push tools/DualSession.java compiled to /data/local/tmp/dual.dex
#   (see tools/DualSession.java header; build with javac+d8 like tools/README).
#
# Usage: dual-session-probe.sh <name> <A:low|normal> <B:low|normal> \
#            <bothSecs> <soloSecs> <enabled:on|off|keep> [aAmp] [aFreq] [bFreq]
# Output: /tmp/p0/<name>/{dual.txt,mic.txt,flinger_*.txt}
# Verdict: python3 scripts/dual-session-fft.py on a micrec pcm, or the RMS
#          analyzer inline (mic buckets vs phase windows).
set -u
DEV=${DSH_SERIAL:-LNNZXG8TPFHU69AI}
ADBX="adb -s $DEV"
CLI="/data/adb/modules/ainur_jamesdsp/backend/jdspcli.dex app_process /system/bin com.jdsp.cli.Main"
name=$1; A=$2; B=$3; both=$4; solo=$5; en=${6:-keep}
out=/tmp/p0/$name; mkdir -p "$out"

# Restore the canonical parameter baseline (jdspcli baseline --all): wipes test
# residue in globalParams/perAppParams before AND after the run, so this probe can
# never leak values into the next test session (2026-10-25 regression class).
cli_baseline() {
  $ADBX shell su -c "CLASSPATH=$CLI baseline --all" >/dev/null 2>&1 || \
    echo "warn: baseline unsupported (old jdspcli?) or failed" >&2
}
trap cli_baseline EXIT
cli_baseline

if [ "$en" != "keep" ]; then
  $ADBX shell su -c "CLASSPATH=$CLI $en" > "$out/enabled_set.txt" 2>&1
fi
$ADBX shell su -c 'pidof audioserver' > "$out/pid_before.txt" 2>&1

total=$((3 + both + solo + 3))
$ADBX shell su -c "CLASSPATH=/data/local/tmp/dual.dex app_process /system/bin DualSession miccap $total" \
  > "$out/mic.txt" 2>&1 &
MICPID=$!
$ADBX shell su -c "CLASSPATH=/data/local/tmp/dual.dex app_process /system/bin DualSession dual $A $B $both $solo ${7:-0} ${8:-440} ${9:-880}" \
  > "$out/dual.txt" 2>&1 &
DUALPID=$!

sleep 2.0
$ADBX shell dumpsys media.audio_flinger > "$out/flinger_aonly.txt" 2>&1
midboth=$((3 + both / 2))
sleep "$midboth"
$ADBX shell dumpsys media.audio_flinger > "$out/flinger_both.txt" 2>&1

wait $DUALPID; echo "dual_rc=$?" > "$out/rc.txt"
sleep 1.5
$ADBX shell dumpsys media.audio_flinger > "$out/flinger_solo.txt" 2>&1
wait $MICPID
sleep 0.5
$ADBX shell su -c "CLASSPATH=$CLI sessions" > "$out/sessions.txt" 2>&1
$ADBX shell su -c 'pidof audioserver' > "$out/pid_after.txt" 2>&1
if [ "$en" != "keep" ]; then
  $ADBX shell su -c "CLASSPATH=$CLI enabled on" > /dev/null 2>&1
fi
echo "== $name done: $out"
tail -3 "$out/dual.txt"
# inline RMS verdict per phase
python3 - "$out" <<'PY'
import re, os, sys
rundir = sys.argv[1]
mic = []
for line in open(os.path.join(rundir, 'mic.txt')):
    m = re.match(r'RMS t=(\d+) wall=(\d+) ([\d.]+) dbfs=(-?[\d.]+)', line)
    if m: mic.append((int(m.group(1)), int(m.group(2)), float(m.group(3))))
ev = []
d = os.path.join(rundir, 'dual.txt')
if os.path.exists(d):
    for line in open(d):
        m = re.search(r'PHASE BOTH (\d+)', line)
        if m: ev.append((int(m.group(1)), 'both'))
        m = re.search(r'PHASE SOLO (\d+)', line)
        if m: ev.append((int(m.group(1)), 'solo'))
bs = max((w for w,l in ev if l=='both'), default=None)
ss = max((w for w,l in ev if l=='solo'), default=None)
def mx(ph):
    v=[r for t,w,r in mic if (ss and w>=ss and ph=='solo') or (bs and ss is None and w>=bs and ph=='both') or (bs and ss and bs<=w<ss and ph=='both')]
    return max(v) if v else float('nan')
print(f"verdict: pre_max~ambient, both_max={mx('both'):.4f}, solo_max={mx('solo'):.4f} (audible if both/solo > ~0.05)")
PY
