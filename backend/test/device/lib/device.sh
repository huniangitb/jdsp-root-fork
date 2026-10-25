#!/usr/bin/env bash
# device.sh — shared device selection + test-dex provisioning for the v2 acceptance scripts.
#
# Sourced by:
#   scripts/verify-v2.sh
#   module/accept-v2.sh
#   backend/test/device/run_acceptance.sh
#
# It contains no device state changes of its own: it only resolves which device to
# talk to and (on request) pushes the test dex files into a root-owned directory.
# ART refuses world-writable dex files, so the dex always lands in a 0755 root
# directory as 0644 files (verified on xaga / Android 15).
#
# Serial resolution order (identical in all three scripts):
#   1. explicit argument (first positional / --serial)
#   2. $DSH_SERIAL
#   3. $ANDROID_SERIAL
#   4. $SERIAL
#   5. the only online device reported by "adb devices"
#   -> with 0 or >1 online devices the scripts fail with exit 2 and never guess.
#
# The library is sourced, not executed: it defines functions only.

JDSP_LIB_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# lib/ -> device/ -> test/ -> backend/ -> repo root
JDSP_REPO_ROOT=$(cd "$JDSP_LIB_DIR/../../../.." && pwd)
JDSP_SDK=${ANDROID_SDK:-${ANDROID_HOME:-/home/bdy/Android/Sdk}}
JDSP_ADB=${ADB:-adb}

# ------------------------------------------------------------------ helpers --

jdsp_log() { printf '%s\n' "$*"; }
jdsp_err() { printf '%s\n' "$*" >&2; }

# Online devices (state == "device"), one serial per line.
jdsp_list_devices() {
    "$JDSP_ADB" devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1 }'
}

# Echo the serial to use, or fail (exit 2) with an explanation.
# $1 = optional explicit serial
jdsp_resolve_serial() {
    local requested=${1:-}
    local candidates=()
    local d
    if [ -n "$requested" ]; then
        candidates+=("$requested")
    elif [ -n "${DSH_SERIAL:-}" ]; then
        candidates+=("$DSH_SERIAL")
    elif [ -n "${ANDROID_SERIAL:-}" ]; then
        candidates+=("$ANDROID_SERIAL")
    elif [ -n "${SERIAL:-}" ]; then
        candidates+=("$SERIAL")
    else
        local list
        list=$(jdsp_list_devices)
        local count
        count=$(printf '%s' "$list" | grep -c . || true)
        if [ "$count" = "0" ]; then
            jdsp_err "ERROR: no online adb device (run 'adb devices'); pass a serial explicitly."
            return 2
        fi
        if [ "$count" != "1" ]; then
            jdsp_err "ERROR: $count online devices, refusing to guess:"
            printf '%s\n' "$list" | sed 's/^/  /' >&2
            jdsp_err "       pass the serial explicitly (first argument) or set ANDROID_SERIAL/DSH_SERIAL."
            return 2
        fi
        printf '%s\n' "$list"
        return 0
    fi

    local serial=${candidates[0]}
    # An explicitly requested serial is verified with "adb -s <serial> get-state"
    # instead of the device list: a device can be reachable (TCP adb, serial console)
    # without being listed as "device" by "adb devices".
    if "$JDSP_ADB" -s "$serial" get-state >/dev/null 2>&1; then
        printf '%s\n' "$serial"
        return 0
    fi
    jdsp_err "ERROR: device '$serial' is not available (adb -s $serial get-state failed)."
    local online
    online=$(jdsp_list_devices)
    if [ -n "$online" ]; then
        jdsp_err "       online devices:"
        printf '%s\n' "$online" | sed 's/^/  /' >&2
    else
        jdsp_err "       (none online)"
    fi
    return 2
}

# Set JDSP_SERIAL and JDSP_ADB_SN from $1 (optional explicit serial).
jdsp_select_device() {
    local serial
    serial=$(jdsp_resolve_serial "${1:-}") || return 2
    JDSP_SERIAL=$serial
    JDSP_ADB_SN=("$JDSP_ADB" -s "$JDSP_SERIAL")
    jdsp_log "device      : $JDSP_SERIAL"
    return 0
}

# adb with the selected device.
jdsp_adb() { "${JDSP_ADB_SN[@]}" "$@"; }

# adb shell (no root escalation).
jdsp_sh() { jdsp_adb shell "$1" 2>&1 | tr -d '\r'; }

# True when the adb shell is already root.
jdsp_shell_is_root() {
    local uid
    uid=$(jdsp_adb shell id -u 2>/dev/null | tr -d '\r' | tr -d ' ' | tail -1)
    [ "$uid" = "0" ]
}

# Run a background command on the device; the call returns immediately.
# $1 = command line (must redirect stdout/stderr itself)
# The "cd DIR; setsid ... &" shape is used on purpose: "sh -c '... && nohup ... &'"
# keeps adb shell's stdout pipe open and makes adb block until the child exits.
jdsp_run_bg() {
    local cmd=$1
    local dir=${2:-/data/local/tmp}
    if jdsp_shell_is_root; then
        jdsp_adb shell "cd $dir; setsid $cmd &" >/dev/null 2>&1
    else
        jdsp_adb shell "su -c 'cd $dir; setsid $cmd &'" >/dev/null 2>&1
    fi
}

# ------------------------------------------------------------- dex building --

jdsp_android_jar() {
    ls "$JDSP_SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1
}

jdsp_d8() {
    if [ -x "$JDSP_SDK/cmdline-tools/latest/bin/d8" ]; then
        printf '%s\n' "$JDSP_SDK/cmdline-tools/latest/bin/d8"
        return 0
    fi
    ls -d "$JDSP_SDK"/build-tools/*/d8 2>/dev/null | sort -V | tail -1
}

jdsp_aidl() {
    ls -d "$JDSP_SDK"/build-tools/*/aidl 2>/dev/null | sort -V | tail -1
}

jdsp_build_dir() {
    printf '%s\n' "$JDSP_REPO_ROOT/backend/test/build/shared"
}

# Build tools/Tone.java into a dex (the repo's canonical test tone).
# $1 = output dex path
jdsp_build_tone_dex() {
    local out=$1
    local aj d8 build
    aj=$(jdsp_android_jar)
    d8=$(jdsp_d8)
    [ -f "$aj" ] || { jdsp_err "ERROR: missing android.jar under $JDSP_SDK"; return 2; }
    [ -x "$d8" ] || { jdsp_err "ERROR: missing d8 under $JDSP_SDK"; return 2; }
    build=$(jdsp_build_dir)
    mkdir -p "$build/tone-classes" "$build/tone-dex"
    if ! javac --release 8 -nowarn -classpath "$aj" -d "$build/tone-classes" \
            "$JDSP_REPO_ROOT/tools/Tone.java" >"$build/tone-javac.log" 2>&1; then
        jdsp_err "ERROR: javac tools/Tone.java failed"
        cat "$build/tone-javac.log" >&2
        return 2
    fi
    if ! "$d8" --lib "$aj" --min-api 29 --output "$build/tone-dex" \
            "$build/tone-classes/Tone.class" >"$build/tone-d8.log" 2>&1; then
        jdsp_err "ERROR: d8 Tone.class failed"
        cat "$build/tone-d8.log" >&2
        return 2
    fi
    cp "$build/tone-dex/classes.dex" "$out"
}

# Build jdspcli.dex: prefer backend/out, otherwise compile the CLI standalone
# (same aidl -> javac -> d8 recipe as backend/build.sh) without touching
# backend/out, so a concurrent daemon build cannot break the acceptance run.
# $1 = output dex path
jdsp_build_cli_dex() {
    local out=$1
    if [ -s "$JDSP_REPO_ROOT/backend/out/jdspcli.dex" ]; then
        cp "$JDSP_REPO_ROOT/backend/out/jdspcli.dex" "$out"
        return 0
    fi
    local aj d8 aidl build
    aj=$(jdsp_android_jar)
    d8=$(jdsp_d8)
    aidl=$(jdsp_aidl)
    [ -f "$aj" ] || { jdsp_err "ERROR: missing android.jar under $JDSP_SDK"; return 2; }
    [ -x "$d8" ] || { jdsp_err "ERROR: missing d8 under $JDSP_SDK"; return 2; }
    [ -x "$aidl" ] || { jdsp_err "ERROR: missing aidl under $JDSP_SDK/build-tools"; return 2; }
    build=$(jdsp_build_dir)
    mkdir -p "$build/cli-gen" "$build/cli-classes" "$build/cli-dex"
    local aidl_files
    mapfile -t aidl_files < <(find "$JDSP_REPO_ROOT/backend/aidl" -name '*.aidl' | sort)
    "$aidl" --lang=java -I"$JDSP_REPO_ROOT/backend/aidl" --out="$build/cli-gen" \
        "${aidl_files[@]}" || { jdsp_err "ERROR: aidl failed"; return 2; }
    local sources
    mapfile -t sources < <(find "$JDSP_REPO_ROOT/backend/src/com/jdsp/cli" \
        "$JDSP_REPO_ROOT/backend/src/com/jdsp/common" "$build/cli-gen" -name '*.java' | sort)
    javac --release 8 -nowarn -Xlint:-options -classpath "$aj" -d "$build/cli-classes" \
        "${sources[@]}" || { jdsp_err "ERROR: javac jdspcli failed"; return 2; }
    find "$build/cli-classes" -name '*.class' > "$build/cli-files.txt"
    "$d8" --lib "$aj" --min-api 29 --output "$build/cli-dex" \
        $(cat "$build/cli-files.txt") >/dev/null || { jdsp_err "ERROR: d8 jdspcli failed"; return 2; }
    cp "$build/cli-dex/classes.dex" "$out"
}

# Build tools/SignalProbe.java into a dex (the signal level probe).
# $1 = output dex path
jdsp_build_probe_dexs() {
    local out=$1
    local aj d8 build
    aj=$(jdsp_android_jar)
    d8=$(jdsp_d8)
    [ -f "$aj" ] || { jdsp_err "ERROR: missing android.jar under $JDSP_SDK"; return 2; }
    [ -x "$d8" ] || { jdsp_err "ERROR: missing d8 under $JDSP_SDK"; return 2; }
    build=$(jdsp_build_dir)
    mkdir -p "$build/probe-classes" "$build/probe-dex"
    if ! javac --release 8 -nowarn -classpath "$aj" -d "$build/probe-classes" \
            "$JDSP_REPO_ROOT/tools/SignalProbe.java" >"$build/probe-javac.log" 2>&1; then
        jdsp_err "ERROR: javac tools/SignalProbe.java failed"
        cat "$build/probe-javac.log" >&2
        return 2
    fi
    local classes
    mapfile -t classes < <(find "$build/probe-classes" -name '*.class' | sort)
    if ! "$d8" --lib "$aj" --min-api 29 --output "$build/probe-dex" \
            "${classes[@]}" >"$build/probe-d8.log" 2>&1; then
        jdsp_err "ERROR: d8 SignalProbe failed"
        cat "$build/probe-d8.log" >&2
        return 2
    fi
    cp "$build/probe-dex/classes.dex" "$out"
}

# Build the native offline renderer (tools/native/offline_render.c) with the NDK.
# $1 = output binary path
jdsp_build_offline_renderer() {
    local out=$1
    local ndk tc
    ndk=${NDK:-/home/bdy/Android/Sdk/ndk/30.0.16248370}
    [ -d "$ndk" ] || ndk=$(ls -d /home/bdy/Android/Sdk/ndk/* 2>/dev/null | sort -V | tail -1)
    tc="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
    if [ ! -x "$tc/clang" ]; then
        jdsp_err "ERROR: no NDK clang at $tc/clang (set NDK)"
        return 2
    fi
    mkdir -p "$(dirname "$out")"
    if ! "$tc/clang" --target=aarch64-linux-android29 -O2 -o "$out" \
            "$JDSP_REPO_ROOT/tools/native/offline_render.c" -ldl -lm \
            >"$JDSP_REPO_ROOT/backend/test/build/offline-clang.log" 2>&1; then
        jdsp_err "ERROR: could not build the offline renderer"
        cat "$JDSP_REPO_ROOT/backend/test/build/offline-clang.log" >&2
        return 2
    fi
    return 0
}

# Copy the vendor engine into a directory the app_process / native linker
# namespace allows and build/push the offline renderer next to it.
# $1 = device directory
jdsp_push_offline_renderer() {
    local dir=${1:-/data/local/tmp/jdsp-offline}
    local build
    build=$(jdsp_build_dir)
    mkdir -p "$build"
    jdsp_build_offline_renderer "$build/offline_render" || return 2
    jdsp_adb shell "mkdir -p $dir && chmod 755 $dir" >/dev/null 2>&1
    # the linker namespace of an app_process process does not include /vendor,
    # so the engine is copied to a permitted path and loaded from there
    jdsp_adb shell "cp /vendor/lib64/soundfx/libjamesdsp.so $dir/libjamesdsp.so 2>/dev/null && chmod 644 $dir/libjamesdsp.so" >/dev/null 2>&1
    jdsp_adb push "$build/offline_render" "$dir/offline_render" >/dev/null \
        || { jdsp_err "ERROR: push offline_render failed"; return 2; }
    jdsp_adb shell "chmod 755 $dir/offline_render" >/dev/null 2>&1
    jdsp_log "offline    : $dir/offline_render (+ libjamesdsp.so copy)"
    return 0
}

# Build and push tone.dex, jdspcli.dex and signalprobe.dex.
# $1 = device directory
jdsp_build_and_push_all() {
    local dir=${1:-/data/local/tmp/jdsp-signal}
    local build
    build=$(jdsp_build_dir)
    mkdir -p "$build"
    jdsp_build_tone_dex "$build/tone.dex" || return 2
    jdsp_build_cli_dex "$build/jdspcli.dex" || return 2
    jdsp_build_probe_dexs "$build/signalprobe.dex" || return 2
    jdsp_adb shell "mkdir -p $dir && chmod 755 $dir" >/dev/null 2>&1
    local f
    for f in tone.dex jdspcli.dex signalprobe.dex; do
        jdsp_adb push "$build/$f" "$dir/$f" >/dev/null \
            || { jdsp_err "ERROR: push $f failed"; return 2; }
    done
    jdsp_adb shell "chmod 644 $dir/tone.dex $dir/jdspcli.dex $dir/signalprobe.dex" >/dev/null 2>&1
    jdsp_log "test dex    : $dir/tone.dex, $dir/jdspcli.dex, $dir/signalprobe.dex"
    return 0
}

# Push tone.dex and jdspcli.dex into a root-owned device directory.
# $1 = device directory (default /data/local/tmp/jdsp-verify)
jdsp_push_test_dexs() {
    local dir=${1:-/data/local/tmp/jdsp-verify}
    local build
    build=$(jdsp_build_dir)
    mkdir -p "$build"
    jdsp_build_tone_dex "$build/tone.dex" || return 2
    jdsp_build_cli_dex "$build/jdspcli.dex" || return 2
    jdsp_adb shell "mkdir -p $dir && chmod 755 $dir" >/dev/null 2>&1
    jdsp_adb push "$build/tone.dex" "$dir/tone.dex" >/dev/null \
        || { jdsp_err "ERROR: push tone.dex failed"; return 2; }
    jdsp_adb push "$build/jdspcli.dex" "$dir/jdspcli.dex" >/dev/null \
        || { jdsp_err "ERROR: push jdspcli.dex failed"; return 2; }
    jdsp_adb shell "chmod 644 $dir/tone.dex $dir/jdspcli.dex" >/dev/null 2>&1
    jdsp_log "test dex    : $dir/tone.dex, $dir/jdspcli.dex"
    return 0
}
