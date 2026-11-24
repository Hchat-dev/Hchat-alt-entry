package h.Hchat.preferences

import java.util.concurrent.ConcurrentHashMap

/**
 * 热路径开关缓存。
 *
 * ## 为什么需要
 *
 * `ConfigStore.getBoolean` 每次调用都会：
 * 1. `"Hchat_feature_" + featureId` 拼一次字符串；
 * 2. `HchatStorage.preferences` 里再用 `"pkg:name"` 拼第二次、走一次 ConcurrentHashMap；
 * 3. 进 FastKV 内部取锁读条目。
 *
 * 单次几微秒，挂在**每次方法调用都会经过**的位置上就是灾难：
 * `Thread#getStackTrace`（微信日志/异常路径高频）、网络发包队列（每个请求）、
 * 列表绑定回调。这些位置一秒钟可能跑几百到几千次。
 *
 * ## 做法
 *
 * 首次取值后缓存 3 秒，读路径只剩一次 volatile 读 + 一次时间比较，
 * 不再拼字符串、不再拿锁。设置页拨开关后最多 3 秒在微信进程生效
 * （跨进程本来就只能靠轮询，3 秒对人工操作完全无感）。
 *
 * ## 边界
 *
 * - 只用于**开关类布尔值**；步数、时长这类数值配置不要走这里（需要精确即时）。
 * - 加载失败回退默认值，不抛异常。
 * - 每个功能在 `onFeatureInstall` 里创建一次 lambda 并长期持有，
 *   闭包直接捕获缓存条目，连 map 查找都省掉。
 */
object HotConfig {
    /** 缓存有效期；拨开关后的最大生效延迟。 */
    private const val TTL_MS = 3_000L

    private val entries = ConcurrentHashMap<String, Entry>()

    private class Entry(
        @Volatile var value: Boolean,
        @Volatile var expiresAt: Long
    )

    /**
     * 创建一个带缓存的开关读取函数。
     *
     * @param loader 真正读配置的逻辑（仅在缓存过期时执行）
     */
    fun flag(
        featureId: String,
        key: String = "enabled",
        default: Boolean,
        loader: () -> Boolean
    ): () -> Boolean {
        val cacheKey = featureId + '\u0000' + key
        val entry = entries.computeIfAbsent(cacheKey) { Entry(default, 0L) }
        return {
            val now = System.currentTimeMillis()
            if (now < entry.expiresAt) {
                entry.value
            } else {
                val fresh = try {
                    loader()
                } catch (_: Throwable) {
                    default
                }
                entry.value = fresh
                entry.expiresAt = now + TTL_MS
                fresh
            }
        }
    }

    /**
     * 立即让缓存失效。
     *
     * 已创建的 lambda 捕获的是同一个 Entry，因此这里把 Entry 置为过期，
     * 而不是只从 map 里摘掉——否则设置页改完开关，旧 lambda 还会拿旧值 3 秒。
     */
    fun invalidate(featureId: String, key: String = "enabled") {
        val removed = entries.remove(featureId + '\u0000' + key) ?: return
        removed.expiresAt = 0L
    }
}
