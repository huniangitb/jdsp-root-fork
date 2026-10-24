#!/usr/bin/env bash
#
# Build libjamesdsp.so for Android from the (patched) JamesDSP extensions
# source with the NDK clang++ wrappers.
#
#   ./build/build.sh                                      # build src/ into out/
#   EXTRA_CFLAGS=-DJDSP_STATE_ENABLE=0 ./build/build.sh   # persistence off
#
# Why not ndk-build: the upstream Android.mk is out of date (it does not list
# jdsp/Effects/harmonicExpander.c, nosR2R.c and subHarmonicExpander.c, so
# ndk-build fails on undefined references) and it passes -mfpu=neon for arm64,
# which NDK r30 rejects.  This script uses the flags the upstream makefile
# intends to use.
#
set -euo pipefail

# 指向 Android NDK r30（含 clang 工具链）。必须设置，或与脚本同级放置 ndk/ 目录。
NDK=${NDK:?set NDK=/path/to/android-ndk-r30}
TOOLCHAIN_BIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SRC=${SRC:-$ROOT/src/Main/libjamesdsp/jni/jamesdsp}
OUT=${OUT:-$ROOT/out}
OBJ=${OBJ:-$ROOT/build/obj}
JOBS=${JOBS:-$(nproc)}
EXTRA_CFLAGS=${EXTRA_CFLAGS:-}
WITH_GC_SECTIONS=${WITH_GC_SECTIONS:-0}   # 0 keeps every symbol the shipped lib exports

# Source list == upstream Android.mk plus the effect files that only exist as
# sources (their symbols are present in the shipped libjamesdsp.so) plus the
# new state persistence module.  jdsp/Effects/eel2/cpthread.c is a duplicate of
# the top level cpthread.c and must not be compiled twice.
SRCS=()
for f in cpthread.c peq_loudness.c jamesdsp.c jdsp_state.c; do
    # jdsp_state.c only exists in the patched tree; the pristine tree is built
    # with this same script for an A/B symbol comparison.
    [ -f "$SRC/$f" ] && SRCS+=("$f")
done
while IFS= read -r f; do SRCS+=("$f"); done < <(
    cd "$SRC" && find jdsp -name '*.c' ! -path '*/eel2/cpthread.c' | sort
)

CFLAGS_COMMON="-x c -std=gnu17 -O3 -ffast-math -ffunction-sections -fdata-sections \
-fPIC -DNDEBUG -DJAMESDSP_REFERENCE_IMPL -Wall -Wextra \
-Wno-unused-parameter -Wno-unused-function -Wno-unused-variable \
-Wno-unused-but-set-variable -Wno-sign-compare -Wno-missing-field-initializers \
-Wno-format -Wno-parentheses -Wno-unknown-pragmas -Wno-tautological-compare \
-Wno-absolute-value -Wno-misleading-indentation -Wno-dangling-else \
-Wno-int-conversion -Wno-incompatible-function-pointer-types \
-Wno-error=incompatible-pointer-types -Wno-incompatible-pointer-types \
-Wno-error=int-conversion"

LDFLAGS_COMMON="-shared -nostdlib++ -Wl,-soname,libjamesdsp_effect.so \
-Wl,--exclude-libs,ALL -llog -lm"

build_abi() {
    local abi=$1 cxx=$2 march=$3 outname=$4
    local objdir=$OBJ/$abi
    rm -rf "$objdir"
    mkdir -p "$objdir" "$OUT"
    echo "== $abi: compiling ${#SRCS[@]} files"
    printf '%s\n' "${SRCS[@]}" | xargs -P "$JOBS" -I{} bash -c '
        src=$1; objdir=$2; cxx=$3; march=$4; cflags=$5; srcdir=$6; extra=$7
        obj="$objdir/$(printf "%s" "$src" | tr "/" "_" | sed "s/\\.c$/.o/")"
        $cxx $cflags $march $extra -c "$srcdir/$src" -o "$obj" || { echo "FAILED: $src" >&2; exit 1; }
    ' _ {} "$objdir" "$TOOLCHAIN_BIN/$cxx" "$march" "$CFLAGS_COMMON" "$SRC" "$EXTRA_CFLAGS"
    local gc=""
    [ "$WITH_GC_SECTIONS" = "1" ] && gc="-Wl,--gc-sections"
    echo "== $abi: linking $outname"
    "$TOOLCHAIN_BIN/$cxx" $LDFLAGS_COMMON $gc -o "$OUT/$outname" "$objdir"/*.o
    ls -l "$OUT/$outname"
}

build_abi arm64-v8a   aarch64-linux-android21-clang++   "-march=armv8-a -ftree-vectorize"             libjamesdsp-arm64-v8a.so
build_abi armeabi-v7a armv7a-linux-androideabi21-clang++ "-march=armv7-a -mfpu=neon -ftree-vectorize" libjamesdsp-armv7a.so

echo "== done"