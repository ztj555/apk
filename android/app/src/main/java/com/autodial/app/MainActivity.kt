package com.autodial.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import kotlinx.coroutines.*

/**
 * 主界面：服务器配置、配对管理、连接状态
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusDot: ImageView
    private lateinit var statusText: TextView
    private lateinit var serverInput: EditText
    private lateinit var connectBtn: Button
    private lateinit var pairCodeText: TextView
    private lateinit var generatePairBtn: Button
    private lateinit var unpairBtn: Button
    private lateinit var pairStatusText: TextView

    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 状态栏透明
        window.statusBarColor = getColor(R.color.bg_primary)

        prefs = getSharedPreferences("autodial", Context.MODE_PRIVATE)

        initViews()
        checkPermissions()
        loadServerConfig()
        observeServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun initViews() {
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        serverInput = findViewById(R.id.serverInput)
        connectBtn = findViewById(R.id.connectBtn)
        pairCodeText = findViewById(R.id.pairCodeText)
        generatePairBtn = findViewById(R.id.generatePairBtn)
        unpairBtn = findViewById(R.id.unpairBtn)
        pairStatusText = findViewById(R.id.pairStatusText)
        logText = findViewById(R.id.logText)

        connectBtn.setOnClickListener { connectToServer() }
        generatePairBtn.setOnClickListener { generatePairCode() }
        unpairBtn.setOnClickListener { unpair() }
    }

    private fun loadServerConfig() {
        val url = prefs.getString("server_url", "") ?: ""
        serverInput.setText(url)
    }

    private fun observeServiceStatus() {
        DialService.onStatusChanged = { connected, paired, code, msg, online ->
            runOnUiThread { refreshStatus() }
        }
    }

    private fun refreshStatus() {
        val connected = DialService.isConnected
        val paired = DialService.isPaired
        val code = DialService.pairCode
        val online = DialService.phoneOnline

        // 状态指示
        statusDot.setImageResource(
            if (connected) R.drawable.dot_green else R.drawable.dot_gray
        )
        statusText.text = DialService.statusMessage

        // 连接按钮
        connectBtn.text = if (connected) "已连接" else "连接"
        connectBtn.isEnabled = !connected

        // 配对区域
        if (paired) {
            pairCodeText.text = "配对码: $code"
            pairStatusText.text = if (online) "✅ 电脑在线" else "⚠️ 电脑离线"
            generatePairBtn.visibility = Button.GONE
            unpairBtn.visibility = Button.VISIBLE
        } else if (connected && code != null) {
            pairCodeText.text = "配对码: $code"
            pairStatusText.text = "等待电脑输入配对码..."
            generatePairBtn.visibility = Button.VISIBLE
            unpairBtn.visibility = Button.GONE
        } else {
            pairCodeText.text = "未生成配对码"
            pairStatusText.text = if (connected) "点击下方按钮生成配对码" else "请先连接服务器"
            generatePairBtn.visibility = if (connected) Button.VISIBLE else Button.GONE
            unpairBtn.visibility = Button.GONE
        }
    }

    private fun connectToServer() {
        val url = serverInput.text.toString().trim()
        if (url.isEmpty()) {
            toast("请输入服务器地址")
            return
        }

        // 保存配置
        prefs.edit().putString("server_url", url).apply()

        // 启动服务
        val intent = Intent(this, DialService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        toast("正在连接...")
    }

    private fun generatePairCode() {
        // 通过服务生成配对码（这里直接发消息给服务）
        // 简化处理：通过广播发送
        val intent = Intent("com.autodial.ACTION_REQUEST_PAIR")
        sendBroadcast(intent)

        // 实际通过 Service 静态方法
        toast("正在生成配对码...")
        CoroutineScope(Dispatchers.IO).launch {
            // 等待服务处理
            delay(1000)
            runOnUiThread { refreshStatus() }
        }
    }

    private fun unpair() {
        AlertDialog.Builder(this)
            .setTitle("确认解绑")
            .setMessage("解绑后需要重新配对才能使用拨号功能，确定解绑吗？")
            .setPositiveButton("解绑") { _, _ ->
                // 通过广播通知服务解绑
                val intent = Intent("com.autodial.ACTION_UNPAIR")
                sendBroadcast(intent)
                Toast.makeText(this, "已发送解绑请求", Toast.LENGTH_SHORT).show()
                CoroutineScope(Dispatchers.IO).launch {
                    delay(1000)
                    runOnUiThread { refreshStatus() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 权限管理 ====================

    private fun checkPermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ANSWER_PHONE_CALLS)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // CALL_PHONE 权限
        if (checkSelfPermission(Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CALL_PHONE)
        }

        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                toast("⚠️ 部分权限未授予，可能影响拨号功能")
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
