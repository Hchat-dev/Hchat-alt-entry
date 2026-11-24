package h.Hchat.compat

import de.robv.android.xposed.XC_MethodHook
import io.github.libxposed.api.XposedInterface

/**
 * LibXposed API 102 之上的 hook 调度器。
 *
 * 把 [XC_MethodHook] 的 before/after 两段语义映射到 LibXposed 单次 `intercept(chain)`：
 *
 * 1. 构造 [XC_MethodHook.MethodHookParam]（args 取 chain 只读列表的副本，允许任意改）；
 * 2. 调 `beforeHookedMethod`；若它 `setResult(...)`（置 `returnEarly`），跳过原方法；
 * 3. 否则 `chain.proceed()`；参数被改过就 `chain.proceed(newArgs)`；
 * 4. 结果写回 `param.result`，再调 `afterHookedMethod`（可再次改结果）；
 * 5. 异常写入 `param.throwable`，after 仍执行，随后按 LibXposed 语义上抛。
 */
object RuntimeHooks {
    @Volatile
    private var module: io.github.libxposed.api.XposedModule? = null

    @Volatile
    private var api: XposedInterface? = null

    fun bind(instance: io.github.libxposed.api.XposedModule) {
        module = instance
        api = instance
    }

    fun unbind() {
        module = null
        api = null
    }

    fun isReady(): Boolean = api != null

    /** 供 `XposedBridge.invokeOriginalMethod` 走 Invoker（Type.ORIGIN）。 */
    fun moduleInstance(): io.github.libxposed.api.XposedModule? = module

    fun frameworkName(): String = runCatching { api?.frameworkName }.getOrNull() ?: ""

    fun frameworkVersion(): String = runCatching { api?.frameworkVersion }.getOrNull() ?: ""

    fun apiVersion(): Int = runCatching { api?.apiVersion }.getOrNull() ?: 0

    fun hook(
        executable: java.lang.reflect.Executable,
        callback: XC_MethodHook
    ): XposedInterface.HookHandle {
        val instance = api ?: error("LibXposed 模块未绑定")
        return instance.hook(executable)
            .setPriority(callback.priority())
            .intercept { chain ->
                val original = chain.args
                val param = XC_MethodHook.MethodHookParam(
                    method = executable,
                    thisObject = chain.thisObject,
                    args = Array(original.size) { original[it] },
                    result = null
                )
                var produced: Any? = null
                try {
                    callback.before(param)
                    produced = if (param.returnEarly) {
                        param.result
                    } else {
                        val next = param.args
                        val unchanged = next.size == original.size &&
                            (0 until next.size).all { next[it] === original[it] }
                        param.result = if (unchanged) chain.proceed() else chain.proceed(next)
                        param.result
                    }
                    callback.after(param)
                    produced
                } catch (error: Throwable) {
                    param.throwable = error
                    runCatching { callback.after(param) }
                    throw error
                }
            }
    }

    /** 兼容旧 `XposedBridge.log`。 */
    fun log(message: String) {
        runCatching { api?.log(android.util.Log.INFO, "Hchat", message) }
    }
}
