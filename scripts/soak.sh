#!/usr/bin/env bash
# soak.sh — 在设备上启动 72 h 长稳监测（scripts/soak_device.sh 的主机侧启动器）。
#
#   scripts/soak.sh [--serial <s>] [--hours N] [--interval S]
#
# soak_device.sh 在设备上 setsid 脱离 adb 会话运行，不依赖 USB 一直连着；
# 报告持续追加在 /data/local/tmp/jdsp-soak/report.tsv，随时可以：
#   adb shell su -c 'tail -5 /data/local/tmp/jdsp-soak/report.tsv'
# 停止：adb shell su -c 'pkill -f soak_device.sh'
# 汇总：scripts/soak.sh --report
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
SERIAL="" HOURS=72 INTERVAL=600 REPORT=0
while [ $# -gt 0 ]; do
    case "$1" in
        --serial) SERIAL=$2; shift 2 ;;
        --hours) HOURS=$2; shift 2 ;;
        --interval) INTERVAL=$2; shift 2 ;;
        --report) REPORT=1; shift ;;
        *) echo "unknown arg $1" >&2; exit 2 ;;
    esac
done
ADB=${ADB:-adb}
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"

if [ "$REPORT" = 1 ]; then
    exec $ADB shell "su -c 'cat /data/local/tmp/jdsp-soak/report.tsv'"
fi

$ADB push "$HERE/soak_device.sh" /data/local/tmp/jdsp-soak_device.sh || exit 1
$ADB shell "su -c 'mkdir -p /data/local/tmp/jdsp-soak; pkill -f soak_device.sh; sleep 1; SOAK_HOURS=$HOURS SOAK_INTERVAL_S=$INTERVAL setsid sh /data/local/tmp/jdsp-soak_device.sh < /dev/null > /data/local/tmp/jdsp-soak/launcher.log 2>&1 &'" || exit 1
sleep 3
echo "soak started (hours=$HOURS interval=${INTERVAL}s)"
$ADB shell "su -c 'pgrep -lf soak_device | head -2; head -2 /data/local/tmp/jdsp-soak/report.tsv'"
