#!/system/bin/sh
MODDIR=${0%/*}
LOG="$MODDIR/logs/mount.log"
PATCHER="$MODDIR/tools/aab_patch"
. "$MODDIR/tools/patchlib.sh"
mkdir -p "$MODDIR/logs"

# CORD v0.6 的做法：直接把每个模块里的 audio_policy 文件中的
# FAST / RAW / COMPRESS_OFFLOAD 输出标志去掉。这样无论哪一份副本
# 最终赢得挂载，都是已打过补丁的版本（v2.0 只挂自己的副本，
# 遇到其它模块也提供同名文件时会输掉挂载，fast 路径又回来了）。
strip_fast_paths() {
	for f in $(find /data/adb/modules -name 'audio_policy*.xml' -o -name 'audio_policy*.conf' 2>/dev/null); do
		[ -f "$f" ] || continue
		# 直接把 flag 名字删掉（不依赖前后空格，flags="AUDIO_OUTPUT_FLAG_FAST" 这种
		# 带引号的形式也能命中），再把变空的 flags="" 换成 NONE。
		sed -i -e 's/AUDIO_OUTPUT_FLAG_FAST//g' \
		       -e 's/AUDIO_OUTPUT_FLAG_RAW//g' \
		       -e 's/AUDIO_OUTPUT_FLAG_COMPRESS_OFFLOAD//g' \
		       -e 's/AUDIO_OUTPUT_FLAG_MMAP_NOIRQ//g' \
		       -e 's/AUDIO_OUTPUT_FLAG_DIRECT//g' \
		       -e 's/,compressed_offload//g' -e 's/compressed_offload,//g' \
		       -e 's/,raw//g' -e 's/raw,//g' \
		       -e 's/,direct_pcm//g' -e 's/direct_pcm,//g' \
		       -e 's/,mmap_no_irq_out//g' -e 's/mmap_no_irq_out,//g' \
		       -e 's/flags=""/flags="AUDIO_OUTPUT_FLAG_NONE"/g' \
		       -e 's/  */ /g' "$f" 2>/dev/null
		echo "stripped: $f" >> "$LOG"
	done
}

aaudio_no_mmap() {
	command -v resetprop >/dev/null 2>&1 || return 0
	resetprop -n aaudio.mmap_policy 0 2>/dev/null
	resetprop -n aaudio.mmap_exclusive_policy 0 2>/dev/null
	resetprop -n aaudio.mmap_policy 0 2>/dev/null
}
aaudio_no_mmap

strip_fast_paths

MODE=$(cat "$MODDIR/files/mode" 2>/dev/null)
case "$MODE" in primary|deep) ;; *) MODE=deep ;; esac

label_of() { ls -Z "$1" 2>/dev/null | awk '{print $1}'; }

is_mounted() {
  awk -v t="$1" '$2 == t {f=1} END {exit !f}' /proc/mounts 2>/dev/null
}

mount_one() {
  SRC="$1"; TGT="$2"
  [ -f "$SRC" ] || { echo "  MISS src $SRC"; return 1; }
  [ -e "$TGT" ] || { echo "  MISS tgt $TGT"; return 1; }
  if is_mounted "$TGT"; then echo "  OK $TGT"; return 0; fi
  L=$(label_of "$TGT"); [ -n "$L" ] && chcon "$L" "$SRC" 2>/dev/null
  mount -o bind "$SRC" "$TGT" 2>/dev/null
  if is_mounted "$TGT"; then echo "  OK $TGT"; return 0; fi
  echo "  FAIL $TGT"; return 1
}

ensure_lib() {
  LIB="$1"; STORE="$2"
  BN=${LIB##*/}
  # Server-side fast-deny on libaudioflinger is opt-in: it also disables the
  # low-latency path for games/VoIP, so it must never be mounted by accident.
  # Enable with:  touch /data/adb/modules/CORD/flinger_fast_deny
  case "$LIB" in
    */libaudioflinger.so)
      if [ ! -f /data/adb/modules/CORD/flinger_fast_deny ]; then
        echo "  $BN: server-side fast-deny disabled (opt-in), skip"; return 1
      fi ;;
  esac
  [ -x "$PATCHER" ] || { echo "  $BN: no patcher, skip"; return 1; }
  [ -f "$LIB" ] || { echo "  $BN: not found, skip"; return 1; }
  cur=$("$PATCHER" -c "$LIB" 2>/dev/null)
  exp=$(cat "$MODDIR/files/$STORE.crc32" 2>/dev/null)
  if [ "$cur" != "$exp" ] || [ ! -f "$MODDIR/files/$STORE" ]; then
    echo "  $BN: system lib changed ($cur != $exp), re-patching"
    case "$MODE" in primary) FL=2 ;; *) FL=8 ;; esac
    if ! patch_lib "$LIB" "$MODDIR/files/$STORE" "$FL" "$MODDIR/logs/patch_$BN.log"; then
      echo "  $BN: re-patch failed, skip"; return 1
    fi
    echo "$cur" > "$MODDIR/files/$STORE.crc32"
  fi
  mount_one "$MODDIR/files/$STORE" "$LIB"
}

P_OK=0; P_FAIL=0; L_OK=0; L_FAIL=0
CFG=$(cat "$MODDIR/files/cfg_source" 2>/dev/null)
case "$CFG" in
  AIDL*) DO_POLICY=0 ;;
  *) DO_POLICY=1 ;;
esac
if [ "$DO_POLICY" = "1" ] && [ -f "$MODDIR/files/policy.map" ]; then
  while IFS='|' read -r TGT IDX; do
    [ -n "$TGT" ] || continue
    OUT=$(mount_one "$MODDIR/files/policy/$IDX.xml" "$TGT")
    echo "$OUT" >> "$LOG"
    if echo "$OUT" | grep -q "^  OK"; then P_OK=$((P_OK+1)); else P_FAIL=$((P_FAIL+1)); fi
  done < "$MODDIR/files/policy.map"
fi
if [ -f "$MODDIR/files/lib.map" ]; then
  while IFS='|' read -r TGT STORE; do
    [ -n "$TGT" ] || continue
    OUT=$(ensure_lib "$TGT" "$STORE")
    echo "$OUT" >> "$LOG"
    if echo "$OUT" | grep -q "^  OK"; then L_OK=$((L_OK+1)); else L_FAIL=$((L_FAIL+1)); fi
  done < "$MODDIR/files/lib.map"
fi
{
  echo "=== CORD $(date) mode=$MODE"
  if [ "$DO_POLICY" = "0" ]; then
    echo "policy source: AIDL HAL, xml mount skipped"
  fi
  echo "RESULT: policy=$P_OK/$P_FAIL lib=$L_OK/$L_FAIL"
} >> "$LOG" 2>&1
