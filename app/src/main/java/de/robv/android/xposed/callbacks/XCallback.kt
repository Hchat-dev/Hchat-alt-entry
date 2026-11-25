package de.robv.android.xposed.callbacks

/**
 * `XCallback` 的等价实现。
 *
 * 业务层只用到两个优先级常量（`PRIORITY_HIGHEST` / `PRIORITY_LOWEST`）与
 * `XC_MethodHook(priority)` 构造；LibXposed 用整数优先级表达同一语义。
 */
open class XCallback @JvmOverloads constructor(
    @JvmField val priority: Int = PRIORITY_DEFAULT
) {
    companion object {
        @JvmField
        val PRIORITY_DEFAULT: Int = io.github.libxposed.api.XposedInterface.PRIORITY_DEFAULT

        @JvmField
        val PRIORITY_LOWEST: Int = io.github.libxposed.api.XposedInterface.PRIORITY_LOWEST

        @JvmField
        val PRIORITY_HIGHEST: Int = io.github.libxposed.api.XposedInterface.PRIORITY_HIGHEST
    }
}
