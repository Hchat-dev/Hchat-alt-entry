package h.Hchat.hooks.items.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import h.Hchat.BuildConfig
import h.Hchat.R
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog

class WeChatKeepAliveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var watchdogEnabled = false
    private var networkHeartbeatEnabled = false
    private val keepAliveTask = object : Runnable {
        override fun run() {
            val watchdog = watchdogEnabled
            val heartbeat = networkHeartbeatEnabled
            // 两个子功能都关闭时不再续期：原来即使什么都不做，也会每 60 秒
            // 唤醒一次主线程 Handler，白白阻止 CPU 进入低功耗状态。
            if (!watchdog && !heartbeat) return
            WeChatKeepAliveRuntime.watchdogTick(this@WeChatKeepAliveService, watchdog)
            WeChatKeepAliveRuntime.heartbeatTick(heartbeat)
            // 息屏时放宽到 5 分钟：保活的目的是别让微信死透，不需要在无人观看时
            // 每分钟唤醒一次 CPU 和无线电；亮屏立即恢复 60 秒粒度。
            handler.postDelayed(this, currentIntervalMs())
        }
    }

    private fun currentIntervalMs(): Long {
        val interactive = runCatching {
            (getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isInteractive
        }.getOrNull() ?: true
        return if (interactive) WATCHDOG_INTERVAL_MS else IDLE_INTERVAL_MS
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系统按 START_STICKY 重建服务时 intent 为 null。原实现在这条路径上把
        // 两个开关都读成 false，于是前台通知照常显示、看门狗和心跳却永久停摆，
        // 用户以为保活生效其实什么都没做。这里在 extra 缺失时回读持久化配置。
        val prefs = HchatStorage.preferences(this, WeChatKeepAliveSettings.PREFS_NAME)
        watchdogEnabled = if (intent != null && intent.hasExtra(EXTRA_WATCHDOG)) {
            intent.getBooleanExtra(EXTRA_WATCHDOG, false)
        } else {
            prefs.getBoolean(
                WeChatKeepAliveSettings.KEY_WATCHDOG,
                WeChatKeepAliveSettings.DEFAULT_WATCHDOG
            )
        }
        networkHeartbeatEnabled = if (intent != null && intent.hasExtra(EXTRA_NETWORK_HEARTBEAT)) {
            intent.getBooleanExtra(EXTRA_NETWORK_HEARTBEAT, false)
        } else {
            prefs.getBoolean(
                WeChatKeepAliveSettings.KEY_NETWORK_HEARTBEAT,
                WeChatKeepAliveSettings.DEFAULT_NETWORK_HEARTBEAT
            )
        }
        return runCatching {
            startForeground(NOTIFICATION_ID, buildNotification())
            handler.removeCallbacks(keepAliveTask)
            // 没有启用任何周期性动作时不要启动轮询，避免空转唤醒。
            if (watchdogEnabled || networkHeartbeatEnabled) {
                handler.postDelayed(keepAliveTask, WATCHDOG_INTERVAL_MS)
            }
            START_STICKY
        }.onFailure {
            HLog.e("$TAG 启动前台保活服务失败: ${it.message}", it)
            // 服务已被 startForegroundService 拉起，却没有成功进入前台状态：
            // 系统会在超时后抛 ForegroundServiceDidNotStartInTimeException 杀掉进程。
            // 主动收尾，避免把模块进程一起带走。
            runCatching {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.getOrDefault(START_NOT_STICKY)
    }

    override fun onDestroy() {
        handler.removeCallbacks(keepAliveTask)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Hchat 微信保活", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager?.createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Hchat 微信强保活")
            .setContentText("正在尝试保持微信息屏运行")
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val TAG = "[Hchat:KeepAliveService]"
        private const val CHANNEL_ID = "hchat_wechat_keep_alive"
        private const val NOTIFICATION_ID = 520134
        private const val WATCHDOG_INTERVAL_MS = 60_000L
        private const val IDLE_INTERVAL_MS = 5 * 60_000L
        private const val EXTRA_WATCHDOG = "h.Hchat.extra.WATCHDOG"
        private const val EXTRA_NETWORK_HEARTBEAT = "h.Hchat.extra.NETWORK_HEARTBEAT"

        fun start(context: Context, watchdog: Boolean, networkHeartbeat: Boolean) {
            // 绝对不能在这里取 getApplicationContext()。
            // LSPosed 里模块的 package context 没有自己的 Application，
            // getApplicationContext() 会回落到宿主（微信）的 Application，
            // 于是 Intent 被解析成 com.tencent.mm/h.Hchat...WeChatKeepAliveService，
            // 系统直接报 "Unable to start service ... not found"（线上抓到的正是这条），
            // 保活功能因此一直静默失效。显式用模块包名构造组件，绕开这条回落。
            val intent = Intent().apply {
                setClassName(BuildConfig.APPLICATION_ID, WeChatKeepAliveService::class.java.name)
                putExtra(EXTRA_WATCHDOG, watchdog)
                putExtra(EXTRA_NETWORK_HEARTBEAT, networkHeartbeat)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent().apply {
                setClassName(BuildConfig.APPLICATION_ID, WeChatKeepAliveService::class.java.name)
            }
            context.stopService(intent)
        }
    }
}
