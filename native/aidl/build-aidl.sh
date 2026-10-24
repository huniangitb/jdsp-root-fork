#!/usr/bin/env bash
#
# build-aidl.sh - build the AIDL effect HAL variant of the JamesDSP fork.
#
#   ./build-aidl.sh soong          # (recommended) build in an AOSP/CLO tree
#   ./build-aidl.sh vendor         # standalone NDK attempt (best effort)
#   ./build-aidl.sh headers        # only (re)generate the AIDL headers
#
# The AIDL interface versions are platform specific: an effect library links
# against android.hardware.audio.effect-V<n>-ndk.so and
# android.media.audio.common.types-V<m>-ndk.so, and those versions differ per
# Android release (c16 / Android 16 has effect V3 + types V4/V5; the reference
# library needs V2/V3 which do not exist there).  Select them with:
#
#   AIDL_EFFECT_V=3 AIDL_TYPES_V=4 ./build-aidl.sh vendor
#
# Why Soong is the primary path: the AIDL effect implementation needs AOSP's
# effect-impl helpers (EffectImpl/EffectContext/EffectThread) *and* the platform
# headers they include (android/binder_interface_utils.h, utils/Errors.h,
# utils/Trace.h, system/audio_effects/aidl_effects_utils.h, ...).  AOSP builds
# those from the tree; the NDK does not ship them.  The vendor mode below vendors
# what is vendorable (the Apache-2.0 effect-impl sources live in aidl-vendor/)
# and stops with an explicit message when a platform-internal header is missing
# instead of producing a broken .so.
#
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE" && pwd)
JNI=$ROOT/Main/libjamesdsp/jni/jamesdsp
VENDOR=$ROOT/aidl-vendor
HEADERS=${HEADERS:-$ROOT/aidl-headers}
OUT=${OUT:-/home/bdy/work/jdsp-state/out-aidl}
AIDL_TOOL=${AIDL_TOOL:-/home/bdy/Android/Sdk/build-tools/35.0.0/aidl}
NDK=${NDK:-/home/bdy/Android/Sdk/ndk/30.0.16248370}
API=${API:-31}
CXX=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android$API-clang++
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android$API-clang
AIDL_EFFECT_V=${AIDL_EFFECT_V:-3}
AIDL_TYPES_V=${AIDL_TYPES_V:-4}

die() { echo "ERROR: $*" >&2; exit 1; }

gen_headers() {
    [ -x "$AIDL_TOOL" ] || die "aidl compiler not found at $AIDL_TOOL"
    echo "== generating AIDL headers (effect V$AIDL_EFFECT_V, types V$AIDL_TYPES_V)"
    rm -rf "$HEADERS" "$ROOT/aidl-out"; mkdir -p "$HEADERS" "$ROOT/aidl-out"
    "$AIDL_TOOL" --lang=ndk --structured --stability=vintf --min_sdk_version="$API" \
        --out="$ROOT/aidl-out" --header_out="$HEADERS" \
        -I "$VENDOR/aidl" $(find "$VENDOR/aidl" -name '*.aidl') \
        || die "aidl header generation failed"
    echo "   $(find "$HEADERS" -name '*.h' | wc -l) headers"
}

case "${1:-soong}" in
headers)
    gen_headers
    exit 0
    ;;
soong)
    cat <<'EOF'
== Soong build (primary path)
   1. place this directory in an AOSP/CLO tree, e.g.
        cp -a Main/libjamesdsp/jni/jamesdsp \
              $ANDROID_BUILD_TOP/device/<vendor>/<device>/jamesdsp/
   2. make it part of the build (product packages or the effect HAL's Android.bp);
      the "aidlaudioeffectservice_defaults" defaults come from
      hardware/interfaces/audio/aidl/default/Android.bp.
   3. build:
        source build/envsetup.sh && lunch <product>-userdebug
        m libjamesdspaidl
      Result: vendor/lib64/soundfx/libjamesdspaidl.so
   4. install it next to the stock effect library; the effect HAL loads it by
      effect UUID f27317f4-c984-4de6-9a90-545759495bf2.
EOF
    exit 0
    ;;
vendor) ;;
*) die "unknown mode: $1 (use soong|vendor|headers)" ;;
esac

echo "== vendor build (best effort, NDK + vendored AOSP headers)"
[ -d "$HEADERS" ] || gen_headers
mkdir -p "$OUT/obj"

CFLAGS_COMMON="-O2 -fPIC -fvisibility=hidden -ffunction-sections -fdata-sections \
-DNDEBUG -DJAMESDSP_REFERENCE_IMPL -DBACKEND_NDK -Wall -Wextra \
-Wno-unused-parameter -Wno-unused-function -Wno-unused-variable -Wno-sign-compare \
-Wno-missing-field-initializers -Wno-incompatible-pointer-types \
-Wno-incompatible-function-pointer-types -Wno-error=incompatible-pointer-types \
-Wno-int-conversion -Wno-error=int-conversion -Wno-misleading-indentation"
CXXFLAGS="$CFLAGS_COMMON -std=c++20 -DAOSP_SOONG_BUILD"
INCS="-I$JNI -I$HEADERS -I$VENDOR/include -I$VENDOR/shim $EXTRA_INCS"
STUBS=${STUBS:-$VENDOR/device-libs}

SRCS_C=$(cd "$JNI" && { echo cpthread.c; echo jdsp_impl.c; echo jdsp_engine_slot.c; \
        echo peq_loudness.c; echo jdsp_state.c; find jdsp -name '*.c'; })
n=0
for f in $SRCS_C; do
    $CC $CFLAGS_COMMON $INCS -c "$JNI/$f" -o "$OUT/obj/$(echo "$f" | tr '/' '_' | sed 's/\\.c$/.o/')" \
        || die "C compile failed: $f"
    n=$((n+1))
done
for _o in "$OUT"/obj/*.c; do [ -f "$_o" ] || continue; mv -f "$_o" "$(echo "$_o" | sed 's/\.c$/\.o/')"; done
echo "   compiled $n engine sources"; echo "   [trace] obj after engine: $(ls "$OUT/obj" | wc -l)"

$CXX $CXXFLAGS $INCS -c "$JNI/jamesdsp_aidl.cpp" -o "$OUT/obj/jamesdsp_aidl.o" 2>"$OUT/bind.log" \
    || { grep -m3 'fatal error' "$OUT/bind.log" >&2
         die "C++ binding needs AOSP platform headers (see $OUT/bind.log).
     Those headers (android/binder_interface_utils.h, utils/Errors.h,
     system/audio_effects/aidl_effects_utils.h, ...) only exist in an AOSP/CLO
     tree: use './build-aidl.sh soong' (primary path) or pass a full AOSP include
     set through EXTRA_INCS."; }
for f in EffectImpl EffectContext EffectThread; do
    [ -f "$VENDOR/effect-impl/$f.cpp" ] || continue
    $CXX $CXXFLAGS $INCS -c "$VENDOR/effect-impl/$f.cpp" -o "$OUT/obj/$f.o" \
        || die "AOSP effect-impl compile failed: $f (needs the AOSP tree)"
done

[ -d "$STUBS" ] || die "missing device stub libraries in $STUBS
    (extract from the target device: android.hardware.audio.effect-V$AIDL_EFFECT_V-ndk.so,
     android.media.audio.common.types-V$AIDL_TYPES_V-ndk.so, android.hardware.common-V2-ndk.so,
     android.hardware.common.fmq-V1-ndk.so, libbinder_ndk.so, libbase.so, libutils.so,
     libcutils.so, libfmq.so, liblog.so, libc++.so)"
echo "   [trace] obj before link: $(ls "$OUT/obj" | wc -l)"; echo "== linking"
"$CXX" -shared -nostdlib++ -Wl,-soname,libjamesdspaidl.so \
    -Wl,--exclude-libs,ALL \
    -o "$OUT/libjamesdspaidl.so" "$OUT"/obj/*.o \
    -L"$STUBS" -Wl,-rpath-link,"$STUBS" \
    "$STUBS/android.hardware.audio.effect-V$AIDL_EFFECT_V-ndk.so" \
    "$STUBS/android.media.audio.common.types-V$AIDL_TYPES_V-ndk.so" \
    "$STUBS/android.hardware.common-V2-ndk.so" "$STUBS/android.hardware.common.fmq-V1-ndk.so" \
    -lbinder_ndk -lfmq -lbase -lutils -lcutils -llog -lc++ -lm -ldl -lc \
    || die "link failed"
sha256sum "$OUT/libjamesdspaidl.so"
echo "== done: $OUT/libjamesdspaidl.so"
