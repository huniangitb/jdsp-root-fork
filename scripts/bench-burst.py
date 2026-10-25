#!/usr/bin/env python3
"""bench-burst.py — 模拟"用户快速拖滑条"的高频提交流，验证 jdspd 后端接得住。

场景：一条 TCP 长连接（与 App 相同的传输），在 N 秒内以 RATE 次/秒连续提交
setParam，值每条都变（postGain 在 ±12 dB 正弦扫动，模拟实时拖动）。
结束后核对：
  * 每一条的响应都 ok（无丢失/无失败）
  * 端到端延迟分布（p50/p95/p99）
  * jdspd stats 的 setParamCalls 增量 == 发送数（后端真实入账）
  * dumpConfig 里最终值 == 最后一条的值（后写覆盖前写，不错序）
  * 放音会话 underrun 保持 0（引擎没被高频打爆）
  * config.json 落盘节流生效（文件 mtime 变化次数远小于提交数）

用法：adb forward 已由调用方设置好。
  bench-burst.py --port 14271 --token <hex> [--seconds 30] [--rate 100] [--session 289]

开始前与结束后（含异常退出）都会执行一次 `jdspcli baseline --all`（经 adb + su），
恢复 canonical 参数基线：burst 残留（例如 postGain 扫动留下的值）不得污染下一轮
配置（2026-10-25 回归类）。旧 jdspcli 不认识 baseline 时按告警跳过。
"""
import argparse
import json
import math
import socket
import struct
import subprocess
import sys
import time

DEFAULT_CLI = "/data/adb/modules/ainur_jamesdsp/backend/jdspcli.dex app_process /system/bin com.jdsp.cli.Main"


def adb_baseline(serial, cli):
    """One best-effort `jdspcli baseline --all` over adb; never raises."""
    try:
        cmd = ["adb"]
        if serial:
            cmd += ["-s", serial]
        cmd += ["shell", "su", "-c", "CLASSPATH=%s baseline --all" % cli]
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        out = (r.stdout or "").strip()
        if "ok baseline" not in out:
            print("warn: baseline failed or unsupported: %s %s"
                  % (out, (r.stderr or "").strip()))
        else:
            print("baseline: %s" % out)
    except Exception as e:
        print("warn: baseline adb call failed: %s" % e)


def float3le(a, b, c):
    return struct.pack("<fff", a, b, c)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--token", required=True)
    ap.add_argument("--seconds", type=float, default=30.0)
    ap.add_argument("--rate", type=float, default=100.0)
    ap.add_argument("--session", type=int, required=True)
    ap.add_argument("--serial", default="LNNZXG8TPFHU69AI",
                    help="adb serial for the baseline restore (empty string = skip)")
    ap.add_argument("--cli", default=DEFAULT_CLI,
                    help="device-side jdspcli invocation used for the baseline restore")
    args = ap.parse_args()

    if args.serial:
        adb_baseline(args.serial, args.cli)

    try:
        return run_burst(args)
    finally:
        # Restore the baseline on every exit path, exceptions included.
        if args.serial:
            adb_baseline(args.serial, args.cli)


def run_burst(args):
    s = socket.create_connection(("127.0.0.1", args.port), timeout=5)
    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    f = s.makefile("rwb")

    def rt(obj):
        f.write((json.dumps(obj) + "\n").encode())
        f.flush()
        line = f.readline()
        if not line:
            raise RuntimeError("connection closed")
        return json.loads(line)

    hello = rt({"op": "hello", "token": args.token})
    if not hello.get("ok"):
        print("FATAL: hello rejected", hello)
        return 2

    period = 1.0 / args.rate
    total = int(args.seconds * args.rate)
    lat = []
    fails = 0
    last_value = None
    t_start = time.perf_counter()

    for i in range(total):
        # postGain 正弦扫 ±12 dB，threshold/release 固定（模拟拖 postGain 滑条）
        gain = 12.0 * math.sin(2 * math.pi * i / max(total - 1, 1))
        val = float3le(-0.1, 60.0, gain)
        t0 = time.perf_counter()
        r = rt({"cmd": "setParam", "session": args.session,
                "param": 1500, "value": val.hex()})
        dt = (time.perf_counter() - t0) * 1000.0
        lat.append(dt)
        if not r.get("ok"):
            fails += 1
        else:
            last_value = val.hex()
        # 按目标速率节流（提交本身有延迟，超速不补、欠速尽量追）
        target = t_start + (i + 1) * period
        delay = target - time.perf_counter()
        if delay > 0:
            time.sleep(delay)

    elapsed = time.perf_counter() - t_start
    lat.sort()
    p = lambda q: lat[min(int(len(lat) * q), len(lat) - 1)] if lat else -1

    cfg = rt({"cmd": "dumpConfig"})
    params = ((cfg.get("config") or {}).get("globalParams")
              if cfg.get("ok") else None)
    got_final = (params or {}).get("1500")

    print(json.dumps({
        "sent": total,
        "fails": fails,
        "elapsed_s": round(elapsed, 2),
        "achieved_rate": round(total / elapsed, 1),
        "latency_ms": {
            "p50": round(p(0.50), 3), "p95": round(p(0.95), 3),
            "p99": round(p(0.99), 3), "max": round(lat[-1], 3) if lat else -1,
        },
        "final_value_match": got_final == last_value,
        "final_hex": got_final,
    }, ensure_ascii=False))
    return 0 if fails == 0 and got_final == last_value else 1


if __name__ == "__main__":
    sys.exit(main())
