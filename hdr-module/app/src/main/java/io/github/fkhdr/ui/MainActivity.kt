package io.github.fkhdr.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import io.github.fkhdr.Config
import io.github.fkhdr.R

/**
 * FkHDR 主界面。
 *
 * 刻意只用手写 Android SDK View：不引入 Compose、不引入 androidx，模块 APK 只有几十 KB，
 * 也避免任何三方依赖被注入到 system_server 时带来的类冲突风险。
 */
class MainActivity : Activity() {

    private lateinit var hdrSwitch: Switch
    private lateinit var statusText: TextView
    private lateinit var capValue: TextView
    private lateinit var capSeek: SeekBar

    /** 程序化回写开关状态时置位，避免触发监听器造成回环写入 */
    private var binding = false

    /** SeekBar 拖动中的待持久化值；只在手指松开时写一次，避免每个 tick 同步 IO / su。 */
    private var pendingCapValue: Float? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.app_name)
        setContentView(buildContentView())
        applyState(Config.isHdrFixEnabled())
    }

    override fun onResume() {
        super.onResume()
        if (::hdrSwitch.isInitialized) applyState(Config.isHdrFixEnabled())
        // 自愈：模块未激活时写 Settings.System 会被拒（无 WRITE_SETTINGS / 无 root），
        // 值只落到本地 prefs。等模块激活（SettingsProviderHook 生效）后，
        // 每次回到界面都把本地值补写回 Settings.System，保证 system_server 读得到。
        if (::hdrSwitch.isInitialized) {
            val switchState = hdrSwitch.isChecked
            Thread {
                val cap = Config.getRatioMax()
                val switchOk = Config.writeConfig(applicationContext, Config.KEY_HDR_RATIO, switchState)
                val capOk = Config.setRatioMax(applicationContext, cap)
                runOnUiThread {
                    if (!switchOk) {
                        verifyIntSetting(Config.KEY_HDR_RATIO, if (switchState) 1 else 0, "HDR 提亮开关")
                    }
                    if (!capOk) {
                        verifyFloatSetting(Config.KEY_RATIO_MAX, cap, "比值封顶上限")
                    }
                }
            }.start()
        }
    }

    /**
     * 写入失败后延迟回读：Config 的 su 兜底是异步的，不能只看 putInt/putFloat 的返回值就报错。
     * 若 1.5s 后 Settings.System 仍不是预期值，明确提示用户模块作用域或 root 未生效。
     */
    private fun verifyIntSetting(key: String, expected: Int, label: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (Config.getSettingInt(key) == expected) return@postDelayed
            showWriteFailure(label)
        }, 1500L)
    }

    private fun verifyFloatSetting(key: String, expected: Float, label: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val actual = Config.getSettingFloat(key, Float.NaN)
            if (!actual.isNaN() && Math.abs(actual - expected) < 0.01f) return@postDelayed
            showWriteFailure(label)
        }, 1500L)
    }

    private fun showWriteFailure(label: String) {
        android.widget.Toast.makeText(
            applicationContext,
            "「" + label + "」未能写入系统设置。请在 LSPosed 中为本模块勾选" +
                "「设置存储 (com.android.providers.settings)」作用域，或授予本应用 root 权限后重试。",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    private fun persistHdrSwitch(isChecked: Boolean) {
        Thread {
            val ok = Config.writeConfig(applicationContext, Config.KEY_HDR_RATIO, isChecked)
            runOnUiThread {
                if (!ok) {
                    verifyIntSetting(Config.KEY_HDR_RATIO, if (isChecked) 1 else 0, "HDR 提亮开关")
                }
            }
        }.start()
    }

    private fun persistRatioMax(value: Float, switchState: Boolean) {
        Thread {
            val capOk = Config.setRatioMax(applicationContext, value)
            val switchOk = Config.writeConfig(applicationContext, Config.KEY_HDR_RATIO, switchState)
            runOnUiThread {
                if (!capOk) verifyFloatSetting(Config.KEY_RATIO_MAX, value, "比值封顶上限")
                if (!switchOk) {
                    verifyIntSetting(Config.KEY_HDR_RATIO, if (switchState) 1 else 0, "HDR 提亮开关")
                }
            }
        }.start()
    }

    // ── UI 构建 ─────────────────────────────────────────────

    private fun buildContentView(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(32))
        }
        scroll.addView(root, ViewGroup.LayoutParams(MATCH, WRAP))

        root.addView(text(getString(R.string.app_name) + " · HDR 提亮修复", 22f, TEXT, true))
        root.addView(text("独立 LSPosed 模块 · 不依赖 FkOScreen", 13f, MUTED).withTop(dp(6)))

        // 开关卡片
        val switchCard = card().withTop(dp(20))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(
            text("HDR 提亮修复", 16f, TEXT, true),
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        hdrSwitch = Switch(this).apply {
            isChecked = false
            setOnCheckedChangeListener { _, isChecked ->
                if (binding) return@setOnCheckedChangeListener
                applyState(isChecked)
                persistHdrSwitch(isChecked)
            }
        }
        row.addView(hdrSwitch, LinearLayout.LayoutParams(WRAP, WRAP))
        switchCard.addView(row)
        statusText = text("", 13f, MUTED).withTop(dp(8))
        switchCard.addView(statusText)
        root.addView(switchCard)

        // 比值封顶上限卡片
        val capCard = card().withTop(dp(12))
        capCard.addView(sectionTitle("比值封顶上限"))
        val capRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        capValue = text("", 20f, TEXT, true)
        capRow.addView(capValue, LinearLayout.LayoutParams(WRAP, WRAP))
        capRow.addView(
            text("×  (HDR 峰值 / SDR 白点)", 12f, MUTED),
            LinearLayout.LayoutParams(0, WRAP, 1f).also { it.leftMargin = dp(8) }
        )
        capCard.addView(capRow)
        capSeek = SeekBar(this).apply {
            max = ((Config.MAX_RATIO_MAX - Config.MIN_RATIO_MAX) * 2f).toInt()
            progress = ((Config.getRatioMax() - Config.MIN_RATIO_MAX) * 2f).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = Config.MIN_RATIO_MAX + progress / 2f
                    capValue.text = String.format("%.1f", v)
                    if (fromUser) pendingCapValue = v
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val v = pendingCapValue ?: return
                    pendingCapValue = null
                    persistRatioMax(v, hdrSwitch.isChecked)
                }
            })
        }
        capValue.text = String.format("%.1f", Config.getRatioMax())
        capCard.addView(capSeek.withTop(dp(4)))
        capCard.addView(
            text(
                "低亮度时 HDR 高光最多比 SDR 白点亮这么多倍；亮度越高上限自动下降，" +
                    "拉到最亮时回落到 1.0（无 headroom）。松手后约 1 秒生效，不用重启。" +
                    "默认 5.0，与旧版配置模块一致。",
                13f, BODY
            ).withTop(dp(6))
        )
        root.addView(capCard)

        // 作用
        val effect = card().withTop(dp(12))
        effect.addView(sectionTitle("作用"))
        effect.addView(
            text(
                "HDR 内容出现时，按「SDR 白点 × 上面的封顶上限」把面板亮度抬上去，" +
                    "并让系统上报真实的 HDR/SDR 比值；SDR 白点本身不变，退出 HDR 后自动回落。",
                14f, BODY
            ).withTop(dp(8))
        )
        root.addView(effect)

        // 支持系统
        val systems = card().withTop(dp(12))
        systems.addView(sectionTitle("支持系统"))
        systems.addView(
            text(
                "ColorOS 16 / 17（realme UI、OxygenOS 等同源机型）；" +
                    "类原生 AOSP / LineageOS / Pixel Experience 等。",
                14f, BODY
            ).withTop(dp(8))
        )
        root.addView(systems)

        // 生效范围
        val scope = card().withTop(dp(12))
        scope.addView(sectionTitle("生效范围"))
        scope.addView(
            text(
                "作用域写在 APK 的 META-INF/xposed/scope.list 里：system_server 那条是「系统框架 / system」，" +
                    "另加「设置存储 com.android.providers.settings」。LSPosed 里启用模块即生效，不用手动勾选；" +
                    "若管理器里仍显示旧的行（android），禁用再启用一次模块让它重写配置。",
                14f, BODY
            ).withTop(dp(8))
        )
        root.addView(scope)

        // 提示
        val tips = card().withTop(dp(12))
        tips.addView(sectionTitle("注意"))
        tips.addView(
            text(
                "开关改动后需重启 system_server 或重启设备才会完整生效；" +
                    "若当前正在播放 HDR 内容，切换后会立即重新计算一次亮度。",
                14f, BODY
            ).withTop(dp(8))
        )
        root.addView(tips)

        root.addView(
            text(versionLabel(), 12f, FAINT)
                .withTop(dp(22))
                .also { it.gravity = android.view.Gravity.CENTER_HORIZONTAL }
        )

        return scroll
    }

    private fun applyState(enabled: Boolean) {
        binding = true
        hdrSwitch.isChecked = enabled
        binding = false
        statusText.text = if (enabled) {
            "已开启 · HDR 内容出现时自动提亮面板"
        } else {
            "已关闭 · 保持系统原始亮度行为"
        }
        statusText.setTextColor(if (enabled) ENABLED else MUTED)
    }

    // ── 小工具 ──────────────────────────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun text(t: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = t
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(3).toFloat(), 1.0f)
        }

    private fun sectionTitle(t: CharSequence): TextView = text(t, 13f, HEADING, true)

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.WHITE)
            setStroke(dp(1), BORDER)
        }
    }

    private fun <T : View> T.withTop(margin: Int): T {
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = margin
        layoutParams = lp
        return this
    }

    @Suppress("DEPRECATION")
    private fun versionLabel(): String {
        val info = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (_: Throwable) {
            null
        }
        val name = info?.versionName ?: "1.0.0"
        val code = info?.versionCode ?: 1
        return "FkHDR v" + name + " (" + code + ")"
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        val BG = Color.parseColor("#FFF4F5F7")
        val BORDER = Color.parseColor("#FFE3E5E9")
        val TEXT = Color.parseColor("#FF111827")
        val HEADING = Color.parseColor("#FF374151")
        val BODY = Color.parseColor("#FF4B5563")
        val MUTED = Color.parseColor("#FF8A94A6")
        val FAINT = Color.parseColor("#FFAAB2BF")
        val ENABLED = Color.parseColor("#FF16A34A")
    }
}
