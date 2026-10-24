#!/system/bin/sh
MODE=deep          # deep = best effects, primary = lower latency
STRIP_DIRECT=1     # 1 = strip direct_pcm/mmap, 0 = keep hi-res direct output

MODPATH=${MODPATH:-${0%/*}}
command -v ui_print >/dev/null 2>&1 || ui_print() { echo "$1"; }
mkdir -p "$MODPATH/files/policy" "$MODPATH/logs"
PATCHER="$MODPATH/tools/aab_patch"
. "$MODPATH/tools/patchlib.sh"
echo "$MODE" > "$MODPATH/files/mode"
CFG_SRC=$(dumpsys media.audio_policy 2>/dev/null | grep -m1 "Config source" | sed "s/.*Config source: *//")
echo "$CFG_SRC" > "$MODPATH/files/cfg_source"
case "$CFG_SRC" in
  AIDL*) ui_print "- policy source: AIDL HAL (xml will be skipped)" ;;
  *) ui_print "- policy source: $CFG_SRC" ;;
esac
ui_print "- mode: $MODE, strip_direct: $STRIP_DIRECT"

is_oplus() {
  [ -f /data/adb/cord_force_generic ] && return 1
  [ -d /my_product/etc ] && return 0
  for p in ro.build.version.oplusrom ro.build.version.opporom ro.oplus.version; do
    [ -n "$(getprop $p 2>/dev/null)" ] && return 0
  done
  case "$(getprop ro.product.brand 2>/dev/null)" in
    *OPPO*|*OnePlus*|*realme*|*oplus*) return 0 ;;
  esac
  return 1
}

patch_one() {
  PF="$1"
  sed -i -e 's/AUDIO_OUTPUT_FLAG_FAST //g' -e 's/ AUDIO_OUTPUT_FLAG_FAST//g'          -e 's/AUDIO_OUTPUT_FLAG_RAW //g' -e 's/ AUDIO_OUTPUT_FLAG_RAW//g'          -e 's/AUDIO_OUTPUT_FLAG_COMPRESS_OFFLOAD //g' -e 's/AUDIO_OUTPUT_FLAG_COMPRESS_OFFLOAD//g'          "$PF" 2>/dev/null
  sed -i -e 's/,compressed_offload//g' -e 's/compressed_offload,//g'          -e 's/,raw//g' -e 's/raw,//g' "$PF" 2>/dev/null
  if [ "$STRIP_DIRECT" = "1" ]; then
    sed -i -e 's/,direct_pcm//g' -e 's/direct_pcm,//g'            -e 's/,mmap_no_irq_out//g' -e 's/mmap_no_irq_out,//g' "$PF" 2>/dev/null
  fi
  sed -i 's/flags=""/flags="AUDIO_OUTPUT_FLAG_NONE"/g' "$PF" 2>/dev/null
}

IDX=0
: > "$MODPATH/files/policy.map"
collect() {
  SRC_DIR="$1"; [ -d "$SRC_DIR" ] || return
  for SRC in $(find "$SRC_DIR" -type f -name "*audio_policy*.xml" 2>/dev/null); do
    case "$SRC" in *volume*|*Volume*) continue;; esac
    IDX=$((IDX+1))
    DST="$MODPATH/files/policy/$IDX.xml"
    cp -f "$SRC" "$DST" 2>/dev/null || continue
    patch_one "$DST"
    if [ "$MODE" = "primary" ]; then
      sed -i 's|flags="AUDIO_OUTPUT_FLAG_DEEP_BUFFER"|flags="AUDIO_OUTPUT_FLAG_NONE"|' "$DST" 2>/dev/null
    fi
    echo "$SRC|$IDX" >> "$MODPATH/files/policy.map"
    ui_print "- policy: $SRC"
  done
}
collect /my_product/etc
collect /vendor/etc
collect /odm/etc
collect /system/etc
collect /product/etc
collect /system_ext/etc

if is_oplus; then
  ui_print "- ColorOS/OPLUS detected: binary patch enabled"
  chmod 755 "$PATCHER" 2>/dev/null
  case "$MODE" in primary) FL=2 ;; *) FL=8 ;; esac
  : > "$MODPATH/files/lib.map"
  LIDX=0
  for src in /system/lib64/libaaudio.so /system/lib64/libaaudio_internal.so \
             /system/lib64/libaaudio_internal_builder.so /system/lib64/libaaudio_internal_core.so \
             /system/lib64/libaaudio_internal_fifo.so /system/lib64/libaaudio_internal_legacy.so \
             /system/lib64/libaaudio_internal_mmap.so /system/lib64/libaaudio_internal_utils.so \
             /system/lib64/libaudioclient.so /system/lib64/libaudioclient_aidl_conversion.so \
             /system/lib64/libwilhelm.so /system/lib64/libOpenSLES.so \
             /system/lib64/vndk-sp/libaaudio_internal_legacy.so \
             /system/lib64/vndk-sp/libaudioclient.so \
             /apex/com.android.runtime/lib64/bionic/libaaudio_internal.so; do
    [ -f "$src" ] || continue
    base=${src##*/}
    LIDX=$((LIDX+1))
    if patch_lib "$src" "$MODPATH/files/lib_$LIDX.so" "$FL" "$MODPATH/logs/patch_$base.log"; then
      crc=$("$PATCHER" -c "$src" 2>/dev/null)
      echo "$crc" > "$MODPATH/files/lib_$LIDX.crc32"
      echo "$src|lib_$LIDX.so" >> "$MODPATH/files/lib.map"
      ui_print "- lib: $base -> lib_$LIDX.so (crc32=$crc)"
    else
      rm -f "$MODPATH/files/lib_$LIDX.so" "$MODPATH/files/lib_$LIDX.crc32" 2>/dev/null
      LIDX=$((LIDX-1))
    fi
  done
  [ -s "$MODPATH/files/lib.map" ] || ui_print "- no lib patched"
else
  # Non-OPLUS devices only need the server side net: AudioFlinger refusing
  # fast tracks.  Without it AAudio/OpenSL ES low-latency sessions create a
  # fast-mode playback thread, and AudioFlinger rejects software effects there
  # ("checkEffectCompatibility_l: non HW effect ... in fast mode").
  if [ -f /data/adb/modules/CORD/flinger_fast_deny ] || [ "$MODE" = "deep" ]; then
    ui_print "- deep mode: patching libaudioflinger (fast-track deny)"
    chmod 755 "$PATCHER" 2>/dev/null
    : > "$MODPATH/files/lib.map"
    for src in /system/lib64/libaudioflinger.so; do
      [ -f "$src" ] || continue
      if patch_lib "$src" "$MODPATH/files/lib_1.so" 8 "$MODPATH/logs/patch_flinger.log"; then
        crc=$("$PATCHER" -c "$src" 2>/dev/null)
        echo "$crc" > "$MODPATH/files/lib_1.crc32"
        echo "$src|lib_1.so" >> "$MODPATH/files/lib.map"
        ui_print "- lib: libaudioflinger.so (crc32=$crc)"
      else
        rm -f "$MODPATH/files/lib_1.so" "$MODPATH/files/lib_1.crc32"
      fi
    done
  else
    ui_print "- not ColorOS: skip binary patch"
    rm -f "$PATCHER"
    rm -f "$MODPATH/files/"*.so "$MODPATH/files/"*.crc32 "$MODPATH/files/lib.map" 2>/dev/null
  fi
fi
ui_print "- done, reboot required"
