package h.Hchat.hooks.api.model

/**
 * 微信版本号的归一化匹配。
 *
 * ## 为什么需要它
 *
 * 微信的版本信息有两个不同的数字，很容易混：
 *
 * | 字段 | 取值（以本机 8.0.80 为例） | 来源 |
 * |---|---|---|
 * | 主版本名 | `8.0.80` | `PackageInfo.versionName` 去掉 `_build` 后缀 |
 * | 内部 build 号 | `3220` | `PackageInfo.versionName` 的 `_3220` 后缀 |
 * | 清单 versionCode | `280005032` | `PackageInfo.versionCode` / `getLongVersionCode()` |
 *
 * 历史代码把「内部 build 号 3220」当成 `versionCode` 来比较
 * （`versionCode != 3220`），但 `PackageInfo` 实际返回的是 `280005032`，
 * 判断恒为 false —— 功能静默不安装、也不报错，表现就是"开关打开了没反应"。
 *
 * ## 匹配策略
 *
 * 只要求**主版本名**严格相等；内部 build 号在能从 `versionName` 解析出来时
 * 才参与比较，解析不到就放宽（此时具体 Hook 仍会通过类名/方法签名校验兜底，
 * 定位失败会记录 `logError`，不会静默挂错）。
 *
 * 这样既修掉了 build 号 == versionCode 的错位，也不会在 `versionName` 不带
 * 后缀的机型上误判为不兼容。
 */
object WeChatVersionMatch {

    /** `8.0.80_3220` -> `8.0.80`；`8.0.80` -> `8.0.80`。 */
    @JvmStatic
    fun mainVersion(versionName: String?): String {
        val raw = versionName.orEmpty().trim()
        val idx = raw.indexOf('_')
        // 注意不能用 substringBefore('_', "")：没有下划线时会返回空串，
        // 反而把 "8.0.80" 这种合法主版本名判成不匹配。
        return (if (idx >= 0) raw.substring(0, idx) else raw).trim()
    }

    /** `8.0.80_3220` -> `3220`；没有 `_数字` 后缀时返回 null。 */
    @JvmStatic
    fun buildNo(versionName: String?): Long? {
        val raw = versionName.orEmpty().trim()
        val idx = raw.lastIndexOf('_')
        if (idx <= 0 || idx == raw.length - 1) return null
        return raw.substring(idx + 1).trim().toLongOrNull()
    }

    /**
     * 是否命中目标微信版本。
     *
     * @param versionName `PackageInfo.versionName` 原值，可带 `_3220` 后缀
     * @param expectedMain 期望主版本号，如 `8.0.80`
     * @param expectedBuild 期望内部 build 号，如 `3220`；传 null 表示不校验 build
     */
    @JvmStatic
    fun matches(
        versionName: String?,
        expectedMain: String,
        expectedBuild: Long?
    ): Boolean {
        val main = mainVersion(versionName)
        if (main.isEmpty() || main != expectedMain) return false
        if (expectedBuild == null) return true
        val actual = buildNo(versionName)
        // versionName 不带 build 后缀时无法校验，交由 Hook 内部的类名/签名探测兜底。
        return actual == null || actual == expectedBuild
    }

    /** [matches] 的便捷重载，直接吃 [WeChatVersionInfo]。 */
    @JvmStatic
    fun matches(
        info: WeChatVersionInfo?,
        expectedMain: String,
        expectedBuild: Long?
    ): Boolean = info != null && matches(info.versionName, expectedMain, expectedBuild)
}
