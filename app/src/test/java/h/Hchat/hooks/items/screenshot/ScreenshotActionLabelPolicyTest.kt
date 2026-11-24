package h.Hchat.hooks.items.screenshot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「转发截图」按钮判定的回归测试。
 *
 * 反编译结论（微信 8.0.80_3220）：`ScreenShotActionOverlay.k(List)` 把
 * `List<at5/z>` 绑成按钮，每个 `at5/z.c` 是 `zs5/s`，其 `b` 字段就是按钮文案。
 * 这里锁死"只认转发那一项"的边界——多判会把编辑/收藏一起隐藏，少判则开关无效。
 */
class ScreenshotActionLabelPolicyTest {

    @Test
    fun matchesChineseButtonLabel() {
        assertTrue(ScreenshotActionLabelPolicy.shouldHide("转发截图"))
        assertTrue(ScreenshotActionLabelPolicy.shouldHide("  转发截图 "))
        // 真实资源里可能带标点或短句，包含即命中。
        assertTrue(ScreenshotActionLabelPolicy.shouldHide("转发截图到聊天"))
    }

    @Test
    fun matchesInternalActionId() {
        assertTrue(ScreenshotActionLabelPolicy.shouldHide("forward_screenshot_btn"))
        assertTrue(ScreenshotActionLabelPolicy.shouldHide("ForwardScreenshot"))
    }

    @Test
    fun keepsOtherActions() {
        assertFalse(ScreenshotActionLabelPolicy.shouldHide("编辑"))
        assertFalse(ScreenshotActionLabelPolicy.shouldHide("收藏"))
        assertFalse(ScreenshotActionLabelPolicy.shouldHide("搜一搜"))
        assertFalse(ScreenshotActionLabelPolicy.shouldHide("翻译"))
        assertFalse(ScreenshotActionLabelPolicy.shouldHide(""))
        assertFalse(ScreenshotActionLabelPolicy.shouldHide("   "))
    }

    @Test
    fun doesNotTreatPlainForwardAsScreenshotForward() {
        // 「转发」是消息菜单里的另一个动作，不能连带隐藏。
        assertFalse(ScreenshotActionLabelPolicy.shouldHide("转发"))
        assertFalse(ScreenshotActionLabelPolicy.shouldHide("转发给朋友"))
    }
}
