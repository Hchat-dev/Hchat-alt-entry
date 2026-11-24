package de.robv.android.xposed

import h.Hchat.compat.CompatHelpers

/**
 * `XposedHelpers` 的等价实现。
 *
 * LibXposed 102 不再提供 Helpers 工具类，模块里只用到了 `findAndHookMethod`、
 * `callMethod`、`getObjectField`、`findClass` 四个，语义保持不变即可。
 */
object XposedHelpers {
    @JvmStatic
    fun findClass(className: String?, classLoader: ClassLoader?): Class<*> =
        CompatHelpers.findClass(className ?: error("className 不能为空"), classLoader)

    @JvmStatic
    fun getObjectField(obj: Any?, fieldName: String?): Any? =
        CompatHelpers.getObjectField(obj, fieldName.orEmpty())

    @JvmStatic
    fun callMethod(obj: Any?, methodName: String?, vararg args: Any?): Any? =
        CompatHelpers.callMethod(obj, methodName.orEmpty(), *args)

    @JvmStatic
    fun callStaticMethod(clazz: Class<*>?, methodName: String?, vararg args: Any?): Any? {
        val target = clazz ?: return null
        return CompatHelpers.callStaticMethod(target, methodName.orEmpty(), *args)
    }

    /** 旧 API 语义：定位失败返回 null，调用方用 `!= null` 判空。 */
    @JvmStatic
    fun findAndHookMethod(
        className: String?,
        classLoader: ClassLoader?,
        methodName: String?,
        vararg parameterTypesAndCallback: Any?
    ): XC_MethodHook.Unhook? {
        val callback = parameterTypesAndCallback.lastOrNull() as? XC_MethodHook ?: return null
        val types = parameterTypesAndCallback.dropLast(1).mapNotNull { it as? Class<*> }.toTypedArray()
        return runCatching {
            val clazz = CompatHelpers.findClass(className.orEmpty(), classLoader)
            val method = clazz.getDeclaredMethod(methodName.orEmpty(), *types)
            method.isAccessible = true
            XC_MethodHook.Unhook(h.Hchat.compat.RuntimeHooks.hook(method, callback))
        }.getOrNull()
    }

    /** Class 入参重载：调用方已持有 Class 对象时使用。 */
    @JvmStatic
    fun findAndHookMethod(
        clazz: Class<*>,
        methodName: String?,
        vararg parameterTypesAndCallback: Any?
    ): XC_MethodHook.Unhook? = findAndHookMethod(clazz.name, clazz.classLoader, methodName, *parameterTypesAndCallback)
}
