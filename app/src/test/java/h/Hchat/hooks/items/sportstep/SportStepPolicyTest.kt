package h.Hchat.hooks.items.sportstep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random

class SportStepPolicyTest {

    // ---------- jitteredStep ----------

    @Test
    fun `jitter keeps step within +-jitterPercent`() {
        val random = Random(42)
        repeat(200) {
            val step = SportStepPolicy.jitteredStep(20000, 3, random)
            assertTrue(step in 19400..20600)
        }
    }

    @Test
    fun `jitter zero percent returns base`() {
        repeat(50) {
            assertEquals(20000, SportStepPolicy.jitteredStep(20000, 0, Random(it)))
        }
    }

    @Test
    fun `jitter with zero base stays zero`() {
        assertEquals(0, SportStepPolicy.jitteredStep(0, 3, Random(1)))
    }

    @Test
    fun `jitter clamps out of range base`() {
        assertEquals(100000, SportStepPolicy.jitteredStep(500000, 0, Random(1)))
        assertEquals(0, SportStepPolicy.jitteredStep(-5, 0, Random(1)))
    }

    @Test
    fun `jitter over 50 percent is clamped`() {
        val random = Random(1)
        repeat(100) {
            val step = SportStepPolicy.jitteredStep(10000, 99, random)
            // 99% 被钳到 50%：范围 [5000, 15000]，而不是 ±99%。
            assertTrue(step in 5000..15000)
        }
    }

    // ---------- dayKey ----------

    @Test
    fun `dayKey formats GMT+8 calendar day`() {
        // 2026-11-13 12:00:00 GMT+8 == 04:00 UTC
        val zone = TimeZone.getTimeZone("GMT+8")
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 13, 4, 0, 0)
        }
        assertEquals("2026-11-13", SportStepPolicy.dayKey(calendar.timeInMillis, zone))
    }

    @Test
    fun `dayKey rolls over at GMT+8 midnight`() {
        val zone = TimeZone.getTimeZone("GMT+8")
        val utc = TimeZone.getTimeZone("UTC")
        // 2026-11-13 15:59:00 UTC = GMT+8 23:59（同一天）
        val before = Calendar.getInstance(utc).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 13, 15, 59, 0)
        }
        // 2026-11-13 16:01:00 UTC = GMT+8 2026-11-14 00:01（已跨天）
        val after = Calendar.getInstance(utc).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 13, 16, 1, 0)
        }
        assertEquals("2026-11-13", SportStepPolicy.dayKey(before.timeInMillis, zone))
        assertEquals("2026-11-14", SportStepPolicy.dayKey(after.timeInMillis, zone))
    }

    // ---------- shouldUpload ----------

    @Test
    fun `uploads once per day`() {
        assertTrue(SportStepPolicy.shouldUpload(true, true, null, "2026-11-13"))
        assertFalse(SportStepPolicy.shouldUpload(true, true, "2026-11-13", "2026-11-13"))
        assertTrue(SportStepPolicy.shouldUpload(true, true, "2026-11-12", "2026-11-13"))
    }

    @Test
    fun `does not upload when disabled or auto off`() {
        assertFalse(SportStepPolicy.shouldUpload(false, true, null, "2026-11-13"))
        assertFalse(SportStepPolicy.shouldUpload(true, false, null, "2026-11-13"))
        assertFalse(SportStepPolicy.shouldUpload(false, false, null, "2026-11-13"))
    }

    // ---------- isUsableStep ----------

    @Test
    fun `usable step boundaries`() {
        assertFalse(SportStepPolicy.isUsableStep(0))
        assertTrue(SportStepPolicy.isUsableStep(1))
        assertTrue(SportStepPolicy.isUsableStep(SportStepSettings.MAX_STEP))
        assertFalse(SportStepPolicy.isUsableStep(SportStepSettings.MAX_STEP + 1))
    }
}
