package h.Hchat.ui.miuix

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator

/**
 * iOS 推入式（push）页面转场。
 *
 * 模块页面不是 Activity，而是挂在微信 decorView 上的一层 FrameLayout，所以没有
 * 系统 Activity 转场可用，只能自己做。视觉对齐 iOS：
 *
 * - 新页面从右侧整屏滑入；
 * - 下层微信界面同步左移约 30%（视差），并压暗一层；
 * - 关闭时反向：新页面滑出，微信界面回正、压暗消失。
 *
 * 时序上分三段，重点是「先给反馈，再等首帧」：
 *
 * 1. [begin] 立刻挂一层很淡的压暗——此时 ComposeView 还没构造，这是点完按钮后
 *    唯一能立刻画出来的东西，用户不会觉得点了没反应；
 * 2. 调用方同步构造 ComposeView（这一步本身就要一两百毫秒）；
 * 3. [beginEnter] 等 Compose 首帧真正可绘制后再滑入。如果不等首帧，用户会先看到
 *    一块空白滑进来，比不做动画更糟。
 */
internal class SettingsPageTransition private constructor(
    private val host: ViewGroup,
    private val scrim: View,
    private val siblings: List<View>,
    private val siblingStartX: Map<View, Float>,
    private val siblingStartLayer: Map<View, Int>
) {
    private lateinit var page: View
    private var enterAnimator: ValueAnimator? = null
    private var exitAnimator: ValueAnimator? = null
    private var firstFrameView: View? = null
    private var gateListener: ViewTreeObserver.OnPreDrawListener? = null
    private var fallback: Runnable? = null
    private var enterStarted = false
    private var enterCancelled = false
    private var layersApplied = false

    var closing = false
        private set

    private val width: Int
        get() = host.width.takeIf { it > 0 } ?: host.resources.displayMetrics.widthPixels

    /**
     * 页面加入 decor 之前调用：先把页面挪到屏幕外，避免从 addView 到首帧之间闪出一整页。
     */
    fun attachPage(target: View) {
        page = target
        target.translationX = width.toFloat()
    }

    /**
     * 等 Compose 首帧可以绘制后再滑入；超时则直接滑入，避免页面一直停在屏幕外。
     */
    fun beginEnter(content: View) {
        if (!::page.isInitialized) return
        firstFrameView = content
        val start = Runnable { startEnter() }
        fallback = start
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                detachFirstFrameGate()
                // 这一帧已经画出来了，下一帧再开始位移，避免和首帧渲染抢主线程。
                content.post(start)
                return true
            }
        }
        gateListener = listener
        runCatching { content.viewTreeObserver.addOnPreDrawListener(listener) }
            .onFailure { start.run() }
        content.postDelayed(start, FIRST_FRAME_TIMEOUT_MS)
    }

    private fun startEnter() {
        if (enterStarted || closing || !::page.isInitialized) return
        enterStarted = true
        cancelFallback()
        detachFirstFrameGate()
        val w = width.toFloat()
        applyLayers(true)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ENTER_DURATION_MS
            interpolator = EASE_OUT
        }
        animator.addUpdateListener { value ->
            val fraction = value.animatedValue as Float
            page.translationX = w * (1f - fraction)
            val parallaxX = -w * PARALLAX * fraction
            for (sibling in siblings) {
                if (sibling.parent === host) sibling.translationX = parallaxX
            }
            scrim.alpha = IMMEDIATE_SCRIM + (SCRIM_ALPHA - IMMEDIATE_SCRIM) * fraction
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // cancel 时也会走到这里：交给退出动画接管，不要把位移归零。
                if (enterCancelled || exitAnimator != null) return
                // 位移结束后释放硬件层：微信界面是一整棵很重的 View 树，常驻纹理不划算。
                applyLayers(false)
                page.translationX = 0f
                scrim.alpha = SCRIM_ALPHA
            }
        })
        enterAnimator = animator
        animator.start()
    }

    /**
     * 反向退出。
     */
    fun playExit(onDone: () -> Unit) {
        if (closing) return
        if (!::page.isInitialized) {
            // 页面还没绑定就被要求关闭：直接释放，不能吞掉 onDone 让页面留在屏幕上。
            closing = true
            release()
            onDone()
            return
        }
        closing = true
        // 先记住页面当前实际位置：进入动画可能只播到一半，退出要从这里继续，不能跳回起点。
        val startTranslation = page.translationX
        cancelFallback()
        detachFirstFrameGate()
        enterCancelled = true
        enterAnimator?.cancel()
        enterAnimator = null
        val w = width.toFloat()
        val from = if (w > 0f) (startTranslation / w).coerceIn(0f, 1f) else 0f
        applyLayers(true)
        val animator = ValueAnimator.ofFloat(from, 1f).apply {
            duration = EXIT_DURATION_MS
            interpolator = EASE_IN
        }
        animator.addUpdateListener { value ->
            val fraction = value.animatedValue as Float
            page.translationX = w * fraction
            val parallaxX = -w * PARALLAX * (1f - fraction)
            for (sibling in siblings) {
                if (sibling.parent === host) sibling.translationX = parallaxX
            }
            scrim.alpha = SCRIM_ALPHA * (1f - fraction)
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                release()
                onDone()
            }
        })
        exitAnimator = animator
        animator.start()
    }

    /**
     * 立即释放，不带动画。用于异常路径和页面被外部移除。
     */
    fun dispose() {
        closing = true
        cancelFallback()
        detachFirstFrameGate()
        enterAnimator?.cancel()
        exitAnimator?.cancel()
        enterAnimator = null
        exitAnimator = null
        release()
    }

    private fun release() {
        applyLayers(false)
        if (::page.isInitialized) page.translationX = 0f
        for ((sibling, startX) in siblingStartX) {
            if (sibling.parent === host) sibling.translationX = startX
        }
        scrim.animate().cancel()
        if (scrim.parent === host) {
            runCatching { host.removeView(scrim) }
        }
    }

    private fun applyLayers(on: Boolean) {
        if (on == layersApplied) return
        layersApplied = on
        runCatching {
            if (::page.isInitialized) {
                page.setLayerType(if (on) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE, null)
            }
            for (sibling in siblings) {
                if (sibling is SurfaceView || sibling is TextureView) continue
                val type = if (on) View.LAYER_TYPE_HARDWARE
                else siblingStartLayer[sibling] ?: View.LAYER_TYPE_NONE
                sibling.setLayerType(type, null)
            }
        }
    }

    private fun detachFirstFrameGate() {
        val view = firstFrameView ?: return
        val listener = gateListener
        gateListener = null
        if (listener != null) {
            runCatching { view.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
        firstFrameView = null
    }

    private fun cancelFallback() {
        val runnable = fallback ?: return
        fallback = null
        firstFrameView?.removeCallbacks(runnable)
    }

    internal companion object {
        private const val ENTER_DURATION_MS = 340L
        private const val EXIT_DURATION_MS = 280L
        private const val FIRST_FRAME_TIMEOUT_MS = 420L
        private const val PARALLAX = 0.30f
        private const val SCRIM_ALPHA = 0.14f
        private const val IMMEDIATE_SCRIM = 0.06f
        private const val SCRIM_TAG = "Hchat:SettingsScrim"

        private val EASE_OUT = PathInterpolator(0.32f, 0.72f, 0f, 1f)
        private val EASE_IN = PathInterpolator(0.4f, 0f, 0.9f, 1f)

        /**
         * 创建压暗层并立即生效。必须在构造 ComposeView 之前调用：
         * 这一步只做一次 addView，几十微秒就能提交，是「点了有反应」的即时反馈来源。
         * 返回 null 表示当前环境不适合做动画（调用方按原逻辑直接显示）。
         */
        fun begin(context: Context, host: ViewGroup): SettingsPageTransition? {
            val siblings = (0 until host.childCount)
                .mapNotNull { host.getChildAt(it) }
                .filter { it.tag != SCRIM_TAG }
            if (siblings.isEmpty()) return null
            val scrim = View(context).apply {
                tag = SCRIM_TAG
                setBackgroundColor(Color.BLACK)
                alpha = IMMEDIATE_SCRIM
                isClickable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            runCatching { host.addView(scrim) }.onFailure { return null }
            val startX = HashMap<View, Float>(siblings.size)
            val startLayer = HashMap<View, Int>(siblings.size)
            for (sibling in siblings) {
                startX[sibling] = sibling.translationX
                startLayer[sibling] = sibling.layerType
            }
            return SettingsPageTransition(host, scrim, siblings, startX, startLayer)
        }
    }
}
