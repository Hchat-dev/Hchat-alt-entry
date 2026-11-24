package h.Hchat.hooks.items.linkparse

import h.Hchat.hooks.api.model.WeChatMessage

internal object LinkParseMessageGate {
    const val MAX_AGE = 30_000L
    const val FUTURE_TOLERANCE = 5_000L

    /** 普通文本消息的正文上限。 */
    const val MAX_TEXT = 16_384

    /**
     * 合并转发（聊天记录）的正文上限。
     *
     * 一条聊天记录可能包几十条子消息，XML 体积远大于普通文本；阈值放宽，但仍要封顶，
     * 避免超长记录把解析线程拖住。
     */
    const val MAX_MERGED_TEXT = 262_144

    /** 合并转发：`type=49`（appmsg）且 appmsg 子类型为 19（聊天记录）。 */
    fun isMergedForward(message: WeChatMessage): Boolean =
        message.isApp() && message.appMsgType() == 19

    fun eligibleTime(message: WeChatMessage, now: Long): Long? {
        if (message.isSystem() || message.msgId <= 0 || message.talker.isBlank()) return null
        val content = message.bodyContent()
        val acceptable = when {
            message.isText() -> content.length <= MAX_TEXT
            // 合并转发的链接藏在 recorditem 的子 appmsg XML 里，正文本身不是纯文本。
            isMergedForward(message) -> content.length <= MAX_MERGED_TEXT
            else -> false
        }
        if (!acceptable) return null
        val time = message.createTime.let { if (it in 1 until 100_000_000_000L) it * 1000 else it }
        return time.takeIf { it > 0 && it <= now + FUTURE_TOLERANCE && now - it < MAX_AGE }
    }
}