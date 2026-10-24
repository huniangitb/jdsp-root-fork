# 引擎改动（native）

上游：`evoeram/JamesDSPManager` 分支 `extensions`（本模块的 fork 引擎源码）。
本目录只放**补丁 + 构建脚本 + 主机单测**，不放整棵上游源码。

## 补丁内容

`patches/0001-state-persistence-and-hot-reload.patch`（相对 `src-baseline`，即仅做了 NDK30 编译兼容修复的上游源码）：

1. **状态持久化** `jdsp_state.c/h`
   - 记录每个成功的 `SET_PARAM` / `ENABLE` / `DISABLE`（带 effect_param_t 头，重放时原样喂回同一处理函数）
   - 原子落盘（tmp + fsync + rename），CRC32 校验，损坏文件静默丢弃
   - 文件格式：`"JDSP" + u32 version + u32 flags + u32 count + {u32 cmd,u32 size,bytes}* + u32 crc32`
2. **inotify 热加载**（单 fd `poll()`，不用 epoll）
   - 监听状态文件所在目录，`IN_CLOSE_WRITE|IN_MOVED_TO|IN_CREATE`
   - 事件到达时在 watcher 线程读取解析，渲染线程不碰文件系统
   - 兜底：inotify 不可用时回到 stat 轮询；`JDSP_STATE_NO_INOTIFY=1` 可强制轮询
3. **后台重建 + 原子切换**（`jamesdsp.c`）
   - builder 线程每 20 ms 检查代次（含 20 ms 突发去抖）
   - 用新参数构造**完整新引擎**，全部应用后发布到 `pending`
   - 渲染线程在回调开头做一次指针交换；切换 buffer 用旧/新引擎输出交叉淡化
4. **去重修复**：`state_dedupe()` 只删除命中的那一条记录（原来会删掉它之前的全部记录）

## 应用补丁

```sh
# 1) 取上游源码
git clone -b extensions https://github.com/evoeram/JamesDSPManager.git jdsp-src
cd jdsp-src

# 2) 应用（补丁里的路径是 src-baseline/ 与 src/）
mkdir -p ../work && cp -a . ../work/src-baseline
cd ../work && cp -a src-baseline src
patch -p1 -d src < <repo>/native/patches/0001-state-persistence-and-hot-reload.patch
```

## 构建

```sh
NDK=/path/to/ndk/30.x ./build.sh      # 产出 out/libjamesdsp-arm64-v8a.so / -armv7a.so
```

## 主机单测

```sh
./test/run_tests.sh
# test_state      状态文件格式 / 重放 / 损坏 / 驱逐 / 代次
# test_watch      inotify 生效、外部覆盖被感知、自身写盘不自激、NO_INOTIFY 回退
```
