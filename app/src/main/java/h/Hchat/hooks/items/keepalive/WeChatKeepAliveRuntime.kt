package h.Hchat.hooks.items.keepalive

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import h.Hchat.BuildConfig
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import java.util.concurrent.TimeUnit

object WeChatKeepAliveRuntime {
    private const val TAG = "[Hchat:KeepAlive]"
    private const val WAKE_LOCK_TAG = "Hchat:WeChatKeepAlive"
    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private var wakeLock: PowerManager.WakeLock? = null
    /** 最近一次成功解析的模块 Context，供 stop() 在调用方没传参时兜底。 */
    @Volatile private var cachedModuleContext: Context? = null
    @Volatile private var rootWhitelistApplied = false
    @Volatile private var rootAppOpsApplied = false

    fun apply(hostContext: Context?, moduleContext: Context? = null) {
        if (hostContext == null) return
        val appContext = hostContext.applicationContext ?: hostContext
        // 模块 Context 必须原样保留：它的 getApplicationContext() 会回落到宿主（微信）的
        // Application，一旦替换掉，后续 startService 就会解析成 com.tencent.mm/... 而失败。
        val serviceContext = moduleContext ?: resolveModuleContext(appContext)
        if (serviceContext != null) cachedModuleContext = serviceContext
        val prefs = HchatStorage.preferences(appContext, WeChatKeepAliveSettings.PREFS_NAME)
        val enabled = prefs.getBoolean(WeChatKeepAliveSettings.KEY_ENABLE, WeChatKeepAliveSettings.DEFAULT_ENABLE)
        if (!enabled) {
            releaseWakeLock()
            if (serviceContext != null) runCatching { WeChatKeepAliveService.stop(serviceContext) }
            return
        }
        if (prefs.getBoolean(WeChatKeepAliveSettings.KEY_WAKE_LOCK, WeChatKeepAliveSettings.DEFAULT_WAKE_LOCK)) {
            acquireWakeLock(appContext)
        } else {
            releaseWakeLock()
        }
        if (prefs.getBoolean(WeChatKeepAliveSettings.KEY_FOREGROUND_SERVICE, WeChatKeepAliveSettings.DEFAULT_FOREGROUND_SERVICE)) {
            if (serviceContext != null) {
                val watchdog = prefs.getBoolean(WeChatKeepAliveSettings.KEY_WATCHDOG, WeChatKeepAliveSettings.DEFAULT_WATCHDOG)
                val networkHeartbeat = prefs.getBoolean(
                    WeChatKeepAliveSettings.KEY_NETWORK_HEARTBEAT,
                    WeChatKeepAliveSettings.DEFAULT_NETWORK_HEARTBEAT
                )
                runCatching { WeChatKeepAliveService.start(serviceContext, watchdog, networkHeartbeat) }
                    .onFailure { HLog.e("$TAG 启动前台服务失败: ${it.message}", it) }
            } else {
                HLog.e("$TAG 模块 Context 不可用，跳过前台服务")
            }
        } else {
            if (serviceContext != null) runCatching { WeChatKeepAliveService.stop(serviceContext) }
        }
        if (prefs.getBoolean(WeChatKeepAliveSettings.KEY_ROOT_DOZE_WHITELIST, WeChatKeepAliveSettings.DEFAULT_ROOT_DOZE_WHITELIST)) {
            applyRootDozeWhitelistOnce()
        }
        if (prefs.getBoolean(WeChatKeepAliveSettings.KEY_ROOT_APP_OPS, WeChatKeepAliveSettings.DEFAULT_ROOT_APP_OPS)) {
            applyRootAppOpsOnce()
        }
    }

    fun stop(moduleContext: Context? = null) {
        releaseWakeLock()
        // 调用方不传参时不能就此什么都不做——原来会留下"关了开关服务还在跑"。
        // 用 apply() 缓存下来的模块 Context 兜底。
        val serviceContext = moduleContext ?: cachedModuleContext
        if (serviceContext != null) {
            runCatching { WeChatKeepAliveService.stop(serviceContext) }
        }
        cachedModuleContext = null
    }

    fun isIgnoringBatteryOptimizations(context: Context?): Boolean {
        if (context == null) return false
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(WECHAT_PACKAGE) == true
        }.getOrDefault(false)
    }

    fun watchdogTick(context: Context?, enabled: Boolean) {
        if (context == null) return
        if (!enabled) return
        val appContext = context.applicationContext ?: context
        // 这段原来直接跑在 Service 的主线程上：pidof / su 都会 fork 子进程再 waitFor，
        // 主线程被 fork + wait 占住就是一次 ANR（6.9.3 那份系统 trace 里主线程正好卡在
        // do_wait，即 wait4 等子进程）。看门狗本来就不需要"立刻"完成，挪到后台线程。
        runCatching {
            watchdogExecutor.execute { runCatching { launchWeChatIfNeeded(appContext) } }
        }.onFailure {
            HLog.e("$TAG 看门狗调度失败: ${it.message}", it)
        }
    }

    fun heartbeatTick(enabled: Boolean) {
        if (!enabled) return
        // 复用看门狗的单线程执行器：每 60 秒 new 一个线程既浪费又增加调度开销。
        // 若队列积压（上一次心跳还没跑完），说明网络很差，本次直接跳过即可。
        runCatching {
            watchdogExecutor.execute {
                var connection: java.net.HttpURLConnection? = null
                try {
                    connection = java.net.URL(HEARTBEAT_URL).openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = HEARTBEAT_TIMEOUT_MS
                    connection.readTimeout = HEARTBEAT_TIMEOUT_MS
                    connection.useCaches = false
                    connection.requestMethod = "GET"
                    connection.responseCode
                } catch (t: Throwable) {
                    HLog.e("$TAG 网络心跳失败: ${t.message}")
                } finally {
                    // 失败路径也要 disconnect：原来异常时 socket 既不入池也不关闭。
                    runCatching { connection?.disconnect() }
                }
            }
        }.onFailure {
            HLog.e("$TAG 网络心跳调度失败: ${it.message}", it)
        }
    }

    private fun acquireWakeLock(context: Context) {
        val current = wakeLock
        if (current?.isHeld == true) return
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                // 兜底超时：如果 stop() 因 Context 解析失败等被吞掉而没走到 release，
                // 无限期持有的 PARTIAL_WAKE_LOCK 会阻止 CPU 深度休眠，直接变成整机耗电。
                acquire(TimeUnit.HOURS.toMillis(12))
            }
            wakeLock = lock
        }.onFailure {
            HLog.e("$TAG 获取 WakeLock 失败: ${it.message}", it)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }.onFailure {
            HLog.e("$TAG 释放 WakeLock 失败: ${it.message}", it)
        }
        wakeLock = null
    }

    private fun applyRootDozeWhitelistOnce() {
        if (rootWhitelistApplied) return
        rootWhitelistApplied = true
        Thread {
            val result = runRootCommand("cmd deviceidle whitelist +com.tencent.mm")
            if (!result) {
                HLog.e("$TAG Root Doze 白名单执行失败")
            }
        }.apply {
            name = "Hchat-KeepAlive-RootWhitelist"
            isDaemon = true
        }.start()
    }

    private fun applyRootAppOpsOnce() {
        if (rootAppOpsApplied) return
        rootAppOpsApplied = true
        Thread {
            val commands = listOf(
                "cmd appops set $WECHAT_PACKAGE RUN_ANY_IN_BACKGROUND allow",
                "cmd appops set $WECHAT_PACKAGE RUN_IN_BACKGROUND allow",
                "cmd appops set $WECHAT_PACKAGE WAKE_LOCK allow",
                "cmd appops set $WECHAT_PACKAGE START_FOREGROUND allow"
            )
            val ok = commands.all { runRootCommand(it) }
            if (!ok) {
                HLog.e("$TAG Root AppOps 放行执行不完整")
            }
        }.apply {
            name = "Hchat-KeepAlive-RootAppOps"
            isDaemon = true
        }.start()
    }

    private fun resolveModuleContext(context: Context): Context? {
        return runCatching {
            context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull()
    }

    private fun runRootCommand(command: String): Boolean {
        return runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            // su 可能等 Magisk 授权弹窗，没有超时就会一直挂着这个后台线程。
            if (!process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching false
            }
            process.exitValue() == 0
        }.getOrDefault(false)
    }

    private fun launchWeChatIfNeeded(context: Context) {
        runCatching {
            if (isWeChatProcessAlive()) return
            val manager = context.packageManager
            val intent = manager.getLaunchIntentForPackage(WECHAT_PACKAGE) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure {
            HLog.e("$TAG 看门狗拉起微信失败: ${it.message}", it)
        }
    }

    private fun isWeChatProcessAlive(): Boolean {
        return runCatching {
            // 直接用 pidof，不套一层 sh：少 fork 一个进程，也少一次 shell 解析。
            val process = ProcessBuilder("/system/bin/pidof", WECHAT_PACKAGE)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                // 查不出来就当它活着：宁可少拉一次，也不要把正在用的微信重新顶到前台。
                return@runCatching true
            }
            process.exitValue() == 0
        }.getOrDefault(true)
    }

    private const val HEARTBEAT_URL = "https://connectivitycheck.gstatic.com/generate_204"
    private const val HEARTBEAT_TIMEOUT_MS = 3000
    private const val PROCESS_TIMEOUT_SECONDS = 3L
    private const val ROOT_TIMEOUT_SECONDS = 8L

    /** 看门狗专用单线程：常驻但只在每个 tick 干一次活，不占用主线程。 */
    private val watchdogExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Hchat-KeepAlive-Watchdog").apply { isDaemon = true }
    }
}
