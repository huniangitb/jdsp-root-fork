#!/system/bin/sh
MODPATH=${0%/*}
MODDIR="$MODPATH"
mkdir -p "$MODPATH/logs"
exec 2>"$MODPATH/logs/post-fs-debug.txt"
set -x

if [ -d "$MODPATH/system/vendor" ] && [ ! -L "$MODPATH/system/vendor" ]; then
	MOD_VENDOR="$MODPATH/system/vendor"
	MOD_ODM="$MODPATH/system/odm"
else
	MOD_VENDOR="$MODPATH/vendor"
	MOD_ODM="$MODPATH/odm"
fi

if [ -f $MODPATH/.AIDL ];then
	AIDL=true
else
	AIDL=false
fi

# 注册 jdsp 到各 audio_effects 文件（只改模块内副本，挂载由元模块做）
. "$MODPATH/aml.sh"
# AIDL 设备：按系统版本修 lib 依赖
. "$MODPATH/patch.sh"

# v2: 按 /data/adb/jdspd/mode 纠正模块内的 audio_effects.xml 副本。
# 注意：hybrid_mount 在 metamount 阶段已把模块文件拷进各分区镜像（ro 快照），
# 所以这一步对**本次**开机无效，作用是把副本纠正到当前 mode，**下次开机**生效；
# 平时靠 jdsp_mode.sh set 在切换时就把副本改好，这里是自愈兜底。
if [ -f "$MODPATH/jdsp_mode.sh" ]; then
	. "$MODPATH/jdsp_mode.sh"
	jdsp_mode_apply post-fs-data
fi

# 注：标准分区（system/vendor/odm）的 SELinux 上下文由元模块在挂载时自动按目标上下文处理，
#     安装器的 set_perm_recursive 也会设一次；这里不再重复 chcon。

# fork 引擎的 native 状态自持久化：只准备 /data/vendor/audio 这一个路径
# （目录/属主/0660/标签/日志），不 mount、不建软链、不批量 chcon。
# 挂载与分区上下文全部由元模块负责。
if [ -f "$MODPATH/jdsp_state.sh" ]; then
	. "$MODPATH/jdsp_state.sh"
	jdsp_state_prepare post-fs-data
fi
