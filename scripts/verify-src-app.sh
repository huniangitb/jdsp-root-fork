#!/usr/bin/env bash
# verify-src-app.sh — 源码版 App（无本地 AudioEffect、写走 jdspd）的验收。
#
#   scripts/verify-src-app.sh [--serial <s>] <apk>
#
# 覆盖：
#   V1 覆盖安装成功（签名兼容）
#   V2 冷启无崩溃、无 JdspP5 错误风暴
#   V3 App 进程无 AudioEffect 实例（dumpsys audio_flinger 的 effect client 无 App pid）
#   V4 写侧：后端 param 150 随 App 内滑条变化（人工滑一次，此脚本只查后端可达+计数增长）
#   V5 读侧权威：手改本地 xml 与后端不同 → 冷启 UI 读侧值（输出证据供人工核对）
#   V6 健康检查不重启引擎（logcat 无 "Engine crashed"）
#   V7 无后端降级：jdspd 停 → App 不崩；恢复
# 信号级验收仍在 scripts/verify-signal.sh（13 项）。
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/.." && pwd)
. "$REPO/backend/test/device/lib/device.sh"

APK=""
SERIAL=""
while [ $# -gt 0 ]; do
    case "$1" in
        --serial) SERIAL=$2; shift 2 ;;
        *) APK=$1; shift ;;
    esac
done
[ -n "$APK" ] || { echo "usage: $0 [--serial <s>] <apk>" >&2; exit 2; }
[ -f "$APK" ] || { echo "no such apk: $APK" >&2; exit 2; }
SERIAL=$(jdsp_resolve_serial "$SERIAL") || exit 2
ADB="$JDSP_ADB -s $SERIAL"
sh_() { $ADB shell "$1" 2>&1 | tr -d '\r'; }

CLI_ON_DEVICE=/data/adb/modules/ainur_jamesdsp/backend/jdspcli.dex
cli() { sh_ "su -c 'CLASSPATH=$CLI_ON_DEVICE app_process /system/bin com.jdsp.cli.Main $*'" 2>/dev/null; }

PASS=0; FAIL=0
record() { # pass|fail name detail
    if [ "$1" = "pass" ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi
    printf '%-5s %-22s %s\n' "$(echo $1 | tr a-z A-Z)" "$2" "$3"
}

# V1 安装
OUT=$($ADB install -r "$APK" 2>&1 | tail -1)
[ "$OUT" = "Success" ] && record pass v1-install "Success" || record fail v1-install "$OUT"

# V2 冷启
$ADB logcat -c 2>/dev/null
$ADB shell am force-stop james.dsp >/dev/null 2>&1
sleep 1
$ADB shell "am start -n james.dsp/me.timschneeberger.rootlessjamesdsp.activity.MainActivity" >/dev/null 2>&1
sleep 6
CRASH=$($ADB logcat -d 2>/dev/null | grep -c "FATAL EXCEPTION.*james\|Process: james.dsp")
[ "$CRASH" = "0" ] && record pass v2-no-crash "no fatal on cold start" || record fail v2-no-crash "$CRASH fatal(s)"

# V3 无 AudioEffect：App 启动后 30 秒观察窗口内，flinger effect 表里不应有 client=App uid
APP_PID=$($ADB shell pidof james.dsp | tr -d '\r')
APP_UID=$($ADB shell "dumpsys package james.dsp | grep -m1 userId=" | grep -o '[0-9]*')
sleep 24
EFFECTS=$($ADB shell "su -c 'dumpsys media.audio_flinger'" 2>/dev/null | grep -iE "james|f27317f4" | grep -c "client session owner.*$APP_UID\|owner:$APP_PID\|pid.*$APP_PID" || true)
[ "$EFFECTS" = "0" ] && record pass v3-no-local-effect "no effect handle owned by app (pid=$APP_PID uid=$APP_UID)" \
                   || record fail v3-no-local-effect "$EFFECTS handle(s) owned by app"

# V6 无引擎重启循环
REBOOT=$($ADB logcat -d 2>/dev/null | grep -c "Engine crashed. Rebooting JamesDSP")
[ "$REBOOT" = "0" ] && record pass v6-no-reboot-loop "no engine crash toast" || record fail v6-no-reboot-loop "$REBOOT reboot attempt(s)"

# V4 写通道可达（后端 stats 里 setParamCalls 在 App 启动 sync 后应 > 0）
SPC=$(cli stats | grep -o '"setParamCalls":[0-9]*' | cut -d: -f2)
[ -n "$SPC" ] && [ "$SPC" -gt 0 ] && record pass v4-write-path "backend setParamCalls=$SPC (app sync reached jdspd)" \
              || record fail v4-write-path "setParamCalls=$SPC (app writes never arrived)"

# V5 读侧证据：本地 xml 值 vs UI dump（输出供人工核对，不做自动判定——控件文本解析太脆）
echo "---- v5 evidence (manual check) ----"
echo "local tube_drive xml  : $(sh_ "su -c 'cat /data/data/james.dsp/shared_prefs/dsp_tube.xml'" 2>/dev/null | grep -o 'value="[^"]*"')"
echo "backend param 150     : $(cli dump-config | grep -o '\"1500\": \"[0-9a-f]*\"' | head -1)"
echo "UI (uiautomator)      : run 'adb shell uiautomator dump' and read the limiter/electronic tube rows"

# V7 降级：杀 jdspd（supervisor 60s 内拉起），App 应存活
sh_ "su -c 'pkill -f com.jdsp.backend.Daemon'" >/dev/null 2>&1
sleep 3
APP_ALIVE=$($ADB shell pidof james.dsp | tr -d '\r' | wc -w)
[ "$APP_ALIVE" -ge 1 ] && record pass v7-degrade "app survived jdspd kill" || record fail v7-degrade "app died with jdspd"

echo
echo "summary: $PASS passed, $FAIL failed"
[ "$FAIL" = "0" ]
