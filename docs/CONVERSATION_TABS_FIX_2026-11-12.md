# 标签分组（实用-增强-标签分组）问题定位与修复

日期：2026-11-12　版本：6.9.3 / 60903

## 一、用户反馈

1. 启用后只有「全部」「私聊」有内容，「未读」「群聊」「公众号」空白
2. 出现过一次「未读」「公众号」有内容，但切换标签很慢
3. 退出微信重进，分组又不显示内容

## 二、逆向证据（微信 8.0.80，classes11.dex / classes4.dex）

用 dex 字符串表全量扫描 18 个 dex：

| 锚点字符串 | 存在情况 |
| --- | --- |
| `MicroMsg.ConversationWithCacheAdapter` | classes11.dex 有 |
| `refreshChangedConversation searchArray` | **全 dex 不存在** |
| `all flag is same, count:` + `message_fold_config2` | classes11.dex 有 |
| `((parentRef is null) or (parentRef in ('', '` | classes11.dex，**仅 1 处 const-string 引用** |
| `remove conv, parentRef:` | classes11.dex 有 |

结论：`ConversationGroupHomeProjection.install()` 里

```kotlin
val legacy = find("MicroMsg.ConversationWithCacheAdapter", "refreshChangedConversation searchArray")
```

DexKit 的 `usingStrings` 是 **AND** 语义，第二个字符串在 8.0.80 根本不存在 → `legacy` 永远为空 → 代码走 `modern` 分支，只 hook `page` 与 `visible`，**没有任何 SQL 谓词下推**。

同一段代码里还有第二处致命限制：

```kotlin
val roots = if (legacy.isEmpty()) emptyList() else find("((parentRef is null)")
    .filter { ... && it.declaringClass.name.startsWith("com.tencent.mm.storage.") }
```

- `legacy.isEmpty()` 直接把查询跳过
- 即使不跳过，混淆后类名落到根包（例如 `lt4/h`），`startsWith("com.tencent.mm.storage.")` 会把它滤掉

这与之前「朋友圈视频禁止自动播放」踩的是同一个坑：**把包名前缀当成稳定性依据**。

## 三、根因链

### 根因 1：标签过滤只能靠逐行隐藏，分页被掏空后不再加载

没有 SQL 下推时，`visible(row)` 返回 false 只是把行隐藏。native 用固定条数（一页）取数：

- 切到「群聊」，首页前 20 条里可能只有 2~3 个群
- 剩余 3 条**填不满一屏**，列表无法滚动 → 不触发「加载更多」
- 用户看到的就是「群聊几乎是空的」

「私聊」内容最多（任何非群非公众号的会话都算），所以只有它看起来正常。
「未读」「公众号」偶尔出现几条，正好对应前 20 条里恰好有这几类的情况。

### 根因 2：username 取自 `convertTo()`，取不到时全部被归成「私聊」

```kotlin
val values = KavaReflector.invokeMethod(native, "convertTo") as? ContentValues
return values?.getAsString("username")
```

`convertTo()` 只导出部分列。一旦 `username` 为空字符串：

- `isGroup` = false
- `isOfficial` = false
- 不在 `system` 名单里
- → `PRIVATE` 判定为 true

于是**所有会话都落进「私聊」**，而「群聊」「公众号」必然为空。`unReadCount` / `verifyFlag` 是同一个来源，取不到就一律当 0，所以「未读」也空。

### 根因 3：切换慢

| 位置 | 开销 |
| --- | --- |
| `visible` after hook | **每行 2 次** `convertTo()`（反射查找 + 分配 ContentValues） |
| `page` after hook | 每行 1 次 `convertTo()` |
| `ConversationTabsRuntime.render()` | 每次 `onResume` 都 `removeAllViews()` 重建整排按钮 |
| `setHostEnabled()` | 每次 render 都把标签栏置 `INVISIBLE`，下一帧再定位，闪一下 |
| 切换标签 | 触发 native `update(type=5)` 全量重查（native 成本，不可避免） |

## 四、修复内容

### 1. 恢复 SQL 下推（核心）

`ConversationGroupHomeProjection.install()`

- `roots` 不再依赖 `legacy` 是否存在，总是查找 `((parentRef is null)` 所在的无参返回 String 方法
- **删除** `startsWith("com.tencent.mm.storage.")` 包名过滤
- 候选 >1 时放弃下推并记日志（避免 hook 错方法）
- 缓存版本 `homepage_projection_v1` → `v2`，旧缓存自动失效
- 新增 `rootsProbed` 标记：父级条件方法可能确实不存在，用独立标记记住"已经找过"，避免每次启动重跑 DexKit

`ConversationGroupHomeProjection` 的 root hook 条件

```kotlin
// 旧：if ((legacyDepth.get() ?: 0) <= 0) return   ← modern 路径永远不满足
// 新：只认 queryScopes 作用域
val scope = queryScopes.get()?.lastOrNull()?.takeIf { it.installation == installation } ?: return
```

modern 路径在 `page` 的 before 里 `enterQuery`、after 里 `leaveQuery`，root 方法在这个窗口内被调用，谓词就能真正进 SQL。

**效果**：native 拿到的分页结果本身已经是过滤后的，一页 20 条全是群聊 → 不再有空页，也不需要"加载更多"。

### 2. 会话名/未读数改用字段直读

新增 `storageField()` / `readStorageString()` / `readStorageInt()`，带 `ConcurrentHashMap` 缓存（含"确实没有这个字段"的哨兵）：

| 数据 | 首选 | 回退 |
| --- | --- | --- |
| username | `field_username` | `convertTo()` |
| 未读 | `field_unReadCount` | 0 |
| 免打扰未读 | `field_unReadMuteCount` | 0 |
| 认证标记 | `field_verifyFlag` | 0 |

微信所有 Storage 对象都用 `field_<name>`，比 `convertTo()` 稳定且不需要分配 ContentValues。

**安全兜底**：拿不到 username 时**不再隐藏该行**（记一条日志）。宁可多显示，也不能把整页误判成私聊。

`convertTo()` 的 Method 在安装期解析并缓存，不再每行 `findCompatibleMethod`。

### 3. 行转换方法定位放宽

```kotlin
// 旧：parameterTypes.all { it.name.startsWith("com.tencent.mm.storage.") } → 混淆后 single 抛异常
// 新：先按严格特征找唯一候选，找不到再按"两个非基本类型参数 + 非 void 返回"形状匹配
```

### 4. 空 WHERE 保护

`ConversationTabFilter.rootWhere()`：原生 WHERE 片段为空时不再拼出 `() AND (...)`（非法 SQL），直接返回谓词本身。

### 5. 切换/返回的 UI 开销

- `Host` 新增 `renderKey`（account + 夜间模式 + 全部标签的 id/名称/显示方式/图标状态）
- `render()` 命中相同 key 时只跑 `highlight()`，不重建按钮
- `setHostEnabled()` 只在启用状态真正变化时调用（原来每次都把标签栏置 INVISIBLE）
- 关闭时也清理按钮，避免残留

## 五、新增诊断日志

```
[Hchat:ConversationGroupHome] 首页入口 legacy=0 modern=1 roots=1 updates=2
[Hchat:ConversationGroupHome] 标签判定样本 user=xxx@chatroom unread=3 muted=0 verify=0 tab=GROUP
[Hchat:ConversationGroupHome] 首页行缺少 username(...xx)，本行不参与标签过滤
[Hchat:ConversationGroupHome] 首页父级条件候选不唯一(2)，本次跳过 SQL 下推
```

`roots=0` 表示没找到父级条件方法（SQL 下推不可用，只能靠逐行隐藏）；`roots=1` 表示下推已装上。

## 六、验证方式

1. 覆盖安装 6.9.3，打开「标签分组」
2. 切到「群聊」「未读」「公众号」，应立刻有内容且条数正常（不再是两三条）
3. 退出微信重进，分组内容应保持
4. 若仍异常，抓日志搜 `[Hchat:ConversationGroupHome]`：
   - 没有「首页入口」那行 → 首页投影没装成
   - `roots=0` 或出现「候选不唯一」→ 下推没生效
   - 出现「缺少 username」→ 字段名与预期不符

---

# 第二轮（6.9.4 / 60904）：公众号分组仍空白

## 一、实测日志给出的定位

```
16:31:19.533  [Hchat:ConversationGroupHome] 首页入口 legacy=0 modern=1 roots=1 updates=2
```

`roots=1` 只说明**方法定位成功**，不代表谓词进了 SQL。整份日志里**没有任何一条
`标签判定样本`**，说明逐行判定这条路径也从未真正执行到。

## 二、三个叠加的根因

### 1. root 方法的改写被作用域挡掉了（决定性）

```kotlin
val scope = queryScopes.get()?.lastOrNull()?.takeIf { it.installation == installation } ?: return
```

`queryScopes` 只在 modern 分页方法 `before/after` 之间开合。8.0.80 的首页 SQL 并不保证在
分页方法内部构造，根条件方法经常在作用域外被调用 → 每次都直接 `return` → 谓词永远进不了 SQL。
上一版为了修「legacyDepth 在 modern 路径恒为 0」把条件收得过紧，结果从一个极端走到另一个极端。

**改法**：root 方法不再要求作用域，只要当前有 active 的标签过滤器就改写。该方法以
`((parentRef is null)` 为唯一锚点（8.0.80 全 dex 仅 1 处引用），就是会话根 WHERE 生成器，
改写范围可控。

### 2. 公众号分组把唯一的容器行排除了

```kotlin
private val containers = setOf("officialaccounts", ...)
... "AND $user NOT IN (${inList(containers)})"
```

微信首页的「订阅号消息 / 服务号」是**聚合容器行**，username 分别为
`officialaccounts` / `service_officialaccounts` / `photoaccounts`；单个 `gh_` 会话被折叠在
容器下面，根查询里根本不出现。而 predicate 的最后统一追加了 `NOT IN (containers)`，
唯一该显示的那一行被自己排掉了 → 公众号分组必然空。

**改法**：
- 新增 `officialContainers`；OFFICIAL 分支的谓词显式承认这三个容器名
- OFFICIAL 分组不再追加容器排除
- `accepts()` 同步：容器名只在 OFFICIAL 分组算「公众号」，其它分组仍排除

### 3. parent() 破坏了原生折叠

```kotlin
"(CASE WHEN $reference IN ('officialaccounts',...,'message_fold') ... THEN NULL ELSE $reference END)"
```

把 `officialaccounts` 也提升成根会话，等于把几十个 `gh_` 会话平铺到首页，`全部` 标签的原生
结构被破坏。**改法**：只展开 Hchat 自己的分组容器（`wxid_hchat_group_*` / `hchat_conv_group:*`），
官方账号保持原生折叠。

## 三、再加一层保底：分页结果投影

SQL 下推是「尽力而为」——它能保证分页被填满，但一旦谓词没进 SQL，就只剩逐行隐藏，
而逐行隐藏只能否决、不能主动保留，公众号这类分组会整片空白。

`page.afterHookedMethod` 现在对返回列表直接做一次投影：

```
隐藏集合命中 → 丢弃
读不到 username → 保留（宁可多显示，也不要把整页误判成某一类）
tab 过滤器 → accepts(username, unReadCount, unReadMuteCount, verifyFlag)
```

SQL 下推生效时 `keep.size == rows.size`，不做任何写操作，只多一次遍历（20 行 × 约 5 次
已缓存 Field 读取，几十微秒）。下推失效时它就是唯一保证正确性的路径。

## 四、新增诊断日志（各限前 3~4 次，不会刷屏）

```
[Hchat:ConversationGroupHome] 根条件已下推 tab=OFFICIAL before=... after=...
[Hchat:ConversationGroupHome] 分页投影 tab=OFFICIAL rows=20 keep=1 sample=officialaccounts,xxx@chatroom,...
[Hchat:ConversationGroupHome] 没有可用的刷新入口，标签切换不会重查首页
```

三条日志分别对应：谓词是否进 SQL / 分页里到底有哪些 username / 切换标签能否触发重查。
第三条若出现，说明 `refreshers` 为空，切换标签不会重新查询，需要按实例类型补 hook。

## 五、验证

1. 覆盖安装 6.9.4，进入首页看「公众号」分组
2. 正常应显示「订阅号消息」（以及未折叠的 `gh_` 会话），不再是空白
3. 若仍空白，抓日志搜 `[Hchat:ConversationGroupHome]`：
   - 有 `根条件已下推` 但 `分页投影 rows=N keep=0` → predicate 与真实 username 不匹配，
     把 `sample=` 后面那段发我
   - 没有 `分页投影` → 分页 hook 没触发，看是否出现「没有可用的刷新入口」
   - 有 `sample=` 里全是 `xxx@chatroom` → 说明当前页确实没有公众号，属正常
