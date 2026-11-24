package h.Hchat.hooks.items.sportstep

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider
import h.Hchat.utils.HLog
import kotlin.random.Random

/**
 * 微信运动步数（对齐 Wren「运动步数」能力）。
 *
 * 每天通过设备步数上报通道（[h.Hchat.hooks.api.message.WeChatMessageApi.uploadDeviceStep]，
 * 与脚本插件同款底层 API）上传配置步数 ± 抖动，让微信运动排行榜显示自定义步数。
 *
 * ## 设计要点
 *
 * - 不做任何 hook：纯调度 + 现成 API，风险面为零。
 * - API 未就绪（DexFinder 还没定位到上报构造器）时 tick 直接跳过，下个 tick 再试。
 * - 上传成功以「当天键」记录，一天只传一次；失败不记，自然重试。
 * - 步数为 0 视为占位关闭（不传）。
 */
class SportStepFeature : BaseFeature() {
    private var runtime: SportStepRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "微信运动步数"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(
            object : SimpleFeatureSettingsProvider(
                ID,
                name(),
                "每天自动上报自定义步数到微信运动排行榜",
                FeatureSettingsProvider.CATEGORY_ENTERTAINMENT
            ) {}
        )
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = SportStepRuntime(context, ::logError)
        runtime?.start()
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.shutdown()
        runtime = null
    }

    companion object {
        const val ID = "sport_step"
        private const val TAG = "[Hchat:SportStep]"
    }
}

internal class SportStepRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val tag = "[Hchat:SportStep]"
    private val handler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences =
        h.Hchat.preferences.HchatStorage.preferences(context.hostContext(), SportStepSettings.PREFS_NAME)
    @Volatile private var started = false
    @Volatile private var shutDown = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (shutDown) return
            runCatching { tick() }.onFailure {
                logger("步数tick异常: ${it.message}", it)
            }
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    fun start() {
        if (started) return
        started = true
        shutDown = false
        // 首个 tick 延后一段，给 WechatApiFeature 的 DexFinder 定位留时间。
        handler.postDelayed(tickRunnable, FIRST_TICK_DELAY_MS)
        HLog.e("$tag 已启动，步数=${readStep()} 自动=${readAuto()}")
    }

    fun shutdown() {
        shutDown = true
        started = false
        handler.removeCallbacks(tickRunnable)
    }

    private fun tick() {
        val enabled = prefs.getBoolean(SportStepSettings.KEY_ENABLE, SportStepSettings.DEFAULT_ENABLE)
        val auto = prefs.getBoolean(SportStepSettings.KEY_AUTO, SportStepSettings.DEFAULT_AUTO)
        val step = readStep()
        val nowKey = SportStepPolicy.dayKey(System.currentTimeMillis())
        val lastDay = prefs.getString(SportStepSettings.KEY_LAST_UPLOAD_DAY, null)
        if (!SportStepPolicy.shouldUpload(enabled, auto && SportStepPolicy.isUsableStep(step), lastDay, nowKey)) return
        if (!WeChatApis.hasMessages()) return
        val finalStep = SportStepPolicy.jitteredStep(step, JITTER_PERCENT)
        val ok = runCatching { WeChatApis.messages().uploadDeviceStep(finalStep.toLong()) }
            .getOrElse {
                logger("步数上报异常: ${it.message}", it)
                false
            }
        if (ok) {
            prefs.edit().putString(SportStepSettings.KEY_LAST_UPLOAD_DAY, nowKey).apply()
            HLog.e("$tag 步数上报成功: $finalStep ($nowKey)")
        } else if (logFailures.compareAndSet(false, true)) {
            logger("步数上报未成功（API未就绪或通道失败），将在下个tick重试", null)
        }
    }

    private fun readStep(): Int = prefs.getInt(SportStepSettings.KEY_STEP, SportStepSettings.DEFAULT_STEP)
        .coerceIn(SportStepSettings.MIN_STEP, SportStepSettings.MAX_STEP)

    private fun readAuto(): Boolean = prefs.getBoolean(SportStepSettings.KEY_AUTO, SportStepSettings.DEFAULT_AUTO)

    companion object {
        private const val TICK_INTERVAL_MS = 30L * 60L * 1000L
        private const val FIRST_TICK_DELAY_MS = 15L * 1000L
        private const val JITTER_PERCENT = 3
        private val logFailures = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
