#!/system/bin/sh
# jdsp_mode.sh — auto|backend 模式开关的唯一实现（被安装器/开机脚本/CLI 复用）
#
# 作用：决定框架的 <postprocess><stream type="music"><apply effect="jamesdsp"/></stream>
#       </postprocess> 这一句在模块的 audio_effects.xml 副本里存不存在。
#   auto    = 保留注入，由 audioserver 在每个 music 会话上自动挂 JamesDSP（v6.7.2 原行为）
#   backend = 去掉注入，改由 jdspd 主动 createEffect 挂载
#
# 用法：
#   sh jdsp_mode.sh get
#   sh jdsp_mode.sh set auto|backend     # 写配置 + 立刻改模块副本；重启后生效
#   sh jdsp_mode.sh apply [tag]          # 按当前配置纠正模块副本（幂等）
#   sh jdsp_mode.sh verify [tag]         # 只读：比对“已挂载的 xml”和配置，不一致记 WARN
#
# 约定：
#   * 事实来源是 /data/adb/jdspd/mode（裸文本 auto|backend，缺省 auto）；
#     post-fs-data 阶段 daemon 还没起来，只能用 cat 读，所以不用 JSON。
#   * 只改模块内的 xml 副本，不碰上游系统文件（挂载是 hybrid_mount 做的）。
#   * 改文件用“临时文件 + cat > 原文件”保持 inode（万一被 bind mount，挂载方也能看到新内容）。
#   * 本脚本永不 exit 1：一个 xml 出错不能把整次开机搞坏。

JDSP_MODULE_DIR=${MODPATH:-${0%/*}}
JDSP_MODE_FILE=${JDSP_MODE_FILE:-/data/adb/jdspd/mode}
JDSP_MODE_DEFAULT=auto
JDSP_STATE_HINT=""

jdsp_mode_log() {
	[ -n "$JDSP_MODULE_DIR" ] || return 0
	echo "[mode]${jdsp_mode_tag:+ $jdsp_mode_tag} $*" >> "$JDSP_MODULE_DIR/boot.log" 2>/dev/null
}

# config.json（jdspd 的 ConfigStore，PLAN-v2 里的权威配置）里的 modeName / mode 优先：
# 这样 App 通过 AIDL setMode 改了配置之后，模块侧读到的就是同一个事实。
jdsp_mode_config_get() {
	_cfg="${JDSP_CONFIG_FILE:-/data/adb/jdspd/config.json}"
	[ -f "$_cfg" ] || return 1
	_n=$(sed -n 's/.*"modeName"[[:space:]]*:[[:space:]]*"\([A-Za-z_]*\)".*/\1/p' "$_cfg" 2>/dev/null | head -n 1)
	case "$_n" in auto|backend) printf '%s\n' "$_n"; return 0 ;; esac
	_i=$(sed -n 's/.*"mode"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$_cfg" 2>/dev/null | head -n 1)
	case "$_i" in 0) printf 'auto\n'; return 0 ;; 1) printf 'backend\n'; return 0 ;; esac
	return 1
}

jdsp_mode_get() {
	jdsp_mode_config_get && return 0
	_m=$(cat "$JDSP_MODE_FILE" 2>/dev/null | tr -d ' \t\r\n')
	case "$_m" in
		auto|backend) printf '%s\n' "$_m" ;;
		*) printf '%s\n' "$JDSP_MODE_DEFAULT" ;;
	esac
}

# 把 mode 同步写进 config.json（只在文件已存在时改两个字段，不做 JSON 解析）
jdsp_mode_config_set() {
	_cfg="${JDSP_CONFIG_FILE:-/data/adb/jdspd/config.json}"
	[ -f "$_cfg" ] || return 0
	case "$1" in backend) _i=1 ;; *) _i=0 ;; esac
	_t="$_cfg.modeset.$$"
	if sed -e "s/\(\"mode\"[[:space:]]*:[[:space:]]*\)[0-9][0-9]*/\1$_i/" \
		-e "s/\(\"modeName\"[[:space:]]*:[[:space:]]*\)\"[A-Za-z_]*\"/\1\"$1\"/" "$_cfg" > "$_t" 2>/dev/null \
		&& grep -q '"modeName"' "$_t" 2>/dev/null; then
		cat "$_t" > "$_cfg" 2>/dev/null
		jdsp_mode_log "config.json mode set to $1"
	fi
	rm -f "$_t" 2>/dev/null
	return 0
}

jdsp_mode_set() {
	_new=$1
	case "$_new" in
		auto|backend) ;;
		*) echo "usage: jdsp_mode.sh set auto|backend" >&2; return 2 ;;
	esac
	mkdir -p "${JDSP_MODE_FILE%/*}" 2>/dev/null
	chmod 0700 "${JDSP_MODE_FILE%/*}" 2>/dev/null
	printf '%s\n' "$_new" > "$JDSP_MODE_FILE" && chmod 0600 "$JDSP_MODE_FILE" 2>/dev/null
	jdsp_mode_tag=set
	jdsp_mode_config_set "$_new"
	jdsp_mode_log "mode set to $_new"
	jdsp_mode_apply set
	echo "mode=$_new written to $JDSP_MODE_FILE"
	echo "REBOOT REQUIRED: audio_effects.xml 只在 audioserver 启动时读一次，且 hybrid_mount 的挂载是 ro 快照"
}

# 模块内所有 audio_effects xml 副本（含 sku_* 变体）
jdsp_mode_files() {
	find "$JDSP_MODULE_DIR" -type f -name 'audio_effects.xml' 2>/dev/null
}

jdsp_mode_count() {
	[ -f "$1" ] || { echo 0; return 0; }
	_c=$(grep -c 'effect="jamesdsp"' "$1" 2>/dev/null)
	[ -n "$_c" ] || _c=0
	echo "$_c"
}

# 去掉 <apply effect="jamesdsp"/>；整行只有我们注入的那一句时连行删掉（保持文件干净）
jdsp_mode_disable_file() {
	_f=$1
	[ -f "$_f" ] || return 0
	[ "$(jdsp_mode_count "$_f")" -gt 0 ] || return 0
	_t="$_f.jdspmode.$$"
	if awk '
		/<stream type="music">[[:space:]]*<apply effect="jamesdsp"\/>[[:space:]]*<\/stream>/ { next }
		{ gsub(/<apply effect="jamesdsp"\/>/, ""); print }
	' "$_f" > "$_t" 2>/dev/null; then
		cat "$_t" > "$_f" 2>/dev/null
		rm -f "$_t" 2>/dev/null
		return 0
	fi
	rm -f "$_t" 2>/dev/null
	return 1
}

# 补上 <postprocess><stream type="music"><apply effect="jamesdsp"/></stream></postprocess>
# 算法与 common/jdsp_postprocess.sh 一致：忽略注释块里被注释掉的 <postprocess>；
# 已有未注释的 <stream type="music"> 就插进去；否则在第一个 </postprocess> 前新建一条；再没有就建整块。
jdsp_mode_enable_file() {
	_f=$1
	[ -f "$_f" ] || return 0
	grep -q '<audio_effects_conf' "$_f" 2>/dev/null || return 0
	[ "$(jdsp_mode_count "$_f")" -gt 0 ] && return 0
	_t="$_f.jdspmode.$$"
	if awk '
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
	' "$_f" > "$_t" 2>/dev/null; then
		cat "$_t" > "$_f" 2>/dev/null
		rm -f "$_t" 2>/dev/null
		return 0
	fi
	rm -f "$_t" 2>/dev/null
	return 1
}

# 按当前配置纠正所有模块内副本
jdsp_mode_apply() {
	jdsp_mode_tag=${1:-apply}
	_mode=$(jdsp_mode_get)
	_changed=0
	_seen=0
	for _f in $(jdsp_mode_files); do
		_seen=$((_seen + 1))
		_before=$(jdsp_mode_count "$_f")
		case "$_mode" in
			backend)
				jdsp_mode_disable_file "$_f" && _after=$(jdsp_mode_count "$_f")
				;;
			*)
				jdsp_mode_enable_file "$_f"
				_after=$(jdsp_mode_count "$_f")
				;;
		esac
		[ -n "$_after" ] || _after=$_before
		if [ "$_before" != "$_after" ]; then
			_changed=$((_changed + 1))
			jdsp_mode_log "mode=$_mode $_f apply: $_before -> $_after"
		fi
	done
	[ "$_changed" = 0 ] && jdsp_mode_log "mode=$_mode, $_seen file(s), already consistent"
	return 0
}

# 只读校验：比对已挂载的 xml 与配置
#
# 除了 apply 数量，还要检查**挂载的是不是我们这份文件**（inode 对比）。
# 原因：别的模块（这台机器上是 XagaSoundBoostTest）也可能提供 /vendor/etc/audio_effects.xml，
# 并且会赢得挂载。那样我们把模块副本改成什么样都没用 —— 挂载出来的还是别人的文件，
# backend 模式永远不生效。inode 不同 = 挂载的不是我们那份。
jdsp_mode_verify() {
	jdsp_mode_tag=${1:-verify}
	_mode=$(jdsp_mode_get)
	_bad=0
	_line=""
	for _f in /odm/etc/audio_effects.xml /vendor/etc/audio_effects.xml /system/etc/audio_effects.xml; do
		[ -f "$_f" ] || continue
		_c=$(jdsp_mode_count "$_f")
		_case_ok=1
		if [ "$_mode" = backend ] && [ "$_c" != 0 ]; then _case_ok=0; fi
		if [ "$_mode" = auto ] && [ "$_c" -lt 1 ]; then _case_ok=0; fi
		[ "$_case_ok" = 0 ] && _bad=1
		_line="$_line $_f=$_c"
	done
	for _f in /vendor/etc/audio/sku_*/audio_effects.xml; do
		[ -f "$_f" ] || continue
		_c=$(jdsp_mode_count "$_f")
		_line="$_line $_f=$_c"
	done
	# inode 对比：挂载点必须是我们模块里**对应分区**的那份副本。
	# 别的模块（这台机器上是 XagaSoundBoostTest）也提供 /vendor/etc/audio_effects.xml 并赢得挂载，
	# 那时我们把副本改成什么都没用 —— 挂载出来的还是别人的文件，backend 模式永远不生效。
	# 注意要分区对应：/vendor|odm 对应模块的 system/vendor|system/odm，/system 对应 system/system。
	jdsp_mode_check_inode() {
		_m=$1        # 挂载点
		_our=$2      # 我们模块里对应的副本
		[ -f "$_m" ] || return 0
		[ -f "$_our" ] || return 0
		_m_inode=$(stat -c %i "$_m" 2>/dev/null)
		_our_inode=$(stat -c %i "$_our" 2>/dev/null)
		[ -n "$_m_inode" ] && [ -n "$_our_inode" ] || return 0
		[ "$_m_inode" = "$_our_inode" ] && return 0
		if [ "$_mode" = "backend" ]; then
			_bad=1
			jdsp_mode_log "WARN: another module provides $_m (inode mismatch: mounted=$_m_inode ours=$_our_inode), backend mode will not take effect"
		else
			jdsp_mode_log "note: $_m is provided by another module (mounted=$_m_inode ours=$_our_inode); harmless in auto mode"
		fi
		for _cand in /data/adb/modules/*/system/vendor/etc/audio_effects.xml /data/adb/modules/*/system/etc/audio_effects.xml; do
			[ -f "$_cand" ] || continue
			[ "$(stat -c %i "$_cand" 2>/dev/null)" = "$_m_inode" ] || continue
			jdsp_mode_log "     provided by: $_cand"
			break
		done
		return 0
	}
	_M=/data/adb/modules/ainur_jamesdsp
[ -d "$_M" ] || _M="$JDSP_MODULE_DIR"
	jdsp_mode_check_inode /vendor/etc/audio_effects.xml "$_M/system/vendor/etc/audio_effects.xml"
	jdsp_mode_check_inode /odm/etc/audio_effects.xml    "$_M/system/odm/etc/audio_effects.xml"
	jdsp_mode_check_inode /system/etc/audio_effects.xml "$_M/system/etc/audio_effects.xml"
	if [ "$_bad" = 1 ]; then
		jdsp_mode_log "mode=$_mode$_line WARN mismatch: 已挂载的 xml 与 mode 不一致（需要重启才能生效）"
		return 1
	fi
	jdsp_mode_log "mode=$_mode$_line OK"
	return 0
}

case "$0" in
	*jdsp_mode.sh)
		case "${1:-get}" in
			get) jdsp_mode_get ;;
			set) jdsp_mode_set "$2" ;;
			apply) jdsp_mode_apply "${2:-manual}" ;;
			verify) jdsp_mode_verify "${2:-manual}" ;;
			*) echo "usage: jdsp_mode.sh get|set <auto|backend>|apply [tag]|verify [tag]" ;;
		esac
		;;
esac
