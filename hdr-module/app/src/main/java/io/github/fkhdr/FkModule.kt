package io.github.fkhdr

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.fkhdr.hooks.HdrBoostHook
import io.github.fkhdr.hooks.SettingsProviderHook
import io.github.fkhdr.xp.Xp

/**
 * FkHDR 入口（libxposed API 102 模块）。
 *
 * 与旧版 HookInit 的差异：
 *  - 入口类继承 XposedModule，由框架在 attachFramework() 之后回调，不再自己拿 classLoader；
 *  - 注册在 META-INF/xposed/java_init.list，作用域在 META-INF/xposed/scope.list；
 *  - system_server 走 onSystemServerStarting()，SettingsProvider 走 onPackageLoaded()。
 *
 * 挂载范围刻意收到最小：
 *   android                        —— system_server，HDR 提亮的唯一战场
 *   com.android.providers.settings —— 让模块界面能写 system settings（开关免 root 生效）
 */
class FkModule : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Xp.attach(this)
        Xp.log(
            "FkHDR:FkModule: loaded api=" + getApiVersion() +
                " framework=" + getFrameworkName() + "/" + getFrameworkVersion() +
                " systemServer=" + param.isSystemServer +
                " process=" + param.processName
        )
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        Xp.log("FkHDR:FkModule: hooking system_server")
        HdrBoostHook.init(param.classLoader)
        // SettingsProvider 不在这里挂：Android 13+ 它跑在 system_server 里，但类在它自己的
        // APK classloader 中，system_server 的 loader 找不到。等 onPackageLoaded 拿到正确的
        // defaultClassLoader 再挂（旧版 legacy 模块挂的是独立进程，所以没这个问题）。
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName == "com.android.providers.settings") {
            Xp.log("FkHDR:FkModule: hooking SettingsProvider")
            SettingsProviderHook.init(param.defaultClassLoader)
        }
    }
}
