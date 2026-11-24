package h.Hchat.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 带缓存的日期格式化。
 *
 * ## 为什么要缓存
 *
 * `new SimpleDateFormat(pattern, locale)` 的构造过程要做 pattern 解析、
 * `DateFormatSymbols` 初始化（月份/星期名数组），单次成本在毫秒量级。
 *
 * 聊天列表渲染时，时间标签是**每个 item 每次 bind 都算一次**，配合
 * 滚动复用，一屏就能构造几十个 formatter。临时对象一多，GC 压力上来，
 * 表现就是滚动掉帧。这类开销不该出现在渲染路径上。
 *
 * ## 为什么用 ThreadLocal
 *
 * `SimpleDateFormat` 不是线程安全的。微信的 view holder bind 可能同时
 * 发生在主线程和后台线程，共享一个实例会出现错乱结果甚至
 * `ArrayIndexOutOfBoundsException`。ThreadLocal 保证每线程独立实例，
 * 同时仍然只创建一次。
 *
 * ## pattern / locale 变化
 *
 * 用户在设置里改时间格式后，pattern 变了要重建 formatter。
 * 缓存键同时包含 pattern 和 locale：只比 pattern 的话，系统/微信切语言后
 * 仍会复用旧实例，`SimpleDateFormat` 内部的 `DateFormatSymbols` 还是旧语言的
 * 月份/星期名，直到进程重启才恢复。
 *
 * 每线程保留少量槽位而不是单个槽位：聊天列表 bind 用 pattern A、防撤回提示用
 * pattern B 时，单槽会来回顶掉对方，等于每次都重建，缓存白做。
 */
object DateFormatCache {

    private class Holder {
        val formatters = HashMap<String, SimpleDateFormat>()
    }

    private val holder = object : ThreadLocal<Holder>() {
        override fun initialValue(): Holder = Holder()
    }

    /** 单线程最多缓存几种格式；超过就整体丢弃重建，避免无界增长。 */
    private const val MAX_CACHED_PATTERNS = 4

    /**
     * 按 [pattern] 格式化时间戳。
     *
     * 传入非法 pattern 时退回 [fallbackPattern]，避免把异常抛到渲染路径上。
     */
    @JvmStatic
    @JvmOverloads
    fun format(
        timestamp: Long,
        pattern: String,
        fallbackPattern: String = DEFAULT_PATTERN,
        locale: Locale = Locale.getDefault()
    ): String {
        return try {
            formatterFor(pattern, locale).format(Date(timestamp))
        } catch (_: Throwable) {
            try {
                formatterFor(fallbackPattern, locale).format(Date(timestamp))
            } catch (_: Throwable) {
                ""
            }
        }
    }

    private fun formatterFor(pattern: String, locale: Locale): SimpleDateFormat {
        val current = holder.get() ?: Holder().also { holder.set(it) }
        val key = pattern + '\u0000' + locale.toLanguageTag()
        current.formatters[key]?.let { return it }
        val created = SimpleDateFormat(pattern, locale)
        if (current.formatters.size >= MAX_CACHED_PATTERNS) current.formatters.clear()
        current.formatters[key] = created
        return created
    }

    private const val DEFAULT_PATTERN = "yyyy-MM-dd HH:mm:ss"
}
