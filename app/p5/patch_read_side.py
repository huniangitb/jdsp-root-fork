#!/usr/bin/env python3
"""
P5 phase3: 把 androidx PreferenceManager 的 **读侧**改成读后端（jdspd）。

为什么改这里：
  App 的界面（androidx Preference 屏幕、参数编辑页）唯一的公共读接口就是
  SharedPreferences。要让界面显示后端 config.json 里的值，
  就在 PreferenceManager.getSharedPreferences() 这一处包一层代理。

改动只有一处（很小的 smali 插入）：
  androidx/preference/PreferenceManager.getSharedPreferences()
    在 return 之前把 mSharedPreferences 用
    JdspBackendSharedPreferences.wrap(real, name, context) 包一层。

**写侧不变**：edit()/putXxx() 仍然落到 App 自己的 SharedPreferences，
  因为 App 的写路径依赖 "SharedPreferences 变更 -> listener ->
  ACTION_PREFERENCES_UPDATED -> engine.syncWithPreferences() -> 发给后端"
  这条链。写出去的最终归宿仍然是后端（引擎只发后端，见 JdspRemoteOps）。

用法： patch_read_side.py <apktool 解包目录>
"""
import pathlib
import re
import sys

CLS = "Lme/timschneeberger/rootlessjamesdsp/p5/JdspBackendSharedPreferences;"
NL = chr(10)


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_read_side.py <apktool-dir>")
    root = pathlib.Path(sys.argv[1])

    hits = list(root.glob("smali*/androidx/preference/PreferenceManager.smali"))
    if len(hits) != 1:
        raise SystemExit("expected exactly one androidx/preference/PreferenceManager.smali, got %r" % hits)
    p = hits[0]
    s = p.read_text(encoding="utf-8")

    if CLS in s:
        print("   already patched, skipping")
        return

    m = re.search(
        r"(\.method public getSharedPreferences\(\)Landroid/content/SharedPreferences;.*?)\.end method",
        s, re.S)
    if not m:
        raise SystemExit("PreferenceManager.getSharedPreferences() not found")
    body = m.group(1)

    marker = "    return-object v0" + NL
    if marker not in body:
        raise SystemExit("unexpected PreferenceManager.getSharedPreferences() body")

    lines = [
        "    # --- P5 phase3: read side comes from the backend (jdspd config.json) ---",
        "    iget-object v1, p0, Landroidx/preference/PreferenceManager;->mSharedPreferencesName:Ljava/lang/String;",
        "",
        "    iget-object v2, p0, Landroidx/preference/PreferenceManager;->mContext:Landroid/content/Context;",
        "",
        "    invoke-static {v0, v1, v2}, " + CLS +
        "->wrap(Landroid/content/SharedPreferences;Ljava/lang/String;Landroid/content/Context;)"
        "Landroid/content/SharedPreferences;",
        "",
        "    move-result-object v0",
        "",
    ]
    wrap = NL.join(lines) + NL
    patched = body.replace(marker, wrap + marker, 1)

    # v1/v2 需要至少 3 个寄存器
    loc = re.search(r"\.locals (\d+)", patched)
    if not loc:
        raise SystemExit("no .locals in PreferenceManager.getSharedPreferences()")
    if int(loc.group(1)) < 3:
        patched = patched.replace(".locals " + loc.group(1), ".locals 3", 1)

    s = s[:m.start(1)] + patched + s[m.end(1):]
    p.write_text(s, encoding="utf-8")
    print("   patched", p.name)


if __name__ == "__main__":
    main()
