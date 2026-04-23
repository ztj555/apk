package com.autodial.app

import android.Manifest
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: ImageView
    private lateinit var statusText: TextView
    private lateinit var connectionBanner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var pinInput: EditText
    private lateinit var connectBtn: View
    private lateinit var discoveryHint: TextView
    private lateinit var foundPCInfo: LinearLayout
    private lateinit var foundPCText: TextView

    private var discoveredIP = ""
    private var discoveryJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                val connected = intent?.getBooleanExtra("connected", false) ?: return
                val reason = intent.getStringExtra("reason")
                updateConnectionUI(connected, reason)
            } catch (_: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            statusDot = findViewById(R.id.statusDot)
            statusText = findViewById(R.id.statusText)
            connectionBanner = findViewById(R.id.connectionBanner)
            bannerText = findViewById(R.id.bannerText)
            pinInput = findViewById(R.id.pinInput)
            connectBtn = findViewById(R.id.connectBtn)
            discoveryHint = findViewById(R.id.discoveryHint)
            foundPCInfo = findViewById(R.id.foundPCInfo)
            foundPCText = findViewById(R.id.foundPCText)

            connectBtn.setOnClickListener { toggleConnection() }

            // 读取保存的配对码
            val prefs = getSharedPreferences("autodial", MODE_PRIVATE)
            pinInput.setText(prefs.getString("pin", ""))

            // 请求通知权限（Android 13+）
            requestNotificationPermission()

            // 启动服务
            startService(DialService.newIntent(this))

            // 注册广播
            try {
                ContextCompat.registerReceiver(this, receiver,
                    IntentFilter("com.autodial.CONNECTION_CHANGE"),
                    ContextCompat.RECEIVER_NOT_EXPORTED)
            } catch (_: Exception) {}

            // 检查当前连接状态
            updateConnectionUI(DialService.isConnected, null)

            // 配对码输入变化时自动扫描
            pinInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val pin = s.toString().trim()
                    if (pin.length == 4) {
                        startDiscovery(pin)
                    } else {
                        stopDiscovery()
                        discoveredIP = ""
                        foundPCInfo.visibility = View.GONE
                        if (pin.isEmpty()) {
                            discoveryHint.text = "🔍 请输入配对码开始搜索"
                        }
                    }
                }
            })

        } catch (e: Exception) {
            Toast.makeText(this, "初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            startService(DialService.newIntent(this))
        }
    }

    override fun onResume() {
        super.onResume()
        try { updateConnectionUI(DialService.isConnected, null) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        stopDiscovery()
    }

    // ==================== UDP 局域网发现 ====================

    private fun startDiscovery(pin: String) {
        stopDiscovery()
        discoveryHint.text = "🔍 正在扫描局域网..."
        discoveryHint.visibility = View.VISIBLE
        foundPCInfo.visibility = View.GONE
        discoveredIP = ""

        discoveryJob = CoroutineScope(Dispatchers.IO).launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null)
                socket.reuseAddress = true
                socket.bind(java.net.InetSocketAddress(0))

                val discoverMsg = """{"type":"discover","pin":"$pin"}""".toByteArray()
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(discoverMsg, discoverMsg.size, broadcastAddr, 35433)

                // 发送多次广播，增加发现概率
                var found = false
                repeat(3) {
                    if (found) return@repeat
                    try {
                        socket.send(packet)
                    } catch (_: Exception) {}
                }

                // 同时直接监听电脑的主动广播
                socket.soTimeout = 5000
                val buffer = ByteArray(1024)
                val startTime = System.currentTimeMillis()

                while (System.currentTimeMillis() - startTime < 4000 && !found && isActive) {
                    try {
                        val recvPacket = DatagramPacket(buffer, buffer.size)
                        socket.receive(recvPacket)
                        val data = String(recvPacket.data, 0, recvPacket.length)

                        val json = try { org.json.JSONObject(data) } catch (_: Exception) { continue }
                        val type = json.optString("type", "")
                        val responsePin = json.optString("pin", "")

                        if ((type == "found" || type == "announce") && responsePin == pin) {
                            val ip = json.optString("ip", "")
                            if (ip.isNotEmpty() && !ip.startsWith("127.")) {
                                discoveredIP = ip
                                found = true
                                withContext(Dispatchers.Main) {
                                    discoveryHint.text = "✅ 已找到电脑"
                                    foundPCText.text = "💻 发现电脑: $ip"
                                    foundPCInfo.visibility = View.VISIBLE
                                }
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // 超时继续等待
                    } catch (_: Exception) {}
                }

                if (!found && isActive) {
                    withContext(Dispatchers.Main) {
                        discoveryHint.text = "⚠️ 未发现电脑，请确认在同一WiFi下"
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    discoveryHint.text = "⚠️ 扫描出错，请重试"
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    // ==================== 连接控制 ====================

    private fun toggleConnection() {
        try {
            if (DialService.isConnected) {
                AlertDialog.Builder(this)
                    .setTitle("断开连接")
                    .setMessage("确定断开与电脑的连接？")
                    .setPositiveButton("断开") { _, _ -> sendDisconnectCommand() }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                val pin = pinInput.text.toString().trim()

                if (pin.length != 4) {
                    Toast.makeText(this, "请输入4位配对码", Toast.LENGTH_SHORT).show()
                    return
                }

                if (discoveredIP.isEmpty()) {
                    // 没有自动发现到，再次尝试扫描
                    pinInput.isEnabled = false
                    connectBtn.isEnabled = false
                    statusText.text = "正在搜索电脑..."
                    statusText.setTextColor(Color.parseColor("#F0C040"))

                    discoveryJob = CoroutineScope(Dispatchers.IO).launch {
                        var socket: DatagramSocket? = null
                        try {
                            socket = DatagramSocket(null)
                            socket.reuseAddress = true
                            socket.bind(java.net.InetSocketAddress(0))
                            socket.soTimeout = 3000

                            val discoverMsg = """{"type":"discover","pin":"$pin"}""".toByteArray()
                            val broadcastAddr = InetAddress.getByName("255.255.255.255")
                            val packet = DatagramPacket(discoverMsg, discoverMsg.size, broadcastAddr, 35433)

                            // 多发几次
                            repeat(5) {
                                try { socket.send(packet) } catch (_: Exception) {}
                                Thread.sleep(200)
                            }

                            val buffer = ByteArray(1024)
                            val startTime = System.currentTimeMillis()
                            var found = false

                            while (System.currentTimeMillis() - startTime < 3000 && !found && isActive) {
                                try {
                                    val recvPacket = DatagramPacket(buffer, buffer.size)
                                    socket.receive(recvPacket)
                                    val data = String(recvPacket.data, 0, recvPacket.length)
                                    val json = try { org.json.JSONObject(data) } catch (_: Exception) { continue }
                                    val type = json.optString("type", "")
                                    val responsePin = json.optString("pin", "")

                                    if ((type == "found" || type == "announce") && responsePin == pin) {
                                        val ip = json.optString("ip", "")
                                        if (ip.isNotEmpty() && !ip.startsWith("127.")) {
                                            discoveredIP = ip
                                            found = true
                                        }
                                    }
                                } catch (_: java.net.SocketTimeoutException) {}
                                catch (_: Exception) {}
                            }

                            withContext(Dispatchers.Main) {
                                pinInput.isEnabled = true
                                connectBtn.isEnabled = true

                                if (found && discoveredIP.isNotEmpty()) {
                                    doConnect(discoveredIP, pin)
                                } else {
                                    Toast.makeText(this@MainActivity, "未找到电脑，请确保电脑端已打开且在同一WiFi下", Toast.LENGTH_LONG).show()
                                    statusText.text = "未找到电脑"
                                    statusText.setTextColor(Color.parseColor("#A09070"))
                                }
                            }
                        } catch (_: Exception) {
                            withContext(Dispatchers.Main) {
                                pinInput.isEnabled = true
                                connectBtn.isEnabled = true
                                Toast.makeText(this@MainActivity, "扫描出错", Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            try { socket?.close() } catch (_: Exception) {}
                        }
                    }
                } else {
                    doConnect(discoveredIP, pin)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun doConnect(ip: String, pin: String) {
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

        // 保存配对码
        getSharedPreferences("autodial", MODE_PRIVATE).edit()
            .putString("pin", pin)
            .apply()
    }

    private fun sendDisconnectCommand() {
        try {
            val intent = Intent(this, DialService::class.java).apply {
                action = "DISCONNECT"
            }
            startService(intent)
            updateConnectionUI(false, null)
        } catch (_: Exception) {}
    }

    private fun updateConnectionUI(connected: Boolean, reason: String?) {
        try {
            pinInput.isEnabled = !connected
            connectBtn.isEnabled = true

            if (connected) {
                statusDot.setImageResource(R.drawable.dot_green)
                statusText.text = "已连接"
                statusText.setTextColor(Color.parseColor("#2ECC71"))
                connectionBanner.visibility = View.VISIBLE
                bannerText.text = "✅ 已连接到电脑！等待拨号指令..."
                discoveryHint.visibility = View.GONE
                foundPCInfo.visibility = View.GONE

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
                statusDot.setImageResource(R.drawable.dot_gray)
                connectionBanner.visibility = View.GONE
                foundPCInfo.visibility = View.GONE

                val connectBtnLayout = findViewById<LinearLayout>(R.id.connectBtn)
                connectBtnLayout.setBackgroundColor(Color.parseColor("#C9A84C"))
                val connectTextView = findViewById<TextView>(R.id.connectBtnText)
                connectTextView.text = "连接"

                when (reason) {
                    "pin_wrong" -> {
                        statusText.text = "配对码错误"
                        statusText.setTextColor(Color.parseColor("#E74C3C"))
                        Toast.makeText(this, "配对码不正确，请重新输入！", Toast.LENGTH_LONG).show()
                        discoveryHint.text = "⚠️ 配对码错误，请重新输入"
                        discoveryHint.visibility = View.VISIBLE
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
