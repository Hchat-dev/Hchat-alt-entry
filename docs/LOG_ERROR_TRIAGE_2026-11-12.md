# 日志报错全量分诊（微信 8.0.80 / Hchat 6.9.5）

对应用户提供的日志（16:31:15 ~ 16:31:31，PID 30320）。

## 一、结论先说

日志里一共 11 类报错。**其中 8 类来自同一个根因：DexFinder 把"解析失败"的半成品缓存
当成完整快照，从此永远命中坏缓存、不再重扫。** 修这一处，8 类一起恢复。

剩下 3 类分别是：首页文字颜色（包名硬过滤）、视频号详情（构造器签名）、通话铃声（锚点全部消失）。

## 二、根因：DexFinder 缓存固化了失败结果

```java
if (loadCache()) {
    resolvedAll = true;
    ...
    return;          // ← 命中缓存直接返回，不再 resolveDatabaseApi()
}
...
resolveDatabaseApi();
```

`saveCache()` 只检查 `cacheDescriptorInvalid`（描述符层面），不看关键字段是否解析成功。
一次在错误时机（DexKit 未就绪 / 类加载失败）跑的全量解析，会把 `sqliteDbWrapperClass` 等
一堆 `null` 写进快照，并把 `CACHE_SNAPSHOT_VERSION` 标记成有效。

此后每次启动：

```
命中缓存: com.tencent.mm|8.0.80|3220|0x28005032|||1793796928186|stable
[WechatApi] 消息变更监听未就绪: databaseHooks=31 wrapperHooks=0 wrapperInserts=0
```

`wrapperHooks=0` 不是"没 hook 到"，而是 `dexFinder.sqliteDbWrapperClass == null`，
`WeChatDatabaseListenerApi.install()` 里 `while (cur != null)` 一步都没走。

### 已修

| 位置 | 修改 |
| --- | --- |
| `DexFinder.CACHE_SNAPSHOT_VERSION` | `"2"` → `"3"`，强制所有存量坏缓存失效一次 |
| `DexFinder.loadCache()` 收尾 | `sqliteDbWrapperClass == null` → 判定缓存无效、清空并重扫 |
| `DexFinder.saveCache()` | `snapshotReady` 增加同一判空，缺失时不写快照版本，避免再次固化 |
| `DexFinder.resolveDatabaseApi()` | 类级 AND 匹配失败时，用**方法锚点反查类**（`findClassByMethodStrings`）兜底，再校验「有 String 首参的库方法」防误命中 |

### 连带恢复的功能（无需单独改）

| 日志 | 机制 |
| --- | --- |
| `本地消息API: insert=null ... ctor=-1` | 同属 DexFinder 字段 |
| `消息变更监听未就绪 wrapperHooks=0` | `sqliteDbWrapperClass` 为 null |
| `input_hint 消息变更 API 未就绪` | 依赖上面 |
| `LinkParse 数据库监听未就绪` | 依赖上面 |
| `同步聊天分组失败: 读取真实会话父级失败` | `database.rawQuery()` 返回 null |
| `Script 未定位到ChatFooter发送入口` | `chatFooterSendClickMethod` 取自 DexFinder 缓存 |
| `数据库变更监听补装 / 自动抢礼物 / 指定骰子猜拳 / 快捷已读 / 脚本插件-消息监听 / 私聊免打扰API补定位 / 聊天发送按钮 多次安装失败` | 同一批 DexFinder 字段 |

## 三、首页文字颜色：包名硬过滤（已修）

```
[Hchat:HomeTextColor] 未找到唯一微信布局创建入口: count=0 methods=
```

实测 8.0.80 的布局入口是 `com.tencent.mm.ui.kd.onCreateView(String, Context, AttributeSet)`，
**符合** `startsWith("com.tencent.mm.ui.")`，所以过滤不是唯一原因。真正的隐患是：

- 过滤条件写死了包前缀，微信一旦把实现挪到别的包就静默失效；
- 失败日志只有 `count`，无法区分「DexKit 没命中」和「命中但类加载失败」。

**修改**：

1. `isLayoutInflaterFactoryMethod` 改为**排除**法（排除 `android.` / `androidx.` / `java.` /
   `com.facebook.` / `com.tencent.midas.` / `com.tencent.shadow.`），不再只允许 `com.tencent.mm.ui.`
2. 候选多于一个时优先主包实现，不再直接放弃
3. 日志增加 `dexHits=`（DexKit 命中数）与 `loaded=`（类加载成功数）

下次日志若是 `dexHits=0` → DexKit 查询本身有问题；`dexHits=1 loaded=0` → 类加载失败；
`count>0` 仍失败 → 候选歧义。三种情况指向完全不同，必须能区分。

## 四、视频号详情：构造器签名（已修）

```
[Hchat:FinderDetail] 视频号详情请求候选数量异常: count=0
```

8.0.80 里引用 `/cgi-bin/micromsg-bin/findergetcommentdetail` 的构造器是：

```
Lth2/g4;-><init>(J, String, I, I, String, Z, ..., Lkotlin/jvm/internal/j;)V
```

**36 个参数**。旧代码要求精确 `(long, String, int, int)`，必然 0 命中。

**修改**：

1. `isRequestConstructor` 放宽为「参数 ≥2 且首参 long、次参 String」
2. 选择顺序：唯一 → 旧精确签名 → 参数最少的那个（Kotlin 默认参数生成的合成构造器
   一定比业务构造器多出 int mask + DefaultConstructorMarker，所以"最少"通常是业务入口）
3. 多候选时打日志说明选了几个参数的

## 五、通话铃声：锚点全部消失（未修）

```
[Hchat:call_ringtone_block] 未定位微信群通话铃声入口
```

三个锚点在 8.0.80 全 dex 中**一个都不存在**：

```
MicroMsg.MT.MultiTalkAudioManager                                    NOT FOUND
requestAudioDeviceToStartRing but waitting the bt connected          NOT FOUND
requestAudioDeviceToStartRing ignore bluetooth or not bt plug        NOT FOUND
```

微信把通话铃声整体重构进了 `com.tencent.pigeon.voipmp`（新出现
`RingtoneHeadItem` / `RingtoneFeedOpenArgs` / `startPlayCustomRing` 等）。

新候选锚点（classes15.dex）：

```
startPlayCustomRing, voipSound: %s, msgSound: %s, msgShake: %s, isCallingParty: %s
startPlayCustomRingWithCustomInterval, voipSound: %s, msgSound: %s, isCallingParty: %s
```

但换锚点只是第一步，后面还有 `isMultiTalkRouteMethod` / `isMultiTalkDirectionMethod`
两套形状校验要跟着重写，属于独立一轮的工作量，本轮不硬改。

## 六、验证方法

1. 覆盖安装后**首次启动会重扫一次全量 dex**（约多花几秒到十几秒），这是缓存版本 2→3 的代价，
   只发生一次
2. 看日志搜 `[Hchat:DexFinder]`，应出现 `数据库 wrapper 改用方法锚点反查: axx.x` 或
   `数据库API: ... sqlite=` 且不为 null
3. 看 `[Hchat:WechatApi]` 不再刷 `wrapperHooks=0`
4. 若仍出现「缓存缺少数据库 wrapper 类，判定缓存失效并重新全量解析」，且**每次启动都重扫**，
   说明类级和方法级都没匹配到 `Lai5/d0`（该类的 `<init>` 里有 `MicroMsg.SqliteDB`、
   多个方法里有 `sql is null `）。这种情况把日志里 `resolveDatabaseApi` 附近的行发我，
   需要按 DexKit 的 `FindMethod` 返回值逐条核对。
5. 验证功能：消息撤回/链接解析/输入提示/聊天分组同步/脚本发送按钮，这几项共用同一条链路，
   任意一项恢复即说明缓存根因已解决
