osp_detect() {
  case $1 in
    *.conf) SPACES=$(sed -n "/^output_session_processing {/,/^}/ {/^ *music {/p}" $1 | sed -r "s/( *).*/\1/")
            EFFECTS=$(sed -n "/^output_session_processing {/,/^}/ {/^$SPACES\music {/,/^$SPACES}/p}" $1 | grep -E "^$SPACES +[A-Za-z]+" | sed -r "s/( *.*) .*/\1/g")
            for EFFECT in ${EFFECTS}; do
              SPACES=$(sed -n "/^effects {/,/^}/ {/^ *$EFFECT {/p}" $1 | sed -r "s/( *).*/\1/")
              [ "$EFFECT" != "atmos" ] && sed -i "/^effects {/,/^}/ {/^$SPACES$EFFECT {/,/^$SPACES}/ s/^/#/g}" $1
            done;;
     *.xml) EFFECTS=$(sed -n "/^ *<postprocess>$/,/^ *<\/postprocess>$/ {/^ *<stream type=\"music\">$/,/^ *<\/stream>$/ {/<stream type=\"music\">/d; /<\/stream>/d; s/<apply effect=\"//g; s/\"\/>//g; p}}" $1)
            for EFFECT in ${EFFECTS}; do
              [ "$EFFECT" != "atmos" ] && sed -ri "/^( *)<apply effect=\"$EFFECT\"\/>/d" $1
            done;;
  esac
}

copy_before_uninstall() {
	APPLOC="/storage/emulated/0/Android/data/james.dsp"

	if [ -d "$APPLOC" ]; then
		mkdir -p $MODPATH/JamesDSP
		cp -rf "$APPLOC"/files/* $MODPATH/JamesDSP
	fi
}

# 仅在安装新版本时清理旧版 app，使 jdsp_app.sh 能在开机时重装新版本
uninstall_app() {
	set +x
	local PKG="james.dsp"
	local VER=$1
	[ -z $INSVER ] && return 0
	[ -f $NVBASE/modules/$MODID/.apkpbone ] || return 0
	[ $INSVER -lt $VER ] || return 0
	pm uninstall "$PKG" > /dev/null 2>&1
	pm uninstall --user 0 "$PKG" > /dev/null 2>&1
	find /data/app -type d -name "*$PKG*" -exec rm -rf {} + 2>/dev/null
	rm -rf /data/data/$PKG /data/user_de/0/$PKG
}

# Tell user aml is needed if applicable
FILES=$(find $NVBASE/modules/*/system $MODULEROOT/*/system -type f -name "*audio_effects*.conf" -o -name "*audio_effects*.xml" 2>/dev/null | sed "/$MODID/d" | sed "/sv_apixaml/d")
if [ ! -z "$FILES" ] && [ ! "$(echo $FILES | grep '/aml/')" ]; then
  ui_print " "
  ui_print "   ! Conflicting audio mod found!"
  ui_print "   ! You will need to install !"
  ui_print "   ! Audio Modification Library !"
  sleep 3
fi

#Lib detection
LIB=$(lshal debug $(lshal | grep -oE "android.hardware.audio.effect@[0-9]+\.[0-9]+::IEffectsFactory/default" | head -n 1) | grep -E 'lib|lib64' | awk -F '/' 'NR==1{print $3}')

LIB2=$(file /*/bin/hw/android.hardware.audio.service* 2>/dev/null | grep -oE "32-bit|64-bit" | head -n 1)

if service list | grep -q "android.hardware.audio.effect.IFactory"; then
AIDL=true
else
AIDL=false
fi

ui_print " "
ui_print "- Lib bit detection -"

if [ "$AIDL" == "true" ]; then
	QARCH="aidl"
	ui_print "  AIDL libs detected "
	touch $MODPATH/.AIDL
	cp_ch $MODPATH/common/files/$QARCH/libjamesdsp.so $MODPATH/system/lib64/soundfx/libjamesdsp.so
elif [ "$LIB" == "lib64" ] || [ "$LIB2" == "64-bit" ]; then
    if grep -q 'sve' /proc/cpuinfo 2>/dev/null; then
        QARCH="arm64-sve"
        ui_print "   64 bit audio libs detected with SVE support, using SVE optimized libs"
    else
        QARCH="arm64"
        ui_print "   64 bit audio libs detected"
    fi
    cp_ch $MODPATH/common/files/$QARCH/libjamesdsp.so $MODPATH/system/lib64/soundfx/libjamesdsp.so
elif [ "$LIB" == "lib" ] || [ "$LIB2" == "32-bit" ]; then
	QARCH=$ARCH32
	ui_print "   32 bit audio libs detected  "
	# AIDL libraries are 64-bit only; do not install them into the 32-bit path.
[ "$QARCH" = "aidl" ] || cp_ch $MODPATH/common/files/$QARCH/libjamesdsp.so $MODPATH/system/lib/soundfx/libjamesdsp.so
else
	ui_print "   Unable to detect audio lib bit...  "
	ui_print "   Is this a device with 64bit only audio libs?"
	ui_print "   If unsure, select 'No'"
	ui_print "   Vol Up = Yes, Vol Down = No"
  
  # 非交互：默认 64 位
  if [ "$AIDL" == "true" ]; then
	QARCH="aidl"; touch $MODPATH/.AIDL
  else
	QARCH="arm64"
  fi
  cp_ch $MODPATH/common/files/$QARCH/libjamesdsp.so $MODPATH/system/lib64/soundfx/libjamesdsp.so
  ui_print "   64 bit libs selected (auto)"
  
fi

cp_ch $MODPATH/common/files/$QARCH/libjamesdsp.so $MODPATH/system/lib/soundfx/libjamesdsp.so

set +x
# App only works when installed normally to data in oreo+
INSVER=$(cmd package list packages -3 --show-versioncode  2>/dev/null | grep james.dsp | sed 's/.*versionCode://')
set -x
ui_print " "
ui_print "- UI installation -"
copy_before_uninstall
# 只提供 ThePBone UI（原版 APK 已移除）
ui_print "   ThePBone UI"
mv -f $MODPATH/common/files/JamesDSPManagerThePBone.apk $MODPATH/JamesDSPManager.apk
touch $MODPATH/.apkpbone
uninstall_app $PAPPVER
set -x
# Audio effects patching 
ui_print " "
ui_print "   Patching existing audio_effects files..."
PARTITIONS="/system /vendor $PARTITIONS"
CFGS="$(find $PARTITIONS -type f -name "*audio_effects*.conf" -o -name "*audio_effects*.xml")"
[ -f "$MODPATH/common/jdsp_postprocess.sh" ] && . "$MODPATH/common/jdsp_postprocess.sh"
for OFILE in ${CFGS}; do
  FILE="$MODPATH$(echo $OFILE | sed "s|^/vendor|/system/vendor|g")"
  FILE="$(echo $FILE | sed "s|$MODPATH/odm|$MODPATH/system/odm|g")"
if [ -f $ORIGDIR$OFILE ];then
  cp_ch -n $ORIGDIR$OFILE $FILE
  osp_detect $FILE
	if [ $AIDL == "true" ];then
	  case $FILE in
		*.conf) sed -i "/jamesdsp {/,/}/d" $FILE
				sed -i "/jdsp {/,/}/d" $FILE
				sed -i "s/^effects {/effects {\n  jamesdsp {\n    library jdsp\n    uuid f27317f4-c984-4de6-9a90-545759495bf2\n    type f98765f4-c321-5de6-9a45-123459495ab2\n  }/g" $FILE
				sed -i "s/^libraries {/libraries {\n  jdsp {\n    path $LIBPATCH\/lib\/soundfx\/libjamesdsp.so\n  }/g" $FILE;;
		*.xml) sed -i "/jamesdsp/d" $FILE
			   sed -i "/jdsp/d" $FILE
			   sed -i "/<libraries>/ a\        <library name=\"jdsp\" path=\"libjamesdsp.so\"\/>" $FILE
			   sed -i "/<effects>/ a\        <effect name=\"jamesdsp\" library=\"jdsp\" uuid=\"f27317f4-c984-4de6-9a90-545759495bf2\" type=\"f98765f4-c321-5de6-9a45-123459495ab2\"\/>" $FILE;;
	  esac
	else
	  case $FILE in
		*.conf) sed -i "/jamesdsp {/,/}/d" $FILE
				sed -i "/jdsp {/,/}/d" $FILE
				sed -i "s/^effects {/effects {\n  jamesdsp {\n    library jdsp\n    uuid f27317f4-c984-4de6-9a90-545759495bf2\n  }/g" $FILE
				sed -i "s/^libraries {/libraries {\n  jdsp {\n    path $LIBPATCH\/lib\/soundfx\/libjamesdsp.so\n  }/g" $FILE;;
		*.xml) sed -i "/jamesdsp/d" $FILE
			   sed -i "/jdsp/d" $FILE
			   sed -i "/<libraries>/ a\        <library name=\"jdsp\" path=\"libjamesdsp.so\"\/>" $FILE
			   sed -i "/<effects>/ a\        <effect name=\"jamesdsp\" library=\"jdsp\" uuid=\"f27317f4-c984-4de6-9a90-545759495bf2\"/>" $FILE;;
	  esac
	fi
	# 补充 <postprocess>：让 audioserver 自动把 jamesdsp 挂到 music 流，不再依赖 App 主动 AudioEffect()
	case $FILE in
		*.xml)  command -v jdsp_apply_music      >/dev/null 2>&1 && jdsp_apply_music      "$FILE";;
		*.conf) command -v jdsp_apply_music_conf >/dev/null 2>&1 && jdsp_apply_music_conf "$FILE";;
	esac
fi
done

# adding executive permissions to jdsp_app.sh
chmod +x "$MODPATH/jdsp_app.sh"

sed -i "1a NVBASE=$NVBASE" $MODPATH/service.sh

ui_print "   Copying apk to /sdcard. Install manually if not present on reboot"
if [ -d /storage/emulated/0/JamesDSP/liveprog ]; then
	mv /storage/emulated/0/JamesDSP/liveprog /storage/emulated/0/JamesDSP/liveprog2
	mv /storage/emulated/0/JamesDSP/liveprog2 /storage/emulated/0/JamesDSP/Liveprog
fi
cp -rf $MODPATH/common/files/JamesDSP /storage/emulated/0/
[ -d $MODPATH/JamesDSP ] && cp -rf $MODPATH/JamesDSP/* /storage/emulated/0/JamesDSP/
cp -f $MODPATH/JamesDSPManager.apk /storage/emulated/0/JamesDSPManager.apk
[ $API -gt 29 ] && { ui_print "   Enabling hidden api policy"; settings put global hidden_api_policy 1 2>/dev/null; }