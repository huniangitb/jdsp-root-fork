# JamesDSP 后端派发架构 (v2) 计划书

状态：计划稿（本会话只出方案，不落代码）
基线：v6.7.2-fork（已回滚，引擎无旁路逻辑，框架 postprocess 自动挂载）
目标：由后端（常驻服务）主动派发并挂载效果，Binder 实时调节每个音频会话的参数，前端只做 UI。

---

## 1. 现状与问题

### 1.1 现在的链路

    App ──AudioEffect(自己 new 的实例)──> 参数只进这个实例
    框架 ──audio_effects.xml 的 <postprocess><apply>──> 每个 music 会话一个实例
    引擎 ──/data/vendor/audio/jamesdsp_state.bin──> 记录 + 新实例时重放

### 1.2 由此产生的四个硬问题

| 问题 | 原因 |
|---|---|
| 效果链在，配置不生效 | 配置由 App 推给 App 自己的实例；框架实例只能靠状态文件重放，长数据（卷积 IR 等）不可靠 |
| App 能控制全局启停 | App 持有自己实例，切歌/开关时释放或禁用；它在链上的权重与框架实例重叠 |
| 关掉前端就回到无配置 | 参数来源在 App，App 死了就没有新的下发；状态文件只是历史快照 |
| 无法按会话精确调参 | 框架实例没有客户端句柄，任何进程都无法 setParameter 到它 |

### 1.3 已经验证过的边界

- 框架 postprocess 实例可以被 SELinux 侧看到，但**不可被外部寻址**（无 handle）。
- 在 HAL 域内直接 bind `/data/vendor/audio/jdsp_ctl.sock` 被拒（EACCES），说明跨域调试通道必须走 SELinux 允许的 IPC（Binder / service_manager），而不是自建 socket。
- App 端 `EFFECT_CMD_DISABLE` 对引擎无影响（v6.7.2 引擎不解析），所以“App 能关”来自实例层，不是引擎层。

---

## 2. 目标架构

### 2.1 三层结构

    前端 App (只做 UI，不持有配置)
        │  AIDL (Binder)
        ▼
    后端服务 jdspd (常驻, root/init service)
        │  AIDL (Binder) → audioserver
        ▼
    audioserver / AudioFlinger  →  每个播放会话的 EffectChain
        │
        ▼
    引擎 libjamesdsp.so (被 jdspd 创建并持有 handle)

要点：

1. **谁创建、谁持有 handle，谁就能实时下发参数**。所以必须由后端（jdspd）调用 `IAudioFlinger::createEffect` 拿到 handle，而不是让框架 postprocess 创建。
2. 前端不再 `new AudioEffect`，也不写任何配置文件；所有配置通过 AIDL 交给 jdspd 持久化。
3. 会话维度的参数与挂载状态都由 jdspd 维护，天然支持“按应用排除”和“每会话不同配置”。

### 2.2 为什么不用现在的 postprocess 自动挂载

- postprocess 由 `AudioPolicyEffects::addOutputSessionEffects` 创建，**没有客户端 handle**，因此：
  - 无法 setParameter 实时调参（只能靠状态文件重放，等于异步、粗粒度）
  - 无法 per-session 差异化（同一份配置复制到所有会话）
  - 无法与 App 的实例解耦（两套实例并存，谁权重高看实现）

### 2.3 与“系统内置音效”对齐

系统效果（DAP/MiSound）由 audioserver 在会话创建时插入链，并通过 `IAudioFlinger` 的 effect handle 下发参数。我们要复刻的正是这条路径：jdspd 等价于“厂商音效服务”，只是效果库换成 libjamesdsp.so。

---

## 3. 组件设计

### 3.1 jdspd（后端服务）

| 模块 | 职责 |
|---|---|
| SessionWatcher | 监听 AudioFlinger 的播放会话变化（AudioPolicy/AudioFlinger binder），得到 sessionId ↔ 应用（uid/pkg）映射 |
| EffectBinder | 对每个会话调用 `createEffect` 挂上 jamesdsp，保存 handle；会话结束销毁 |
| ParamBus | 参数下发/读取，支持批量提交与速率限制；一次 set 立即生效 |
| ConfigStore | 自身持久化（JSON 或二进制），不再依赖 App 与 state.bin |
| PolicyEngine | 按 pkg/uid 决定是否挂载（排除列表）、每应用配置覆盖 |
| BinderService | 对前端/CLI 暴露 AIDL |

进程与权限：

    init service: /system/bin/jdspd (root, SELinux 域 jdspd)
    binder service name: jdsp.service
    需要的权限: 访问 audioserver、读取 /data/system/packages.list（映射 uid→pkg）

### 3.2 AIDL 草案

    interface IJdspService {
        // 会话
        List<JdspSession> listSessions();          // sessionId, uid, pkg, attached, enabled
        int attach(int sessionId);                  // 0=ok
        void detach(int sessionId);
        // 参数（实时）
        void setParam(int sessionId, int paramId, in byte[] value);
        byte[] getParam(int sessionId, int paramId);
        void commit(int sessionId);                 // 批量提交
        // 配置
        String dumpConfig();                        // 人类可读全量配置
        void loadPreset(in String path);
        void savePreset(in String path);
        // 策略
        void setAppPolicy(in String pkg, int mode); // 0=跟随全局 1=启用 2=排除
        // 诊断
        String stats();
    }

### 3.3 jdspcli（调试命令行）

    jdspcli sessions
    jdspcli get  <session> <param>
    jdspcli set  <session> <param> <hex>
    jdspcli dump <session>
    jdspcli policy add com.example.music exclude

作用：不依赖 App 就能验证后端是否“真的有配置”，也是本阶段排障的主要工具。

---

## 4. 分阶段实施计划

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0 | 本计划书归档；基线固定在 v6.7.2；清理 6.7.3~6.7.6 的实验产物（socket、诊断日志） | 仓库与设备都是干净基线 |
| P1 | jdspd 骨架 + AIDL + jdspcli；只读能力（列会话、列参数、stats） | 无 App 时可以查询到“当前配置内容” |
| P2 | 后端主动挂载：按会话 createEffect、销毁；与 postprocess 二选一开关 | 关掉 postprocess 也能出声且有效果 |
| P3 | 实时调参：setParam/getParam 直达 handle；参数持久化迁到 ConfigStore | 改一个 PEQ band < 20 ms 生效，无爆音 |
| P4 | 按应用策略：uid→pkg 映射 + 排除列表 | 排除的应用无效，其他正常 |
| P5 | 前端改造：去掉 SharedPreferences 依赖，全部走 AIDL；保留离线只读缓存 | 杀前端/卸载前端，音效与配置不变 |
| P6 | 多设备验证（Xiaomi / c16 / c17），打包发布 | 三台设备同一套行为 |

每阶段独立可回退：保留 `<postprocess>` 旧链路作为开关（config: mode=auto|backend）。

---

## 5. 关键风险与对策

| 风险 | 说明 | 对策 |
|---|---|---|
| createEffect 权限 | 普通进程无法对任意 session 建效果 | jdspd 以 root 运行，SELinux 域显式允许访问 audioserver；参考 RootlessJamesDSP root 模式的做法 |
| Android 版本差异 | AIDL audio HAL、EffectChain 接口在 16/17 有变化 | 用 versioned AIDL；c16/c17 分别验证 |
| 与杜比共存 | 多插入一条效果链可能影响 DAP/MiSound | 统一在同一个 chain 里追加，rank 与系统效果一致；提供仅查询模式排障 |
| 爆音/切换瞬态 | 挂载/卸载在播放中发生 | 只在会话创建/结束时挂载；参数变更走 buffer 边界提交 |
| 前端耦合 | App 代码量大，改造面广 | 先做“只读前端”（P1-P4 不动 App），P5 再改 App |
| 回退 | 新架构不稳定 | 保留 v6.7.2 完整包与 postprocess 模式，一条命令切换 |

---

## 6. 验收标准（可自动化）

1. 无 App（先 force-stop 再播放）：`jdspcli sessions` 能看到会话且 `attached=1`、`params=N>0`。
2. `jdspcli get <session> 115` 返回与最后一次设置一致的字节。
3. `jdspcli set` 后 20 ms 内 `get` 返回新值，且播放无 underrun、无爆音日志。
4. 排除列表内的应用播放时 `jdspcli sessions` 显示 `attached=0`，其他应用 `attached=1`。
5. 连续 72 h 播放/切歌/杀前端：无 HAL 崩溃、无 ANR、状态文件/配置不被破坏。

---

## 7. 本会话的收尾动作

- 不再改动音效链路（保持 v6.7.2 行为）。
- 把 6.7.3~6.7.6 的实验代码从模块里剔除（socket 服务、诊断日志、sepolicy 试验规则）。
- 计划书入仓：docs/PLAN-v2.md。
- 下一会话从 P1 开始：jdspd 骨架 + AIDL + jdspcli。
