#!/system/bin/sh
MODDIR=${0%/*}
LOG="$MODDIR/logs/mount.log"

is_mounted() { awk -v t="$1" '$2 == t {f=1} END {exit !f}' /proc/mounts 2>/dev/null; }
label_of() { ls -Z "$1" 2>/dev/null | awk '{print $1}'; }

# Late repair: a bind can disappear when another module rebuilds /system
# overlays after post-fs-data. Re-bind, and restart audioserver if we had to.
sleep 25
L_HELD=0; L_REBOUND=0; L_LOST=0
if [ -f "$MODDIR/files/lib.map" ]; then
  while IFS='|' read -r TGT STORE; do
    [ -n "$TGT" ] || continue
    SRC="$MODDIR/files/$STORE"
    if is_mounted "$TGT"; then L_HELD=$((L_HELD+1)); continue; fi
    if [ -f "$SRC" ] && [ -e "$TGT" ]; then
      LB=$(label_of "$TGT"); [ -n "$LB" ] && chcon "$LB" "$SRC" 2>/dev/null
      mount -o bind "$SRC" "$TGT" 2>/dev/null
    fi
    if is_mounted "$TGT"; then
      L_REBOUND=$((L_REBOUND+1)); echo "  REBOUND $TGT" >> "$LOG"
    else
      L_LOST=$((L_LOST+1)); echo "  LOST $TGT" >> "$LOG"
    fi
  done < "$MODDIR/files/lib.map"
fi

if [ "$L_REBOUND" -gt 0 ]; then
  setprop ctl.restart audioserver 2>/dev/null
  sleep 3
fi

POL=0; POL_TOT=0
if [ -f "$MODDIR/files/policy.map" ]; then
  while IFS='|' read -r TGT IDX; do
    [ -n "$TGT" ] || continue
    POL_TOT=$((POL_TOT+1))
    is_mounted "$TGT" && POL=$((POL+1))
  done < "$MODDIR/files/policy.map"
fi

{
  echo "=== check $(date) mode=$(cat "$MODDIR/files/mode" 2>/dev/null)"
  echo "policy source: $(cat "$MODDIR/files/cfg_source" 2>/dev/null)"
  echo "policy mounted: $POL/$POL_TOT"
  echo "audio libs mounted: $((L_HELD+L_REBOUND))/$((L_HELD+L_REBOUND+L_LOST)) (held=$L_HELD rebound=$L_REBOUND lost=$L_LOST)"
  echo "audioserver: $(pidof audioserver)"
  echo "output threads: $(dumpsys media.audio_flinger 2>/dev/null | grep -c 'Output thread')"
  echo "effect chains: $(dumpsys media.audio_flinger 2>/dev/null | grep -c 'effects for session')"
  echo "jamesdsp attached: $(dumpsys media.audio_flinger 2>/dev/null | grep -c 'James')"
} >> "$LOG" 2>&1