#!/system/bin/sh
# 为 music 流补充 <postprocess> 声明，让 audioserver 自动创建并启用 jamesdsp。
#
# 原理（AOSP AudioPolicyEffects）：audioserver 在每次 startOutput() 时读取
#   <postprocess><stream type="music"><apply effect="jamesdsp"/></stream></postprocess>
# 然后自己 new AudioEffect 并 setEnabled(true)，挂在该曲目实际使用的输出线程上。
# 不依赖任何 App 调用 AudioEffect()，也不会像 App 那样落到 selectOutputForMusicEffects()
# 选出的 deep-buffer 线程而与音乐真正的输出错开。
#
# 用法：jdsp_apply_music <audio_effects.xml>
# 特性：幂等（已有 effect="jamesdsp" 就跳过）；会跳过 XML 注释里的 <postprocess>；
#       若已有未注释的 <stream type="music">，把 <apply> 插进它内部以保留原有效果；
#       若整个文件没有可用的 <postprocess>，则新建一个。

jdsp_apply_music() {
	f="$1"
	[ -f "$f" ] || return 0
	grep -q '<audio_effects_conf' "$f" 2>/dev/null || return 0
	# Normalise: keep exactly one <apply effect="jamesdsp"/> so that a
	# file patched by an older module run does not end up with duplicates.
	sed -i '/<apply effect="jamesdsp"\/>/d' "$f" 2>/dev/null


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

			m = 0
			for (i = 1; i <= n; i++)
				if (C[i] == 0 && index(L[i], OPEN) > 0) { m = i; break }
			if (m > 0) {
				p = index(L[m], OPEN) + length(OPEN)
				L[m] = substr(L[m], 1, p - 1) APPLY substr(L[m], p)
				for (i = 1; i <= n; i++) print L[i]
				exit
			}

			q = 0
			for (i = 1; i <= n; i++)
				if (C[i] == 0 && index(L[i], "</postprocess>") > 0) { q = i; break }
			for (i = 1; i <= n; i++) {
				if (i == q) print "        " OPEN APPLY "</stream>"
				if (q == 0 && C[i] == 0 && index(L[i], "</audio_effects_conf>") > 0) {
					print "    <postprocess>"
					print "        " OPEN APPLY "</stream>"
					print "    </postprocess>"
				}
				print L[i]
			}
		}
	' "$f" > "$f.jdsp" && mv -f "$f.jdsp" "$f"
}

# 旧式 audio_effects.conf 的等价物：output_session_processing { music { jamesdsp { } } }
jdsp_apply_music_conf() {
	f="$1"
	[ -f "$f" ] || return 1
	grep -q 'output_session_processing' "$f" 2>/dev/null || return 1

	# patch_config already injected exactly one '  jamesdsp {' effect block.
	# The block we add for postprocess lives inside music { }, so the file
	# only counts as done when it appears twice.  Counting blocks avoids the
	# old bug where the bare word matched the effect declaration itself and
	# the guard returned before anything was inserted.
	n=$(grep -c '^[[:space:]]*jamesdsp[[:space:]]*{' "$f" 2>/dev/null)
	[ -n "$n" ] || n=0
	[ "$n" -ge 2 ] && return 2
	[ "$n" -eq 0 ] && return 1

	awk '
		{ L[NR] = $0 }
		END {
			n = NR; inp = 0; inmus = 0; added = 0
			for (i = 1; i <= n; i++) {
				if (!inp && L[i] ~ /^[[:space:]]*output_session_processing[[:space:]]*\{/) { inp = 1; print L[i]; continue }
				if (inp && !inmus && L[i] ~ /^[[:space:]]*music[[:space:]]*\{/) {
					print L[i]
					print "        jamesdsp {"
					print "        }"
					inmus = 1; added = 1; continue
				}
				if (inp && inmus && L[i] ~ /^[[:space:]]*\}/) inmus = 0
				if (inp && L[i] ~ /^\}/) inp = 0
				print L[i]
			}
		}
	' "$f" > "$f.jdsp" && mv -f "$f.jdsp" "$f"
	return 0
}

# Merge-only variant, used for audio_effects.xml files owned by ANOTHER module.
# It never creates a new <postprocess> block: enabling post processing on a
# device whose vendor left it commented out changes the effect routing and can
# break vendor effects (e.g. Dolby/DAP on MediaTek).  It only adds the apply
# entry when the file already has an active music post-process stream.
jdsp_merge_music() {
	f="$1"
	[ -f "$f" ] || return 0
	grep -q '<audio_effects_conf' "$f" 2>/dev/null || return 0
	awk -v OUT="$f.jdsp_merge" '
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
			seen = 0
			for (i = 1; i <= n; i++) {
				if (C[i] == 0 && index(L[i], "effect=\"jamesdsp\"") > 0) seen = 1
			}
			if (seen) {
				for (i = 1; i <= n; i++) print L[i]
				exit
			}
			for (i = 1; i <= n; i++) {
				if (C[i] == 0 && index(L[i], OPEN) > 0) {
					p = index(L[i], OPEN) + length(OPEN)
					L[i] = substr(L[i], 1, p - 1) APPLY substr(L[i], p)
					for (j = 1; j <= n; j++) print L[j]
					exit
				}
			}
			for (i = 1; i <= n; i++) print L[i]
		}
	' "$f" > "$f.jdsp_merge" && mv -f "$f.jdsp_merge" "$f"
}
