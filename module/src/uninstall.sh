# Don't modify anything after this
if [ -f $INFO ]; then
  while read LINE; do
    if [ "$(echo -n $LINE | tail -c 1)" == "~" ]; then
      continue
    elif [ -f "$LINE~" ]; then
      mv -f $LINE~ $LINE
    else
      rm -f $LINE
      while true; do
        LINE=$(dirname $LINE)
        [ "$(ls -A $LINE 2>/dev/null)" ] && break 1 || rm -rf $LINE
      done
    fi
  done < $INFO
  rm -f $INFO
fi

# v2: 停掉 jdspd 看护循环与守护进程，清理运行目录（mode/config/日志/socket）。
# 保留 /data/vendor/audio/jamesdsp_state.bin（属主 1041，引擎状态；重装后仍是用户最后一次设置）。
for JDSP_PID in $(pidof jdspd 2>/dev/null); do kill "$JDSP_PID" 2>/dev/null; done
# 不依赖 pkill：扫 /proc 找 cmdline 里带 jdspd.sh 的看护脚本
for JDSP_PROC in /proc/[0-9]*; do
	[ -r "$JDSP_PROC/cmdline" ] || continue
	case "$(tr '\0' ' ' < "$JDSP_PROC/cmdline" 2>/dev/null)" in
		*jdspd.sh*) kill "${JDSP_PROC#/proc/}" 2>/dev/null ;;
	esac
done
rm -rf /data/adb/jdspd

# 说明：卸载模块不再卸载 james.dsp 应用，应用与其数据保留。
