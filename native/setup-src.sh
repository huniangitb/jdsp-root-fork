#!/usr/bin/env bash
#
# Fetch the upstream engine source and produce the patched tree build.sh and
# test/run_tests.sh expect (SRC=<repo>/... or the default <repo>/src layout).
#
#   ./setup-src.sh <clone-target>       # e.g. ./setup-src.sh /home/bdy/work/jdsp-native-work
#
# Produces, inside <clone-target>:
#   src-baseline/   pristine upstream extensions branch
#   src/            src-baseline + 0001 + 0002 + 0003
#
# Note: 0001's Android.mk hunk was written against an older upstream snapshot.
# The current extensions branch already lists the three effect sources, so that
# one hunk fails; this script re-applies the same change by hand (jdsp_state.c
# in the source list + the clang 20 compatibility flags), which is exactly what
# 0001 intended. Everything else of 0001 applies cleanly.
set -euo pipefail

TARGET=${1:?usage: setup-src.sh <target-dir>}
UPSTREAM=${UPSTREAM:-https://github.com/evoeram/JamesDSPManager.git}
BRANCH=${BRANCH:-extensions}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

if [ -e "$TARGET" ]; then
    echo "target $TARGET already exists, refusing to overwrite" >&2
    exit 1
fi
mkdir -p "$TARGET"

echo "== clone $UPSTREAM ($BRANCH)"
git clone -q -b "$BRANCH" "$UPSTREAM" "$TARGET/upstream"

echo "== snapshot src-baseline"
cp -a "$TARGET/upstream" "$TARGET/src-baseline"

echo "== apply 0001"
cp -a "$TARGET/src-baseline" "$TARGET/src"
# hunk 1 (Android.mk source list) is allowed to fail; everything else must apply
patch -p1 --forward --quiet -d "$TARGET/src" \
    < "$HERE/patches/0001-state-persistence-and-hot-reload.patch" || true
rm -f "$TARGET/src"/Main/libjamesdsp/jni/jamesdsp/*.rej

echo "== reconcile Android.mk (the drifted part of 0001)"
python3 - "$TARGET/src/Main/libjamesdsp/jni/jamesdsp/Android.mk" <<'EOF'
import sys
p = sys.argv[1]
s = open(p).read()
changed = False
if "jdsp_state.c" not in s:
    s = s.replace("\tpeq_loudness.c \\\n\tjamesdsp.c \\\n",
                  "\tpeq_loudness.c \\\n\tjdsp_state.c \\\n\tjamesdsp.c \\\n")
    changed = True
if "jdsp_writelock.c" not in s:
    s = s.replace("\tpeq_loudness.c \\\n\tjdsp_state.c \\\n\tjamesdsp.c \\\n",
                  "\tpeq_loudness.c \\\n\tjdsp_state.c \\\n\tjdsp_writelock.c \\\n\tjamesdsp.c \\\n")
    changed = True
if "-Wno-incompatible-function-pointer-types" not in s:
    s = s.replace("LOCAL_LDLIBS := -llog\n",
                  "LOCAL_LDLIBS := -llog\n"
                  "# clang 20 (NDK r27+) errors on constructs that older clang only warned about\n"
                  "LOCAL_CFLAGS += -Wno-incompatible-function-pointer-types -Wno-incompatible-pointer-types \\\n"
                  "\t-Wno-error=incompatible-pointer-types -Wno-int-conversion -Wno-error=int-conversion\n")
    changed = True
if "-O3 -ffast-math" not in s:
    s = s.replace("-ffunction-sections -fdata-sections -Ofast",
                  "-ffunction-sections -fdata-sections -O3 -ffast-math")
    changed = True
if changed:
    open(p, "w").write(s)
    print("Android.mk reconciled")
else:
    print("Android.mk already carries the needed changes, left alone")
EOF

if [ -f "$HERE/patches/0002-engine-write-lock.patch" ]; then
    echo "== apply 0002"
    # When the reconcile above already inserted the Android.mk source lines the
    # 0002 Android.mk hunk is a no-op; everything else must apply.
    patch -p1 --forward -s -d "$TARGET/src" \
        < "$HERE/patches/0002-engine-write-lock.patch" || true
    rm -f "$TARGET/src"/Main/libjamesdsp/jni/jamesdsp/*.rej
    grep -q "jdsp_writelock.c" "$TARGET/src/Main/libjamesdsp/jni/jamesdsp/Android.mk" \
        || { echo "Android.mk missing jdsp_writelock.c after setup" >&2; exit 1; }
fi

if [ -f "$HERE/patches/0003-deferred-param-apply-and-no-cross-instance-rebuild.patch" ]; then
    echo "== apply 0003"
    patch -p1 --forward -s -d "$TARGET/src" \
        < "$HERE/patches/0003-deferred-param-apply-and-no-cross-instance-rebuild.patch"
    grep -q "jdsp_paramq.c" "$TARGET/src/Main/libjamesdsp/jni/jamesdsp/Android.mk" \
        || { echo "Android.mk missing jdsp_paramq.c after setup" >&2; exit 1; }
fi

echo
echo "done. build with:"
echo "  NDK=/path/to/android-ndk-r30 SRC=$TARGET/src/Main/libjamesdsp/jni/jamesdsp $HERE/build.sh"
echo "test with:"
echo "  SRC=$TARGET/src/Main/libjamesdsp/jni/jamesdsp $HERE/test/run_tests.sh"
