package h.Hchat.hooks.items.sportstep

import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random

object SportStepSettings {
    const val PREFS_NAME = "Hchat_sport_step_config"
    const val KEY_ENABLE = "sport_step_enable"
    const val KEY_STEP = "sport_step_step"
    const val KEY_AUTO = "sport_step_auto"
    const val KEY_LAST_UPLOAD_DAY = "sport_step_last_upload_day"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_STEP = 20000
    const val DEFAULT_AUTO = true

    const val MIN_STEP = 0
    const val MAX_STEP = 100000
}

/**
 * 上传/抖动判定。抽成纯函数是为了 JVM 单测锁死边界，不起微信进程。
 *
 * 上传走 [h.Hchat.hooks.api.message.WeChatMessageApi.uploadDeviceStep]（底层
 * DexKit 定位的设备步数上报通道，脚本插件同款 API），本类只负责「什么时候传、传多少」。
 */
internal object SportStepPolicy {

    /** 步数抖动：固定值 + ±jitterPercent 的随机偏差，避免每天整整齐齐同一个数。 */
    fun jitteredStep(baseStep: Int, jitterPercent: Int, random: Random = Random.Default): Int {
        val base = baseStep.coerceIn(SportStepSettings.MIN_STEP, SportStepSettings.MAX_STEP)
        if (base <= 0) return 0
        val jitter = (base * jitterPercent.coerceIn(0, 50)) / 100
        if (jitter == 0) return base
        return base + random.nextInt(-jitter, jitter + 1)
    }

    /**
     * 当日键（按北京时间天为单位）。用 Calendar 而不是 java.time，
     * 避免对 desugaring 的依赖。
     */
    fun dayKey(epochMillis: Long, zone: TimeZone = TimeZone.getTimeZone("GMT+8")): String {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = epochMillis }
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return "%04d-%02d-%02d".format(year, month, day)
    }

    /**
     * 本次 tick 是否应该上传。
     * - 开关关闭或未开自动：不上传
     * - 当天已成功上传过：不上传
     * - 其余情况：上传（失败不记状态，下个 tick 自然重试）
     */
    fun shouldUpload(
        enabled: Boolean,
        autoUpload: Boolean,
        lastUploadDay: String?,
        nowKey: String
    ): Boolean {
        if (!enabled || !autoUpload) return false
        return lastUploadDay != nowKey
    }

    /** 步数输入合法性：0 表示当天不上传（占位关闭）。 */
    fun isUsableStep(step: Int): Boolean = step in 1..SportStepSettings.MAX_STEP
}
