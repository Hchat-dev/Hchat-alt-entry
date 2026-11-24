package h.Hchat.hooks.items.customfriendavatar

import android.content.Context
import h.Hchat.preferences.HchatStorage

object CustomFriendAvatarSettings {
    const val PREFS_NAME = "Hchat_custom_friend_avatar_config"

    const val KEY_ENABLE = "enable"
    const val KEY_CHAT = "scope_chat"
    const val KEY_CONVERSATION = "scope_conversation"
    const val KEY_CONTACTS = "scope_contacts"
    const val KEY_PROFILE = "scope_profile"
    const val KEY_MOMENTS = "scope_moments"
    const val KEY_OTHER_UI = "scope_other_ui"
    const val KEY_DESKTOP_SHORTCUT = "scope_desktop_shortcut"
    const val KEY_NOTIFICATIONS = "scope_notifications"
    const val KEY_MOMENTS_NOTIFICATIONS = "scope_moments_notifications"
    const val KEY_CONVERSATION_MENU = "conversation_menu"
    const val KEY_CONFIGURED_FRIENDS = "configured_friends"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_SCOPE = true
    const val DEFAULT_MENU = true

    fun enabled(context: Context): Boolean = preferences(context).getBoolean(KEY_ENABLE, DEFAULT_ENABLE)

    fun scopeEnabled(context: Context, surface: AvatarSurface): Boolean {
        // draw() 每帧每头像走这里，走缓存版；配置变更由 AvatarScopeCache 的监听失效。
        return AvatarScopeCache.enabled(context) && AvatarScopeCache.scope(context, surface)
    }

    fun notificationsEnabled(context: Context): Boolean {
        return enabled(context) && preferences(context).getBoolean(KEY_NOTIFICATIONS, DEFAULT_SCOPE)
    }

    fun desktopShortcutEnabled(context: Context): Boolean {
        return enabled(context) && preferences(context)
            .getBoolean(KEY_DESKTOP_SHORTCUT, DEFAULT_SCOPE)
    }

    fun momentsNotificationsEnabled(context: Context): Boolean {
        return enabled(context) && preferences(context)
            .getBoolean(KEY_MOMENTS_NOTIFICATIONS, DEFAULT_SCOPE)
    }

    fun conversationMenuEnabled(context: Context): Boolean {
        return enabled(context) && preferences(context).getBoolean(KEY_CONVERSATION_MENU, DEFAULT_MENU)
    }

    internal fun preferences(context: Context) = HchatStorage.preferences(context, PREFS_NAME)
}

/**
 * 头像 draw() 是每帧每头像的热路径，原来每次都读 FastKV（enable + 场景开关两次查询）。
 * 这里按「读缓存 + 配置变更即失效」的模式给 scope 判定加一层；配置页改开关是低频操作，
 * 变更时全量清空即可，下一次读取重新填充，行为与直读完全一致。
 */
internal object AvatarScopeCache {
    @Volatile private var enableCache: Boolean? = null
    private val scopeCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    @Volatile private var listenerBound = false

    private val invalidator = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        enableCache = null
        scopeCache.clear()
    }

    fun enabled(context: Context): Boolean {
        ensureListener(context)
        enableCache?.let { return it }
        val v = CustomFriendAvatarSettings.preferences(context).getBoolean(
            CustomFriendAvatarSettings.KEY_ENABLE, CustomFriendAvatarSettings.DEFAULT_ENABLE
        )
        enableCache = v
        return v
    }

    fun scope(context: Context, surface: AvatarSurface): Boolean {
        ensureListener(context)
        val key = when (surface) {
            AvatarSurface.CHAT -> CustomFriendAvatarSettings.KEY_CHAT
            AvatarSurface.CONVERSATION -> CustomFriendAvatarSettings.KEY_CONVERSATION
            AvatarSurface.CONTACTS -> CustomFriendAvatarSettings.KEY_CONTACTS
            AvatarSurface.PROFILE -> CustomFriendAvatarSettings.KEY_PROFILE
            AvatarSurface.MOMENTS -> CustomFriendAvatarSettings.KEY_MOMENTS
            AvatarSurface.OTHER -> CustomFriendAvatarSettings.KEY_OTHER_UI
        }
        scopeCache[key]?.let { return it }
        val v = CustomFriendAvatarSettings.preferences(context).getBoolean(
            key, CustomFriendAvatarSettings.DEFAULT_SCOPE
        )
        scopeCache[key] = v
        return v
    }

    private fun ensureListener(context: Context) {
        if (listenerBound) return
        synchronized(this) {
            if (listenerBound) return
            runCatching {
                CustomFriendAvatarSettings.preferences(context)
                    .registerOnSharedPreferenceChangeListener(invalidator)
            }
            listenerBound = true
        }
    }
}

enum class AvatarSurface {
    CHAT,
    CONVERSATION,
    CONTACTS,
    PROFILE,
    MOMENTS,
    OTHER
}
