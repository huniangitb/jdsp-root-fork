# 调试工具

## JdspCtl — 免开 App 调参

通过 `AudioEffect` 直接连到引擎实例下发参数（session 0 = 全局控制实例）。

```sh
# 编译成 dex（需要 android.jar + d8）
AAR=$ANDROID_HOME/platforms/android-36/android.jar
javac -source 8 -target 8 -bootclasspath "$AAR" -d out JdspCtl.java
d8 --lib "$AAR" --output out out/JdspCtl.class
adb push out/classes.dex /data/local/tmp/jdspctl.dex

# 用法：<sessionId> <paramId> <float...>
adb shell su -c 'CLASSPATH=/data/local/tmp/jdspctl.dex app_process /system/bin JdspCtl 0 1500 -0.1 60 3.0'
# 只开关
adb shell su -c 'CLASSPATH=/data/local/tmp/jdspctl.dex app_process /system/bin JdspCtl 0 enable'
```

参数 id 与 App 的 `JamesDspRemoteEngine` 一致（1500 = 限幅器 threshold/release/postGain，
1300 = PEQ 级联，1301/1302 = loudness，20001 = 采样率查询）。

## Tone / ToneFast — 播放测试音

```sh
javac -source 8 -target 8 -bootclasspath "$AAR" -d out Tone.java
d8 --lib "$AAR" --output out out/Tone.class
adb push out/classes.dex /data/local/tmp/tone.dex
adb shell su -c 'CLASSPATH=/data/local/tmp/tone.dex app_process /system/bin Tone 8'
```

`Tone` 用默认属性（走 deep-buffer），`ToneFast` 设 `PERFORMANCE_MODE_LOW_LATENCY`
（走 fast track，用于验证低延迟路径）。
