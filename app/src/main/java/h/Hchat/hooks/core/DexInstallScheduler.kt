package h.Hchat.hooks.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import h.Hchat.utils.HLog
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 统一串行安装、有限重试；卸载后旧任务不能重新安装或保留等待中的闭包。 */
object DexInstallScheduler {
    enum class Stage(internal val level: Int) { EARLY(0), BRIDGE(1), WARMUP(2) }

    private const val TAG = "[Hchat:DexInstall]"

    /**
     * 单个安装任务的重试上限。
     *
     * 原来是 6 次（退避 1/3/6/12/24/48 秒）。当微信版本超出 DexFinder 的适配范围时，
     * 这些重试不会成功，只会在微信进程里反复触发 DexKit 全 dex 扫描 —— 每次扫描都要
     * 把 dex 映射进 native 内存，重试期间 DexKit 的空闲释放窗口根本来不及触发，
     * 表现就是“微信内存一路涨、CPU 也高”。
     *
     * 降到 3 次（1/3/6 秒）足够覆盖 DexKit 首次加载慢、Tinker 未 attach 完这类瞬时失败，
     * 又能在真正不兼容时快速收敛，避免无意义的重试风暴。
     */
    private const val MAX_RETRY_ATTEMPTS = 3
    private const val MAX_RETRY_DELAY_MS = 60_000L

    /**
     * 重试预算耗尽后的冷却时间。
     *
     * 同一代次内重复调度（installAll + DexReady）不应重新给预算，否则会形成
     * 无限重试循环；只有过了这个冷却期才视为"用户重新打开了功能"。
     */
    private const val RETRY_BUDGET_COOLDOWN_MS = 30_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val states = ConcurrentHashMap<String, State>()
    private val readyLevel = AtomicInteger(0)
    private val stateLock = Any()
    private val dexKitExecutionLock = Any()
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>(),
        { runnable -> Thread(runnable, "Hchat-DexInstall").apply {
            isDaemon = true
            // 冷启动必现掉帧的主因之一：这个线程串行跑几十个功能的 DexKit 全量定位，
            // 是纯 CPU 密集任务。默认优先级和微信主线程相同，会直接抢首页渲染的 CPU，
            // 表现是"每次冷启动首页持续掉帧"。降到后台优先级，让主线程先跑完首帧。
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        } }
    )

    fun schedule(
        key: String,
        label: String = key,
        stage: Stage = Stage.BRIDGE,
        priority: Int = 0,
        installer: () -> Boolean
    ) = synchronized(stateLock) {
        if (key.isBlank()) return@synchronized
        val registry = HookRegistry.get()
        val generation = registry.installationGeneration()
        if (!registry.isInstallationGenerationActive(generation)) return@synchronized
        val previous = states[key]
        val state = if (previous == null || previous.generation != generation) {
            previous?.let(::cancelState)
            State(label, generation).also { states[key] = it }
        } else previous
        if (state.installed.get()) return@synchronized
        state.label = label
        state.stage = stage
        state.priority = priority
        state.installer = installer
        if (!isReady(stage)) return@synchronized
        if (!state.running.get() && !state.retryScheduled.get() &&
            state.retryIndex.get() >= MAX_RETRY_ATTEMPTS
        ) {
            // 预算耗尽后，不要"再调一次 schedule 就清零"。
            // 现实里 installAll 和 Events.DexReady 会对同一个功能各调一次 schedule，
            // 原来的写法于是形成"耗尽→清零→再重试 3 次"的循环，正是这次改动想消灭的
            // 重试风暴。只有距离上次耗尽超过冷却时间才重新给一轮预算，
            // 并对齐语义：这通常意味着用户重新打开了功能或微信重启。
            val exhaustedAt = state.retryExhaustedAt
            if (exhaustedAt == 0L || SystemClock.elapsedRealtime() - exhaustedAt >= RETRY_BUDGET_COOLDOWN_MS) {
                state.retryIndex.set(0)
                state.retryExhaustedAt = 0L
            }
        }
        runNow(key, state)
    }

    @JvmStatic
    fun scheduleTask(key: String, label: String, stage: Stage, priority: Int, installer: Callable<Boolean>) {
        schedule(key, label, stage, priority) { installer.call() == true }
    }

    @JvmStatic fun markDexBridgeReady() { advanceReady(Stage.BRIDGE) }
    @JvmStatic fun markDexReady() { markDexWarmupReady() }
    @JvmStatic fun markDexWarmupReady() { advanceReady(Stage.WARMUP) }

    @JvmStatic
    fun runDexKitTask(task: Runnable) {
        val registry = HookRegistry.get()
        val generation = registry.installationGeneration()
        synchronized(dexKitExecutionLock) {
            registry.withInstallationGeneration(generation) { task.run() }
        }
    }

    private fun advanceReady(stage: Stage) {
        while (true) {
            val current = readyLevel.get()
            if (current >= stage.level) break
            if (readyLevel.compareAndSet(current, stage.level)) break
        }
        runReadyStates()
    }

    fun markInstalled(key: String) = synchronized(stateLock) {
        val registry = HookRegistry.get()
        val generation = registry.installationGeneration()
        if (!registry.isInstallationGenerationActive(generation)) return@synchronized
        val previous = states[key]
        val state = if (previous == null || previous.generation != generation) {
            previous?.let(::cancelState)
            State(key, generation).also { states[key] = it }
        } else previous
        cancelState(state)
        state.installed.set(true)
    }

    /** 不等待无法中断的 DexKit 调用；它返回后由旧代次租约拒绝后续挂钩。 */
    fun cancelAll() = synchronized(stateLock) {
        val previous = states.values.toList()
        states.clear()
        previous.forEach(::cancelState)
        // 只取消请求，现有 DexKit 的就绪阶段不随请求清理而倒退。
    }

    private fun cancelState(state: State) {
        state.installer = null
        state.retryTask?.let(mainHandler::removeCallbacks)
        state.retryTask = null
        state.retryScheduled.set(false)
        state.future?.let {
            it.cancel(false)
            executor.remove(it)
        }
        state.future = null
    }

    private fun isCurrent(key: String, state: State): Boolean =
        states[key] === state && HookRegistry.get().isInstallationGenerationActive(state.generation)

    private fun runNow(key: String, state: State) = synchronized(stateLock) {
        if (!isCurrent(key, state) || !isReady(state.stage) || state.installed.get() || state.installer == null) {
            return@synchronized
        }
        if (!state.running.compareAndSet(false, true)) return@synchronized
        val future = FutureTask<Unit> { runInstaller(key, state) }
        state.future = future
        try {
            executor.execute(future)
        } catch (error: Throwable) {
            state.future = null
            state.running.set(false)
            throw error
        }
    }

    private fun runInstaller(key: String, state: State) {
        var ok = false
        try {
            synchronized(dexKitExecutionLock) {
                val installer = synchronized(stateLock) {
                    if (isCurrent(key, state) && !state.installed.get()) state.installer else null
                } ?: return
                ok = HookRegistry.get().withInstallationGeneration(state.generation) { installer() }
            }
        } catch (_: CancellationException) {
            // 卸载与正在执行的定位竞争时，旧租约失效属于正常取消。
        } catch (error: Throwable) {
            HLog.e("$TAG ${state.label} 安装异常: ${error.message}", error)
        } finally {
            synchronized(stateLock) {
                state.future = null
                state.running.set(false)
                if (states[key] === state) {
                    if (!isCurrent(key, state)) {
                        states.remove(key, state)
                        cancelState(state)
                    } else if (ok || state.installed.get()) {
                        state.installed.set(true)
                        state.installer = null
                        state.retryTask?.let(mainHandler::removeCallbacks)
                        state.retryTask = null
                        state.retryScheduled.set(false)
                    } else scheduleRetry(key, state)
                }
            }
        }
    }

    private fun runReadyStates() = synchronized(stateLock) {
        states.entries.sortedWith(
            compareBy<Map.Entry<String, State>> { it.value.stage.level }
                .thenBy { it.value.priority }.thenBy { it.key }
        ).forEach { (key, state) -> runNow(key, state) }
    }

    private fun scheduleRetry(key: String, state: State) {
        if (!isCurrent(key, state) || state.installed.get()) return
        if (!state.retryScheduled.compareAndSet(false, true)) return
        val index = state.retryIndex.getAndIncrement()
        if (index >= MAX_RETRY_ATTEMPTS) {
            state.retryScheduled.set(false)
            state.installer = null
            // 记录预算耗尽时刻，供 schedule() 判断是否该重新给预算。
            state.retryExhaustedAt = SystemClock.elapsedRealtime()
            HLog.e("$TAG ${state.label} 多次安装失败，停止本轮重试")
            return
        }
        val retry = Runnable {
            synchronized(stateLock) {
                if (!isCurrent(key, state)) return@synchronized
                state.retryTask = null
                state.retryScheduled.set(false)
                runNow(key, state)
            }
        }
        state.retryTask = retry
        mainHandler.postDelayed(retry, retryDelayMs(index))
    }

    private fun retryDelayMs(attempt: Int): Long {
        if (attempt <= 0) return 1_000L
        if (attempt == 1) return 3_000L
        return minOf(MAX_RETRY_DELAY_MS, 3_000L * (1L shl (attempt - 1)))
    }

    private fun isReady(stage: Stage): Boolean = readyLevel.get() >= stage.level

    private class State(@Volatile var label: String, val generation: Long) {
        @Volatile var stage: Stage = Stage.BRIDGE
        @Volatile var priority: Int = 0
        @Volatile var installer: (() -> Boolean)? = null
        var future: FutureTask<Unit>? = null
        var retryTask: Runnable? = null
        val installed = AtomicBoolean(false)
        val running = AtomicBoolean(false)
        val retryScheduled = AtomicBoolean(false)
        val retryIndex = AtomicInteger(0)
        /** 重试预算耗尽的时刻；0 表示从未耗尽。 */
        @Volatile var retryExhaustedAt: Long = 0L
    }
}
