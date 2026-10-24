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

## jdspcli（命令行客户端）

`jdspcli` 是 jdspd 的控制端：排障、验收、脚本化都走它。

### 设备上运行

```sh
# 放到一个 root 拥有、其它用户不可写的目录（ART 拒绝 world-writable 的 dex）
adb shell 'mkdir -p /data/local/tmp/jdsp-accept && chmod 755 /data/local/tmp/jdsp-accept'
adb push out/jdspcli.dex /data/local/tmp/jdsp-accept/jdspcli.dex
adb shell 'chmod 644 /data/local/tmp/jdsp-accept/jdspcli.dex'

adb shell 'CLASSPATH=/data/local/tmp/jdsp-accept/jdspcli.dex app_process /system/bin com.jdsp.cli.Main sessions'
```

### 命令表

| 命令 | 说明 |
|---|---|
| `sessions` | 列出播放会话（session / uid / pkg / attached / enabled / params / effectId / policy） |
| `get <session> <param> [vsize]` | 读参数，输出小写 hex；vsize 默认 4 字节 |
| `set <session> <param> <v...>` | 写参数；默认每个值按 **float32 小端** 编码 |
| `attach <session>` / `detach <session>` | 建立 / 释放该会话的效果句柄 |
| `commit <session>` | 提交批量参数 |
| `dump-config` | 全量配置（JSON） |
| `dump-session <session>` | 单会话摘要（JSON） |
| `stats` | 运行统计（JSON） |
| `ping` | 探活 |
| `version` | 打印 jdspd 版本 |
| `mode [auto|backend]` | 显示 / 设置架构模式（0=auto 1=backend） |
| `policy list\|get <pkg>\|set <pkg> <follow\|on\|exclude>` | 策略（0=follow 1=on 2=exclude） |
| `bench <session> <param> <v...> [--iters N]` | set/get 往返延迟与一致性探针（默认 100 次） |
| `help` | 用法 |

`set` 的取值方式（互斥）：

| 写法 | 含义 |
|---|---|
| `set 161 1500 -0.1 60 3.0` | 三个 float32 小端（12 字节） |
| `--hex <hex>` | 精确字节，接受 `0x`/空格/冒号/逗号分隔 |
| `--int <n>` | int32 小端（可再加若干位置参数） |
| `--short <n>` | int16 小端（可再加若干位置参数） |
| `--raw` | 位置参数按 hex 分片拼接 |

### 全局选项

| 选项 | 说明 |
|---|---|
| `--json` | 机器可读输出（`sessions` 输出 JSON 数组，`get` 输出 `{"session","param","vsize","len","hex"}`） |
| `--tcp [host:port]` | 强制走 loopback TCP 通道；不给端口时读 `/data/data/james.dsp/files/jdsp.port` + `jdsp.token` |
| `--help`, `-h` | 用法 |

### 三个传输通道

| 通道 | 接入方 | 说明 |
|---|---|---|
| binder `jdsp.service` | root / su 域 | 默认第一选择；App 侧受 SELinux 限制连不上 |
| loopback TCP + token | App 与 root 都可以 | jdspd 写 `jdsp.port`（端口）与 `jdsp.token`（hex），首行 `{"op":"hello","token":"<hex>"}` |
| root shell（`app_process` + `CLASSPATH`） | root | 兜底手段，不需要装 App |

默认顺序：**binder → TCP → 都不可用才报错退出（exit 2）**。`--tcp` 可以强制走 TCP。

### 退出码

| 码 | 含义 |
|---|---|
| 0 | 成功 |
| 1 | 本地内部错误 |
| 2 | jdspd 不可用（binder 服务不存在、TCP 端点缺失/连不上、token 被拒） |
| 3 | 参数错误（命令/选项/取值非法） |
| 4 | 远端返回非 0（引擎或 daemon 的错误码） |

错误信息示例：

```
jdspcli: jdspd is not running (service jdsp.service not found) and tcp endpoint files missing (/data/data/james.dsp/files/jdsp.{port,token})
jdspcli: tcp hello rejected (bad token)
jdspcli: tcp endpoint unreachable (127.0.0.1:38011: Connection refused)
```

### 测试

```sh
bash backend/test/run_tests.sh              # 主机单测（纯 Java，无 JUnit，无需设备）
bash backend/test/device/run_acceptance.sh  # 真机验收（PLAN-v2 §6 第 1-4 项）
SOAK_SECONDS=1800 bash backend/test/device/run_acceptance.sh   # 追加 30 分钟 soak
```

## 目录

| 路径 | 内容 |
|---|---|
| `aidl/` | `IJdspService` 契约（Parcelable `JdspSession`） |
| `src/com/jdsp/backend/` | 守护进程：SessionWatcher / EffectManager / ParamBus / ConfigStore / PolicyEngine / BinderService |
| `src/com/jdsp/cli/` | `jdspcli` 客户端与命令解析 |
| `src/com/jdsp/common/` | 共享常量 |
| `test/` | 主机单测（dumpsys 解析、配置 JSON、策略） |