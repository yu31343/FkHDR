package io.github.fkoscreen.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * ColorOS HDR 修复（通用版：无白名单依赖，全应用生效，带降级分支）
 *
 * ── 本机根因 ────────────────────────────────────────────────
 * 1) 产品配置缺 3 个 OPPO feature -> isSupportSdrToHdrRatio() 恒 false，
 *    HDR 提亮 feature 不进亮度后处理链，hdrSdrRatio 停在 NaN。
 * 2) 面板没有 HDR color mode -> SurfaceFlinger 不进 HDR 合成 ->
 *    ColorOS 的 checkIsPlayingHDR() 恒 0（HDR 开关信号缺失）。
 * 3) ColorOS 提亮只改面板 nit，Builder.setNit() 顺手把 sdrNit 也改成同值 ->
 *    面板亮了但 hdrSdrRatio 恒 1。
 *
 * ── 关于「HDR 亮度比例」────────────────────────────────────
 * 不再使用任何写死的比率（旧实现的 1.5625 已删除）。比值完全由系统按**面板硬件上限**推导：
 *   ratio = 面板 nit(getMaxPanelNit()) / SDR 白点 nit
 * 所以亮度越低比值越大（低亮度余量多），亮度拉满时自然回落到 1.0；
 * HDR 关闭后 SDR 白点与面板重新相等，比值动画回落 1.0。
 *
 * ── 设计原则 ────────────────────────────────────────────────
 * · 亮度算法不实现：优先驱动 ColorOS 自己的 OplusFeatureHDREnhanceBrightness
 *   （enterEnhanceBrightness / exitEnhanceBrightness，含它自己的动画与硬件上限 clamp）。
 * · 不走白名单：直接接管 mEnterWhiteApp，任何前台应用都能触发，与 <hdr_enhance_method> 表无关。
 * · 降级：找不到 ColorOS 的 HDR 提亮类时，退回 ColorOS 通用的 nit 后处理链
 *   （OplusBrightnessPostProcessingManager），仍然不碰面板驱动。
 * · 所有 hook 都 try/catch，类不存在就跳过，不会影响其它机型/版本。
 *
 * 关闭：setprop persist.sys.fkoscreen.hdr_fix 0（重启生效）
 */
object HdrBoostHook {
    private const val TAG = "FkOScreen:HdrFix"
    private const val PROP_ENABLE = "persist.sys.fkoscreen.hdr_fix"
    private const val PROP_FACTOR = "persist.sys.fkoscreen.hdr_factor"

    private const val COLOR_MODE_HDR = 2
    private const val CODE_GET_HDR_STATE = 21012
    private const val SF_TOKEN = "android.ui.ISurfaceComposer"
    private const val POLL_MS = 400L

    private const val CLS_HDR_ENHANCE =
        "com.android.server.display.feature.postprocess.OplusFeatureHDREnhanceBrightness"
    private const val CLS_POST_MANAGER =
        "com.android.server.display.feature.postprocess.OplusBrightnessPostProcessingManager"
    private const val CLS_LOCAL_DEVICE_EXT =
        "com.android.server.display.LocalDisplayDeviceExtImpl"

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

    /** 可见且 LayoutParams.colorMode == COLOR_MODE_HDR 的窗口（relayout 事件维护） */
    private val hdrWindows = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())
    )

    fun init(classLoader: ClassLoader) {
        hookFeatures(classLoader)
        nativeDriver = hookOplusHdrEnhance(classLoader)
        if (!nativeDriver) hookGenericBoost(classLoader)
        hookWindowManager(classLoader)
        startWatcher()
        XposedBridge.log("$TAG: init done, nativeDriver=$nativeDriver")
    }

    // ── 属性 ────────────────────────────────────────────────
    private fun prop(key: String, def: String): String = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
            .invoke(null, key, def) as String
    } catch (_: Throwable) {
        def
    }

    /** 开关优先级：系统属性 > 模块界面开关（复用原 hdr_ratio_fix 键） */
    private fun enabled(): Boolean {
        val p = prop(PROP_ENABLE, "")
        if (p.isNotEmpty()) return p != "0" && !p.equals("false", true)
        return try {
            io.github.fkoscreen.ConfigManager.isHdrFixEnabled()
        } catch (_: Throwable) {
            true
        }
    }

    /** 提亮倍数；默认 100 -> 由 ColorOS 自己 clamp 到 getMaxPanelNit() 硬件上限 */
    private fun factor() = prop(PROP_FACTOR, "100").toFloatOrNull()?.takeIf { it > 1f } ?: 100f

    // ── A. 补 feature 配置（所有 ColorOS 通用） ──────────────
    private fun hookFeatures(classLoader: ClassLoader) {
        try {
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
            XposedBridge.hookAllMethods(svc, "hasFeature", hook)
            XposedBridge.hookAllMethods(svc, "hasFeatureMap", hook)
            XposedBridge.log("$TAG: hooked feature service")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: feature service not found: ${t.message}")
        }
    }

    // ── B. 首选：驱动 ColorOS 自己的 HDR 提亮实现 ────────────
    private fun hookOplusHdrEnhance(classLoader: ClassLoader): Boolean {
        return try {
            val cls = XposedHelpers.findClass(CLS_HDR_ENHANCE, classLoader)
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    hdrEnhance = param.thisObject
                }
            })
            // 保住 SDR 白点：ColorOS 的 setNit() 会把 sdrNit 一起改掉，
            // 先置 hasEdrProcessed 后，系统自己的 handleHdrSdrNitsChanged() 才能算出真实比率。
            XposedBridge.hookAllMethods(cls, "postProcessing", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled() || !hdrOn) return
                    val builder = param.args.getOrNull(0) ?: return
                    try {
                        XposedHelpers.callMethod(builder, "setHasEdrProcessed")
                    } catch (_: Throwable) {
                    }
                }
            })
            // 接管 HDR 状态：不走白名单，直接设 mEnterWhiteApp，然后走它自己的 enter/exit。
            XposedBridge.hookAllMethods(cls, "checkIsPlayingHDR", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!enabled()) return
                    val self = param.thisObject
                    try {
                        XposedHelpers.setBooleanField(self, "mEnterWhiteApp", hdrOn)
                        XposedHelpers.setFloatField(self, "mEnhanceFactor", factor())
                        if (hdrOn) XposedHelpers.callMethod(self, "enterEnhanceBrightness")
                        else XposedHelpers.callMethod(self, "exitEnhanceBrightness")
                    } catch (_: Throwable) {
                    }
                }
            })
            XposedBridge.log("$TAG: native driver hooked (OplusFeatureHDREnhanceBrightness)")
            true
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: native driver unavailable: ${t.message}")
            false
        }
    }

    // ── C. 降级：ColorOS 通用 nit 后处理链（其它版本/机型） ──
    private fun hookGenericBoost(classLoader: ClassLoader) {
        try {
            val ext = XposedHelpers.findClass(CLS_LOCAL_DEVICE_EXT, classLoader)
            XposedBridge.hookAllMethods(ext, "isSupportSdrToHdrRatio", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (enabled()) param.result = true
                }
            })
        } catch (_: Throwable) {
        }
        try {
            val mgr = XposedHelpers.findClass(CLS_POST_MANAGER, classLoader)
            XposedBridge.hookAllMethods(mgr, "convertNitToBrightness", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled() || !hdrOn) return
                    try {
                        val builder = param.args.getOrNull(0) ?: return
                        val bc = builder.javaClass
                        val base = (bc.getMethod("getNit").invoke(builder) as? Float) ?: return
                        if (base.isNaN() || base <= 0f) return
                        val model = XposedHelpers.getObjectField(param.thisObject, "mBrightnessModel")
                        val maxNit = XposedHelpers.callMethod(model, "getMaxPanelNit") as Float
                        if (maxNit <= 0f) return
                        val target = minOf(maxNit, base * factor())
                        if (target <= base * 1.02f) return
                        XposedHelpers.callMethod(builder, "setHasEdrProcessed")
                        XposedHelpers.callMethod(builder, "setSdrNit", base)
                        XposedHelpers.callMethod(builder, "setNit", target)
                        XposedHelpers.callMethod(builder, "setReason", "FkOScreen:HdrBoost")
                    } catch (_: Throwable) {
                    }
                }
            })
            XposedBridge.log("$TAG: generic nit boost hooked")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: generic boost unavailable: ${t.message}")
        }
    }

    // ── D. HDR 信号：焦点窗口 / relayout / SurfaceFlinger ─────
    private fun hookWindowManager(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass("com.android.server.wm.WindowManagerService", classLoader)
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    wms = param.thisObject
                }
            })
            XposedBridge.hookAllMethods(cls, "relayoutWindow", object : XC_MethodHook() {
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
            try {
                XposedBridge.hookAllMethods(cls, "removeWindow", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val client = param.args.getOrNull(1) ?: return
                        hdrWindows.remove(client)
                    }
                })
            } catch (_: Throwable) {
            }
            XposedBridge.log("$TAG: hooked WindowManagerService")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: WMS not found: ${t.message}")
        }
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
    } catch (_: Throwable) {
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
                        hdrEnhance?.let {
                            try {
                                XposedHelpers.callMethod(it, "checkIsPlayingHDR")
                            } catch (_: Throwable) {
                            }
                        }
                        XposedBridge.log("$TAG: HDR -> $want")
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
