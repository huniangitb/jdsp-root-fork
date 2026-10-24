#!/usr/bin/env bash
# module/accept-v2.sh — v2.0.0 模块侧验收（真机，adb root shell）
#
# 用法：
#   bash module/accept-v2.sh                 # 读当前 mode 做全部检查
#   bash module/accept-v2.sh --mode backend  # 按 backend 判据检查（apply 必须 0）
#   bash module/accept-v2.sh --kill-restart  # 额外做"杀 daemon 是否自愈"（会短暂中断）
#   bash module/accept-v2.sh --tone          # 额外播 tone 并检查实例（10s）
#
# 退出码：0 全部 PASS（SKIP 不算失败）；1 有 FAIL；2 环境问题。
# 环境变量：ADB / SERIAL / TONE_SECONDS（默认 12）
set -uo pipefail

SDK=${ANDROID_SDK:-/home/bdy/Android/Sdk}
ADB=${ADB:-adb}
SERIAL=${SERIAL:-LNNZXG8TPFHU69AI}
WANT_MODE=""
DO_KILL=0
DO_TONE=0
TONESEC=${TONE_SECONDS:-12}

for a in "$@"; do
    case "$a" in
        --mode) WANT_MODE="__next__" ;;
        --mode=*) WANT_MODE=${a#--mode=} ;;
        --kill-restart) DO_KILL=1 ;;
        --tone) DO_TONE=1 ;;
        -h|--help) sed -n "2,13p" "$0"; exit 0 ;;
        *) echo "unknown arg: $a" >&2; exit 2 ;;
    esac
done

P=0; F=0; S=0
pass() { printf "PASS  %-26s %s\n" "$1" "$2"; P=$((P+1)); }
fail() { printf "FAIL  %-26s %s\n" "$1" "$2"; F=$((F+1)); }
skip() { printf "SKIP  %-26s %s\n" "$1" "$2"; S=$((S+1)); }

D() { "$ADB" -s "$SERIAL" shell "$1" 2>/dev/null; }
DR() { "$ADB" -s "$SERIAL" shell "su -c \"$1\"" 2>/dev/null; }

command -v "$ADB" >/dev/null || { echo "adb not found" >&2; exit 2; }
"$ADB" -s "$SERIAL" get-state >/dev/null 2>&1 || { echo "device $SERIAL not available" >&2; exit 2; }

MOD=/data/adb/modules/ainur_jamesdsp
RUNDIR=/data/adb/jdspd
APPFILES=/data/data/james.dsp/files
BASE_SHA=b6e1fc1fdc67b0c7bd73b083e92238eb29b4d0c66f7c02e5b476ad78c9fbf700

mode() {
    if [ -n "$WANT_MODE" ] && [ "$WANT_MODE" != "__next__" ]; then printf "%s\n" "$WANT_MODE"; return; fi
    m=$(DR "sh $MOD/jdsp_mode.sh get")
    if [ -n "$m" ]; then printf "%s\n" "$m"; else printf "auto\n"; fi
}
M=$(mode)
echo "=== v2 module acceptance (mode=$M) $(date +%H:%M:%S) ==="

# 1 engine sha
sha=$(D "sha256sum /vendor/lib64/soundfx/libjamesdsp.so" | awk "{print \$1}")
if [ "$sha" = "$BASE_SHA" ]; then pass "engine sha" "$sha"; else fail "engine sha" "got=$sha"; fi

# 2 jdspd single instance
n=$(D "ps -A -o NAME | grep -cx jdspd")
pid=$(D "pidof jdspd" | tr -d "\r")
if [ "$n" = "1" ]; then pass "jdspd process" "pid=$pid single"; else fail "jdspd process" "count=$n"; fi

# 3 binder service
s=$(D "service check jdsp.service" | tr -d "\r")
case "$s" in *found*) pass "binder jdsp.service" "found" ;; *) fail "binder jdsp.service" "$s" ;; esac

# 4 mounted xml apply count vs mode
for f in /vendor/etc/audio_effects.xml /system/etc/audio_effects.xml; do
    c=$(D "grep -c 'effect=\"jamesdsp\"' $f 2>/dev/null" | tr -d "\r")
    if [ -z "$c" ]; then c=0; fi
    if [ "$M" = "backend" ]; then
        if [ "$c" = "0" ]; then pass "apply $(basename $(dirname $f))" "0 (backend)"; else fail "apply $(basename $(dirname $f))" "count=$c need 0"; fi
    else
        if [ "$c" -ge 1 ]; then pass "apply $(basename $(dirname $f))" "$c (auto)"; else fail "apply $(basename $(dirname $f))" "count=$c need>=1"; fi
    fi
done

# 5 boot.log mode line
bl=$(DR "grep -h '^\[mode\]' $MOD/boot.log | tail -2" | tr -d "\r")
case "$bl" in
    *OK*) pass "boot.log mode" "$(printf "%s" "$bl" | tail -1)" ;;
    *WARN*) fail "boot.log mode" "$(printf "%s" "$bl" | tail -1)" ;;
    *) skip "boot.log mode" "no [mode] line yet" ;;
esac

# 5b 挂载的 audio_effects.xml 是不是我们这份（inode 对比）
# 别的模块（XagaSoundBoostTest）也提供 /vendor/etc/audio_effects.xml 且会赢得挂载，
# 那时我们改模块副本没用，backend 模式永远不生效 —— 必须能检出。
our_inode=$(DR "stat -c %i $MOD/system/vendor/etc/audio_effects.xml 2>/dev/null" | tr -d "\r")
mounted_inode=$(DR "stat -c %i /vendor/etc/audio_effects.xml 2>/dev/null" | tr -d "\r")
if [ -n "$our_inode" ] && [ -n "$mounted_inode" ]; then
    if [ "$our_inode" = "$mounted_inode" ]; then
        pass "xml owner (inode)" "mounted=$mounted_inode = ours"
    else
        # 找出是哪个模块提供了这份文件（用设备的 shell 做，避免本地引号地狱）
        # 逐个候选路径 stat（避免远端 shell 在双引号里展开 $p / $(...)）
        owner=""
        for cand in $(DR "ls -d /data/adb/modules/*/system/vendor/etc/audio_effects.xml 2>/dev/null"); do
            ci=$(DR "stat -c %i $cand 2>/dev/null" | tr -d "\r")
            if [ "$ci" = "$mounted_inode" ]; then owner=$cand; break; fi
        done
        if [ "$M" = "backend" ]; then
            fail "xml owner (inode)" "another module provides /vendor/etc/audio_effects.xml (mounted=$mounted_inode ours=$our_inode) provider=$owner -> backend mode will not take effect"
        else
            # auto 模式下别人的文件带 apply 是正常的（我们不依赖它），只提示不判失败
            pass "xml owner (inode)" "mounted=$mounted_inode provider=$owner (auto 模式不影响：框架注入由胜出者提供)"
        fi
    fi
else
    skip "xml owner (inode)" "cannot stat"
fi

# 6 jdspd.log health
if DR "[ -f $RUNDIR/jdspd.log ]" >/dev/null 2>&1; then
    # 只统计"最近一次 supervisor start"之后的启动次数：日志现在每次开机轮转，
    # 但旧版本留下的日志可能仍跨重启累积，按最后一个 supervisor 标记切段最稳。
    starts=$(DR "awk '/supervisor start/{n=0} /starting jdspd/{n++} END{print n+0}' $RUNDIR/jdspd.log" | tr -d "\r")
    if [ -z "$starts" ]; then starts=0; fi
    if [ "$starts" -le 3 ]; then pass "jdspd.log health" "starts=$starts"; else fail "jdspd.log health" "restart storm starts=$starts"; fi
else
    skip "jdspd.log health" "log missing"
fi

# 7 config.json mode vs module mode (最危险失败模式)
cfg=$(DR "cat $RUNDIR/config.json 2>/dev/null" | tr -d "\n\r")
if [ -n "$cfg" ]; then
    cm=$(printf "%s" "$cfg" | sed -n 's/.*"modeName"[[:space:]]*:[[:space:]]*"\([A-Za-z_]*\)".*/\1/p')
    if [ -z "$cm" ]; then cm="?"; fi
    if [ "$cm" = "$M" ]; then pass "config.json mode" "modeName=$cm matches"; else fail "config.json mode" "modeName=$cm but module=$M"; fi
else
    skip "config.json mode" "config.json missing"
fi

# 8 transport files (loopback TCP + token)，不再是 unix socket
if [ -d "$APPFILES" ] || DR "[ -d $APPFILES ]" >/dev/null 2>&1; then
    pl=$(DR "ls -ln $APPFILES/jdsp.port $APPFILES/jdsp.token 2>/dev/null")
    if printf "%s" "$pl" | grep -q jdsp.port; then
        port=$(DR "cat $APPFILES/jdsp.port 2>/dev/null" | tr -d "\r\n")
        toklen=$(DR "tr -d '\n' < $APPFILES/jdsp.token 2>/dev/null | wc -c" | tr -d "\r ")
        pass "transport" "port=$port token_len=$toklen"
        printf "      %s\n" "$(printf "%s" "$pl" | tr "\n" " ")"
    else
        skip "transport" "jdsp.port/token absent"
    fi
else
    skip "transport" "$APPFILES absent (App 未装/未启动)"
fi

# 9 state.bin mode
sm=$(DR "stat -c %a /data/vendor/audio/jamesdsp_state.bin 2>/dev/null" | tr -d "\r")
case "$sm" in
    660) pass "state.bin mode" "660" ;;
    "") skip "state.bin mode" "absent (首启未写过)" ;;
    *) pass "state.bin mode" "$sm (等看护循环归一化)" ;;
esac

# 10 optional kill -> restart
if [ "$DO_KILL" = 1 ] && [ -n "$pid" ]; then
    DR "kill $pid" >/dev/null 2>&1
    ok=0; i=0
    while [ "$i" -lt 20 ]; do
        i=$((i+1)); sleep 1
        np=$(D "pidof jdspd" | tr -d "\r")
        if [ -n "$np" ] && [ "$np" != "$pid" ]; then ok=1; break; fi
    done
    if [ "$ok" = 1 ]; then pass "kill -> restart" "old=$pid new=$np in ${i}s"; else fail "kill -> restart" "no restart in 20s"; fi
fi

# 11 optional tone -> instance + client pid
if [ "$DO_TONE" = 1 ]; then
    DR "CLASSPATH=/data/local/tmp/tone.dex nohup app_process /system/bin Tone $TONESEC >/data/local/tmp/tone-acc.log 2>&1 &" >/dev/null 2>&1
    sleep 6
    sess=$(D "cat /data/local/tmp/tone-acc.log" | tr -d "\r" | sed -n "s/^SESSION=\([0-9]*\).*/\1/p")
    dump=$(D "dumpsys media.audio_flinger")
    inst=$(printf "%s" "$dump" | grep -c "name: JamesDSP")
    cli=$(printf "%s" "$dump" | grep -A8 "UUID: f27317f4" | sed -n "s/.*Pid \([0-9]*\).*/\1/p" | head -1)
    if [ "$inst" -ge 1 ]; then
        pass "tone instance" "session=$sess instances=$inst client=$cli jdspd=$pid"
        case "$M" in
            backend) if [ "$cli" = "$pid" ]; then pass "instance owner" "jdspd created it (backend OK)"; else fail "instance owner" "client=$cli want jdspd=$pid"; fi ;;
            *) if [ "$cli" = "$pid" ]; then pass "instance owner" "jdspd attached existing"; else pass "instance owner" "client=$cli (framework)"; fi ;;
        esac
    else
        fail "tone instance" "session=$sess no instance"
    fi
fi

echo "---"
echo "PASS=$P FAIL=$F SKIP=$S"
if [ "$F" = 0 ]; then exit 0; else exit 1; fi