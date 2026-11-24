package h.Hchat.crash

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import h.Hchat.utils.HLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 主线程卡顿在线采样器（运行在微信进程内）。
 *
 * 为什么需要它：Android 11+ 的 [android.app.ApplicationExitInfo] 对 ANR 只保留了
 * "Waiting Channels" 摘要（sysTid + 内核等待队列名），**不含 Java 堆栈**。
 * 事后拿到的"系统 ANR Trace"能证明主线程卡在 do_wait / futex，却永远说不出是哪段代码
 * —— 6.9.3 那份报告就卡在这一步，只能靠猜。
 *
 * 换一个思路：不指望系统给栈，进程还活着的时候自己抓。
 *  1. 每约 10 秒向主线程 post 一个空消息（无分配、无反射、不碰 WakeLock）；
 *  2. 3 秒内没被执行 → 主线程被占用 ≥3 秒，立刻在独立线程抓它的 Java 堆栈；
 *  3. 连同最近若干条 Hchat 日志写进 `crash/main_thread_stall.log`。进程随后被 ANR
 *     杀掉也没关系，证据已经落盘，下次启动的 ANR 报告会把这段挂上去。
 *
 * 成本：正常状态下采样线程每 10 秒醒来两次（一次发包、一次判定），只在真卡顿时抓栈。
 * 不持有 WakeLock、不注册 AlarmManager，CPU 占用在测量噪声以下。
 */
internal object MainThreadStallWatchdog {
    private const val TAG = "[Hchat:StallWatchdog]"

    /** 主线程超过这个时长没回 ping 才认为"卡住了"。ANR 判定是 5 秒，3 秒留出抓栈时间。 */
    private const val STALL_THRESHOLD_MS = 3_000L

    /** 健康状态下的空闲间隔：发送 → 等 3 秒 → 再等 7 秒，一轮 10 秒。 */
    private const val IDLE_INTERVAL_MS = 7_000L

    /** 卡顿后轮询主线程恢复的节奏。 */
    private const val RECOVERY_POLL_MS = 500L

    /** 一直不恢复（真 ANR）时最多等这么久，然后继续下一轮。 */
    private const val MAX_RECOVERY_WAIT_MS = 15_000L

    /** 抓栈线程的 join 上限：Thread.getStackTrace 要求目标线程到达安全点，卡死时可能不返回。 */
    private const val CAPTURE_JOIN_MS = 4_000L

    private const val MAX_STACK_FRAMES = 48
    private const val LOGGED_STACK_FRAMES = 12
    private const val BREADCRUMB_LINES = 20
    private const val FILE_NAME = "main_thread_stall.log"
    private const val EVIDENCE_LIMIT = 256 * 1024
    private const val EVIDENCE_DISPLAY_LIMIT = 48 * 1024

    private val started = AtomicBoolean(false)
    private val sequence = AtomicInteger(0)

    /** 显式绑定主 Looper 的 Handler，可在任意线程创建。 */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var lastPongAt = 0L

    @Volatile
    private var evidenceFile: File? = null

    /** 幂等安装；[directory] 传崩溃目录（与 ANR 报告同目录，便于下次启动回收）。 */
    fun install(directory: File) {
        if (!started.compareAndSet(false, true)) return
        try {
            if (!directory.isDirectory) directory.mkdirs()
            evidenceFile = File(directory, FILE_NAME)
            lastPongAt = SystemClock.uptimeMillis()
            Thread(::loop, "Hchat-MainStallWatch").apply { isDaemon = true }.start()
        } catch (error: Throwable) {
            started.set(false)
            HLog.e("$TAG 启动失败: ${error.message}", error)
        }
    }

    private fun loop() {
        while (true) {
            val token = sequence.incrementAndGet()
            val pingAt = SystemClock.uptimeMillis()
            try {
                // 用 post 而不是 sendMessageAtTime：要的是"排到主线程队列尾巴"，
                // 与主线程当前是否被占用无关，排队时间本身就是我们要测的量。
                mainHandler.post {
                    if (token == sequence.get()) lastPongAt = SystemClock.uptimeMillis()
                }
            } catch (_: Throwable) {
                // 主线程 Looper 已经退出（进程在收尾），采样没有继续的意义。
                return
            }
            sleep(STALL_THRESHOLD_MS)
            if (lastPongAt < pingAt) {
                capture(pingAt)
                awaitRecovery(pingAt)
                continue
            }
            sleep(IDLE_INTERVAL_MS)
        }
    }

    private fun awaitRecovery(pingAt: Long) {
        val deadline = SystemClock.uptimeMillis() + MAX_RECOVERY_WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (lastPongAt >= pingAt) return
            sleep(RECOVERY_POLL_MS)
        }
    }

    private fun capture(pingAt: Long) {
        val worker = Thread({ writeEvidence(pingAt) }, "Hchat-MainStallCapture").apply { isDaemon = true }
        try {
            worker.start()
        } catch (error: Throwable) {
            HLog.e("$TAG 抓栈线程启动失败: ${error.message}", error)
            return
        }
        // 绝不让采样线程跟着一起卡死，否则整条链路只采样一次就废了。
        runCatching { worker.join(CAPTURE_JOIN_MS) }
    }

    private fun writeEvidence(pingAt: Long) {
        val mainThread = runCatching { Looper.getMainLooper().thread }.getOrNull() ?: return
        val frames = runCatching { mainThread.stackTrace }.getOrDefault(emptyArray())
        val stallMs = SystemClock.uptimeMillis() - pingAt
        val stack = frames.take(MAX_STACK_FRAMES).joinToString("\n") { "    at $it" }
        val breadcrumbs = runCatching {
            HLog.captureSnapshot().takeLast(BREADCRUMB_LINES).joinToString("\n") { "    $it" }
        }.getOrDefault("")
        val text = buildString {
            appendLine("时间: ${formatTime(System.currentTimeMillis())}")
            appendLine("进程: ${processName()} (pid=${android.os.Process.myPid()})")
            appendLine(
                "主线程: state=${runCatching { mainThread.state }.getOrNull()} " +
                    "未响应 ping ${stallMs}ms（ANR 阈值 input 5s / broadcast 10s / service 20s）"
            )
            appendLine("主线程堆栈:")
            appendLine(stack.ifEmpty { "    <未取得堆栈：目标线程未到达安全点>" })
            if (breadcrumbs.isNotEmpty()) {
                appendLine("最近 Hchat 日志:")
                appendLine(breadcrumbs)
            }
        }.trimEnd()
        appendEvidence(text)
        // 同时写进内存日志：用户导出"运行日志"时立刻可见，不必等进程重启。
        val head = frames.firstOrNull()?.toString() ?: "未取得堆栈"
        HLog.e("$TAG 主线程卡顿 ${stallMs}ms，栈顶: $head")
        if (frames.isNotEmpty()) {
            HLog.e(
                "$TAG 主线程堆栈:\n" +
                    frames.take(LOGGED_STACK_FRAMES).joinToString("\n") { "    at $it" }
            )
        }
    }

    private fun appendEvidence(text: String) {
        val file = evidenceFile ?: return
        runCatching {
            // 单文件封顶，避免连续卡顿把磁盘写爆；超过上限就从头覆盖。
            if (file.length() > EVIDENCE_LIMIT) file.delete()
            file.appendText(text + "\n\n==============================\n\n")
        }.onFailure { HLog.e("$TAG 写入卡顿证据失败: ${it.message}") }
    }

    /**
     * 读取上一个进程留下的卡顿证据。
     *
     * [maxAgeMs] 用来丢弃过期记录：只剩一分钟前的卡顿去解释今天的 ANR 是误导。
     */
    fun readEvidence(directory: File, maxAgeMs: Long): String? {
        return runCatching {
            val file = File(directory, FILE_NAME)
            if (!file.isFile || file.length() == 0L) return null
            if (System.currentTimeMillis() - file.lastModified() > maxAgeMs) return null
            val bytes = file.readBytes()
            val from = (bytes.size - EVIDENCE_DISPLAY_LIMIT).coerceAtLeast(0)
            String(bytes, from, bytes.size - from, Charsets.UTF_8).trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** 证据已经被写进报告后清掉，避免下次启动重复挂同一段。 */
    fun clearEvidence(directory: File) {
        runCatching { File(directory, FILE_NAME).delete() }
    }

    private fun sleep(millis: Long) {
        runCatching { Thread.sleep(millis) }
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp))

    private fun processName(): String = runCatching {
        val bytes = File("/proc/self/cmdline").readBytes()
        val end = bytes.indexOfFirst { it.toInt() == 0 }.let { if (it < 0) bytes.size else it }
        String(bytes, 0, end, Charsets.UTF_8)
    }.getOrDefault("unknown")
}
