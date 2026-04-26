package com.autodial.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
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
 * 参数说明：
 * - lastSimSlot / lastDialTime：该号码上次拨号的卡号和时间（来自 sim_cache / APP 拨号记录）
 *   用于显示上方横幅和卡按钮后的"同上次"提示
 */
object SimSelectOverlay {

    private const val TAG = "SimSelectOverlay"
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    // 自动消失（30秒无操作）
    private val autoDismissRunnable = Runnable { dismiss() }

    /**
     * 弹出选卡悬浮窗
     * @param context   Context
     * @param number    拨打的号码
     * @param lastSimSlot  该号码上次使用的卡槽（-1=无记录），来自 sim_cache/系统通话记录
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

                Log.d(TAG, "选卡悬浮窗已显示")
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
     * 布局结构：
     * ┌─────────────────────────────┐
     * │  📞 19988886666             │  ← 号码
     * │  ┌─ 对比通话记录横幅 ─────┐  │  ← 上方横幅（有历史时显示）
     * │  │ 上次：卡1  今天        │  │
     * │  └────────────────────────┘  │
     * │  ─────────────────────────  │
     * │  SIM 1  1   使用卡1拨打 →  │
     * │          同上次拨打         │  ← 按钮后追加提示
     * │  ─────────────────────────  │
     * │  SIM 2  2   使用卡2拨打 →  │
     * │          推荐切换卡2        │  ← 按钮后追加提示
     * │  ─────────────────────────  │
     * │         [ 取消 ]            │
     * └─────────────────────────────┘
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

        // 底部卡片区域
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1D24"))
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (28 * dp).toInt())
        }

        // ─── 号码显示 ───
        val numberText = TextView(context).apply {
            text = number
            setTextColor(Color.parseColor("#E8DCC8"))
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        card.addView(numberText)

        // ─── 对比通话记录横幅（弹窗上方，模拟金色横幅） ───
        if (hasHistory) {
            val simColor = if (lastSimSlot == 0) "#C9A84C" else "#2ECC71"
            val bannerText = "上次使用：卡${lastSimSlot + 1}  $displayDate"

            val banner = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#2A2D34"))
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (12 * dp).toInt()
                }

                // 左侧金色竖条
                val indicator = View(context).apply {
                    setBackgroundColor(Color.parseColor("#C9A84C"))
                    layoutParams = LinearLayout.LayoutParams(
                        (3 * dp).toInt(),
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                }
                addView(indicator)

                val label = TextView(context).apply {
                    text = "  $bannerText"
                    setTextColor(Color.parseColor(simColor))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                }
                addView(label)
            }
            card.addView(banner)
        }

        // 分割线
        card.addView(View(context).apply {
            setBackgroundColor(Color.parseColor("#2A2D34"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply {
                topMargin = (16 * dp).toInt()
                bottomMargin = (12 * dp).toInt()
            }
        })

        // ─── 卡1 按钮 ───
        val sim1Hint = if (hasHistory) {
            if (lastSimSlot == 0) "  同上次拨打" else "  推荐切换卡1"
        } else null
        card.addView(createSimButton(context, 0, "SIM 1", "#C9A84C", sim1Hint) {
            dialAndDismiss(context, number, 0)
        })

        // 间距
        card.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (8 * dp).toInt()
            )
        })

        // ─── 卡2 按钮 ───
        val sim2Hint = if (hasHistory) {
            if (lastSimSlot == 1) "  同上次拨打" else "  推荐切换卡2"
        } else null
        card.addView(createSimButton(context, 1, "SIM 2", "#2ECC71", sim2Hint) {
            dialAndDismiss(context, number, 1)
        })

        // 取消按钮
        val cancelBtn = TextView(context).apply {
            text = "取消"
            setTextColor(Color.parseColor("#A09070"))
            textSize = 15f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#2A2D34"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (48 * dp).toInt()
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
     * @param hint 按钮右侧追加的提示文字（如"同上次拨打"/"推荐切换卡2"），null 则不追加
     */
    private fun createSimButton(context: Context, simSlot: Int, label: String, color: String, hint: String?, onClick: () -> Unit): LinearLayout {
        val dp = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#22252E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // 主按钮行
            val mainRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((20 * dp).toInt(), (14 * dp).toInt(), (20 * dp).toInt(), (6 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val simLabel = TextView(context).apply {
                text = label
                setTextColor(Color.parseColor(color))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            }
            mainRow.addView(simLabel)

            val numLabel = TextView(context).apply {
                text = (simSlot + 1).toString()
                setTextColor(Color.parseColor(color))
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (4 * dp).toInt()
                }
            }
            mainRow.addView(numLabel)

            mainRow.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })

            val actionLabel = TextView(context).apply {
                text = "使用卡${simSlot + 1}拨打 →"
                setTextColor(Color.parseColor("#A09070"))
                textSize = 16f
            }
            mainRow.addView(actionLabel)
            addView(mainRow)

            // 提示行（如有）
            if (hint != null) {
                val hintRow = TextView(context).apply {
                    text = hint
                    setTextColor(Color.parseColor("#888888"))
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        // 和主行左侧对齐
                        marginStart = (20 * dp).toInt()
                        bottomMargin = (8 * dp).toInt()
                    }
                }
                addView(hintRow)
            }

            setOnClickListener { onClick() }

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> v.setBackgroundColor(Color.parseColor("#2E323C"))
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> v.setBackgroundColor(Color.parseColor("#22252E"))
                }
                false
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
