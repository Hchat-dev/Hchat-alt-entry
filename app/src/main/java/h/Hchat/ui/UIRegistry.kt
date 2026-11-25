package h.Hchat.ui

import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

object UIRegistry {
    // 注册发生在 installAll 的安装线程，读取/注销发生在设置页主线程。
    // 裸 ArrayList 上并发 removeIf / 复制会抛 ArrayIndexOutOfBoundsException，
    // 或出现设置项重复、丢失。
    private val providers = CopyOnWriteArrayList<FeatureSettingsProvider>()

    /**
     * 稳定快照。
     *
     * 设置页拿这个 List 作为 `remember` 的 key。原来每次都 `ArrayList(providers)`
     * 返回新实例，导致 key 永远变、`remember` 永远失效 —— 每次重组都要重跑 90+ 次
     * 分组过滤与 indexOf 排序，设置页一拨开关就卡一下。
     *
     * 现在只在注册/注销/清空时置脏，下次读取重建一次，其余时候返回同一个实例，
     * Compose 可以正常跳过。
     */
    @Volatile
    private var snapshot: List<FeatureSettingsProvider> = emptyList()
    private val snapshotDirty = AtomicBoolean(true)

    @JvmStatic
    fun get(): UIRegistry = this

    fun registerProvider(provider: FeatureSettingsProvider?) {
        if (provider == null) return
        unregisterProvider(provider.featureId())
        providers.add(provider)
        snapshotDirty.set(true)
    }

    fun unregisterProvider(featureId: String?) {
        if (featureId == null) return
        providers.removeIf { provider -> featureId == provider.featureId() }
        snapshotDirty.set(true)
    }

    fun getAllProviders(): List<FeatureSettingsProvider> {
        if (snapshotDirty.compareAndSet(true, false)) {
            snapshot = Collections.unmodifiableList(ArrayList(providers))
        }
        return snapshot
    }

    fun clear() {
        providers.clear()
        snapshotDirty.set(true)
    }
}
