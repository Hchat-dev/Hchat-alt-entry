# ANR 分诊与主线程卡顿采样（Hchat 6.9.6）

对应财神爷提供的 ANR 报告：时间 2026-11-12 16:42:38，模块版本 **6.9.3 (60903)**，
微信 8.0.80 (3220)，黑鲨 SHARK KTUS-A0 / Android 12。

## 一、这份日志到底说了什么

先把「能读到的」和「读不到的」分开，否则很容易修错地方。

### 事实（日志原文可核）

| 项 | 值 | 说明 |
| --- | --- | --- |
| ANR 记录归属进程 | `pid=17282` | 是**上一个**微信进程 |
| 当前进程 | `pid=18531` | 报告是它启动后补写的 ApplicationExitInfo 历史 |
| 退出原因 | `ANR (6)` | `REASON_ANR` |
| 说明字段 | `user request after error` | 不是系统直接杀，是 ANR 弹窗后用户点了「关闭应用」 |
| 内存 | PSS 615433 KB / RSS 1185116 KB | 1.18 GB，明显偏大 |
| 主线程等待通道 | `do_wait` | `wait4` 系列系统调用：**等子进程结束** |
| 其余线程 | `futex_wait_queue_me` ×N、`do_sigtimedwait` ×1 | 常规空闲态 |
| 系统 trace 内容 | 只有 `Waiting Channels` 段 | **没有 Java 堆栈** |

### 读不到的

Android 11 起，`ApplicationExitInfo.getTraceInputStream()` 对 ANR 只保留
「逐线程等待通道」摘要，完整的 `/data/anr/traces.txt` 不对外提供（要 root）。
所以这份报告能定「主线程卡在等子进程」，定不到「卡在哪一行代码」。

这也是本轮第一个改动的理由：**不要再指望系统给栈，进程活着的时候自己抓。**

## 二、主线程 `do_wait` 意味着什么

`do_wait` 是内核里 `wait4/waitid` 的落点，用户态只可能来自这几处：

1. `Runtime.exec` / `ProcessBuilder` 之后调 `waitFor()`（最常见）；
2. `os.waitpid` / `waitid`（native 代码）;
3. 宿主自己 fork 子进程后回收。

对应到 Hchat 侧的实际审计结果：

| 位置 | 是否在微信进程内 | 是否可能在主线程 |
| --- | --- | --- |
| `WeChatKeepAliveRuntime.runRootCommand()`（`su -c`） | 否（模块进程的 Service） | 否（曾跑在 Service 主线程，本轮已改后台线程） |
| `WeChatKeepAliveRuntime.isWeChatProcessAlive()`（`pidof`） | 否（同上） | 否（同上） |
| `ScriptPluginAgentWorkspaceTools` / `ProotEnvironment`（脚本插件、Agent 的 shell） | **是** | 有超时，跑在调用它的线程；**不能排除**脚本在 UI 回调里同步执行 |
| 模块其余代码 | — | 已 grep 全量：无 `exec` 调用 |

结论分两层（推断，非日志实证）：

- **直接触发**：无法从这份 trace 定案。主线程在 `do_wait` 可能来自微信自身或第三方 SDK
  起的子进程，也可能是脚本插件在 UI 线程里同步跑 shell。
- **放大因素**：RSS 1.18 GB。`fork()` 要复制整份页表，进程越大这一步越慢；
  同样一条 `pidof` 在 50 MB 进程里几十毫秒，在 1 GB 进程里可以放大到几百毫秒。
  一次不够成 ANR，叠加在 CPU 被打满的时候就够了。

### 和 6.9.3 已知问题的相关性（推断，证据②）

6.9.3 那份日志里同时有：

```
[WechatApi] 消息变更监听未就绪: ... wrapperHooks=0
[Hchat:DexInstall] xxx 多次安装失败，停止本轮重试
```

即 DexFinder 坏缓存 → 一批功能反复安装失败 → 每次都触发 DexKit 全 dex 扫描。
DexKit 会把 dex **映射进进程地址空间**，这正是 RSS 上到 1.18 GB 的合理来源。
CPU 被扫描吃满 + 内存暴涨，主线程任何一次外部命令都会从几十毫秒劣化到秒级。

6.9.5 已经把坏缓存这条根因修掉（`CACHE_SNAPSHOT_VERSION 2→3` + 判空重扫）。
也就是说：**这份 6.9.3 的 ANR，很可能是那条链路的下游症状，而根因已经修了。**

## 三、本轮改动（6.9.6 / 60906）

### 1. 新增主线程卡顿在线采样器（核心）

`app/src/main/java/h/Hchat/crash/MainThreadStallWatchdog.kt`

```
每 10 秒：主线程 post 一个空消息
   ├─ 3 秒内被执行  → 正常，继续
   └─ 3 秒内没执行  → 主线程被占用 ≥3 秒
        → 独立线程抓主线程 Java 堆栈 + 最近 20 条 Hchat 日志
        → 落盘 crash/main_thread_stall.log（封顶 256 KB）
        → 同时写进内存日志，导出「运行日志」立即可见
```

设计要点：

| 决策 | 原因 |
| --- | --- |
| 抓栈放独立线程 + `join(4s)` | `Thread.getStackTrace()` 要求目标线程到达安全点，卡死时可能不返回，不能让看门狗自己跟着卡死 |
| 卡顿后轮询等恢复再进下一轮 | 同一次卡顿不会刷出几十条重复证据 |
| 不持 WakeLock、不用 AlarmManager | 正常状态只是两次 sleep，对续航的影响在测量噪声以下 |
| 文件封顶 + 10 分钟窗口 | 旧卡顿不许拿来解释新 ANR |

配套：`CrashReportRuntime.collectPreviousCrash()` 在生成 ANR 段时，
把 10 分钟内的卡顿证据挂在报告后面（段名「Hchat 主线程卡顿采样」），并入档后删除。

### 2. ANR 报告加「主线程状态解读」

`CrashExitInfoApi30` 现在会把 `Waiting Channels` 翻译成人话：

```
线程总数: 118（futex 等待 112，binder 等待 3）
主线程(tid=17282) 等待通道: do_wait
判定: 主线程在 waitpid 等子进程结束。最常见来源是主线程执行外部命令后 waitFor()
      （Runtime.exec / ProcessBuilder），其次才是宿主自己 fork 的子进程。
      排查方向：谁在主线程上跑 shell / su。
内存: RSS 1157 MB 偏大。进程越大，主线程一次 fork/exec 复制页表的代价越高……
```

七种典型通道各有判定：`do_wait` / `futex` / `binder` / `epoll` / `pipe` / 同步 IO / 信号。
以后日志自己就能看出「该查锁、该查 binder、还是该查 exec」，不用再猜。

### 3. 保活看门狗不再占主线程

`WeChatKeepAliveRuntime`：

| 改动 | 前 | 后 |
| --- | --- | --- |
| `watchdogTick()` | 在调用方线程直接跑（Service 主线程） | 投递到专用后台单线程 |
| `isWeChatProcessAlive()` | `sh -c pidof`（两次 fork） | 直接 `pidof`（一次 fork），3 秒超时；查不出来按「活着」处理，避免把微信反复顶到前台 |
| `runRootCommand()` | `waitFor()` 无限等 | 8 秒超时 + `destroyForcibly()`（`su` 会等 Magisk 授权弹窗） |

### 4. 首页分页投影少算一半反射

`ConversationGroupHomeProjection` 的分页结果投影跑在微信首页绑定路径上。
原来每行会话名要算**两次**（过滤一次、日志样本再算一次，第二次还多走一遍 `convertTo` 兜底）。
改成逐行只算一次、样本顺手攒进 `StringBuilder`。

## 四、下次怎么看日志

### 验证卡顿采样器装上了

启动微信后搜 `[Hchat:StallWatchdog]`：正常状态**没有**输出（这本身是好事）。
只有真卡顿才会出现：

```
[Hchat:StallWatchdog] 主线程卡顿 4213ms，栈顶: at com.tencent.mm.ui.LauncherUI.onCreate(...)
[Hchat:StallWatchdog] 主线程堆栈:
    at ...
```

### 验证 ANR 报告变完整

覆盖安装后如果又出 ANR，报告里应该多两段：

1. `--- 主线程状态解读 ---`（系统通道翻译）
2. `--- Hchat 主线程卡顿采样（ANR 前在线抓取，含 Java 堆栈）---`（我们自己抓的栈）

**第 2 段存在 = 有 Java 堆栈 = 这次能定位到具体代码。** 如果只有第 1 段没有第 2 段，
说明卡顿短于 3 秒或采样线程没能拿到栈，把报告发我，我按第 1 段的通道继续缩范围。

### 判断是不是 Hchat 造成的

看第 2 段的栈里有没有 `h.Hchat` 帧：

- 有 → 直接把该帧发我，定位到具体 hook；
- 没有，栈顶在 `com.tencent.mm.*` 或 native → 说明主线程在微信自己的代码里，
  再看是不是紧跟着 Hchat 的 hook（栈里会有 `XC_MethodHook` 帧）。

## 五、遗留

- **通话铃声拦截**：8.0.80 三个锚点全 dex 不存在（微信重构成 `com.tencent.pigeon.voipmp`），
  属于独立一轮工作量，本轮不动。
- **保活服务常住进程**：Service 在模块进程，不占微信内存，但会常驻一个前台通知。
  如果财神爷不需要强保活，关掉它就是最直接的省电手段。
