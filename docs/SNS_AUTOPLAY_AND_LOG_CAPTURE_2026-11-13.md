# 朋友圈自动播放拦截失效 + 日志抓取不可见（2026-11-13，6.9.6.3）

用户反馈：禁止朋友圈视频自动播放「试了还是不能用」；抓取日志功能「不能用」。

## 一、自动播放：缓存重载丢失主路径方法（实锤根因）

**离线验证**：对 `wechat-8.0.80.apk` 全 dex 扫描三条锚点字符串
（`autoPlay DynamicConfig false` / `wifi autoplay true` / `2G/3G autoplay false`），
引用它们的方法**只有两个**，与 6.9.x 反编译结论一致：

| 方法 | 返回类型 | 作用 |
|---|---|---|
| `fl4/b#invoke()` | `Object`（Boxed Boolean，Kotlin lambda） | **ImproveAutoPlayManager（新版信息流主路径）** |
| `pm4/e#a(wo)` | `boolean` | SnsTimeLineUI / 折叠详情页（旧路径） |

**根因**：`MomentsVideoAutoPlayFeature.resolveJudgeMethods()` 的缓存重载分支只保留
`returnType == boolean/Boolean` 的方法，`Object` 返回的 `fl4/b#invoke` 被**静默过滤掉**。
后果链：

1. 拨开开关 → 完整扫描 → 两个方法都挂上 → 当下好用；
2. 重启微信 → 走 `DexMethodCache` 缓存重载 → `fl4/b` 被丢 → 只剩 `pm4/e`；
3. 8.0.80 的朋友圈信息流走 improve 路径（`fl4/d`→`fl4/b`），根本不调 `pm4/e`
   → 自动播放回来了。

**修复**：
- 缓存重载过滤条件改为 `isCandidateReturnType(it) || isObjectReturning(it)`；
- 缓存描述符存在但被类型过滤到空时，清缓存强制重扫，不再困在残缺缓存里；
- hook 回调对 Object 形态的处理本就正确（`result is Boolean` 匹配装箱值），未改。

**新增拦截计数回显**：hook 命中后累计 `KEY_HIT_COUNT` 落盘（CAS 节流 10s 一次，
首次立即写），功能页显示「本次使用期间已拦截 N 次」——不依赖日志抓取，
数字在涨 = 拦截真实发生。

## 二、日志抓取：LSPosed 日志被采集模式吞掉

**发现的真陷阱**：`HLog.log()` 原来在 `captureEnabled` 时跳过 `XposedBridge.log`
（省一次同步 IPC）。但用户取日志的既定工作流是 **LSPosed/Vector 导出**
（此前 6.9.2 轮正是发 Vector zip 来的）。开了「记录运行日志」后模块日志
**只进内存缓冲、LSPosed 日志里全部消失**，按老习惯去 LSPosed 抓日志 = 空。
采集模式本来就是排查用的短期开关，正确语义是双写。

**修复**：`XposedBridge.log` 恢复为始终调用（去重已在 isDuplicate 完成，不会刷屏）。
关闭采集时行为与旧版完全一致。

**新增自检入口**：日志查看页加「写入测试日志」按钮——点击后下方立即出现
「测试日志（由设置页写入）」即证明 开关→缓冲→页面轮询 整条管线正常；
没出现才是真采集故障。空状态文案同步更新。

## 三、验证

- 编译过；单测 59 用例全过
- 版本 `6.9.6.3 / 6090603`，签名 CN=Hchat 不变

## 四、给用户的验证步骤

1. 覆盖安装后**强停微信再打开**（模块 dex 需要重新加载）；
2. 实用 → 禁止朋友圈视频自动播放：确认状态行是「已生效：已接入 2 个判定入口」
   （以前重启后只剩 1 个）；
3. 刷一次朋友圈，回到该功能页，看到「已拦截 N 次」在涨 = 生效；
4. 运行日志页点「写入测试日志」自检管线；之后再要日志，
   LSPosed 侧也能看到模块输出了（双写修复）。
