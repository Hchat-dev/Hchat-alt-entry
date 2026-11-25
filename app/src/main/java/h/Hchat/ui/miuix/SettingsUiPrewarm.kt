package h.Hchat.ui.miuix

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 设置页首开卡顿的主要来源之一：模块界面是跑在微信进程里的 ComposeView，
 * 用户第一次打开设置页时，几百个 Compose / miuix / foundation 类要在主线程
 * 上完成「加载 + 校验」，微信进程通常有十几万个类，这个过程可感知（300ms+）。
 *
 * 类加载本身是线程安全的，把这一步挪到模块安装完成后的后台低优先级线程，
 * 用户之后首次打开设置页时这些类已经在进程内，只剩真正的组合计算。
 * 全部用 initialize=false：只做加载与链接，不触发任何静态初始化器，
 * 不会与主线程上的对象初始化顺序产生冲突，失败也完全无感（捕获后放弃）。
 */
internal object SettingsUiPrewarm {
    private val started = AtomicBoolean(false)

    private val CLASS_NAMES = arrayOf(
        // runtime / ui 核心
        "androidx.compose.runtime.Recomposer",
        "androidx.compose.runtime.ComposerImpl",
        "androidx.compose.runtime.CompositionImpl",
        "androidx.compose.runtime.slottable.SlotTable",
        "androidx.compose.runtime.snapshots.Snapshot",
        "androidx.compose.runtime.CompositionLocalKt",
        "androidx.compose.ui.platform.ComposeView",
        "androidx.compose.ui.platform.AndroidComposeView",
        "androidx.compose.ui.platform.AbstractComposeView",
        "androidx.compose.ui.platform.CompositionContextKt",
        "androidx.compose.ui.node.LayoutNode",
        "androidx.compose.ui.node.MeasurePass",
        "androidx.compose.ui.layout.MeasurePolicy",
        "androidx.compose.ui.draw.CacheDrawScopeKt",
        "androidx.compose.ui.graphics.Canvas",
        "androidx.compose.ui.unit.DensityKt",
        "androidx.compose.ui.input.pointer.PointerInputModifierKt",
        // foundation（LazyColumn / Pager / 布局原语）
        "androidx.compose.foundation.layout.RowKt",
        "androidx.compose.foundation.layout.ColumnKt",
        "androidx.compose.foundation.layout.BoxKt",
        "androidx.compose.foundation.layout.PaddingKt",
        "androidx.compose.foundation.layout.SizeKt",
        "androidx.compose.foundation.lazy.LazyListKt",
        "androidx.compose.foundation.lazy.layout.LazyLayoutKt",
        "androidx.compose.foundation.lazy.grid.LazyGridKt",
        "androidx.compose.foundation.pager.PagerKt",
        "androidx.compose.foundation.gestures.DragGestureDetectorKt",
        "androidx.compose.foundation.gestures.ScrollableKt",
        "androidx.compose.foundation.gestures.TapGestureDetectorKt",
        "androidx.compose.foundation.background.BackgroundKt",
        "androidx.compose.foundation.shape.RoundedCornerShape",
        "androidx.compose.animation.AnimatedContentKt",
        "androidx.compose.animation.core.AnimatableKt",
        "androidx.compose.animation.core.AnimationsKt",
        "androidx.compose.animation.CrossfadeKt",
        "androidx.compose.ui.platform.AndroidCompositionLocals_androidKt",
        // miuix 组件与主题
        "top.yukonga.miuix.kmp.basic.LazyColumnKt",
        "top.yukonga.miuix.kmp.basic.TextKt",
        "top.yukonga.miuix.kmp.basic.CardKt",
        "top.yukonga.miuix.kmp.basic.ScaffoldKt",
        "top.yukonga.miuix.kmp.theme.MiuixThemeKt",
        "top.yukonga.miuix.kmp.theme.ColorsKt",
        "top.yukonga.miuix.kmp.basic.SmallTitleKt",
        "top.yukonga.miuix.kmp.basic.SwitchRowKt"
    )

    fun prewarm(classLoader: ClassLoader) {
        if (!started.compareAndSet(false, true)) return
        val worker = Thread {
            // 最低优先级：预热永远不该和微信自己的后台任务抢 CPU。
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST)
            for (name in CLASS_NAMES) {
                try {
                    Class.forName(name, false, classLoader)
                } catch (_: Throwable) {
                    // 类名随依赖版本变化：单个失败无所谓，继续加载其余的。
                }
            }
        }
        worker.name = "Hchat-SettingsUiPrewarm"
        worker.isDaemon = true
        worker.start()
    }
}
