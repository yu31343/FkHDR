package io.github.fkoscreen.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * ColorOS HDR 提亮修复（通用版：无白名单，全应用生效，带自诊断与通用兜底）
 *
 * ── 根因（ColorOS 16 实测 + 反编译确认）──────────────────────
 * 1) 产品配置缺 3 个 OPPO feature -> isSupportSdrToHdrRatio() 恒 false，
 *    HDR 提亮 feature 不进亮度后处理链，hdrSdrRatio 停在 NaN。
 * 2) 面板没有 HDR color mode -> SurfaceFlinger 不进 HDR 合成 ->
 *    ColorOS 的 checkIsPlayingHDR() 恒 0（HDR 开关信号缺失）。
 * 3) ColorOS 提亮只改面板 nit，Builder.setNit() 在 mHasEdrProcessed==false 时
 *    顺手把 sdrNit 也改成同值 -> 面板亮了但 hdrSdrRatio 恒 1。
 *
 * ── 三层实现（任何一层单独可用，按可用性叠加）──────────────
 * A. 原生驱动：接管 OplusFeatureHDREnhanceBrightness 的 mEnterWhiteApp / mEnhanceFactor，
 *    由它自己算 nit、走自己的动画与硬件上限 clamp。并强制打开它的
 *    mEnhanceHDRBrightnessSupport（新版系统该 feature 名可能查不到，会导致整条链静默失效）。
 * B. 通用兜底：OplusBrightnessPostProcessingManager#convertNitToBrightness 之前，
 *    若 hdrOn 且系统没有把 nit 抬上去（nit/sdrNit <= 1.02），直接按面板上限改 nit，
 *    并先 setHasEdrProcessed() 保住 SDR 白点，让系统自己算出真实比率。
 *    ——这一层不依赖任何 OPPO feature 类，改类名/改开关也照样生效。
 * C. 比率开关：LocalDisplayDeviceExtImpl#isSupportSdrToHdrRatio() 恒 true，
 *    让系统愿意上报 hdrSdrRatio。
 *
 * ── 关于「HDR 亮度比例」────────────────────────────────────
 * 不使用任何写死的比率。ratio = 面板 nit / SDR 白点 nit，由面板硬件上限决定：
 * 亮度越低比值越大，亮度拉满时自然回落到 1.0；HDR 关闭后回落 1.0。
 *
 * ── 诊断 ────────────────────────────────────────────────────
 * 所有关键节点写 XposedBridge.log（LSPosed 管理器 → 日志 可见），前缀 FkOScreen:HdrFix。
 * 不生效时日志能直接区分：「类没找到 / hook 没挂上 / feature 没开 /
 * 系统没调 postProcessing / 提亮被上限截断」。
 *
 * 关闭：setprop persist.sys.fkoscreen.hdr_fix 0（重启生效）
 * 倍数：setprop persist.sys.fkoscreen.hdr_factor 2.0（默认 100，由面板上限截断）
 */
object HdrBoostHook {
    private const val TAG = "FkOScreen:HdrFix"
    private const val PROP_ENABLE = "persist.sys.fkoscreen.hdr_fix"
    private const val PROP_FACTOR = "persist.sys.fkoscreen.hdr_factor"
    /** 1=启用原生驱动（默认），0=只用通用兜底（用于定位/兜底） */
    private const val PROP_NATIVE = "persist.sys.fkoscreen.hdr_native"
    /** 1=允许通用兜底（默认），0=只走 ColorOS 原生驱动（用于验证原生链路是否独立可用） */
    private const val PROP_FALLBACK = "persist.sys.fkoscreen.hdr_fallback"

    private const val COLOR_MODE_HDR = 2
    private const val CODE_GET_HDR_STATE = 21012
    private const val SF_TOKEN = "android.ui.ISurfaceComposer"
    private const val POLL_MS = 400L

    /** 认为「系统自己已经提亮过」的阈值：nit / sdrNit */
    private const val RATIO_DONE = 1.02f

    private const val CLS_HDR_ENHANCE =
        "com.android.server.display.feature.postprocess.OplusFeatureHDREnhanceBrightness"
    private const val CLS_POST_MANAGER =
        "com.android.server.display.feature.postprocess.OplusBrightnessPostProcessingManager"
    private const val CLS_LOCAL_DEVICE_EXT =
        "com.android.server.display.LocalDisplayDeviceExtImpl"
    private const val CLS_FEATURE_MGR = "com.oplus.content.OplusFeatureConfigManager"
    private const val CLS_FEATURE_CACHE = "com.oplus.content.OplusFeatureCache"
    private const val CLS_FEATURE_MANAGER =
        "com.android.server.display.feature.postprocess.OplusDisplayFeatureManager"

    /** 本机产品配置缺失、但 ColorOS 代码依赖的 feature */
    private val MISSING_FEATURES = setOf(
        "oplus.software.display.enhance_brightness_with_uidimming",
        "oplus.software.display.hdr_enhance_brightness_support",
        "oplus.software.display.dolby_enhance_v1_support",
    )

    @Volatile private var hdrEnhance: Any? = null
    @Volatile private var wms: Any? = null
    @Volatile private var hdrOn = false
    @Volatile private var nativeDriver = false

    /** 用来主动触发一次亮度重算（兜底路径没有别的触发器） */
    @Volatile private var dpc: Any? = null
    @Volatile private var dpcHandler: android.os.Handler? = null

    /** 面板 nit 硬件上限，0 = 还没读到 */
    @Volatile private var panelMaxNit = 0f

    /** 可见且 LayoutParams.colorMode == COLOR_MODE_HDR 的窗口（relayout 事件维护） */
    private val hdrWindows = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())
    )

    // ── 限流日志 ────────────────────────────────────────────
    private val logAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun logEvery(key: String, ms: Long, msg: () -> String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = logAt[key] ?: 0L
        if (now - last < ms) return
        logAt[key] = now
        try {
            val s = msg()
            if (s.isNotEmpty()) XposedBridge.log(s)
        } catch (_: Throwable) {
        }
    }

    fun init(classLoader: ClassLoader) {
        val steps = StringBuilder()
        steps.append("features=").append(hookFeatures(classLoader))
        steps.append(" featCfg=").append(hookFeatureSingleton(classLoader))
        steps.append(" fmgr=").append(hookFeatureManager(classLoader))
        steps.append(" ratioFlag=").append(hookRatioSupport(classLoader))
        nativeDriver = if (prop(PROP_NATIVE, "1") != "0") hookOplusHdrEnhance(classLoader) else {
            XposedBridge.log("$TAG: native driver disabled by " + PROP_NATIVE + ", fallback only")
            false
        }
        steps.append(" native=").append(nativeDriver)
        steps.append(" boost=").append(hookPostProcessBoost(classLoader))
        steps.append(" dpc=").append(hookDisplayPowerController(classLoader))
        steps.append(" wms=").append(hookWindowManager(classLoader))
        startWatcher()
        XposedBridge.log("$TAG: init done [" + steps + "] factor=" + factor())
    }

    // ── 属性 ────────────────────────────────────────────────
    private fun prop(key: String, def: String): String = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
            .invoke(null, key, def) as String
    } catch (_: Throwable) {
        def
    }

    // enabled() 在 watcher 里每 400ms 调一次，加 5s 缓存避免反复 read syscall
    @Volatile private var enabledCache = true
    @Volatile private var enabledAt = 0L

    /** 开关优先级：系统属性 > 模块界面开关（复用原 hdr_ratio_fix 键） */
    private fun enabled(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - enabledAt < 5000L) return enabledCache
        val v = try {
            val p = prop(PROP_ENABLE, "")
            if (p.isNotEmpty()) p != "0" && !p.equals("false", true)
            else io.github.fkoscreen.ConfigManager.isHdrFixEnabled()
        } catch (_: Throwable) {
            true
        }
        enabledCache = v
        enabledAt = now
        return v
    }

    /** 提亮倍数；默认 100 -> 由面板 nit 上限截断，等于「只受硬件限制」 */
    private fun factor() = prop(PROP_FACTOR, "100").toFloatOrNull()?.takeIf { it > 1f } ?: 100f

    private fun f2(v: Float): String = (Math.round(v * 100f) / 100f).toString()

    // ── 工具：安全 hook + 安全读写字段 ───────────────────────
    /** 返回该名字实际挂上多少个方法（0 = 这个版本里没有该方法） */
    private fun hookAll(cls: Class<*>, name: String, hook: XC_MethodHook): Int = try {
        XposedBridge.hookAllMethods(cls, name, hook).size
    } catch (t: Throwable) {
        logEvery("hook_" + name, 60000L) { "$TAG: hook " + name + " unavailable: " + t }
        0
    }

    private fun boolField(o: Any, n: String): String = try {
        XposedHelpers.getBooleanField(o, n).toString()
    } catch (_: Throwable) {
        "?"
    }

    private fun intField(o: Any, n: String): String = try {
        XposedHelpers.getIntField(o, n).toString()
    } catch (_: Throwable) {
        "?"
    }

    private fun floatField(o: Any, n: String): String = try {
        f2(XposedHelpers.getFloatField(o, n))
    } catch (_: Throwable) {
        "?"
    }

    private fun hasMethod(cls: Class<*>, name: String): Boolean =
        cls.declaredMethods.any { it.name == name } ||
            (cls.superclass?.declaredMethods?.any { it.name == name } ?: false)

    // ── A1. 补 feature 配置 ─────────────────────────────────
    private fun hookFeatures(classLoader: ClassLoader): Int = try {
        val svc = XposedHelpers.findClass(
            "com.android.server.content.OplusFeatureConfigManagerService", classLoader
        )
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!enabled()) return
                val name = param.args.getOrNull(0) as? String ?: return
                if (name in MISSING_FEATURES) param.result = true
            }
        }
        hookAll(svc, "hasFeature", hook) + hookAll(svc, "hasFeatureMap", hook)
    } catch (t: Throwable) {
        XposedBridge.log("$TAG: feature service not found: " + t.message)
        0
    }

    // ── A2. 比率开关：让系统愿意算/上报 hdrSdrRatio ──────────
    private fun hookRatioSupport(classLoader: ClassLoader): Int = try {
        val ext = XposedHelpers.findClass(CLS_LOCAL_DEVICE_EXT, classLoader)
        hookAll(ext, "isSupportSdrToHdrRatio", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (enabled()) param.result = true
            }
        })
    } catch (t: Throwable) {
        XposedBridge.log("$TAG: LocalDisplayDeviceExtImpl not found: " + t.message)
        0
    }

    // ── A3. C17：feature 查询改走本地缓存，必须在缓存层兜住 ────
    //
    // ColorOS 17 的 com.oplus.content.OplusFeatureConfigManager#hasFeature(String) 实现是
    //     return this.mCache.query(name);     // 纯本地缓存，根本不经过 ConfigManagerService
    // 于是老办法「hook OplusFeatureConfigManagerService.hasFeature」在 C17 上完全失效：
    // OplusDisplayFeatureManager.mEnhanceHDRBrightnessSupport 恒 false ->
    // ColorOS 自己那个 HDR 提亮驱动（OplusFeatureHDREnchanceBrightness）压根不会被 new 出来
    // -> 日志里 driver=null -> 原生提亮链整条消失。
    //
    // 平台差异（C16 只有 Service 一条路，C17 两条路并存）：
    //   OplusFeatureCache.getOrCreate(IOplusFeatureConfigManagerInternal.DEFAULT).hasFeature()  <- 仍能被 Service hook 影响
    //   OplusFeatureConfigManager.getInstacne().hasFeature()                                  <- 只读本地缓存
    // 两条都兜住才算通杀。
    private fun hookFeatureSingleton(classLoader: ClassLoader): Int {
        var n = 0
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!enabled()) return
                val name = param.args.getOrNull(0) as? String ?: return
                if (name in MISSING_FEATURES) param.result = true
            }
        }
        try {
            val cls = XposedHelpers.findClass(CLS_FEATURE_MGR, classLoader)
            n += hookAll(cls, "hasFeature", hook)
        } catch (t: Throwable) {
            logEvery("featmgr", 60000L) { "$TAG: feature singleton unavailable: " + t.message }
        }
        try {
            val cls = XposedHelpers.findClass(CLS_FEATURE_CACHE, classLoader)
            n += hookAll(cls, "query", hook)
        } catch (t: Throwable) {
            logEvery("featcache", 60000L) { "$TAG: feature cache unavailable: " + t.message }
        }
        return n
    }

    // ── A4. 双保险：直接掰开 feature manager 的开关 ───────────
    //
    // 就算将来 OPPO 再换 feature 查询实现，只要这个字段还是它，就在这里强制置位，
    // 保证 constructOptionalEnhanceFeatures() 里那句
    //     if (this.mEnhanceHDRBrightnessSupport) { this.mHDREnhanceBrightness = new ... }
    // 一定会执行。
    private fun hookFeatureManager(classLoader: ClassLoader): Int = try {
        val cls = XposedHelpers.findClass(CLS_FEATURE_MANAGER, classLoader)
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!enabled()) return
                val self = param.thisObject
                try {
                    if (!XposedHelpers.getBooleanField(self, "mEnhanceHDRBrightnessSupport")) {
                        XposedHelpers.setBooleanField(self, "mEnhanceHDRBrightnessSupport", true)
                        XposedBridge.log("$TAG: forced mEnhanceHDRBrightnessSupport=true")
                    }
                } catch (t: Throwable) {
                    logEvery("fmgrfail", 5000L) {
                        "$TAG: force feature-manager flag failed: " + t.message
                    }
                }
            }
        }
        // C16 用 addPostprocessFeatures()；C17 拆成 constructOptionalEnhanceFeatures()。
        // 两个名字都挂，谁存在就谁生效。
        var n = hookAll(cls, "addPostprocessFeatures", hook)
        n += hookAll(cls, "constructOptionalEnhanceFeatures", hook)
        n
    } catch (t: Throwable) {
        XposedBridge.log("$TAG: OplusDisplayFeatureManager not found: " + t.message)
        0
    }

    // ── B. 首选：驱动 ColorOS 自己的 HDR 提亮实现 ────────────
    private fun hookOplusHdrEnhance(classLoader: ClassLoader): Boolean {
        return try {
            val cls = XposedHelpers.findClass(CLS_HDR_ENHANCE, classLoader)
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val self = param.thisObject
                    hdrEnhance = self
                    XposedBridge.log("$TAG: driver ready [" + hardenDriver(self) + "]")
                }
            })

            // 保住 SDR 白点：ColorOS 的 setNit() 在 mHasEdrProcessed==false 时会把
            // sdrNit 一起改掉，先置 hasEdrProcessed 系统才能算出真实比率。
            val nPost = hookAll(cls, "postProcessing", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled()) return
                    val builder = param.args.getOrNull(0) ?: return
                    if (!hdrOn) return
                    try {
                        XposedHelpers.callMethod(builder, "setHasEdrProcessed")
                    } catch (_: Throwable) {
                    }
                    // C17 新增了按包名取 mEnhanceFactor 的白名单逻辑，
                    // 每次后处理前都重新压一遍，保证不受任何应用切换影响。
                    val self = param.thisObject
                    try {
                        XposedHelpers.setBooleanField(self, "mEnterWhiteApp", true)
                        XposedHelpers.setFloatField(self, "mEnhanceFactor", factor())
                        XposedHelpers.setBooleanField(self, "mNeedToEnhanceBrightness", true)
                    } catch (_: Throwable) {
                    }
                    logEvery("pp", 2000L) {
                        "$TAG: pp nit=" + num(builder, "getNit") +
                            "/" + num(builder, "getSdrNit") +
                            " [" + driverState(param.thisObject) + "]"
                    }
                }
            })

            // ── 白名单彻底解除（C17 新增了「按包名取倍率」）──
            // C17 的 onAppEnter(pkg) 会 getEnhanceFactorForPackage(pkg)：
            //   不在 display_brightness_app_list.xml 里 -> 返回 null -> 直接 return，不设 mEnterWhiteApp
            // 这里让它对所有包都返回我们的倍率，白名单形同不存在。
            val nFactor = hookAll(cls, "getEnhanceFactorForPackage", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (enabled()) param.result = factor()
                }
            })

            // onAppExit 会无条件 mEnterWhiteApp=false + exitEnhanceBrightness()，
            // HDR 期间任何一次应用切换都会把提亮掐掉 —— 直接短路掉。
            val nExit = hookAll(cls, "onAppExit", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (enabled() && hdrOn) {
                        param.result = null
                        logEvery("onexit", 2000L) {
                            "$TAG: suppressed onAppExit while HDR on (bypass whitelist)"
                        }
                    }
                }
            })

            // 接管 HDR 状态：不走白名单，直接设 mEnterWhiteApp，再走它自己的 enter/exit。
            val nCheck = hookAll(cls, "checkIsPlayingHDR", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!enabled()) return
                    val self = param.thisObject
                    try {
                        XposedHelpers.setBooleanField(self, "mEnterWhiteApp", hdrOn)
                        XposedHelpers.setFloatField(self, "mEnhanceFactor", factor())
                        if (hdrOn) callPriv(self, "enterEnhanceBrightness")
                        else callPriv(self, "exitEnhanceBrightness")
                    } catch (t: Throwable) {
                        logEvery("checkfail", 3000L) {
                            "$TAG: force enter/exit failed: " + t
                        }
                    }
                }
            })

            XposedBridge.log(
                "$TAG: native driver hooked (post=" + nPost + " check=" + nCheck +
                    " enter=" + hasMethod(cls, "enterEnhanceBrightness") +
                    " exit=" + hasMethod(cls, "exitEnhanceBrightness") +
                    " factorHook=" + nFactor + " exitHook=" + nExit + ")"
            )
            nPost > 0 || nCheck > 0
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: native driver unavailable: " + t.message)
            false
        }
    }

    /**
     * 兜住 ColorOS 自己的开关：新版系统上 OplusFeatureConfigManager 的查询链可能变了，
     * mEnhanceHDRBrightnessSupport 会恒 false —— postProcessing 就整个静默失效。
     * 这里直接掰成 true 并补跑 init()。
     */
    private fun hardenDriver(self: Any): String {
        val sb = StringBuilder()
        val support = try {
            XposedHelpers.getBooleanField(self, "mEnhanceHDRBrightnessSupport")
        } catch (_: Throwable) {
            true
        }
        sb.append("support=").append(support)
        if (!support) {
            try {
                XposedHelpers.setBooleanField(self, "mEnhanceHDRBrightnessSupport", true)
                sb.append("->true")
                try {
                    XposedHelpers.callMethod(self, "init")
                    sb.append("(init ok)")
                } catch (t: Throwable) {
                    sb.append("(init fail:").append(t.javaClass.simpleName).append(")")
                }
            } catch (t: Throwable) {
                sb.append("(force fail:").append(t.javaClass.simpleName).append(")")
            }
        }
        sb.append(" maxNit=").append(floatField(self, "mMaxNit"))
        sb.append(" factor=").append(floatField(self, "mEnhanceFactor"))
        return sb.toString()
    }

    private fun callPriv(self: Any, name: String) {
        try {
            XposedHelpers.callMethod(self, name)
        } catch (t: Throwable) {
            logEvery("call_" + name, 3000L) { "$TAG: " + name + " failed: " + t }
        }
    }

    private fun driverState(self: Any?): String {
        if (self == null) return "driver=null"
        return "need=" + boolField(self, "mNeedToEnhanceBrightness") +
            " enter=" + boolField(self, "mEnterWhiteApp") +
            " enhanced=" + boolField(self, "mIsEnhancedStatus") +
            " playingHDR=" + intField(self, "mIsPlayingHDR") +
            " battery=" + intField(self, "mBatteryLevel") +
            " powerSave=" + boolField(self, "mPowerSavingMode") +
            " maxNit=" + floatField(self, "mMaxNit")
    }

    // ── C. 通用兜底：后处理链里按面板上限抬 nit ──────────────
    //
    // 注入点（ColorOS 16 反编译确认）：
    //   OplusBrightnessPostProcessingManager#postProcessing(builder, rate)
    //     ├─ mDisplayFeatureManager.postProcessing(builder)   <- 所有 OPPO feature（含 HDR 提亮）
    //     ├─ builder.setNit(mBrightnessModel.clampNit(...))   <- 已按面板上限截断
    //     ├─ convertNitToBrightness(builder)                  <- ★ 在这里把 nit 换成 brightness
    //     └─ ...
    // 所以钩 convertNitToBrightness 之前改 nit：既在全部 feature 之后（不会被覆盖），
    // 又在亮度换算之前（改完立刻生效），且与具体 feature 类名无关。
    private fun hookPostProcessBoost(classLoader: ClassLoader): Int = try {
        val mgr = XposedHelpers.findClass(CLS_POST_MANAGER, classLoader)
        var n = hookAll(mgr, "convertNitToBrightness", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!enabled() || !hdrOn) return
                val builder = param.args.getOrNull(0) ?: return
                boost(param.thisObject, builder)
            }
        })
        // 诊断：证明「后处理链到底有没有在跑」。C17 静默失效最常见的原因就是它压根没被调用。
        n += hookAll(mgr, "postProcessing", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!enabled() || !hdrOn) return
                logEvery("mpp", 2000L) {
                    val st = param.result
                    "$TAG: mgr pp ran nit=" + num(st, "getNit") +
                        " sdrNit=" + num(st, "getSdrNit") +
                        " brightness=" + num(st, "getBrightness")
                }
            }
        })
        n
    } catch (t: Throwable) {
        XposedBridge.log("$TAG: post-processing manager not found: " + t.message)
        0
    }

    // ── E. 主动触发亮度重算 ─────────────────────────────────
    //
    // 兜底路径是「被动」的：只有当系统重算亮度时才会经过 convertNitToBrightness。
    // 光切窗口 colorMode 不会必然触发一次重算，所以 HDR 开关后必须自己点一次火。
    private fun hookDisplayPowerController(classLoader: ClassLoader): Boolean = try {
        val cls = XposedHelpers.findClass("com.android.server.display.DisplayPowerController", classLoader)
        XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val self = param.thisObject
                dpc = self
                try {
                    dpcHandler = XposedHelpers.getObjectField(self, "mHandler") as? android.os.Handler
                } catch (_: Throwable) {
                }
            }
        })
        true
    } catch (t: Throwable) {
        XposedBridge.log("$TAG: DisplayPowerController not found: " + t.message)
        false
    }

    /** 让 DisplayPowerController 在自己的 handler 线程上重算一次亮度 */
    private fun requestPowerUpdate() {
        val d = dpc
        if (d == null) {
            logEvery("nodpc", 5000L) { "$TAG: no DPC instance, cannot request power update" }
            return
        }
        val run = Runnable {
            try {
                XposedHelpers.callMethod(d, "updatePowerState")
            } catch (t: Throwable) {
                logEvery("updfail", 5000L) { "$TAG: updatePowerState failed: " + t }
            }
        }
        try {
            val h = dpcHandler
            if (h != null) h.post(run) else run.run()
        } catch (t: Throwable) {
            logEvery("updfail2", 5000L) { "$TAG: post update failed: " + t }
        }
    }

    private fun num(o: Any, getter: String): Float = try {
        (XposedHelpers.callMethod(o, getter) as? Float) ?: Float.NaN
    } catch (_: Throwable) {
        Float.NaN
    }

    /** 面板 nit 硬件上限（带缓存） */
    private fun maxPanelNit(mgr: Any): Float {
        val cached = panelMaxNit
        if (cached > 0f) return cached
        var v = Float.NaN
        try {
            val model = XposedHelpers.getObjectField(mgr, "mBrightnessModel")
            v = num(model, "getMaxPanelNit")
            if (v.isNaN() || v <= 0f) v = num(model, "getTotalBrightness")
        } catch (_: Throwable) {
        }
        if (!v.isNaN() && v > 0f) panelMaxNit = v
        return v
    }

    /** 通用兜底是否允许。设 0 则只走 ColorOS 原生驱动（用于验证原生链路）。 */
    private fun fallbackEnabled(): Boolean = prop(PROP_FALLBACK, "1") != "0"

    private fun boost(mgr: Any, builder: Any): Boolean {
        return try {
            if (!fallbackEnabled()) {
                logEvery("fb_off", 5000L) { "$TAG: fallback disabled by " + PROP_FALLBACK }
                return false
            }
            val base = num(builder, "getNit")
            if (base.isNaN() || base <= 0f) return false
            val sdr = num(builder, "getSdrNit")
            if (!sdr.isNaN() && sdr > 0f && base / sdr > RATIO_DONE) {
                // 原生驱动已经提亮了，兜底保持静默（证明日志：fallback idle）
                logEvery("fb_idle", 10000L) {
                    "$TAG: fallback idle (native engaged, nit=" + f2(base) +
                        " sdrNit=" + f2(sdr) + " ratio=" + f2(base / sdr) + ")"
                }
                return false
            }
            logEvery("fb_active", 5000L) {
                "$TAG: fallback active (native did not engage, nit=" + f2(base) + ")"
            }
            val maxNit = maxPanelNit(mgr)
            if (maxNit.isNaN() || maxNit <= 0f) {
                logEvery("nomax", 5000L) { "$TAG: panel max nit unknown, skip boost" }
                return false
            }
            val target = minOf(maxNit, base * factor())
            if (target <= base * RATIO_DONE) {
                logEvery("noboost", 5000L) {
                    "$TAG: already at panel max, skip (nit=" + f2(base) + " max=" + maxNit + ")"
                }
                return false
            }
            XposedHelpers.callMethod(builder, "setHasEdrProcessed")
            XposedHelpers.callMethod(builder, "setSdrNit", base)
            XposedHelpers.callMethod(builder, "setNit", target)
            try {
                XposedHelpers.callMethod(builder, "setReason", "FkOScreen:HdrFix")
            } catch (_: Throwable) {
            }
            logEvery("boost", 1000L) {
                "$TAG: boost nit " + f2(base) + " -> " + f2(target) +
                    " (max=" + f2(maxNit) + " ratio=" + f2(target / base) + ")"
            }
            true
        } catch (t: Throwable) {
            logEvery("boostfail", 3000L) { "$TAG: boost failed: " + t }
            false
        }
    }

    // ── D. HDR 信号：焦点窗口 / relayout / SurfaceFlinger ─────
    private fun hookWindowManager(classLoader: ClassLoader): Int {
        var n = 0
        try {
            val cls = XposedHelpers.findClass("com.android.server.wm.WindowManagerService", classLoader)
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    wms = param.thisObject
                }
            })
            n += hookAll(cls, "relayoutWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (wms == null) wms = param.thisObject
                    try {
                        val args = param.args
                        if (args.size < 6) return
                        val client = args[1] ?: return
                        val attrs = args[2] ?: return
                        val vis = (args[5] as? Int) ?: -1
                        val cm = XposedHelpers.callMethod(attrs, "getColorMode") as? Int ?: 0
                        if (cm == COLOR_MODE_HDR && vis == 0) hdrWindows.add(client)
                        else hdrWindows.remove(client)
                    } catch (_: Throwable) {
                    }
                }
            })
            n += hookAll(cls, "removeWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val client = param.args.getOrNull(1) ?: return
                    hdrWindows.remove(client)
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: WMS not found: " + t.message)
        }
        return n
    }

    /** 焦点窗口是否请求 HDR；null = 反射失败 */
    private fun focusedWindowHdr(): Boolean? = try {
        val w = wms ?: return null
        val root = XposedHelpers.getObjectField(w, "mRoot")
        val dc = XposedHelpers.callMethod(root, "getTopFocusedDisplayContent")
        // Android 16 的 DisplayContent 没有 getFocusedWindow()，直接读 mCurrentFocus
        val win = XposedHelpers.getObjectField(dc, "mCurrentFocus") ?: return false
        val attrs = XposedHelpers.getObjectField(win, "mAttrs")
        (XposedHelpers.callMethod(attrs, "getColorMode") as Int) == COLOR_MODE_HDR
    } catch (t: Throwable) {
        logEvery("focusfail", 10000L) { "$TAG: focus poll failed: " + t }
        null
    }

    /** SurfaceFlinger 是否正在播放 HDR（真 HDR 视频走这条，正常机型也走这条） */
    private fun sfHdrPlaying(): Boolean = try {
        val flinger = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "SurfaceFlinger") ?: return false
        val parcelCls = Class.forName("android.os.Parcel")
        val data = parcelCls.getMethod("obtain").invoke(null)
        val reply = parcelCls.getMethod("obtain").invoke(null)
        parcelCls.getMethod("writeInterfaceToken", String::class.java).invoke(data, SF_TOKEN)
        flinger.javaClass.getMethod(
            "transact", Int::class.javaPrimitiveType, parcelCls, parcelCls, Int::class.javaPrimitiveType
        ).invoke(flinger, CODE_GET_HDR_STATE, data, reply, 0)
        val v = parcelCls.getMethod("readInt").invoke(reply) as Int
        parcelCls.getMethod("recycle").invoke(data)
        parcelCls.getMethod("recycle").invoke(reply)
        v == 1
    } catch (_: Throwable) {
        false
    }

    // ── 状态机 ──────────────────────────────────────────────
    private fun startWatcher() {
        Thread {
            while (true) {
                try {
                    // 焦点窗口是权威值；反射失败才退回 relayout 集合 / SF 播放态
                    val focus = focusedWindowHdr()
                    val want = focus ?: (hdrWindows.isNotEmpty() || sfHdrPlaying())
                    if (want != hdrOn) {
                        hdrOn = want
                        val d = hdrEnhance
                        if (d != null) callPriv(d, "checkIsPlayingHDR")
                        // 原生驱动会自己 sendUpdatePowerState；没有驱动时必须自己点火
                        requestPowerUpdate()
                        try {
                            val h = dpcHandler
                            if (h != null) h.postDelayed({ requestPowerUpdate() }, 700L)
                        } catch (_: Throwable) {
                        }
                        XposedBridge.log(
                            "$TAG: HDR -> " + want +
                                (if (focus == null) "(fallback)" else "(focus=" + focus + ")") +
                                " [" + driverState(d) + "]"
                        )
                    } else if (want) {
                        // HDR 开着但驱动没进提亮态 -> 3s 报一次，便于定位
                        logEvery("stuck", 3000L) {
                            val st = driverState(hdrEnhance)
                            if (st.contains("enhanced=false") || st.contains("need=false")) {
                                "$TAG: HDR on but driver idle [" + st + "]"
                            } else {
                                ""
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
                try {
                    Thread.sleep(POLL_MS)
                } catch (_: InterruptedException) {
                }
            }
        }.apply { isDaemon = true; name = "FkHdr" }.start()
        XposedBridge.log("$TAG: watcher started")
    }
}
