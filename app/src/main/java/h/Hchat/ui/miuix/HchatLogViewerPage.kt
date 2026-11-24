package h.Hchat.ui.miuix

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.StartupTimer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.clickable
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 模块运行日志页。
 *
 * 日志通过 [HLog] 的原子快照读取，新增记录仅在采集开启时接收。
 * 已采集记录在后台持久化，同一宿主目录内已初始化的写入端可汇总；
 * 页面支持关闭后查看、重启后读取以及导出文本文件。
 */
@Composable
internal fun HchatLogViewerPage(context: Context, onBack: () -> Unit) {
    val prefs = remember { HchatStorage.preferences(context, HLog.PREFS_NAME) }
    var enabled by remember {
        mutableStateOf(prefs.getBoolean(HLog.KEY_CAPTURE_ENABLED, false))
    }
    val initialSnapshot = remember { HLog.captureSnapshotState() }
    var lines by remember { mutableStateOf(initialSnapshot.lines) }
    var revision by remember { mutableStateOf(initialSnapshot.revision) }
    var followLatest by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var startupReport by remember { mutableStateOf(StartupTimer.lastReport()) }

    // 从别的页面改过开关再回到这里时，重新对齐一次状态。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { HLog.initCapture(context); HLog.flushCapture() }
        enabled = HLog.isCaptureEnabled()
        val snapshot = HLog.captureSnapshotState()
        lines = snapshot.lines
        revision = snapshot.revision
        startupReport = StartupTimer.lastReport()
    }

    // 轻量轮询：只有版本号变化时才会真正取一次快照。
    LaunchedEffect(Unit) {
        while (true) {
            delay(POLL_INTERVAL_MS)
            enabled = HLog.isCaptureEnabled()
            captureError = HLog.captureError()
            val current = HLog.captureRevision()
            if (current != revision) {
                // 拷贝最多 2000 条并持锁，放到默认调度器上做，避免卡住主线程。
                val snapshot = withContext(Dispatchers.Default) { HLog.captureSnapshotState() }
                lines = snapshot.lines
                revision = snapshot.revision
            }
        }
    }

    val scroll = MiuixScrollBehavior()
    val listState = rememberLazyListState()

    // 默认保留操作区；仅在用户明确开启跟随后移动，索引包含列表前置卡片。
    LaunchedEffect(lines, followLatest) {
        if (followLatest && lines.isNotEmpty()) {
            val prefixItems = if (startupReport.isNotBlank()) 4 else 3
            listState.scrollToItem(prefixItems + lines.lastIndex)
        }
    }

    PageScaffold(
        title = "运行日志",
        largeTitle = "运行日志",
        scrollBehavior = scroll,
        bottomBar = {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Text("日志操作", modifier = Modifier.clickable {
                        followLatest = false
                        scope.launch { listState.scrollToItem(0) }
                    }.padding(12.dp))
                    Text("导出日志", modifier = Modifier.clickable {
                        scope.launch { exportRuntimeLog(context) }
                    }.padding(12.dp))
                }
                BottomActionBar("返回", onBack)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "日志记录") }
            if (startupReport.isNotBlank()) {
                item {
                    SettingsCard {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                text = "本次冷启动耗时（模块初始化各阶段）",
                                color = MiuixTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                            startupReport.split('\n').forEach { line ->
                                Text(
                                    text = line,
                                    modifier = Modifier.padding(top = 4.dp),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                            Text(
                                text = "「本段」最长的一项就是瓶颈。想测这个需要先开「记录运行日志」，然后完全杀掉微信重开一次。",
                                modifier = Modifier.padding(top = 8.dp),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = "记录运行日志",
                        summary = "默认关闭。共享同一宿主数据目录的进程合并采集，最多 $MAX_ENTRIES 条 / 2 MiB，重启保留", 
                        onCheckedChange = { value ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    HLog.setCaptureEnabled(value)
                                    HLog.flushCapture()
                                    if (HLog.captureError().isEmpty()) {
                                        prefs.edit().putBoolean(HLog.KEY_CAPTURE_ENABLED, value).apply()
                                    }
                                }
                                enabled = HLog.isCaptureEnabled()
                                captureError = HLog.captureError()
                                val snapshot = HLog.captureSnapshotState()
                                lines = snapshot.lines
                                revision = snapshot.revision
                                // 这里很容易误触：看着"已开启"点一下以为在确认，实际是关掉，
                                // 之后整轮排查都是空的。明确回一次提示。
                                Toast.makeText(
                                    context,
                                    if (enabled) "日志采集已开启" else "日志采集已关闭，之后不再记录新日志",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                    InsetDivider()
                    LogStatusRow(
                        title = "采集开关",
                        value = if (enabled) "已开启" else "已关闭",
                        ok = enabled
                    )
                    InsetDivider()
                    LogStatusRow(
                        title = "当前缓冲",
                        value = "${lines.size} / $MAX_ENTRIES 条",
                        ok = lines.isNotEmpty()
                    )
                    InsetDivider()
                    LogStatusRow(
                        title = "进程",
                        value = "PID ${android.os.Process.myPid()}",
                        ok = true
                    )
                    InsetDivider()
                    ActionRow(
                        title = "写入测试日志",
                        summary = "写入后跳到测试记录；其他进程和功能命中仍需实际触发验证",
                        onClick = {
                            if (!HLog.isCaptureEnabled()) {
                                Toast.makeText(context, "请先开启「记录运行日志」", Toast.LENGTH_SHORT).show()
                            } else {
                                HLog.e("[Hchat:Log] 测试日志 时间=" + System.currentTimeMillis())
                                val snapshot = HLog.captureSnapshotState()
                                lines = snapshot.lines
                                revision = snapshot.revision
                                followLatest = true
                            }
                        }
                    )
                    InsetDivider()
                    SwitchRow(
                        checked = followLatest,
                        title = "跟随最新日志",
                        summary = "默认关闭；底部「日志操作」可随时回到操作区",
                        onCheckedChange = { followLatest = it }
                    )
                    if (captureError.isNotEmpty()) {
                        LogStatusRow("采集失败", captureError, false)
                    }
                    InsetDivider()
                    ActionRow(
                        title = "复制全部日志",
                        summary = if (lines.isEmpty()) "暂无日志" else "已捕获 ${lines.size} 条",
                        onClick = {
                            scope.launch {
                                val text = withContext(Dispatchers.IO) {
                                    HLog.flushCapture()
                                    HLog.captureText()
                                }
                                val message = when {
                                    text.isBlank() -> "暂无日志"
                                    text.length > 128_000 -> "日志较多，请使用底部「导出日志」"
                                    else -> runCatching {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                            as? android.content.ClipboardManager
                                            ?: error("剪贴板不可用")
                                        clipboard.setPrimaryClip(ClipData.newPlainText("HchatLog", text))
                                        "已复制日志"
                                    }.getOrElse { "复制失败，请导出日志" }
                                }
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    InsetDivider()
                    ActionRow(
                        title = "清空日志",
                        summary = "清空共享采集记录，不改变开关状态",
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { HLog.clearCapture(); HLog.flushCapture() }
                                val snapshot = HLog.captureSnapshotState()
                                lines = snapshot.lines
                                revision = snapshot.revision
                                captureError = HLog.captureError()
                                Toast.makeText(context, if (captureError.isEmpty()) "已清空" else "清空失败：$captureError", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }

            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "日志内容") }
            if (lines.isEmpty()) {
                item {
                    SettingsCard {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (enabled) "还没有日志。先用上面的「写入测试日志」确认采集管线，再触发一次相关操作。" else "日志记录未开启。",
                                color = MiuixTheme.colorScheme.onSurface,
                                fontSize = 14.sp
                            )
                            Text(
                                text = if (enabled) {
                                    "采集按约 1.2 秒同步；隔离进程或不同宿主数据目录不能合并。"
                                } else {
                                    "打开上面的开关后，新产生的日志才会进入缓冲；开启前的日志无法补录。"
                                },
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            } else {
                items(count = lines.size) { index ->
                    Text(
                        text = lines[index],
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        color = MiuixTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

private fun findLogActivity(context: Context): Activity? {
    var current = context
    repeat(16) {
        val candidate = current
        if (candidate is Activity) return candidate
        val base = (candidate as? ContextWrapper)?.baseContext ?: return null
        if (base === candidate) return null
        current = base
    }
    return null
}

private suspend fun exportRuntimeLog(context: Context) {
    val activity = findLogActivity(context)
    if (activity == null) {
        Toast.makeText(context, "当前页面无法打开文件选择器", Toast.LENGTH_SHORT).show()
        return
    }
    val text = withContext(Dispatchers.IO) {
        HLog.flushCapture()
        "Hchat ${h.Hchat.BuildConfig.VERSION_NAME}\n采集状态：${HLog.captureError().ifEmpty { "正常" }}\n" + HLog.captureText()
    }
    val app = context.applicationContext ?: context
    ScriptPluginDocumentBridge.launchExport(activity, "Hchat-log-${System.currentTimeMillis()}.txt", "text/plain") { uri ->
        // 文件选择回调只持有应用 Context 和快照，离开页面后仍能完成已确认的导出。
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                val output = app.contentResolver.openOutputStream(uri, "wt") ?: error("无法打开输出文件")
                output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(app, if (result.isSuccess) "日志已导出" else "导出失败：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

private val MAX_ENTRIES = HLog.MAX_CAPTURE_ENTRIES
private const val POLL_INTERVAL_MS = 1200L

/**
 * 一行状态：左边标题，右边值。
 *
 * 「看不到日志」最常见的两种原因是开关没开、或者看的不是同一个进程，
 * 把这两件事直接写在页面上，用户不用猜，我也不用远程排查。
 */
@Composable
private fun LogStatusRow(title: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = title,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 13.sp
        )
        Text(
            text = value,
            color = if (ok) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp
        )
    }
}
