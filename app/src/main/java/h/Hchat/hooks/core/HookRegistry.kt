package h.Hchat.hooks.core

import de.robv.android.xposed.XC_MethodHook
import h.Hchat.compat.RuntimeHooks
import h.Hchat.utils.SelfFrameRegistry
import java.lang.reflect.Member
import java.util.concurrent.CancellationException

/**
 * 统一保存 hook 句柄，便于防御性卸载。
 *
 * 迁移到 LibXposed 后，句柄类型换成 [io.github.libxposed.api.XposedInterface.HookHandle]，
 * 对外仍是 `XC_MethodHook.Unhook`（由 [RuntimeHooks] 包装），因此 100+ 处调用点不用改。
 */
class HookRegistry private constructor() {
    private val lock = Any()
    private val unhooks = ArrayList<XC_MethodHook.Unhook>()
    private val installationLease = ThreadLocal<Long>()
    @Volatile private var generation = 0L

    fun installationGeneration(): Long = installationLease.get() ?: generation

    fun isInstallationGenerationActive(generation: Long): Boolean = this.generation == generation

    fun <T> withInstallationGeneration(generation: Long, action: () -> T): T {
        val previous = installationLease.get()
        if (!isInstallationGenerationActive(generation) || previous != null && previous != generation) {
            throw CancellationException("安装代次已失效")
        }
        installationLease.set(generation)
        try { return action() }
        finally {
            if (previous == null) installationLease.remove() else installationLease.set(previous)
        }
    }

    fun hook(method: Member, callback: XC_MethodHook): XC_MethodHook.Unhook = synchronized(lock) {
        if (!isInstallationGenerationActive(installationGeneration())) {
            throw CancellationException("安装代次已失效")
        }
        val executable = method as? java.lang.reflect.Executable
            ?: throw IllegalArgumentException("只能 hook Method / Constructor：$method")
        val unhook = XC_MethodHook.Unhook(RuntimeHooks.hook(executable, callback))
        unhooks.add(unhook)
        // release 走 R8 后类名会被改短，反检测的栈帧过滤没法按前缀识别自身帧，
        // 所以在注册这一刻把回调类的真实运行时类名记下来（见 SelfFrameRegistry）。
        SelfFrameRegistry.register(callback.javaClass)
        unhook
    }

    fun add(unhook: XC_MethodHook.Unhook?) {
        if (unhook == null) return
        synchronized(lock) {
            if (isInstallationGenerationActive(installationGeneration())) {
                unhooks.add(unhook)
                return
            }
        }
        unhook.unhook()
        throw CancellationException("安装代次已失效")
    }

    fun size(): Int = synchronized(lock) { unhooks.size }

    fun unhook(unhook: XC_MethodHook.Unhook?) {
        if (unhook == null) return
        unhook.unhook()
        synchronized(lock) { unhooks.remove(unhook) }
    }

    fun unhookAll() {
        val previous = synchronized(lock) {
            generation++
            unhooks.toList().also { unhooks.clear() }
        }
        for (unhook in previous) {
            runCatching { unhook.unhook() }
        }
    }

    companion object {
        private val INSTANCE = HookRegistry()

        @JvmStatic
        fun get(): HookRegistry = INSTANCE
    }
}
