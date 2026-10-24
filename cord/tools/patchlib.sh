PATCH_ANCHORS="AudioTrack3set AudioTrackC1E19audio_stream_type AudioTrackC2E19audio_stream_type"

# Force mFlags inside AudioTrack::set() so nothing downstream can drop the
# deep-buffer bit.  0x9 = PRIMARY|DEEP_BUFFER is the value AOSP itself
# produces for a normal music track; it also stays clear of OPLUS
# checkHdSampleMusicPlayback(), which only rewrites an exact 0x8.
patch_flags_body() {
  PL_LIB="$1"; PL_FL="$2"; PL_LOG="$3"
  OUT="$PL_LIB.body"
  # -F only.  Both later experiments were withdrawn:
  #   -B (retarget the skip branches, so EVERY track is forced) broke playback:
  #      offload / direct / raw tracks must keep their flag combination.
  #   -R (retarget into AOSP's own FAST->PRIMARY rewrite) broke it the same way.
  # Forcing flags on tracks that never asked for the downgrade is not safe.
  if "$PATCHER" -F "$PL_LIB" "$PL_FL" "$OUT" >/dev/null 2>>"$PL_LOG"; then
    mv -f "$OUT" "$PL_LIB" 2>/dev/null && return 0
    rm -f "$OUT" 2>/dev/null
    return 0
  fi
  rm -f "$OUT" 2>/dev/null
  return 1
}

patch_lib() {
  PL_LIB="$1"; PL_OUT="$2"; PL_FL="$3"; PL_LOG="$4"

  # Server-side net: make AudioFlinger refuse every fast track.  Client-side
  # forced flags are enough for apps that reach AudioTrack::set(), but anything
  # that still asks for FAST ends up on the PRIMARY|FAST thread, which can never
  # host a software effect.  OPT-IN only -- it also removes the low-latency
  # path for games/VoIP, so never enable it implicitly:
  #     touch /data/adb/modules/CORD/flinger_fast_deny
  case "$PL_LIB" in
    */libaudioflinger.so)
      if [ ! -f /data/adb/modules/CORD/flinger_fast_deny ]; then
        echo "  flinger fast-deny disabled (opt-in)"
        return 1
      fi
      if "$PATCHER" -AF "$PL_LIB" "$PL_OUT" 2>>"$PL_LOG"; then
        echo "  flinger: fast-track admission denied"
        return 0
      fi
      rm -f "$PL_OUT" 2>/dev/null
      echo "  flinger: pattern not found, skipped"
      return 1 ;;
  esac

  cp -f "$PL_LIB" "$PL_OUT" 2>/dev/null
  for PL_A in $PATCH_ANCHORS; do
    if "$PATCHER" "$PL_OUT" "$PL_A" "$PL_FL" "$PL_OUT.tmp" 2>>"$PL_LOG"; then
      mv -f "$PL_OUT.tmp" "$PL_OUT" 2>/dev/null
      echo "  anchor=$PL_A"
      case "$PL_FL" in
        8) patch_flags_body "$PL_OUT" 9 "$PL_LOG" && echo "  mflags forced 0x9" ;;
        2) patch_flags_body "$PL_OUT" 1 "$PL_LOG" && echo "  mflags forced 0x1" ;;
      esac
      return 0
    fi
    rm -f "$PL_OUT.tmp" 2>/dev/null
  done
  echo "  no anchor matched, candidates:"
  "$PATCHER" -s "$PL_LIB" AudioTrack >>"$PL_LOG" 2>&1
  rm -f "$PL_OUT" 2>/dev/null
  return 1
}