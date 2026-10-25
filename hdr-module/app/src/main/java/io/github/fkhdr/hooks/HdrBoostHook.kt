package io.github.fkhdr.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * FkHDR · HDR 提亮修复（独立模块，从 FkOScreen 拆分）
 *
 * 目标：HDR 内容出现时，把面板推到**物理最大亮度**并让系统上报**真实 HDR/SDR 比值**；
 * 内容结束立即回落。无白名单，全应用生效，只看内容不看包名。
 *
 * ── 平台支持（严格分级，互斥挂载，不跨平台制造错误日志）────────
 *  COLOROS  一加/OPPO/realme 官方 ROM（ColorOS 16/17）：走 OPPO 原生提亮驱动，质量最好
 *  AOSP     类原生（LineageOS / PE / GSI / HyperOS 等）：走 AOSP 显示栈主动推送
 *  UNKNOWN  只挂通用链路（DPC / WMS / 媒体会话 / HDR 检测），不碰任何私有类
 *
 * ── 属性（新名字优先，自动回落 FkOScreen 旧名字）──────────────
 *  persist.sys.fkhdr.fix        0 = 关闭
 *  persist.sys.fkhdr.factor     提亮倍数（默认 100，由面板 nit 上限截断）
 *  persist.sys.fkhdr.ratio_max  人工指定比值上限（默认按面板物理上限）
 *  persist.sys.fkhdr.native     0 = 不用 OPPO 原生驱动
 *  persist.sys.fkhdr.fallback   0 = 不用通用兜底
 *  persist.sys.fkhdr.video      0 = 不把视频播放算作 HDR 场景
 *  persist.sys.fkhdr.aosp_force 1 = 在 ColorOS 上也强行走 AOSP 通路（调试用）
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
 * 所有关键节点写 XposedBridge.log（LSPosed 管理器 → 日志 可见），前缀 FkHDR:HdrFix。
 * 不生效时日志能直接区分：「类没找到 / hook 没挂上 / feature 没开 /
 * 系统没调 postProcessing / 提亮被上限截断 / 亮度没推上去 / 比值没算出来」。
 */
object HdrBoostHook {
    private const val TAG = "FkHDR:HdrFix"
    private const val PROP_ENABLE = "persist.sys.fkhdr.fix"
    private const val PROP_FACTOR = "persist.sys.fkhdr.factor"
    /** 比值上限：物理硬件能接受的最大 HDR/SDR 比值（默认 8.0 = ColorOS 自己 getHighestHdrSdrRatio 的声明值） */
    private const val PROP_RATIO_MAX = "persist.sys.fkhdr.ratio_max"
    /** 1=启用原生驱动（默认），0=只用通用兜底（用于定位/兜底） */
    private const val PROP_NATIVE = "persist.sys.fkhdr.native"
    /** 1=允许通用兜底（默认），0=只走 ColorOS 原生驱动（用于验证原生链路是否独立可用） */
    private const val PROP_FALLBACK = "persist.sys.fkhdr.fallback"
    /** 1=视频播放也算 HDR 场景（默认），0=只认窗口 colorMode。视频判定不看音频。 */
    private const val PROP_VIDEO = "persist.sys.fkhdr.video"

    // ── 向后兼容：FkOScreen 旧模块用过的属性名 ──────────────────
    // 用户从旧模块迁移时不必重新 setprop：新名字优先，读不到自动回落旧名字。
    private const val PROP_ENABLE_OLD = "persist.sys.fkoscreen.hdr_fix"
    private const val PROP_FACTOR_OLD = "persist.sys.fkoscreen.hdr_factor"
    private const val PROP_RATIO_MAX_OLD = "persist.sys.fkoscreen.hdr_ratio_max"
    private const val PROP_NATIVE_OLD = "persist.sys.fkoscreen.hdr_native"
    private const val PROP_FALLBACK_OLD = "persist.sys.fkoscreen.hdr_fallback"
    private const val PROP_VIDEO_OLD = "persist.sys.fkoscreen.hdr_video"
    private const val PROP_HBM = "persist.sys.fkhdr.hbm"
    private const val PROP_HBM_OLD = "persist.sys.fkoscreen.hdr_hbm"
    private const val PROP_AOSP_FORCE = "persist.sys.fkhdr.aosp_force"
    private const val PROP_AOSP_FORCE_OLD = "persist.sys.fkoscreen.hdr_aosp_force"

    private const val PLAYBACK_PLAYING = 3

    private const val COLOR_MODE_HDR = 2
    private const val CODE_GET_HDR_STATE = 21012
    private const val SF_TOKEN = "android.ui.ISurfaceComposer"
    private const val POLL_MS = 400L
    /** 类原生退出迟滞：HDR 消失后继续维持这么久才回落，消除瞬时闪烁 */
    private const val HDR_EXIT_HOLD_MS = 1200L

    /** 认为「系统自己已经提亮过」的阈值：nit / sdrNit */
    private const val RATIO_DONE = 1.02f

    private const val CLS_HDR_ENHANCE =
        "com.android.server.display.feature.postprocess.OplusFeatureHDREnhanceBrightness"
    private const val CLS_POST_MANAGER =
        "com.android.server.display.feature.postprocess.OplusBrightnessPostProcessingManager"
    private const val CLS_LOCAL_DEVICE_EXT =
        "com.android.server.display.LocalDisplayDeviceExtImpl"
    private const val CLS_DMS = "com.android.server.display.DisplayManagerService"
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

    /**
     * 平台分级。严格互斥，不跨平台挂 hook：
     *   COLOROS —— 有 OPPO 私有 ExtImpl/feature 体系（ColorOS / realme / OnePlus 官方 ROM）
     *   AOSP    —— 纯 AOSP 显示栈（LineageOS / PE / GSI 等类原生）
     *   UNKNOWN —— 只有通用链路（DPC / WMS / 媒体会话 / HDR 检测），不碰任何私有类
     *
     * 目的：避免在 AOSP 上反复触发 ClassNotFoundException 产生无意义错误日志。
     */
    private const val PLAT_COLOROS = "coloros"
    private const val PLAT_AOSP = "aosp"
    private const val PLAT_UNKNOWN = "unknown"

    @Volatile private var platform = PLAT_UNKNOWN

    private fun detectPlatform(cl: ClassLoader): String {
        // ① OPPO 官方 ROM：Feature 服务类（ColorOS 16/17 均存在）
        if (exists(cl, "com.android.server.content.OplusFeatureConfigManagerService")) return PLAT_COLOROS
        // ② 退一步：个别版本/机型 Service 类名不同，但 OPPO 的 Feature 门面一定在
        if (exists(cl, "com.oplus.content.OplusFeatureConfigManager")) return PLAT_COLOROS
        // ③ 有 AOSP 显示栈就是类原生（LineageOS / PE / GSI / HyperOS 等）
        if (exists(cl, "com.android.server.display.LocalDisplayAdapter")) return PLAT_AOSP
        return PLAT_UNKNOWN
    }

    private fun exists(cl: ClassLoader, name: String): Boolean = try {
        XposedHelpers.findClass(name, cl)
        true
    } catch (_: Throwable) {
        false
    }

    fun init(classLoader: ClassLoader) {
        platform = detectPlatform(classLoader)
        val steps = StringBuilder("platform=").append(platform)

        // ── 平台专属：ColorOS ──────────────────────────────
        if (platform == PLAT_COLOROS) {
            steps.append(" features=").append(hookFeatures(classLoader))
            steps.append(" ratioMax=").append(hookSystemRatioMax(classLoader))
            steps.append(" featCfg=").append(hookFeatureSingleton(classLoader))
            steps.append(" fmgr=").append(hookFeatureManager(classLoader))
            steps.append(" ratioFlag=").append(hookRatioSupport(classLoader))
            nativeDriver = if (prop2(PROP_NATIVE, PROP_NATIVE_OLD, "1") != "0") {
                hookOplusHdrEnhance(classLoader)
            } else {
                false
            }
            steps.append(" native=").append(nativeDriver)
            val boostN = hookPostProcessBoost(classLoader)
            steps.append(" boost=").append(boostN)
            // ColorOS 上 OPPO 通路全部失配时（未来版本改类名等），退回 AOSP 通路兜底
            if (!nativeDriver && boostN == 0) {
                oppoPathDead = true
                XposedBridge.log("$TAG: OPPO path all missing on coloros -> enable AOSP fallback")
            }
        }

        // ── 平台专属：AOSP ─────────────────────────────────
        // 仅当 ColorOS 专属路径不可用、且确实是 AOSP 显示栈时才启用
        val forceAosp = prop2(PROP_AOSP_FORCE, PROP_AOSP_FORCE_OLD, "0") == "1"
        aospMode = platform == PLAT_AOSP || forceAosp || oppoPathDead
        if (aospMode) {
            steps.append(" aosp[").append(hookAospLocalDisplay(classLoader)).append("]")
            steps.append(" gate=").append(hookAospRatioGate(classLoader))
            steps.append(" hbm=").append(hookHighBrightnessModeController(classLoader))
        }

        // ── 通用：三类平台都挂（都是 AOSP 基础类，必然存在）──
        // DisplayInfo 伪装也放通用段：patchDisplayInfo 自带守卫（已含 11/12 就原样返回），
        // 真 HDR 机型不会被改动；缺 HDR 色彩模式的机型（本机）才会被补上。
        steps.append(" forge=").append(hookDisplayInfoForgery(classLoader))
        steps.append(" dpc=").append(hookDisplayPowerController(classLoader))
        steps.append(" media=").append(hookMediaSessions(classLoader))
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

    /** 新属性名优先，读不到自动回落 FkOScreen 旧属性名（迁移兼容） */
    private fun prop2(key: String, legacy: String, def: String): String {
        val v = prop(key, SENTINEL)
        if (v != SENTINEL) return v
        return prop(legacy, def)
    }

    private const val SENTINEL = "\u0001"

    // enabled() 在 watcher 里每 400ms 调一次，加 5s 缓存避免反复 read syscall
    @Volatile private var enabledCache = true
    @Volatile private var enabledAt = 0L

    /** 开关优先级：系统属性 > 模块界面开关（复用原 hdr_ratio_fix 键） */
    private fun enabled(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - enabledAt < 5000L) return enabledCache
        val v = try {
            val p = prop2(PROP_ENABLE, PROP_ENABLE_OLD, "")
            if (p.isNotEmpty()) p != "0" && !p.equals("false", true)
            else io.github.fkhdr.Config.isHdrFixEnabled()
        } catch (_: Throwable) {
            true
        }
        enabledCache = v
        enabledAt = now
        return v
    }

    /** 提亮倍数；默认 100 -> 由面板 nit 上限截断，等于「只受硬件限制」 */
    private fun factor() = prop2(PROP_FACTOR, PROP_FACTOR_OLD, "100").toFloatOrNull()?.takeIf { it > 1f } ?: 100f

    /**
     * 物理比值上限。取值优先级：
     *   ① 属性 persist.sys.fkhdr.ratio_max（人工指定）
     *   ② ColorOS 自己声明的 DisplayManagerServiceExtImpl#getHighestHdrSdrRatio()
     *      —— 这是 OPPO 对该面板 HDR 能力的官方口径，不是我们拍脑袋的数
     *   ③ 兜底 8.0
     * 面板/HBM 峰值有限，比值超过硬件能力只能靠压暗 SDR 白点硬凑，观感反而更差。
     */
    @Volatile private var sysRatioMax = 0f
    /** DisplayPowerController 里的 DisplayDeviceConfig（面板配置，含 HDR 曲线） */
    @Volatile private var displayConfig: Any? = null
    /** 面板是否声明了 sdrToHdrRatioSpline */
    @Volatile private var hasRatioSpline = false
    /** 最近一次按面板曲线算出的比值上限（供原生路径复用） */
    @Volatile private var lastRatioCap = Float.MAX_VALUE

    /**
     * 该档位下"面板自己承认"的 HDR/SDR 比值上限。分级推导，不写死任何数字：
     *   ① 属性 persist.sys.fkhdr.ratio_max —— 人工覆盖
     *   ② 面板配置曲线 DisplayDeviceConfig.hasSdrToHdrRatioSpline() + getHdrBrightnessFromSdr()
     *      —— 厂商为这块屏写的 nits→ratio 曲线，换机型自动适配
     *   ③ 面板物理峰值 getMaxPanelNit() / sdrNit —— 不设额外上限，交给硬件本身
     */
    private fun ratioCapAt(mgr: Any, sdrNit: Float): Float {
        prop2(PROP_RATIO_MAX, PROP_RATIO_MAX_OLD, "").toFloatOrNull()?.takeIf { it > 1f }?.let { return it }
        val cfg = displayConfig ?: return Float.MAX_VALUE
        if (!hasRatioSpline || sdrNit <= 0f || sdrNit.isNaN()) return Float.MAX_VALUE
        return try {
            val model = XposedHelpers.getObjectField(mgr, "mBrightnessModel")
            // nit -> brightness
            val sdrB = XposedHelpers.callMethod(model, "getValueByCurve", "backlights", sdrNit) as? Float
            if (sdrB == null || sdrB <= 0f) return Float.MAX_VALUE
            // 面板曲线给出的 HDR brightness
            val hdrB = XposedHelpers.callMethod(
                cfg, "getHdrBrightnessFromSdr", sdrB, Float.MAX_VALUE
            ) as? Float
            if (hdrB == null || hdrB <= 0f) return Float.MAX_VALUE
            // brightness -> nit，换算回 hdrSdrRatio 的同一量纲
            val hdrNit = XposedHelpers.callMethod(model, "getValueByCurve", "nits", hdrB) as? Float
                ?: return Float.MAX_VALUE
            val r = hdrNit / sdrNit
            if (r > 1f) r else Float.MAX_VALUE
        } catch (_: Throwable) {
            Float.MAX_VALUE
        }
    }

    /** 原生路径用的倍率：受面板曲线上限约束 */
    private fun nativeFactor(): Float = minOf(factor(), lastRatioCap)

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

    // ── A0. 读取系统自己声明的最大 HDR/SDR 比值 ─────────────
    // DisplayManagerServiceExtImpl#getHighestHdrSdrRatio() 是 OPPO 对"这个面板最多能到几倍"的官方答案。
    // 用它当上限，比我们硬编码任何数字都更贴近"物理硬件可接受"。
    private fun hookSystemRatioMax(classLoader: ClassLoader): Boolean = try {
        val cls = XposedHelpers.findClass(
            "com.android.server.display.DisplayManagerServiceExtImpl", classLoader
        )
        val n = hookAll(cls, "getHighestHdrSdrRatio", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val v = (param.result as? Float) ?: return
                if (v > 1f && v != sysRatioMax) {
                    sysRatioMax = v
                    XposedBridge.log("$TAG: system declared max hdr/sdr ratio = " + f2(v))
                }
            }
        })
        // 顺带主动问一次，避免等别人调用
        try {
            val inst = XposedHelpers.callStaticMethod(
                cls, "getInstance"
            )
            if (inst != null) {
                val v = XposedHelpers.callMethod(inst, "getHighestHdrSdrRatio") as? Float
                if (v != null && v > 1f) sysRatioMax = v
            }
        } catch (_: Throwable) {
        }
        n > 0
    } catch (t: Throwable) {
        logEvery("ratioMaxCls", 60000L) { "$TAG: getHighestHdrSdrRatio unavailable: " + t.message }
        false
    }

    // ── A0. 全局伪装"HDR 可用" ───────────────────────────────
    //
    // 本机 HWC 没有 HDR 通路（SF supportedHdrTypes=INVALID），但 OPPO 已经在配置里声明了
    // hdrCapabilities=[1,2,3,4]。缺的只是 display 色彩模式里的 HDR 色域模式
    // （BT2020=10 / BT2100_PQ=11 / BT2100_HLG=12）。
    //
    // 这里对「返回给应用的 DisplayInfo」补上这三个模式：应用侧 getSupportedColorModes()
    // 就能看到 HDR 可用，从而有机会主动 setColorMode(COLOR_MODE_HDR)——
    // 一旦它这么做，窗口 mAttrs.colorMode=2，我们现有检测器直接命中，无需音频/无需白名单。
    //
    // 只改 binder 返回值，不动 DisplayManagerService 内部状态，也不动 HWC。
    // 实测：HDR colorMode 窗口在本机被 SF 静默降级为 SDR，不会花屏。
    private fun hookDisplayInfoForgery(classLoader: ClassLoader): Int = try {
        val binder = XposedHelpers.findClass(
            CLS_DMS + '$' + "BinderService", classLoader
        )
        hookAll(binder, "getDisplayInfo", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!enabled()) return
                patchDisplayInfo(param.result ?: return)
            }
        })
    } catch (t: Throwable) {
        XposedBridge.log("$TAG: BinderService not found: " + t.message)
        0
    }

    private fun patchDisplayInfo(info: Any) {
        try {
            val cur = XposedHelpers.getObjectField(info, "supportedColorModes") as? IntArray
            if (cur != null && cur.contains(11) && cur.contains(12)) return
            val src = cur ?: IntArray(0)
            val set = java.util.TreeSet<Int>()
            for (v in src) set.add(v)
            set.add(10) // BT2020
            set.add(11) // BT2100_PQ
            set.add(12) // BT2100_HLG
            val out = IntArray(set.size)
            var i = 0
            for (v in set) {
                out[i++] = v
            }
            XposedHelpers.setObjectField(info, "supportedColorModes", out)
            logEvery("forge", 60000L) {
                "$TAG: forged DisplayInfo HDR colorModes " +
                    src.joinToString(",") + " -> " + out.joinToString(",")
            }
        } catch (_: Throwable) {
        }
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
                        XposedHelpers.setFloatField(self, "mEnhanceFactor", nativeFactor())
                        XposedHelpers.setBooleanField(self, "mNeedToEnhanceBrightness", true)
                    } catch (_: Throwable) {
                    }
                    logEvery("pp", 2000L) {
                        "$TAG: pp nit=" + num(builder, "getNit") +
                            "/" + num(builder, "getSdrNit") +
                            " cap=" + (if (lastRatioCap >= 1e6f) "none(physical)" else f2(lastRatioCap)) +
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
                        XposedHelpers.setFloatField(self, "mEnhanceFactor", nativeFactor())
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
                try {
                    val cfg = XposedHelpers.getObjectField(self, "mDisplayDeviceConfig")
                    displayConfig = cfg
                    hasRatioSpline = XposedHelpers.callMethod(
                        cfg, "hasSdrToHdrRatioSpline"
                    ) as? Boolean ?: false
                    XposedBridge.log(
                        "$TAG: panel config ready [sdrToHdrSpline=" + hasRatioSpline + "]"
                    )
                } catch (t: Throwable) {
                    logEvery("cfg", 60000L) { "$TAG: display config unavailable: " + t.message }
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
    private fun fallbackEnabled(): Boolean = prop2(PROP_FALLBACK, PROP_FALLBACK_OLD, "1") != "0"

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
            val cap = ratioCapAt(mgr, base)
            lastRatioCap = cap
            val target = minOf(maxNit, base * factor(), base * cap)
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
                XposedHelpers.callMethod(builder, "setReason", "FkHDR:HdrFix")
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

    // ── H. 类原生（AOSP）通路 ───────────────────────────────
    //
    // AOSP 的比率链与 ColorOS 完全同构（ColorOS 就是在 AOSP 上做 ExtImpl）：
    //   LocalDisplayAdapter$LocalDisplayDevice$1#setDisplayBrightness(float brightness, float sdrBrightness)
    //     ├─ nits = backlightToNits(brightnessToBacklight(brightness))
    //     ├─ sdrNits = backlightToNits(brightnessToBacklight(sdrBrightness))
    //     ├─ mLocalDisplayDeviceExt.setDisplayBrightness(...)      ← OEM 钩子，AOSP 桩类空实现
    //     └─ if (mLocalDisplayDeviceExt.isSupportSdrToHdrRatio() && nits!=0 && sdrNits!=0)
    //             handleHdrSdrNitsChanged(nits, sdrNits)          ← 唯一的比率计算点
    //
    // 类原生上 isSupportSdrToHdrRatio() 默认 false → 比率永不计算 → App 看到恒 1.0。
    // 所以类原生只需两步：
    //   ① 把 isSupportSdrToHdrRatio() 掰成 true（打开比率计算）
    //   ② 让 brightness != sdrBrightness（造出 HDR 峰值与 SDR 白点之差）
    //
    // ②的做法：把 brightness 抬到面板最大、sdrBrightness 保留用户亮度。
    // AOSP 的 brightness 是归一化 0..1（BrightnessEvent 日志 brt=0.45882353），所以峰值取 1.0f。
    // 仅在「OPPO 通路完全不可用」时启用，避免在 ColorOS 上误改 0..2047 量纲。
    /** 内部类分隔符（避免 Kotlin 字符串模板把 $ 当插值） */
    private const val D = "$"

    @Volatile private var aospMode = false
    /** ColorOS 上 OPPO 通路全部失配（原生驱动 + 后处理都挂不上） */
    @Volatile private var oppoPathDead = false
    @Volatile private var extHooked = false

    // ── AOSP 主动推送所需的设备快照 ────────────────────────
    // requestDisplayStateLocked 只在「屏幕状态或亮度发生变化」时才会被调用（DisplayPowerState
    // 内部用 floatEquals 挡住无变化调用），而 HDR 起播本身不产生亮度变化 —— 所以被动注入
    // 永远等不到点火。这里缓存设备对象/状态/用户 SDR 亮度，HDR 翻转时主动推一次。
    @Volatile private var aospDev: Any? = null
    @Volatile private var aospState: Int = -1
    @Volatile private var aospSdr: Float = Float.NaN
    @Volatile private var aospPeak: Float = 1.0f
    /** 已经证明漏斗在跑（避免每次启动都刷日志） */
    @Volatile private var aospFunnelSeen = false

    private fun hookAospLocalDisplay(classLoader: ClassLoader): String {
        // ① 捕获 LocalDisplayDevice 实例 → 拿到 mLocalDisplayDeviceExt 的运行时类 → 挂闸门
        //    同时在这一层改写 brightnessState：这是「面板亮度」进入设备的唯一漏斗，
        //    且它上游 DisplayPowerState 只在亮度真变化时才调它 —— 见 ② 的主动推送。
        var dev = 0
        try {
            val cls = XposedHelpers.findClass(
                "com.android.server.display.LocalDisplayAdapter" + D + "LocalDisplayDevice", classLoader
            )
            dev = hookAll(cls, "requestDisplayStateLocked", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    onAospRequestState(param)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    aospDev = param.thisObject
                    hookExt(param.thisObject)
                    hookInnerDynamic(param.result)
                }
            })
            if (dev == 0) {
                // 退路：构造器
                XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        aospDev = param.thisObject
                        hookExt(param.thisObject)
                    }
                })
                dev = -1
            }
        } catch (t: Throwable) {
            logEvery("aospdev", 60000L) { "$TAG: LocalDisplayDevice unavailable: " + t.message }
        }

        // ② 亮度注入点
        val bright = hookBrightnessInner(classLoader)
        return "dev=" + dev + " bright=" + bright
    }

    // ── 亮度注入点：LocalDisplayDevice 里持有 setDisplayBrightness 的匿名内部类 ──
    //
    // 约定名是 LocalDisplayDevice$1，但「第几个匿名类」取决于 ROM 在该类里插了多少匿名实现，
    // 写死 $1 在改过的 ROM 上会直接失效。因此：
    //   ① 先扫 $1..$9，谁有 setDisplayBrightness 就挂谁；
    //   ② 都扫不到时，等 requestDisplayStateLocked 返回 Runnable，按它的**运行时类**动态挂，
    //      这样与类名、序号、ROM 改动都无关。
    @Volatile private var innerCls: Class<*>? = null
    @Volatile private var innerHooked = false

    private val brightnessHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!aospMode || !enabled() || !hdrOn) return
            val args = param.args
            if (args.size < 2) return
            val sdr = args[1] as? Float ?: return
            if (sdr.isNaN() || sdr <= 0f) return
            // HDR 峰值 → 面板最大。量纲自适应：
            //   AOSP 归一化 0..1（BrightnessEvent brt=0.45882353）→ peak=1.0
            //   ColorOS 绝对 0..2047（mScreenBrightnessRangeMaximum）→ peak=该值
            val cur = args[0] as? Float ?: return
            val peak = if (cur > 1.5f) absMaxBrightness() else 1.0f
            if (peak <= sdr) return
            args[0] = peak
            logEvery("aospboost", 2000L) {
                "$TAG: aosp boost brightness " + f2(sdr) + " -> " + f2(peak) +
                    " (sdr kept " + f2(sdr) + ")"
            }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            // 兜底：不依赖 hasSdrToHdrRatioSpline() 闸门（实测被 ART 内联，hook 不生效）
            pushRatioDirect(param.thisObject, param.args)
        }
    }

    private fun hookBrightnessInner(classLoader: ClassLoader): Int {
        for (i in 1..9) {
            val name = "com.android.server.display.LocalDisplayAdapter" + D + "LocalDisplayDevice" + D + i
            val c = try {
                XposedHelpers.findClass(name, classLoader)
            } catch (_: Throwable) {
                null
            } ?: continue
            val n = hookInner(c)
            if (n > 0) return n
        }
        logEvery("aospbright", 60000L) {
            "$TAG: setDisplayBrightness inner class not found by name; will hook dynamically on first Runnable"
        }
        return 0
    }

    private fun hookInner(cls: Class<*>): Int {
        if (innerHooked) return 1
        if (!hasMethod(cls, "setDisplayBrightness")) return 0
        val n = hookAll(cls, "setDisplayBrightness", brightnessHook)
        if (n > 0) {
            innerCls = cls
            innerHooked = true
            XposedBridge.log("$TAG: brightness hook installed on " + cls.name + " (" + n + " method)")
            hookHdrRatioProbe(cls)
        }
        return n
    }

    /** requestDisplayStateLocked 返回的 Runnable 就是那个匿名类实例 —— 按运行时类动态挂 */
    private fun hookInnerDynamic(r: Any?) {
        if (innerHooked || r == null) return
        try {
            hookInner(r.javaClass)
        } catch (_: Throwable) {
        }
    }

    /**
     * requestDisplayStateLocked 的入参改写点。
     *
     * 形参：(int state, float brightnessState, float sdrBrightnessState[, DisplayOffloadSession])
     *   brightnessState    → 面板背光（AOSP 归一化 0..1，1.0 = 面板物理最大 = HBM 区）
     *   sdrBrightnessState → SDR 白点（保留用户当前亮度，不动）
     *
     * 把前者抬到 1.0、后者保留用户值 → 面板变亮且 SDR 内容不变，
     * 比值 = nits(1.0)/nits(用户) 才有意义。
     */
    private fun onAospRequestState(param: XC_MethodHook.MethodHookParam) {
        val a = param.args
        if (a.size < 3) return
        val st = a[0] as? Int ?: return
        val b = a[1] as? Float ?: return
        val sdr = a[2] as? Float ?: return

        aospDev = param.thisObject
        aospState = st
        if (sdr.isFinite() && sdr > 0.001f) aospSdr = sdr

        if (!aospFunnelSeen) {
            aospFunnelSeen = true
            // useSurfaceControlBrightness 决定 AOSP 会不会算比值：false 时 handleHdrSdrNitsChanged
            // 会把 ratio 直接置 NaN（只剩物理提亮）。这条日志能一眼判定类原生上到底有没有比值能力。
            var sc = "?"
            try {
                val ba = XposedHelpers.getObjectField(param.thisObject, "mBacklightAdapter")
                sc = XposedHelpers.getBooleanField(ba, "mUseSurfaceControlBrightness").toString()
            } catch (_: Throwable) {
            }
            XposedBridge.log(
                "$TAG: aosp funnel alive (state=" + st + " brightness=" + f2(b) +
                    " sdr=" + f2(sdr) + " useSurfaceControlBrightness=" + sc + ")"
            )
        }
        if (!aospMode || !enabled() || !hdrOn) return

        val sdrEff = if (sdr.isFinite() && sdr > 0.001f) sdr else b
        val peak = if (b > 1.5f) absMaxBrightness() else 1.0f
        aospPeak = peak
        if (peak > sdrEff) {
            a[1] = peak
            logEvery("aosppush", 2000L) {
                "$TAG: aosp push brightness " + f2(sdrEff) + " -> " + f2(peak) +
                    " (sdr kept " + f2(sdrEff) + ")"
            }
        }
    }

    /**
     * HDR 翻转时主动推一次亮度。
     *
     * 为什么必须主动推：DisplayPowerState 只在值真变化时才 scheduleScreenUpdate，
     * 而 HDR 起播不改亮度 → requestDisplayStateLocked 根本不会被调用 → 被动 hook 永不触发。
     * 这里直接反射调一次 requestDisplayStateLocked 造出「变化」，让系统自己走完整链路
     * （内部会同时更新 mBrightnessState，所以退出 HDR 时能自然回落到用户亮度）。
     */
    private fun pushAospBrightness(boost: Boolean) {
        if (!aospMode) return
        val dev = aospDev
        if (dev == null) {
            logEvery("aospnodev", 5000L) {
                "$TAG: aosp push skipped: LocalDisplayDevice not captured yet"
            }
            return
        }
        val sdr = aospSdr
        if (!sdr.isFinite() || sdr <= 0.001f) {
            logEvery("aospnosdr", 5000L) {
                "$TAG: aosp push skipped: no user sdr brightness yet"
            }
            return
        }
        val target = if (boost) {
            if (absMaxBrightness() > 1.5f) absMaxBrightness() else 1.0f
        } else {
            sdr
        }
        // 状态必须取设备自己的 mState：随便传一个值会让 runnable 误以为要切换电源状态，
        // 在 DOZE/AOD 下可能把屏幕直接点亮。
        var st = aospState
        try {
            st = XposedHelpers.getIntField(dev, "mState")
        } catch (_: Throwable) {
        }
        if (st != 2) {
            // 2 = Display.STATE_ON。非亮屏态不推亮度，交给之后的被动改写
            logEvery("aospstate", 5000L) {
                "$TAG: aosp push skipped: display not ON (state=" + st + ")"
            }
            return
        }
        val task = Runnable {
            try {
                val m = dev.javaClass.declaredMethods.firstOrNull {
                    it.name == "requestDisplayStateLocked" &&
                        it.parameterTypes.size >= 3 &&
                        it.parameterTypes[0] == Integer.TYPE &&
                        it.parameterTypes[1] == java.lang.Float.TYPE &&
                        it.parameterTypes[2] == java.lang.Float.TYPE
                }
                if (m == null) {
                    logEvery("aosprdsl", 5000L) {
                        "$TAG: aosp push failed: requestDisplayStateLocked signature not found"
                    }
                    return@Runnable
                }
                m.isAccessible = true
                val callArgs = arrayOfNulls<Any?>(m.parameterTypes.size)
                callArgs[0] = st
                callArgs[1] = target
                callArgs[2] = sdr
                val runnable = m.invoke(dev, *callArgs) as? Runnable
                if (runnable == null) {
                    // 没有产生 Runnable = 系统认为没变化，说明上一次已经推到位
                    logEvery("aospnoop", 5000L) {
                        "$TAG: aosp push no-op (already " + f2(target) + ")"
                    }
                    return@Runnable
                }
                runnable.run()
                logEvery("aosppushok", 1000L) {
                    "$TAG: aosp push applied brightness=" + f2(target) +
                        " sdr=" + f2(sdr) + " state=" + st
                }
            } catch (t: Throwable) {
                logEvery("aosppushfail", 3000L) { "$TAG: aosp push failed: " + t }
            }
        }
        try {
            val h = dpcHandler
            if (h != null) h.post(task) else task.run()
        } catch (_: Throwable) {
            task.run()
        }
    }

    /** 绝对量纲下的面板最大亮度（ColorOS 等非归一化实现） */
    private fun absMaxBrightness(): Float {
        val d = dpc ?: return 1.0f
        for (f in arrayOf("mScreenBrightnessRangeMaximum", "mScreenBrightnessNormalMaximum")) {
            try {
                val v = XposedHelpers.getFloatField(d, f)
                if (v > 1.5f) return v
            } catch (_: Throwable) {
            }
        }
        return 1.0f
    }

    /**
     * 把运行时 Ext 类的 isSupportSdrToHdrRatio 掰成 true（不依赖具体类名，通吃 AOSP/ColorOS/OEM）。
     *
     * AOSP 的比率计算点在 LocalDisplayDevice#setDisplayBrightness：
     *     if (mLocalDisplayDeviceExt.isSupportSdrToHdrRatio() && nits != 0 && sdrNits != 0)
     *         handleHdrSdrNitsChanged(nits, sdrNits);
     * 类原生上该方法返回 false → 比率恒 1.0。闸门不开，光把面板推亮也不会产生 HDR 比值。
     *
     * 字段名在不同 ROM 上可能是 mLocalDisplayDeviceExt / mLocalDisplayDeviceExtImpl，
     * 类型可能是 class 也可能是 interface —— 因此按名字找不到时按类型名扫描一遍。
     * 失败必须留下日志：旧实现 catch 后静默 return，导致「看起来挂上了其实什么都没做」。
     */
    private fun hookExt(devObj: Any?) {
        if (extHooked || devObj == null) return
        try {
            val ext = findExt(devObj)
            if (ext == null) {
                extHooked = true
                val fields = try {
                    devObj.javaClass.declaredFields.joinToString(",") { it.name }
                } catch (_: Throwable) {
                    "?"
                }
                XposedBridge.log(
                    "$TAG: aosp ratio gate SKIPPED: no LocalDisplayDeviceExt field on " +
                        devObj.javaClass.name + " fields=[" + fields + "]"
                )
                return
            }
            val extCls = ext.javaClass
            val n = XposedBridge.hookAllMethods(extCls, "isSupportSdrToHdrRatio", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (enabled()) param.result = true
                }
            }).size
            extHooked = true
            if (n > 0) {
                XposedBridge.log(
                    "$TAG: aosp ratio gate opened on " + extCls.name + " (" + n + " method)"
                )
            } else {
                val cand = try {
                    extCls.declaredMethods.map { it.name }.filter { it.contains("Ratio", true) }
                        .distinct().joinToString(",")
                } catch (_: Throwable) {
                    "?"
                }
                XposedBridge.log(
                    "$TAG: aosp ratio gate MISSING: " + extCls.name +
                        " has no isSupportSdrToHdrRatio; ratio-ish methods=[" + cand + "]"
                )
            }
        } catch (t: Throwable) {
            extHooked = true
            XposedBridge.log("$TAG: aosp ratio gate failed: " + t)
        }
    }

    // ── AOSP 比值：绕开闸门直接算 ───────────────────────────
    //
    // AOSP 的 setDisplayBrightness 末尾是：
    //     if (getDisplayDeviceConfig().hasSdrToHdrRatioSpline()) handleHdrSdrNitsChanged(nits, sdrNits);
    // 实测（LineageOS 23 / v1.9.2）：hook 了 hasSdrToHdrRatioSpline 并返回 true，但
    // handleHdrSdrNitsChanged 依然一次都没被调用 —— 说明这个调用点被 ART 内联成常量 false，
    // 挂在被调方上不生效。因此改为「自己算、自己调」：
    //     brightnessToBacklight(b) → backlightToNits(backlight) → handleHdrSdrNitsChanged(nits, sdrNits)
    // 这三个都是 $1 上的方法（私有/包私有），反射可调；不依赖任何面板配置声明。
    @Volatile private var ratioB2B: java.lang.reflect.Method? = null
    @Volatile private var ratioB2N: java.lang.reflect.Method? = null
    @Volatile private var ratioApply: java.lang.reflect.Method? = null
    @Volatile private var ratioResolved = false

    private fun pushRatioDirect(inner: Any, args: Array<Any?>) {
        if (!aospMode || !enabled()) return
        // HDR 期间必算；HDR 结束后 5s 内也算一次，把比值收回 1.0
        if (!hdrOn && android.os.SystemClock.elapsedRealtime() >= gateUntil) return
        try {
            if (!ratioResolved) {
                val cls = inner.javaClass
                ratioB2B = cls.declaredMethods.firstOrNull { it.name == "brightnessToBacklight" }
                    ?.also { it.isAccessible = true }
                ratioB2N = cls.declaredMethods.firstOrNull { it.name == "backlightToNits" }
                    ?.also { it.isAccessible = true }
                ratioApply = cls.declaredMethods.firstOrNull { it.name == "handleHdrSdrNitsChanged" }
                    ?.also { it.isAccessible = true }
                ratioResolved = true
                XposedBridge.log(
                    "$TAG: ratio direct ready (brightnessToBacklight=" + (ratioB2B != null) +
                        " backlightToNits=" + (ratioB2N != null) +
                        " handleHdrSdrNitsChanged=" + (ratioApply != null) + ")"
                )
            }
            val b2b = ratioB2B
            val b2n = ratioB2N
            val apply = ratioApply
            if (b2b == null || b2n == null || apply == null) return
            val b = args.getOrNull(0) as? Float ?: return
            val sdr = args.getOrNull(1) as? Float ?: return
            if (b.isNaN() || sdr.isNaN()) return
            val backlight = b2b.invoke(inner, b) as? Float ?: return
            val sdrBacklight = b2b.invoke(inner, sdr) as? Float ?: return
            val nits = b2n.invoke(inner, backlight) as? Float ?: return
            val sdrNits = b2n.invoke(inner, sdrBacklight) as? Float ?: return
            apply.invoke(inner, nits, sdrNits)
            // 系统内部还有两道我们绕不开的条件：
            //   handleHdrSdrNitsChanged 里要求 (mUseSurfaceControlBrightness || mForceSurfaceControl)，
            //   小米还额外要求 mSupportUltraHDR（旧模块就是靠反编译删掉这一条）。
            // 本机实测 mUseSurfaceControlBrightness=false -> 系统会把比值直接写成 NaN。
            // 所以这里再补一步：直接把 mCurrentHdrSdrRatio 写进 LocalDisplayDevice 并刷新 DeviceInfo，
            // 与任何内部条件无关。App 最终读到的就是这个值。
            val ratio = if (sdrNits > 0f) Math.max(1f, nits / sdrNits) else 1f
            forceHdrSdrRatio(inner, ratio)
            logEvery("ratio2", 2000L) {
                "$TAG: ratio direct nits=" + f2(nits) + " sdrNits=" + f2(sdrNits) +
                    " -> ratio=" + f2(ratio) + " current=" + f2(currentHdrSdrRatio(inner))
            }
        } catch (t: Throwable) {
            logEvery("ratiofail", 10000L) { "$TAG: ratio direct failed: " + t }
        }
    }

    /**
     * 直接把比值写进 LocalDisplayDevice，绕开所有内部条件。
     *
     * AOSP 的 handleHdrSdrNitsChanged 有三道门：
     *   ① displayNits/sdrNits != INVALID_NITS
     *   ② mBacklightAdapter.mUseSurfaceControlBrightness || mForceSurfaceControl
     *   ③ 小米私有：LocalDisplayAdapter.mSupportUltraHDR
     * 本机 ② 就是 false，所以系统自己算出来的永远是 NaN。这里跳过它，直接写字段 + 刷新 DeviceInfo。
     */
    private fun forceHdrSdrRatio(inner: Any, ratio: Float) {
        try {
            val dev = XposedHelpers.getObjectField(inner, "this" + D + "0")
            val root = try {
                XposedHelpers.callMethod(dev, "getSyncRoot")
            } catch (_: Throwable) {
                null
            }
            val apply = Runnable {
                try {
                    XposedHelpers.setFloatField(dev, "mCurrentHdrSdrRatio", ratio)
                    XposedHelpers.callMethod(dev, "updateDeviceInfoLocked")
                } catch (t: Throwable) {
                    logEvery("ratioforce", 10000L) { "$TAG: force ratio failed: " + t }
                }
            }
            if (root != null) {
                synchronized(root) { apply.run() }
            } else {
                apply.run()
            }
        } catch (t: Throwable) {
            logEvery("ratioforce2", 10000L) { "$TAG: force ratio failed: " + t }
        }
    }

    /** 读回 LocalDisplayDevice.mCurrentHdrSdrRatio（App 最终看到的值） */
    private fun currentHdrSdrRatio(inner: Any): Float = try {
        val outer = XposedHelpers.getObjectField(inner, "this" + D + "0")
        XposedHelpers.getFloatField(outer, "mCurrentHdrSdrRatio")
    } catch (_: Throwable) {
        Float.NaN
    }

    // ── AOSP HBM 通路（补「配置法」的缺口）─────────────────────
    //
    // 小米/一加等 ROM 的 HDR 提亮最终靠 HighBrightnessModeController 进 HBM：
    //     calculateHighBrightnessMode():
    //         if (!deviceSupportsHbm()) return 0;
    //         if (mIsHdrLayerPresent || ...) return 2;      // ← HDR 时直接 HBM_ON_HDR
    //         return isHbmCurrentlyAllowed() ? 1 : 0;
    // 而 mIsHdrLayerPresent 由 SurfaceControlHdrLayerInfoListener 根据「HDR 图层面积
    // >= 屏幕面积 × minimumHdrPercentOfScreen」置位。很多 ROM 默认要求 HDR 占屏 ≥50%，
    // 或者应用根本没把窗口 colorMode 设成 HDR，于是 SF 永远不上报 → HBM 永远不进。
    //
    // 这类机型常见的修法是「改 displayconfig XML + 反编译 services.jar 删门禁」，
    // 我们改为运行时等效：HDR 期间直接置 mIsHdrLayerPresent=true 并触发 updateHbmMode()。
    // 只在 AOSP 通路生效；ColorOS 有自己的原生驱动，不重复干预。
    @Volatile private var hbmCtl: Any? = null

    private fun hbmEnabled(): Boolean = prop2(PROP_HBM, PROP_HBM_OLD, "1") != "0"

    private fun hookHighBrightnessModeController(classLoader: ClassLoader): Boolean = try {
        val cls = XposedHelpers.findClass(
            "com.android.server.display.HighBrightnessModeController", classLoader
        )
        XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                hbmCtl = param.thisObject
                logEvery("hbmctl", 60000L) { "$TAG: HBM controller captured" }
            }
        })
        true
    } catch (t: Throwable) {
        logEvery("hbmcls", 60000L) { "$TAG: HighBrightnessModeController unavailable: " + t.message }
        false
    }

    /** HDR 翻转时强制/恢复 HBM。失败只打日志，不影响主链路。 */
    private fun applyHbm(boost: Boolean) {
        if (!aospMode || !enabled() || !hbmEnabled()) return
        val c = hbmCtl ?: run {
            logEvery("hbmnoctl", 10000L) { "$TAG: HBM skipped: controller not captured" }
            return
        }
        try {
            XposedHelpers.setBooleanField(c, "mIsHdrLayerPresent", boost)
            try {
                // 目标 HDR/SDR 比：决定 getHdrBrightnessValue() 的 headroom
                XposedHelpers.setFloatField(
                    c, "mMaxDesiredHdrSdrRatio", if (boost) factor().coerceIn(1f, 20f) else 1f
                )
            } catch (_: Throwable) {
            }
            XposedHelpers.callMethod(c, "updateHbmMode")
            val mode = try {
                XposedHelpers.getIntField(c, "mHbmMode")
            } catch (_: Throwable) {
                -1
            }
            logEvery("hbm", 2000L) {
                "$TAG: hbm hdrLayerPresent=" + boost + " -> mode=" + mode + " (2=HBM_ON_HDR)"
            }
        } catch (t: Throwable) {
            logEvery("hbmfail", 10000L) { "$TAG: hbm apply failed: " + t }
        }
    }

    /**
     * AOSP 真正的 HDR 比值闸门。
     *
     * AOSP 16 LocalDisplayAdapter$LocalDisplayDevice$1#setDisplayBrightness 里只有这一句：
     *     if (getDisplayDeviceConfig().hasSdrToHdrRatioSpline()) handleHdrSdrNitsChanged(nits, sdrNits);
     * 面板 XML 没声明 sdr-to-hdr-ratio-spline 时该方法返回 false → 比值永不计算。
     * 本机实测 hasSdrToHdrRatioSpline=false，所以类原生上比值恒为初始 NaN。
     *
     * 直接放行即可：handleHdrSdrNitsChanged 自己会用 backlightToNits 算 nits/sdrNits，
     * HDR 关时 brightness == sdrBrightness → 比值自然回到 1.0，不需要我们再收拾。
     *
     * 注意：OPPO 的 isSupportSdrToHdrRatio() 是另一套（仅 ColorOS 存在），两者都要挂。
     */
    /** 闸门开放窗口：HDR 关闭后再保留 5s，用于让回落那一次也把比值算回 1.0 */
    @Volatile private var gateUntil = 0L
    /** HDR 内容首次消失的时刻（用于退出迟滞） */
    @Volatile private var hdrOffSince = 0L

    private fun hookAospRatioGate(classLoader: ClassLoader): Int = try {
        val cls = XposedHelpers.findClass("com.android.server.display.DisplayDeviceConfig", classLoader)
        val n = XposedBridge.hookAllMethods(cls, "hasSdrToHdrRatioSpline", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!enabled()) return
                // 只在 HDR 期间放行。常开会让「比值算不出（NaN）」的机型每次亮度变化都触发
                // updateDeviceInfoLocked（floatEquals(NaN,NaN) 恒 false），白烧性能。
                if (hdrOn || android.os.SystemClock.elapsedRealtime() < gateUntil) {
                    param.result = true
                }
            }
        }).size
        if (n > 0) {
            XposedBridge.log("$TAG: aosp ratio gate opened on DisplayDeviceConfig.hasSdrToHdrRatioSpline (" + n + " method)")
        } else {
            XposedBridge.log("$TAG: aosp ratio gate MISSING: hasSdrToHdrRatioSpline not found")
        }
        n
    } catch (t: Throwable) {
        XposedBridge.log("$TAG: aosp ratio gate failed: " + t.message)
        0
    }

    /**
     * 比值诊断探针：证明 handleHdrSdrNitsChanged 到底有没有被调用、算出来的比值是多少。
     * 这是类原生上「到底卡在哪一环」的最终判据：
     *   没有这行 → 闸门没开（hasSdrToHdrRatioSpline 没挂上）
     *   有这行但 nits==sdrNits → 亮度没推上去
     */
    @Volatile private var probeInstalled = false

    private fun hookHdrRatioProbe(inner: Class<*>): Int = try {
        val n = XposedBridge.hookAllMethods(inner, "handleHdrSdrNitsChanged", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!enabled()) return
                val nits = param.args.getOrNull(0) as? Float ?: return
                val sdrNits = param.args.getOrNull(1) as? Float ?: return
                logEvery("ratio", 2000L) {
                    "$TAG: ratio probe nits=" + f2(nits) + " sdrNits=" + f2(sdrNits) +
                        " -> ratio=" + f2(if (sdrNits > 0f) nits / sdrNits else Float.NaN)
                }
            }
        }).size
        if (n > 0) probeInstalled = true
        n
    } catch (t: Throwable) {
        logEvery("probe", 60000L) { "$TAG: ratio probe unavailable: " + t.message }
        0
    }

    /** 在 LocalDisplayDevice 上找到 ext 实现对象（字段名/类型名两种策略） */
    private fun findExt(devObj: Any): Any? {
        for (name in arrayOf("mLocalDisplayDeviceExt", "mLocalDisplayDeviceExtImpl")) {
            try {
                val v = XposedHelpers.getObjectField(devObj, name)
                if (v != null) return v
            } catch (_: Throwable) {
            }
        }
        for (f in devObj.javaClass.declaredFields) {
            val typeName = f.type.simpleName
            if (!f.name.contains("LocalDisplayDeviceExt", true) &&
                !typeName.contains("LocalDisplayDeviceExt", true)
            ) continue
            try {
                f.isAccessible = true
                val v = f.get(devObj)
                if (v != null) return v
            } catch (_: Throwable) {
            }
        }
        return null
    }

    // ── F. 视频链路：媒体会话检测 ───────────────────────────
    //
    // 本机平台层没有 HDR 显示能力（SF supportedHdrTypes=INVALID、supportedColorModes 无 HDR），
    // 播放器永远不会把窗口 colorMode 设成 COLOR_MODE_HDR，所以「只看窗口」的视频永远不生效。
    // 这里补一条独立的视频信号：system_server 里的 MediaSessionStack 能拿到所有会话的
    // 播放状态与包名；只要「前台应用正在播放媒体」就视为 HDR 场景，交给同一套提亮链。
    private val playingPkgs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 拥有媒体会话的应用（不限播放状态）。系统视频播放器就是靠这条能被识别到。 */
    private val sessionPkgs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun videoEnabled(): Boolean = prop2(PROP_VIDEO, PROP_VIDEO_OLD, "1") != "0"

    private fun hookMediaSessions(classLoader: ClassLoader): Int = try {
        val cls = XposedHelpers.findClass("com.android.server.media.MediaSessionStack", classLoader)
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!enabled() || !videoEnabled()) return
                val rec = param.args.getOrNull(0) ?: return
                noteSession(rec)
            }
        }
        var n = hookAll(cls, "onPlaybackStateChanged", hook)
        n += hookAll(cls, "addSession", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                recheckNow()
                if (!enabled() || !videoEnabled()) return
                noteSession(param.args.getOrNull(0) ?: return)
            }
        })
        n += hookAll(cls, "removeSession", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val rec = param.args.getOrNull(0) ?: return
                try {
                    val pkg = XposedHelpers.callMethod(rec, "getPackageName") as? String ?: return
                    playingPkgs.remove(pkg)
                    sessionPkgs.remove(pkg)
                } catch (_: Throwable) {
                }
            }
        })
        n += hookAll(cls, "joinSession2Stack", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!enabled() || !videoEnabled()) return
                noteSession(param.args.getOrNull(0) ?: return)
            }
        })
        n
    } catch (t: Throwable) {
        XposedBridge.log("$TAG: MediaSessionStack not found: " + t.message)
        0
    }

    private fun noteSession(rec: Any) {
        try {
            val pkg = XposedHelpers.callMethod(rec, "getPackageName") as? String ?: return
            sessionPkgs[pkg] = android.os.SystemClock.elapsedRealtime()
            var state = -1
            try {
                state = XposedHelpers.getIntField(rec, "mPlaybackState")
            } catch (_: Throwable) {
                try {
                    val st = XposedHelpers.callMethod(rec, "getStateWithUpdatedPosition") ?: return
                    state = XposedHelpers.callMethod(st, "getState") as? Int ?: -1
                } catch (_: Throwable) {
                    return
                }
            }
            if (state == PLAYBACK_PLAYING) {
                playingPkgs[pkg] = android.os.SystemClock.elapsedRealtime()
            } else {
                // 不是播放态：立刻清掉，而不是等超时
                var st2 = state
                if (st2 < 0) {
                    try {
                        val st = XposedHelpers.callMethod(rec, "getStateWithUpdatedPosition") ?: return
                        st2 = XposedHelpers.callMethod(st, "getState") as? Int ?: -1
                    } catch (_: Throwable) {
                    }
                }
                if (st2 != PLAYBACK_PLAYING) playingPkgs.remove(pkg)
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 前台是否"正在放视频"。
     *
     * 只认两个条件同时成立，绝不看音频（音频误判面太大：任何媒体播放都会命中）：
     *   ① 前台应用自己发布了处于 PLAYING 的 MediaSession（说明它在放内容）
     *   ② 前台窗口是全屏/沉浸态（视频播放的典型形态，音乐 App 通常不是）
     *
     * 全屏判据优先用 FLAG_FULLSCREEN，取不到则退化为"窗口高度 ≥ 屏幕高度 95%"。
     */
    private fun videoPlaying(): Boolean {
        if (!videoEnabled()) return false
        val now = android.os.SystemClock.elapsedRealtime()
        val it = playingPkgs.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value > 30000L) it.remove()
        }
        val it2 = sessionPkgs.entries.iterator()
        while (it2.hasNext()) {
            val e = it2.next()
            if (now - e.value > 600000L) it2.remove()
        }
        val pkg = focusedPackage() ?: return false
        if (!playingPkgs.containsKey(pkg) && !sessionPkgs.containsKey(pkg)) return false
        // 只要该应用有媒体会话即算（不再要求全屏/半屏，HDR 内容为准）
        // ★ 关键闸门：必须拿到「HDR 内容」证据，普通 SDR 视频一律不提亮。
        // 证据来自 SurfaceFlinger：图层携带 HDR 元数据时 SF 才认为在放 HDR
        // （与 HWC 是否支持 HDR 无关）。transact(21012) 就是 ColorOS 自己用的那条。
        val hdr = sfLayerHasHdrMetadata()
        if (hdr == true) return true
        logEvery("sdrsafe", 5000L) {
            "$TAG: fullscreen media without HDR content -> no boost (SDR safe, hdrLayer=" + hdr + ")"
        }
        return false
    }

    // ── G. HDR 内容证据：SurfaceFlinger 图层元数据 ──────────
    //
    // 实测：HDR10 片源播放时，SF 图层 dump 里会出现
    //     hdr metadata types=3        （3 = SMPTE2086 | CTA861_3，HDR10 静态元数据）
    // 而 SDR 片源全部是 types=0。这是与播放器实现无关、且不依赖 HWC 支持 HDR 的硬证据。
    // （ColorOS 的 transact(21012) 不行：它要求真正进入 HDR 合成，本机 HWC 不支持，恒 0。）
    //
    // 成本控制：只在「全屏 + 媒体会话」成立时才查；结果缓存 2s；
    // 用管道并发读取并**命中即停**，避免 237KB 全量解析和管道阻塞。
    @Volatile private var hdrMetaCache: Boolean? = null
    @Volatile private var hdrMetaAt = 0L

    private fun sfLayerHasHdrMetadata(): Boolean? {
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = hdrMetaCache
        if (cached != null && now - hdrMetaAt < 2000L) return cached
        val v = querySfLayerHdr()
        if (v != null) {
            hdrMetaCache = v
            hdrMetaAt = now
        }
        return v ?: cached
    }

    private val HDR_META_RE = Regex("hdr metadata types=([0-9]+)")

    private fun querySfLayerHdr(): Boolean? = try {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "SurfaceFlinger") as? android.os.IBinder ?: return null
        val pipe = android.os.ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]
        val hit = java.util.concurrent.atomic.AtomicBoolean(false)
        val reader = Thread {
            try {
                java.io.FileInputStream(readFd.fileDescriptor).bufferedReader().use { br ->
                    while (true) {
                        val line = br.readLine() ?: break
                        val m = HDR_META_RE.find(line)
                        if (m != null) {
                            val n = m.groupValues[1].toIntOrNull() ?: 0
                            if (n > 0) {
                                hit.set(true)
                                break // 命中即停，不等全量 dump
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    readFd.close()
                } catch (_: Throwable) {
                }
            }
        }.apply { isDaemon = true }
        reader.start()
        try {
            binder.dump(writeFd.fileDescriptor, arrayOf())
        } catch (_: Throwable) {
            // 读端提前关闭会抛 EPIPE，属预期
        } finally {
            try {
                writeFd.close()
            } catch (_: Throwable) {
            }
        }
        reader.join(1500L)
        hit.get()
    } catch (_: Throwable) {
        null
    }

    /** 事件驱动：一旦有媒体会话/窗口变化，立刻重算一次，不等下一轮轮询 */
    @Volatile private var recheckAt = 0L
    private fun recheckNow() {
        recheckAt = android.os.SystemClock.elapsedRealtime()
        hdrMetaCache = null // 失效缓存，强制重查
    }

    /** 前台窗口是否全屏/沉浸（用于把"音乐 App 前台播放"排除掉） */
    private fun focusedWindowFullscreen(): Boolean = try {
        val w = wms ?: return false
        val root = XposedHelpers.getObjectField(w, "mRoot")
        val dc = XposedHelpers.callMethod(root, "getTopFocusedDisplayContent")
        val win = XposedHelpers.getObjectField(dc, "mCurrentFocus") ?: return false
        val attrs = XposedHelpers.getObjectField(win, "mAttrs")
        val flags = try {
            XposedHelpers.getIntField(attrs, "flags")
        } catch (_: Throwable) {
            0
        }
        if ((flags and 0x400) != 0) return true // FLAG_FULLSCREEN
        // 退化判据：窗口高度接近屏幕高度
        val frames = try {
            XposedHelpers.callMethod(win, "getWindowFrames")
        } catch (_: Throwable) {
            null
        } ?: return false
        val frame = try {
            XposedHelpers.getObjectField(frames, "mFrame")
        } catch (_: Throwable) {
            null
        } ?: return false
        val h = try {
            XposedHelpers.getIntField(frame, "bottom") - XposedHelpers.getIntField(frame, "top")
        } catch (_: Throwable) {
            0
        }
        val dh = try {
            val info = XposedHelpers.callMethod(dc, "getDisplayInfo")
            XposedHelpers.getIntField(info, "logicalHeight")
        } catch (_: Throwable) {
            0
        }
        h > 0 && dh > 0 && h >= dh * 0.95f
    } catch (_: Throwable) {
        false
    }

    private fun focusedPackage(): String? = try {
        val w = wms ?: return null
        val root = XposedHelpers.getObjectField(w, "mRoot")
        val dc = XposedHelpers.callMethod(root, "getTopFocusedDisplayContent")
        val win = XposedHelpers.getObjectField(dc, "mCurrentFocus") ?: return null
        val attrs = XposedHelpers.getObjectField(win, "mAttrs")
        XposedHelpers.getObjectField(attrs, "packageName") as? String
    } catch (_: Throwable) {
        null
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
                        if (vis == 0) recheckNow()
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
                    // 三条信号：① 焦点窗口 colorMode ② 视频播放中 ③ 兜底（relayout 集合 / SF）
                    val focus = focusedWindowHdr()
                    val video = videoPlaying()
                    val raw = when {
                        focus == true -> true
                        video -> true
                        focus == null -> hdrWindows.isNotEmpty() || sfHdrPlaying()
                        else -> false
                    }
                    // 退出迟滞（仅类原生）：HDR 图片/瞬时窗口会让状态在几百毫秒内抖动，
                    // 直连面板时表现为「闪一下又回去」。熄灭前要求连续缺失 HDR_EXIT_HOLD_MS。
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    val want = if (!raw && hdrOn && aospMode) {
                        if (hdrOffSince == 0L) hdrOffSince = nowMs
                        (nowMs - hdrOffSince) < HDR_EXIT_HOLD_MS
                    } else {
                        if (raw) hdrOffSince = 0L
                        raw
                    }
                    if (want != hdrOn) {
                        hdrOn = want
                        if (!want) gateUntil = android.os.SystemClock.elapsedRealtime() + 5000L
                        val d = hdrEnhance
                        if (d != null) callPriv(d, "checkIsPlayingHDR")
                        // 原生驱动会自己 sendUpdatePowerState；没有驱动时必须自己点火
                        requestPowerUpdate()
                        // 类原生：主动把面板亮度推到顶（并保留 SDR 白点），否则永远等不到亮度变化事件
                        pushAospBrightness(want)
                        // 类原生：强制进/出 HBM（等效于「改 displayconfig + 删 jar 门禁」那条路）
                        applyHbm(want)
                        try {
                            val h = dpcHandler
                            if (h != null) h.postDelayed({ requestPowerUpdate() }, 700L)
                        } catch (_: Throwable) {
                        }
                        try {
                            val h = dpcHandler
                            if (h != null) h.postDelayed({ pushAospBrightness(hdrOn) }, 700L)
                        } catch (_: Throwable) {
                        }
                        val why = when {
                            focus == true -> "focus=true"
                            video -> "video playing"
                            focus == null -> "fallback"
                            else -> "focus=false"
                        }
                        XposedBridge.log(
                            "$TAG: HDR -> " + want + "(" + why + ") [" + driverState(d) + "]"
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
