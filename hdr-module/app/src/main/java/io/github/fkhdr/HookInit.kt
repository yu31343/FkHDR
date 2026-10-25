package io.github.fkhdr

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.fkhdr.hooks.HdrBoostHook
import io.github.fkhdr.hooks.SettingsProviderHook

/**
 * FkHDR 入口（独立 HDR 模块，只做 HDR 一件事）
 *
 * 挂载范围刻意收到最小：
 *   android                       —— system_server，HDR 提亮的唯一战场
 *   com.android.providers.settings —— 让模块界面能写 system settings（开关免 root 生效）
 *
 * 不在 SystemUI / Settings 里挂任何东西：HDR 不需要它们，
 * 少挂一个进程就少一份与其他模块/ROM 冲突的可能。
 */
class HookInit : IXposedHookLoadPackage {
    companion object {
        private const val TAG = "FkHDR:HookInit"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "android" -> {
                XposedBridge.log("$TAG: hooking system_server")
                HdrBoostHook.init(lpparam.classLoader)
                SettingsProviderHook.init(lpparam.classLoader)
            }
            "com.android.providers.settings" -> {
                XposedBridge.log("$TAG: hooking SettingsProvider")
                SettingsProviderHook.init(lpparam.classLoader)
            }
        }
    }
}
