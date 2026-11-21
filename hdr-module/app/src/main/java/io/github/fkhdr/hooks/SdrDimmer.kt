package io.github.fkhdr.hooks

import io.github.fkhdr.xp.Xp
import io.github.fkhdr.xp.XpHelpers
import java.lang.reflect.Method

/**
 * 全局 SDR 压暗。
 *
 * ColorOS 的视频 HDR 只把面板亮度顶上去（厂商 convertNitToBrightness 把 nit 拉到面板上限），
 * 合成器不给 SDR 图层做 per-layer dimming：HDR 内容变亮的同时 SDR 内容也一起被抬亮。
 * MTK 机器上「HDR 亮、SDR 暗」来自 HWC/DPU 自己的 HDR dimmer，这台设备没有等价实现。
 *
 * 这里在 system_server 里补齐同一层语义：给所有 SDR 窗口的 SurfaceControl 下发
 * Transaction#setColorTransform（RGB 乘 1/ratio），把 SDR 层压回去。
 *
 * 视频走 SurfaceView 子图层，父窗口的颜色矩阵不下发到子图层，所以 HDR 内容不受影响；
 * 窗口自己 colorMode=HDR 时也直接跳过。
 *
 * 覆盖范围是整块屏幕上的所有 SDR 窗口（桌面、状态栏、通知、后台应用），不是只压前台一个窗口。
 *
 * 已知边界（一加平板 Pro / QC + ColorOS / Android 16 实测）：
 * 颜色矩阵由 GPU 在合成时执行，因此只有 CLIENT 合成的图层会在面板上真的变暗
 * （视频上的半透明 HUD、对话框、圆角/模糊层等）；不透明应用窗口、输入法、系统 UI 在这台机器上
 * 是 HWC DEVICE 合成，QC 的 composer 收下 setLayerColorTransform 但不执行，观感不变。
 * 试过的两条补救都没成：① SF 的 CompositionEngine 始终算 dimmingRatio=1.0（
 * displayBrightnessNits == sdrWhitePointNits，即 HWC 没上报 DisplayBrightnessCommand 能力），
 * 逐层 DPU dimming 在模块侧无法请求；② 给普通图层打 Transaction#setSecure 不会让
 * ColorOS 的 SF 强制 CLIENT 合成。所以这里不再尝试强制合成路径，避免引入 GPU 全屏合成的开销。
 */
internal object SdrDimmer {
    private const val TAG = "FkHDR:SdrDim"

    /** 最多压到 1/20，防止异常比值把屏幕压黑。 */
    private const val MIN_FACTOR = 0.05f

    private val zeros = floatArrayOf(0f, 0f, 0f, 0f)
    private var setColorTransform: Method? = null
    private var applyTx: Method? = null
    private var txClass: Class<*>? = null
    private var reflectFailed = false

    @Volatile private var factor = 1.0f
    /** 当前是否已经把颜色矩阵下发到图层（用来决定退出时要不要还原）。 */
    private var dimActive = false
    private var lastSignature = 0
    private var lastLogAt = 0L

    /** 供 watcher 判重：当前生效的压暗系数（1.0 = 未压暗）。 */
    fun currentFactor(): Float = factor

    /**
     * watcher 每轮调用。active = HDR 正在提亮、需要把 SDR 压回去；
     * ratio = 当前 hdr/sdr 比值（NaN 或 <=1 表示没有 HDR）；
     * windows = 当前所有窗口（WindowState, 该窗口自己是不是 HDR）。
     */
    fun update(active: Boolean, ratio: Float, windows: List<Pair<Any, Boolean>>) {
        val f = if (!active || ratio.isNaN() || ratio <= 1.02f) 1.0f
        else Math.max(MIN_FACTOR, 1.0f / ratio)
        synchronized(this) {
            val sig = signatureOf(windows)
            if (f >= 0.99f) {
                // 没有 HDR 时绝不给图层下发颜色矩阵：这条路会强制改变合成路径（本机 screencap
                // 能直接看出绿通道被吃掉），所以只在需要压暗时用，退出时还原一次 identity。
                if (!dimActive) {
                    logStatus(f, windows)
                    return
                }
                dimActive = false
                factor = 1.0f
                lastSignature = sig
                apply(1.0f, windows)
                return
            }
            if (dimActive && Math.abs(f - factor) < 0.002f && sig == lastSignature) {
                logStatus(f, windows)
                return
            }
            dimActive = true
            factor = f
            lastSignature = sig
            apply(f, windows)
        }
    }

    private fun signatureOf(windows: List<Pair<Any, Boolean>>): Int {
        var h = windows.size * 31
        for (w in windows) {
            h = h * 31 + System.identityHashCode(w.first)
            h = h * 31 + (if (w.second) 1 else 0)
        }
        return h
    }

    @Synchronized
    private fun apply(f: Float, windows: List<Pair<Any, Boolean>>) {
        if (reflectFailed) return
        if (windows.isEmpty()) {
            logThrottled("no window available")
            return
        }
        if (!ensureReflection()) return
        val tx = try {
            txClass!!.getConstructor().newInstance()
        } catch (t: Throwable) {
            reflectFailed = true
            Xp.log("$TAG: Transaction() failed: " + t)
            return
        }
        val matrix = matrixOf(f)
        var dimmed = 0
        var skipped = 0
        var failed = 0
        for ((w, isHdr) in windows) {
            if (isHdr) {
                skipped++
                continue
            }
            val sc = try {
                XpHelpers.callMethod(w, "getSurfaceControl")
            } catch (_: Throwable) {
                null
            } ?: continue
            try {
                setColorTransform!!.invoke(tx, sc, matrix, zeros)
                dimmed++
            } catch (t: Throwable) {
                failed++
                logThrottled("setColorTransform failed: " + t)
            }
        }
        try {
            applyTx!!.invoke(tx)
        } catch (t: Throwable) {
            logThrottled("apply() failed: " + t)
        }
        Xp.log(
            "$TAG: sdr dim factor=" + fmt(f) + " on " + dimmed + " window(s), skipped " +
                skipped + " hdr window(s), failed " + failed + " of " + windows.size
        )
    }

    /** 系数和窗口集合都没变时的低频心跳，便于确认链路还活着。 */
    private fun logStatus(f: Float, windows: List<Pair<Any, Boolean>>) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLogAt < 10000L) return
        lastLogAt = now
        Xp.log("$TAG: unchanged factor=" + fmt(f) + " windows=" + windows.size)
    }

    private fun ensureReflection(): Boolean {
        setColorTransform?.let { return true }
        return try {
            val scCls = Class.forName("android.view.SurfaceControl")
            val tx = Class.forName("android.view.SurfaceControl\$Transaction", false, scCls.classLoader)
            txClass = tx
            setColorTransform = tx.getMethod(
                "setColorTransform", scCls, FloatArray::class.java, FloatArray::class.java
            )
            applyTx = tx.getMethod("apply")
            true
        } catch (t: Throwable) {
            reflectFailed = true
            Xp.log("$TAG: SurfaceControl.Transaction#setColorTransform unavailable: " + t)
            false
        }
    }

    /**
     * 4x4 颜色矩阵：RGB 对角缩到 f、alpha 保持 1。
     * f 是亮度比（= 1/ratio），和 AOSP OutputLayer 里 dimmingRatio 的口径一致。
     * 内存布局按本机 native 的列主序排法写：本机把这 16 个 float 的前 9 个按「列主序 3x3」解释，
     * 下标 0,1,2 = 第 0 列（m00,m10,m20），3,4,5 = 第 1 列，6,7,8 = 第 2 列。按行主序填
     * （0,5,10）会把绿通道系数放到 m21、绿被压成 0（实测屏幕发紫），所以对角必须写在下标 0/4/8 上。
     */
    private fun matrixOf(f: Float): FloatArray = floatArrayOf(
        f, 0f, 0f,
        0f, f, 0f,
        0f, 0f, f,
        0f, 0f, 0f,
        0f, 0f, 0f,
        1f
    )

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)

    private fun logThrottled(msg: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLogAt < 5000L) return
        lastLogAt = now
        Xp.log("$TAG: " + msg)
    }
}
