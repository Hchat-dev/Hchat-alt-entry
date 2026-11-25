package h.Hchat.utils

/**
 * 模块自身类的**运行时**类名登记表。
 *
 * ## 为什么需要
 *
 * 反检测的「隐藏模块调用栈」原本按类名前缀判断自身帧（`h.Hchat.`）。release 走 R8 后，
 * 未显式 keep 的类会被改名并**压进默认包**——实测 6.7.7.5 的 dex：7962 个类里 6906 个
 * 类名不含 `.`（`Lu;`、`La00;` 这种），`h.Hchat.*` 只剩 40 个（proguard 里 keep 的），
 * 单字母包 0 个。于是前缀判断命中不了，钩子回调帧照样出现在宿主调用栈里。
 *
 * 这里换个思路：**名字拿不到，就在运行时记下来**。Hook 注册和功能装载时都能拿到
 * 真实的 `Class` 对象，取其 `getName()` 存进集合，过滤时按精确类名命中，与混淆策略无关。
 *
 * ## 为什么用并发集合而不是写时复制
 *
 * `contains` 挂在每一次 `getStackTrace` 上，是真正的热路径。之前用过"写时复制 +
 * volatile 引用"，读确实快，但每次 `register` 都要整体拷贝一遍（装载期几百次 ×
 * 每次几百个引用），白白制造一堆短命对象。并发集合的 `contains` 同样是**无锁、零分配**，
 * 哈希定位与普通 HashSet 是同一量级，且登记不再产生任何拷贝 —— 更省内存、GC 更安静。
 *
 * ## 上限
 *
 * 正常情况下登记量 = hook 回调数 + 功能数（几百条，约 30 KB）。设 [MAX_ENTRIES] 只是
 * 防御：万一有动态生成的类被登记，也不会把内存拖成无界增长。
 */
object SelfFrameRegistry {
    /** 防御性上限；正常装载远达不到。 */
    private const val MAX_ENTRIES = 4_096

    private val names: MutableSet<String> = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    @JvmStatic
    fun register(cls: Class<*>?) {
        if (cls != null) register(cls.name)
    }

    @JvmStatic
    fun register(className: String?) {
        if (className.isNullOrEmpty()) return
        if (names.size >= MAX_ENTRIES) return
        names.add(className)
    }

    /** 栈帧过滤的判断入口：无锁、无分配。 */
    @JvmStatic
    fun contains(className: String?): Boolean {
        if (className.isNullOrEmpty()) return false
        return names.contains(className)
    }

    /** 已登记数量，仅用于启动期自检日志。 */
    @JvmStatic
    fun size(): Int = names.size
}