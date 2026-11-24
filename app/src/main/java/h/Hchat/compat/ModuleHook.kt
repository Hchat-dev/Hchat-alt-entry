package h.Hchat.compat

import io.github.libxposed.api.XposedInterface

/**
 * hook 回调的抽象基类。
 *
 * 业务层用的是它的子类 [de.robv.android.xposed.XC_MethodHook]（保留旧类名，见该类注释）。
 * 这里只定义 before/after 两段语义与优先级，与具体 Xposed 实现解耦。
 */
abstract class ModuleHook {
    /** hook 优先级，数值越大越先被调用。 */
    open fun priority(): Int = io.github.libxposed.api.XposedInterface.PRIORITY_DEFAULT

    open fun before(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {}

    open fun after(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {}
}