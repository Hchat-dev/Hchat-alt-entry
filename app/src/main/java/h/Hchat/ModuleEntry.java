package h.Hchat;

import android.app.Application;
import android.content.Context;
import android.os.Process;

import androidx.annotation.NonNull;

import h.Hchat.crash.CrashReportRuntime;
import h.Hchat.compat.RuntimeHooks;
import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.api.media.WeChatImageApi;
import h.Hchat.hooks.core.DexInstallScheduler;
import h.Hchat.hooks.core.FeatureContext;
import h.Hchat.hooks.core.FeatureManager;
import h.Hchat.hooks.core.FeatureRegistry;
import h.Hchat.hooks.core.HookRegistry;
import h.Hchat.hooks.items.fakelocation.FakeLocationFeature;
import h.Hchat.hooks.items.custombottombar.CustomBottomBarFeature;
import h.Hchat.hooks.items.conversationtabs.ConversationTabsFeature;
import h.Hchat.hooks.items.hotupdate.DisableHotUpdateFeature;
import h.Hchat.hooks.items.miniprogrambaselib.FakeMiniProgramBaseLibFeature;
import h.Hchat.hooks.items.miniprogramsplashad.SkipGlobalMiniProgramSplashAdsFeature;
import h.Hchat.hooks.items.miniprogramvideoad.SkipMiniProgramVideoAdsFeature;
import h.Hchat.hooks.items.subscribe.RemoveSubscribeAdsFeature;
import h.Hchat.hooks.items.floatingshortcut.FloatingShortcutRuntime;
import h.Hchat.hooks.items.script.ScriptPluginRuntime;
import h.Hchat.hooks.items.script.agent.CodexWebGuard;
import h.Hchat.hooks.items.script.agent.ModuleResourceMenuFallback;
import h.Hchat.hooks.items.script.agent.ScriptPluginAgentLocalReverseTools;
import h.Hchat.hooks.items.tablet.WeChatTabletFeature;
import h.Hchat.loader.utils.NativeLibraryLoader;
import h.Hchat.preferences.ConfigStore;
import h.Hchat.preferences.TermsGate;
import h.Hchat.dexkit.DexBridgeHolder;
import h.Hchat.event.EventBus;
import h.Hchat.ui.UIRegistry;
import h.Hchat.utils.HLog;

import org.luckypray.dexkit.DexKitBridge;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口（LibXposed API 102）。
 *
 * 启动流程：
 * 1. onPackageReady → 过滤目标包 + 目标进程
 * 2. 小程序子进程安装必要的缓存 Hook 和显式声明的小程序脚本插件
 * 3. 主进程在 Tinker attach 后优先安装自定义底栏 Hook
 * 4. Application.onCreate 后异步初始化完整模块
 *
 * 进程过滤：普通功能只在微信主进程加载；:appbrand* 使用独立的轻量运行时。
 *
 * 迁移说明（Xposed API 82 → LibXposed API 102）：
 * - 入口从 `IXposedHookLoadPackage.handleLoadPackage` 改为继承 {@link XposedModule}
 *   并覆写 {@link XposedModuleInterface#onPackageReady}；
 * - 旧 `XC_LoadPackage.LoadPackageParam` 的四个字段（packageName / processName /
 *   classLoader / appInfo）由 `PackageReadyParam` 一一对应提供，兼容层
 *   `de.robv.android.xposed.callbacks.XC_LoadPackage` 负责拼装，
 *   因此模块内部 25 处 `lpparam.*` 引用无需改动；
 * - `XC_MethodHook` / `XposedBridge` / `XposedHelpers` 保留旧类名，但实现全部
 *   改走 LibXposed 的 `hook(...).intercept(chain)` 与 Invoker（见 de.robv.android.xposed 包）；
 * - `assets/xposed_init` 改为 `META-INF/xposed/java_init.list`。
 */
public class ModuleEntry extends XposedModule {
    private static final String TAG = "[Hchat:Entry]";
    private static final String WECHAT_PKG = "com.tencent.mm";
    private static final Object DEXKIT_CREATE_LOCK = new Object();
    private static final Map<String, DexKitBridge> DEXKIT_BRIDGES = new ConcurrentHashMap<>();

    /**
     * DexKit native 引擎的空闲回收窗口。
     *
     * 必须覆盖整个安装窗口：几十个功能的定位任务是串行跑的，失败重试退避为
     * 1/3/6 秒，窗口过短会让引擎"释放—重扫—再释放"。重扫要把整包 dex 重新解析，
     * 峰值内存比保持驻留更高，所以这里给 60 秒。
     */
    private static final long DEXKIT_IDLE_RELEASE_MS = 60_000L;

    /**
     * 创建后台初始化线程，统一降到后台优先级。
     *
     * 冷启动必现掉帧的主因：这些线程跑的是 DexKit 全 dex 解析 / 反射定位这类
     * CPU 密集任务，默认优先级和微信主线程相同（0），于是和首页渲染抢 CPU。
     * 表现就是"每次重新打开微信后首页持续掉帧一段时间"，而不是偶发。
     *
     * 同项目的 SettingsUiPrewarm 早就设了 THREAD_PRIORITY_LOWEST，真正吃 CPU 的
     * 这几个线程反而没设——这里统一补齐。用 BACKGROUND 而不是 LOWEST：
     * 后台任务仍需在合理时间内完成（功能安装不能拖太久），LOWEST 可能饿死。
     */
    private static Thread backgroundThread(Runnable task, String name) {
        return new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        }, name);
    }

    // 全局基础设施实例（模块生命周期内单例）
    private final EventBus eventBus = EventBus.get();
    private final UIRegistry uiRegistry = UIRegistry.get();

    private FeatureManager featureManager;
    private FeatureContext featureContext;

    /** 进程名：onModuleLoaded 时记录，供 lpparam 拼装使用。 */
    @NonNull
    private String processName = "";

    public ModuleEntry() {
        // LibXposed 反射实例化入口：先无参构造，再由框架 attachFramework 注入。
    }

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam loadedParam) {
        super.onModuleLoaded(loadedParam);
        processName = loadedParam.getProcessName();
        // 兼容层绑定：XC_MethodHook / XposedBridge 等旧入口在底层全部转走 LibXposed。
        RuntimeHooks.INSTANCE.bind(this);
        RuntimeHooks.INSTANCE.log("[Hchat:Entry] onModuleLoaded: " + processName);
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam readyParam) {
        // 拼装旧 LoadPackageParam：字段一一对应，25 处 lpparam.* 引用无需改动。
        XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(
                readyParam.getPackageName(),
                processName,
                readyParam.getClassLoader(),
                readyParam.getApplicationInfo(),
                readyParam.isFirstPackage());
        handleLoadPackage(lpparam);
    }

    /** 兼容旧 handleLoadPackage 的主流程；包名/进程过滤逻辑保持不变。 */
    private void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 1. 包名过滤
        if (!isWeChatPackage(lpparam.packageName)) return;
        if (!Process.is64Bit()) {
            HLog.e(TAG + " 当前版本仅支持 ARM64 微信进程，跳过32位进程: " + lpparam.processName);
            return;
        }

        // 热更新加载发生在 Tinker attach 阶段，必须早于普通功能初始化安装。
        installHotUpdateEarlyHook(lpparam);

        // 平板模式需要在微信所有进程保持一致，避免升级/降级后登录态校验看到不同设备形态。
        installTabletEarlyHook(lpparam);

        // 2. 子进程只安装明确需要的最小 Hook。
        if (!isMainProcess(lpparam)) {
            if (isAppBrandProcess(lpparam)) {
                installAppBrandProcessHook(lpparam);
            }
            return;
        }

        // 3. Mars CDN 管理器可能早于模块 API 初始化创建，先按稳定类名捕获实例。
        WeChatImageApi.installMarsCdnManagerHook(
                lpparam.classLoader,
                message -> HLog.e("[Hchat:WechatApi] " + message));

        // 底栏在 LauncherUI 首帧前创建，必须早于完整 DexKit 初始化安装 Hook。
        installCustomBottomBarEarlyHook(lpparam);

        // 4. Hook Application.onCreate，等 Application 完全初始化后再启动模块
        HookRegistry.get().add(XposedHelpers.findAndHookMethod(
                Application.class,
                "onCreate",
                new XC_MethodHook() {
                    @Override
                    public void beforeHookedMethod(MethodHookParam param) {
                        Application app = (Application) param.thisObject;
                        if (TermsGate.INSTANCE.isAccepted(app)) {
                            CrashReportRuntime.prepare(app);
                        }
                    }

                    @Override
                    public void afterHookedMethod(MethodHookParam param) {
                        Application app = (Application) param.thisObject;
                        if (TermsGate.INSTANCE.isAccepted(app)) {
                            CrashReportRuntime.install(app, ModuleEntry.this.getClass().getClassLoader());
                            // 标签栏先捕获首页根视图，不能等待后台 DexKit 初始化后再挂载。
                            try {
                                ConversationTabsFeature.installEarly(resolveHostClassLoader(app, lpparam));
                            } catch (Throwable e) {
                                HLog.e(TAG + " 标签分组早期安装失败", e);
                            }
                            // 悬浮入口不依赖 DexKit，先注册生命周期，避免错过首个 Activity。
                            try {
                                FloatingShortcutRuntime.INSTANCE.install(app);
                            } catch (Throwable e) {
                                HLog.e(TAG + " 悬浮快捷菜单早期安装失败", e);
                            }
                        }
                        installCustomBottomBarEarly(app, resolveHostClassLoader(app, lpparam));

                        // 「记录运行日志」不能等 initModule 的后台线程：
                        // 那条线程要先 loadDexKit 再全量解析 dex（秒级），一旦它被拖慢，
                        // 设置页看到的就是"日志抓不到"。这里在 onCreate 返回时就恢复采集，
                        // initCapture 自身幂等，后面 initModule 再调一次不会有额外开销。
                        try {
                            HLog.initCapture(app);
                        } catch (Throwable e) {
                            HLog.e(TAG + " 日志采集早期初始化失败: " + e.getMessage(), e);
                        }

                        // 公众号信息流广告：onCreate 之后立刻用独立后台线程安装，
                        // 早于 initModule 的 DexKit 全量解析，但不在 attach 阶段做重活。
                        installSubscribeAdsAfterStartup(app, lpparam);

                        backgroundThread(() -> initModule(app, lpparam), "Hchat-Init").start();
                    }
                }
        ));
    }

    // ============ 包名与进程判断 ============

    /**
     * 判断是否为目标微信包。
     * 支持 com.tencent.mm 以及以 com.tencent.mm 开头的分身包名。
     */
    private boolean isWeChatPackage(String packageName) {
        return packageName != null && packageName.startsWith(WECHAT_PKG);
    }

    /**
     * 判断是否为主进程。
     * 微信主进程的 processName 等于 packageName。
     */
    private boolean isMainProcess(XC_LoadPackage.LoadPackageParam lpparam) {
        String process = lpparam.processName;
        return process == null || process.equals(lpparam.packageName);
    }

    private boolean isAppBrandProcess(XC_LoadPackage.LoadPackageParam lpparam) {
        String process = lpparam.processName;
        String packageName = lpparam.packageName;
        return process != null && packageName != null && process.startsWith(packageName + ":appbrand");
    }

    private void installAppBrandProcessHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            HookRegistry.get().add(XposedHelpers.findAndHookMethod(
                    "com.tencent.tinker.loader.app.TinkerApplication",
                    lpparam.classLoader,
                    "onBaseContextAttached",
                    Context.class,
                    long.class,
                    long.class,
                    new XC_MethodHook() {
                        @Override
                        public void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.args[0];
                            ClassLoader loader = resolveTinkerClassLoader(param.thisObject);
                            if (loader == null) {
                                loader = resolveHostClassLoader(context, lpparam);
                            }
                            installAppBrandProcessHook(context, loader, lpparam, false);
                        }
                    }
            ));
        } catch (Throwable e) {
            HLog.e(TAG + " 小程序进程早期入口安装失败: " + e.getMessage(), e);
        }
        try {
            HookRegistry.get().add(XposedHelpers.findAndHookMethod(
                    Application.class,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        public void afterHookedMethod(MethodHookParam param) {
                            Application app = (Application) param.thisObject;
                            ClassLoader loader = resolveHostClassLoader(app, lpparam);
                            installAppBrandProcessHook(app, loader, lpparam, true);
                        }
                    }
            ));
        } catch (Throwable e) {
            HLog.e(TAG + " 小程序进程兜底入口安装失败: " + e.getMessage(), e);
        }
    }

    private void installAppBrandProcessHook(Context context,
                                            ClassLoader classLoader,
                                            XC_LoadPackage.LoadPackageParam lpparam,
                                            boolean applicationReady) {
        if (!TermsGate.INSTANCE.isAccepted(context)) return;
        // 子进程也接入同一份日志存储；初始化只排队，不阻塞 attach 主线程。
        HLog.initCapture(context);
        boolean failed = false;
        if (FakeMiniProgramBaseLibFeature.isEnabled(context)
                && !FakeMiniProgramBaseLibFeature.installAppBrandProcessHook(context, classLoader)) {
            failed = true;
        }
        if (SkipMiniProgramVideoAdsFeature.isEnabled(context)
                && !SkipMiniProgramVideoAdsFeature.install(context, classLoader)) {
            failed = true;
        }
        if (SkipGlobalMiniProgramSplashAdsFeature.isEnabled(context)) {
            SkipGlobalMiniProgramSplashAdsFeature.scheduleAppBrandProcessHook(context, classLoader);
        }
        if (FakeLocationFeature.isEnabled(context)
                && !FakeLocationFeature.installAppBrandProcessHook(context, classLoader)) {
            failed = true;
        }
        if (applicationReady) {
            ScriptPluginRuntime.installAppBrandProcess(
                    context,
                    classLoader,
                    lpparam.processName
            );
        }
        if (failed && applicationReady) {
            HLog.e(TAG + " 小程序进程Hook安装失败: " + lpparam.processName);
        }
    }

    // ============ 模块初始化 ============

    private void installHotUpdateEarlyHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            HookRegistry.get().add(XposedHelpers.findAndHookMethod(
                    "com.tencent.tinker.loader.app.TinkerApplication",
                    lpparam.classLoader,
                    "onBaseContextAttached",
                    Context.class,
                    long.class,
                    long.class,
                    new XC_MethodHook() {
                        @Override
                        public void beforeHookedMethod(MethodHookParam param) {
                            installHotUpdateForAttach(lpparam, param, "before");
                        }

                        @Override
                        public void afterHookedMethod(MethodHookParam param) {
                            installHotUpdateForAttach(lpparam, param, "after");
                        }
                    }
            ));
        } catch (Throwable e) {
            HLog.e(TAG + " 热更新早期入口安装失败: " + e.getMessage(), e);
        }
    }

    private void installHotUpdateForAttach(XC_LoadPackage.LoadPackageParam lpparam,
                                           XC_MethodHook.MethodHookParam param,
                                           String stage) {
        try {
            Context context = (Context) param.args[0];
            if (!TermsGate.INSTANCE.isAccepted(context)) return;
            if (!DisableHotUpdateFeature.isEnabled(context)) return;
            if ("before".equals(stage)) {
                DisableHotUpdateFeature.installEarly(context, lpparam.classLoader);
                return;
            }
            backgroundThread(() -> {
                DexInstallScheduler.runDexKitTask(() -> {
                    try {
                        ClassLoader hostClassLoader = resolveTinkerClassLoader(param.thisObject);
                        if (hostClassLoader == null) {
                            hostClassLoader = resolveHostClassLoader(context, lpparam);
                        }
                        NativeLibraryLoader nativeLibraryLoader = new NativeLibraryLoader();
                        nativeLibraryLoader.loadDexKit(context, ModuleEntry.this.getClass().getClassLoader());
                        DexKitBridge dexKit = createDexKitBridge(hostClassLoader, lpparam.appInfo.sourceDir);
                        DisableHotUpdateFeature.install(context, hostClassLoader, dexKit);
                    } catch (Throwable e) {
                        HLog.e(TAG + " 热更新后置安装失败: " + e.getMessage(), e);
                    }
                });
            }, "Hchat-HotUpdateHook").start();
        } catch (SecurityException e) {
            throw e;
        } catch (Throwable e) {
            HLog.e(TAG + " 热更新早期Hook失败(" + stage + "): " + e.getMessage(), e);
        }
    }

    private void installTabletEarlyHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            HookRegistry.get().add(XposedHelpers.findAndHookMethod(
                    "com.tencent.tinker.loader.app.TinkerApplication",
                    lpparam.classLoader,
                    "onBaseContextAttached",
                    Context.class,
                    long.class,
                    long.class,
                    new XC_MethodHook() {
                        @Override
                        public void beforeHookedMethod(MethodHookParam param) {
                            installTabletHookForAttach(lpparam, param, "before");
                        }

                        @Override
                        public void afterHookedMethod(MethodHookParam param) {
                            installTabletHookForAttach(lpparam, param, "after");
                        }
                    }
            ));
        } catch (Throwable e) {
            HLog.e(TAG + " 平板模式早期入口安装失败: " + e.getMessage(), e);
        }
    }

    private void installCustomBottomBarEarlyHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            HookRegistry.get().add(XposedHelpers.findAndHookMethod(
                    "com.tencent.tinker.loader.app.TinkerApplication",
                    lpparam.classLoader,
                    "onBaseContextAttached",
                    Context.class,
                    long.class,
                    long.class,
                    new XC_MethodHook() {
                        @Override
                        public void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.args[0];
                            ClassLoader classLoader = resolveTinkerClassLoader(param.thisObject);
                            if (classLoader == null) {
                                classLoader = resolveHostClassLoader(context, lpparam);
                            }
                            installCustomBottomBarEarly(context, classLoader);
                        }
                    }
            ));
        } catch (Throwable e) {
            HLog.e(TAG + " 自定义底栏早期入口安装失败: " + e.getMessage(), e);
        }
    }

    private void installCustomBottomBarEarly(Context context, ClassLoader classLoader) {
        try {
            if (!TermsGate.INSTANCE.isAccepted(context)) return;
            CustomBottomBarFeature.installEarly(context, classLoader);
        } catch (Throwable e) {
            HLog.e(TAG + " 自定义底栏早期Hook失败: " + e.getMessage(), e);
        }
    }

    /**
     * 公众号信息流广告的安装。
     *
     * ## 为什么不在 Tinker attach 阶段装（6.6.6.5 的做法，已回退）
     *
     * 1. attach 阶段 ClassLoader 还没切到补丁后的版本，这时候 Class.forName 拿到的可能是
     *    随后被替换掉的旧 Class，hook 会挂在废弃类上——白装，还查不出来；
     * 2. 这次安装要做最多 10 次 XposedBridge.hookMethod，每次都 suspend 全部线程。
     *    在 Application 构造期间做，会把启动主线程长时间按住，日志采集线程也起不来，
     *    表现就是"设置页抓不到日志"；
     * 3. 提前把 wx.i1 / mu0.i 这类 Flutter 运行时类拉进内存并解析整张方法表，
     *    这些反射对象和类结构会一直常驻，进程内存明显上涨。
     *
     * ## 为什么要早于 initModule
     *
     * 冷启动时 native 会拿本地缓存主动推一轮广告卡。这一轮发生在用户点开订阅号信息流时，
     * 远晚于 onCreate；而 initModule 的后台线程此时还卡在 DexKit 全量解析上（秒级）。
     * 所以 onCreate 之后单独起一条只做反射的线程，既足够早，又不占主线程。
     */
    private void installSubscribeAdsAfterStartup(Context context, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (!TermsGate.INSTANCE.isAccepted(context)) return;
            if (!RemoveSubscribeAdsFeature.isEnabled(context)) return;
            final ClassLoader classLoader = resolveHostClassLoader(context, lpparam);
            backgroundThread(() -> {
                try {
                    RemoveSubscribeAdsFeature.install(context, classLoader);
                } catch (Throwable e) {
                    HLog.e(TAG + " 信息流广告安装失败: " + e.getMessage(), e);
                }
            }, "Hchat-SubscribeAds").start();
        } catch (Throwable e) {
            HLog.e(TAG + " 信息流广告安装线程启动失败: " + e.getMessage(), e);
        }
    }

    private void installTabletHookForAttach(XC_LoadPackage.LoadPackageParam lpparam,
                                            XC_MethodHook.MethodHookParam param,
                                            String stage) {
        try {
            Context context = (Context) param.args[0];
            if (!TermsGate.INSTANCE.isAccepted(context)) return;
            if (!WeChatTabletFeature.isEnabled(context)) return;
            ClassLoader resolvedClassLoader = resolveTinkerClassLoader(param.thisObject);
            if (resolvedClassLoader == null) {
                resolvedClassLoader = resolveHostClassLoader(context, lpparam);
            }
            final ClassLoader hostClassLoader = resolvedClassLoader;
            // 缓存安装不使用 DexKit，避免主线程等待后台预热持有的串行门。
            if (WeChatTabletFeature.installCached(context, hostClassLoader)) return;
            DexInstallScheduler.runDexKitTask(() -> {
                if (WeChatTabletFeature.installCached(context, hostClassLoader)) return;
                if (!"after".equals(stage)) {
                    if (!DisableHotUpdateFeature.isEnabled(context)) return;
                    NativeLibraryLoader nativeLibraryLoader = new NativeLibraryLoader();
                    nativeLibraryLoader.loadDexKit(context, ModuleEntry.this.getClass().getClassLoader());
                    DexKitBridge dexKit = createDexKitBridge(lpparam.classLoader, lpparam.appInfo.sourceDir);
                    WeChatTabletFeature.install(context, lpparam.classLoader, dexKit);
                    return;
                }
                NativeLibraryLoader nativeLibraryLoader = new NativeLibraryLoader();
                nativeLibraryLoader.loadDexKit(context, ModuleEntry.this.getClass().getClassLoader());
                DexKitBridge dexKit = createDexKitBridge(hostClassLoader, lpparam.appInfo.sourceDir);
                WeChatTabletFeature.install(context, hostClassLoader, dexKit);
            });
        } catch (SecurityException e) {
            throw e;
        } catch (Throwable e) {
            HLog.e(TAG + " 平板模式早期Hook失败(" + stage + "): " + e.getMessage(), e);
        }
    }

    private ClassLoader resolveTinkerClassLoader(Object tinkerApplication) {
        try {
            Object value = XposedHelpers.callMethod(tinkerApplication, "getClassLoader");
            return value instanceof ClassLoader ? (ClassLoader) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void initModule(Context hostContext, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 0. 尽早恢复日志存储（崩溃证据与看门狗面包屑仍依赖它），此时只是一次配置读取。
            HLog.initCapture(hostContext);

            // 1. 加载 Native 库（DexKit）
            NativeLibraryLoader nativeLibraryLoader = new NativeLibraryLoader();
            nativeLibraryLoader.loadDexKit(hostContext, getClass().getClassLoader());
            nativeLibraryLoader.loadSilkCodec(hostContext, getClass().getClassLoader());

            // 2. 创建模块自身 Context
            Context moduleContext = createModuleContext(hostContext);
            ConfigStore configStore = new ConfigStore(hostContext);

            ModuleResourceMenuFallback.INSTANCE.install(resolveHostClassLoader(hostContext, lpparam));

            DexInstallScheduler.runDexKitTask(() -> {
                // 3. 初始化 DexKit。优先使用 Tinker 之后的运行时 ClassLoader。
                String apkPath = lpparam.appInfo.sourceDir;
                ClassLoader hostClassLoader = resolveHostClassLoader(hostContext, lpparam);
                DexKitBridge dexKit = createDexKitBridge(hostClassLoader, apkPath);
                DexFinder finder = new DexFinder(dexKit, hostClassLoader, hostContext);
                WeChatImageApi.installMarsCdnManagerHook(
                        finder.marsCdnManagerClass,
                        message -> HLog.e("[Hchat:WechatApi] " + message));

                // 4. 创建共享 DexKit 服务
                DexBridgeHolder dexBridgeHolder = new DexBridgeHolder(
                        dexKit, finder, hostClassLoader, apkPath);
                ScriptPluginAgentLocalReverseTools.install(dexBridgeHolder, hostContext);

                // 5. 创建统一上下文
                featureContext = new FeatureContext(
                        hostContext,
                        moduleContext != null ? moduleContext : hostContext,
                        hostClassLoader,
                        lpparam,
                        dexKit,
                        finder,
                        eventBus,
                        configStore,
                        dexBridgeHolder,
                        uiRegistry
                );

                // 6. 注册并安装所有功能模块
                featureManager = FeatureRegistry.createDefaultManager();
                featureManager.installAll(featureContext);

                // 6.5 后台预热设置页 UI 依赖的 Compose/miuix 类：
                // 设置页首次打开要在微信进程主线程加载几百个类，是「进设置页卡一下」
                // 的主因之一；类加载线程安全，挪到后台低优先级线程做完，
                // 用户首次进入时只剩组合计算本身。失败静默，不影响任何功能。
                try {
                    h.Hchat.ui.miuix.SettingsUiPrewarm.INSTANCE.prewarm(featureContext.hostClassLoader());
                } catch (Throwable t) {
                    HLog.e(TAG + " UI 类预热失败(忽略): " + t);
                }

                // codex 网页版保活：微信进程重启后把上次开着的服务自动拉回来
                CodexWebGuard.INSTANCE.restore(hostContext);

                // 7. 防崩兜底：微信 8.0.76 消息描述文本方法对 null 调 isEmpty() 崩溃（收到特殊小程序电商卡片消息触发）
                installMsgDescTextFallback(finder);
            });

        } catch (Throwable e) {
            HLog.e(TAG + " 初始化失败: " + e, e);
        }
    }

    /**
     * 防崩兜底：微信 8.0.76 的 pt0.r.l(Context, boolean) 存在未初始化局部变量，
     * 收到特殊小程序电商卡片消息（type 44 + ecsInfo）时 String.isEmpty() 对 null 调用导致崩溃。
     * 这里 hook 该方法，手动调用原方法并捕获异常，崩溃时兜底返回空串。
     */
    private void installMsgDescTextFallback(DexFinder finder) {
        try {
            finder.resolveMsgDescTextApi();
            if (finder.msgDescTextMethods.isEmpty()) {
                HLog.e("[Hchat:WechatApi] 消息描述防崩兜底未定位到方法");
                return;
            }
            for (Method m : finder.msgDescTextMethods) {
                if (m == null) continue;
                try {
                    HookRegistry.get().add(XposedHelpers.findAndHookMethod(
                            m.getDeclaringClass(), m.getName(), Context.class, boolean.class,
                            new XC_MethodHook() {
                                @Override
                                public void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    try {
                                        // 绝不能用 Method.invoke：反射调用仍会经过 hook 入口，
                                        // 形成 before → invoke → before 的无限递归，直到 StackOverflowError。
                                        // 内层 catch 把溢出吞成 setResult("") 后逐层返回，结果是该方法
                                        // 此后恒返回空串（消息描述文本丢失），且每次调用白烧一整条栈。
                                        // invokeOriginalMethod 是 Xposed 提供的直通原实现入口，不会重入 hook。
                                        param.setResult(XposedBridge.invokeOriginalMethod(
                                                param.method, param.thisObject, param.args));
                                    } catch (Throwable t) {
                                        param.setResult("");
                                    }
                                }
                            }
                    ));
                } catch (Throwable e) {
                    HLog.e("[Hchat:WechatApi] 消息描述防崩兜底安装失败: " + m, e);
                }
            }
        } catch (Throwable e) {
            HLog.e(TAG + " 消息描述防崩兜底安装失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建模块自身的 Context。
     * 通过 createPackageContext 获取模块自身的 Context，
     * 以便访问模块资源。
     */
    private Context createModuleContext(Context hostContext) {
        try {
            return hostContext.createPackageContext(
                    "h.Hchat",
                    Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE
            );
        } catch (Throwable e) {
            return null;
        }
    }

    private ClassLoader resolveHostClassLoader(Context hostContext,
                                               XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader lpClassLoader = lpparam.classLoader;
        ClassLoader contextClassLoader = null;
        ClassLoader threadClassLoader = null;
        try {
            contextClassLoader = hostContext.getClassLoader();
        } catch (Throwable ignored) {}
        try {
            threadClassLoader = Thread.currentThread().getContextClassLoader();
        } catch (Throwable ignored) {}

        if (isTinkerClassLoader(contextClassLoader)) return contextClassLoader;
        if (isTinkerClassLoader(threadClassLoader)) return threadClassLoader;
        if (contextClassLoader != null) return contextClassLoader;
        if (threadClassLoader != null) return threadClassLoader;
        return lpClassLoader;
    }

    private DexKitBridge createDexKitBridge(ClassLoader classLoader, String apkPath) {
        synchronized (DEXKIT_CREATE_LOCK) {
            String key = apkPath != null ? apkPath : "";
            DexKitBridge cached = DEXKIT_BRIDGES.get(key);
            if (cached != null) return cached;
            // 仅缓存轻量入口；实际查询才加载 dex，连续空闲后释放 native 分析资源。
            //
            // 空闲窗口取 60 秒，不是越小越好：
            // - 释放后再次查询会重新走 nativeInitDexKit，把整包 dex 重新解析一遍，
            //   峰值内存和耗时都比"保持驻留"更高；
            // - 安装阶段要串行跑几十个定位任务，中间间隔（含 1/3/6 秒退避重试）很容易
            //   超过 10 秒，10 秒窗口会造成"释放—重扫—再释放"的反复，内存曲线反而更抖；
            // - 定位结果已落盘到 DexMethodCache / DexFinder 缓存，二次启动通常不再扫描，
            //   所以 60 秒后释放不会带来后续重扫成本。
            // 需要更激进的释放可以改这个常量，但不要低于安装窗口的量级。
            DexKitBridge bridge = DexKitBridge.createManaged(apkPath, DEXKIT_IDLE_RELEASE_MS);
            DEXKIT_BRIDGES.put(key, bridge);
            return bridge;
        }
    }

    private boolean isTinkerClassLoader(ClassLoader classLoader) {
        if (classLoader == null) return false;
        String text = String.valueOf(classLoader);
        return text.contains("/tinker/") || text.contains("DelegateLastClassLoader");
    }
}
