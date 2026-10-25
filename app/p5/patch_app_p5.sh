#!/usr/bin/env bash
#
# P5：把 App 的参数下发镜像到 jdspd（传输 C'：loopback TCP + token）。
#
# 用法： ./patch_app_p5.sh <输入.apk> <输出.apk> [keystore] [ks-pass] [key-pass]
#
# 在 patch_app.sh（汉化/资源补丁）之后跑，顺序：
#   官方 APK -> patch_app.sh -> patch_app_p5.sh -> 安装
#
# 做了什么：
#   1. 注入 JdspProxyEffect.smali（AudioEffect 子类，override setParameter 三个重载 + setEnabled）
#   2. 改 JamesDspRemoteEngine.createEffect()：new AudioEffect -> new JdspProxyEffect（2 行）
#   3. 把 JdspBridge/Transport 的 Java 字节码编成 classes4.dex 塞进 APK（multidex）
#   4. zipalign + apksigner 重签
#
# 不做：改业务逻辑、改 SharedPreferences、去掉 App 自己的 AudioEffect。
# 降级：jdspd 不在（连不上 tcp）时 JdspBridge 直接停用，App 行为与官方版完全一致。
set -euo pipefail

IN=${1:?input apk}
OUT=${2:?output apk}
KS=${3:-${JDSP_KEYSTORE:-/home/bdy/work/fork-rootfix/jdsp.keystore}}
KS_PASS=${4:-${JDSP_KS_PASS:-jdsp123}}
KEY_PASS=${5:-${JDSP_KEY_PASS:-jdsp123}}
# 模式：mirror（第一阶段，默认，已真机验证）| phase2（第二阶段，构建产物已出，未装机）
#   mirror : 写路径 = 本地 + 镜像给 jdspd（两套实例都在写）。
#   phase2 : 写路径 = 只给 jdspd（本地作为只读回退 + 无后端时的回退）。
MODE=${P5_MODE:-mirror}

HERE=$(cd "$(dirname "$0")" && pwd)
SDK=${ANDROID_SDK:-${ANDROID_HOME:-/home/bdy/Android/Sdk}}
BT="$SDK/build-tools/37.0.0"
JAR="$SDK/platforms/android-37.0/android.jar"

# 注意：本机 /tmp 是 3.9G 的 tmpfs，apktool 解包 40MB APK 会撑爆；一律放工作区。
WORK_ROOT=${P5_WORK:-${HOME}/.cache/jdsp-p5}
mkdir -p "$WORK_ROOT"
WORK=$(mktemp -d "$WORK_ROOT/patch-XXXXXX")
trap 'rm -rf "$WORK"' EXIT

APKTOOL_JAR=${APKTOOL_JAR:-/usr/share/java/android-apktool/apktool.jar}
APKTOOL_HEAP=${APKTOOL_HEAP:-3g}
apk() {
  if [ -f "$APKTOOL_JAR" ]; then
    java -Xmx"$APKTOOL_HEAP" -jar "$APKTOOL_JAR" "$@"
  else
    apktool "$@"
  fi
}

echo "== decode ($IN)"
apk d -f -q -o "$WORK/app" "$IN"

PKG_SMALI="$WORK/app/smali/me/timschneeberger/rootlessjamesdsp/p5"
mkdir -p "$PKG_SMALI"

case "$MODE" in
  mirror)
    PROXY="JdspProxyEffect.smali"
    PROXY_CLS="Lme/timschneeberger/rootlessjamesdsp/p5/JdspProxyEffect;"
    ;;
  phase2)
    PROXY="JdspRemoteEffect.smali"
    PROXY_CLS="Lme/timschneeberger/rootlessjamesdsp/p5/JdspRemoteEffect;"
    ;;
  phase3)
    # phase3 = phase2 + **读侧**也走后端（App 不持有配置）。
    # 写路径与 phase2 相同；额外把 androidx PreferenceManager 的
    # getSharedPreferences() 包一层 JdspBackendSharedPreferences，
    # 于是界面显示的是后端 config.json 里的值。
    PROXY="JdspRemoteEffect.smali"
    PROXY_CLS="Lme/timschneeberger/rootlessjamesdsp/p5/JdspRemoteEffect;"
    PATCH_READ=1
    ;;
  *)
    echo "unknown P5_MODE=$MODE (expected mirror|phase2|phase3)" >&2
    exit 2
    ;;
esac

echo "== inject $PROXY (mode=$MODE)"
cp "$HERE/smali/$PROXY" "$PKG_SMALI/$PROXY"

echo "== patch JamesDspRemoteEngine.createEffect()"
python3 - "$WORK/app" "$PROXY_CLS" <<'PY'
import sys, pathlib, re
root = pathlib.Path(sys.argv[1])
old_new = sys.argv[2]
old_cls = "Landroid/media/audiofx/AudioEffect;"
hits = list(root.glob("smali*/me/timschneeberger/rootlessjamesdsp/interop/JamesDspRemoteEngine.smali"))
if len(hits) != 1:
    raise SystemExit("expected exactly one JamesDspRemoteEngine.smali, got %r" % hits)
p = hits[0]
s = p.read_text(encoding="utf-8")

# 只改 createEffect() 方法体内的两处；其它 AudioEffect 引用（release/enabled 等）保持不动
m = re.search(r"(\.method private final createEffect\(\)Landroid/media/audiofx/AudioEffect;.*?)\.end method",
              s, re.S)
if not m:
    raise SystemExit("createEffect() not found")
body = m.group(1)
patched = body.replace("new-instance v0, " + old_cls, "new-instance v0, " + old_new, 1)
patched = patched.replace("invoke-direct {v0, v1, v2, v3, v4}, " + old_cls + "-><init>(Ljava/util/UUID;Ljava/util/UUID;II)V",
                          "invoke-direct {v0, v1, v2, v3, v4}, " + old_new + "-><init>(Ljava/util/UUID;Ljava/util/UUID;II)V", 1)
if patched == body:
    raise SystemExit("createEffect() patch did not apply")
s = s[:m.start(1)] + patched + s[m.end(1):]
p.write_text(s, encoding="utf-8")
print("   patched", p.name)
PY

if [ -n "${PATCH_READ:-}" ]; then
  echo "== patch androidx PreferenceManager.getSharedPreferences() (read side -> backend)"
  python3 "$HERE/patch_read_side.py" "$WORK/app"
fi

echo "== build bridge dex (classes4.dex)"
rm -rf "$WORK/classes" "$WORK/dex"
mkdir -p "$WORK/classes" "$WORK/dex"
javac -source 8 -target 8 -nowarn -Xlint:-options -cp "$JAR" -d "$WORK/classes" \
    $(find "$HERE/src" -name '*.java')
"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$WORK/dex" \
    $(find "$WORK/classes" -name '*.class')
cp "$WORK/dex/classes.dex" "$WORK/classes4.dex"

echo "== apktool build (heap $APKTOOL_HEAP)"
# 本机内存紧张：apktool 默认堆在 smali 大包时 OOM（实测 java heap space），显式给堆
apk b "$WORK/app" -o "$WORK/unsigned.apk"

echo "== add classes4.dex"
python3 - "$WORK/unsigned.apk" "$WORK/classes4.dex" <<'PY'
import sys, zipfile, shutil, os
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk) as z:
    names = set(z.namelist())
if "classes4.dex" in names:
    raise SystemExit("APK already has classes4.dex; pick another slot")
shutil.copyfile(apk, apk + ".tmp")
with zipfile.ZipFile(apk + ".tmp", "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes4.dex")
os.replace(apk + ".tmp", apk)
print("   classes4.dex added; existing dex:", sorted(n for n in names if n.endswith(".dex")))
PY

echo "== zipalign + sign"
"$BT/zipalign" -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
    --out "$OUT" "$WORK/aligned.apk"

echo "== done: $OUT"
"$BT/apksigner" verify --print-certs "$OUT" | head -4
ls -l "$OUT"
