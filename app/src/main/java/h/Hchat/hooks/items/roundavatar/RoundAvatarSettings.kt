package h.Hchat.hooks.items.roundavatar

import android.content.Context
import h.Hchat.preferences.HchatStorage
import kotlin.math.roundToInt

object RoundAvatarSettings {
    const val PREFS_NAME = "Hchat_round_avatar_config"

    const val KEY_ENABLE = "round_avatar_enable"
    const val KEY_RADIUS_FACTOR = "round_avatar_radius_factor"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_RADIUS_FACTOR = 0.5f
    const val MIN_RADIUS_FACTOR = 0.1f
    const val MAX_RADIUS_FACTOR = 0.5f

    // 这两个值在头像 draw() 热路径上被反复读（自定义头像/圆角头像两个 feature 都会查），
    // 加读缓存 + 配置变更监听失效，行为与直读一致。
    @Volatile private var enableCache: Boolean? = null
    @Volatile private var factorCache: Float? = null
    @Volatile private var listenerBound = false

    private val invalidator = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == KEY_ENABLE) enableCache = null
        if (key == null || key == KEY_RADIUS_FACTOR) factorCache = null
    }

    private fun ensureListener(context: Context) {
        if (listenerBound) return
        synchronized(this) {
            if (listenerBound) return
            runCatching {
                preferences(context).registerOnSharedPreferenceChangeListener(invalidator)
            }
            listenerBound = true
        }
    }

    fun enabled(context: Context): Boolean {
        ensureListener(context)
        enableCache?.let { return it }
        val v = preferences(context).getBoolean(KEY_ENABLE, DEFAULT_ENABLE)
        enableCache = v
        return v
    }

    fun radiusFactor(context: Context): Float {
        ensureListener(context)
        factorCache?.let { return it }
        val v = normalizeRadiusFactor(
            preferences(context).getFloat(KEY_RADIUS_FACTOR, DEFAULT_RADIUS_FACTOR)
        )
        factorCache = v
        return v
    }

    fun normalizeRadiusFactor(value: Float): Float {
        return (value.coerceIn(MIN_RADIUS_FACTOR, MAX_RADIUS_FACTOR) * 100f).roundToInt() / 100f
    }

    fun displayPercent(value: Float): Int {
        return (normalizeRadiusFactor(value) * 100f).roundToInt()
    }

    private fun preferences(context: Context) = HchatStorage.preferences(context, PREFS_NAME)
}
