#!/system/bin/sh
# JDSP soak autostart（KernelSU service.d）
# 作用：设备重启后自动续跑 72h soak，避免"重启即中断"（2026-10-26 实测两次 reboot,shell）。
# 幂等：已有 soak_device.sh 在跑就不重复启动。删除本文件即禁用。
DIR=/data/local/tmp/jdsp-soak
LOG=$DIR/launcher.log
[ -f "$DIR/soak_device.sh" ] || exit 0
# 等 daemon（模块 service.sh 会拉起 jdspd.sh），最多 60s
i=0
while [ $i -lt 60 ]; do
    [ -n "$(pidof jdspd 2>/dev/null)" ] && break
    i=$((i+1))
    sleep 1
done
case "$(ps -A -o ARGS 2>/dev/null)" in
    *soak_device.sh*) exit 0 ;;
esac
echo "[$(date '+%F %T')] autostart soak (boot)" >> "$LOG"
setsid sh "$DIR/soak_device.sh" >> "$LOG" 2>&1 &
