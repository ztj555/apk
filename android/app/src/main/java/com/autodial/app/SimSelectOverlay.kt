package com.autodial.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.*

/**
 * 使用 WindowManager 悬浮窗实现选卡弹窗
 * 可从任何界面（包括桌面/其他 APP）弹出，需要 SYSTEM_ALERT_WINDOW 权限
 *
 * UI 风格：科技感深蓝渐变主题
 * - 深蓝色调背景 + 青蓝/金色点缀
 * - 卡按钮带色带标识，按下态发光
 * - 上次使用提示显示具体日期，跟在按钮右侧
 *
 * 参数说明：
 * - lastSimSlot / lastDialTime：该号码上次拨号的卡号和时间（来自系统通话记录）
 *   用于显示上方横幅和按钮后的日期提示
 */
object SimSelectOverlay {

    private const val TAG = "SimSelectOverlay"
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    // 自动消失（30秒无操作）
    private val autoDismissRunnable = Runnable { dismiss() }

    // 科技感深蓝色主题
    private val BG_CARD = "#101B30"
    private val BG_CARD_BORDER = "#1E3A5F"
    private val BG_SIM_BTN = "#142240"
    private val BG_SIM_BTN_PRESSED = "#1A2D52"
    private val BG_BANNER = "#0F1E38"
    private val BG_CANCEL = "#162840"
    private val GOLD = "#F0C040"
    private val CYAN = "#00D4AA"
    private val TEXT_PRIMARY = "#D4E4F7"
    private val TEXT_SUB = "#5A7A9A"
    private val TEXT_HINT_SAME = "#F0C040"
    private val DIVIDER = "#1A3050"
    private val BORDER_GLOW = "#1E3A5F"

    /**
     * 弹出选卡悬浮窗
     * @param context   Context
     * @param number    拨打的号码
     * @param lastSimSlot  该号码上次使用的卡槽（-1=无记录），来自系统通话记录
     * @param lastDialTime 该号码上次拨号时间戳
     */
    fun show(context: Context, number: String, lastSimSlot: Int, lastDialTime: Long) {
        handler.post {
            try {
                // 先移除已有的
                dismiss()
                windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

                val params = WindowManager.LayoutParams().apply {
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.WRAP_CONTENT
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    format = PixelFormat.TRANSLUCENT

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                    }

                    y = 0
                }

                overlayView = buildUI(context, number, lastSimSlot, lastDialTime)
                windowManager?.addView(overlayView, params)

                // 30秒后自动消失
                handler.postDelayed(autoDismissRunnable, 30000)

                Log.d(TAG, "选卡悬浮窗已显示 (lastSimSlot=$lastSimSlot, lastDialTime=$lastDialTime)")
            } catch (e: Exception) {
                Log.e(TAG, "显示悬浮窗失败: ${e.message}")
            }
        }
    }

    /**
     * 移除悬浮窗
     */
    fun dismiss() {
        handler.removeCallbacks(autoDismissRunnable)
        try {
            overlayView?.let {
                windowManager?.removeView(it)
            }
        } catch (_: Exception) {}
        overlayView = null
    }

    /**
     * 检查是否有悬浮窗权限
     */
    fun hasPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * 构建悬浮窗 UI
     *
     * 布局结构（科技感深蓝主题）：
     * ┌══════════════════════════════┐  ← 蓝色细边框
     * │     📞 19988886666           │  ← 号码（金色）
     * │  ┌─── 对比通话记录 ────────┐  │  ← 上方横幅（有历史时）
     * │  │▌ 上次：卡1  今天        │  │
     * │  └────────────────────────┘  │
     * │  ────────────────────────   │
     * │  SIM 1   1        04-26使用│  ← 按钮行（日期提示在右侧）
     * │  ────────────────────────   │
     * │  SIM 2   2                  │  ← 按钮行
     * │         [ 取消 ]            │
     * └══════════════════════════════┘
     */
    private fun buildUI(context: Context, number: String, lastSimSlot: Int, lastDialTime: Long): View {
        val dp = context.resources.displayMetrics.density

        // 计算上次拨号的日期显示文字
        val (displayDate, hasHistory) = if (lastSimSlot >= 0 && lastDialTime > 0) {
            val cal = Calendar.getInstance()
            val today = SimpleDateFormat("MM-dd", Locale.getDefault()).format(cal.time)
            cal.add(Calendar.DAY_OF_MONTH, -1)
            val yesterday = SimpleDateFormat("MM-dd", Locale.getDefault()).format(cal.time)
            val dateStr = SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(lastDialTime))
            val dateText = when (dateStr) {
                today -> "今天"
                yesterday -> "昨天"
                else -> dateStr
            }
            Pair(dateText, true)
        } else {
            Pair("", false)
        }

        // 外层容器
        val outerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
        }

        // 卡片背景（圆角 + 蓝色边框）
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardBackground(dp)
            setPadding((20 * dp).toInt(), (22 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt())
        }

        // ─── 号码显示 ───
        val numberRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val phoneIcon = TextView(context).apply {
            text = "📞 "
            textSize = 20f
        }
        numberRow.addView(phoneIcon)
        val numberText = TextView(context).apply {
            text = number
            setTextColor(Color.parseColor(GOLD))
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
        }
        numberRow.addView(numberText)
        card.addView(numberRow)

        // ─── 对比通话记录横幅 ───
        if (hasHistory) {
            val simColor = if (lastSimSlot == 0) GOLD else CYAN
            // 横幅文字：醒目颜色，卡1/卡2 字体放大
            // 用两个 TextView 分别设置样式

            val banner = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                background = createBannerBackground(dp)
                gravity = Gravity.CENTER_VERTICAL
                setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (14 * dp).toInt()
                }

                // 左侧竖条
                val indicator = View(context).apply {
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(simColor))
                        cornerRadius = 2 * dp
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        (4 * dp).toInt(),
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                }
                addView(indicator)

                // "上次：" 前缀
                val prefix = TextView(context).apply {
                    text = "  上次："
                    setTextColor(Color.parseColor(TEXT_PRIMARY))
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                }
                addView(prefix)

                // "卡X" 放大醒目
                val simLabel = TextView(context).apply {
                    text = "卡${lastSimSlot + 1}"
                    setTextColor(Color.parseColor(simColor))
                    textSize = 19f
                    typeface = Typeface.DEFAULT_BOLD
                }
                addView(simLabel)

                // 日期
                val dateLabel = TextView(context).apply {
                    text = "  $displayDate"
                    setTextColor(Color.parseColor(TEXT_SUB))
                    textSize = 14f
                }
                addView(dateLabel)
            }
            card.addView(banner)
        }

        // 分割线
        card.addView(createDivider(context, dp, 16, 14))

        // ─── 卡1 按钮 ───
        val sim1Hint = if (hasHistory && lastSimSlot == 0) displayDate else null
        card.addView(createSimButton(context, dp, 0, "SIM 1", GOLD, sim1Hint) {
            dialAndDismiss(context, number, 0)
        })

        // 间距
        card.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (6 * dp).toInt()
            )
        })

        // ─── 卡2 按钮 ───
        val sim2Hint = if (hasHistory && lastSimSlot == 1) displayDate else null
        card.addView(createSimButton(context, dp, 1, "SIM 2", CYAN, sim2Hint) {
            dialAndDismiss(context, number, 1)
        })

        // 取消按钮
        val cancelBtn = TextView(context).apply {
            text = "取消"
            setTextColor(Color.parseColor(TEXT_SUB))
            textSize = 15f
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
            background = createCancelBackground(dp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (46 * dp).toInt()
            ).apply {
                topMargin = (14 * dp).toInt()
            }
            setOnClickListener {
                val cancelIntent = Intent(context, DialService::class.java).apply {
                    action = "DIAL_CANCELLED"
                    putExtra("number", number)
                }
                context.startService(cancelIntent)
                dismiss()
            }
        }
        card.addView(cancelBtn)

        outerContainer.addView(card)
        return outerContainer
    }

    /**
     * 创建 SIM 卡选择按钮
     * @param dp density
     * @param simSlot 0=卡1, 1=卡2
     * @param label "SIM 1" / "SIM 2"
     * @param accentColor 主色调（金色/青色）
     * @param hintDate 提示日期文字（null=不显示），如 "今天"/"04-25"
     */
    private fun createSimButton(
        context: Context, dp: Float, simSlot: Int, label: String,
        accentColor: String, hintDate: String?, onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createSimButtonBackground(dp, accentColor)
            setPadding((18 * dp).toInt(), (16 * dp).toInt(), (18 * dp).toInt(), (16 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // SIM 标签
            val simLabel = TextView(context).apply {
                text = label
                setTextColor(Color.parseColor(accentColor))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.05f
            }
            addView(simLabel)

            // 大数字
            val numLabel = TextView(context).apply {
                text = (simSlot + 1).toString()
                setTextColor(Color.parseColor(accentColor))
                textSize = 28f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (6 * dp).toInt()
                    marginEnd = (12 * dp).toInt()
                }
            }
            addView(numLabel)

            // 弹簧
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })

            // 右侧日期提示（仅与上次相同时显示）
            if (hintDate != null) {
                val hintLabel = TextView(context).apply {
                    text = "$hintDate使用"
                    setTextColor(Color.parseColor(TEXT_HINT_SAME))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.02f
                    background = createHintBackground(dp, "#1A1A0E")
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                }
                addView(hintLabel)
            }

            setOnClickListener { onClick() }

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> v.background = createSimButtonBackground(dp, accentColor, pressed = true)
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> v.background = createSimButtonBackground(dp, accentColor)
                }
                false
            }
        }
    }

    // ==================== 绘制辅助 ====================

    /** 卡片背景：深蓝 + 蓝色细边框 */
    private fun createCardBackground(dp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(BG_CARD))
            cornerRadius = 16 * dp
            setStroke((1.5 * dp).toInt(), Color.parseColor(BORDER_GLOW))
        }
    }

    /** 横幅背景 */
    private fun createBannerBackground(dp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(BG_BANNER))
            cornerRadius = 8 * dp
        }
    }

    /** 卡按钮背景 */
    private fun createSimButtonBackground(dp: Float, accentColor: String, pressed: Boolean = false): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(if (pressed) BG_SIM_BTN_PRESSED else BG_SIM_BTN))
            cornerRadius = 12 * dp
            setStroke(1, Color.parseColor(if (pressed) accentColor else "#1E3050"))
        }
    }

    /** 取消按钮背景 */
    private fun createCancelBackground(dp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(BG_CANCEL))
            cornerRadius = 10 * dp
        }
    }

    /** 提示标签背景 */
    private fun createHintBackground(dp: Float, bgColor: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgColor))
            cornerRadius = 4 * dp
        }
    }

    /** 分割线 */
    private fun createDivider(context: Context, dp: Float, marginTopDp: Int, marginBottomDp: Int): View {
        return View(context).apply {
            setBackgroundColor(Color.parseColor(DIVIDER))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply {
                topMargin = (marginTopDp * dp).toInt()
                bottomMargin = (marginBottomDp * dp).toInt()
            }
        }
    }

    private fun dialAndDismiss(context: Context, number: String, simSlot: Int) {
        val intent = Intent(context, DialService::class.java).apply {
            action = "DIAL_WITH_SIM"
            putExtra("number", number)
            putExtra("sim_slot", simSlot)
        }
        context.startService(intent)
        dismiss()
    }
}
