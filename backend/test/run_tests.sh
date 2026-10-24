#!/usr/bin/env bash
# Host-side unit tests for jdspcli.
#
# No JUnit, no emulator, no device: plain javac + java. The tests cover command
# parsing, value encoding (float32/int32/int16 LE, hex/raw), JSON output, the
# unix-socket protocol and the exit-code mapping (0/2/3/4).
#
# What it does:
#   1. AIDL -> Java stubs (same aidl invocation as backend/build.sh)
#   2. compile the whole CLI (including the Android-facing Client/Main) against
#      android.jar, so compile errors in those files are caught here too
#   3. compile and run backend/test/java/** WITHOUT android.jar on the runtime
#      classpath, proving the tested code is pure Java
#
# Output directory: backend/test/build/units (gitignored via the build/ rule).
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
BACKEND=$(cd "$HERE/.." && pwd)
SDK=${ANDROID_SDK:-/home/bdy/Android/Sdk}
BUILD="$HERE/build/units"

pick() { ls -d "$1"/*/"$2" 2>/dev/null | sort -V | tail -1; }

ANDROID_JAR=${ANDROID_JAR:-$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)}
AIDL=${AIDL:-$(pick "$SDK/build-tools" aidl)}

command -v javac >/dev/null || { echo "missing javac" >&2; exit 1; }
command -v java >/dev/null || { echo "missing java" >&2; exit 1; }
[ -n "${ANDROID_JAR:-}" ] && [ -f "$ANDROID_JAR" ] \
    || { echo "missing android.jar (set ANDROID_JAR or ANDROID_SDK)" >&2; exit 1; }
[ -x "${AIDL:-}" ] || { echo "missing aidl (set AIDL or ANDROID_SDK)" >&2; exit 1; }

echo "android.jar : $ANDROID_JAR"
echo "aidl        : $AIDL"
echo

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/test-classes" "$BUILD/gen"

# 1) AIDL -> Java
mapfile -t AIDL_FILES < <(find "$BACKEND/aidl" -name '*.aidl' | sort)
"$AIDL" --lang=java -I"$BACKEND/aidl" --out="$BUILD/gen" "${AIDL_FILES[@]}"

# 2) compile the CLI (Android-facing files included) against android.jar
mapfile -t SOURCES < <(find "$BACKEND/src/com/jdsp/cli" "$BACKEND/src/com/jdsp/common" \
        "$BUILD/gen" -name '*.java' | sort)
echo "compiling ${#SOURCES[@]} cli sources..."
if ! javac --release 8 -nowarn -Xlint:-options -classpath "$ANDROID_JAR" \
        -d "$BUILD/classes" "${SOURCES[@]}" >"$BUILD/javac.log" 2>&1; then
    echo "cli javac FAILED:" >&2
    cat "$BUILD/javac.log" >&2
    exit 1
fi

# 3) compile the tests (pure Java, same package so package-private is visible)
mapfile -t TESTS < <(find "$HERE/java" -name '*.java' | sort)
echo "compiling ${#TESTS[@]} test sources..."
if ! javac --release 8 -nowarn -Xlint:-options -cp "$BUILD/classes" \
        -d "$BUILD/test-classes" "${TESTS[@]}" >"$BUILD/javac-tests.log" 2>&1; then
    echo "test javac FAILED:" >&2
    cat "$BUILD/javac-tests.log" >&2
    exit 1
fi

# 4) run without android.jar: this is a feature, it proves the tested classes
#    have no Android dependency
echo
java -cp "$BUILD/test-classes:$BUILD/classes" com.jdsp.cli.AllTests
