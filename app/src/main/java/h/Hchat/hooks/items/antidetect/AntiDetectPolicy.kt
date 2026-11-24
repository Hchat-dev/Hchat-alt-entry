package h.Hchat.hooks.items.antidetect

/**
 * 反检测功能的纯判定策略。全部抽成无 Android 依赖的对象，
 * 是为了能在 JVM 单测里锁死边界，不必起微信进程。
 */
internal object StackFramePolicy {
    /** 类名前缀命中即隐藏：Xposed/LSPosed 框架与本模块自身的类。 */
    val HIDDEN_CLASS_PREFIXES = listOf(
        "de.robv.android.xposed.",
        "org.lsposed.",
        "io.github.lsposed.",
        "h.Hchat."
    )

    /** 类名片段命中即隐藏：LSPosed 注入的代理方法帧与常见 hook 框架实现类。 */
    val HIDDEN_CLASS_FRAGMENTS = listOf(
        "LSPHooker_",
        "XposedBridge",
        "XposedHelper",
        "EdXposed",
        "SandHook",
        "PineHook"
    )

    /**
     * 最短的隐藏判定串长度：片段里最短是 `SandHook` / `PineHook`（8），
     * 前缀里最短是 `h.Hchat.`（9）。比它短的类名不可能命中任何一条。
     */
    private const val MIN_HIDDEN_NAME_LENGTH = 8

    /**
     * 是否属于要隐藏的帧。
     *
     * @param isSelf 运行时类名判断（`SelfFrameRegistry::contains` 之类）。
     *   release 构建走 R8 后会改短模块自身的类名并压进默认包，`h.Hchat.` 前缀
     *   只对 proguard 里显式 keep 的少数类有效，所以必须由调用方补一份"登记过的
     *   真实类名"判断，否则匿名回调类（`u`、`n` 这种）根本拦不住。
     *
     * 判断顺序按"最可能命中、最便宜"排：
     * 1. 不含 `.` 的类名（混淆后的自有类、极少数默认包类）→ 只查登记表，一次哈希；
     * 2. 含 `.` 且短于 8 → 直接排除，**跳过 10 次子串扫描**；微信自己的 `px.b`、`wx.m0`
     *    这类帧全走这条，比加登记表之前的实现还快；
     * 3. 其余长名才做前缀/片段扫描，并用登记表兜 keep 过的自有类。
     */
    fun isHiddenClassName(className: String, isSelf: (String) -> Boolean = { false }): Boolean {
        if (className.isEmpty()) return false
        if (className.indexOf('.') < 0) return isSelf(className)
        if (className.length < MIN_HIDDEN_NAME_LENGTH) return false
        if (isSelf(className)) return true
        for (prefix in HIDDEN_CLASS_PREFIXES) {
            if (className.startsWith(prefix)) return true
        }
        for (fragment in HIDDEN_CLASS_FRAGMENTS) {
            if (className.contains(fragment)) return true
        }
        return false
    }

    /**
     * 过滤调用栈。没有任何命中时原数组原样返回（引用相等），
     * 调用方可以用 `===` 判断是否真的换过数组，避免无谓的重组。
     * 过滤后若为空栈（极端：栈上全是模块帧），保留原栈——空栈比带痕迹更可疑。
     */
    fun clean(stack: Array<StackTraceElement>, isSelf: (String) -> Boolean = { false }): Array<StackTraceElement> {
        var hidden = false
        for (element in stack) {
            if (isHiddenClassName(element.className, isSelf)) {
                hidden = true
                break
            }
        }
        if (!hidden) return stack
        val kept = stack.filterNot { isHiddenClassName(it.className, isSelf) }.toTypedArray()
        return if (kept.isEmpty()) stack else kept
    }
}

internal object TelemetryUriPolicy {
    /**
     * 风控/埋点上报 CGI 的 URI 片段（8.0.80 反编译自 network/y0#getUri 的返回值）。
     * - /cgi-bin/mmsupport-bin/stackreport：崩溃/异常栈上报（也是收集 hook 痕迹的通道）
     * - /cgi-bin/mmsupport-bin/reportdevice：设备环境上报
     * - newreportkvcomm / newreportkvcommrsa：KV 行为埋点（rsa 是其加密变体）
     * - rtkvreport：实时 KV 上报
     */
    val BLOCKED_URI_FRAGMENTS = listOf(
        "mmsupport-bin/stackreport",
        "mmsupport-bin/reportdevice",
        "newreportkvcomm",
        "rtkvreport"
    )

    /**
     * 是否命中上报端点。
     *
     * 这段代码挂在**每个网络请求**上，不能每次都 `lowercase()` 造一个新字符串。
     * 微信的 CGI 路径本身是小写（如 `/cgi-bin/mmsupport-bin/stackreport`），
     * 先直接 contains 快速判定；只有含大写字母时才退回一次 lowercase 兜底。
     */
    fun shouldBlock(uri: String?): Boolean {
        if (uri.isNullOrEmpty()) return false
        if (BLOCKED_URI_FRAGMENTS.any(uri::contains)) return true
        var hasUpper = false
        for (i in uri.indices) {
            val c = uri[i]
            if (c in 'A'..'Z') {
                hasUpper = true
                break
            }
        }
        if (!hasUpper) return false
        val lower = uri.lowercase()
        return BLOCKED_URI_FRAGMENTS.any(lower::contains)
    }
}

internal object XposedProbePolicy {
    /** 通过 Class.forName 探测 Xposed 框架的类名前缀：命中一律伪装成"类不存在"。 */
    val DENIED_CLASS_PREFIXES = listOf(
        "de.robv.android.xposed",
        "org.lsposed",
        "io.github.lsposed"
    )

    /** 通过已安装应用列表探测模块管理器的包名片段（小写比较）。 */
    val DENIED_PACKAGE_FRAGMENTS = listOf(
        "xposed",
        "lsposed",
        "edxposed"
    )

    fun isDeniedClassName(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        return DENIED_CLASS_PREFIXES.any(name::startsWith)
    }

    fun isDeniedPackageName(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        val lower = packageName.lowercase()
        return DENIED_PACKAGE_FRAGMENTS.any(lower::contains)
    }
}
