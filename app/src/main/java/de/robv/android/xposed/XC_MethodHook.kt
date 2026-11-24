package de.robv.android.xposed

import h.Hchat.compat.ModuleHook
import h.Hchat.compat.RuntimeHooks
import io.github.libxposed.api.XposedInterface

/**
 * 传统 `XC_MethodHook` 的 LibXposed 实现。
 *
 * ## 为什么保留这个类名
 *
 * 模块里 110 个文件、560 处调用点都是
 * `object : XC_MethodHook() { override fun beforeHookedMethod(param) … }` 的形态。
 * 迁移到 LibXposed（API 102）后，底层必须变成 `hook(m).intercept(chain -> …)`。
 * 逐处改写 560 个调用点既机械又容易漏，因此这里保留类名与回调签名，把
 * **"程序模型转换"收在一处**：调用点一行都不用动，底层已经完全是 LibXposed。
 *
 * 与旧 API 唯一的语义修正：旧 API 里 `param.result = x` 不会跳过原方法，必须
 * `setResult(x)`；这里给 `result` 赋值等价于 `setResult`，避免再出现
 * "日志打了、功能没生效"这类历史问题。
 */
open class XC_MethodHook @JvmOverloads constructor(
    /** 等价于旧 API 的 priority：数值越大越先执行。 */
    private val hookPriority: Int = XposedInterface.PRIORITY_DEFAULT
) : ModuleHook() {

    final override fun priority(): Int = hookPriority

    /**
     * 子类覆写这里（旧 API 语义：原方法执行前）。默认实现保留以便
     * `XC_MethodHook` 匿名子类只覆写需要的一段。旧 API 允许声明 `throws Throwable`，
     * 这里同样放行（LibXposed 的 intercept 链本身允许异常上抛）。
     */
    @Throws(Throwable::class)
    open fun beforeHookedMethod(param: MethodHookParam) {}

    /** 子类覆写这里（旧 API 语义：原方法执行后）。 */
    @Throws(Throwable::class)
    open fun afterHookedMethod(param: MethodHookParam) {}

    override fun before(param: MethodHookParam) {
        beforeHookedMethod(param)
    }

    override fun after(param: MethodHookParam) {
        afterHookedMethod(param)
    }

    /** 卸载句柄；名字与旧 API 一致，业务层大量字段声明为 `XC_MethodHook.Unhook?`。 */
    class Unhook internal constructor(
        private val handle: XposedInterface.HookHandle
    ) {
        fun unhook() {
            runCatching { handle.unhook() }
        }

        internal fun nativeHandle(): XposedInterface.HookHandle = handle
    }

    /**
     * 一次 hook 调用的可变上下文，替代旧 `XC_MethodHook.MethodHookParam`。
     *
     * 业务层习惯（`param.args` / `param.thisObject` / `param.setResult` /
     * `param.getObjectExtra` / `param.method`）全部保留，因此 110 个文件不需要改。
     */
    class MethodHookParam internal constructor(
        /** 被 hook 的成员（Method 或 Constructor）。 */
        @JvmField val method: java.lang.reflect.Member,
        /** 调用时的 this；静态方法为 null。 */
        @JvmField val thisObject: Any?,
        /** 可写参数数组。改元素或整体替换都会带回宿主。 */
        @JvmField var args: Array<Any?>,
        /** 已声明的返回值。 */
        @JvmField var result: Any?
    ) {
        /** 是否跳过原方法（旧 API 的 returnEarly）。 */
        @Volatile
        @JvmField
        var returnEarly: Boolean = false

        /** 原方法抛出的异常；仅 after 阶段可见。 */
        @JvmField
        var throwable: Throwable? = null

        private var payload: MutableMap<String, Any?>? = null

        fun hasThrowable(): Boolean = throwable != null

        fun getResult(): Any? {
            val error = throwable
            if (error != null) throw error
            return result
        }

        /** 置返回值并跳过原方法。 */
        fun setResult(value: Any?) {
            result = value
            returnEarly = true
        }

        fun setThrowable(error: Throwable?) {
            throwable = error
        }

        fun setObjectExtra(key: String?, value: Any?) {
            if (key == null) return
            val map = payload ?: HashMap<String, Any?>(4).also { payload = it }
            map[key] = value
        }

        fun getObjectExtra(key: String?): Any? {
            if (key == null) return null
            return payload?.get(key)
        }
    }
}

/** `XposedBridge` 的等价实现：只保留模块实际用到的方法。 */
object XposedBridge {
    @JvmField
    val TAG: String = "Hchat"

    @JvmStatic
    fun hookMethod(
        method: java.lang.reflect.Member?,
        callback: XC_MethodHook?
    ): XC_MethodHook.Unhook? {
        val executable = method as? java.lang.reflect.Executable ?: return null
        if (callback == null) return null
        return runCatching {
            executable.isAccessible = true
            XC_MethodHook.Unhook(RuntimeHooks.hook(executable, callback))
        }.getOrNull()
    }

    @JvmStatic
    fun hookAllMethods(
        clazz: Class<*>?,
        methodName: String?,
        callback: XC_MethodHook?
    ): Set<XC_MethodHook.Unhook> {
        if (clazz == null || methodName == null || callback == null) return emptySet()
        val handles = LinkedHashSet<XC_MethodHook.Unhook>()
        for (method in clazz.declaredMethods) {
            if (method.name != methodName) continue
            hookMethod(method, callback)?.let { handles += it }
        }
        return handles
    }

    @JvmStatic
    fun hookAllConstructors(
        clazz: Class<*>?,
        callback: XC_MethodHook?
    ): Set<XC_MethodHook.Unhook> {
        if (clazz == null || callback == null) return emptySet()
        val handles = LinkedHashSet<XC_MethodHook.Unhook>()
        for (ctor in clazz.declaredConstructors) {
            hookMethod(ctor, callback)?.let { handles += it }
        }
        return handles
    }

    @JvmStatic
    fun log(message: String?) {
        if (message != null) RuntimeHooks.log(message)
    }

    @JvmStatic
    fun log(error: Throwable?) {
        if (error != null) RuntimeHooks.log(android.util.Log.getStackTraceString(error))
    }

    /**
     * 直接调用原实现、不经过 hook 链。
     *
     * LibXposed 用 `getInvoker(...).setType(Invoker.Type.ORIGIN).invoke(...)` 表达同一语义。
     */
    @JvmStatic
    fun invokeOriginalMethod(
        method: java.lang.reflect.Member?,
        thisObject: Any?,
        args: Array<Any?>?
    ): Any? {
        val executable = method as? java.lang.reflect.Method ?: return null
        val module = RuntimeHooks.moduleInstance() ?: return null
        return runCatching {
            executable.isAccessible = true
            module.getInvoker(executable)
                .setType(XposedInterface.Invoker.Type.ORIGIN)
                .invoke(thisObject, *(args ?: emptyArray()))
        }.getOrNull()
    }
}
