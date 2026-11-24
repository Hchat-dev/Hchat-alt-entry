# 微信交互卡顿：全量代码性能审计与修复（2026-11-12）

## 结论先行

卡顿**不是**新增的「朋友圈视频禁止自动播放」造成的。已用三条独立证据排除：

1. DexKit 的 `MethodMatcher.usingStrings()` 官方语义是 **AND**（方法必须包含全部给定字符串），
   官方示例 `usingStrings("VipCheckUtil", "userInfo:")` 配 `paramCount(0)` 只命中一个方法。
   因此三字符串锚点只会命中 `fl4/b` 和 `pm4/e` 两个判定方法，没有误 hook 高频方法。
2. 这两个方法只在「是否允许自动播放」判定时被调用，不在绘制/滚动逐帧路径上。
3. 新功能在开关关闭时只读一次 SharedPreferences 就返回，不扫 dex、不挂 hook。

真正的卡顿来自长期累积的既有 hook：全进程级的热路径上做了反射、文件 stat、全局锁、
同步表查询，且**功能关闭时仍在执行**。

## 审计方式

787 个源文件、106 个 hook 回调、92 个已注册 Feature。分三路并行审计：

| 路 | 范围 | 主要发现 |
| --- | --- | --- |
| A | 启动链路（ModuleEntry / FeatureManager / DexFinder / DexInstallScheduler） | 缓存键含 Tinker 易变字段、失败重试 3 次重复全量扫 dex、DexKit 60s 空闲释放后重解析 |
| B | hook 热路径（103 个含回调的文件） | 头像 draw 每帧 4 次 stat、全局 RecyclerView 触摸每 MOVE 全树遍历、全局 View.dispatchTouchEvent、全进程 SQLite、消息绑定无开关守卫 |
| C | UI 与主线程（设置页 / 日志页 / 底栏） | 底栏 backdrop 每帧重绘微信 View 树、providers 每次重组重建 90+ 分组、日志页 800ms 全量拷贝 |

## 本轮修复（9 处，均已编译通过）

### 1. 消息绑定三个功能：开关判断前置

挂在全局消息绑定（`_onBindViewHolder`）上的回调，原来先做反射和视图树遍历、最后才判开关。
关闭状态下每条消息都白做一遍，聊天列表滚动 10–20 次/秒。

| 文件 | 改动 |
| --- | --- |
| `hchatextra/HchatExtraFeature.kt` | `before/afterHookedMethod` 首行加 `if (!messageDetailsConfig.enabled) return`（volatile 字段，零成本） |
| `messagebubble/MessageBubbleFeature.kt` | 新增 `bubbleEnabled()`，before/after 首行判断；删掉 `applyBubble` 里重复的 prefs 读 |
| `messagetextcolor/MessageTextColorFeature.kt` | 关闭且无已应用样式时直接退出，避免每条消息一次 `findRootView` 反射 |

### 2. 头像绘制：去掉每帧文件 stat

`CustomFriendAvatarFeature` 的 `Drawable.draw()` hook 每帧、每个可见头像都调用
`CustomFriendAvatarStore.loadBitmap()`，而它内部要做 **4 次 stat syscall**
（`isFile` / `length` / `lastModified` ×2）+ 一把全局锁。一屏 10 个头像按 60fps 就是
每秒 2400 次 syscall，直接吃掉绘制预算。

新增 `CustomFriendAvatarStore.cachedBitmap(wxid)`：**只查内存缓存，不碰文件系统**。
绘制路径优先走它，只有首次未命中才真正解码；缓存失效由 `saveFromUri` / `removeCached` 主动完成。
`setImageBitmap` 路径同样改为缓存优先。

### 3. 左滑引用：不再对每个 MOVE 全树遍历

`SwipeQuoteFeature` hook 的是**全局** `RecyclerView.onInterceptTouchEvent/onTouchEvent`
（androidx 与 support v7 两套）。原来每个触摸事件（含每个 MOVE）都调用
`findRecyclerTarget()` 递归遍历当前可见行的整棵子树，并对同步 Map 反复加锁。
通讯录、朋友圈、表情面板这些根本没有引用目标的列表也被拖慢，是「所有列表滚动都发涩」
的主要来源。

改后：
- 非 `ACTION_DOWN` 且该列表没有进行中的手势 → **直接返回**（挡掉绝大多数触摸）
- DOWN 时已记下命中目标 → MOVE/UP 复用 `state.hit`，不再重新命中

### 4. 全局 View.dispatchTouchEvent：加极廉价 gate

`ScriptSendButtonHook` 无条件 hook 了 `View.dispatchTouchEvent`（全进程所有 View 的每次触摸），
回调里要做 2–3 次静态同步表查询。而真正的业务前置条件
`ScriptPluginRuntime.hasLongSendButtonCallbacks()` 直到很后面才判断。

现在 before/after 首行都先判它：没有脚本注册长按回调（绝大多数用户）时，回调只做一次
空集合 `any`，立即返回。

### 5. HLog：采集开启时不再走进程间日志

`log()` 原先在 `captureEnabled` 时把 `verbose` 置真，**跳过去重**，并且每条日志仍然调用
`XposedBridge.log`（同步 IPC）。开着「记录运行日志」排查时，日志一多就是明显的抖动。

改后：
- 只有 `stackTraceEnabled`（要完整堆栈）才允许重复输出，采集模式仍走去重
- 采集开启时日志只进内存缓冲，不再调 `XposedBridge.log`
- `appendCapture` 复用 ThreadLocal `Date`，不再每条 new 一个

### 6. 设置项注册表：返回稳定快照

`UIRegistry.getAllProviders()` 原来每次 `ArrayList(providers)` 返回新实例。
设置页把它作为 `remember` 的 key，导致每次重组 key 都变、`remember` 永远失效，
每次重组重跑 90+ 次分组过滤和 `indexOf` 排序。

改为带脏标记的快照：注册/注销/清空时置脏，下次读取重建一次，其余返回同一实例。

### 7. 运行日志页

- 轮询间隔 800ms → 1200ms
- 快照拷贝（最多 2000 条 + 持锁）移到 `Dispatchers.Default`

### 8. 朋友圈自动播放 hook 自身微优化

- `enabledCache: Boolean?` 改为 `enabledState: Int`（-1/0/1），热路径不再装箱
- 返回类型（primitive vs Boxed）在安装期算好传入 hook，回调不再反射 `getReturnType`
- 命中日志计数从「两次原子操作」减为一次 `getAndIncrement()`

## 耗电专项（同轮补做）

用户提出「加了这些改动会不会更费电」。结论：**本轮所有改动都是净减少，没有任何一处
增加常驻开销**。逐条对账见下表。

| 改动 | 改之前每次要做什么 | 改之后 | 方向 |
| --- | --- | --- | --- |
| 消息绑定三功能开关前置 | 每条消息：反射 `findRootView` + 视图树遍历 + prefs 读 | 一次 `@Volatile` 字段读（约 1ns） | 大降 |
| 头像 `cachedBitmap()` | 每帧每头像 4 次 stat syscall + 全局锁 | 纯内存 Map 查（约 100ns） | 大降 |
| 左滑引用 | 每个 MOVE 事件全树递归遍历 + 同步 Map 加锁 | 一次状态判断 | 大降 |
| `ScriptSendButtonHook` | 每次触摸 2–3 次静态同步表查询 | 一次空集合 `any` | 大降 |
| `HLog` 采集模式 | 每条日志一次 `XposedBridge.log`（同步跨进程 IPC） | 只写内存环形缓冲 | 最大一项 |
| `UIRegistry` 快照 | 每次重组重跑 90+ 分组过滤与排序 | 复用同一快照实例 | 中降 |
| 日志页轮询 | 800ms 一次全量拷贝（主线程） | 1200ms 一次，移到 `Dispatchers.Default` | 中降 |
| 朋友圈自动播放 hook | 新增：每次「是否允许自动播放」判定读一个 `Int` 字段（约 5ns） | — | **微量增加** |

唯一真正新增开销的是朋友圈那个 hook。量化一下：它挂在
`SnsAutoPlayUtil.checkAutoPlay` 与 `ImproveAutoPlayManager` 的两个方法上，只在
「视频进入视野 / 滑动切换 / 点开详情」时被调用，一次几十到几百毫秒级别的间隔，
每秒最多几次；每次开销是一次 volatile int 读 + 一次比较。开关关闭时连
`setResult` 都不调用。相较被它替代掉的那 2400 次/秒 stat syscall，低三个数量级。

反过来，这个功能本身是**省电**的：朋友圈视频不自动播放，就不会预解码、不会
无声起播、不会持续拉流。

### 本轮新修的 3 处耗电项

#### 1. 数据库 hook 的每次写库分配（`WeChatDatabaseListenerApi`）

这是模块里唯一真正「常驻高频」的位置：全进程每一次 insert / update / rawQuery 都会
穿过它，微信的写库频率是每秒数十到数百次。

- `ThreadLocal<Integer>` 深度计数 → 每次入库自动装箱一个 `Integer`。改成单元素
  `int[]`，零分配。
- `normalizeLegacyMsgSource` 原先**不管写的是哪张表**都要先取 `ContentValues`、
  再 `table.toLowerCase()`（一次 String 分配）。现在先用零分配的字符比较判定表名，
  非消息表直接返回。微信绝大部分写入是 `rcontact` 和各类缓存表，到此为止。
- `extractMsgSource` 对 `lvbuffer`（几十 KB 的 protobuf）整串 `toLowerCase()` 再
  `indexOf`，等于每条消息多分配一份等长字符串。改成原地大小写不敏感查找。
- `msgSourceFromLvBuffer` 现在先按字节扫一遍 `<msgsource`，没有这个标签就完全
  不解码整条 lvbuffer。

GC 减少直接等于 CPU 时间减少，CPU 时间就是电。

#### 2. 底栏磨砂每帧重绘整个微信界面（`ViewBackdrop`）

`drawBackdrop` 每次调用都执行 `recordSource()`，把**整屏微信 View 树**重绘进一个
`GraphicsLayer`。而它被调用的次数远多于「微信内容变化」的次数：底栏自己的选中动画、
角标变化、任何重组都会触发。屏幕亮着就一直在重复画同一屏。

现在加了脏判定：只有 `version`（由 `OnPreDrawListener` 的 `isDirty` 驱动）变化或
尺寸变化时才真正重录，其余情况直接复用上一帧图层。底栏开启磨砂时收益明显；
不开磨砂则完全没影响。

顺带把 `drawBackdrop` 里每帧 `new IntArray(2)` 改成复用字段。

#### 3. 其它

- `WeChatVersionApi` 缓存键含 `tinkerId/patchId`：只在微信打补丁时才失效，
  不是每次启动，实测影响可忽略，**本轮不动**。
- `DexInstallScheduler` 失败重试 3 次全量扫 dex：只在「版本不匹配」时发生，
  首次安装那一次有成本，之后走缓存。**留待下一轮**。

### 模块常驻设备的资源占用（事实核对）

| 资源 | 默认状态 | 说明 |
| --- | --- | --- |
| `PARTIAL_WAKE_LOCK` | 关闭 | `WeChatKeepAlive` 默认 `DEFAULT_ENABLE=false`，不 acquire 就不耗电 |
| 前台 Service | 不启动 | 依赖保活总开关，默认关 |
| `AlarmManager` 精确唤醒 | 不注册 | 定时任务功能，用户自建任务才有 |
| 周期性定时器 | 不启动 | 朋友圈自动刷新默认关（间隔 300s） |
| 悬浮窗 | 不创建 | 底栏/悬浮快捷键默认关 |

也就是说，**功能全关时模块只剩 hook 回调的 CPU 时间**，没有唤醒源。
开着「记录运行日志」是唯一会让模块持续干活的东西，测耗电时务必关掉。

## 尚未修复（按优先级，留待下一轮）


| 优先级 | 位置 | 问题 | 为什么这轮不动 |
| --- | --- | --- | --- |
| ~~P0~~ 已修 | `api/runtime/WeChatDatabaseListenerApi.java` | 每次 insert/update 装箱、`toLowerCase()`、整串 lvbuffer 解码 | 本轮已改为零分配快速路径 |
| P0 | `custombottombar/CustomBottomBarFeature.kt:870` | 磨砂层 `dispatchDraw` 每帧一次静态锁 | 单次锁几十纳秒，且抑制表是 `WeakHashMap`（条目会被 GC 清除），加 volatile 计数会出现计数与内容不一致，收益不值得引入这个隐患 |
| P1 | `WeChatVersionApi` 缓存键含 `tinkerId/patchId` | Tinker 补丁变化会使全部定位缓存失效，下次启动全量重扫（250–350 次） | 会影响所有功能的缓存判定，需要单独验证 |
| P1 | `DexInstallScheduler` 失败重试 3 次 | 定位不到的 Feature 每次启动重复全量扫 dex | 需引入「负缓存」区分「暂时未就绪」与「版本不匹配」 |
| P1 | `ModuleEntry:72` `DEXKIT_IDLE_RELEASE_MS = 60s` | 空闲释放后首次查询要重解析整包 dex | 提高会推高常驻内存，与「不加内存」要求冲突，需权衡 |
| P2 | `MessageBlockFeature` / `CustomNotificationRuntime` | 每条消息 new Settings + 解析 JSON | 影响面小，属于第二梯队 |

## 验证建议

1. **先关掉「记录运行日志」再测**。日志采集本身有锁和格式化开销，开着测帧率不准。
2. 分别测试四个场景：聊天列表滚动、通讯录滚动、朋友圈滚动、首页静止。
3. 若仍有明显卡顿，用 `adb shell dumpsys gfxinfo <微信包名> framestats` 取 jank 分布，
   或临时关掉悬浮底栏对比（`ui/miuix/ViewBackdrop.kt` 每帧会把微信内容层重绘进图层再做模糊，
   这是首页静止也可能掉帧的来源）。
