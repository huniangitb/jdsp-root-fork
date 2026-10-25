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

    # androidx 1.2 的方法体是：
    #   if (getPreferenceDataStore() != null) return null;   <- 第一个 return（早退，别碰）
    #   if (mSharedPreferences == null) { ...创建... }
    #   return mSharedPreferences;                            <- 最终 return，wrap 要插这里
    # 所以锚点是**最后一个** return-object v0，且它前面一行必须是刚加载的
    # mSharedPreferences —— 防止 ROM/库版本变化时插错分支（phase3 v1 就犯了这个错：
    # wrap 被插进了 dataStore 分支且入参是 null，读侧从未生效）。
    marker = "    return-object v0" + NL
    idx = body.rfind(marker)
    if idx < 0:
        raise SystemExit("unexpected PreferenceManager.getSharedPreferences() body")
    tail = body[:idx].rstrip(NL) + NL
    load = ("    iget-object v0, p0, "
            "Landroidx/preference/PreferenceManager;->mSharedPreferences:Landroid/content/SharedPreferences;" + NL)
    if not tail.endswith(load):
        raise SystemExit("final return does not follow the mSharedPreferences load — layout changed")

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
    patched = body[:idx] + wrap + body[idx:]

    s = s[:m.start(1)] + patched + s[m.end(1):]
    p.write_text(s, encoding="utf-8")
    print("   patched", p.name)

    patch_preference_cache(root)


def patch_preference_cache(root):
    """App 主界面的读值不走 androidx PreferenceManager，而是它自己的 PreferenceCache：
    PreferenceCache$Companion.getPreferences(context, namespace) 直接调
    Context.getSharedPreferences(name, mode)，然后把返回的 SharedPreferences 缓存起来，
    所有滑条/开关的读值都从它来（uncachedGet → getXxx）。只包 PreferenceManager
    覆盖不到任何实际读点（phase3 v1 的实测结论）。这里把 getPreferences 的返回值也包一层。
    """
    hits = list(root.glob("smali*/me/timschneeberger/rootlessjamesdsp/interop/"
                          "PreferenceCache$Companion.smali"))
    if len(hits) != 1:
        raise SystemExit("expected exactly one PreferenceCache$Companion.smali, got %r" % hits)
    p = hits[0]
    s = p.read_text(encoding="utf-8")

    if CLS in s:
        print("   PreferenceCache already patched, skipping")
        return

    m = re.search(
        r"(\.method public final getPreferences\(Landroid/content/Context;Ljava/lang/String;\)"
        r"Landroid/content/SharedPreferences;.*?)\.end method",
        s, re.S)
    if not m:
        raise SystemExit("PreferenceCache$Companion.getPreferences() not found")
    body = m.group(1)

    marker = "    return-object p1" + NL
    if marker not in body:
        raise SystemExit("unexpected getPreferences() body (no return-object p1)")
    if "checkNotNullExpressionValue" not in body:
        raise SystemExit("getPreferences() layout changed (no kotlin null check)")

    # 扩一个 local 存 context（p1 会被 move-result 覆盖成 sp）。
    # apktool 输出 .locals N，baksmali 输出 .registers N（= locals + params）——两种都兼容。
    if ".locals 1" + NL in body:
        body = body.replace(".locals 1" + NL, ".locals 2" + NL, 1)
    elif ".registers 4" + NL in body:
        body = body.replace(".registers 4" + NL, ".registers 5" + NL, 1)
    else:
        raise SystemExit("unexpected register count in getPreferences()")

    # const/4 v0, 0x4（mode）之前把 context 存进 v1。
    anchor = "    const/4 v0, 0x4" + NL
    if anchor not in body:
        raise SystemExit("mode constant not found in getPreferences()")
    body = body.replace(anchor, "    move-object v1, p1" + NL + NL + anchor, 1)

    # Kotlin 编译器会在 getSharedPreferences 之后把 p2（namespace）复用为
    # checkNotNullExpressionValue 的消息字符串（const-string p2, "getSharedPreferences(...)"），
    # 所以 namespace 也必须备份到 v0，wrap 才能拿到正确的命名空间。
    anchor2 = "    move-result-object p1" + NL
    if anchor2 not in body:
        raise SystemExit("move-result-object not found in getPreferences()")
    body = body.replace(anchor2, anchor2 + "    move-object v0, p2" + NL, 1)

    lines = [
        "    # --- P5 phase3: read side comes from the backend (jdspd config.json) ---",
        "    invoke-static {p1, v0, v1}, " + CLS +
        "->wrap(Landroid/content/SharedPreferences;Ljava/lang/String;Landroid/content/Context;)"
        "Landroid/content/SharedPreferences;",
        "",
        "    move-result-object p1",
        "",
    ]
    wrap = NL.join(lines) + NL
    # 定位必须在上面两次 replace 之后：它们各自加行，会让之前算的偏移失效
    # （第一版就是拿着旧偏移插入，把 wrap 插进了 invoke 的类型签名中间）。
    idx = body.rfind(marker)
    body = body[:idx] + wrap + body[idx:]

    s = s[:m.start(1)] + body + s[m.end(1):]
    p.write_text(s, encoding="utf-8")
    print("   patched PreferenceCache$Companion.getPreferences()")


if __name__ == "__main__":
    main()
