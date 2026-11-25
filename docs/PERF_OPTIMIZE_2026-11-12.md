# 整体性能优化与逻辑排查（2026-11-12，6.9.6.2）

用户诉求：设置界面刚打开会卡一下；整体减少内存占用与耗电；不影响任何功能；顺带查逻辑 bug。

## 一、设置页首开卡顿的根因

模块 UI 是**微信进程内**的 ComposeView（非独立进程）。首次打开时要在微信进程
主线程上完成几百个 Compose / miuix / foundation 类的「加载 + 校验」，
微信进程类池巨大，此过程 300ms+ 可感知。这是 Xposed 模块内嵌 Compose UI 的通病。

**修复**：新增 `ui/miuix/SettingsUiPrewarm.kt`。模块安装完成后（仍在后台线程）
以 THREAD_PRIORITY_LOWEST 的 daemon 线程 `Class.forName(name, false, cl)` 预加载
约 50 个最热类。要点：

- `initialize=false`：只加载链接、不触发静态初始化器，零副作用；
- 单个类名失败静默跳过（依赖版本变化不炸）；
- 接入点在 `ModuleEntry.java` 的 `installAll` 之后（6.5 步），Java 侧调 Kotlin object
  必须写 `SettingsUiPrewarm.INSTANCE.prewarm(...)`，直接 `.prewarm` 会报
  「无法从静态上下文中引用非静态方法」。

顺手：`HchatMiuixTheme` 的 `darkColorScheme()/lightColorScheme()` 原来每次重组都重建，
包进 `remember`。

## 二、热路径与内存（探查代理全量扫描 13 项，落地 10 项）

| # | 文件 | 问题 | 修复 |
|---|---|---|---|
| P0 | customfriendavatar/CustomFriendAvatarFeature.kt | 圆角缓存存 WeakReference，GC 一回收就打空，draw() 每帧每头像重新 createBitmap+Shader（主线程分配风暴） | 改 LruCache 强引用，上限 16MB，键=（源 Bitmap, 圆角系数）——**系数必须参与键**，否则原生/圆角切换拿错缓存 |
| P1 | customfriendavatar/…Settings.kt | draw hook 每帧每头像读 FastKV 4 次 | 新增 AvatarScopeCache（读缓存+监听失效），scopeEnabled 改走缓存 |
| P1 | roundavatar/RoundAvatarSettings.kt | enabled/radiusFactor 同样在 draw 热路径 | 同模式加缓存 |
| P1 | quickread/QuickMarkReadRuntime.kt | dispatchTouchEvent 每个 MOVE 都查 FastKV | isDragEnabled 加读缓存+监听失效 |
| P1 | quickread/QuickMarkReadFeature.kt | 每次 ACTION_DOWN 全树 DFS 找底栏 | DOWN 先做屏幕底部 34% 区域预判，上方直接短路；collectDispatchMethods 只挂继承链最派生实现（原来一层触摸进 N 个回调） |
| P1 | keepalive/WeChatKeepAliveRuntime.kt | 心跳失败路径不 disconnect（socket 泄漏）+ 每 60s new 线程 | 复用 watchdogExecutor + finally disconnect |
| P1 | keepalive/WeChatKeepAliveRuntime.kt | PARTIAL_WAKE_LOCK 无限期 acquire | acquire(12h) 兜底超时 |
| P1 | keepalive/WeChatKeepAliveService.kt | 息屏仍每 60s 唤醒 | 按 isInteractive 自适应：亮屏 60s / 息屏 5min |
| P2 | conversationgroup/ConversationGroupHomeProjection.kt | 反射字段缓存 512 就 clear()，首页绑定周期性反射风暴 | 去清空；键集由微信版本决定实际只有几十个，只设 2048 不增长上限 |
| P2 | autoreply/AutoReplyRuntime.kt | WebSocket 竞争时无上限自旋 new session | yield + 4 次失败复用现有连接 |
| P2 | script/ScriptMessageHook.kt | ①媒体补查 2 线程、每条忙等最多 15s，连发即丢事件 ②dedupKeys 每条消息驻留最多 3 份全文（上限 4096 条）③已稳定消息也查库 | ①线程 2→4 ②body:/chat: 键改「长度+哈希」摘要，raw: 保留全文精确去重 ③查库前先判稳定直接返回 |

**跳过**：ConversationTabsRuntime `evictAll`（禁用时清位图缓存本来就是省内存的正确行为，重新启用后重解码代价可忽略）。

## 三、已排查确认无问题的项

MainThreadStallWatchdog、WeChatKeepAliveService START_STICKY 回读、ScriptSendButtonHook
触摸 gate、CustomBottomBarFeature dispatchDraw gate、HchatLogViewerPage 轮询、
FloatingBottomBar remember、WeChatMessageStoreApi Cursor finally、DexInstallScheduler 退避——
均已是修复后形态。

## 四、验证

- `:app:compileDebugKotlin` `:app:compileDebugJavaWithJavac` 通过
- `:app:testDebugUnitTest` **59 用例全过**（failures=0 errors=0）
- 版本 `6.9.6.2 / 6090602`（四段规则，同轮修补只加最后一位），签名 CN=Hchat 不变
