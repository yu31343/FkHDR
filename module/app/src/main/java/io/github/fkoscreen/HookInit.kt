package io.github.fkoscreen

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.fkoscreen.hooks.*

class HookInit : IXposedHookLoadPackage {
    companion object {
        private const val TAG = "FkOScreen:HookInit"
        /** 独立 HDR 模块（从本模块拆分出去） */
        private const val STANDALONE_HDR_PKG = "io.github.benbaobaoshigemi.fkhdr"

        /**
         * 独立 HDR 模块是否已安装。
         *
         * HDR 提亮已拆分到 FkHDR。两个模块同时接管同一条提亮链会重复点火
         * （两套 watcher 各自 checkIsPlayingHDR / sendUpdatePowerState / 推亮度），
         * 所以检测到独立模块在场时，本模块主动让位。
         *
         * 检测失败一律按「未安装」处理：宁可重复，也不要静默丢掉 HDR。
         */
        private fun standaloneHdrInstalled(): Boolean = try {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null)
            val ctx = at.getMethod("getSystemContext").invoke(thread) as android.content.Context
            ctx.packageManager.getPackageInfo(STANDALONE_HDR_PKG, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "android" -> {
                XposedBridge.log("$TAG: Hooking system_server...")
                BrightnessLimitHook.init(lpparam.classLoader)
                FossBypassHook.init(lpparam.classLoader)
                // HDR 提亮已拆分到独立模块 FkHDR；独立模块在场时本模块让位，避免双重点火
                if (standaloneHdrInstalled()) {
                    XposedBridge.log(
                        "$TAG: standalone FkHDR installed -> skip built-in HDR hook (no double boost)"
                    )
                } else {
                    HdrBoostHook.init(lpparam.classLoader)
                }
                ColorBallAnchorHook.initSystemServer(lpparam.classLoader)
                SettingsProviderHook.init(lpparam.classLoader)
                RefreshRate144Hook.initSystemServer(lpparam.classLoader)
                EyeProtectReadModeHook.initSystemServer(lpparam.classLoader)
            }
            "com.android.providers.settings" -> {
                XposedBridge.log("$TAG: Hooking SettingsProvider...")
                SettingsProviderHook.init(lpparam.classLoader)
            }
            "com.oplus.eyeprotect" -> {
                XposedBridge.log("$TAG: Hooking com.oplus.eyeprotect (ReadMode & PaperTexture)...")
                EyeProtectReadModeHook.initEyeProtect(lpparam.classLoader)
            }
            "com.android.settings" -> {
                XposedBridge.log("$TAG: Hooking com.android.settings (UI & Controls)...")
                DarkModeStyleHook.init(lpparam.classLoader)
                ColorBallAnchorHook.initSettings(lpparam.classLoader)
                RefreshRate144Hook.initSettings(lpparam.classLoader)
                MotionSicknessHook.initSettings(lpparam.classLoader)
            }
            "com.android.systemui" -> {
                XposedBridge.log("$TAG: Hooking com.android.systemui (HDR & QuickSettings)...")
                // 旧 HdrRatioHook（写死 1.5625 伪造 DisplayInfo.hdrSdrRatio）已删除：
                // 只骗读者、不提亮。现在由 HdrBoostHook 按硬件上限恢复真实比值。
                BrightnessLimitHook.initSystemUI(lpparam.classLoader)
                AodBrightnessHook.init(lpparam.classLoader)
            }
        }
    }
}
