#!/system/bin/sh
API="$(getprop ro.build.version.sdk)"
[ "$API" -ge 26 ] && libdir="/vendor" || libdir="/system"

if [ -f /data/adb/modules/ainur_jamesdsp/.AIDL ]; then
AIDL=true
else
AIDL=false
fi

LIB=libjamesdsp.so
LIBNAME=jdsp
NAME=jamesdsp
UUID=f27317f4-c984-4de6-9a90-545759495bf2
TYPE=f98765f4-c321-5de6-9a45-123459495ab2

# <postprocess> 自动挂载条目与 <library>/<effect> 声明分开维护：
# 声明已存在时下面的循环会跳过声明部分，但 <apply> 仍必须单独补齐，
# 否则库虽然注册了却不会被挂到 music 流。
# 幂等实现：先删掉所有旧条目，再插入恰好一条。重复条目会让 AudioFlinger
# 建链失败，并连带把厂商的全局效果（例如杜比 DAP）一起带掉。
aml_apply_music() {
	XML="$1"
	[ -s "$XML" ] || return 0
	grep -q "<audio_effects_conf" "$XML" 2>/dev/null || return 0

	TMP="$XML.aml"
	STRIP="$XML.strip"

	# 先做纯文本处理，绝不就地修改：任何一步失败都不会破坏原文件。
	sed 's|<apply effect="jamesdsp"/>||g' "$XML" > "$STRIP" 2>/dev/null || { rm -f "$STRIP"; return 0; }

	awk '
		{ L[NR] = $0 }
		END {
			n = NR; inc = 0
			for (i = 1; i <= n; i++) {
				C[i] = inc
				s = L[i]; k = 1
				while (k <= length(s)) {
					if (!inc && substr(s, k, 4) == "<!--") { inc = 1; k += 4; continue }
					if (inc && substr(s, k, 3) == "-->") { inc = 0; k += 3; continue }
					k++
				}
			}
			APPLY = "<apply effect=\"jamesdsp\"/>"
			OPEN  = "<stream type=\"music\">"
			for (i = 1; i <= n; i++)
				if (C[i] == 0 && index(L[i], OPEN) > 0) {
					p = index(L[i], OPEN) + length(OPEN)
					L[i] = substr(L[i], 1, p - 1) APPLY substr(L[i], p)
					for (j = 1; j <= n; j++) print L[j]
					exit
				}
			for (i = 1; i <= n; i++) {
				if (C[i] == 0 && index(L[i], "</audio_effects_conf>") > 0) {
					print "    <postprocess>"
					print "        " OPEN APPLY "</stream>"
					print "    </postprocess>"
				}
				print L[i]
			}
		}
	' "$STRIP" > "$TMP" 2>/dev/null

	# 安全闸：输出为空或缺少结束标签就放弃，保留原文件（历史事故：曾把厂商配置截断成 0 字节，
	# 导致 MiSound/杜比无法创建效果）。
	if [ ! -s "$TMP" ] || ! grep -q "</audio_effects_conf>" "$TMP"; then
		rm -f "$TMP" "$STRIP"
		return 0
	fi

	[ -f "$XML.jdsp.bak" ] || cp -f "$XML" "$XML.jdsp.bak" 2>/dev/null
	rm -f "$STRIP"
	mv -f "$TMP" "$XML"
}

patchxml() {
if [ ! -z "$AUDEFXML" ]; then
	for XML in $AUDEFXML; do
		if ! grep -q "$LIBNAME" "$XML"; then
			if [ $AIDL == "true" ]; then
				sed -i "/<libraries>/a\        <library name=\"$LIBNAME\" path=\"$LIB\"\/>" "$XML"
				sed -i "/<effects>/a\        <effect name=\"$NAME\" library=\"$LIBNAME\" uuid=\"$UUID\"\ type=\"$TYPE\"\/>" "$XML"
			else
				sed -i "/<libraries>/a\        <library name=\"$LIBNAME\" path=\"$LIB\"\/>" "$XML"
				sed -i "/<effects>/a\        <effect name=\"$NAME\" library=\"$LIBNAME\" uuid=\"$UUID\"\/>" "$XML"
			fi
		fi
		aml_apply_music "$XML"
	done
fi
}

patchconf() {
if [ ! -z "$AUDEFCONF" ]; then
	for CONF in $AUDEFCONF; do
		grep -q "$LIBNAME" "$CONF" && continue
		sed -i "/^libraries {/a\  $LIBNAME {\n    path \\$libdir\/lib\/soundfx\/$LIB\n  }" "$CONF"
		sed -i "/^effects {/a\  $NAME {\n    library $LIBNAME\n    uuid $UUID\n  }" "$CONF"
	done
fi
}
AUDEFXML="$(find /data/adb/modules -type f -name *audio*effects*.xml)"
AUDEFCONF="$(find /data/adb/modules -type f -name *audio*effects*.conf)"

patchxml

if [ $AIDL == "false" ]; then
	patchconf
fi
