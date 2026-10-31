#!/usr/bin/env bash
# 打包 KernelSU/Magisk 模块 dist/JDSP-fork-v2.1.6.zip
#
#   ./module/package.sh          # 从 backend/out 取 dex + 打 zip + 自检（打完删掉拷进来的 dex）
#   ./module/package.sh --keep   # 同上，但保留 module/src/backend/*.dex（调试用）
#   ./module/package.sh --check  # 只对已有 dist zip 做自检
#
# dex 拷贝默认在打包后被删除，避免二进制进 git（dist/ 本身已 gitignore）。
#
# 前置：先跑 backend/build.sh 产出 backend/out/{jdspd,jdspcli}.dex。
# 约定：
#   * module/src/backend/ 是构建产物的落点（dex 不进 git，见 .gitignore）
#   * 打包用 -X（不写额外属性），保持归档内容只由工作树决定
#   * 打完包做一组结构自检，任何一项失败都 exit 1
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/.." && pwd)
SRC="$HERE/src"
OUT="$REPO/dist/JDSP-fork-v2.1.6.zip"
VER_EXPECT="versionCode=216"

say() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

MODE=${1:-package}

if [ "$MODE" != "--check" ]; then
    say "== 1/4 拷贝 dex 到 module/src/backend =="
    mkdir -p "$SRC/backend"
    for d in jdspd jdspcli; do
        [ -s "$REPO/backend/out/$d.dex" ] || die "缺少 $REPO/backend/out/$d.dex（先跑 backend/build.sh）"
        cp -f "$REPO/backend/out/$d.dex" "$SRC/backend/$d.dex"
        chmod 0644 "$SRC/backend/$d.dex"
        say "   $d.dex $(stat -c %s "$SRC/backend/$d.dex") B  sha256=$(sha256sum "$SRC/backend/$d.dex" | cut -d' ' -f1)"
    done

    say "== 2/4 打包 =="
    mkdir -p "$REPO/dist"
    rm -f "$OUT"
    ( cd "$SRC" && zip -r -X "$OUT" . -x '*.git*' >/dev/null )
    say "   $OUT $(stat -c %s "$OUT") B"
    say "   sha256=$(sha256sum "$OUT" | cut -d' ' -f1)"
fi

say "== 3/4 结构自检 =="
[ -f "$OUT" ] || die "没有 $OUT"
LIST=$(unzip -l "$OUT")

check_entry() {
    printf '%s\n' "$LIST" | grep -qE "[[:space:]]$1$" && say "   OK   $1" || die "缺少 $1"
}
check_entry 'module.prop'
check_entry 'customize.sh'
check_entry 'META-INF/com/google/android/update-binary'
check_entry 'common/functions.sh'
check_entry 'common/install.sh'
check_entry 'common/jdsp_postprocess.sh'
check_entry 'service.sh'
check_entry 'post-fs-data.sh'
check_entry 'uninstall.sh'
check_entry 'sepolicy.rule'
check_entry 'jdsp_mode.sh'
check_entry 'jdspd.sh'
check_entry 'backend/jdspd.dex'
check_entry 'backend/jdspcli.dex'
check_entry 'common/files/arm64/libjamesdsp.so'
check_entry 'common/files/JamesDSPManagerThePBone.apk'

say "== 4/4 内容自检 =="
unzip -p "$OUT" module.prop | grep -qx "$VER_EXPECT" || die "module.prop 不是 $VER_EXPECT"
say "   OK   module.prop $VER_EXPECT"
N=$(unzip -p "$OUT" sepolicy.rule | grep -c '^allow mtk_hal_audio')
[ "$N" -ge 4 ] || die "sepolicy.rule 里 allow mtk_hal_audio 规则只有 $N 条（应 >=4）"
say "   OK   sepolicy.rule allow mtk_hal_audio 规则 $N 条"
unzip -p "$OUT" module.prop | grep -q '^version=v2\.1\.6' || die "module.prop version 不是 v2.1.6"
say "   OK   module.prop version=v2.1.6-fork"
unzip -p "$OUT" jdspd.sh | grep -q 'jdspd_normalize_state' || die "jdspd.sh 缺 state.bin 归一化"
say "   OK   jdspd.sh 带 state.bin 归一化"
unzip -p "$OUT" jdsp_mode.sh | grep -q 'jdsp_mode_config_get' || die "jdsp_mode.sh 缺 config.json 读取"
say "   OK   jdsp_mode.sh 读 config.json"
unzip -p "$OUT" service.sh | grep -q 'jdspd.sh' || die "service.sh 没挂 jdspd.sh"
say "   OK   service.sh 挂 jdspd.sh"

if [ "$MODE" != "--check" ] && [ "$MODE" != "--keep" ]; then
    rm -rf "$SRC/backend"
    say "== 清理 module/src/backend（保持工作树无二进制） =="
fi

say ""
say "包已就绪：$OUT"
say "sha256: $(sha256sum "$OUT" | cut -d' ' -f1)"
