#!/system/bin/sh
# Prepares /data/vendor/audio so the fork engine can persist its own state.
#
# The effect instance is created by the framework inside the audio HAL
# (hal_audio_default), which has no client handle for the app to push
# parameters into. The engine therefore records every accepted parameter into
#     /data/vendor/audio/jamesdsp_state.bin
# and replays that file when a new instance is constructed, so the last applied
# settings keep running after the app is closed or uninstalled.
#
# This file only prepares the environment (idempotent, safe to call twice):
#   * create /data/vendor/audio when the ROM does not ship it,
#   * seed a default state once if the module carries state.bin,
#   * detect the real owner from the directory contents instead of guessing,
#   * chmod 0660 + chcon u:object_r:vendor_audio_data_file:s0,
#   * log exists / size / mtime / owner / mode / label to the module boot log.
#
# It is sourced by post-fs-data.sh (early) and service.sh (late) and does no
# mounting and no partition-wide relabelling: hybrid_mount owns mounts and the
# SELinux context of every mounted partition file. sepolicy.rule carries the
# hal_audio_default rules that let the HAL use this one path.
#
# Module directory: the metamodule defines MODPATH (canonical) and MODDIR
# (what this file used in the standalone module), so accept either.

JDSP_MODULE_DIR=${MODPATH:-$MODDIR}
JDSP_STATE_DIR=/data/vendor/audio
JDSP_STATE_FILE=$JDSP_STATE_DIR/jamesdsp_state.bin
JDSP_STATE_SEED=$JDSP_MODULE_DIR/state.bin
# AID_AUDIOSERVER:AID_AUDIO. Only used when the directory is brand new and has
# no existing entry to copy an owner from; on this fleet hal_audio_default runs
# as 1041:1005 and the state file it creates is 1041:1005.
JDSP_STATE_FALLBACK_UIDGID=1041:1005

jdsp_state_log() {
	[ -n "${JDSP_MODULE_DIR:-}" ] || return 0
	echo "[state]${jdsp_state_tag:+ $jdsp_state_tag} $*" >> "$JDSP_MODULE_DIR/boot.log" 2>/dev/null
}

# stat -c wrapper. Toybox and busybox both support -c; if a ROM does not, the
# log line simply says "unknown" instead of failing the boot script.
jdsp_state_meta() {
	jdsp_state_v=$(stat -c "$1" "$2" 2>/dev/null)
	[ -n "$jdsp_state_v" ] && printf '%s\n' "$jdsp_state_v" || printf 'unknown\n'
}

jdsp_state_label() {
	jdsp_state_l=$(ls -Z "$1" 2>/dev/null | awk 'NR == 1 { print $1 }')
	case "$jdsp_state_l" in
		''|'?') printf 'unknown\n' ;;
		*) printf '%s\n' "$jdsp_state_l" ;;
	esac
}

# Prints "<uid>:<gid> <source>", source = file|dir|fallback.
# Order: an existing entry in the directory (the HAL wrote it, so its owner is
# exactly the identity the state file needs) -> the directory itself (unless it
# is root-owned, i.e. we created it) -> AOSP AIDs.
# Two fields are printed because a command substitution runs in a subshell, so
# the caller cannot rely on this function setting a global.
jdsp_state_detect_owner() {
	# 1) our own state file, when present: the HAL wrote it, so its owner is
	#    exactly the identity the next write needs.
	if [ -e "$JDSP_STATE_FILE" ]; then
		jdsp_state_o=$(stat -c '%u:%g' "$JDSP_STATE_FILE" 2>/dev/null)
		case "$jdsp_state_o" in
			''|0:0|0:) ;;
			*) printf '%s statefile\n' "$jdsp_state_o"; return 0 ;;
		esac
	fi
	# 2) another regular file written by the HAL (custom_enable_state.bin ...).
	#    Directories are skipped: /data/vendor/audio also contains subdirs owned
	#    by other services (acdbdata -> 1013:1005), and they must not win.
	for jdsp_state_f in "$JDSP_STATE_DIR"/*; do
		[ -f "$jdsp_state_f" ] || continue
		jdsp_state_o=$(stat -c '%u:%g' "$jdsp_state_f" 2>/dev/null)
		case "$jdsp_state_o" in
			''|0:0|0:) continue ;;
		esac
		printf '%s file\n' "$jdsp_state_o"
		return 0
	done
	jdsp_state_o=$(stat -c '%u:%g' "$JDSP_STATE_DIR" 2>/dev/null)
	case "$jdsp_state_o" in
		''|0:0|0:) ;;
		*) printf '%s dir\n' "$jdsp_state_o"; return 0 ;;
	esac
	printf '%s fallback\n' "$JDSP_STATE_FALLBACK_UIDGID"
}

jdsp_state_prepare() {
	jdsp_state_tag=${1:-boot}
	jdsp_state_created=0

	# /data/vendor is a separate mount on most devices and may not exist yet
	# when post-fs-data runs; service.sh runs later and will do the real work.
	if [ ! -d /data/vendor ]; then
		jdsp_state_log "skip: /data/vendor not mounted yet, retrying in service.sh"
		return 0
	fi

	if [ ! -d "$JDSP_STATE_DIR" ]; then
		mkdir -p "$JDSP_STATE_DIR" 2>/dev/null && jdsp_state_created=1
	fi
	if [ ! -d "$JDSP_STATE_DIR" ]; then
		jdsp_state_log "ERROR cannot create $JDSP_STATE_DIR, state persistence disabled this boot"
		return 1
	fi

	jdsp_state_detected=$(jdsp_state_detect_owner)
	case "$jdsp_state_detected" in
		*' '*) ;;
		*) jdsp_state_detected="$JDSP_STATE_FALLBACK_UIDGID fallback" ;;
	esac
	jdsp_state_owner=${jdsp_state_detected%% *}
	jdsp_state_owner_src=${jdsp_state_detected##* }
	case "$jdsp_state_owner_src" in
		statefile) jdsp_state_log "owner copied from the state file itself: $jdsp_state_owner" ;;
		file) jdsp_state_log "owner copied from an existing regular file in $JDSP_STATE_DIR: $jdsp_state_owner" ;;
		dir)  jdsp_state_log "owner copied from the directory itself: $jdsp_state_owner" ;;
		*)    jdsp_state_log "no existing owner to copy, using fallback AID ($jdsp_state_owner)" ;;
	esac

	if [ "$jdsp_state_created" = "1" ]; then
		chown "$jdsp_state_owner" "$JDSP_STATE_DIR" 2>/dev/null
		chmod 0770 "$JDSP_STATE_DIR" 2>/dev/null
		jdsp_state_log "created $JDSP_STATE_DIR owner=$jdsp_state_owner mode=0770"
	fi
	# Label the directory too: one we just created may not have inherited
	# vendor_audio_data_file, and the HAL needs 'search' on the parent as well.
	# This is the only relabel the module does; partition files are left to
	# hybrid_mount.
	chcon u:object_r:vendor_audio_data_file:s0 "$JDSP_STATE_DIR" 2>/dev/null ||
		jdsp_state_log "WARN chcon $JDSP_STATE_DIR failed"

	if [ -f "$JDSP_STATE_SEED" ] && [ ! -e "$JDSP_STATE_FILE" ]; then
		if cp -f "$JDSP_STATE_SEED" "$JDSP_STATE_FILE" 2>/dev/null; then
			jdsp_state_log "seeded $JDSP_STATE_FILE from $JDSP_STATE_SEED"
		else
			jdsp_state_log "WARN could not seed $JDSP_STATE_FILE from $JDSP_STATE_SEED"
		fi
	fi

	if [ -e "$JDSP_STATE_FILE" ]; then
		chown "$jdsp_state_owner" "$JDSP_STATE_FILE" 2>/dev/null
		chmod 0660 "$JDSP_STATE_FILE" 2>/dev/null
		chcon u:object_r:vendor_audio_data_file:s0 "$JDSP_STATE_FILE" 2>/dev/null ||
			jdsp_state_log "WARN chcon $JDSP_STATE_FILE failed"
		jdsp_state_log "state file EXISTS $JDSP_STATE_FILE size=$(jdsp_state_meta '%s' "$JDSP_STATE_FILE") mtime=$(jdsp_state_meta '%y' "$JDSP_STATE_FILE") owner=$(jdsp_state_meta '%u:%g' "$JDSP_STATE_FILE") mode=$(jdsp_state_meta '%a' "$JDSP_STATE_FILE") label=$(jdsp_state_label "$JDSP_STATE_FILE")"
	else
		jdsp_state_log "state file ABSENT $JDSP_STATE_FILE (engine starts from built-in defaults and writes the file on the first parameter change)"
	fi
	jdsp_state_log "state dir $JDSP_STATE_DIR owner=$(jdsp_state_meta '%u:%g' "$JDSP_STATE_DIR") mode=$(jdsp_state_meta '%a' "$JDSP_STATE_DIR") label=$(jdsp_state_label "$JDSP_STATE_DIR")"

	return 0
}