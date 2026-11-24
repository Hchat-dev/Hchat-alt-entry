package h.Hchat.hooks.items.antidetect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private fun frame(className: String, methodName: String = "call") =
    StackTraceElement(className, methodName, "Source.kt", 1)

class StackFramePolicyTest {
    @Test
    fun `xposed and lsposed frames are hidden`() {
        assertTrue(StackFramePolicy.isHiddenClassName("de.robv.android.xposed.XposedBridge"))
        assertTrue(StackFramePolicy.isHiddenClassName("org.lsposed.lspd.core.Main"))
        assertTrue(StackFramePolicy.isHiddenClassName("io.github.lsposed.manager.Foo"))
        assertTrue(StackFramePolicy.isHiddenClassName("com.tencent.mm.ui.LSPHooker_onChatMsg"))
        assertTrue(StackFramePolicy.isHiddenClassName("h.Hchat.hooks.core.HookRegistry"))
    }

    @Test
    fun `host frames are never hidden`() {
        assertFalse(StackFramePolicy.isHiddenClassName("com.tencent.mm.app.Application"))
        assertFalse(StackFramePolicy.isHiddenClassName("com.tencent.mars.stn.StnLogic"))
        assertFalse(StackFramePolicy.isHiddenClassName("android.app.Activity"))
        assertFalse(StackFramePolicy.isHiddenClassName(""))
    }

    @Test
    fun `clean returns same array when nothing hidden`() {
        val stack = arrayOf(frame("com.tencent.mm.app.Application"), frame("android.os.Looper"))
        assertSame(stack, StackFramePolicy.clean(stack))
    }

    @Test
    fun `clean drops hook frames but keeps all host frames`() {
        val stack = arrayOf(
            frame("com.tencent.mm.app.Application"),
            frame("de.robv.android.xposed.XposedBridge", "handleHookedMethod"),
            frame("com.tencent.mm.ui.chatting.ChatMsg", "onMsg"),
            frame("h.Hchat.hooks.items.demo.Feature", "hook"),
            frame("java.lang.Thread", "getStackTrace")
        )
        val cleaned = StackFramePolicy.clean(stack)
        assertEquals(
            listOf(
                "com.tencent.mm.app.Application",
                "com.tencent.mm.ui.chatting.ChatMsg",
                "java.lang.Thread"
            ),
            cleaned.map { it.className }
        )
    }

    @Test
    fun `clean keeps original when every frame would be filtered`() {
        val stack = arrayOf(
            frame("de.robv.android.xposed.XposedBridge"),
            frame("h.Hchat.hooks.core.Foo")
        )
        // 空栈比带痕迹更可疑：全命中时保留原栈。
        assertSame(stack, StackFramePolicy.clean(stack))
    }

    @Test
    fun `obfuscated short frame names only consult the registry`() {
        // release 下混淆类名不含点：只有登记过才算自身帧。
        assertFalse(StackFramePolicy.isHiddenClassName("u"))
        assertTrue(StackFramePolicy.isHiddenClassName("u") { it == "u" })
        // 含点且短于 8 的宿主帧直接排除（跳过子串扫描）。
        assertFalse(StackFramePolicy.isHiddenClassName("px.b"))
        assertFalse(StackFramePolicy.isHiddenClassName("wx.m0"))
        assertFalse(StackFramePolicy.isHiddenClassName("px.b") { it == "px.b" })
    }

    @Test
    fun `registry hides self classes that lost the keep prefix`() {
        assertFalse(StackFramePolicy.isHiddenClassName("com.example.demo.Feature") { false })
        assertTrue(
            StackFramePolicy.isHiddenClassName("com.example.demo.Feature") {
                it == "com.example.demo.Feature"
            }
        )
    }

    @Test
    fun `clean drops obfuscated self frames but keeps host frames`() {
        val stack = arrayOf(frame("com.tencent.mm.app.Application"), frame("u", "run"))
        val cleaned = StackFramePolicy.clean(stack) { it == "u" }
        assertEquals(listOf("com.tencent.mm.app.Application"), cleaned.map { it.className })
    }
}

class TelemetryUriPolicyTest {
    @Test
    fun `confirmed telemetry endpoints are blocked`() {
        assertTrue(TelemetryUriPolicy.shouldBlock("/cgi-bin/mmsupport-bin/stackreport"))
        assertTrue(TelemetryUriPolicy.shouldBlock("/cgi-bin/mmsupport-bin/reportdevice"))
        assertTrue(TelemetryUriPolicy.shouldBlock("/cgi-bin/mmreportkvcomm/newreportkvcomm"))
        assertTrue(TelemetryUriPolicy.shouldBlock("/cgi-bin/mmreportkvcomm/newreportkvcommrsa"))
        assertTrue(TelemetryUriPolicy.shouldBlock("/cgi-bin/mmreportkvcomm/rtkvreport"))
    }

    @Test
    fun `normal traffic passes through`() {
        assertFalse(TelemetryUriPolicy.shouldBlock("/cgi-bin/micromsg-bin/newsendmsg"))
        assertFalse(TelemetryUriPolicy.shouldBlock("/cgi-bin/mmsnssync/sync"))
        assertFalse(TelemetryUriPolicy.shouldBlock(null))
        assertFalse(TelemetryUriPolicy.shouldBlock(""))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(TelemetryUriPolicy.shouldBlock("/CGI-BIN/MMSUPPORT-BIN/STACKREPORT"))
    }
}

class XposedProbePolicyTest {
    @Test
    fun `xposed class probes are denied`() {
        assertTrue(XposedProbePolicy.isDeniedClassName("de.robv.android.xposed.XposedBridge"))
        assertTrue(XposedProbePolicy.isDeniedClassName("de.robv.android.xposed.XposedHelpers"))
        assertTrue(XposedProbePolicy.isDeniedClassName("org.lsposed.lspd.service.BridgeService"))
        assertTrue(XposedProbePolicy.isDeniedClassName("io.github.lsposed.core.Foo"))
    }

    @Test
    fun `normal classes are never denied`() {
        assertFalse(XposedProbePolicy.isDeniedClassName("java.lang.String"))
        assertFalse(XposedProbePolicy.isDeniedClassName("com.tencent.mm.app.Application"))
        assertFalse(XposedProbePolicy.isDeniedClassName(null))
        assertFalse(XposedProbePolicy.isDeniedClassName(""))
    }

    @Test
    fun `manager packages are denied by fragment`() {
        assertTrue(XposedProbePolicy.isDeniedPackageName("de.robv.android.xposed.installer"))
        assertTrue(XposedProbePolicy.isDeniedPackageName("io.github.lsposed.manager"))
        assertTrue(XposedProbePolicy.isDeniedPackageName("SomeApp.EdXposed.Manager"))
    }

    @Test
    fun `normal packages are never denied`() {
        assertFalse(XposedProbePolicy.isDeniedPackageName("com.tencent.mm"))
        assertFalse(XposedProbePolicy.isDeniedPackageName("h.Hchat"))
        assertFalse(XposedProbePolicy.isDeniedPackageName(null))
        assertFalse(XposedProbePolicy.isDeniedPackageName(""))
    }
}
