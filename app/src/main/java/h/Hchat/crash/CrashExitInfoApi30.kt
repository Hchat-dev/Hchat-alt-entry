package h.Hchat.crash

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.abs

internal data class SystemExitRecord(
    val timestamp: Long,
    val details: String
)

@TargetApi(Build.VERSION_CODES.R)
internal object CrashExitInfoApi30 {
    private const val SYSTEM_TRACE_LIMIT = 768 * 1024
    private const val EXIT_MATCH_WINDOW_MS = 5 * 60 * 1000L
    private val WHITESPACE = Regex("\\s+")

    fun findNativeExit(
        context: Application,
        previousLaunch: Long,
        lastHandledTimestamp: Long,
        capturedAt: Long,
        capturedPid: Int
    ): SystemExitRecord? {
        return findExit(
            context = context,
            reason = ApplicationExitInfo.REASON_CRASH_NATIVE,
            previousLaunch = previousLaunch,
            lastHandledTimestamp = lastHandledTimestamp,
            capturedAt = capturedAt,
            capturedPid = capturedPid
        )
    }

    fun findAnrExit(
        context: Application,
        previousLaunch: Long,
        lastHandledTimestamp: Long
    ): SystemExitRecord? {
        return findExit(
            context = context,
            reason = ApplicationExitInfo.REASON_ANR,
            previousLaunch = previousLaunch,
            lastHandledTimestamp = lastHandledTimestamp,
            capturedAt = 0L,
            capturedPid = 0
        )
    }

    private fun findExit(
        context: Application,
        reason: Int,
        previousLaunch: Long,
        lastHandledTimestamp: Long,
        capturedAt: Long,
        capturedPid: Int
    ): SystemExitRecord? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val candidates = manager.getHistoricalProcessExitReasons(context.packageName, 0, 32)
            .asSequence()
            .filter { it.reason == reason }
            .filter { capturedPid <= 0 || it.pid == capturedPid }
            .filter {
                capturedPid > 0 || it.processName.isNullOrBlank() ||
                    it.processName == context.packageName
            }
            .filter { it.timestamp > lastHandledTimestamp }
            .toList()
        val info = when {
            capturedAt > 0L -> candidates
                .filter { abs(it.timestamp - capturedAt) <= EXIT_MATCH_WINDOW_MS }
                .maxByOrNull { it.timestamp }
                ?: candidates
                    .filter { previousLaunch <= 0L || it.timestamp >= previousLaunch }
                    .maxByOrNull { it.timestamp }
            previousLaunch > 0L -> candidates
                .filter { it.timestamp >= previousLaunch }
                .maxByOrNull { it.timestamp }
            else -> null
        } ?: return null
        return SystemExitRecord(info.timestamp, formatExitInfo(info))
    }

    private fun formatExitInfo(info: ApplicationExitInfo): String = buildString {
        appendLine("进程: ${info.processName.orEmpty()}")
        appendLine("PID: ${info.pid}")
        appendLine("UID: real=${info.realUid}, package=${info.packageUid}")
        appendLine("原因: ${reasonName(info.reason)} (${info.reason})")
        info.description.orEmpty().takeIf { it.isNotBlank() }?.let { appendLine("说明: $it") }
        appendLine("状态: ${info.status}")
        appendLine("重要性: ${info.importance}")
        appendLine("PSS/RSS: ${info.pss} KB / ${info.rss} KB")
        val isAnr = info.reason == ApplicationExitInfo.REASON_ANR
        val traceName = if (isAnr) {
            "系统 ANR Trace"
        } else {
            "系统 Tombstone"
        }
        val trace = readSystemTrace(info, traceName)
        if (trace != null) {
            appendLine()
            appendLine("--- $traceName ---")
            append(trace)
        } else {
            appendLine("$traceName: 当前系统未提供")
        }
        if (isAnr) {
            appendLine()
            appendLine("--- 主线程状态解读 ---")
            append(diagnoseAnr(info, trace))
        }
    }.trimEnd()

    /**
     * 把系统给的 Waiting Channels 摘要翻译成能读的结论。
     *
     * Android 11+ 存进 ApplicationExitInfo 的 ANR trace 只有这一段（内核等待队列名），
     * 没有 Java 堆栈。虽然定位不到具体行号，但"主线程卡在哪个子系统"是可以判定的——
     * do_wait 指向 exec/子进程、futex 指向锁竞争、binder 指向跨进程调用，三者排查方向完全不同。
     */
    private fun diagnoseAnr(info: ApplicationExitInfo, trace: String?): String = buildString {
        val channels = ArrayList<Pair<Int, String>>()
        trace?.lineSequence()?.forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("sysTid=")) return@forEach
            val parts = trimmed.split(WHITESPACE, limit = 3)
            val tid = parts.getOrNull(0)?.removePrefix("sysTid=")?.toIntOrNull() ?: return@forEach
            channels += tid to parts.getOrNull(1).orEmpty()
        }
        if (channels.isEmpty()) {
            appendLine("系统没有提供逐线程等待通道，无法离线判断主线程状态。")
            appendLine("（这也是需要 Hchat 自己在线采样主线程堆栈的原因，见崩溃报告里的卡顿采样段。）")
            return@buildString
        }
        val futex = channels.count { it.second.contains("futex") }
        val binder = channels.count { it.second.contains("binder") }
        appendLine("线程总数: ${channels.size}（futex 等待 $futex，binder 等待 $binder）")
        val main = channels.firstOrNull { it.first == info.pid }
        if (main == null) {
            appendLine("未找到 pid=${info.pid} 的主线程记录：dump 之前主线程可能已经不在。")
        } else {
            appendLine("主线程(tid=${main.first}) 等待通道: ${main.second}")
            appendLine("判定: ${explainChannel(main.second)}")
        }
        val rssMb = info.rss / 1024
        if (rssMb >= 800) {
            appendLine(
                "内存: RSS $rssMb MB 偏大。进程越大，主线程一次 fork/exec 复制页表的代价越高，" +
                    "同样一条外部命令在小进程里几十毫秒，在这种进程里可能放大到几百毫秒。"
            )
        }
    }

    private fun explainChannel(channel: String): String = when {
        channel.startsWith("do_wait") ->
            "主线程在 waitpid 等子进程结束。最常见来源是主线程执行外部命令后 waitFor()" +
                "（Runtime.exec / ProcessBuilder），其次才是宿主自己 fork 的子进程。" +
                "排查方向：谁在主线程上跑 shell / su。"
        channel.contains("futex") ->
            "主线程在 futex 上等锁（Java 监视器或 native 互斥量）。指向锁竞争或死锁，" +
                "常见于主线程等一个被后台线程长期持有的锁。"
        channel.contains("binder") ->
            "主线程在等 binder 回包，说明一次跨进程调用被对端拖住了（系统服务或别的应用）。"
        channel.contains("epoll") || channel.contains("poll") ->
            "主线程在事件循环里等消息，属于正常空闲态；如果此时仍被判 ANR，" +
                "说明它连消息都收不到，方向应查 CPU 抢占或整机卡死。"
        channel.contains("pipe") -> "主线程在同步等管道数据（同步 IO）。"
        channel.contains("read") || channel.contains("write") ->
            "主线程在做同步文件 IO（读写磁盘或 socket）。"
        channel.contains("pause") || channel.contains("wait") ->
            "主线程在等信号或条件变量。"
        else -> "不是典型模式，需要结合 Java 堆栈判断。"
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "Java 崩溃"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native 崩溃"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖进程退出"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源使用过量"
        ApplicationExitInfo.REASON_EXIT_SELF -> "进程自行退出"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初始化失败"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "低内存"
        ApplicationExitInfo.REASON_OTHER -> "其他"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变化"
        ApplicationExitInfo.REASON_SIGNALED -> "收到信号"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "用户请求退出"
        else -> "未知"
    }

    private fun readSystemTrace(info: ApplicationExitInfo, traceName: String): String? {
        return runCatching {
            info.traceInputStream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (total < SYSTEM_TRACE_LIMIT) {
                    val read = input.read(buffer, 0, minOf(buffer.size, SYSTEM_TRACE_LIMIT - total))
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    total += read
                }
                val bytes = output.toByteArray()
                when {
                    bytes.isEmpty() -> null
                    !isMostlyText(bytes) ->
                        "系统返回了 ${bytes.size} 字节二进制 $traceName，无法直接显示文本。"
                    else -> String(bytes, StandardCharsets.UTF_8).trimEnd() +
                        if (total >= SYSTEM_TRACE_LIMIT) "\n[$traceName 超过保存上限]" else ""
                }
            }
        }.getOrNull()
    }

    private fun isMostlyText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        var printable = 0
        val sampled = minOf(bytes.size, 4096)
        for (index in 0 until sampled) {
            val value = bytes[index].toInt() and 0xff
            if (value == 9 || value == 10 || value == 13 || value in 32..126 || value >= 0x80) {
                printable++
            }
        }
        return printable * 100 / sampled >= 85
    }
}
