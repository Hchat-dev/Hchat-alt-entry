# 线上日志分析（Vector-logs-release-20261112-131638）

抓取时间：2026-11-12 13:16　设备：blackshark KTUS-A0 / Android 12 / MIUI V11.0.4.0
覆盖范围：11-12 11:25（系统重启后）～ 13:16，以及历史 tombstone（10-28 起）

## 一、结论先行

| 现象 | 归属 | 证据 | 本轮处理 |
| --- | --- | --- | --- |
| 微信进程崩溃 18 次 | **微 X 模块**（`com.fkzhang.wechatxposedhooks`） | tombstone abort message 指名 `WxJniCoreBridge.CallMethod` 传 null 给 JNI | 非本模块；该模块当前已卸载 |
| 主线程 ANR 8 秒 | **Hchat** | ANR 主线程栈里有 `h.Hchat.utils.KavaReflector.declaredMethods` | 已修（3 处） |
| 「微信偶尔不能联网」 | **微信自身进程死亡 / 账号过期** | `AccountExpiredUI` + `Process com.tencent.mm has died: fg TOP` | 本模块无可拦网点位，见第四节 |
| `WeChatKeepAliveService ... not found` ×15 | **Hchat** | 组件被解析成 `com.tencent.mm/h.Hchat...` | 已修 |
| `视频号详情请求候选数量异常 count=0` | Hchat（DexKit 未适配 8.0.80） | 构造器签名不匹配 | 未修（需要单独一轮反编译适配） |

## 二、崩溃：18 次 JNI abort

```
signal 6 (SIGABRT)
Abort message: 'JNI DETECTED ERROR IN APPLICATION: obj == null
    in call to CallObjectMethodV
    from java.lang.Object com.fkzhang.wechatxposedhooks.WxJniCoreBridge.CallMethod(int, java.lang.Object[])'
Cmdline: com.tencent.mm
```

- 集中在 **2026-11-09 19:46–21:59**，一分钟内可连崩 4 次；10-28 另有 2 次。
- `com.fkzhang.wechatxposedhooks` 是**微 X 模块**的包名，不是 `h.Hchat`。
- 本仓库全量检索无 `WxJniCoreBridge` / 无任何 JNI 方法调用桥（只有 DexKit、silk codec、crash guard 三个 so）。
- 本次抓包的模块清单里已没有该模块（`modules/` 下只有 WorkSettingPro、blackfly、bqry、zygisk_vector），
  `modules_config.db` 里也搜不到 `fkzhang`。

结论：这批崩溃与 Hchat 无关，且已随该模块卸载而消失。

## 三、ANR：Hchat 反射把主线程顶了 8 秒

```
Subject: Input dispatching timed out (...LauncherUI ... Waited 8001ms for MotionEvent(action=DOWN))

at java.lang.reflect.Executable.getParameterTypesInternal(Native method)
at java.lang.reflect.Method.getParameterTypes(Method.java:186)
at java.lang.Class.getDeclaredMethods(Class.java:1915)
at h.Hchat.utils.KavaReflector.declaredMethods(...)
at lt4.i(...) / lt4.h(...)                 ← 混淆后的业务类
at ym.afterHookedMethod(...)
at XposedBridge$LegacyApiSupport.handleAfter
at Vector_.onAttachedToWindow(Dobby:-4)    ← 被 hook 的是 ChatFooter.onAttachedToWindow
at View.dispatchAttachedToWindow
...
at com.tencent.mm.ui.chatting.ChattingUIProxy.onInit
```

调用链还原：`ChatFooter.onAttachedToWindow` 的 after 回调 →
`InputHintRuntime.bindFooter` → `ScriptSendButtonHook.findInputView` →
遍历 `ChatFooter` 全部字段 → 每个字段类型调 `inputScore` →
`findNoArgMethod` 沿继承链 → `KavaReflector.declaredMethods`。

两处放大器：

1. **`declaredMethods` 完全没有缓存**。每次调用都走
   `clazz.resolve().optional().method { }` 重新遍历方法表、构造 `Method` 对象，
   再逐个 `setAccessible(true)`（每个都是一次 native 调用）。
   `ChatFooter` 有上百个方法，叠加字段数和继承链，一发就是几百次 native 调用。
2. **"没找到"这个结论存不住**。`inputFieldCache` 是 `Map<Class, Field?>`，
   写 null 读出来还是 null，与"没缓存"无法区分 → 每次 attach 都重扫一遍。
   `findNoArgMethod` / `findOneArgMethod` 同样只缓存命中结果。

而且这个 hook 挂在 **view attach 期间**，正是输入分发最敏感的时刻，8 秒超时是必然结果。

## 四、「不能联网」的证据链

### 直接证据：微信在前台死亡

```
11-12 11:35:36.195 I ActivityTaskManager: START u0 {cmp=com.tencent.mm/.ui.AccountExpiredUI} from uid 10909
11-12 11:35:36.355 I wm_on_create_called: [0,com.tencent.mm.ui.AccountExpiredUI,performCreate,53]
11-12 11:35:37.112 I ActivityManager: Process com.tencent.mm (pid 13820) has died: fg  TOP
11-12 11:35:37.161 I ActivityManager: Start proc 26515:com.tencent.mm/u0a909 for restart
```

`AccountExpiredUI` 是微信的「账号已过期，请重新登录」页面。它弹出来之后主进程直接死掉，
再被系统拉起 —— 这段时间收发消息全部失败，用户看到的就是"连不上网"。

### 次要证据：双开实例

日志里同时存在两个 uid 的微信进程：`10909` 与 `10912`
（`am_proc_start: [0,23552,10909,com.tencent.mm:hotpot..]` / `[0,24185,10912,...]`）。
说明手机上有应用分身/双开。双开环境叠加 hook 框架，微信风控会明显更敏感。

### 干扰项：SELinux 拒绝 484 次

```
avc: denied { read } for comm="mars::26185" name="route" ... app=com.tencent.mm
avc: denied ... name="u:object_r:serialno_prop:s0" ... app=com.tencent.mm
avc: denied ... name="u:object_r:qemu_hw_prop:s0" ... app=com.tencent.mm
```

`route` 被拒 91 次、`serialno_prop` 61 次、`qemu_hw_prop` 25 次。后两类是微信的
**设备指纹 / 模拟器检测**采集，被拒本身不致命，但会让微信判定运行环境"不干净"，
这与「账号过期」是同一条链路上的两种表现。

### 本模块排除

- `WeChatNetworkDispatcher.hookNetworkQueue` 的 `beforeHookedMethod` **只缓存 dispatcher
  实例，从不 `setResult`**，不干涉微信自己的发包结果。
- 全模块没有 hook `okhttp` / `HttpURLConnection` / `InetAddress` / `Socket` / `mars`。
- `full.log` 里 `Unable to resolve host` / `SocketTimeoutException` / `UnknownHostException`
  / `ECONNREFUSED` 命中次数全部为 **0**。也就是说这段时间根本没有发生"请求发出去了失败"，
  而是**进程没在跑**。

## 五、本轮修复

### 1. 反射全局缓存（`utils/KavaReflector.kt`）

`declaredMethods` / `declaredFields` 加 `ConcurrentHashMap<Class, List<...>>` 缓存，
**空结果也缓存**。类结构在进程生命周期内不变，缓存安全；上限 4096 条兜底。
这一条直接消掉 ANR 栈里的那条路径，且全项目所有反射调用点同时受益。

### 2. 输入框定位：负缓存 + 缓存查询（`script/ScriptSendButtonHook.kt`）

- `inputFieldCache` 改为 `Map<Class, Any>` + 哨兵，**没找到也落盘**；
- `noArgMethodCache` / `oneArgMethodCache` 同样加负结果哨兵；
- 新增 `inputScoreCache`，同一字段类型只打一次分（`ChatFooter` 里大量同类型字段）；
- 新增 `findInputViewCached()`：只查缓存、绝不扫描。

### 3. attach 期不再做扫描（`inputhint/InputHintFeature.kt`、`ScriptSendButtonHook`）

`ChatFooter.onAttachedToWindow` 的两处回调（输入框提示、输入时长）改为：
先 `findInputViewCached`，未命中就 `view.post {}` 推到下一帧再扫。
attach 路径上只剩一次 Map 查询，输入分发不会再有超时风险。

### 4. 保活服务组件名（`keepalive/WeChatKeepAliveService.kt`、`WeChatKeepAliveRuntime.kt`）

线上抓到 15 次：

```
W ActivityManager: Unable to start service Intent {
    cmp=com.tencent.mm/h.Hchat.hooks.items.keepalive.WeChatKeepAliveService } not found
```

根因：`WeChatKeepAliveRuntime` 把模块的 package context 换成了
`it.applicationContext`。模块 context 在 LSPosed 里没有自己的 Application，
`getApplicationContext()` 会回落到**宿主微信的 Application**，
于是 `Intent(ctx, Service::class)` 被解析成 `com.tencent.mm/...` → 组件不存在。
保活功能因此一直静默失效（前台服务、看门狗、心跳全部没跑）。

修法：`start/stop` 用 `setClassName(BuildConfig.APPLICATION_ID, ...)` 显式指定组件，
运行时不再做 `.applicationContext` 回落。

### 5. 堆栈模式日志去重（`utils/HLog.java`）

抓包时用户开着"记录堆栈"，于是每条错误都 `new RuntimeException()` 抓栈 +
`XposedBridge.log(Throwable)` 同步跨进程序列化。而「视频号详情解析失败」是每次进页面
都报一次（11:28 一分钟内 6 条）。现在同一位置 10 秒内只有第一条带完整堆栈，
其余折叠成一行。

## 六、仍未修（需要单独一轮）

| 位置 | 现象 | 原因 |
| --- | --- | --- |
| `shortvideo/FinderFeedDetailResolver.kt` | `视频号详情请求候选数量异常: count=0` | DexKit 按 `(long, String, int, int)` 构造器签名匹配，8.0.80 里签名已变，需重新反编译对位 |
| `messagebubble` 首页文字颜色 | `多次安装失败，停止本轮重试` | 同样属于 8.0.80 未适配 |
| `conversationgroup` | `读取真实会话父级失败` | 会话树字段定位失效 |

这三项都是"功能不生效"，不影响稳定性。每次启动会重试 2 次后放弃，
对启动时间有约 0.5–1s 影响，后续可以考虑持久化负缓存。
