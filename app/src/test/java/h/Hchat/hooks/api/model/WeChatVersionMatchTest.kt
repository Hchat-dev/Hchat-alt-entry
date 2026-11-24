package h.Hchat.hooks.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本匹配的回归测试。
 *
 * 这里锁死一个真实踩过的坑：`3220` 是 `versionName` 里的内部 build 号，
 * 而 `PackageInfo.versionCode` 实际是 `280005032`。历史实现拿 versionCode
 * 直接比 3220，判断恒为 false，功能静默不安装。
 */
class WeChatVersionMatchTest {

    @Test
    fun mainVersionDropsBuildSuffix() {
        assertEquals("8.0.80", WeChatVersionMatch.mainVersion("8.0.80_3220"))
        assertEquals("8.0.80", WeChatVersionMatch.mainVersion("8.0.80"))
        assertEquals("8.0.80", WeChatVersionMatch.mainVersion(" 8.0.80_3220 "))
        assertEquals("", WeChatVersionMatch.mainVersion(null))
        assertEquals("", WeChatVersionMatch.mainVersion(""))
    }

    @Test
    fun buildNoParsedFromSuffixOnly() {
        assertEquals(3220L, WeChatVersionMatch.buildNo("8.0.80_3220"))
        assertNull(WeChatVersionMatch.buildNo("8.0.80"))
        assertNull(WeChatVersionMatch.buildNo("8.0.80_"))
        assertNull(WeChatVersionMatch.buildNo(null))
    }

    @Test
    fun matchesRealDeviceVersionWithBuildSuffix() {
        // 本机实测：versionName=8.0.80_3220, versionCode=280005032
        assertTrue(WeChatVersionMatch.matches("8.0.80_3220", "8.0.80", 3220L))
    }

    @Test
    fun matchesWhenVersionNameHasNoBuildSuffix() {
        // 不带 _build 后缀时无法校验 build 号，放宽交给 Hook 内部的类名探测兜底，
        // 否则这类机型会整体失效。
        assertTrue(WeChatVersionMatch.matches("8.0.80", "8.0.80", 3220L))
    }

    @Test
    fun rejectsDifferentBuildNo() {
        assertFalse(WeChatVersionMatch.matches("8.0.80_3221", "8.0.80", 3220L))
        assertFalse(WeChatVersionMatch.matches("8.0.80_3219", "8.0.80", 3220L))
    }

    @Test
    fun rejectsDifferentMainVersion() {
        assertFalse(WeChatVersionMatch.matches("8.0.79_3220", "8.0.80", 3220L))
        assertFalse(WeChatVersionMatch.matches("8.0.81_3220", "8.0.80", 3220L))
        assertFalse(WeChatVersionMatch.matches("9.0.0_3220", "8.0.80", 3220L))
    }

    @Test
    fun nullBuildExpectationSkipsBuildCheck() {
        assertTrue(WeChatVersionMatch.matches("8.0.80_9999", "8.0.80", null))
    }

    @Test
    fun rejectsEmptyVersionName() {
        assertFalse(WeChatVersionMatch.matches("", "8.0.80", 3220L))
        // 显式转型：matches 有 (String?, ...) 和 (WeChatVersionInfo?, ...) 两个重载，
        // 裸 null 会让编译器无法在两者之间做选择（OVERLOAD_RESOLUTION_AMBIGUITY）。
        assertFalse(WeChatVersionMatch.matches(null as String?, "8.0.80", 3220L))
    }
}
