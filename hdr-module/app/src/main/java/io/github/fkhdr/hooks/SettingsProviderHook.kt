package io.github.fkhdr.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.fkhdr.Config

/**
 * 让本模块（无「修改系统设置」授权）也能写 Settings.System 的白名单键。
 *
 * 背景：模块 UI 通过 Settings.System 传递开关（system_server 读不到模块私有 prefs），
 * 但 SettingsProvider 会用 checkAndNoteWriteSettingsOperation 校验调用方权限，
 * 普通应用写 system 设置会被静默拒绝 -> hook 端永远读不到界面里的开关。
 *
 * 作用域：只在 com.android.providers.settings 进程内生效（xposed_scope 已声明），
 * 只放行本模块写的、本模块自己的键，不影响其它应用。
 */
object SettingsProviderHook {
    private const val TAG = "FkHDR:SettingsProviderHook"

    /** 只放行本模块自己的开关键 */
    private val ALLOWED_KEYS = hashSetOf(Config.KEY_HDR_RATIO)

    private val MODULE_PACKAGES = setOf(Config.PACKAGE_NAME)

    /** 当前线程正在处理「本模块写白名单键」的 mutateSystemSetting */
    private val sModuleWrite = ThreadLocal<Boolean>()

    fun init(classLoader: ClassLoader) {
        val spClass = try {
            XposedHelpers.findClass("com.android.providers.settings.SettingsProvider", classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: SettingsProvider not found: " + t.message)
            return
        }

        // 1) 识别「本模块正在写白名单键」的调用，并放行它的写权限校验
        try {
            XposedBridge.hookAllMethods(spClass, "mutateSystemSetting", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val name = param.args.getOrNull(0) as? String ?: return
                    if (!ALLOWED_KEYS.contains(name)) return
                    val pkg = try {
                        XposedHelpers.callMethod(param.thisObject, "getCallingPackage") as? String
                    } catch (_: Throwable) {
                        null
                    }
                    if (pkg in MODULE_PACKAGES) sModuleWrite.set(true)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    sModuleWrite.remove()
                }
            })
            XposedBridge.hookAllMethods(
                android.provider.Settings::class.java,
                "checkAndNoteWriteSettingsOperation",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (sModuleWrite.get() == true) param.result = true
                    }
                }
            )
            XposedBridge.log("$TAG: hooked mutateSystemSetting (module write access)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: hook mutateSystemSetting failed: " + t.message)
        }

        // 2) 部分 ROM 会在这里直接抛异常拒绝普通应用改系统设置
        try {
            XposedBridge.hookAllMethods(
                spClass,
                "enforceRestrictedSystemSettingsMutationForCallingPackage",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val name = param.args.getOrNull(1) as? String ?: return
                        if (ALLOWED_KEYS.contains(name)) param.result = null
                    }
                }
            )
            XposedBridge.log("$TAG: hooked enforceRestrictedSystemSettingsMutationForCallingPackage")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: hook enforceRestrictedSystemSettingsMutation failed: " + t.message)
        }
    }
}
