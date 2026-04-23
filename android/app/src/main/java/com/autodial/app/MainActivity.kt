package com.autodial.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: ImageView
    private lateinit var statusText: TextView
    private lateinit var connectionBanner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var ipInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var connectBtn: View

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val connected = intent?.getBooleanExtra("connected", false) ?: return
            val reason = intent.getStringExtra("reason")
            updateConnectionUI(connected, reason)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        connectionBanner = findViewById(R.id.connectionBanner)
        bannerText = findViewById(R.id.bannerText)
        ipInput = findViewById(R.id.ipInput)
        pinInput = findViewById(R.id.pinInput)
        connectBtn = findViewById(R.id.connectBtn)

        connectBtn.setOnClickListener { toggleConnection() }

        // 读取保存的连接信息
        val prefs = getSharedPreferences("autodial", MODE_PRIVATE)
        ipInput.setText(prefs.getString("ip", ""))
        pinInput.setText(prefs.getString("pin", ""))

        // 启动服务
        startService(DialService.newIntent(this))

        // 注册广播
        ContextCompat.registerReceiver(this, receiver, IntentFilter("com.autodial.CONNECTION_CHANGE"), ContextCompat.RECEIVER_NOT_EXPORTED)

        // 检查当前连接状态
        updateConnectionUI(DialService.isConnected, null)
    }

    override fun onResume() {
        super.onResume()
        updateConnectionUI(DialService.isConnected, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    private fun toggleConnection() {
        if (DialService.isConnected) {
            // 断开
            AlertDialog.Builder(this)
                .setTitle("断开连接")
                .setMessage("确定断开与电脑的连接？")
                .setPositiveButton("断开") { _, _ ->
                    sendDisconnectCommand()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            // 连接
            val ip = ipInput.text.toString().trim()
            val pin = pinInput.text.toString().trim()

            if (ip.isEmpty()) {
                Toast.makeText(this, "请输入电脑IP地址", Toast.LENGTH_SHORT).show()
                return
            }
            if (pin.length != 4) {
                Toast.makeText(this, "请输入4位配对码", Toast.LENGTH_SHORT).show()
                return
            }

            // 禁用输入，等待连接结果
            ipInput.isEnabled = false
            pinInput.isEnabled = false
            connectBtn.isEnabled = false
            statusText.text = "正在连接..."
            statusText.setTextColor(Color.parseColor("#F0C040"))
            statusDot.setImageResource(R.drawable.dot_gray)

            val intent = Intent(this, DialService::class.java).apply {
                action = "CONNECT"
                putExtra("ip", ip)
                putExtra("pin", pin)
            }
            startService(intent)
        }
    }

    private fun sendDisconnectCommand() {
        val intent = Intent(this, DialService::class.java).apply {
            action = "DISCONNECT"
        }
        startService(intent)
        updateConnectionUI(false, null)
    }

    private fun updateConnectionUI(connected: Boolean, reason: String?) {
        ipInput.isEnabled = !connected
        pinInput.isEnabled = !connected
        connectBtn.isEnabled = true

        if (connected) {
            // === 连接成功 ===
            statusDot.setImageResource(R.drawable.dot_green)
            statusText.text = "已连接"
            statusText.setTextColor(Color.parseColor("#2ECC71"))
            connectionBanner.visibility = View.VISIBLE
            bannerText.text = "✅ 已连接到电脑！等待拨号指令..."

            // 震动提示
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(300)
                }
            }

            val connectTextView = findViewById<TextView>(R.id.connectBtnText)
            connectTextView.text = "断开连接"
            val connectBtnLayout = findViewById<LinearLayout>(R.id.connectBtn)
            connectBtnLayout.setBackgroundColor(Color.parseColor("#E74C3C"))

        } else {
            // === 断开连接 ===
            statusDot.setImageResource(R.drawable.dot_gray)
            connectionBanner.visibility = View.GONE

            val connectBtnLayout = findViewById<LinearLayout>(R.id.connectBtn)
            connectBtnLayout.setBackgroundColor(Color.parseColor("#C9A84C"))
            val connectTextView = findViewById<TextView>(R.id.connectBtnText)
            connectTextView.text = "连接"

            when (reason) {
                "pin_wrong" -> {
                    statusText.text = "配对码错误"
                    statusText.setTextColor(Color.parseColor("#E74C3C"))
                    Toast.makeText(this, "配对码不正确，请重新输入！", Toast.LENGTH_LONG).show()
                }
                "kicked" -> {
                    statusText.text = "已被踢下线"
                    statusText.setTextColor(Color.parseColor("#E74C3C"))
                    Toast.makeText(this, "有其他手机连接了该电脑", Toast.LENGTH_LONG).show()
                }
                else -> {
                    statusText.text = "未连接"
                    statusText.setTextColor(Color.parseColor("#A09070"))
                }
            }
        }
    }
}
