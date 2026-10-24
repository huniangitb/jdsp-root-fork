# backend/ — jdspd 后端派发服务

PLAN-v2 P1–P4 的实现：一个常驻的 root 守护进程，持有每条播放会话的
`AudioEffect` 控制句柄，把参数实时下发到引擎，并对外暴露 AIDL。

## 构建

```sh
./build.sh          # out/jdspd.dex, out/jdspcli.dex
./build.sh clean
```

只需要 Android SDK（build-tools 里的 `aidl`、`cmdline-tools` 里的 `d8`、任意
`platforms/android-*/android.jar`）。JDK 21 即可。

## 运行（设备，root）

```sh
adb push out/jdspd.dex out/jdspcli.dex /data/local/tmp/
adb shell su -c 'CLASSPATH=/data/local/tmp/jdspd.dex app_process /system/bin --nice-name=jdspd com.jdsp.backend.Daemon &'
adb shell su -c 'CLASSPATH=/data/local/tmp/jdspcli.dex app_process /system/bin com.jdsp.cli.Main sessions'
```

## 目录

| 路径 | 内容 |
|---|---|
| `aidl/` | `IJdspService` 契约（Parcelable `JdspSession`） |
| `src/com/jdsp/backend/` | 守护进程：SessionWatcher / EffectManager / ParamBus / ConfigStore / PolicyEngine / BinderService |
| `src/com/jdsp/cli/` | `jdspcli` 客户端与命令解析 |
| `src/com/jdsp/common/` | 共享常量 |
| `test/` | 主机单测（dumpsys 解析、配置 JSON、策略） |
