# 朋友圈视频禁止自动播放（实用 → 朋友圈）

开关默认关闭。位置：Hchat 设置 → 实用 → 朋友圈 → 禁止朋友圈视频自动播放。

## 需求

朋友圈信息流滚动时，视频卡片会静音自动播放。微信自带的「移动网络下视频自动播放」只
管移动网络，WiFi 下仍是强制自动播放。本功能在 WiFi / 移动网络下一律禁止自动播放，视频
改为点击后才播放。

## 8.0.80 反编译结论（2026-11 离线逆向官方包）

对官方 `weixin8080android3220_0x28005032_arm64.apk` 反编译（baksmali 3.0.9）确认：
朋友圈自动播放判定在 8.0.80 已**重构为两个返回 boolean 的独立方法**，不再是 7.x 时代
`SnsTimeLineUI` 里返回 void、写实例字段的旧形态。

| 混淆类 | 原始类/方法 | 签名 | 日志 tag | 调用点 |
| --- | --- | --- | --- | --- |
| `pm4/e` | `SnsAutoPlayUtil.checkAutoPlay(SnsNetworkMgr)` | `a(Lcom/tencent/mm/plugin/sns/ui/wo;)Z` | `MicroMsg.SnsAutoPlayUtil` | `sns.ui.uo`（网络广播接收）、`SnsWsFoldDetailUI`（折叠详情） |
| `fl4/b` | `ImproveAutoPlayManager$autoPlay$2.invoke()` | `invoke()Ljava/lang/Object`（Boxed Boolean） | —（走 `ImproveAutoPlayManager` 的 monitor） | 只被 `fl4/d` 引用；`fl4/d` 被 `sns.ui.improve.component.ImproveMainUIC` 引用 = 新版信息流主路径 |

两者共用同一套判定字符串，且从 7.x 一直保留：

```
"autoPlay DynamicConfig false"
"2G/3G autoplay false"       // 8.0.80 是 "2G/3G"（带斜杠），7.x 是 "2G3G"
"wifi autoplay true"          // WiFi 分支写死 true，正是要拦的目标
```

判定顺序：DynamicConfig 命中 → 关；2G/3G → 关；**WiFi → 强制 true**；其余读用户
设置（`u3.h9` 布尔，默认 true）。

`fl4/b.invoke()` 尾部即 `return-object Boolean.valueOf(v9)`，`v9` 就是 `autoPlay:%s`
日志里的那个布尔值 —— 即「是否允许自动播放」，不是「操作是否成功」。`pm4/e.a()` 同理。
所以对两者 `setResult(false)` 语义正确。

## 实现原理

判定方法直接返回「是否自动播放」的 boolean，因此 hook 这两个方法，在
`afterHookedMethod` 里把返回值改成 `false`：

- **零字段猜测**：不用猜混淆字段名，也不用快照对比实例字段。
- **零副作用**：不跳过方法体（播放器封面/尺寸/静音策略照常初始化），只覆盖最终开关。
- **工作量极小**：每次触发只做一次 `setResult(false)`。

## ⚠️ 关键教训：绝不能用包名过滤（第一版失效根因）

第一版定位写成：

```kotlin
.filter { it.declaringClass.name.startsWith("com.tencent.mm.plugin.sns.") }
```

看起来合理，实际上**必然命中 0 个**。因为微信混淆把类搬到根包：

- `pm4/e` 的 smali 声明是 `.class public final Lpm4/e;`
- `fl4/b` 的 smali 声明是 `.class public final Lfl4/b;`

两者都**不在** `com.tencent.mm.plugin.sns.*` 下。一行包名过滤把所有正确结果全部筛掉，
表现就是永远「未定位到判定方法」，功能静默失效。

> 通用结论：微信里只有**部分**类保留了 `com.tencent.mm.*` 原名（如 `SnsWsFoldDetailUI`），
> 被混淆的类会被搬到根包（两级短名，如 `pm4/e`、`fl4/b`）。任何按包名硬过滤的定位
> 逻辑都要先确认目标类本身是否被混淆。

## 定位策略（v2）

方法名/类名每版混淆，但日志字符串与返回类型稳定。用 DexKit 按**纯字符串组合 +
返回类型**定位：

1. **不写死 returnType**：`checkAutoPlay` 返回 `Z`，`autoPlay$2.invoke` 返回
   `Ljava/lang/Object`，两种都要接受。
2. **不做包名硬过滤**：包名只用于排序（sns 包的排前面），不用于筛选。
3. **锚点从强到弱**（同一方法内 AND 语义）：

   1. `["autoPlay DynamicConfig false", "wifi autoplay true", "2G/3G autoplay false"]`
   2. `["autoPlay DynamicConfig false", "wifi autoplay true"]`
   3. `["autoPlay DynamicConfig false", "2G/3G autoplay false"]`
   4. `["wifi autoplay true", "2G/3G autoplay false"]`

   三条同时命中在 8.0.80 全 dex 范围内**只命中这两个方法**（已用 smali 全量 grep 验证：
   `autoPlay DynamicConfig false` 仅出现在 `fl4/b.smali` 与 `pm4/e.smali`）。因此去掉
   包名过滤后不会误伤。刻意**不再保留单字符串兜底**：没有包名过滤时单字符串太容易命中
   其他模块。

定位结果按 微信版本+APK+Tinker 落盘缓存（`sns_video_autoplay_judge_methods_v2`，
键名升 v2 以排除第一版遗留）。

## 安装链路（第二处失效根因）

第一版在 `onFeatureInstall` 里直接同步调 `installHooks()`。此时 DexKit 可能还没就绪，
`bridge ?: return false` 静默失败，而 `onFeatureInstall` 只执行一次、**失败后无人重试**，
于是功能从启动那一刻起永久未安装。

现在的处理：

- `onFeatureInstall` → 走 `DexInstallScheduler.schedule(stage = BRIDGE)`，等 DexKit 就绪
  后执行，并做有限重试（1s / 3s / 6s）。
- 开关关闭时 `installHooks` 直接返回 `true`（本轮不重试），不扫 dex。
- 设置页拨开开关 → `onPreferenceChanged` 用**递增 key** 单独调度，保证不会被调度器里
  历史 `installed` 标记挡住；此时微信早已启动、DexKit 必然就绪，能立即装上。

## 状态回显（不用抓日志也能判断）

安装发生在微信启动早期，那时日志采集通常还是关的，日志里看不到定位过程。因此把结论写进
`moments_video_autoplay_last_status`，直接显示在设置页开关下方：

| 状态值 | 设置页显示 |
| --- | --- |
| `ok:N` | 已生效：已接入 N 个自动播放判定入口（主色） |
| `not_found` | 未生效：当前微信版本未匹配到判定入口 + 提示反馈版本号 |
| `disabled` / 空 | 不显示（开关关闭时） |

## 开销

| 场景 | 开销 |
| --- | --- |
| 开关关闭（默认） | 安装阶段读一次 SharedPreferences 就 return，不扫 dex、不挂 hook，运行期零开销 |
| 开关打开（首次） | DexKit 扫描一次（后台线程 `Hchat-DexInstall`），结果落盘缓存 |
| 开关打开（后续启动） | 读 `DexMethodCache` 缓存，不扫描 |
| Hook 命中时 | 只读一个 `@Volatile` 布尔缓存（不碰 SharedPreferences），再 `setResult(false)`；无 Bitmap/MediaPlayer 常驻 |
| 日志 | 最多打印 3 次拦截记录（`MAX_HIT_LOGS`），不刷屏 |

Hook 内部通过 `enabledCache` 实时判断开关：**关闭开关立即恢复自动播放，不需要重启微信**。

## 兼容性

- **8.0.80（优先适配）**：两个返回 boolean 的方法，已验证 smali 结构。
- **8.0.76**：字符串锚点仍在（已扫描确认 `autoPlay DynamicConfig false` /
  `wifi autoplay true` 存在于 dex），方法形态可能不同，由锚点组合自适应。
- **7.x**：旧形态是返回 void + 写实例字段，本实现不覆盖。若要兼容需再加一路
  「返回 void + 字段快照」兜底。

## 涉及文件

- `app/src/main/java/h/Hchat/hooks/items/moments/MomentsVideoAutoPlayFeature.kt`
- `app/src/main/java/h/Hchat/hooks/items/moments/MomentsVideoAutoPlaySettings.kt`
- `app/src/main/java/h/Hchat/hooks/items/moments/MomentsVideoAutoPlaySettingsProvider.kt`
- `app/src/main/java/h/Hchat/hooks/core/FeatureRegistry.kt`（注册）
- `app/src/main/java/h/Hchat/ui/miuix/MiuixSettingsPage.kt`（分组、详情页、状态回显、搜索关键字、配置备份）
- `app/proguard-rules.pro`（`HLog` keep）

## 验证

**首选：看设置页状态行**（不需要日志采集）

打开 设置 → 实用 → 朋友圈：

- 显示「已生效：已接入 2 个自动播放判定入口」→ 装上了，退出重进朋友圈即可看到效果。
- 显示「未生效：当前微信版本未匹配到判定入口」→ 定位失败，把微信版本号给我重新反编译。

**次选：抓日志**（需先在打开开关之后再抓）

`SnsAutoPlay` 的日志应该看到：

- `开始定位：微信 8.0.80，loader=...`
- `锚点组#1 命中 N 个方法，取前 N 个接入`
- `Hook 成功: pm4.e#a (返回 boolean)` / `Hook 成功: fl4.b#invoke (返回 Object)`
- `已安装朋友圈自动播放拦截，判定方法 N 个`
- 浏览朋友圈视频时：`已拦截自动播放：... 返回 true → false`（最多 3 条）

若只有 `锚点组#N 未命中`，说明该版本已清理日志字符串，需要重新离线反编译定位。
