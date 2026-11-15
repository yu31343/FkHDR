package io.github.fkhdr.hooks

import io.github.fkhdr.xp.Xp
import io.github.fkhdr.xp.XpHook
import io.github.fkhdr.xp.XpHook.MethodHookParam
import io.github.fkhdr.xp.XpHelpers
import io.github.fkhdr.Config

/**
 * 让本模块（无「修改系统设置」授权）也能写 Settings.System 的白名单键。
 *
 * 背景：模块 UI 通过 Settings.System 传递开关与比值上限（system_server 读不到模块私有 prefs），
 * 但 SettingsProvider 会用 checkAndNoteWriteSettingsOperation 校验调用方权限，
 * 普通应用写 system 设置会被静默拒绝 -> hook 端永远读不到界面里的值。
 *
 * 作用域：只在 com.android.providers.settings 进程内生效（META-INF/xposed/scope.list 已声明），
 * 只放行本模块写的、本模块自己的键，不影响其它应用。
 */
object SettingsProviderHook {
    private const val TAG = "FkHDR:SettingsProviderHook"

    /** 只放行本模块自己的开关键与比值上限键 */
    private val ALLOWED_KEYS = hashSetOf(Config.KEY_HDR_RATIO, Config.KEY_RATIO_MAX)

    private val MODULE_PACKAGES = setOf(Config.PACKAGE_NAME)

    /**
     * 支持嵌套调用：记录本模块白名单写操作的深度。
     * 每次 mutate 的 before/after 用 MethodHookParam 的 extra 配对，只有真正入栈的
     * 那次调用才在 after 出栈；非白名单的嵌套 mutate 不会误清外层标记。
     */
    private val sModuleWriteDepth = ThreadLocal<Int>()
    private const val EXTRA_MODULE_WRITE = "fkhdr.moduleWrite"

    /** 原子安装守卫：两个 loadPackage 并发时只允许一次安装；未装上任何钩子时可重试。 */
    private val installStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    private const val RETRY_MS = 500L
    private const val MAX_RETRIES = 40

    fun init(classLoader: ClassLoader) {
        if (!installStarted.compareAndSet(false, true)) return
        if (install(classLoader)) return
        // Android 13+ 的 SettingsProvider 跑在 system_server 里，类要等 provider 真正被实例化
        // 才进 classloader：启动瞬间 findClass 必然失败。后台重试到出现为止，成功即退出；
        // 超时则复位守卫，留给之后可能出现的 package 加载事件。
        Thread({
            for (i in 1..MAX_RETRIES) {
                try {
                    Thread.sleep(RETRY_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (install(classLoader)) return@Thread
            }
            installStarted.set(false)
            Xp.log("$TAG: give up after " + MAX_RETRIES + " retries")
        }, "fkhdr-settings-hook").start()
    }

    /** 真正安装钩子；返回是否至少装上一个。 */
    @Synchronized
    private fun install(classLoader: ClassLoader): Boolean {
        val spClass = try {
            XpHelpers.findClass("com.android.providers.settings.SettingsProvider", classLoader)
        } catch (_: Throwable) {
            return false
        }

        var hookedAny = false

        // 1) 识别「本模块正在写白名单键」的调用，并仅放行同一调用栈的权限校验
        try {
            val mutateHooks = Xp.hookAllMethods(spClass, "mutateSystemSetting", object : XpHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val name = param.args.getOrNull(0) as? String ?: return
                    if (!ALLOWED_KEYS.contains(name)) return
                    val pkg = try {
                        XpHelpers.callMethod(param.thisObject, "getCallingPackage") as? String
                    } catch (_: Throwable) {
                        null
                    }
                    if (pkg !in MODULE_PACKAGES) return
                    sModuleWriteDepth.set((sModuleWriteDepth.get() ?: 0) + 1)
                    param.setObjectExtra(EXTRA_MODULE_WRITE, true)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    // 只给「本次 before 确实入栈」的调用出栈；非白名单/非本模块的
                    // 同线程嵌套 mutate 没有这个标记，不能递减外层深度。
                    if (param.getObjectExtra(EXTRA_MODULE_WRITE) == null) return
                    val depth = sModuleWriteDepth.get() ?: 0
                    if (depth <= 1) sModuleWriteDepth.remove() else sModuleWriteDepth.set(depth - 1)
                }
            })
            if (mutateHooks.isNotEmpty()) hookedAny = true
            else Xp.log("$TAG: mutateSystemSetting not found")

            val checkHooks = Xp.hookAllMethods(
                android.provider.Settings::class.java,
                "checkAndNoteWriteSettingsOperation",
                object : XpHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if ((sModuleWriteDepth.get() ?: 0) <= 0) return
                        // checkAndNoteWriteSettingsOperation 的参数含 callingPackage，
                        // 但不含 setting key：除栈标记外再核一次调用方，避免同线程
                        // 嵌套的其他调用借用本模块的放行标记。
                        // AOSP15 的两个重载里 callingPackage 都是 args[2]。
                        val pkg = param.args.getOrNull(2) as? String ?: return
                        if (MODULE_PACKAGES.contains(pkg)) param.result = true
                    }
                }
            )
            if (checkHooks.isNotEmpty()) hookedAny = true
            else Xp.log("$TAG: checkAndNoteWriteSettingsOperation not found")
        } catch (t: Throwable) {
            Xp.log("$TAG: hook mutateSystemSetting failed: " + t.message)
        }

        // 2) 部分 ROM 会在这里直接抛异常拒绝普通应用改系统设置；
        //    必须同时满足「本模块线程」「白名单键」才短路，避免任意调用方绕过限制。
        try {
            val enforceHooks = Xp.hookAllMethods(
                spClass,
                "enforceRestrictedSystemSettingsMutationForCallingPackage",
                object : XpHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val name = param.args.getOrNull(1) as? String ?: return
                        if ((sModuleWriteDepth.get() ?: 0) > 0 && ALLOWED_KEYS.contains(name)) {
                            param.result = null
                        }
                    }
                }
            )
            if (enforceHooks.isNotEmpty()) hookedAny = true
            else Xp.log("$TAG: enforceRestrictedSystemSettingsMutationForCallingPackage not found")
        } catch (t: Throwable) {
            Xp.log("$TAG: hook enforceRestrictedSystemSettingsMutation failed: " + t.message)
        }

        if (hookedAny) Xp.log("$TAG: initialized (module-only whitelist writes)")
        return hookedAny
    }
}
