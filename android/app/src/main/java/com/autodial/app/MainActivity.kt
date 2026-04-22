package com.autodial.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        connectBtn.setOnClickListener { connectToServer() }
        generatePairBtn.setOnClickListener { generatePairCode() }
        unpairBtn.setOnClickListener { unpair() }
    }

    private fun loadServerConfig() {
        val url = prefs.getString("server_url", "") ?: ""
        serverInput.setText(url)
    }

    private fun observeServiceStatus() {
        DialService.onStatusChanged = { _, _, _, _, _ ->
            runOnUiThread { refreshStatus() }
        }
    }

    private fun refreshStatus() {
        val connected = DialService.isConnected
        val paired = DialService.isPaired
        val code = DialService.pairCode
        val online = DialService.phoneOnline

        statusDot.setImageResource(
            if (connected) R.drawable.dot_green else R.drawable.dot_gray
        )
        statusText.text = DialService.statusMessage

        connectBtn.text = if (connected) "已连接" else "连接"
        connectBtn.isEnabled = !connected

        if (paired) {
            pairCodeText.text = "配对码: $code"
            pairStatusText.text = if (online) "电脑在线" else "电脑离线"
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

        prefs.edit().putString("server_url", url).apply()

        val intent = Intent(this, DialService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        toast("正在连接...")
    }

    private fun generatePairCode() {
        val intent = Intent("com.autodial.ACTION_REQUEST_PAIR")
        sendBroadcast(intent)
        toast("正在生成配对码...")
        CoroutineScope(Dispatchers.IO).launch {
            delay(1500)
            runOnUiThread { refreshStatus() }
        }
    }

    private fun unpair() {
        AlertDialog.Builder(this)
            .setTitle("确认解绑")
            .setMessage("解绑后需要重新配对才能使用拨号功能，确定解绑吗？")
            .setPositiveButton("解绑") { _, _ ->
                val intent = Intent("com.autodial.ACTION_UNPAIR")
                sendBroadcast(intent)
                toast("已发送解绑请求")
                CoroutineScope(Dispatchers.IO).launch {
                    delay(1000)
                    runOnUiThread { refreshStatus() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

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
                toast("部分权限未授予，可能影响拨号功能")
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
