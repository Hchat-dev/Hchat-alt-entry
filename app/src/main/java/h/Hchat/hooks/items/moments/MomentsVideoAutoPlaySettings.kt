package h.Hchat.hooks.items.moments

object MomentsVideoAutoPlaySettings {
    const val PREFS_NAME = "Hchat_moments_video_autoplay_config"
    const val KEY_DISABLE_AUTOPLAY = "moments_video_disable_autoplay"
    const val DEFAULT_DISABLE_AUTOPLAY = false

    /**
     * 定位结果缓存，单独存放。
     * 微信内部混淆名每个版本都会变，用 DexMethodCache 的运行时 key 做失效，
     * 命中缓存就不再重新扫描 dex（扫描本身才是真正的 CPU 大头）。
     */
    const val CACHE_PREFS_NAME = "Hchat_moments_video_autoplay_cache"

    /**
     * 键名带 v2：旧版定位逻辑按 `com.tencent.mm.plugin.sns.` 包名硬过滤，
     * 而混淆后的目标类在根包下，理论上命中必为空、不会落盘。这里仍升版以彻底
     * 排除历史残留，避免旧结果被复用到新逻辑。
     */
    const val CACHE_KEY_JUDGE_METHODS = "sns_video_autoplay_judge_methods_v2"

    /**
     * 上一次安装结果，直接回显在设置页。
     *
     * 安装发生在微信启动早期，那时日志采集通常还是关的，日志里看不到定位过程。
     * 把结论落在这个 key 上，用户打开设置页即可判断「装上了 / 定位不到 / 开关没开」，
     * 不用靠抓日志猜。
     */
    const val KEY_LAST_STATUS = "moments_video_autoplay_last_status"

    const val STATUS_DISABLED = "disabled"
    const val STATUS_WAITING = "waiting"
    const val STATUS_NOT_FOUND = "not_found"
    const val STATUS_OK_PREFIX = "ok:"

    /**
     * 累计拦截次数。写盘有 10 秒节流，只用于设置页回显「拦截是否真的在发生」，
     * 让用户不依赖日志抓取就能判断功能是否生效。
     */
    const val KEY_HIT_COUNT = "moments_video_autoplay_hit_count"
}
