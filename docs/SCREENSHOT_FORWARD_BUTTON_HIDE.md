# 隐藏「转发截图」浮层按钮（反编译 + 实现）

目标包：`wechat-8.0.80.apk`（8.0.80_3220，18 个 dex）。
产物：Hchat 6.9.6.1，开关在 **实用 → 增强 → 隐藏截图转发按钮**（默认关）。

## 一、结论

按钮不在资源布局里，也不在 `ScreenShotActionOverlay` 的 Java 代码里写死，而是由
**一个 provider 方法返回的动作项**动态生成的。所以正确的切点是那个 provider，而不是去改窗口/布局。

```
u0.a(zs5/w)  →  返回 [动作项("forward", "转发截图", icon)]
                        ↓
ScreenShotActionOverlay.k(List)  →  为每一项 createButton，塞进窗口
```

## 二、证据链

### 1. 文案 → 资源 id

```
aapt2 dump resources wechat-8.0.80.apk
resource 0x7f104db0 string/og0
  () "转发截图"
  (en) "Forward screenshot"
```

### 2. 全 dex 只有一处引用这个 id

按 `const vAA, 0x7f104db0`（format 31i：`14 | AA | lit32`）逐 dex 扫字节，唯一命中：

```
classes15.dex | Lcom/tencent/mm/ui/feature/api/screenshot/u0;->a(Lzs5/w;)Ljava/util/List;
```

### 3. 这个方法在做什么（指令级）

```
const-string v0, "request"                       ; Kotlin 参数空检查
iget-object v0, u0->b Leb6/a;  invoke()           ; 前置门槛
if-eqz v0 → sget-object qa6/q0;->d ; return       ; ← 门槛不满足就返回「空列表」
const v2, 2131774896                              ; = 0x7f104db0，「转发截图」
invoke Context->getString(I)                      ; 文案
const v6, 2131823295                              ; 图标资源
new-instance zs5/s
const-string v4, "forward"                        ; ← 动作 id 就叫 forward
invoke-direct zs5/s-><init>(String, CharSequence, I, zs5/q, I, zs5/r, Z)
invoke-static qa6/c0->c(Object)List               ; listOf(...)
return-object
```

关键点：**微信自己也会在这个方法里返回空列表**（门槛分支 `qa6.q0.d`），
所以「让它返回空列表」是官方走过的路径，不会触发调用方异常。

### 4. 列表去了哪里

```
Lcom/tencent/mm/ui/screenshot/action/ScreenShotActionOverlay;
  k(Ljava/util/List;)Lat5/f;
    for (z : list)                       // z = Lat5/z { a: 动作id, b: 载荷, c: zs5/s }
      label = z.c.b                      // CharSequence 文案
      view  = inflate(...); button = createButton(z.a, label)
      list.add(z)
    return Lat5/f(list, ...)
```

`ScreenShotActionOverlay` 的窗口 title 是 `com.tencent.mm/.ScreenShotActionButton`，
就是我们截图后右下角那个浮层按钮所在窗口。

## 三、实现（两处挂钩，互为兜底）

| 位置 | 做法 | 说明 |
| --- | --- | --- |
| `u0.a(zs5/w): List` | 直接 `return emptyList()` | 主切点。DexKit 按「方法内有字符串 `forward` + 单参数 + 返回 `java.util.List`」定位，再用明文包名 `com.tencent.mm.ui.feature.api.screenshot.` 收窄 |
| `ScreenShotActionOverlay.k(List)` | 摘掉文案命中的项 | 兜底。类名是明文，方法名混淆，所以按「单 List 参数 + 有返回值」锚定；文案用反射两层挖 CharSequence，不写死字段名 |

两处都只摘「转发」这一项：

- 文案命中 `转发截图`（中文包）或同时含 `forward` + `screenshot`（英文包 / 内部 id）；
- `转发` 单独出现**不算**——那是消息长按菜单里的另一个动作，不能连带隐藏。

## 四、代码

```
app/src/main/java/h/Hchat/hooks/items/screenshot/ScreenshotForwardButtonFeature.kt
app/src/test/java/h/Hchat/hooks/items/screenshot/ScreenshotActionLabelPolicyTest.kt   // 4 用例
```

注册：

- `FeatureRegistry` 加 `ScreenshotForwardButtonFeature()`
- `MiuixSettingsPage.FeatureSettingsPage` 加分支，复用 `HchatExtraToggleMiuixPage` + 按功能隔离的偏好存储
  （`configStore().getBoolean(featureId, "enabled", false)`）

## 五、验证

1. 装 6.9.6.1，进 **实用 → 增强 → 隐藏截图转发按钮** 打开。
2. 退到桌面/任意聊天截图：
   - 开关 **关**：右下角照常出现「转发截图」；
   - 开关 **开**：不再出现，截图本身、编辑/收藏等其它动作都不受影响。
3. 日志（`[Hchat:ScreenshotButton]`）：

```
已挂钩 转发provider=true 浮层绑定=true，微信 8.0.80
已拦截转发截图动作: com.tencent.mm.ui.feature.api.screenshot.u0#a
截图动作浮层候选项: forward|转发截图
浮层已摘掉转发按钮: 丢弃 1 项，保留 0/1
```

- 只有 `已挂钩` 没有 `已拦截`：provider 没被调用到 → 说明这条链不是当次渲染路径，把日志发我。
- `候选项` 那行是浮层兜底看到的原始文案，可用于核对文案是否变化。

## 六、边界与风险

- **只隐藏按钮**：截图、分享面板、其它动作均不改；不拦 `screencap`、不动窗口。
- **provider 返回空列表**：与微信自身门槛分支返回值一致，调用方已有空列表处理。
- **版本适配**：类名/包名是明文，方法名与字段名混淆。换版本时先看日志是「未命中」还是「命中了但没拦到」，
  再决定是改 DexKit 锚点还是改文案判定，不要凭记忆改类名。
- **英文/繁体包**：走 `forward` + `screenshot` 双关键词判定，不依赖中文文案。
