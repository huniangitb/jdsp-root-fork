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

# 注：标准分区（system/vendor/odm）的 SELinux 上下文由元模块在挂载时自动按目标上下文处理，
#     安装器的 set_perm_recursive 也会设一次；这里不再重复 chcon。

# fork 引擎的 native 状态自持久化：只准备 /data/vendor/audio 这一个路径
# （目录/属主/0660/标签/日志），不 mount、不建软链、不批量 chcon。
# 挂载与分区上下文全部由元模块负责。
if [ -f "$MODPATH/jdsp_state.sh" ]; then
	. "$MODPATH/jdsp_state.sh"
	jdsp_state_prepare post-fs-data
fi
