package de.robv.android.xposed.callbacks

import android.content.pm.ApplicationInfo

/**
 * `XC_LoadPackage.LoadPackageParam` 的等价实现。
 *
 * LibXposed 用 `onPackageReady(PackageReadyParam)` 取代了旧的 `handleLoadPackage`：
 * 前者提供 `getPackageName()` / `getClassLoader()` / `getApplicationInfo()` /
 * `isFirstPackage()`，字段名不同但语义一一对应。这里保留旧字段名，让
 * `ModuleEntry` 里的 `lpparam.packageName` / `lpparam.classLoader` / `lpparam.processName`
 * 等 25 处引用无需改写。
 */
class XC_LoadPackage {
    class LoadPackageParam internal constructor(
        @JvmField val packageName: String,
        @JvmField val processName: String,
        @JvmField val classLoader: ClassLoader,
        @JvmField val appInfo: ApplicationInfo,
        /** 是否是本进程加载的第一个包（旧 API 无此概念，默认 true）。 */
        @JvmField val isFirstApplication: Boolean
    )
}
