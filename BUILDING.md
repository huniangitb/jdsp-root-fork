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
