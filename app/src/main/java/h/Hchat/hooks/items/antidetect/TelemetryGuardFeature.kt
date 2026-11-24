package h.Hchat.hooks.items.antidetect

import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.net.WeChatNetworkDispatcher
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.preferences.HotConfig
import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider
import h.Hchat.utils.HLog

/**
 * 拦截风控/埋点上报 CGI（防封轮 · ③拦上报）。
 *
 * ## 反编译依据（微信 8.0.80）
 *
 * 发包链路 `q63/o -> q63/j -> r63/c -> modelbase/i#doScene -> dispatch`，
 * `network/y0#getUri()` 拿每个请求的 CGI URI（类名方法名全明文）。已确认的
 * 风控上报端点：
 *
 * - `/cgi-bin/mmsupport-bin/stackreport`：异常栈上报——**这是微信收集 hook 痕迹的
 *   主通道**，探针抓到可疑栈帧后会连同环境信息一起从这里送出去；
 * - `/cgi-bin/mmsupport-bin/reportdevice`：设备环境上报；
 * - `newreportkvcomm`（及其 `rsa` 加密变体）、`rtkvreport`：KV 行为埋点。
 *
 * ## 做法
 *
 * 复用 [WeChatNetworkDispatcher] 的统一 hook 面：它已经 hook 了微信自己的网络队列
 * 发送方法（每个请求都过那里），本功能只往它的进程级 SendGuard 表里注册一条
 * 规则——按 URI 片段匹配 [TelemetryUriPolicy.BLOCKED_URI_FRAGMENTS]，命中即整次
 * 丢弃（boolean 队列返回 false，int 队列返回 0，等价于一次普通的发送失败，
 * 微信自己的容错逻辑会兜住，不产生崩溃）。
 *
 * ## 边界
 *
 * - 只按 URI 片段匹配已确认的上报端点，正常收发消息/朋友圈/支付的 CGI 一个不碰。
 * - URI 提取不到时放行（fail-open），绝不误杀正常请求。
 * - 微信对丢弃的上报会照常重试排队，日志里会看到"已拦截风控上报 CGI"累计计数；
 *   若出现登录异常等副作用，把这个开关关掉即可立即恢复。
 */
class TelemetryGuardFeature : BaseFeature() {
    override fun featureId() = ID
    override fun name() = "拦截风控上报"

    /**
     * 守卫在每个网络请求上都会读一次开关，必须走热路径缓存：
     * 直接读配置文件要拼字符串 + 拿锁，挂在发包链路上代价明显。
     */
    @Volatile
    private var enabled: () -> Boolean = { DEFAULT_ENABLED }

    private val guard = WeChatNetworkDispatcher.SendGuard { _, uri ->
        enabled() && TelemetryUriPolicy.shouldBlock(uri)
    }

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(
            object : SimpleFeatureSettingsProvider(
                ID,
                name(),
                "丢弃 stackreport / reportdevice / newreportkvcomm / rtkvreport 等风控上报请求，" +
                    "从通道上断掉检测结果的回传。默认开启。",
                FeatureSettingsProvider.CATEGORY_ENHANCE
            ) {}
        )
    }

    override fun onFeatureInstall(context: FeatureContext) {
        val config = context.configStore()
        enabled = HotConfig.flag(ID, KEY_ENABLED, DEFAULT_ENABLED) {
            config.getBoolean(ID, KEY_ENABLED, DEFAULT_ENABLED)
        }
        // guard 是静态表成员，注册顺序与网络 hook 安装顺序无关：
        // 队列 hook 回调每次调用都会重新读守卫表。
        WeChatNetworkDispatcher.registerSendGuard(guard)
        HLog.e(
            "$TAG 风控上报拦截已注册到网络队列守卫，微信 ${wechatVersion()}；" +
                "端点数=${TelemetryUriPolicy.BLOCKED_URI_FRAGMENTS.size}"
        )
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        try {
            WeChatNetworkDispatcher.unregisterSendGuard(guard)
        } catch (e: Throwable) {
            logError("注销发送守卫失败", e)
        }
    }

    private fun wechatVersion(): String = WeChatApis.version()?.current()?.versionName ?: "未知"

    companion object {
        const val ID = "anti_detect_telemetry"
        const val KEY_ENABLED = "enabled"
        const val DEFAULT_ENABLED = true

        private const val TAG = "[Hchat:AntiDetectTelemetry]"
    }
}
