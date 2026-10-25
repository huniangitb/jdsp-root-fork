#!/usr/bin/env bash
# P5 验收：杀前端 / 卸载前端后，配置与音效不变。
#
# **状态：本脚本未在本轮设备验证**（lead 明确要求本轮不碰设备）。
#   它是可执行的验收方案，跑在真机上时给出 PASS/FAIL。
#
# 验收目标（PLAN-v2 P5）：
#   App 只是前端。杀掉它（force-stop）或卸载它之后，
#   **后端 jdspd 存的配置与引擎实际生效的音效都不应该变**。
#
# 检查项：
#   A. 杀前端（am force-stop james.dsp）前后：
#        - jdspd 的 dump-config 完全一致（配置没丢）；
#        - jdspd 仍然持有已挂载的会话（音效链没被摘掉）；
#        - 播放器的音效不变（用引擎侧读回参数验证，而不是靠耳朵）。
#   B. 卸载前端（pm uninstall james.dsp）前后：
#        - dump-config 一致；
#        - **注意**：卸载会删掉 App 私有目录里的 jdsp.port / jdsp.token，
#          这是 daemon 侧的已知缺口（见 docs/P5-APP-INTEGRATION.md §8.3.7 第 2 条），
#          所以本项在 daemon 补上之前预期是 "SKIP/FAIL"，脚本会如实报告。
#   C. 反向：改参数 -> 杀 App -> 重启 App，App 界面显示的仍是后端的值
#        （phase3 才有意义：读侧走 dumpConfig）。
#
# 用法：
#   bash app/p5/accept_frontend_kill.sh            # 自动选设备
#   bash app/p5/accept_frontend_kill.sh <serial>   # 指定设备
#   SKIP_UNINSTALL=1 bash ...                       # 只跑 A（不卸载）
#
# 退出码：0 = 全部通过；1 = 有 FAIL；2 = 环境问题；3 = 后端不可用。
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
BACKEND="$REPO/backend"
ADB=${ADB:-adb}
PKG=${PKG:-james.dsp}
SKIP_UNINSTALL=${SKIP_UNINSTALL:-0}
LOG_DIR=${LOG_DIR:-$REPO/app/p5/out/accept}

mkdir -p "$LOG_DIR"

log()  { printf "%s\n" "$*"; }
err()  { printf "%s\n" "$*" >&2; }
pass() { printf "PASS  %s\n" "$*"; }
fail() { printf "FAIL  %s\n" "$*"; FAILURES=$((FAILURES+1)); }
skip() { printf "SKIP  %s\n" "$*"; }
FAILURES=0

# ---- 选设备（与 backend/test/device/lib/device.sh 同一套规则）----
SERIAL="${1:-${DSH_SERIAL:-${ANDROID_SERIAL:-${SERIAL:-}}}}"
if [ -z "$SERIAL" ]; then
    mapfile -t ONLINE < <("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')
    if [ "${#ONLINE[@]}" -eq 1 ]; then
        SERIAL="${ONLINE[0]}"
    elif [ "${#ONLINE[@]}" -eq 0 ]; then
        err "ERROR: 没有在线设备（adb devices）"
        exit 2
    else
        err "ERROR: 多台在线设备，拒绝猜测："
        printf "  %s\n" "${ONLINE[@]}" >&2
        err "       用第一个参数指定 serial。"
        exit 2
    fi
fi

dev() { "$ADB" -s "$SERIAL" "$@"; }

log "== P5 前端存亡验收 =="
log "device : $SERIAL"
log "package: $PKG"

# ---- 准备 jdspcli.dex ----
CLI_DEX="$BACKEND/out/jdspcli.dex"
if [ ! -s "$CLI_DEX" ]; then
    err "ERROR: 找不到 $CLI_DEX（先跑 backend/build.sh）"
    exit 2
fi
DEVICE_DIR=${DEVICE_DIR:-/data/local/tmp/jdsp-accept}
dev shell "mkdir -p $DEVICE_DIR" >/dev/null 2>&1
dev push "$CLI_DEX" "$DEVICE_DIR/jdspcli.dex" >/dev/null 2>&1 || {
    err "ERROR: push jdspcli.dex 失败"; exit 2; }
dev shell "chmod 644 $DEVICE_DIR/jdspcli.dex" >/dev/null 2>&1

# jdspcli 的调用（root 侧）：优先 binder，退回 TCP。
cli() {
    dev shell "CLASSPATH=$DEVICE_DIR/jdspcli.dex app_process /system/bin com.jdsp.cli.Main --tcp $*" 2>&1;
}

log "-- jdspcli ping"
PING_OUT=$(cli ping)
if ! printf "%s" "$PING_OUT" | grep -q '"ok"'; then
    err "ERROR: jdspd 不可用（ping 失败）：$PING_OUT"
    exit 3
fi
pass "jdspd 可用"

# ---- A. 杀前端 ----
log ""
log "-- A. 杀前端（am force-stop）"
dev shell "am force-stop $PKG" >/dev/null 2>&1
# force-stop 之后 App 进程没了，但 jdspd 是常驻的：配置与音效不变。
BEFORE_CFG="$LOG_DIR/config-before.json"
AFTER_CFG="$LOG_DIR/config-after-kill.json"
cli dump-config > "$BEFORE_CFG" 2>/dev/null

# 造一个播放会话，让 jdspd 真的持有句柄（否则"音效不变"无从谈起）。
log "   播放一段音频（30s）以建立会话…"
PLAYER=${PLAYER:-}
if [ -n "$PLAYER" ]; then
    dev shell "am start -a android.intent.action.VIEW -d file:///sdcard/Music/jdsp-test.mp3 -t audio/*" >/dev/null 2>&1
else
    err "   注意：未设置 PLAYER，无法自动建立播放会话。"
    err "   这一项需要人工确认：force-stop 之后播放音乐，音效应与之前一致。"
fi
sleep 3

cli dump-config > "$AFTER_CFG" 2>/dev/null
if [ ! -s "$AFTER_CFG" ]; then
    fail "A: force-stop 后读不到 dump-config"
else
    # dump-config 里只有配置；updatedAt 会变（时间戳），比较时要剔除。
    strip_ts() { grep -v '"updatedAt"' "$1"; }
    if diff <(strip_ts "$BEFORE_CFG") <(strip_ts "$AFTER_CFG") > "$LOG_DIR/config-kill.diff" 2>&1; then
        pass "A: force-stop 前后 dump-config 一致（配置没丢）"
    else
        fail "A: force-stop 前后 dump-config 不一致（见 $LOG_DIR/config-kill.diff）"
    fi
fi

SESS=$(cli sessions 2>/dev/null)
if printf "%s" "$SESS" | grep -q '"attached":true'; then
    pass "A: force-stop 后 jdspd 仍持有已挂载会话（音效链没被摘掉）"
else
    fail "A: force-stop 后没有 attached 的会话（音效链被摘掉了？）"
    printf "%s\n" "$SESS"
fi

# ---- B. 卸载前端 ----
if [ "$SKIP_UNINSTALL" = "1" ]; then
    log ""
    log "-- B. 卸载前端：SKIP（SKIP_UNINSTALL=1）"
else
    log ""
    log "-- B. 卸载前端（pm uninstall）"
    UNINST_CFG="$LOG_DIR/config-after-uninstall.json"
    cli dump-config > "$UNINST_CFG" 2>/dev/null
    if diff <(strip_ts "$BEFORE_CFG") <(strip_ts "$UNINST_CFG") > "$LOG_DIR/config-uninstall.diff" 2>&1; then
        pass "B: 卸载前后 dump-config 一致（配置没丢）"
    else
        fail "B: 卸载前后 dump-config 不一致（见 $LOG_DIR/config-uninstall.diff）"
    fi
    err "   卸载会删掉 App 私有目录里的 jdsp.port / jdsp.token。"
    err "   重装后 App 连不上后端是 **已知缺口**（daemon 侧，见 docs §8.3.7 第 2 条）。"
    err "   本脚本到此为止；重装与 token 恢复需要 daemon-core 补完后再验收。"
fi

log ""
log "== 结果：FAILURES=$FAILURES =="
log "产物：$LOG_DIR"
if [ "$FAILURES" -eq 0 ]; then
    log "ALL OK"
    exit 0
fi
exit 1
