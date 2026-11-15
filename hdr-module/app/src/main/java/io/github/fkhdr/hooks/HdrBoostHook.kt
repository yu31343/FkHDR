package io.github.fkhdr.hooks

import io.github.fkhdr.xp.Xp
import io.github.fkhdr.xp.XpHook
import io.github.fkhdr.xp.XpHook.MethodHookParam
import io.github.fkhdr.xp.XpHelpers

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
 * C. 比率开关（仅 ColorOS 分支）：LocalDisplayDeviceExtImpl#isSupportSdrToHdrRatio() 恒 true，
 *    让系统愿意上报 hdrSdrRatio；AOSP 分支不碰这个闸门。
 *
 * ── 关于「HDR 亮度比例」────────────────────────────────────
 * 有效输入按 AOSP 公式计算 ratio = max(1, 面板 nit / SDR 白点 nit)，且不人为封顶；
 * 厂商亮度通路由系统决定，模块绝不写 mSupportUltraHDR / mForceSurfaceControl。
 * 无效输入保留 Float.NaN —— AOSP 用 NaN 表示「比值不可用」，不得伪装成 1.0；
 * 退出 HDR 时只清理残留的有限旧比值，不主动写入 1.0。
 *
 * ── 诊断 ────────────────────────────────────────────────────
 * 所有关键节点写 Xp.log（LSPosed 管理器 → 日志 可见），前缀 FkHDR:HdrFix。
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
    /** 只剩窗口信号、SF 已确认没有 HDR 图层时，最多还信它多久 */
    private const val HDR_FOCUS_ONLY_HOLD_MS = 3000L

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
    /** 开关关闭期间构造的原生驱动：只记录实例，开关打开后由驱动回调补跑 hardening。 */
    @Volatile private var driverHardenPending: Any? = null
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
            if (s.isNotEmpty()) Xp.log(s)
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
        XpHelpers.findClass(name, cl)
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
                Xp.log("$TAG: OPPO path all missing on coloros -> enable AOSP fallback")
            }
        }

        // ── 平台专属：AOSP ─────────────────────────────────
        // 仅当 ColorOS 专属路径不可用、且确实是 AOSP 显示栈时才启用
        val forceAosp = prop2(PROP_AOSP_FORCE, PROP_AOSP_FORCE_OLD, "0") == "1"
        aospMode = platform == PLAT_AOSP || forceAosp || oppoPathDead
        if (aospMode) {
            // GENERIC 兜底永远安装：启动期静态探测不能决定运行时是否真能走专项链路。
            // 专项链路只由 refreshSpecificMode() 依据 splineInstalled + ratioBypassInstalled
            // + ratioBypassVerified 动态判定；watcher 始终运行以便失效时自愈/降级。
            // 绝不写 mSupportUltraHDR / mForceSurfaceControl（会把亮度切到不支持的通路）。
            steps.append(" bypass=").append(hookAospRatioBypass(classLoader))
            steps.append(" aosp[").append(hookAospLocalDisplay(classLoader)).append("]")
            steps.append(" hbm=").append(hookHighBrightnessModeController(classLoader))
            steps.append(" probe=").append(hookRatioProbeOnly(classLoader))
            // 应用侧「HDR SDR 调光支持」= !Float.isNaN(DisplayInfo.hdrSdrRatio)。
            // 空闲期系统把比值写成 NaN，于是应用永远看到「不支持」，也永远不会
            // registerHdrSdrRatioChangedListener —— 功能在应用眼里就是坏的。
            // 在 DisplayDeviceInfo 重建前按 AOSP 自己的下界语义（比值恒 >= 1）把 NaN 归一成 1.0：
            // 1.0 = 当前 HDR 与 SDR 同亮度；HDR 一开就被真实比值覆盖。
            steps.append(" ratioAvail=").append(hookRatioAvailability(classLoader))
        }
        // 注意：hookAospRatioGate(DisplayDeviceConfig.hasSdrToHdrRatioSpline) 已删除。
        // 实机证明它被 ART 内联、完全不生效，且会在 HDR 期间每帧放行 updateDeviceInfoLocked。

        // ── 通用：三类平台都挂（都是 AOSP 基础类，必然存在）──
        // DisplayInfo 伪装也放通用段：patchDisplayInfo 自带守卫（已含 11/12 就原样返回），
        // 真 HDR 机型不会被改动；缺 HDR 色彩模式的机型（本机）才会被补上。
        steps.append(" forge=").append(hookDisplayInfoForgery(classLoader))
        // 系统内部仍以 NaN 表示「比值不可用」，只在信息重建前做应用可见的可用性归一（hookRatioAvailability）。
        steps.append(" dpc=").append(hookDisplayPowerController(classLoader))
        // watcher 无条件运行：GENERIC 状态机、专项链路自愈与降级都在里面。
        steps.append(" media=").append(hookMediaSessions(classLoader))
        steps.append(" wms=").append(hookWindowManager(classLoader))
        startWatcher()
        Xp.log("$TAG: init done [" + steps + "] factor=" + factor())
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
    @Volatile private var enabledAt = -5000L

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
            val model = XpHelpers.getObjectField(mgr, "mBrightnessModel")
            // nit -> brightness
            val sdrB = XpHelpers.callMethod(model, "getValueByCurve", "nits", sdrNit) as? Float
            if (sdrB == null || sdrB <= 0f) return Float.MAX_VALUE
            // 面板曲线给出的 HDR brightness
            val hdrB = XpHelpers.callMethod(
                cfg, "getHdrBrightnessFromSdr", sdrB, Float.MAX_VALUE
            ) as? Float
            if (hdrB == null || hdrB <= 0f) return Float.MAX_VALUE
            // brightness -> nit，换算回 hdrSdrRatio 的同一量纲
            val hdrNit = XpHelpers.callMethod(model, "getValueByCurve", "backlights", hdrB) as? Float
                ?: return Float.MAX_VALUE
            val r = hdrNit / sdrNit
            if (r > 1f) r else Float.MAX_VALUE
        } catch (_: Throwable) {
            Float.MAX_VALUE
        }
    }

    /** 原生路径用的倍率：受面板曲线上限约束 */
    private fun nativeFactor(): Float = minOf(factor(), lastRatioCap)

    // 注意 Math.round(NaN) == 0，旧写法会把 NaN 打成 "0.0"，排查时会误判成「被写成 0」
    private fun f2(v: Float): String =
        if (v.isNaN()) "NaN" else (Math.round(v * 100f) / 100f).toString()

    // ── 工具：安全 hook + 安全读写字段 ───────────────────────
    /** 返回该名字实际挂上多少个方法（0 = 这个版本里没有该方法） */
    private fun hookAll(cls: Class<*>, name: String, hook: XpHook): Int = try {
        Xp.hookAllMethods(cls, name, hook).size
    } catch (t: Throwable) {
        logEvery("hook_" + name, 60000L) { "$TAG: hook " + name + " unavailable: " + t }
        0
    }

    private fun boolField(o: Any, n: String): String = try {
        XpHelpers.getBooleanField(o, n).toString()
    } catch (_: Throwable) {
        "?"
    }

    private fun intField(o: Any, n: String): String = try {
        XpHelpers.getIntField(o, n).toString()
    } catch (_: Throwable) {
        "?"
    }

    private fun floatField(o: Any, n: String): String = try {
        f2(XpHelpers.getFloatField(o, n))
    } catch (_: Throwable) {
        "?"
    }

    private fun hasMethod(cls: Class<*>, name: String): Boolean =
        cls.declaredMethods.any { it.name == name } ||
            (cls.superclass?.declaredMethods?.any { it.name == name } ?: false)

    // ── A1. 补 feature 配置 ─────────────────────────────────
    private fun hookFeatures(classLoader: ClassLoader): Int = try {
        val svc = XpHelpers.findClass(
            "com.android.server.content.OplusFeatureConfigManagerService", classLoader
        )
        val hook = object : XpHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!enabled()) return
                val name = param.args.getOrNull(0) as? String ?: return
                if (name in MISSING_FEATURES) param.result = true
            }
        }
        hookAll(svc, "hasFeature", hook) + hookAll(svc, "hasFeatureMap", hook)
    } catch (t: Throwable) {
        Xp.log("$TAG: feature service not found: " + t.message)
        0
    }

    // ── A2. 比率开关：让系统愿意算/上报 hdrSdrRatio ──────────
    private fun hookRatioSupport(classLoader: ClassLoader): Int = try {
        val ext = XpHelpers.findClass(CLS_LOCAL_DEVICE_EXT, classLoader)
        hookAll(ext, "isSupportSdrToHdrRatio", object : XpHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (enabled()) param.result = true
            }
        })
    } catch (t: Throwable) {
        Xp.log("$TAG: LocalDisplayDeviceExtImpl not found: " + t.message)
        0
    }

    // ── A0. 读取系统自己声明的最大 HDR/SDR 比值 ─────────────
    // DisplayManagerServiceExtImpl#getHighestHdrSdrRatio() 是 OPPO 对"这个面板最多能到几倍"的官方答案。
    // 用它当上限，比我们硬编码任何数字都更贴近"物理硬件可接受"。
    private fun hookSystemRatioMax(classLoader: ClassLoader): Boolean = try {
        val cls = XpHelpers.findClass(
            "com.android.server.display.DisplayManagerServiceExtImpl", classLoader
        )
        val n = hookAll(cls, "getHighestHdrSdrRatio", object : XpHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val v = (param.result as? Float) ?: return
                if (v > 1f && v != sysRatioMax) {
                    sysRatioMax = v
                    Xp.log("$TAG: system declared max hdr/sdr ratio = " + f2(v))
                }
            }
        })
        // 顺带主动问一次，避免等别人调用
        try {
            val inst = XpHelpers.callStaticMethod(
                cls, "getInstance"
            )
            if (inst != null) {
                val v = XpHelpers.callMethod(inst, "getHighestHdrSdrRatio") as? Float
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
        val binder = XpHelpers.findClass(
            CLS_DMS + '$' + "BinderService", classLoader
        )
        hookAll(binder, "getDisplayInfo", object : XpHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!enabled()) return
                patchDisplayInfo(param.result ?: return)
            }
        })
    } catch (t: Throwable) {
        Xp.log("$TAG: BinderService not found: " + t.message)
        0
    }

    private fun patchDisplayInfo(info: Any) {
        try {
            val cur = XpHelpers.getObjectField(info, "supportedColorModes") as? IntArray
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
            XpHelpers.setObjectField(info, "supportedColorModes", out)
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
        val hook = object : XpHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!enabled()) return
                val name = param.args.getOrNull(0) as? String ?: return
                if (name in MISSING_FEATURES) param.result = true
            }
        }
        try {
            val cls = XpHelpers.findClass(CLS_FEATURE_MGR, classLoader)
            n += hookAll(cls, "hasFeature", hook)
        } catch (t: Throwable) {
            logEvery("featmgr", 60000L) { "$TAG: feature singleton unavailable: " + t.message }
        }
        try {
            val cls = XpHelpers.findClass(CLS_FEATURE_CACHE, classLoader)
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
        val cls = XpHelpers.findClass(CLS_FEATURE_MANAGER, classLoader)
        val hook = object : XpHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!enabled()) return
                val self = param.thisObject
                try {
                    if (!XpHelpers.getBooleanField(self, "mEnhanceHDRBrightnessSupport")) {
                        XpHelpers.setBooleanField(self, "mEnhanceHDRBrightnessSupport", true)
                        Xp.log("$TAG: forced mEnhanceHDRBrightnessSupport=true")
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
        Xp.log("$TAG: OplusDisplayFeatureManager not found: " + t.message)
        0
    }

    // ── B. 首选：驱动 ColorOS 自己的 HDR 提亮实现 ────────────
    private fun hookOplusHdrEnhance(classLoader: ClassLoader): Boolean {
        return try {
            val cls = XpHelpers.findClass(CLS_HDR_ENHANCE, classLoader)
            Xp.hookAllConstructors(cls, object : XpHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val self = param.thisObject
                    // 实例始终记录，watcher 在开关打开后仍要用它判断 HDR 状态；
                    // 但开关关闭时不写任何厂商字段，hardening 推迟到开关打开后的驱动回调。
                    hdrEnhance = self
                    if (enabled()) {
                        Xp.log("$TAG: driver ready [" + hardenDriver(self) + "]")
                    } else {
                        driverHardenPending = self
                        Xp.log("$TAG: driver captured while disabled; hardening deferred")
                    }
                }
            })

            // 保住 SDR 白点：ColorOS 的 setNit() 在 mHasEdrProcessed==false 时会把
            // sdrNit 一起改掉，先置 hasEdrProcessed 系统才能算出真实比率。
            val nPost = hookAll(cls, "postProcessing", object : XpHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled()) return
                    hardenDriverIfPending(param.thisObject)
                    val builder = param.args.getOrNull(0) ?: return
                    if (!hdrOn) return
                    try {
                        XpHelpers.callMethod(builder, "setHasEdrProcessed")
                    } catch (_: Throwable) {
                    }
                    // C17 新增了按包名取 mEnhanceFactor 的白名单逻辑，
                    // 每次后处理前都重新压一遍，保证不受任何应用切换影响。
                    val self = param.thisObject
                    try {
                        XpHelpers.setBooleanField(self, "mEnterWhiteApp", true)
                        XpHelpers.setFloatField(self, "mEnhanceFactor", nativeFactor())
                        XpHelpers.setBooleanField(self, "mNeedToEnhanceBrightness", true)
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
            val nFactor = hookAll(cls, "getEnhanceFactorForPackage", object : XpHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (enabled()) param.result = factor()
                }
            })

            // onAppExit 会无条件 mEnterWhiteApp=false + exitEnhanceBrightness()，
            // HDR 期间任何一次应用切换都会把提亮掐掉 —— 直接短路掉。
            val nExit = hookAll(cls, "onAppExit", object : XpHook() {
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
            val nCheck = hookAll(cls, "checkIsPlayingHDR", object : XpHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!enabled()) return
                    val self = param.thisObject
                    hardenDriverIfPending(self)
                    try {
                        XpHelpers.setBooleanField(self, "mEnterWhiteApp", hdrOn)
                        XpHelpers.setFloatField(self, "mEnhanceFactor", nativeFactor())
                        if (hdrOn) callPriv(self, "enterEnhanceBrightness")
                        else callPriv(self, "exitEnhanceBrightness")
                    } catch (t: Throwable) {
                        logEvery("checkfail", 3000L) {
                            "$TAG: force enter/exit failed: " + t
                        }
                    }
                }
            })

            Xp.log(
                "$TAG: native driver hooked (post=" + nPost + " check=" + nCheck +
                    " enter=" + hasMethod(cls, "enterEnhanceBrightness") +
                    " exit=" + hasMethod(cls, "exitEnhanceBrightness") +
                    " factorHook=" + nFactor + " exitHook=" + nExit + ")"
            )
            nPost > 0 || nCheck > 0
        } catch (t: Throwable) {
            Xp.log("$TAG: native driver unavailable: " + t.message)
            false
        }
    }

    /**
     * 兜住 ColorOS 自己的开关：新版系统上 OplusFeatureConfigManager 的查询链可能变了，
     * mEnhanceHDRBrightnessSupport 会恒 false —— postProcessing 就整个静默失效。
     * 仅在开关打开且该字段为 false 时掰成 true 并补跑 init()。
     */
    private fun hardenDriver(selfOrNull: Any?): String {
        val self = selfOrNull ?: return "no-driver"
        if (!enabled()) return "disabled(no write)"
        val sb = StringBuilder()
        val support = try {
            XpHelpers.getBooleanField(self, "mEnhanceHDRBrightnessSupport")
        } catch (_: Throwable) {
            true
        }
        sb.append("support=").append(support)
        if (!support) {
            try {
                XpHelpers.setBooleanField(self, "mEnhanceHDRBrightnessSupport", true)
                sb.append("->true")
                try {
                    XpHelpers.callMethod(self, "init")
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

    /** 开关由关变开后，在真正运行的驱动回调里补一次 hardened 检查（不再无条件写字段）。 */
    private fun hardenDriverIfPending(selfOrNull: Any?) {
        val self = selfOrNull ?: return
        val pending = driverHardenPending ?: return
        if (!enabled() || pending !== self) return
        driverHardenPending = null
        Xp.log("$TAG: driver ready (deferred) [" + hardenDriver(pending) + "]")
    }

    /** 退出时把厂商驱动的提亮标志位放下；HDR 状态下不做任何写入。 */
    private fun clearDriverEnhance(selfOrNull: Any?) {
        val self = selfOrNull ?: return
        try {
            XpHelpers.setBooleanField(self, "mEnterWhiteApp", false)
            XpHelpers.setBooleanField(self, "mNeedToEnhanceBrightness", false)
        } catch (_: Throwable) {
        }
    }

    private fun callPriv(selfOrNull: Any?, name: String) {
        val self = selfOrNull ?: return
        try {
            XpHelpers.callMethod(self, name)
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
        val mgr = XpHelpers.findClass(CLS_POST_MANAGER, classLoader)
        var n = hookAll(mgr, "convertNitToBrightness", object : XpHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!enabled() || !hdrOn) return
                val builder = param.args.getOrNull(0) ?: return
                boost(param.thisObject, builder)
            }
        })
        // 诊断：证明「后处理链到底有没有在跑」。只有 convertNitToBrightness 能证明兜底可挂载，
        // 诊断钩子不计入返回值，否则 ColorOS 原生驱动失配时永远进不了 AOSP 兜底。
        hookAll(mgr, "postProcessing", object : XpHook() {
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
        Xp.log("$TAG: post-processing manager not found: " + t.message)
        0
    }

    // ── E. 主动触发亮度重算 ─────────────────────────────────
    //
    // 兜底路径是「被动」的：只有当系统重算亮度时才会经过 convertNitToBrightness。
    // 光切窗口 colorMode 不会必然触发一次重算，所以 HDR 开关后必须自己点一次火。
    private fun hookDisplayPowerController(classLoader: ClassLoader): Boolean = try {
        val cls = XpHelpers.findClass("com.android.server.display.DisplayPowerController", classLoader)
        Xp.hookAllConstructors(cls, object : XpHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val self = param.thisObject
                dpc = self
                try {
                    dpcHandler = XpHelpers.getObjectField(self, "mHandler") as? android.os.Handler
                } catch (_: Throwable) {
                }
                try {
                    val cfg = XpHelpers.getObjectField(self, "mDisplayDeviceConfig")
                    displayConfig = cfg
                    hasRatioSpline = XpHelpers.callMethod(
                        cfg, "hasSdrToHdrRatioSpline"
                    ) as? Boolean ?: false
                    Xp.log(
                        "$TAG: panel config ready [sdrToHdrSpline=" + hasRatioSpline + "]"
                    )
                    if (aospMode && enabled()) {
                        installSpecificSpline()
                        updateSpecificMode()
                    }
                    Xp.log(
                        "$TAG: chain = " + when {
                            specOn() -> "SPECIFIC(系统自算, 运行时健康)"
                            else -> "GENERIC(模块代劳, 兜底已安装)"
                        }
                    )
                } catch (t: Throwable) {
                    logEvery("cfg", 60000L) { "$TAG: display config unavailable: " + t.message }
                }
            }
        })
        true
    } catch (t: Throwable) {
        Xp.log("$TAG: DisplayPowerController not found: " + t.message)
        false
    }

    /** 让 DisplayPowerController 在自己的 handler 线程上重算一次亮度 */
    private fun requestPowerUpdate() {
        if (!enabled()) return
        val d = dpc
        if (d == null) {
            logEvery("nodpc", 5000L) { "$TAG: no DPC instance, cannot request power update" }
            return
        }
        val run = Runnable {
            try {
                XpHelpers.callMethod(d, "updatePowerState")
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

    private fun num(objOrNull: Any?, getter: String): Float {
        val o = objOrNull ?: return Float.NaN
        return try {
            (XpHelpers.callMethod(o, getter) as? Float) ?: Float.NaN
        } catch (_: Throwable) {
            Float.NaN
        }
    }

    /** 面板 nit 硬件上限（带缓存） */
    private fun maxPanelNit(mgr: Any): Float {
        val cached = panelMaxNit
        if (cached > 0f) return cached
        var v = Float.NaN
        try {
            val model = XpHelpers.getObjectField(mgr, "mBrightnessModel")
            v = num(model, "getMaxPanelNit")
            if (v.isNaN() || v <= 0f) v = num(model, "getTotalBrightness")
        } catch (_: Throwable) {
        }
        if (!v.isNaN() && v > 0f) panelMaxNit = v
        return v
    }

    /** 通用兜底是否允许。设 0 则只走 ColorOS 原生驱动（用于验证原生链路）。 */
    private fun fallbackEnabled(): Boolean = prop2(PROP_FALLBACK, PROP_FALLBACK_OLD, "1") != "0"

    private fun boost(mgrOrNull: Any?, builder: Any): Boolean {
        val mgr = mgrOrNull ?: return false
        return try {
            // 专项链路下系统自己会算，兜底必须完全静默
            if (specOn()) {
                logEvery("boost_skip_spec", 10000L) { "$TAG: boost skipped (specific chain)" }
                return false
            }
            if (!fallbackEnabled()) {
                logEvery("fb_off", 5000L) { "$TAG: fallback disabled by " + PROP_FALLBACK }
                return false
            }
            val base = num(builder, "getNit")
            if (base.isNaN() || base <= 0f) return false
            val sdr = num(builder, "getSdrNit")
            // 压暗对齐：厂商原生驱动会把 nit 直接顶到面板上限（本站实测 99.4 -> 846.09 = 8.51x），
            // 但 SurfaceFlinger 只按自己的 hdr/sdr ratio（本站 5.0）压暗 SDR 层。
            // 两者不一致时 SDR 会按 (8.51/5.0) 偏亮，观感就是「提亮了但没压暗」。
            // 这里把 nit 收口到 sdrNit * ratioCap()，让模块的提亮倍数与 SF 的压暗倍数同一个数，
            // 同时让 App 里的「比值封顶上限」在这条路径上也实时可控。
            if (!sdr.isNaN() && sdr > 0f) {
                val cap = ratioCap()
                val cur = base / sdr
                if (cap >= 1f && cur > cap + 0.01f) {
                    val clamped = sdr * cap
                    XpHelpers.callMethod(builder, "setNit", clamped)
                    logEvery("clamphalt", 2000L) {
                        "$TAG: hdr nit clamped " + f2(base) + " -> " + f2(clamped) +
                            " (ratio " + f2(cur) + " -> cap " + f2(cap) + ")"
                    }
                    return true
                }
            }
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
            if (cap >= 1e6f) {
                // 面板没有 sdrHdrRatioMap：拿不到物理上限，退回 App 设定的 cap，
                // 绝不能用 factor()（默认 100）当乘数，否则 target 恒等于面板最大 = 硬推。
                logEvery("nocapcurve", 10000L) {
                    "$TAG: panel ratio curve unavailable, using app cap=" + f2(ratioCap())
                }
            }
            val curve = if (cap >= 1e6f) ratioCap() else cap
            val target = minOf(maxNit, base * curve)
            if (target <= base * RATIO_DONE) {
                logEvery("noboost", 5000L) {
                    "$TAG: already at panel max, skip (nit=" + f2(base) + " max=" + maxNit + ")"
                }
                return false
            }
            XpHelpers.callMethod(builder, "setHasEdrProcessed")
            XpHelpers.callMethod(builder, "setSdrNit", base)
            XpHelpers.callMethod(builder, "setNit", target)
            try {
                XpHelpers.callMethod(builder, "setReason", "FkHDR:HdrFix")
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
    // 始终安装 GENERIC 兜底：requestDisplayStateLocked / setDisplayBrightness 捕获、
    // 按精确 (float)->float 签名解析 nits 链、在 DisplayAdapter 的 SyncRoot 下发布比值。
    // 专项链路只是运行时优化：spline 与比值接管都安装并验证成功后才抑制 GENERIC 补偿。
    // 任何一步失败、曲线被系统换回、比值长时间无效，watcher 都会退回 GENERIC；
    // AOSP 分支不强制 isSupportSdrToHdrRatio()（ColorOS 分支的 OPPO Ext 私有钩子是另一套设计），
    // 绝不写 mSupportUltraHDR / mForceSurfaceControl。
    /** 内部类分隔符（避免 Kotlin 字符串模板把 $ 当插值） */
    private const val D = "$"

    @Volatile private var aospMode = false
    /** ColorOS 上 OPPO 通路全部失配（原生驱动 + 后处理都挂不上） */
    @Volatile private var oppoPathDead = false

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
        // 捕获 LocalDisplayDevice 实例与 requestDisplayStateLocked 亮度漏斗；
        // 同时从返回的 Runnable / 内部类动态解析亮度处理类。这里不再挂 OEM 比率闸门。
        var dev = 0
        try {
            val cls = XpHelpers.findClass(
                "com.android.server.display.LocalDisplayAdapter" + D + "LocalDisplayDevice", classLoader
            )
            dev = hookAll(cls, "requestDisplayStateLocked", object : XpHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    onAospRequestState(param)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    hookInnerDynamic(param.result)
                    if (!enabled()) return
                    aospDev = param.thisObject
                }
            })
            if (dev == 0) {
                // 退路：构造器
                Xp.hookAllConstructors(cls, object : XpHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!enabled()) return
                        aospDev = param.thisObject
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
    private val hookedInnerClasses = java.util.Collections.synchronizedSet(
        java.util.HashSet<Class<*>>()
    )

    private val brightnessHook = object : XpHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!aospMode || !enabled() || !hdrOn) return
            // ★ 关键守卫：曲线装上后亮度必须完全交给 DPC。
            //   这里是「第二条硬推路径」——它作用在 $1.setDisplayBrightness 上，
            //   即使 DPC 已经算出正确的 HDR 目标亮度，也会被这里改写成面板最大，
            //   于是 nits 恒 650、比值恒被 cap 钉死，且退出 HDR 后没人把亮度改回来。
            if (specOn()) {
                logEvery("specnoBoost", 5000L) {
                    "$TAG: setDisplayBrightness boost skipped (healthy specific chain)"
                }
                return
            }
            val args = param.args
            if (args.size < 2) return
            val sdr = args[1] as? Float ?: return
            if (sdr.isNaN() || sdr <= 0f) return
            // 最后一层兜底：曲线目标没有送达时才按面板上限抬亮度，量纲自适应：
            //   AOSP 归一化 0..1 → 1.0；ColorOS 绝对 0..2047 → mScreenBrightnessRangeMaximum。
            //   若上面已有曲线目标（cur 已高于 sdr），下面的 cur > sdr 判断会直接跳过。
            val cur = args[0] as? Float ?: return
            // onAospRequestState / pushAospBrightness 已算出曲线目标时不要再硬推到面板峰值。
            if (cur > sdr * 1.001f) return
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
        var total = 0
        for (c in brightnessCandidates(classLoader)) {
            total += hookInner(c)
        }
        if (total == 0) {
            logEvery("aospbright", 60000L) {
                "$TAG: setDisplayBrightness target not found by class scan; will hook dynamically on first Runnable"
            }
        }
        return total
    }

    private fun hookInner(cls: Class<*>): Int {
        if (!hookedInnerClasses.add(cls)) return 1
        if (!hasMethodAny(cls, "setDisplayBrightness")) return 0
        val n = hookAll(cls, "setDisplayBrightness", brightnessHook)
        if (n > 0) {
            innerCls = cls
            innerHooked = true
            Xp.log("$TAG: brightness hook installed on " + cls.name + " (" + n + " method)")
            hookHdrRatioProbe(cls)
        } else {
            hookedInnerClasses.remove(cls)
        }
        return n
    }

    /** requestDisplayStateLocked 返回的 Runnable 就是那个匿名类实例 —— 按运行时类动态挂 */
    private fun hookInnerDynamic(r: Any?) {
        if (r == null) return
        try {
            hookInner(r.javaClass)
        } catch (_: Throwable) {
        }
    }

    /**
     * requestDisplayStateLocked 的入参改写点。
     *
     * 形参：(int state, float brightnessState, float sdrBrightnessState[, DisplayOffloadSession])
     *   brightnessState    → 背光目标（归一化 0..1 或厂商绝对量纲，由运行时曲线决定）
     *   sdrBrightnessState → SDR 白点（保留用户当前亮度，不动）
     *
     * 专项链未验证时把 brightnessState 抬到 aospBoostTarget()：SDR nits × 上限曲线，
     * 再用同一组 cfg 方法换回亮度；曲线不可用或目标与入参量纲不一致时保持原值，
     * 不硬推 1.0，也不把绝对量纲设备压到接近全黑。
     */
    private fun onAospRequestState(param: MethodHookParam) {
        if (!aospMode || !enabled()) return
        val a = param.args
        if (a.size < 3) return
        val st = a[0] as? Int ?: return
        val b = a[1] as? Float ?: return
        val sdr = a[2] as? Float ?: return

        aospDev = param.thisObject
        aospState = st
        // args[2] 我们从不改写，它始终是系统给的 SDR 亮度，可以一直记录。
        // （之前只在 !hdrOn 时记录，导致 HDR 期间用户调亮度后，退出会跳回旧值。）
        if (sdr.isFinite() && sdr > 0.001f) {
            if (hdrOn && Math.abs(sdr - aospSdr) > 0.01f) {
                logEvery("sdrchg", 2000L) {
                    "$TAG: user sdr changed during HDR " + f2(aospSdr) + " -> " + f2(sdr)
                }
            }
            aospSdr = sdr
        }

        if (!aospFunnelSeen) {
            aospFunnelSeen = true
            // useSurfaceControlBrightness 决定 AOSP 会不会算比值：false 时 handleHdrSdrNitsChanged
            // 会把 ratio 直接置 NaN（只剩物理提亮）。这条日志能一眼判定类原生上到底有没有比值能力。
            var sc = "?"
            try {
                val ba = XpHelpers.getObjectField(param.thisObject, "mBacklightAdapter")
                val use = XpHelpers.getBooleanField(ba, "mUseSurfaceControlBrightness")
                val force = try {
                    XpHelpers.getBooleanField(ba, "mForceSurfaceControl")
                } catch (_: Throwable) {
                    false
                }
                sc = "use=" + use + " force=" + force
            } catch (_: Throwable) {
            }
            Xp.log(
                "$TAG: aosp funnel alive (state=" + st + " brightness=" + f2(b) +
                    " sdr=" + f2(sdr) + " useSurfaceControlBrightness=" + sc + ")"
            )
        }
        if (!hdrOn) return

        // 曲线已装上（含通用链路）时：亮度完全交给 DPC。
        // 直写 args[1] 是硬跳变，没有 ramp；DPC 自己算出来的目标会走 BrightnessRamp 渐变，丝滑得多。
        if (specOn()) {
            logEvery("specgate", 3000L) {
                var sc = "?"
                try {
                    val ba = XpHelpers.getObjectField(param.thisObject, "mBacklightAdapter")
                    sc = "use=" + XpHelpers.getBooleanField(ba, "mUseSurfaceControlBrightness") +
                        " force=" + XpHelpers.getBooleanField(ba, "mForceSurfaceControl")
                } catch (_: Throwable) {
                }
                "$TAG: specific gate [b=" + f2(b) + " sdr=" + f2(sdr) + " " + sc + "]"
            }
            return
        }

        val sdrEff = if (sdr.isFinite() && sdr > 0.001f) sdr else b
        // 与主动推送共用同一套曲线目标：SDR 白点不动、目标不超过面板上限。
        // 曲线不可用时「不提亮」而不是拉满 —— 拉满会让物理比值飙到几十（刺眼+发烫）。
        val target = aospBoostTarget(sdrEff)
        aospPeak = target
        if (target > sdrEff) {
            a[1] = target
            logEvery("aosppush", 2000L) {
                "$TAG: aosp push " + f2(sdrEff) + " -> " + f2(target)
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
        // 开关关掉后绝不能继续推亮度（这里是自己塞 callArgs[1]，不经过 before-hook 的 enabled 检查）
        if (!enabled()) return
        // 曲线已装上：亮度交给 DPC 自己算，避免硬跳变（无 ramp）
        if (specOn()) return
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
                "$TAG: aosp push skipped: no user sdr brightness yet (letting system recompute)"
            }
            // 没有可信的用户亮度时，交给 DPC 自己重算，别硬推一个可能为 0 的值
            requestPowerUpdate()
            return
        }
        // 与被动改写共用 aospBoostTarget()：不再把绝对量纲目标截断成 1.0，
        // 也不在曲线/量纲不可信时硬推亮度，避免 ColorOS 0..2047 设备被压到接近全黑。
        val target = if (boost) aospBoostTarget(sdr) else sdr
        // 状态必须取设备自己的 mState：随便传一个值会让 runnable 误以为要切换电源状态，
        // 在 DOZE/AOD 下可能把屏幕直接点亮。
        var st = aospState
        try {
            st = XpHelpers.getIntField(dev, "mState")
        } catch (_: Throwable) {
        }
        if (st != 2) {
            // 2 = Display.STATE_ON。非亮屏态不推亮度，交给之后的被动改写
            logEvery("aospstate", 5000L) {
                "$TAG: aosp push skipped: display not ON (state=" + st + ")"
            }
            return
        }
        val root = syncRootOf(dev)
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
                // 系统自己的 handleHdrSdrNitsChanged 都是 synchronized(getSyncRoot())。
                // 我们不持锁直接调 requestDisplayStateLocked，会和 PhotonicModulator 抢
                // mState/mBrightnessState，导致 run() 用旧值回写 -> 退出 HDR 时亮度不回落。
                val exec = {
                    val runnable = m.invoke(dev, *callArgs) as? Runnable
                    if (runnable == null) {
                        // 没有产生 Runnable = 系统认为没变化，说明上一次已经推到位
                        logEvery("aospnoop", 5000L) {
                            "$TAG: aosp push no-op (already " + f2(target) + ")"
                        }
                    } else {
                        runnable.run()
                        logEvery("aosppushok", 1000L) {
                            "$TAG: aosp push applied brightness=" + f2(target) +
                                " sdr=" + f2(sdr) + " state=" + st
                        }
                    }
                }
                if (root != null) {
                    synchronized(root) { exec() }
                } else {
                    logEvery("nosyncroot", 30000L) {
                        "$TAG: sync root unavailable, refusing to push brightness without lock"
                    }
                }
            } catch (t: Throwable) {
                logEvery("aosppushfail", 3000L) { "$TAG: aosp push failed: " + t }
            }
        }
        val h = dpcHandler
        if (h == null) {
            // 在 watcher 线程直接跑 updatePowerState/requestDisplayStateLocked 会破坏 DPC 状态
            logEvery("nohandler", 30000L) {
                "$TAG: DPC handler missing, refuse to push brightness off-thread"
            }
            return
        }
        try {
            h.post(task)
        } catch (t: Throwable) {
            logEvery("postfail", 30000L) { "$TAG: post to DPC handler failed: " + t }
        }
    }

    /** 绝对量纲下的面板最大亮度（ColorOS 等非归一化实现） */
    private fun absMaxBrightness(): Float {
        val d = dpc ?: return 1.0f
        for (f in arrayOf("mScreenBrightnessRangeMaximum", "mScreenBrightnessNormalMaximum")) {
            try {
                val v = XpHelpers.getFloatField(d, f)
                if (v > 1.5f) return v
            } catch (_: Throwable) {
            }
        }
        return 1.0f
    }

    // ── AOSP 比值：GENERIC 兜底自己算 ───────────────────────
    //
    // 历史上 hook DisplayDeviceConfig.hasSdrToHdrRatioSpline() 返回 true 并不能让
    // handleHdrSdrNitsChanged 被调用（调用点可能被 ART 内联）。因此 GENERIC 兜底
    // 直接从 setDisplayBrightness 的入参解析 nits 链，按 AOSP 公式算比值：
    //     brightnessToBacklight(b) → backlightToNits(backlight) → max(1, nits/sdrNits)
    // 方法按精确 (float)->float 签名在 inner / LocalDisplayDevice / DisplayDeviceConfig
    // 上动态解析，兼容两组常见命名，不硬编码匿名类序号。
    // 专项链健康时 specOn() 会短路本函数，避免与系统自算双写。
    @Volatile private var ratioResolved = false
    @Volatile private var lastForcedRatio = Float.NaN
    @Volatile private var lastForceAt = 0L
    private const val FORCE_MIN_INTERVAL_MS = 500L
    @Volatile private var lastRatioResolveAt = 0L
    @Volatile private var stateGen = 0L

    private val trailHandler: android.os.Handler by lazy {
        android.os.Handler(android.os.Looper.getMainLooper())
    }

    private data class PendingRatio(val dev: Any, val ratio: Float)
    private val pendingRatio = java.util.concurrent.atomic.AtomicReference<PendingRatio?>(null)

    private val trailRunnable: Runnable = Runnable {
        val p = pendingRatio.getAndSet(null)
        if (p != null) {
            if (enabled() && (hdrOn || android.os.SystemClock.elapsedRealtime() < gateUntil)) {
                if (forceHdrSdrRatio(p.dev, p.ratio)) {
                    lastForceAt = android.os.SystemClock.elapsedRealtime()
                    lastForcedRatio = p.ratio
                    logEvery("trail", 3000L) { "$TAG: ratio trailing write " + f2(p.ratio) }
                } else {
                    pendingRatio.set(p)
                    trailHandler.postDelayed(trailRunnable, FORCE_MIN_INTERVAL_MS)
                }
            }
        }
    }

    private fun pushRatioDirect(innerOrNull: Any?, args: Array<Any?>) {
        val inner = innerOrNull ?: return
        if (!aospMode || !enabled() || specOn()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!hdrOn && now >= gateUntil) return
        try {
            val dev = localDeviceOf(inner)
            if (dev == null) {
                logEvery("rationodev", 30000L) { "$TAG: ratio direct skipped: LocalDisplayDevice unresolved" }
                return
            }
            if (findFieldUp(dev.javaClass, "mCurrentHdrSdrRatio") == null) {
                logEvery("rationofield", 30000L) { "$TAG: ratio direct skipped: mCurrentHdrSdrRatio missing" }
                return
            }
            val cfg = displayConfig
            val b2b = floatUnary(inner, arrayOf("brightnessToBacklight"))
                ?: floatUnary(dev, arrayOf("brightnessToBacklight"))
                ?: cfg?.let { floatUnary(it, arrayOf("getBacklightFromBrightness")) }
            val b2n = floatUnary(inner, arrayOf("backlightToNits"))
                ?: floatUnary(dev, arrayOf("backlightToNits"))
                ?: cfg?.let { floatUnary(it, arrayOf("getNitsFromBacklight")) }
            if (b2b == null || b2n == null) {
                if (!ratioResolved && now - lastRatioResolveAt >= 3000L) {
                    lastRatioResolveAt = now
                    logEvery("rationotready", 30000L) {
                        "$TAG: ratio direct NOT ready on " + inner.javaClass.name +
                            " (b2b=" + (b2b != null) + " b2n=" + (b2n != null) + ")"
                    }
                }
                ratioResolved = false
                return
            }
            if (!ratioResolved) {
                ratioResolved = true
                Xp.log("$TAG: ratio direct ready (float->float methods resolved by signature)")
            }
            val b = args.getOrNull(0) as? Float ?: return
            val sdr = args.getOrNull(1) as? Float ?: return
            if (b.isNaN() || sdr.isNaN()) return
            val backlight = b2b.first.invoke(b2b.second, b) as? Float ?: return
            val sdrBacklight = b2b.first.invoke(b2b.second, sdr) as? Float ?: return
            val nits = b2n.first.invoke(b2n.second, backlight) as? Float ?: return
            val sdrNits = b2n.first.invoke(b2n.second, sdrBacklight) as? Float ?: return
            val ratio = if (nits != -1f && sdrNits != -1f && nits.isFinite() &&
                sdrNits.isFinite() && sdrNits > 0f
            ) {
                cappedRatio(Math.max(1f, nits / sdrNits)).takeIf { it.isFinite() } ?: Float.NaN
            } else {
                Float.NaN
            }
            if (ratioEquals(currentHdrSdrRatio(dev), ratio)) return
            val last = lastForcedRatio
            val changed = when {
                ratio.isNaN() -> !last.isNaN()
                last.isNaN() -> true
                else -> Math.abs(ratio - last) > 0.05f
            }
            if (!changed) return
            val cooled = now - lastForceAt >= FORCE_MIN_INTERVAL_MS
            if (cooled) {
                if (forceHdrSdrRatio(dev, ratio)) {
                    lastForceAt = now
                    lastForcedRatio = ratio
                } else {
                    pendingRatio.set(PendingRatio(dev, ratio))
                    trailHandler.removeCallbacks(trailRunnable)
                    trailHandler.postDelayed(trailRunnable, FORCE_MIN_INTERVAL_MS)
                }
            } else {
                pendingRatio.set(PendingRatio(dev, ratio))
                trailHandler.removeCallbacks(trailRunnable)
                trailHandler.postDelayed(trailRunnable, FORCE_MIN_INTERVAL_MS)
            }
            logEvery("ratio2", 2000L) {
                "$TAG: ratio direct nits=" + f2(nits) + " sdrNits=" + f2(sdrNits) +
                    " -> ratio=" + f2(ratio) + " current=" + f2(currentHdrSdrRatio(dev))
            }
        } catch (t: Throwable) {
            logEvery("ratiofail", 10000L) { "$TAG: ratio direct failed: " + t }
        }
    }

    /**
     * GENERIC 兜底：把有效比值写进 LocalDisplayDevice，保留 AOSP 的 NaN 语义。
     * 只在拿到 DisplayAdapter 的 SyncRoot 且字段存在时写；拿不到锁就让系统原路径继续。
     */
    private fun forceHdrSdrRatio(dev: Any?, ratio: Float): Boolean {
        if (!enabled()) return false
        try {
            val d = dev ?: run {
                logEvery("rationodev", 10000L) { "$TAG: force ratio skipped: no LocalDisplayDevice" }
                return false
            }
            if (findFieldUp(d.javaClass, "mCurrentHdrSdrRatio") == null) return false
            val root = syncRootOf(d)
            if (root == null) {
                logEvery("norootforce", 30000L) { "$TAG: sync root unavailable, refusing unlocked ratio write" }
                return false
            }
            var ok = false
            val apply = Runnable {
                var step = "set"
                try {
                    XpHelpers.setFloatField(d, "mCurrentHdrSdrRatio", ratio)
                    step = "updateDeviceInfo"
                    XpHelpers.callMethod(d, "updateDeviceInfoLocked")
                    ok = true
                    // 诊断读回只用于日志：它的异常不能把已成功的写入判成失败，
                    // 否则 pushRatioDirect 会误排队重写同一个比值。
                    try {
                        val info = XpHelpers.getObjectField(d, "mInfo")
                        val back = XpHelpers.getFloatField(d, "mCurrentHdrSdrRatio")
                        val infoBack = try {
                            XpHelpers.getFloatField(info, "hdrSdrRatio")
                        } catch (_: Throwable) {
                            Float.NaN
                        }
                        logEvery("ratioforceok", 3000L) {
                            "$TAG: ratio forced " + f2(ratio) + " on " + d.javaClass.simpleName +
                                " (field=" + f2(back) + " info=" + f2(infoBack) + ")"
                        }
                    } catch (t: Throwable) {
                        logEvery("ratioforceprobe", 10000L) {
                            "$TAG: ratio readback probe failed (write already applied): " + t
                        }
                    }
                } catch (t: Throwable) {
                    logEvery("ratioforce", 10000L) { "$TAG: force ratio failed at " + step + ": " + t }
                }
            }
            synchronized(root) { apply.run() }
            return ok
        } catch (t: Throwable) {
            logEvery("ratioforce2", 10000L) { "$TAG: force ratio failed: " + t }
            return false
        }
    }

    private fun currentHdrSdrRatio(dev: Any?): Float = try {
        val d = dev ?: return Float.NaN
        XpHelpers.getFloatField(d, "mCurrentHdrSdrRatio")
    } catch (_: Throwable) {
        Float.NaN
    }

    private fun currentHdrSdrRatio(): Float = currentHdrSdrRatio(aospDev)

    // ── 面板 nits 曲线 + 上限曲线（与旧模块「改 displayconfig」等价，但走系统逻辑）──
    //
    // 旧模块的做法是改 display_id_*.xml 里的 sdrHdrRatioMap：
    //     <point><sdrNits>2.0</sdrNits><hdrRatio>5.0</hdrRatio></point>
    //     <point><sdrNits>650.0</sdrNits><hdrRatio>1.0</hdrRatio></point>
    // 于是 DisplayDeviceConfig.getHdrBrightnessFromSdr() 会给系统一个「HDR 目标亮度」，
    // DPC 用它算亮度、setDisplayBrightness 再把 nits/sdrNits 交给 handleHdrSdrNitsChanged，
    // 比值天然 ≤ 5，且 SDR 白点不动。
    //
    // 我们不改文件，改为运行时：
    //   ① 用面板自己的 screenBrightnessMap 取到 minNits/maxNits（不写死）
    //   ② 用「低亮度 -> cap，高亮度 -> 1.0」的线性曲线，与旧 XML 完全同形
    //   ③ 上限 cap 由 App 设置（默认 5.0，与旧模块一致）
    @Volatile private var panelMinNits = Float.NaN
    @Volatile private var panelMaxNits = Float.NaN
    @Volatile private var cfgMtdNits: java.lang.reflect.Method? = null
    @Volatile private var cfgMtdBacklight: java.lang.reflect.Method? = null
    @Volatile private var cfgMtdBacklightFromNits: java.lang.reflect.Method? = null
    @Volatile private var cfgMtdBrightnessFromBacklight: java.lang.reflect.Method? = null
    @Volatile private var cfgMethodsResolved = false
    @Volatile private var cfgMethodsFor: Any? = null

    private fun resolveCfgMethods(): Boolean {
        val cfg = displayConfig ?: return false
        if (cfgMethodsResolved && cfgMethodsFor === cfg) return panelMaxNits > 0f
        cfgMethodsResolved = false
        cfgMethodsFor = null
        try {
            cfgMtdNits = floatUnary(cfg, arrayOf("getNitsFromBacklight"))?.first
            cfgMtdBacklight = floatUnary(cfg, arrayOf("getBacklightFromBrightness"))?.first
            cfgMtdBacklightFromNits = floatUnary(cfg, arrayOf("getBacklightFromNits"))?.first
            cfgMtdBrightnessFromBacklight = floatUnary(cfg, arrayOf("getBrightnessFromBacklight"))?.first
            if (cfgMtdNits == null || cfgMtdBacklight == null ||
                cfgMtdBacklightFromNits == null || cfgMtdBrightnessFromBacklight == null
            ) return false
            val bl0 = cfgMtdBacklight!!.invoke(cfg, 0.0f) as Float
            val bl1 = cfgMtdBacklight!!.invoke(cfg, 1.0f) as Float
            val n0 = cfgMtdNits!!.invoke(cfg, bl0) as Float
            val n1 = cfgMtdNits!!.invoke(cfg, bl1) as Float
            if (n0 < 0f || !n0.isFinite() || n1 <= 0f || !n1.isFinite() || n1 <= n0) return false
            panelMinNits = n0
            panelMaxNits = n1
            cfgMethodsResolved = true
            cfgMethodsFor = cfg
            Xp.log(
                "$TAG: panel nits curve [min=" + f2(n0) + " max=" + f2(n1) +
                    "] ratioCap=" + f2(ratioCap())
            )
            return true
        } catch (t: Throwable) {
            logEvery("cfgmtd", 60000L) { "$TAG: panel nits curve unavailable: " + t }
            return false
        }
    }

    /**
     * 亮度量纲：归一化(0..1) 还是绝对量纲(0..max)。
     * 判据必须看「面板上限」而不是「当前亮度值」—— 用当前值判断时，
     * 绝对量纲下用户把亮度调到 1.5 以下就会被误判成归一化，然后 args[0]=1.0 导致接近全黑。
     */
    private fun peakBrightness(): Float =
        if (absMaxBrightness() > 1.5f) absMaxBrightness() else 1.0f

    private fun nitsOf(brightness: Float): Float = try {
        if (!resolveCfgMethods()) return Float.NaN
        val cfg = displayConfig ?: return Float.NaN
        val bl = cfgMtdBacklight!!.invoke(cfg, brightness) as Float
        cfgMtdNits!!.invoke(cfg, bl) as Float
    } catch (_: Throwable) {
        Float.NaN
    }

    private fun brightnessOfNits(nits: Float): Float = try {
        if (!resolveCfgMethods()) return Float.NaN
        val cfg = displayConfig ?: return Float.NaN
        val bl = cfgMtdBacklightFromNits!!.invoke(cfg, nits) as Float
        cfgMtdBrightnessFromBacklight!!.invoke(cfg, bl) as Float
    } catch (_: Throwable) {
        Float.NaN
    }

    /**
     * 被动改写与主动推送共用的 HDR 亮度目标，返回值与入参 sdr 同一量纲。
     *
     * AOSP 的 getBrightnessFromBacklight() 返回 framework 0..1；部分 OEM 把
     * requestDisplayStateLocked 的 brightness 改成「名义 nit」(0..2047)。两条链若量纲
     * 不一致，直接把 t 推回去会把屏幕压到接近全黑。因此只在 t 确实高于 sdr 且
     * clamp 后仍高于 sdr 时提亮，否则保持 sdr 让系统自己的亮度链继续工作。
     */
    private fun aospBoostTarget(sdr: Float): Float {
        val sdrNits = nitsOf(sdr)
        val ratio = splineRatio(sdrNits)
        val t = brightnessOfNits(sdrNits * ratio)
        if (!t.isFinite() || t <= 0f) {
            logEvery("curvefail", 5000L) {
                "$TAG: brightness curve unavailable, boost skipped (sdrNits=" + f2(sdrNits) + ")"
            }
            return sdr
        }
        val capped = Math.min(t, peakBrightness())
        if (capped <= sdr) {
            logEvery("curvescale", 5000L) {
                "$TAG: boost skipped (target=" + f2(capped) + " sdr=" + f2(sdr) +
                    "): no gain or brightness-domain mismatch"
            }
            return sdr
        }
        return capped
    }

    /**
     * App 可配的封顶上限（Settings.System:hdr_ratio_max，默认 5.0 = 旧模块的值）。
     *
     * 1 秒 TTL：App 里改完最多 1 秒就生效，不需要重启 system_server，也不需要重启应用；
     * 代价是 system_server 每秒一次本地 Settings 读（SettingsProvider 就在同一进程内）。
     */
    @Volatile private var capCache = Float.NaN
    @Volatile private var capAt = 0L

    private fun ratioCap(): Float {
        val now = android.os.SystemClock.elapsedRealtime()
        val c = capCache
        if (!c.isNaN() && now - capAt < 1000L) return c
        val v = try {
            io.github.fkhdr.Config.getRatioMax()
        } catch (_: Throwable) {
            5.0f
        }
        capCache = v
        capAt = now
        if (!c.isNaN() && Math.abs(v - c) > 0.01f) {
            Xp.log("$TAG: read app ratio cap " + f2(c) + " -> " + f2(v))
        }
        return v
    }

    /**
     * App 改「比值封顶上限」后立即生效。
     *
     * 上限有两个落点，都必须跟着变：
     *   ① 已装进 DisplayDeviceConfig 的曲线端点 y=[cap, 1.0] —— 不重装，系统仍按旧端点算 HDR 亮度；
     *   ② 当前 HDR 会话已经发布出去的比值 —— 只有 handleHdrSdrNitsChanged 下一次被调用才会重算，
     *      中间这段时间应用读到的还是旧值，所以这里主动按新上限收口一次。
     * 值本身由 ratioCap() 每秒从 Settings.System 重读，App 侧写完 1 秒内到这里。
     */
    @Volatile private var capApplied = Float.NaN

    private fun syncRatioCap() {
        if (!enabled()) return
        val cap = ratioCap()
        val prev = capApplied
        capApplied = cap
        if (prev.isNaN() || Math.abs(cap - prev) < 0.01f) return
        Xp.log("$TAG: app ratio cap " + f2(prev) + " -> " + f2(cap) + ", applying live")
        val used = splineCap
        if (splineInstalled && !used.isNaN() && Math.abs(cap - used) > 0.01f) {
            splineInstalled = false
            installSpecificSpline()
        }
        val cur = currentHdrSdrRatio()
        if (!cur.isNaN() && cur > cap + 0.01f && forceHdrSdrRatio(aospDev, cap)) {
            lastForcedRatio = cap
            lastForceAt = android.os.SystemClock.elapsedRealtime()
            Xp.log("$TAG: ratio capped " + f2(cur) + " -> " + f2(cap) + " (app cap)")
        }
    }

    /**
     * 模块发布出去的比值一律受 App 上限约束。
     *
     * AOSP 原版只管 nits 比值，上限由 DisplayDeviceConfig 的曲线承担；这里模块代劳了比值
     * 发布，就得自己按 cap 收口，否则把滑块拉到 2.0 之后的当前 HDR 会话仍会报 4.37。
     * NaN（不可用）与 1.0（AOSP 下界）原样放行。
     */
    private fun cappedRatio(raw: Float): Float {
        if (raw.isNaN() || raw <= 1f) return raw
        val cap = ratioCap()
        return if (cap in 1f..raw) cap else raw
    }

    /**
     * 与旧 XML 完全同形的上限曲线：
     *   sdrNits <= 面板最小 nits  -> cap
     *   sdrNits >= 面板最大 nits  -> 1.0
     *   中间线性插值
     */
    private fun splineRatio(sdrNits: Float): Float {
        val cap = ratioCap()
        if (!resolveCfgMethods() || sdrNits <= 0f || sdrNits.isNaN()) return cap
        val span = panelMaxNits - panelMinNits
        if (span <= 0f) return cap
        val t = ((sdrNits - panelMinNits) / span).coerceIn(0f, 1f)
        return cap + (1f - cap) * t
    }

    // ══ 专项链路（运行时验证通过后让系统自算；GENERIC 始终保留）══
    //
    // 旧模块改 display_id_*.xml 的 sdrHdrRatioMap 并反编译 services.jar 删门禁。
    // v1.6.2 不复制「改 jar」这一步，绝不写 mSupportUltraHDR / mForceSurfaceControl
    // （历史上强切亮度通路会黑屏）：
    //   ① 运行时找到 spline 字段后替换曲线，x 轴来自面板 nits，不写死 [2,650]；
    //   ② 接管 handleHdrSdrNitsChanged，按 AOSP 公式算比值并在 SyncRoot 内发布；
    //   ③ 只有 splineInstalled + ratioBypassInstalled + ratioBypassVerified 全部成立，
    //      且本次 HDR 会话没有触发 specFallback，specOn() 才抑制 GENERIC 补偿。
    // watcher 发现曲线被换回、比值 2 秒仍无效或开关关闭时，立即退回始终安装的 GENERIC。
    // 字段形状兼容 AOSP15 的 DisplayDeviceConfig.mSdrToHdrRatioSpline 与 OEM 的
    // mHbmData / mHdrBrightnessData.sdrToHdrRatioSpline。
    @Volatile private var specificMode = false
    @Volatile private var splineInstalled = false
    /** 当前装进系统的曲线用的上限；App 改了 hdr_ratio_max 就要按它判断是否需要重装 */
    @Volatile private var splineCap = Float.NaN

    private fun outerOf(obj: Any): Any? {
        var k: Class<*>? = obj.javaClass
        while (k != null) {
            for (f in k.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                val maybeOuter = f.isSynthetic || f.name.startsWith("this") ||
                    f.type.name.contains("LocalDisplayDevice")
                if (!maybeOuter) continue
                try {
                    f.isAccessible = true
                    val v = f.get(obj) ?: continue
                    if (v !== obj && findFieldUp(v.javaClass, "mCurrentHdrSdrRatio") != null) return v
                } catch (_: Throwable) {
                }
            }
            k = k.superclass
        }
        return null
    }

    private fun localDeviceOf(objOrNull: Any?): Any? {
        val obj = objOrNull ?: return null
        if (findFieldUp(obj.javaClass, "mCurrentHdrSdrRatio") != null) return obj
        return outerOf(obj) ?: aospDev
    }

    private fun adapterOf(dev: Any): Any? {
        var k: Class<*>? = dev.javaClass
        while (k != null) {
            for (f in k.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                val maybeAdapter = f.isSynthetic || f.name.startsWith("this") ||
                    f.type.name.contains("DisplayAdapter")
                if (!maybeAdapter) continue
                try {
                    f.isAccessible = true
                    val v = f.get(dev) ?: continue
                    if (v !== dev && (hasMethodAny(v.javaClass, "getSyncRoot") ||
                            findFieldUp(v.javaClass, "mSyncRoot") != null)
                    ) return v
                } catch (_: Throwable) {
                }
            }
            k = k.superclass
        }
        return null
    }

    private fun syncRootOf(dev: Any): Any? {
        val candidates = ArrayList<Any>()
        adapterOf(dev)?.let { candidates.add(it) }
        candidates.add(dev)
        for (o in candidates) {
            try {
                val m = o.javaClass.getMethod("getSyncRoot")
                val root = m.invoke(o)
                if (root != null) return root
            } catch (_: Throwable) {
            }
            try {
                val f = findFieldUp(o.javaClass, "mSyncRoot") ?: continue
                val root = f.get(o)
                if (root != null) return root
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun ratioEquals(a: Float, b: Float): Boolean = when {
        a.isNaN() || b.isNaN() -> a.isNaN() && b.isNaN()
        else -> Math.abs(a - b) < 0.0001f
    }

    private fun notifyBacklightAnimFinished(dev: Any, ratio: Float) {
        val ext = findExt(dev) ?: return
        try {
            val m = ext.javaClass.declaredMethods.firstOrNull {
                it.name == "notifyBacklightAnimFinished" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == java.lang.Float.TYPE
            } ?: return
            m.isAccessible = true
            m.invoke(ext, ratio)
        } catch (_: Throwable) {
        }
    }

    /**
     * ★ 核心：接管 handleHdrSdrNitsChanged，用 AOSP 原版逻辑替换掉带厂商门禁的实现。
     *
     * 这正是旧模块「反编译删掉三道门禁」的运行时等价物：
     *   AOSP 原版：
     *       if (displayNits != -1 && sdrNits != -1) ratio = max(1, nits/sdrNits);
     *       else                                     ratio = NaN;
     *       if (!floatEquals(mCurrentHdrSdrRatio, ratio)) {
     *           synchronized (getSyncRoot()) { mCurrentHdrSdrRatio = ratio; updateDeviceInfoLocked(); }
     *       }
     *   厂商版：上面还要 (mUseSurfaceControlBrightness || mForceSurfaceControl) && mSupportUltraHDR
     *
     * 关键：**绝不改任何标志位**。之前把 mForceSurfaceControl 置 true 会把亮度输出
     * 从 sysfs 背光通路切到 SurfaceControl 通路，本机不支持 → 屏幕点不亮。
     * 这里只接管这一个方法，完全不碰亮度通路。
     */
    private fun hookAospRatioBypass(classLoader: ClassLoader): Int = try {
        ratioBypassInstalled = false
        ratioBypassVerified = false
        val hook = object : XpHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!aospMode || !enabled()) return
                val nits = param.args.getOrNull(0) as? Float ?: return
                val sdrNits = param.args.getOrNull(1) as? Float ?: return
                val ratio = if (nits != -1f && sdrNits != -1f && nits.isFinite() &&
                    sdrNits.isFinite() && sdrNits > 0f
                ) {
                    cappedRatio(Math.max(1f, nits / sdrNits)).takeIf { it.isFinite() } ?: Float.NaN
                } else {
                    Float.NaN
                }
                val dev = localDeviceOf(param.thisObject) ?: return
                if (findFieldUp(dev.javaClass, "mCurrentHdrSdrRatio") == null) return
                val cur = try {
                    XpHelpers.getFloatField(dev, "mCurrentHdrSdrRatio")
                } catch (_: Throwable) {
                    Float.NaN
                }
                if (ratioEquals(cur, ratio)) {
                    if (!ratio.isNaN()) ratioBypassVerified = true
                    updateSpecificMode()
                    param.result = null
                    return
                }
                val root = syncRootOf(dev)
                if (root == null) {
                    ratioBypassVerified = false
                    specificMode = false
                    logEvery("noroot", 30000L) {
                        "$TAG: sync root unavailable, letting original handleHdrSdrNitsChanged run"
                    }
                    return
                }
                notifyBacklightAnimFinished(dev, ratio)
                var writeOk = false
                val job = Runnable {
                    try {
                        XpHelpers.setFloatField(dev, "mCurrentHdrSdrRatio", ratio)
                        XpHelpers.callMethod(dev, "updateDeviceInfoLocked")
                        writeOk = true
                    } catch (t: Throwable) {
                        writeOk = false
                        logEvery("bypasswrite", 10000L) { "$TAG: ratio write failed: " + t }
                    }
                }
                synchronized(root) { job.run() }
                ratioBypassVerified = writeOk && !ratio.isNaN()
                if (!writeOk) {
                    specificMode = false
                    return
                }
                updateSpecificMode()
                param.result = null
                logEvery("bypass", 2000L) {
                    "$TAG: ratio bypass nits=" + f2(nits) + " sdrNits=" + f2(sdrNits) +
                        " -> " + f2(ratio)
                }
            }
        }
        var n = 0
        for (cls in ratioHandlerClasses(classLoader)) {
            n += hookAll(cls, "handleHdrSdrNitsChanged", hook)
        }
        if (n > 0) {
            ratioBypassInstalled = true
            Xp.log("$TAG: ratio bypass installed on " + n + " method(s)")
        } else {
            logEvery("bypassnone", 60000L) { "$TAG: ratio bypass target missing; GENERIC fallback stays active" }
        }
        n
    } catch (t: Throwable) {
        ratioBypassInstalled = false
        ratioBypassVerified = false
        logEvery("bypasshook", 60000L) { "$TAG: ratio bypass hook failed: " + t.message }
        0
    }

    /**
     * 让应用侧「HDR SDR 调光支持」变成可用状态。
     *
     * android.view.Display#isHdrSdrRatioAvailable() 的实现就是
     *     return !Float.isNaN(mDisplayInfo.hdrSdrRatio);
     * 而 Display#registerHdrSdrRatioChangedListener() 在不可用时直接 return。也就是说
     * 只要比值是 NaN，应用既显示「不支持」，也永远不会订阅后续变化 —— 即使我们后来
     * 真的把比值写成 4.7，应用的界面也停在 1.0。这正是 v1.6.2 删掉 NaN 归一后的回归。
     *
     * 做法：在 LocalDisplayDevice 重建 DisplayDeviceInfo 之前归一一次，
     *   NaN      -> 1.0（AOSP 自己的下界：比值恒 >= 1，1.0 就是「HDR 与 SDR 同亮度」）
     *   有限值   -> 原样保留（HDR 期间由 hookAospRatioBypass / forceHdrSdrRatio 写入真实比值）
     * 只改这一个字段，不碰亮度通路，也不写 mSupportUltraHDR / mForceSurfaceControl。
     */
    private fun hookRatioAvailability(classLoader: ClassLoader): Int = try {
        var n = 0
        val hook = object : XpHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!enabled()) return
                try {
                    val dev = param.thisObject ?: return
                    if (findFieldUp(dev.javaClass, "mCurrentHdrSdrRatio") == null) return
                    val cur = XpHelpers.getFloatField(dev, "mCurrentHdrSdrRatio")
                    if (!cur.isNaN()) return
                    XpHelpers.setFloatField(dev, "mCurrentHdrSdrRatio", 1.0f)
                    logEvery("ratioavail", 60000L) {
                        "$TAG: ratio availability advertised (NaN -> 1.0) so apps can subscribe"
                    }
                } catch (_: Throwable) {
                }
            }
        }
        val cand = localDisplayCandidates(classLoader)
        for (cls in cand) {
            n += hookAll(cls, "updateDeviceInfoLocked", hook)
        }
        Xp.log(
            "$TAG: ratio availability candidates=" + cand.size +
                " withUpdateDeviceInfo=" + cand.count { hasMethod(it, "updateDeviceInfoLocked") } +
                " hooked=" + n
        )
        if (n > 0) Xp.log("$TAG: ratio availability hook installed on " + n + " method(s)")
        n
    } catch (t: Throwable) {
        logEvery("ratioavailhook", 60000L) { "$TAG: ratio availability hook failed: " + t.message }
        0
    }

    /**
     * 只读诊断：打印 handleHdrSdrNitsChanged 的真实入参与门禁状态。
     * 不改任何行为，纯粹为了「比值为什么是 1」能一眼看出来。
     */
    private fun hookRatioProbeOnly(classLoader: ClassLoader): Int = try {
        val hook = object : XpHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!enabled()) return
                logEvery("probeOnly", 2000L) {
                    val nits = param.args.getOrNull(0) as? Float ?: Float.NaN
                    val sdrNits = param.args.getOrNull(1) as? Float ?: Float.NaN
                    val dev = localDeviceOf(param.thisObject)
                    var gates = "?"
                    var cur = Float.NaN
                    if (dev != null) {
                        try {
                            val ba = XpHelpers.getObjectField(dev, "mBacklightAdapter")
                            val adapter = adapterOf(dev)
                            gates = "use=" + XpHelpers.getBooleanField(ba, "mUseSurfaceControlBrightness") +
                                " force=" + XpHelpers.getBooleanField(ba, "mForceSurfaceControl") +
                                " ultra=" + (adapter?.let {
                                    XpHelpers.getBooleanField(it, "mSupportUltraHDR").toString()
                                } ?: "?")
                        } catch (_: Throwable) {
                        }
                        cur = try {
                            XpHelpers.getFloatField(dev, "mCurrentHdrSdrRatio")
                        } catch (_: Throwable) {
                            Float.NaN
                        }
                    }
                    "$TAG: PROBE handleHdrSdrNitsChanged(nits=" + f2(nits) +
                        " sdrNits=" + f2(sdrNits) + ") -> current=" + f2(cur) + " [" + gates + "]"
                }
            }
        }
        var n = 0
        for (cls in ratioHandlerClasses(classLoader)) {
            n += hookAll(cls, "handleHdrSdrNitsChanged", hook)
        }
        n
    } catch (t: Throwable) {
        logEvery("probefail", 60000L) { "$TAG: ratio probe hook failed: " + t.message }
        0
    }

    /** 用 AOSP 自带的静态工厂造 Spline（等价于 XML 解析 <sdrHdrRatioMap>） */
    private fun buildSpline(splineCls: Class<*>, xs: FloatArray, ys: FloatArray): Any? {
        for (name in arrayOf("createMonotoneCubicSpline", "createLinearSpline")) {
            try {
                val m = splineCls.getMethod(name, FloatArray::class.java, FloatArray::class.java)
                val v = m.invoke(null, xs, ys)
                if (v != null) return v
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private data class SplineTarget(
        val field: java.lang.reflect.Field,
        val owner: Any,
        val shape: String
    )

    private fun resolveSplineTarget(cfg: Any): SplineTarget? {
        findFieldUp(cfg.javaClass, "mSdrToHdrRatioSpline")?.let {
            it.isAccessible = true
            return SplineTarget(it, cfg, "DisplayDeviceConfig.mSdrToHdrRatioSpline")
        }
        for (holderName in arrayOf("mHbmData", "mHdrBrightnessData")) {
            val holderField = findFieldUp(cfg.javaClass, holderName) ?: continue
            holderField.isAccessible = true
            val holder = holderField.get(cfg) ?: continue
            val splineField = findFieldUp(holder.javaClass, "sdrToHdrRatioSpline") ?: continue
            splineField.isAccessible = true
            return SplineTarget(splineField, holder, holderName + ".sdrToHdrRatioSpline")
        }
        return null
    }

    @Synchronized
    private fun installSpecificSpline() {
        if (!aospMode || !enabled() || splineInstalled) return
        val cfg = displayConfig ?: run {
            logEvery("splinedefer", 60000L) { "$TAG: spline install deferred: displayConfig not captured yet" }
            return
        }
        val target = resolveSplineTarget(cfg) ?: run {
            logEvery("splineshape", 60000L) {
                "$TAG: spline install deferred: no supported sdrToHdrRatioSpline field on " + cfg.javaClass.name
            }
            return
        }
        if (!resolveCfgMethods() || !panelMinNits.isFinite() || !panelMaxNits.isFinite() ||
            panelMaxNits <= panelMinNits
        ) {
            logEvery("splinenits", 60000L) {
                "$TAG: spline install deferred: panel nits unresolved (max=" +
                    f2(panelMaxNits) + "), will retry"
            }
            return
        }
        try {
            val splineCls = target.field.type
            val cap = ratioCap()
            val xs = floatArrayOf(panelMinNits, panelMaxNits)
            val ys = floatArrayOf(cap, 1.0f)
            val spline = buildSpline(splineCls, xs, ys) ?: run {
                logEvery("splinefactory", 60000L) {
                    "$TAG: spline install deferred: no static factory on " + splineCls.name
                }
                return
            }
            target.field.set(target.owner, spline)
            splineProxy = spline
            splineField = target.field
            splineOwner = target.owner
            splineShape = target.shape
            splineInstalled = true
            splineCap = cap
            Xp.log(
                "$TAG: specific chain sdrHdrRatioSpline replaced (" + target.shape +
                    " " + splineCls.simpleName + " x=[" + f2(xs[0]) + "," + f2(xs[1]) +
                    "] y=[" + f2(ys[0]) + "," + f2(ys[1]) + "])"
            )
        } catch (t: Throwable) {
            logEvery("splinefail", 60000L) { "$TAG: replace sdrHdrRatioSpline failed: " + t }
        }
    }

    @Volatile private var ratioBypassInstalled = false
    /** 只有一次真正按 AOSP 语义写出过有限比值，专项链路才算健康。 */
    @Volatile private var ratioBypassVerified = false

    /** 沿继承链找字段：厂商常常把 DisplayDeviceConfig 再派生一层，字段在父类上，
     *  只用 getDeclaredField 会直接 NoSuchFieldException。 */
    private fun findFieldUp(c: Class<*>, name: String): java.lang.reflect.Field? {
        var k: Class<*>? = c
        while (k != null) {
            try {
                val fd = k.getDeclaredField(name)
                fd.isAccessible = true
                return fd
            } catch (_: Throwable) {
            }
            k = k.superclass
        }
        return null
    }

    private fun hasMethod(c: Class<*>, name: String, vararg p: Class<*>): Boolean {
        var k: Class<*>? = c
        while (k != null) {
            try {
                k.getDeclaredMethod(name, *p)
                return true
            } catch (_: Throwable) {
                k = k.superclass
            }
        }
        return false
    }

    private fun hasMethodAny(c: Class<*>, name: String): Boolean {
        var k: Class<*>? = c
        while (k != null) {
            if (k.declaredMethods.any { it.name == name }) return true
            k = k.superclass
        }
        return false
    }

    private fun floatUnary(owner: Any, names: Array<String>): Pair<java.lang.reflect.Method, Any>? {
        var k: Class<*>? = owner.javaClass
        while (k != null) {
            for (m in k.declaredMethods) {
                if (m.name !in names) continue
                if (m.parameterTypes.size != 1 || m.parameterTypes[0] != java.lang.Float.TYPE) continue
                if (m.returnType != java.lang.Float.TYPE) continue
                if (java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
                m.isAccessible = true
                return Pair(m, owner)
            }
            k = k.superclass
        }
        return null
    }

    /** LocalDisplayDevice 本体 + 它内部的 listener 类（$1..$16），不过滤方法，供多用途复用。 */
    private fun localDisplayCandidates(cl: ClassLoader): List<Class<*>> {
        val out = ArrayList<Class<*>>()
        val device = try {
            XpHelpers.findClass("com.android.server.display.LocalDisplayAdapter" + D + "LocalDisplayDevice", cl)
        } catch (_: Throwable) {
            null
        }
        if (device != null) {
            out.add(device)
            try {
                out.addAll(device.declaredClasses.toList())
            } catch (_: Throwable) {
            }
        }
        for (i in 1..16) {
            try {
                out.add(
                    XpHelpers.findClass(
                        "com.android.server.display.LocalDisplayAdapter" + D + "LocalDisplayDevice" + D + i, cl
                    )
                )
            } catch (_: Throwable) {
            }
        }
        return out.distinct()
    }

    private fun ratioHandlerClasses(cl: ClassLoader): List<Class<*>> {
        val out = ArrayList<Class<*>>()
        val device = try {
            XpHelpers.findClass("com.android.server.display.LocalDisplayAdapter" + D + "LocalDisplayDevice", cl)
        } catch (_: Throwable) {
            null
        }
        if (device != null) {
            out.add(device)
            try {
                out.addAll(device.declaredClasses.toList())
            } catch (_: Throwable) {
            }
        }
        for (i in 1..16) {
            try {
                out.add(
                    XpHelpers.findClass(
                        "com.android.server.display.LocalDisplayAdapter" + D + "LocalDisplayDevice" + D + i, cl
                    )
                )
            } catch (_: Throwable) {
            }
        }
        val F = java.lang.Float.TYPE
        return out.distinct().filter { hasMethod(it, "handleHdrSdrNitsChanged", F, F) }
    }

    private fun brightnessCandidates(cl: ClassLoader): List<Class<*>> {
        val out = ArrayList<Class<*>>()
        val device = try {
            XpHelpers.findClass("com.android.server.display.LocalDisplayAdapter" + D + "LocalDisplayDevice", cl)
        } catch (_: Throwable) {
            null
        }
        if (device != null) {
            out.add(device)
            try {
                out.addAll(device.declaredClasses.toList())
            } catch (_: Throwable) {
            }
        }
        for (i in 1..16) {
            try {
                out.add(
                    XpHelpers.findClass(
                        "com.android.server.display.LocalDisplayAdapter" + D + "LocalDisplayDevice" + D + i, cl
                    )
                )
            } catch (_: Throwable) {
            }
        }
        return out.distinct().filter { hasMethodAny(it, "setDisplayBrightness") }
    }

    @Volatile private var splineProxy: Any? = null
    @Volatile private var splineField: java.lang.reflect.Field? = null
    @Volatile private var splineOwner: Any? = null
    @Volatile private var splineShape = "none"
    @Volatile private var lastModeCheckAt = 0L

    /** 本次 HDR 会话内专项链路没生效 -> 临时退回通用兜底 */
    @Volatile private var specFallback = false
    @Volatile private var hdrOnSince = 0L

    private fun updateSpecificMode() {
        // 曲线已替换 + 比值计算已被接管 = 设备行为回到 AOSP
        specificMode = aospMode && splineInstalled && ratioBypassInstalled && ratioBypassVerified
    }

    // 门禁守护已删除：绝不写 mSupportUltraHDR / mForceSurfaceControl，避免切到设备不支持的亮度通路。

    /** 当前是否真的按「专项链路（系统自算）」运行 */
    private fun specOn(): Boolean = aospMode && specificMode && !specFallback

    /**
     * 周期性自检，防止链路长期卡在错误状态：
     *  ① DPC 早于 LocalDisplayAdapter 构造时，持续重试安装 spline；
     *  ② 系统重新加载 DisplayDeviceConfig 时，检测曲线被换回并重装；
     *  ③ 专项链长时间不能产生有效比值时，退回始终安装的 GENERIC 兜底。
     */
    private fun refreshSpecificMode() {
        if (!aospMode || !enabled()) {
            specificMode = false
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastModeCheckAt < 3000L) return
        lastModeCheckAt = now
        val before = specificMode
        if (!splineInstalled) {
            installSpecificSpline()
        } else {
            val fld = splineField
            val owner = splineOwner
            val proxy = splineProxy
            try {
                if (fld == null || owner == null || proxy == null || fld.get(owner) !== proxy) {
                    splineInstalled = false
                    splineProxy = null
                    logEvery("splinecheck", 60000L) {
                        "$TAG: sdrHdrRatioSpline was replaced or invalid -> reinstall"
                    }
                    installSpecificSpline()
                }
            } catch (t: Throwable) {
                splineInstalled = false
                splineProxy = null
                logEvery("splinecheck", 60000L) { "$TAG: spline check failed: " + t }
                installSpecificSpline()
            }
        }
        updateSpecificMode()
        if (before != specificMode) {
            Xp.log(
                "$TAG: chain -> " + (if (specificMode) "SPECIFIC(系统自算)" else "GENERIC(模块代劳)") +
                    " [spline=" + splineInstalled + " shape=" + splineShape +
                    " bypass=" + ratioBypassInstalled + " verified=" + ratioBypassVerified +
                    " fallback=" + specFallback + "]"
            )
        }
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
        val cls = XpHelpers.findClass(
            "com.android.server.display.HighBrightnessModeController", classLoader
        )
        Xp.hookAllConstructors(cls, object : XpHook() {
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
        // 专项链路：HBM 由系统自己的 SurfaceControlHdrLayerInfoListener 决定，模块不介入
        if (specOn()) return
        val c = hbmCtl ?: run {
            logEvery("hbmnoctl", 10000L) { "$TAG: HBM skipped: controller not captured" }
            return
        }
        try {
            XpHelpers.setBooleanField(c, "mIsHdrLayerPresent", boost)
            try {
                // 目标 HDR/SDR 比：决定 getHdrBrightnessValue() 的 headroom
                XpHelpers.setFloatField(
                    c, "mMaxDesiredHdrSdrRatio", if (boost) factor().coerceIn(1f, 20f) else 1f
                )
            } catch (_: Throwable) {
            }
            XpHelpers.callMethod(c, "updateHbmMode")
            val mode = try {
                XpHelpers.getIntField(c, "mHbmMode")
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


    /** 闸门开放窗口：HDR 关闭后再保留 5s，用于把残留的有限旧比值恢复为 1.0；NaN 保持「不可用」 */
    @Volatile private var gateUntil = 0L
    /** HDR 内容首次消失的时刻（用于退出迟滞） */
    @Volatile private var hdrOffSince = 0L
    /** ColorOS 原生路径上「只剩窗口信号、SF 已无 HDR 图层」的起点 */
    @Volatile private var noContentSince = 0L

    /**
     * 只读比值诊断探针：打印 handleHdrSdrNitsChanged 的真实入参与算出的比值。
     * 判据：没有这行 → 系统没调用该方法或比值链未就绪；nits==sdrNits → 亮度没推上去。
     * 当前不再挂 hasSdrToHdrRatioSpline()，也不写任何厂商门禁标志位。
     */
    private fun hookHdrRatioProbe(inner: Class<*>): Int = try {
        val n = Xp.hookAllMethods(inner, "handleHdrSdrNitsChanged", object : XpHook() {
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
        n
    } catch (t: Throwable) {
        logEvery("probe", 60000L) { "$TAG: ratio probe unavailable: " + t.message }
        0
    }

    // AOSP Display.isHdrSdrRatioAvailable() 用 !Float.isNaN(hdrSdrRatio) 判可用；
    // NaN 是合法的「不可用」状态，绝不伪装成 1.0。

    /** 在 LocalDisplayDevice 上找到 ext 实现对象（字段名/类型名两种策略） */
    private fun findExt(devObj: Any): Any? {
        for (name in arrayOf("mLocalDisplayDeviceExt", "mLocalDisplayDeviceExtImpl")) {
            try {
                val v = XpHelpers.getObjectField(devObj, name)
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

    private fun videoEnabled(): Boolean = prop2(PROP_VIDEO, PROP_VIDEO_OLD, "1") != "0"

    private fun hookMediaSessions(classLoader: ClassLoader): Int = try {
        val cls = XpHelpers.findClass("com.android.server.media.MediaSessionStack", classLoader)
        val hook = object : XpHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!enabled() || !videoEnabled()) return
                val rec = param.args.getOrNull(0) ?: return
                noteSession(rec)
            }
        }
        var n = hookAll(cls, "onPlaybackStateChanged", hook)
        n += hookAll(cls, "addSession", object : XpHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                recheckNow()
                if (!enabled() || !videoEnabled()) return
                noteSession(param.args.getOrNull(0) ?: return)
            }
        })
        n += hookAll(cls, "removeSession", object : XpHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val rec = param.args.getOrNull(0) ?: return
                try {
                    val pkg = XpHelpers.callMethod(rec, "getPackageName") as? String ?: return
                    playingPkgs.remove(pkg)
                } catch (_: Throwable) {
                }
            }
        })
        n += hookAll(cls, "joinSession2Stack", object : XpHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!enabled() || !videoEnabled()) return
                noteSession(param.args.getOrNull(0) ?: return)
            }
        })
        n
    } catch (t: Throwable) {
        Xp.log("$TAG: MediaSessionStack not found: " + t.message)
        0
    }

    private fun noteSession(rec: Any) {
        try {
            val pkg = XpHelpers.callMethod(rec, "getPackageName") as? String ?: return
            var state = -1
            try {
                state = XpHelpers.getIntField(rec, "mPlaybackState")
            } catch (_: Throwable) {
                try {
                    val st = XpHelpers.callMethod(rec, "getStateWithUpdatedPosition") ?: return
                    state = XpHelpers.callMethod(st, "getState") as? Int ?: -1
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
                        val st = XpHelpers.callMethod(rec, "getStateWithUpdatedPosition") ?: return
                        st2 = XpHelpers.callMethod(st, "getState") as? Int ?: -1
                    } catch (_: Throwable) {
                    }
                }
                if (st2 != PLAYBACK_PLAYING) playingPkgs.remove(pkg)
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 前台是否正在播放带 HDR 元数据的媒体。
     *
     * 只认「前台包名处于 PLAYING 的 MediaSession」+「SF 图层带 HDR 元数据」；
     * 暂停会话、纯音频会话和没有 HDR 证据的 SDR 视频都不提亮。
     */
    private fun videoPlaying(): Boolean {
        if (!videoEnabled()) return false
        val now = android.os.SystemClock.elapsedRealtime()
        val it = playingPkgs.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value > 30000L) it.remove()
        }
        val pkg = focusedPackage() ?: return false
        if (!playingPkgs.containsKey(pkg)) return false
        // 还必须有 HDR 内容证据；普通 SDR 视频/音频一律不提亮。
        // ★ 关键闸门：必须拿到「HDR 内容」证据，普通 SDR 视频一律不提亮。
        // 证据来自 SurfaceFlinger：图层携带 HDR 元数据时 SF 才认为在放 HDR
        // （与 HWC 是否支持 HDR 无关）。transact(21012) 就是 ColorOS 自己用的那条。
        val hdr = sfLayerHasHdrMetadata()
        if (hdr == true) return true
        logEvery("sdrsafe", 5000L) {
            "$TAG: playing media without HDR content -> no boost (SDR safe, hdrLayer=" + hdr + ")"
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
    // 成本控制：只在前台应用有正在播放的媒体会话时查询 SF；结果缓存 2s；
    // 管道并发读取、命中即停，避免 237KB 全量解析和管道阻塞。
    @Volatile private var hdrMetaCache: Boolean? = null
    @Volatile private var hdrMetaAt = 0L
    @Volatile private var hdrQueryAt = 0L
    private val watcherLock = java.util.concurrent.locks.ReentrantLock()
    private val watcherWakeup: java.util.concurrent.locks.Condition = watcherLock.newCondition()
    private val recheckPending = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun sfLayerHasHdrMetadata(): Boolean? {
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = hdrMetaCache
        val pending = recheckPending.get()
        if (!pending && cached != null && now - hdrMetaAt < 2000L) return cached
        if (recheckPending.compareAndSet(true, false)) hdrQueryAt = 0L
        if (now - hdrQueryAt < 800L) return null
        hdrQueryAt = now
        val v = querySfLayerHdr()
        if (v == null) {
            hdrMetaCache = null
            logEvery("sffail", 30000L) { "$TAG: SF layer dump failed (HDR evidence unavailable)" }
            return null
        }
        hdrMetaAt = now
        hdrMetaCache = v
        return v
    }

    private val HDR_META_RE = Regex("hdr metadata types=([0-9]+)")
    /** HDR10/PQ 的 SF dataspace 头（Android 16 / ColorOS 平板实测：图层不带静态元数据，只换 dataspace） */
    private const val HDR_DATASPACE_MARK = "dataspace=0x188a"
    private val HDR_RATIO_RE = Regex("current hdr/sdr ratio=([0-9.]+)")

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
                        // 有的机型（Android 16 / ColorOS 平板实测）图层不带静态 HDR 元数据，
                        // HDR 内容只体现为 PQ/BT2020 dataspace 或 SF 自己的 hdr/sdr 比值。
                        if (line.contains(HDR_DATASPACE_MARK)) {
                            hit.set(true)
                            break
                        }
                        val r = HDR_RATIO_RE.find(line)
                        if (r != null) {
                            val v = r.groupValues[1].toFloatOrNull() ?: 0f
                            if (v > 1.5f) {
                                hit.set(true)
                                break
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
        if (reader.isAlive) {
            try {
                readFd.close()
            } catch (_: Throwable) {
            }
            reader.interrupt()
            return null
        }
        hit.get()    } catch (_: Throwable) {
        null
    }

    /** 事件驱动：标记一次待复查并唤醒 watcher；失败不会把旧 true 续命。 */
    private fun recheckNow() {
        recheckPending.set(true)
        hdrMetaCache = null
        hdrMetaAt = 0L
        watcherLock.lock()
        try {
            watcherWakeup.signalAll()
        } finally {
            watcherLock.unlock()
        }
    }

    private fun focusedPackage(): String? = try {
        val w = wms ?: return null
        val root = XpHelpers.getObjectField(w, "mRoot")
        val dc = XpHelpers.callMethod(root, "getTopFocusedDisplayContent") ?: return null
        val win = XpHelpers.getObjectField(dc, "mCurrentFocus") ?: return null
        val attrs = XpHelpers.getObjectField(win, "mAttrs")
        XpHelpers.getObjectField(attrs, "packageName") as? String
    } catch (_: Throwable) {
        null
    }

    // ── D. HDR 信号：焦点窗口 / relayout / SurfaceFlinger ─────
    private fun hookWindowManager(classLoader: ClassLoader): Int {
        var n = 0
        try {
            val cls = XpHelpers.findClass("com.android.server.wm.WindowManagerService", classLoader)
            Xp.hookAllConstructors(cls, object : XpHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    wms = param.thisObject
                }
            })
            n += hookAll(cls, "relayoutWindow", object : XpHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (wms == null) wms = param.thisObject
                    try {
                        val args = param.args
                        if (args.size < 6) return
                        val client = args[1] ?: return
                        val attrs = args[2] ?: return
                        val vis = (args[5] as? Int) ?: -1
                        val cm = XpHelpers.callMethod(attrs, "getColorMode") as? Int ?: 0
                        if (cm == COLOR_MODE_HDR && vis == 0) hdrWindows.add(client)
                        else hdrWindows.remove(client)
                        if (vis == 0) recheckNow()
                    } catch (_: Throwable) {
                    }
                }
            })
            n += hookAll(cls, "removeWindow", object : XpHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val client = param.args.getOrNull(1) ?: return
                    hdrWindows.remove(client)
                }
            })
        } catch (t: Throwable) {
            Xp.log("$TAG: WMS not found: " + t.message)
        }
        return n
    }

    /** 焦点窗口是否请求 HDR；null = 反射失败 */
    private fun focusedWindowHdr(): Boolean? = try {
        val w = wms ?: return null
        val root = XpHelpers.getObjectField(w, "mRoot")
        val dc = XpHelpers.callMethod(root, "getTopFocusedDisplayContent") ?: return null
        // Android 16 的 DisplayContent 没有 getFocusedWindow()，直接读 mCurrentFocus
        val win = XpHelpers.getObjectField(dc, "mCurrentFocus") ?: return false
        val attrs = XpHelpers.getObjectField(win, "mAttrs")
        (XpHelpers.callMethod(attrs, "getColorMode") as Int) == COLOR_MODE_HDR
    } catch (t: Throwable) {
        logEvery("focusfail", 10000L) { "$TAG: focus poll failed: " + t }
        null
    }

    /** SurfaceFlinger 是否正在播放 HDR（真 HDR 视频走这条，正常机型也走这条） */
    private fun sfHdrPlaying(): Boolean {
        var parcelCls: Class<*>? = null
        var data: Any? = null
        var reply: Any? = null
        return try {
            val flinger = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "SurfaceFlinger") ?: return false
            val c = Class.forName("android.os.Parcel")
            parcelCls = c
            data = c.getMethod("obtain").invoke(null)
            reply = c.getMethod("obtain").invoke(null)
            c.getMethod("writeInterfaceToken", String::class.java).invoke(data, SF_TOKEN)
            flinger.javaClass.getMethod(
                "transact", Int::class.javaPrimitiveType, c, c, Int::class.javaPrimitiveType
            ).invoke(flinger, CODE_GET_HDR_STATE, data, reply, 0)
            val v = c.getMethod("readInt").invoke(reply) as Int
            v == 1
        } catch (_: Throwable) {
            false
        } finally {
            val c = parcelCls
            if (c != null) {
                try {
                    val recycle = c.getMethod("recycle")
                    data?.let { recycle.invoke(it) }
                    reply?.let { recycle.invoke(it) }
                } catch (_: Throwable) {
                }
            }
        }
    }

    // ── 状态机 ──────────────────────────────────────────────
    private fun startWatcher() {
        Thread {
            while (true) {
                try {
                    if (!enabled()) {
                        trailHandler.removeCallbacks(trailRunnable)
                        pendingRatio.set(null)
                        hdrWindows.clear()
                        playingPkgs.clear()
                        if (hdrOn) hdrOn = false
                        gateUntil = 0L
                        specFallback = false
                        hdrOffSince = 0L
                        noContentSince = 0L
                        lastForcedRatio = Float.NaN
                    } else {
                        syncRatioCap()
                        refreshSpecificMode()
                        val nowTail = android.os.SystemClock.elapsedRealtime()
                        if (aospMode && !hdrOn && gateUntil != 0L && nowTail >= gateUntil) {
                            gateUntil = 0L
                            val cur = currentHdrSdrRatio()
                            // NaN 是合法的「不可用」状态；只清理残留的有限旧比值，不伪装成 1.0。
                            if (!cur.isNaN() && Math.abs(cur - 1f) > 0.05f) {
                                if (forceHdrSdrRatio(aospDev, 1.0f)) {
                                    lastForcedRatio = 1.0f
                                    lastForceAt = nowTail
                                    Xp.log("$TAG: ratio tail-restore 1.0 (was " + f2(cur) + ")")
                                }
                            }
                        }
                        if (hdrOn && specificMode && !specFallback &&
                            nowTail - hdrOnSince > 2000L
                        ) {
                            val cur = currentHdrSdrRatio()
                            if (cur.isNaN() || cur <= 1.05f) {
                                specFallback = true
                                Xp.log(
                                    "$TAG: specific chain ineffective after 2s (ratio=" +
                                        f2(cur) + ") -> fallback to GENERIC for this session"
                                )
                            }
                        }
                        val focus = focusedWindowHdr()
                        val video = videoPlaying()
                        val sfMeta = sfLayerHasHdrMetadata()
                        val contentEvidence = video || sfMeta == true
                        val base = when {
                            focus == true -> true
                            video -> true
                            focus == null -> hdrWindows.isNotEmpty() || sfHdrPlaying()
                            else -> sfMeta == true || hdrWindows.isNotEmpty()
                        }
                        val nowMs = android.os.SystemClock.elapsedRealtime()
                        // 窗口 colorMode / WMS 残留比内容晚很久才清：Android 16 / ColorOS 平板实测
                        // 关掉 HDR 之后还能挺二十多秒，屏一直停在 HDR 亮度与高比值。
                        // 只在「ColorOS 原生路径 + SF 明确没有 HDR 图层」时给一段有界宽限；
                        // AOSP 专项链不受影响，SF 查询失败（null）时也不介入。
                        val focusOnly = base && !contentEvidence && !aospMode && sfMeta == false
                        val raw = if (focusOnly) {
                            if (noContentSince == 0L) noContentSince = nowMs
                            (nowMs - noContentSince) < HDR_FOCUS_ONLY_HOLD_MS
                        } else {
                            noContentSince = 0L
                            base
                        }
                        val want = if (!raw && hdrOn && aospMode) {
                            if (hdrOffSince == 0L) hdrOffSince = nowMs
                            (nowMs - hdrOffSince) < HDR_EXIT_HOLD_MS
                        } else {
                            if (raw) hdrOffSince = 0L
                            raw
                        }
                        if (want != hdrOn) {
                            hdrOn = want
                            if (!want) {
                                gateUntil = nowMs + 5000L
                                specFallback = false
                            } else {
                                lastForcedRatio = Float.NaN
                                hdrOnSince = nowMs
                            }
                            val gen = ++stateGen
                            val d = hdrEnhance
                            if (!specOn()) {
                                // 退出时先放下厂商的提亮标志位，再走它自己的 checkIsPlayingHDR；
                                // 否则 exitEnhanceBrightness() 之后后处理仍按旧标志继续提亮。
                                if (!want) clearDriverEnhance(d)
                                if (d != null) callPriv(d, "checkIsPlayingHDR")
                                requestPowerUpdate()
                                pushAospBrightness(want)
                                applyHbm(want)
                                try {
                                    val h = dpcHandler
                                    if (h != null) {
                                        h.postDelayed({
                                            if (gen == stateGen && enabled()) requestPowerUpdate()
                                        }, 700L)
                                    }
                                } catch (_: Throwable) {
                                }
                                try {
                                    val h = dpcHandler
                                    if (h != null) {
                                        h.postDelayed({
                                            if (gen == stateGen && enabled()) pushAospBrightness(want)
                                        }, 700L)
                                    }
                                } catch (_: Throwable) {
                                }
                            }
                            val why = when {
                                focusOnly && !want -> "focus-only expired, no HDR layer"
                                focus == true -> "focus=true"
                                video -> "video playing"
                                focus == null -> "fallback"
                                else -> "focus=false"
                            }
                            Xp.log(
                                "$TAG: HDR -> " + want + "(" + why + ") [" + driverState(d) + "]"
                            )
                        } else if (want) {
                            logEvery("stuck", 3000L) {
                                val st = driverState(hdrEnhance)
                                if (st.contains("enhanced=false") || st.contains("need=false")) {
                                    "$TAG: HDR on but driver idle [" + st + "]"
                                } else {
                                    ""
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    logEvery("watchfail", 5000L) { "$TAG: watcher loop failed: " + t }
                }
                watcherLock.lock()
                try {
                    try {
                        watcherWakeup.await(POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                    }
                } finally {
                    watcherLock.unlock()
                }
            }
        }.apply { isDaemon = true; name = "FkHdr" }.start()
        Xp.log("$TAG: watcher started")
    }
}
