package h.Hchat.hooks.items.linkparse

import h.Hchat.hooks.api.model.WeChatMessage
import org.junit.Assert.*
import org.junit.Test

class LinkParseMessageGateTest {
    private val now = 1_770_000_000_000L
    private fun msg(type: Int = 1, id: Long = 4, time: Long = now, text: String = "https://v.douyin.com/bg9DH--cvOc/") =
        WeChatMessage(id, 0, type, 0, 1, time, "filehelper", text, "", "", "", 0)

    @Test fun onlyFreshTextWithStableIdentityIsEligible() {
        assertEquals(now, LinkParseMessageGate.eligibleTime(msg(), now))
        assertEquals(now, LinkParseMessageGate.eligibleTime(msg(time = now / 1000), now))
        assertNull(LinkParseMessageGate.eligibleTime(msg(time = now - 30_000), now))
        assertNull(LinkParseMessageGate.eligibleTime(msg(time = now + 5_001), now))
        assertNull(LinkParseMessageGate.eligibleTime(msg(id = 0), now))
        assertNull(LinkParseMessageGate.eligibleTime(msg(type = 3), now))
        assertNull(LinkParseMessageGate.eligibleTime(msg(type = 49), now))
        assertNull(LinkParseMessageGate.eligibleTime(msg(text = "x".repeat(16_385)), now))
    }

    @Test fun mergedForwardIsEligibleWithinItsOwnSizeBudget() {
        fun merged(body: String) = msg(
            type = 49,
            text = "<msg><appmsg><type>19</type><title>聊天记录</title>" +
                "<recorditem><![CDATA[$body]]></recorditem></appmsg></msg>"
        )
        val record = "<msg><appmsg><type>5</type><url>https://v.douyin.com/bg9DH--cvOc/</url></appmsg></msg>"
        assertEquals(now, LinkParseMessageGate.eligibleTime(merged(record), now))
        assertTrue(LinkParseMessageGate.isMergedForward(merged(record)))

        // 普通链接卡（appmsg type 5）不是合并转发，仍然不进入解析。
        val card = msg(type = 49, text = "<msg><appmsg><type>5</type><url>https://v.douyin.com/x/</url></appmsg></msg>")
        assertNull(LinkParseMessageGate.eligibleTime(card, now))
        assertFalse(LinkParseMessageGate.isMergedForward(card))

        // 超过合并转发的体积上限就放弃，避免超长记录拖住解析线程。
        val oversized = merged("x".repeat(LinkParseMessageGate.MAX_MERGED_TEXT))
        assertNull(LinkParseMessageGate.eligibleTime(oversized, now))
    }
}
