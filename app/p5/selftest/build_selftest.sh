#!/usr/bin/env bash
# 构建 P5 传输自测 APK（com.jdsp.selftest）与根侧探针 dex。
set -euo pipefail

SDK=${ANDROID_SDK:-${ANDROID_HOME:-/home/bdy/Android/Sdk}}
BT="$SDK/build-tools/37.0.0"
JAR="$SDK/platforms/android-37.0/android.jar"
KS=${JDSP_KEYSTORE:-/home/bdy/work/fork-rootfix/jdsp.keystore}
KS_PASS=${JDSP_KS_PASS:-jdsp123}
KEY_PASS=${JDSP_KEY_PASS:-jdsp123}

HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$HERE/../src"
OUT=${1:-$HERE/out}

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/probe-classes" "$OUT/probe-dex"

echo "== javac (shared transports + activity)"
javac -source 8 -target 8 -nowarn -Xlint:-options -cp "$JAR" -d "$OUT/classes" \
    $(find "$SRC" -name '*.java') "$HERE/SelftestActivity.java"

echo "== d8 (app)"
"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')

echo "== aapt2 link"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" \
    --manifest "$HERE/AndroidManifest.xml" \
    --min-sdk-version 26 --target-sdk-version 35 \
    --version-code 1 --version-name 1.0

echo "== package dex"
(cd "$OUT/dex" && zip -q -j "$OUT/base.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
    --out "$OUT/jdsp-selftest.apk" "$OUT/aligned.apk"

echo "== javac + d8 (client check: 不装 App 也能验证客户端 ↔ 真 jdspd)"
rm -rf "$OUT/cc-classes" "$OUT/cc-dex"
mkdir -p "$OUT/cc-classes" "$OUT/cc-dex"
javac -source 8 -target 8 -nowarn -Xlint:-options -cp "$JAR" -d "$OUT/cc-classes" "$HERE/ClientCheck.java"
"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$OUT/cc-dex" \
    $(find "$OUT/cc-classes" -name '*.class')

echo "== host unit test (JdspRemoteOps 策略分支，不需要设备)"
HOST_SRC="$HERE/host"
rm -rf "$OUT/host-classes"
mkdir -p "$OUT/host-classes"
# 只编译**纯 Java**的源文件：宿主单测不能依赖 Android SDK。
# JdspRemoteOps / JdspParamCodec / JdspParamMapping / JdspJson / JdspConfigSnapshot
# 都只用到 java.*，可以直接在宿主上跑。
HOST_PURE="$SRC/me/timschneeberger/rootlessjamesdsp/p5/JdspRemoteOps.java"
HOST_PURE="$HOST_PURE $SRC/me/timschneeberger/rootlessjamesdsp/p5/JdspParamCodec.java"
HOST_PURE="$HOST_PURE $SRC/me/timschneeberger/rootlessjamesdsp/p5/JdspParamMapping.java"
HOST_PURE="$HOST_PURE $SRC/me/timschneeberger/rootlessjamesdsp/p5/JdspJson.java"
HOST_PURE="$HOST_PURE $SRC/me/timschneeberger/rootlessjamesdsp/p5/JdspConfigSnapshot.java"
HOST_PURE="$HOST_PURE $SRC/me/timschneeberger/rootlessjamesdsp/p5/JdspCodec.java"
javac -source 8 -target 8 -nowarn -Xlint:-options -d "$OUT/host-classes" \
    $(find "$HOST_SRC" -name '*.java') $HOST_PURE
java -cp "$OUT/host-classes" host.OpsHostTest
java -cp "$OUT/host-classes" host.ConfigReadHostTest

echo "== javac + d8 (probe)"
javac -source 8 -target 8 -nowarn -Xlint:-options -cp "$JAR" -d "$OUT/probe-classes" "$HERE/ProbeServer.java"
"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$OUT/probe-dex" \
    $(find "$OUT/probe-classes" -name '*.class')

echo "== done"
ls -l "$OUT/jdsp-selftest.apk" "$OUT/probe-dex/classes.dex" "$OUT/cc-dex/classes.dex"
