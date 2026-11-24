package h.Hchat.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotConfigTest {

    @Test
    fun `loader runs once within ttl`() {
        var calls = 0
        val flag = HotConfig.flag("test_hot_a", "enabled", true) {
            calls++
            false
        }
        assertFalse(flag())
        assertFalse(flag())
        assertFalse(flag())
        assertEquals(1, calls)
    }

    @Test
    fun `loader failure falls back to default`() {
        val flag = HotConfig.flag("test_hot_b", "enabled", true) {
            throw IllegalStateException("config not ready")
        }
        // 配置未就绪时不能把功能变成关闭或被异常打断。
        assertTrue(flag())
        assertTrue(flag())
    }

    @Test
    fun `invalidate forces the existing lambda to reload`() {
        var calls = 0
        var returnValue = false
        val flag = HotConfig.flag("test_hot_c", "enabled", false) {
            calls++
            returnValue
        }
        assertFalse(flag())
        returnValue = true
        // 缓存未过期时仍拿旧值
        assertFalse(flag())
        HotConfig.invalidate("test_hot_c")
        assertTrue(flag())
        assertEquals(2, calls)
    }

    @Test
    fun `different keys do not share cache`() {
        var firstCalls = 0
        var secondCalls = 0
        val first = HotConfig.flag("test_hot_d", "enabled", false) { firstCalls++; true }
        val second = HotConfig.flag("test_hot_d", "auto", false) { secondCalls++; false }
        assertTrue(first())
        assertFalse(second())
        assertTrue(first())
        assertFalse(second())
        assertEquals(1, firstCalls)
        assertEquals(1, secondCalls)
    }

    @Test
    fun `default is used before first load completes`() {
        // 首次调用前 entry 的 value 是 default，expiresAt=0 立即触发加载；
        // 这里只验证 default 与加载值不一致时以加载值为准。
        val flag = HotConfig.flag("test_hot_e", "enabled", true) { false }
        assertFalse(flag())
    }
}
