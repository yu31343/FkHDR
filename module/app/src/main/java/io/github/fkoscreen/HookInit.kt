package io.github.fkoscreen

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.fkoscreen.hooks.*

class HookInit : IXposedHookLoadPackage {
    companion object {
        private const val TAG = "FkOScreen:HookInit"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "android" -> {
                XposedBridge.log("$TAG: Hooking system_server...")
                BrightnessLimitHook.init(lpparam.classLoader)
                FossBypassHook.init(lpparam.classLoader)
                HdrBoostHook.init(lpparam.classLoader)
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
