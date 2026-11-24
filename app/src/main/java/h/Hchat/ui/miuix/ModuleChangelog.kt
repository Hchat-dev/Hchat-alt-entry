package h.Hchat.ui.miuix

/**
 * 模块内置更新日志。
 *
 * ## 为什么放在代码里
 *
 * 之前「设置 → 历史版本」是去远端接口拉版本列表，每一项还带「下载此版本」——
 * 等于把发版和分发都绑在一个第三方服务上，断网/服务下线就只能看到报错。
 * 现在改成**随包发布的更新日志**：只读本地数据，无网络请求、无下载入口。
 *
 * ## 维护约定
 *
 * 每次发布新的 6.7.x（以及之后的大版本）时，在 [entries] **最前面**补一条：
 * 版本号 + 面向用户的变更点（一行一条，短句，动词开头）。
 * 不要再把下载链接写进来；用户从各自的渠道取包。
 */
internal data class ModuleChangelogEntry(
    val version: String,
    val notes: List<String>
)

internal object ModuleChangelog {
    /** 新的在上。版本号按发布顺序倒序排列。 */
    val entries: List<ModuleChangelogEntry> = listOf(
        ModuleChangelogEntry(
            "6.7.7.8",
            listOf(
                "设置 → 历史版本：改为随包发布的更新日志，去掉远端下载入口",
                "链接解析：支持动图，GIF/PNG 按原始字节发送，微信里会动",
                "链接解析：小红书改为原图优先，取不到再退回 jpg 变体",
                "链接解析：支持合并转发（聊天记录）里的作品链接"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.7",
            listOf(
                "设置页切 tab 不再整页重组，底栏高亮改为点击即跟手",
                "液态玻璃底栏在翻页动画期间自动关闭，动画结束恢复",
                "明暗主题色板改为缓存，不再每次重组重建",
                "安装包回到 6.7.7 分支，签名不变，可直接覆盖安装"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.6",
            listOf(
                "反检测：release 包也能正确识别并隐藏模块自身的调用栈帧",
                "日志落盘轮询空闲期从 1.2 秒放宽到 15 秒，减少后台唤醒",
                "栈帧过滤增加快路径，抓栈开销比 6.7.7.1 更低"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.5",
            listOf(
                "订阅号广告：新增广告卡帧任务门，只拦广告服务，正常品牌卡不受影响",
                "公众号文章广告检查日志改为「结果变化才写」，不再刷屏",
                "修掉广告取数任务的重复触发"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.4",
            listOf(
                "订阅号广告：直接丢弃广告卡拉取任务，冷启动少一次跨 ZIDL 取数",
                "挂载日志新增「拉取任务门」",
                "日志轮询改为自适应周期"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.3",
            listOf(
                "订阅号广告：卡片门诊断改为按方法名锚定，并标出被框架代理的实际帧",
                "反检测：新增模块自身类名登记表，正式包不再漏帧"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.2",
            listOf(
                "订阅号广告：挂载日志补齐微信版本号",
                "启动早期也能读到宿主版本，不再显示「未知」"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.1",
            listOf(
                "订阅号广告：首次命中时输出各门挂载状态与 Fj 调用方栈",
                "便于区分「没挂上」与「没命中」"
            )
        )
    )

    /** 当前安装版本对应的条目，找不到返回 null。 */
    fun current(versionName: String): ModuleChangelogEntry? =
        entries.firstOrNull { it.version == versionName }
}