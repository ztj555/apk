package com.autodial.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 短信确认界面 - 透明全屏 Activity
 * PC 端发送短信请求后弹出，用户确认后发送短信
 */
class SmsConfirmActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SmsConfirm"
        private const val REQ_SMS = 201
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 透明全屏
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setGravity(Gravity.CENTER)

        val number = intent.getStringExtra("number") ?: ""
        val content = intent.getStringExtra("content") ?: ""

        // 构建界面
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            // 点击空白区域关闭
            setOnClickListener { finish() }
        }

        val card = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 36, 40, 32)
            background = getDrawable(R.drawable.sms_confirm_bg) ?: android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1A1D24.toInt())
                cornerRadius = 40f
                setStroke(2, 0xFF8B6914.toInt())
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                width = (resources.displayMetrics.widthPixels * 0.85).toInt()
            }
            // 阻止点击穿透
            setOnClickListener { }
        }

        // 标题
        val title = TextView(this).apply {
            text = "\uD83D\uDCAC \u53D1\u77ED\u4FE1"
            textSize = 20f
            setTextColor(0xFFF0C040.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        card.addView(title)

        // 收件人
        val recipientLabel = TextView(this).apply {
            text = "\u6536\u4EF6\u4EBA"
            textSize = 11f
            setTextColor(0xFFA09070.toInt())
            setPadding(0, 0, 0, 4)
        }
        card.addView(recipientLabel)

        val recipientText = TextView(this).apply {
            text = number
            textSize = 22f
            setTextColor(0xFFE8DCC8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.05f
        }
        card.addView(recipientText)

        // 短信内容
        val contentLabel = TextView(this).apply {
            text = "\u77ED\u4FE1\u5185\u5BB9"
            textSize = 11f
            setTextColor(0xFFA09070.toInt())
            setPadding(0, 0, 0, 4)
        }
        card.addView(contentLabel)

        val contentInput = EditText(this).apply {
            setText(content)
            textSize = 16f
            setTextColor(0xFFE8DCC8.toInt())
            setHintTextColor(0xFFA09070.toInt())
            hint = "\u8BF7\u8F93\u5165\u77ED\u4FE1\u5185\u5BB9..."
            setPadding(20, 16, 20, 16)
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF22262F.toInt())
                cornerRadius = 16f
                setStroke(1, 0xFF333333.toInt())
            }
            setPadding(20, 16, 20, 16)
        }
        card.addView(contentInput)

        // 按钮行
        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 0)
        }

        val cancelBtn = Button(this).apply {
            text = "\u53D6\u6D88"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF444444.toInt())
                cornerRadius = 28f
            }
            setPadding(0, 14, 0, 14)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
            setOnClickListener {
                DialService.sendSmsResult(number, "cancelled")
                finish()
            }
        }
        btnRow.addView(cancelBtn)

        val sendBtn = Button(this).apply {
            text = "\u53D1\u9001"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFC9A84C.toInt())
                cornerRadius = 28f
            }
            setPadding(0, 14, 0, 14)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8
            }
            setOnClickListener {
                val msg = contentInput.text.toString().trim()
                if (msg.isEmpty()) {
                    Toast.makeText(this@SmsConfirmActivity, "\u77ED\u4FE1\u5185\u5BB9\u4E0D\u80FD\u4E3A\u7A7A", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                sendSms(number, msg)
                finish()
            }
        }
        btnRow.addView(sendBtn)
        card.addView(btnRow)
        root.addView(card)
        setContentView(root)
    }

    private fun sendSms(number: String, content: String) {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), REQ_SMS)
                DialService.sendSmsResult(number, "error")
                Toast.makeText(this, "\u7F3A\u5C11\u53D1\u9001\u77ED\u4FE1\u6743\u9650", Toast.LENGTH_SHORT).show()
                return
            }
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            // 分段发送长短信
            val parts = smsManager.divideMessage(content)
            if (parts.size == 1) {
                smsManager.sendTextMessage(number, null, content, null, null)
            } else {
                smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            }
            Log.d(TAG, "短信已发送: $number, 长度=${content.length}")
            DialService.sendSmsResult(number, "sent")
        } catch (e: Exception) {
            Log.e(TAG, "发送短信失败: ${e.message}")
            DialService.sendSmsResult(number, "error")
            Toast.makeText(this, "\u53D1\u9001\u5931\u8D25: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SMS && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "\u6743\u9650\u5DF2\u6388\u4E88\uFF0C\u8BF7\u5728\u7535\u8111\u7AEF\u91CD\u8BD5", Toast.LENGTH_SHORT).show()
        }
    }
}
