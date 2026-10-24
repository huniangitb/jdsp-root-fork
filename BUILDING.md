# 从源码复现全部二进制

三步，产物与已验证版本一致（sha256 见 README 产物表）。

## 1. 引擎库 libjamesdsp.so

```sh
# 取上游源码
git clone -b extensions https://github.com/evoeram/JamesDSPManager.git jdsp-src
cd jdsp-src && cp -a . ../src-baseline && cd .. && cp -a src-baseline src
patch -p1 -d src < native/patches/0001-state-persistence-and-hot-reload.patch

# 构建（NDK r30）
cd src && NDK=/path/to/android-ndk-r30 bash ../native/build.sh
# 产出 out/libjamesdsp-arm64-v8a.so / out/libjamesdsp-armv7a.so
```

放到模块对应目录：

```sh
cp out/libjamesdsp-arm64-v8a.so module/src/common/files/arm64/libjamesdsp.so
cp out/libjamesdsp-armv7a.so   module/src/common/files/arm/libjamesdsp.so
# aidl / arm64-sve / x86 三路沿用上游发布的预编译库（本 fork 未修改）
```

## 2. App（汉化 + 默认项 + 去悬浮窗）

```sh
keytool -genkeypair -keystore jdsp.keystore -alias jdsp -keyalg RSA -keysize 2048 \
        -validity 10000 -storepass jdsp123 -keypass jdsp123 \
        -dname "CN=JamesDSP Fork, O=Local, C=CN"

./app/patch_app.sh <官方 root-full APK> out.apk jdsp.keystore jdsp123 jdsp123
cp out.apk module/src/common/files/JamesDSPManagerThePBone.apk
```

官方 root-full APK 来源见上游 BUILD_ROOT.md 里的 nightly 站点。

## 3. 打包模块

```sh
cd module/src && zip -r -X ../JDSP-fork.zip . -x '*.git*'
```

刷入（KernelSU / Magisk），重启即可。

## 4. 自检

```sh
./native/test/run_tests.sh      # 状态文件格式 / 热加载 / watcher / 回退路径
```

## 5. AIDL 变体（可选）

\`\`\`sh
# 生成与目标平台版本匹配的 AIDL 头（effect V<n> + common.types V<m>）
AIDL_EFFECT_V=3 AIDL_TYPES_V=5 ./native/aidl/build-aidl.sh headers
AIDL_EFFECT_V=3 AIDL_TYPES_V=5 ./native/aidl/build-aidl.sh vendor
# 产物 out-aidl/libjamesdspaidl.so -> module/src/common/files/aidl/libjamesdsp.so
\`\`\`

需要从目标机 \`/system/lib64\` 拷出 stub 库到 \`aidl-vendor/device-libs/\`，
细节与 AOSP 头来源见 \`native/aidl/AIDL-NOTES.md\`。

## 6. 引擎补丁与 App 补丁（全局开关）

引擎侧（native/patches/）：

| 补丁 | 内容 |
|---|---|
| 0001-state-persistence-and-hot-reload.patch | 状态持久化 / inotify 热加载 / builder / crossfade |
| 0002-engine-global-switch.patch | EFFECT_CMD_DISABLE 真正直通；新增 SET_PARAM cmd=400 全局开关；release 也定义日志宏 |

应用侧（app/patch_app.sh）新增一步：把 JamesDspRemoteEngine.setEnabled() 改成同时发送
SET_PARAM(cmd=400, value=on/off)，这样 App 界面上的总开关可以直接关掉后端处理。

```sh
./app/patch_app.sh <官方 root-full APK> out.apk jdsp.keystore jdsp123 jdsp123
```

没有 App 或需要从 shell 切换时，用模块自带的 jdsp_switch.sh on|off（写同一条 cmd400 记录）。
