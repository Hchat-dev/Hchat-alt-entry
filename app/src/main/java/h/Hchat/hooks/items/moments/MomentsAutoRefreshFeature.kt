package h.Hchat.hooks.items.moments

import android.content.SharedPreferences
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.preferences.HchatStorage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MomentsAutoRefreshFeature : BaseFeature() {
    private var runtime: MomentsAutoRefreshRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "朋友圈自动刷新"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MomentsAutoRefreshSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = MomentsAutoRefreshRuntime(context, ::logError)
        DexInstallScheduler.schedule(
            "$ID:runtime",
            name(),
            DexInstallScheduler.Stage.WARMUP
        ) {
            val current = runtime ?: return@schedule false
            current.start()
            true
        }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
    }

    companion object {
        const val ID = "moments_auto_refresh"
    }
}

private class MomentsAutoRefreshRuntime(
    context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs = HchatStorage.preferences(context.hostContext(), MomentsAutoRefreshSettings.PREFS_NAME)
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == MomentsAutoRefreshSettings.KEY_ENABLE) {
            reconfigure()
        } else if (key == MomentsAutoRefreshSettings.KEY_INTERVAL_SECONDS) {
            // 间隔变了要按新周期重建调度器，否则仍然按旧周期轮询。
            stopScheduler()
            reconfigure()
        }
    }
    private var scheduler: ScheduledExecutorService? = null
    private var lastAttemptAt = 0L

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        reconfigure()
    }

    fun destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        stopScheduler()
    }

    @Synchronized
    private fun reconfigure() {
        if (!prefs.getBoolean(MomentsAutoRefreshSettings.KEY_ENABLE, false)) {
            stopScheduler()
            return
        }
        if (scheduler?.isShutdown == false) return
        lastAttemptAt = 0L
        // 轮询粒度不再固定 1 秒。
        //
        // 原实现是 scheduleWithFixedDelay(::tick, 1, 1, SECONDS)，而 tick 内部
        // 先自增写入 lastAttemptAt、再判断「距上次不足 intervalMillis 就返回」，
        // 默认间隔 300 秒 = 300 次唤醒才做 1 次真实刷新。线程每秒醒来会让 CPU
        // 无法进入深度休眠，长时间挂后台就是持续的耗电，同时在主频波动下也会
        // 影响前台滑动的流畅度。
        //
        // 改成按「实际刷新间隔」调度，上限 60 秒：
        // - 间隔 ≤ 60 秒时完全对齐用户设置，不浪费；
        // - 间隔更长时按 60 秒检查一次，仍然满足时间窗口（默认 8:00–22:00）
        //   的分钟级判断精度，唤醒次数下降约 98%。
        val intervalSeconds = prefs.getInt(
            MomentsAutoRefreshSettings.KEY_INTERVAL_SECONDS,
            MomentsAutoRefreshSettings.DEFAULT_INTERVAL_SECONDS
        ).coerceAtLeast(1)
        val pollSeconds = intervalSeconds.toLong().coerceAtMost(MAX_POLL_INTERVAL_SECONDS)
        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "Hchat-MomentsAutoRefresh").apply { isDaemon = true }
        }.also {
            // 首次延迟必须是 0：原来首参传 pollSeconds，等于开启功能后要等一整个
            // 轮询周期（最长 60 秒）才做第一次判断，用户以为开关没生效。
            // tick 内部本来就有 now - lastAttemptAt < intervalMillis 的闸门，不会因此多刷。
            it.scheduleWithFixedDelay(::tick, 0, pollSeconds, TimeUnit.SECONDS)
        }
    }

    @Synchronized
    private fun stopScheduler() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    private fun tick() {
        if (!prefs.getBoolean(MomentsAutoRefreshSettings.KEY_ENABLE, false)) return
        if (prefs.getBoolean(MomentsAutoRefreshSettings.KEY_TIME_WINDOW_ENABLE, false) &&
            !isInMomentsTimeWindow(
                prefs.getString(MomentsAutoRefreshSettings.KEY_START_TIME, MomentsAutoRefreshSettings.DEFAULT_START_TIME).orEmpty(),
                prefs.getString(MomentsAutoRefreshSettings.KEY_END_TIME, MomentsAutoRefreshSettings.DEFAULT_END_TIME).orEmpty()
            )
        ) return
        val intervalMillis = prefs.getInt(
            MomentsAutoRefreshSettings.KEY_INTERVAL_SECONDS,
            MomentsAutoRefreshSettings.DEFAULT_INTERVAL_SECONDS
        ).coerceAtLeast(0) * 1000L
        val now = System.currentTimeMillis()
        if (now - lastAttemptAt < intervalMillis) return
        lastAttemptAt = now
        runCatching { WeChatApis.snsApi()?.refreshTimeline() == true }
            .onSuccess {
                if (!it) lastAttemptAt = now - intervalMillis + minOf(intervalMillis, RETRY_DELAY_MS)
                saveResult(now, if (it) "刷新请求已提交" else "刷新请求提交失败")
            }
            .onFailure {
                lastAttemptAt = now - intervalMillis + minOf(intervalMillis, RETRY_DELAY_MS)
                saveResult(now, "刷新异常：${it.message.orEmpty()}")
                logger("朋友圈自动刷新失败", it)
            }
    }

    private fun saveResult(time: Long, result: String) {
        prefs.edit()
            .putLong(MomentsAutoRefreshSettings.KEY_LAST_TIME, time)
            .putString(MomentsAutoRefreshSettings.KEY_LAST_RESULT, result)
            .apply()
    }

    companion object {
        private const val RETRY_DELAY_MS = 30_000L

        /** 轮询上限：间隔再长也至少每分钟检查一次，兼顾时间窗口精度与耗电。 */
        private const val MAX_POLL_INTERVAL_SECONDS = 60L
    }
}
