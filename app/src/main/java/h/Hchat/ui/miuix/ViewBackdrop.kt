package h.Hchat.ui.miuix

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.blur.Backdrop
import kotlin.math.round

/** Captures a native Android [View] for use by the Compose blur pipeline. */
@Composable
fun rememberViewBackdrop(sourceView: View): ViewBackdrop {
    val graphicsLayer = rememberGraphicsLayer()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val backdrop = remember(graphicsLayer) { ViewBackdrop(graphicsLayer) }
    backdrop.sourceView = sourceView
    backdrop.density = density
    backdrop.layoutDirection = layoutDirection

    DisposableEffect(sourceView) {
        val observer = sourceView.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (sourceView.isDirty) backdrop.bumpVersion()
            true
        }
        observer.addOnPreDrawListener(listener)
        onDispose {
            (if (observer.isAlive) observer else sourceView.viewTreeObserver)
                .removeOnPreDrawListener(listener)
        }
    }
    return backdrop
}

@Stable
class ViewBackdrop internal constructor(
    private val graphicsLayer: GraphicsLayer
) : Backdrop {
    internal var sourceView: View? = null
    internal var density: Density = Density(1f)
    internal var layoutDirection: LayoutDirection = LayoutDirection.Ltr
    private var version by mutableIntStateOf(0)

    // 上一次真正录制时的快照。录制一次要把整个微信内容层（整屏 View 树）重绘进
    // GraphicsLayer，是全项目最贵的绘制开销之一。原来 drawBackdrop 每次调用都
    // 重录一遍：底栏自己的选中动画、角标变化、任何原因引起的重组都会触发，
    // 等于屏幕亮着就一直重复画一遍微信界面。这里只在内容真的脏了（version 变化）
    // 或尺寸变了的时候才重录，其余情况直接复用上一帧的图层。
    private var recordedVersion = -1
    private var recordedWidth = 0
    private var recordedHeight = 0
    private val sourceLocation = IntArray(2)

    override val isCoordinatesDependent: Boolean = true

    override var offsetResidualX: Float = 0f
        private set

    override var offsetResidualY: Float = 0f
        private set

    internal fun bumpVersion() {
        version++
    }

    private fun recordSource(): Boolean {
        val view = sourceView ?: return false
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return false

        val currentVersion = version
        if (currentVersion == recordedVersion && width == recordedWidth && height == recordedHeight) {
            return true
        }

        graphicsLayer.record(density, layoutDirection, IntSize(width, height)) {
            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas
                val saveCount = nativeCanvas.save()
                nativeCanvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
                view.draw(nativeCanvas)
                nativeCanvas.restoreToCount(saveCount)
            }
        }
        recordedVersion = currentVersion
        recordedWidth = width
        recordedHeight = height
        return true
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int
    ) {
        @Suppress("UNUSED_EXPRESSION") version
        val view = sourceView ?: return
        val barCoordinates = coordinates ?: return
        recordSource()

        val barInWindow = barCoordinates.positionInWindow()
        sourceLocation.also { view.getLocationInWindow(it) }
        val offsetX = barInWindow.x - sourceLocation[0]
        val offsetY = barInWindow.y - sourceLocation[1]

        if (downscaleFactor > 1) {
            val inverse = 1f / downscaleFactor
            val scaledX = offsetX * inverse
            val scaledY = offsetY * inverse
            val roundedX = round(scaledX * 0.5f).toInt().toFloat() * 2f
            val roundedY = round(scaledY * 0.5f).toInt().toFloat() * 2f
            offsetResidualX = (scaledX - roundedX) * downscaleFactor
            offsetResidualY = (scaledY - roundedY) * downscaleFactor
            translate(-roundedX, -roundedY) {
                scale(inverse, inverse, Offset.Zero) {
                    drawLayer(graphicsLayer)
                }
            }
        } else {
            offsetResidualX = 0f
            offsetResidualY = 0f
            translate(-offsetX, -offsetY) {
                drawLayer(graphicsLayer)
            }
        }
    }
}
