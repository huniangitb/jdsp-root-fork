# 从源码复现全部二进制

三步，产物与已验证版本一致（sha256 见 README 产物表）。

## 1. 引擎库 libjamesdsp.so

```sh
# 取上游源码并打补丁（自动处理 0001 的 Android.mk 上游漂移）
./native/setup-src.sh /path/to/work

# 构建（NDK r30）
NDK=/path/to/android-ndk-r30 SRC=/path/to/work/src/Main/libjamesdsp/jni/jamesdsp \
    bash native/build.sh
# 产出 out/libjamesdsp-arm64-v8a.so / out/libjamesdsp-armv7a.so
```

放到模块对应目录：

```sh
cp out/libjamesdsp-arm64-v8a.so module/src/common/files/arm64/libjamesdsp.so
cp out/libjamesdsp-armv7a.so   module/src/common/files/arm/libjamesdsp.so
# aidl / arm64-sve / x86 三路沿用上游发布的预编译库——注意：这三路不含
# 0001/0002 的 fork 补丁（无写锁、无持久化）。目标设备若走 aidl 或 arm64-sve
# 路径，必须先按第 5 节构建对应变体，否则 App 直写不受保护。
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
SRC=/path/to/work/src/Main/libjamesdsp/jni/jamesdsp ./native/test/run_tests.sh
# 状态文件格式 / 热加载 / watcher / 回退路径 / 写锁（0002）语义
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

## 6. 版本基线

当前模块源码 = v2.1.9-fork（versionCode 219），内置后端 jdspd 2.1.9、引擎补丁 0001-0008、
App 1.6.20（versionCode 66；重连后以**后端**开关状态为准，另有 1.6.19 的一帧一批 / 全局参数 s=0、
1.6.18 的总开关意图直投 / -1006 业务码透传 / 20 s TCP 保活）。分发包
`dist/JDSP-fork-v2.1.9.zip`（33,274,174 B，sha256 `4918908cc089d38e12aff749b78557f1a0143400622bfd6675c5826960beec6f`）。

App 构建（源码路线，Gradle 离线）：`cd /home/bdy/work/loundess_git && ./gradlew --offline --no-daemon \
-Dorg.gradle.jvmargs=-Xmx3g --max-workers=2 assembleRootFullRelease`，产物
`app/build/outputs/apk/rootFull/release/JamesDSP-v1.6.20-66-root-full-universal-release.apk`
（debug 签名证书 `904ddf44…`，可直接覆盖升级），再拷到 `module/src/common/files/JamesDSPManagerThePBone.apk`。

引擎侧补丁（`native/patches/`，arm64/arm 两路都已编入；arm64-sve/aidl/x86 仍是上游预编译，未含补丁）：
- `0001-state-persistence-and-hot-reload.patch`：状态持久化 + inotify 热加载
- `0002-engine-write-lock.patch`：调用方写保护（参数只允许 jdspd 解锁后写入）
- `0003-deferred-param-apply-and-no-cross-instance-rebuild.patch`：SET_PARAM 延迟到音频线程块边界应用 +
  消除跨实例重建风暴（滑杆连续拖动无毛刺）
- `0004-state-writer-thread-and-dump.patch`：state.bin 异步落盘 + EFFECT_CMD_DUMP + 禁用态语义
- `0005-accumulate-output-contract.patch`：遵守 EFFECT_BUFFER_ACCESS_ACCUMULATE，修复同输出第二会话静音
- `0006-async-engine-prime.patch`：EffectCreate 发布 pristine 直通实例，state 重放异步完成（attach ~90ms）
- `0007-engine-capture-tap.patch`：采集 tap（32001/32002），effect 入/出两路 PCM 落 WAV；默认关闭、
  未武装零开销。详见 `docs/ENGINE-TAP-AND-MIX-CHECK.md`
- `0008-engine-mix-integrity-check.patch`：accumulate 路径上的 mix 保真自检（30023-30028）；默认关闭

后端：`backend/`（jdspd + jdspcli），`./backend/build.sh` 产物进 `module/src/backend/` 后由
`./module/package.sh` 打包。注意 `native/build.sh` 目前只编译不应用补丁（见
`out/verify/dist-verify.md` R1/R2），补丁版 .so 需按 `native/setup-src.sh` + 手工应用 0004-0008。
