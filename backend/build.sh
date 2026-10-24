#!/usr/bin/env bash
# Builds the jdspd backend: AIDL stub generation + javac + d8.
#
#   ./build.sh          -> out/jdspd.dex, out/jdspcli.dex
#   ./build.sh clean    -> remove out/
#
# Everything is built with the Android SDK only: no Gradle, no AOSP tree.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SDK=${ANDROID_SDK:-/home/bdy/Android/Sdk}

pick() { ls -d "$1"/*/"$2" 2>/dev/null | sort -V | tail -1; }

AIDL=${AIDL:-$(pick "$SDK/build-tools" aidl)}
D8=${D8:-$SDK/cmdline-tools/latest/bin/d8}
ANDROID_JAR=${ANDROID_JAR:-$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)}

# Serialise concurrent builds (daemon-core / cli-harness run this at the same time).
if command -v flock >/dev/null; then
    exec 9>"$HERE/.build.lock"
    flock 9
fi

OUT="$HERE/out"
GEN="$OUT/gen"
CLASSES="$OUT/classes"

if [ "${1:-}" = "clean" ]; then
    rm -rf "$OUT"
    echo "cleaned $OUT"
    exit 0
fi

for tool in "$AIDL" "$D8"; do
    [ -x "$tool" ] || { echo "missing tool: $tool" >&2; exit 1; }
done
[ -f "$ANDROID_JAR" ] || { echo "missing android.jar: $ANDROID_JAR" >&2; exit 1; }
command -v javac >/dev/null || { echo "missing javac" >&2; exit 1; }

echo "aidl        : $AIDL"
echo "d8          : $D8"
echo "android.jar : $ANDROID_JAR"

rm -rf "$OUT"
mkdir -p "$GEN" "$CLASSES"

# 1) AIDL -> Java
mapfile -t AIDL_FILES < <(find "$HERE/aidl" -name '*.aidl' | sort)
"$AIDL" --lang=java -I"$HERE/aidl" --out="$GEN" "${AIDL_FILES[@]}"

# 2) javac (Java 8 bytecode, Android boot classpath)
mapfile -t SRC_FILES < <(find "$HERE/src" "$GEN" -name '*.java' | sort)
# --release 8 + -classpath (not -bootclasspath): java.* comes from the JDK, android.* from
# android.jar.  This also lets javac emit invokedynamic for lambdas (d8 desugars them);
# with -bootclasspath android.jar javac cannot find LambdaMetafactory at all.
javac --release 8 -nowarn -classpath "$ANDROID_JAR" -d "$CLASSES" "${SRC_FILES[@]}" 2>&1 | grep -vE '^警告|^warning|已过时' || true
if [ ! -d "$CLASSES/com/jdsp" ]; then
    echo "javac produced no classes" >&2
    exit 1
fi

# 3) dex: daemon = AIDL + common + backend, cli = AIDL + common + cli
dex() {
    local name=$1; shift
    local tmp="$OUT/.dex-$name"
    mkdir -p "$tmp"
    find "$@" -name '*.class' > "$tmp/files.txt"
    "$D8" --lib "$ANDROID_JAR" --min-api 29 --output "$tmp" $(cat "$tmp/files.txt") >/dev/null
    mv "$tmp/classes.dex" "$OUT/$name.dex"
    rm -rf "$tmp"
    ls -l "$OUT/$name.dex"
}

dex jdspd "$CLASSES/android/jdsp/backend" "$CLASSES/com/jdsp/common" "$CLASSES/com/jdsp/backend"
dex jdspcli "$CLASSES/android/jdsp/backend" "$CLASSES/com/jdsp/common" "$CLASSES/com/jdsp/cli"

echo
echo "built:"
ls -l "$OUT"/*.dex
