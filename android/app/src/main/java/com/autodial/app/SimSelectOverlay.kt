package com.autodial.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telephony.SubscriptionManager
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
 * 静态管理，由 DialService 或 MainActivity 直接调用
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

                    // 底部留出导航栏空间
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
     */
    private fun buildUI(context: Context, number: String, lastSimSlot: Int, lastDialTime: Long): View {
        val dp = context.resources.displayMetrics.density

        // 外层容器（半透明背景遮罩 + 底部卡片）
        val outerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
        }

        // 底部卡片区域
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1D24"))
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (28 * dp).toInt())
            // 圆角效果通过背景色模拟，悬浮窗不好用 shape，直接用纯色
        }

        // 号码显示
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

        // 上次使用的卡提示
        if (lastSimSlot >= 0 && lastDialTime > 0) {
            val cal = Calendar.getInstance()
            val today = SimpleDateFormat("MM-dd", Locale.getDefault()).format(cal.time)
            cal.add(Calendar.DAY_OF_MONTH, -1)
            val yesterday = SimpleDateFormat("MM-dd", Locale.getDefault()).format(cal.time)
            val dateStr = SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(lastDialTime))
            val displayDate = when (dateStr) {
                today -> "今天"
                yesterday -> "昨天"
                else -> dateStr
            }

            val simColor = if (lastSimSlot == 0) "#C9A84C" else "#2ECC71"
            val hintText = "上次：卡${lastSimSlot + 1}  $displayDate"

            val lastHintText = TextView(context).apply {
                text = hintText
                setTextColor(Color.parseColor(simColor))
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (8 * dp).toInt()
                }
            }
            card.addView(lastHintText)
        }

        // 分割线
        val divider = View(context).apply {
            setBackgroundColor(Color.parseColor("#2A2D34"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply {
                topMargin = (20 * dp).toInt()
                bottomMargin = (16 * dp).toInt()
            }
        }
        card.addView(divider)

        // 卡1 按钮
        card.addView(createSimButton(context, 0, "SIM 1", "#C9A84C") {
            dialAndDismiss(context, number, 0)
        })

        // 间距
        card.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (10 * dp).toInt()
            )
        })

        // 卡2 按钮
        card.addView(createSimButton(context, 1, "SIM 2", "#2ECC71") {
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
                // 通知 DialService 取消
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

    private fun createSimButton(context: Context, simSlot: Int, label: String, color: String, onClick: () -> Unit): LinearLayout {
        val dp = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#22252E"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (64 * dp).toInt()
            )

            val simLabel = TextView(context).apply {
                text = label
                setTextColor(Color.parseColor(color))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(simLabel)

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
            addView(numLabel)

            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })

            val actionLabel = TextView(context).apply {
                text = "使用卡${simSlot + 1}拨打 →"
                setTextColor(Color.parseColor("#A09070"))
                textSize = 16f
            }
            addView(actionLabel)

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
