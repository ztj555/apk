package com.autodial.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 透明全屏 Activity，用于在任何界面（包括桌面/其他 App）上弹出选卡卡片
 */
class SimSelectActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NUMBER = "number"
        const val EXTRA_LAST_SIM_SLOT = "last_sim_slot"
        const val EXTRA_LAST_DIAL_TIME = "last_dial_time"

        fun newIntent(context: Context, number: String, lastSimSlot: Int, lastDialTime: Long): Intent {
            return Intent(context, SimSelectActivity::class.java).apply {
                putExtra(EXTRA_NUMBER, number)
                putExtra(EXTRA_LAST_SIM_SLOT, lastSimSlot)
                putExtra(EXTRA_LAST_DIAL_TIME, lastDialTime)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置为透明全屏，从底部弹出
        window.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            attributes = attributes?.apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                // 让 Activity 布局在底部
            }
        }

        val number = intent.getStringExtra(EXTRA_NUMBER) ?: ""
        val lastSimSlot = intent.getIntExtra(EXTRA_LAST_SIM_SLOT, -1)
        val lastDialTime = intent.getLongExtra(EXTRA_LAST_DIAL_TIME, 0L)

        // 构建视图
        val root = buildUI(number, lastSimSlot, lastDialTime)
        setContentView(root)
    }

    private fun buildUI(number: String, lastSimSlot: Int, lastDialTime: Long): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1D24"))
            val dp = resources.displayMetrics.density
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt())
        }

        // 号码显示（不脱敏）
        val numberText = TextView(this).apply {
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
        root.addView(numberText)

        // 上次使用的卡提示（醒目样式）
        if (lastSimSlot >= 0 && lastDialTime > 0) {
            val cal = java.util.Calendar.getInstance()
            val today = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(cal.time)
            cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
            val yesterday = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(cal.time)
            val dateStr = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(java.util.Date(lastDialTime))
            val displayDate = when (dateStr) {
                today -> "今天"
                yesterday -> "昨天"
                else -> dateStr
            }

            val simColor = if (lastSimSlot == 0) "#C9A84C" else "#2ECC71"
            val hintText = "上次：卡${lastSimSlot + 1}  $displayDate"

            val lastHintText = TextView(this).apply {
                text = hintText
                setTextColor(Color.parseColor(simColor))
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (8 * resources.displayMetrics.density).toInt()
                }
            }
            root.addView(lastHintText)
        }

        // 分割线
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#2A2D34"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply {
                topMargin = (20 * resources.displayMetrics.density).toInt()
                bottomMargin = (16 * resources.displayMetrics.density).toInt()
            }
        }
        root.addView(divider)

        val dp = resources.displayMetrics.density

        // 卡1 按钮
        val btn1 = createSimButton(0, "SIM 1", "#C9A84C") {
            dialAndFinish(number, 0)
        }
        root.addView(btn1)

        // 间距
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (10 * dp).toInt()
            )
        }
        root.addView(spacer)

        // 卡2 按钮
        val btn2 = createSimButton(1, "SIM 2", "#2ECC71") {
            dialAndFinish(number, 1)
        }
        root.addView(btn2)

        // 取消按钮
        val cancelBtn = TextView(this).apply {
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
                val cancelIntent = Intent(this@SimSelectActivity, DialService::class.java).apply {
                    action = "DIAL_CANCELLED"
                    putExtra("number", number)
                }
                startService(cancelIntent)
                finish()
            }
        }
        root.addView(cancelBtn)

        return root
    }

    private fun createSimButton(simSlot: Int, label: String, color: String, onClick: () -> Unit): LinearLayout {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#22252E"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (64 * dp).toInt()
            )

            val simLabel = TextView(this@SimSelectActivity).apply {
                text = label
                setTextColor(Color.parseColor(color))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(simLabel)

            val numLabel = TextView(this@SimSelectActivity).apply {
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

            // spacer
            val spacer = View(this@SimSelectActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            }
            addView(spacer)

            val actionLabel = TextView(this@SimSelectActivity).apply {
                text = "使用卡${simSlot + 1}拨打 →"
                setTextColor(Color.parseColor("#A09070"))
                textSize = 16f
            }
            addView(actionLabel)

            setOnClickListener { onClick() }

            // 按压效果
            setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> v.setBackgroundColor(Color.parseColor("#2E323C"))
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> v.setBackgroundColor(Color.parseColor("#22252E"))
                }
                false
            }
        }
    }

    private fun dialAndFinish(number: String, simSlot: Int) {
        val intent = Intent(this, DialService::class.java).apply {
            action = "DIAL_WITH_SIM"
            putExtra("number", number)
            putExtra("sim_slot", simSlot)
        }
        startService(intent)
        finish()
    }

    // 点击外部区域关闭
    override fun onBackPressed() {
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: ""
        val cancelIntent = Intent(this, DialService::class.java).apply {
            action = "DIAL_CANCELLED"
            putExtra("number", number)
        }
        startService(cancelIntent)
        super.onBackPressed()
    }
}
