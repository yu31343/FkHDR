package io.github.fkhdr

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * FkHDR 配置读写（独立模块，与 FkOScreen 完全解耦，但沿用同一 Settings.System 键以便继承旧设置）。
 *
 * 三条通道：
 *  1) Settings.System —— 唯一能被 system_server / SystemUI / 设置可靠读到的通道。
 *     LSPosed 注入的进程读不到本模块私有 prefs（SELinux 拦截），所以开关一律以它为准。
 *  2) 本地 SharedPreferences —— 仅模块自身 UI 进程可读，作为 UI 回显与 Settings.System
 *     写入失败（无 WRITE_SETTINGS 授权）时的兜底。
 *  3) 模块自身 prefs —— 仅模块自身进程使用；被 hook 的进程里不碰，避免 avc denied 刷屏。
 */
object Config {
    const val PACKAGE_NAME = "io.github.benbaobaoshigemi.fkhdr"
    const val PREFS_NAME = "fkhdr_prefs"

    /** 与旧模块共用同一 Settings.System 键，保证用户旧设置可继承 */
    const val KEY_HDR_RATIO = "hdr_ratio_fix"

    /** HDR/SDR 比值封顶上限（浮点，写 Settings.System）。默认 5.0 = 旧模块 XML 里的值 */
    const val KEY_RATIO_MAX = "hdr_ratio_max"
    const val DEFAULT_RATIO_MAX = 5.0f
    const val MIN_RATIO_MAX = 1.0f
    const val MAX_RATIO_MAX = 20.0f

    /** 读封顶上限；读不到用默认 5.0 */
    fun getRatioMax(): Float {
        val fromSettings = try {
            val cr = appContext()?.contentResolver
            if (cr == null) null
            else android.provider.Settings.System.getFloat(cr, KEY_RATIO_MAX, DEFAULT_RATIO_MAX)
        } catch (_: Throwable) {
            null
        }
        // 与 getBooleanConfig 一致：system_server 里绝不碰模块私有 prefs（会 avc denied 刷屏，
        // 而且这里处在亮度 ramp 的关键路径上，文件 IO 会直接掉帧）。
        val v = fromSettings ?: if (inModuleProcess()) {
            try {
                getXPrefs().getFloat(KEY_RATIO_MAX, DEFAULT_RATIO_MAX)
            } catch (_: Throwable) {
                DEFAULT_RATIO_MAX
            }
        } else {
            DEFAULT_RATIO_MAX
        }
        return v.coerceIn(MIN_RATIO_MAX, MAX_RATIO_MAX)
    }

    /** 写封顶上限 */
    fun setRatioMax(ctx: Context, value: Float): Boolean {
        val v = value.coerceIn(MIN_RATIO_MAX, MAX_RATIO_MAX)
        try {
            getAppPrefs(ctx).edit().putFloat(KEY_RATIO_MAX, v).apply()
        } catch (_: Throwable) {
        }
        val ok = try {
            android.provider.Settings.System.putFloat(ctx.contentResolver, KEY_RATIO_MAX, v)
        } catch (_: Throwable) {
            false
        }
        if (!ok) {
            Thread {
                try {
                    Runtime.getRuntime()
                        .exec(arrayOf("su", "-c", "settings put system " + KEY_RATIO_MAX + " " + v))
                        .waitFor()
                } catch (_: Throwable) {
                }
            }.start()
        }
        return ok
    }

    private var xPrefs: ModulePrefs? = null

    /**
     * 模块自身进程内的 prefs 门面。
     *
     * api 102 的模块不能再引用 legacy 的 XSharedPreferences（de.robv.* 在 102 下不可用）。
     * 这里只有模块自己的进程会读它——被 hook 的进程读模块私有 prefs 会被 SELinux 拒绝，
     * 所以直接用本进程的 SharedPreferences，不再需要 makeWorldReadable()。
     */
    fun getXPrefs(): ModulePrefs {
        val cached = xPrefs
        if (cached != null) return cached
        val sp = try {
            appContext()?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        }
        val created = ModulePrefs(sp)
        xPrefs = created
        return created
    }

    /**
     * 进程内可用的 Context。
     *
     * 模块自身进程用 currentApplication()；被 hook 的 system_server 里 currentApplication()
     * 常常是 null（没有 Application 实例），会导致 Settings.System 读不到 ->
     * 一路回退到模块私有 prefs -> SELinux 拦截（avc denied）-> 开关失效 + 日志刷屏。
     * 这里用 currentActivityThread() + getSystemContext() 兜底，拿到的就是 system_server 的 Context。
     */
    private fun appContext(): Context? = try {
        val at = Class.forName("android.app.ActivityThread")
        val app = at.getMethod("currentApplication").invoke(null) as? Context
        if (app != null) {
            app
        } else {
            val thread = at.getMethod("currentActivityThread").invoke(null)
            at.getMethod("getSystemContext").invoke(thread) as? Context
        }
    } catch (_: Throwable) {
        null
    }

    /** 当前进程是否就是模块自己的 App 进程（只有它读得到模块私有 prefs） */
    private fun inModuleProcess(): Boolean = try {
        appContext()?.packageName == PACKAGE_NAME
    } catch (_: Throwable) {
        false
    }

    /** 读 Settings.System 的 int 开关；读不到（无 Context / 无该键 / 抛异常）返回 -1 */
    fun getSettingInt(key: String): Int {
        return try {
            val cr = appContext()?.contentResolver ?: return -1
            android.provider.Settings.System.getInt(cr, key, -1)
        } catch (_: Throwable) {
            -1
        }
    }

    /** 读 Settings.System 的浮点值；读不到返回 def，用于 UI 写入后回读校验。 */
    fun getSettingFloat(key: String, def: Float): Float = try {
        val cr = appContext()?.contentResolver ?: return def
        android.provider.Settings.System.getFloat(cr, key, def)
    } catch (_: Throwable) {
        def
    }

    /** HDR 提亮修复开关，默认开启 */
    fun isHdrFixEnabled(): Boolean = getBooleanConfig(KEY_HDR_RATIO, true)

    // Settings.System 优先；读不到时：
    //   - 被 hook 的进程（system_server 等）：直接返回默认值，不碰 prefs 文件（SELinux 会拒绝并刷 avc denied）
    //   - 模块自身进程：回退到自己的 prefs，保证 UI 首次启动能显示上次的选择
    private fun getBooleanConfig(key: String, def: Boolean): Boolean {
        val s = getSettingInt(key)
        if (s != -1) return s == 1
        if (!inModuleProcess()) return def
        return try {
            getXPrefs().getBoolean(key, def)
        } catch (_: Throwable) {
            def
        }
    }

    /**
     * 模块界面写入开关：本地 prefs + Settings.System。
     * 模块没有「修改系统设置」授权时 putInt 会返回 false / 抛 SecurityException，
     * 此时改用 root（su）写入；SettingsProviderHook 也会为白名单键放行写入检查。
     */
    fun writeConfig(ctx: Context, key: String, value: Boolean): Boolean {
        try {
            getAppPrefs(ctx).edit().putBoolean(key, value).apply()
        } catch (_: Throwable) {
        }
        fixPermissions(ctx)
        val intValue = if (value) 1 else 0
        var err = ""
        val written = try {
            android.provider.Settings.System.putInt(ctx.contentResolver, key, intValue)
        } catch (t: Throwable) {
            err = t.javaClass.simpleName + ": " + t.message
            false
        }
        // 诊断：开关写不进去时能直接从 logcat 看出原因（权限 / SELinux / provider 拒绝）
        android.util.Log.i("FkHDR", "writeConfig " + key + "=" + intValue + " written=" + written + (if (err.isEmpty()) "" else " err=" + err))
        if (!written) {
            Thread {
                try {
                    val p = Runtime.getRuntime()
                        .exec(arrayOf("su", "-c", "settings put system $key $intValue"))
                    val code = p.waitFor()
                    android.util.Log.i("FkHDR", "writeConfig su fallback exit=" + code)
                } catch (t: Throwable) {
                    android.util.Log.i("FkHDR", "writeConfig su fallback failed: " + t)
                }
            }.start()
        }
        return written
    }

    fun getAppPrefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun fixPermissions(ctx: Context) {
        try {
            val prefsFile = File(ctx.filesDir.parentFile, "shared_prefs/$PREFS_NAME.xml")
            if (!prefsFile.exists()) return
            prefsFile.setReadable(true, false)
            prefsFile.parentFile?.setReadable(true, false)
            prefsFile.parentFile?.setExecutable(true, false)
            ctx.filesDir.parentFile?.setExecutable(true, false)
            ctx.filesDir.parentFile?.setReadable(true, false)
            Thread {
                try {
                    Runtime.getRuntime()
                        .exec(arrayOf("su", "-c", "chmod 666 ${prefsFile.absolutePath}"))
                        .waitFor()
                } catch (_: Throwable) {
                }
            }.start()
        } catch (_: Throwable) {
        }
    }
}

/** 只读 prefs 门面：拿不到 Context 时全部返回默认值，不抛异常。 */
class ModulePrefs(private val sp: SharedPreferences?) {
    fun getBoolean(key: String, def: Boolean): Boolean = try {
        sp?.getBoolean(key, def) ?: def
    } catch (_: Throwable) {
        def
    }

    fun getFloat(key: String, def: Float): Float = try {
        sp?.getFloat(key, def) ?: def
    } catch (_: Throwable) {
        def
    }

    fun getInt(key: String, def: Int): Int = try {
        sp?.getInt(key, def) ?: def
    } catch (_: Throwable) {
        def
    }

    fun getString(key: String, def: String?): String? = try {
        sp?.getString(key, def)
    } catch (_: Throwable) {
        def
    }
}
