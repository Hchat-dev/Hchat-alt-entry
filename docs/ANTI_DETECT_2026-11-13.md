# 防封轮：反检测防护（6.9.6.5）

日期：2026-11-13
目标：降低 Hchat 被微信风控检测到的可能性，同时不碰中心化远程开关。

## 需求背景

功能已基本齐了，进入"防止被检测、防止封号"阶段。参考防封清单图片圈定的方向，
拆成四件事：

| # | 事项 | 取舍 |
| --- | --- | --- |
| ① | 隐藏栈帧（Xposed/LSPosed/Hchat 自身帧） | ✅ 实现，默认开 |
| ② | 伪装 Xposed 环境探测（安装列表） | ✅ 实现，默认开（forName 路线 6.9.6.8 移除，见下） |
| ③ | 拦截风控/埋点上报 CGI | ✅ 实现，默认开 |
| ④ | 中心化熔断（远程开关，wxp2022.vip 类方案） | ❌ **拒绝实现**——联第三方域名等于把"你的号还能不能用"的开关交给别人，且该域名本身就是上报源 |
| ⑤ | 时序抖动（自动回复固定 300ms 间隙） | ✅ 实现，叠加 0~500ms 抖动 |

## ① 隐藏栈帧 — `StackTraceGuardFeature`

- **反编译/运行依据**：微信风控运行期遍历 `Thread.currentThread().getStackTrace()`
  / `new Exception().getStackTrace()` 找 `xposed`、`LSPHooker_` 等痕迹；LSPosed 的
  hook 代理会在栈上留 `de.robv.android.xposed.XposedBridge`、`LSPHooker_xxx` 帧。
- **做法**：hook `Thread#getStackTrace()` 与 `Throwable#getStackTrace()`，返回前摘掉
  四类帧：`de.robv.android.xposed.`、`org.lsposed.`、`io.github.lsposed.`、
  `h.Hchat.` 前缀 + `LSPHooker_ / XposedBridge / XposedHelper / EdXposed / SandHook / PineHook` 片段。
- **边界**：
  - 无命中时**原数组引用原样返回**（`===` 短路），日常栈遍历零开销；
  - 全命中时保留原栈（空栈比带痕迹更可疑）；
  - `Throwable#printStackTrace` 走私有字段，覆盖不到——但没有探针用它做逻辑判定；
  - `Thread#getAllStackTraces()` 内部逐线程调公开 getStackTrace()，天然被覆盖。

## ② 伪装 Xposed 探测 — `XposedProbeFeature`

| 探针 | 对策 |
| --- | --- |
| ~~`Class.forName("de.robv.android.xposed.*")`~~ | ~~抛 `ClassNotFoundException`~~ **⛔ 6.9.6.8 移除** |
| `getInstalledPackages/Applications()` 扫管理器 | 结果列表摘掉包名含 xposed/lsposed/edxposed 的项 |
| `getPackageInfo / getApplicationInfo` 定点探测 | 抛 `NameNotFoundException` |

- **⛔ forName 路线翻车实录（6.9.6.8 修正，C 类教训）**：6.9.6.5~6.9.6.7 hook
  `Class.forName` 两个重载并抛 CNFE，实测微信启动即闪退。根因：`de.robv.android.xposed.*`
  正是 LSPosed 框架自身的 API 包名，框架 hook 派发链内部也经 `Class.forName` 解析这些类，
  伪装成"不存在"等于炸掉框架自己的类解析；且 `forName(String)` 内部转发 3 参重载，
  最热反射入口每次调用跑两遍回调。
- **规则**：凡是框架自身身份所在的名字空间，一律不能在类加载层伪装。
- **边界**：PackageManager 查询是低频调用，hook 开销无感；回调不主动 throw，
  仅 `getPackageInfo/getApplicationInfo` 命中探测包名时抛该方法自身的
  `NameNotFoundException`（与宿主方法语义一致）。

## ③ 拦截风控上报 — `TelemetryGuardFeature`

- **反编译依据（8.0.80）**：发包链路 `q63/o → q63/j → r63/c → modelbase/i#doScene → dispatch`，
  `network/y0#getUri()` 拿 CGI URI。已确认端点：
  - `/cgi-bin/mmsupport-bin/stackreport`（异常栈上报，**收集 hook 痕迹的主通道**）
  - `/cgi-bin/mmsupport-bin/reportdevice`（设备环境上报）
  - `newreportkvcomm` / `newreportkvcommrsa`、`rtkvreport`（KV 埋点）
- **做法**：在 `WeChatNetworkDispatcher` 新增**进程级 SendGuard 表**（静态 CopyOnWriteArrayList），
  队列 hook 回调对每个请求提取 URI（`getUri()`，带正/负缓存）交给守卫评估；
  命中即整次丢弃（boolean 队列→false、int 队列→0、void→跳过），等价一次普通发送失败，
  走微信自己的容错。守卫抛异常按放行处理（fail-open），绝不拖垮正常收发。
- **边界**：
  - 只按 URI 片段匹配上述已确认端点，正常收发/朋友圈/支付 CGI 一个不碰；
  - URI 提取不到 → 放行；
  - 被丢弃的上报微信会照常重试，日志可见"已拦截风控上报 CGI(累计N次)"（前 20 次逐条 + 每 200 次一条）；
  - **若出现登录异常等副作用，关掉该开关立即恢复**（其余两个不受影响）。

## ⑤ 时序抖动 — `AutoReplyRuntime`

固定 300ms 间隔是显眼机器指纹。新增 `sendGapMs() = 300 + Random(0..500)`，
均值仍约 550ms，体感无差异，但时序指纹被打散。覆盖自动回复所有分步发送间隙
（多步回复 / 多段文本 / 多段语音 / 多文件 / 收藏）。

## 设置页性能（同轮附带）

- `MiuixSettingsPage` 主页 + 搜索页的 4 个 `providers.filter` 全部 `remember(providers)` 记忆化
  （providers 引用不变时不再每次重组重算，UIRegistry 本身已有快照）；
- 设置页首帧耗时打点：`[Hchat:SettingsUI] 设置页首帧组装完成: Nms`，便于回归对比。

## 交付与验证

- 三个功能均注册进 `FeatureRegistry`，位于 实用-增强 分类，默认开启；
- 纯策略抽到 `AntiDetectPolicy.kt`，JVM 单测 `AntiDetectPolicyTest`（栈帧过滤 /
  URI 黑名单 / 探测判定边界共 12 例）；
- 版本 6.9.6.5，`encodeVersionCode` 自动推导 versionCode。
