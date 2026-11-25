package h.Hchat.compat

/**
 * `XposedHelpers` 的等价实现（LibXposed 不再提供 Helpers 工具类）。
 *
 * 模块里只用到四个方法：`findAndHookMethod`、`callMethod`、`getObjectField`、`findClass`。
 * 这里按同样签名实现，内部统一走 KavaReflector 与 [RuntimeHooks]，避免业务层到处改写法。
 */
object CompatHelpers {
    @JvmStatic
    fun findClass(className: String, classLoader: ClassLoader?): Class<*> =
        Class.forName(className, false, classLoader)

    @JvmStatic
    fun getObjectField(obj: Any?, fieldName: String): Any? {
        if (obj == null) return null
        val field = h.Hchat.utils.KavaReflector.findFieldRecursive(obj.javaClass, fieldName)
            ?: return null
        field.isAccessible = true
        return runCatching { field.get(obj) }.getOrNull()
    }

    @JvmStatic
    fun callMethod(obj: Any?, methodName: String, vararg args: Any?): Any? {
        val target = obj ?: return null
        val method = findMethod(target.javaClass, methodName, args)
            ?: error("找不到方法 $methodName")
        method.isAccessible = true
        return runCatching { method.invoke(target, *args) }.getOrNull()
    }

    @JvmStatic
    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? {
        val method = findMethod(clazz, methodName, args) ?: error("找不到静态方法 $methodName")
        method.isAccessible = true
        return runCatching { method.invoke(null, *args) }.getOrNull()
    }


    /**
     * 沿继承链查找方法。
     *
     * 旧 `XposedHelpers.findAndHookMethod` 就是这么做的（先 `getDeclaredMethod`，再逐级
     * `getSuperclass()`）；直接 `getDeclaredMethod` 只查本类，会让"方法声明在父类"的
     * 场景静默失败 —— 设置页入口消失就是这类问题的高危点，所以这里必须与旧语义一致。
     */
    @JvmStatic
    fun findMethodInHierarchy(
        clazz: Class<*>?,
        name: String?,
        types: Array<out Class<*>>
    ): java.lang.reflect.Method? {
        val methodName = name.orEmpty()
        var current: Class<*>? = clazz
        while (current != null) {
            runCatching { current.getDeclaredMethod(methodName, *types) }.getOrNull()?.let { return it }
            current.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.contentEquals(types)
            }?.let { return it }
            current = current.superclass
        }
        return null
    }

    /** 按 Class + 方法名 + 参数类型注册 hook；定位失败返回 null（与旧 API 一致）。 */
    @JvmStatic
    fun hookByClass(
        clazz: Class<*>?,
        methodName: String?,
        types: Array<out Class<*>>,
        callback: de.robv.android.xposed.XC_MethodHook?
    ): de.robv.android.xposed.XC_MethodHook.Unhook? {
        if (clazz == null || callback == null) return null
        val method = findMethodInHierarchy(clazz, methodName, types) ?: return null
        return runCatching {
            method.isAccessible = true
            de.robv.android.xposed.XC_MethodHook.Unhook(RuntimeHooks.hook(method, callback))
        }.getOrNull()
    }

    private fun findMethod(
        clazz: Class<*>,
        name: String,
        args: Array<out Any?>
    ): java.lang.reflect.Method? {
        val candidates = clazz.methods.filter { it.name == name } +
            clazz.declaredMethods.filter { it.name == name }
        return candidates.firstOrNull { candidate ->
            val params = candidate.parameterTypes
            params.size == args.size && params.indices.all { index ->
                val arg = args[index] ?: return@all !params[index].isPrimitive
                wrap(params[index]).isInstance(arg)
            }
        }
    }

    private fun wrap(type: Class<*>): Class<*> = when (type) {
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> type
    }
}
