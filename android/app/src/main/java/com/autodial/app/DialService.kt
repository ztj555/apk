package com.autodial.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DialService : Service() {

    companion object {
        private const val TAG = "DialService"
        private const val CHANNEL_ID = "autodial_service"
        private const val NOTIFICATION_ID = 1001

        var isRunning = false
            private set
        var isConnected = false
            private set
        var serverAddress = ""
            private set

        fun newIntent(context: Context): Intent = Intent(context, DialService::class.java)
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)     // 连接超时5秒，快速失败
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var lastPin = ""
    private var lastIp = ""
    private var manualConnecting = false  // 标记是否用户手动发起的连接

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        try {
            isRunning = true
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification("AutoDial 运行中"))

            val prefs = getSharedPreferences("autodial", MODE_PRIVATE)
            lastIp = prefs.getString("ip", "") ?: ""
            lastPin = prefs.getString("pin", "") ?: ""
            serverAddress = lastIp
            // 不在 onCreate 自动连接了，等用户手动点连接
            // 如果之前是已连接状态，可以尝试重连
            val wasConnected = prefs.getBoolean("was_connected", false)
            if (wasConnected && lastIp.isNotEmpty() && lastPin.isNotEmpty()) {
                Log.d(TAG, "上次已连接，自动重连到 $lastIp")
                updateNotification("自动重连中...")
                connectToServer(lastIp, lastPin, isAutoReconnect = true)
            }
        } catch (e: Exception) {
            isRunning = true
            createNotificationChannel()
            try {
                startForeground(NOTIFICATION_ID, buildNotification("AutoDial 运行中"))
            } catch (_: Exception) {}
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                "CONNECT" -> {
                    val ip = intent.getStringExtra("ip") ?: ""
                    val pin = intent.getStringExtra("pin") ?: ""
                    if (ip.isNotEmpty() && pin.isNotEmpty()) {
                        lastIp = ip
                        lastPin = pin
                        serverAddress = ip
                        manualConnecting = true
                        getSharedPreferences("autodial", MODE_PRIVATE).edit()
                            .putString("ip", ip)
                            .putString("pin", pin)
                            .apply()
                        // 取消自动重连
                        cancelReconnect()
                        connectToServer(ip, pin, isAutoReconnect = false)
                    }
                }
                "DISCONNECT" -> {
                    manualConnecting = false
                    disconnect()
                    getSharedPreferences("autodial", MODE_PRIVATE).edit()
                        .putBoolean("was_connected", false)
                        .apply()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            disconnect()
            handler.removeCallbacksAndMessages(null)
            isRunning = false
            isConnected = false
        } catch (_: Exception) {}
    }

    // ==================== WebSocket 连接 ====================

    private fun connectToServer(ip: String, pin: String, isAutoReconnect: Boolean = false) {
        try {
            // 先彻底关闭旧的
            reconnectRunnable = null
            try {
                webSocket?.cancel()  // cancel 比 close 更彻底，不会触发正常的 onClose
            } catch (_: Exception) {}
            webSocket = null

            val url = "ws://$ip:35432"
            updateNotification("正在连接 $ip ...")
            Log.d(TAG, "连接到 $url (自动重连=$isAutoReconnect)")

            val request = Request.Builder().url(url).build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket 已打开，发送 phone_hello")
                    try {
                        val msg = JSONObject().apply {
                            put("type", "phone_hello")
                            put("pin", pin)
                        }
                        webSocket.send(msg.toString())
                    } catch (e: Exception) {
                        Log.e(TAG, "发送 phone_hello 失败: ${e.message}")
                        e.printStackTrace()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val msg = JSONObject(text)
                        Log.d(TAG, "收到消息: ${msg.optString("type")}")
                        when (msg.optString("type", "")) {
                            "auth_ok" -> {
                                Log.d(TAG, "配对成功！")
                                isConnected = true
                                manualConnecting = false
                                handler.post {
                                    updateNotification("已连接到电脑")
                                    getSharedPreferences("autodial", MODE_PRIVATE).edit()
                                        .putBoolean("was_connected", true)
                                        .apply()
                                    sendBroadcast(Intent("com.autodial.CONNECTION_CHANGE").apply {
                                        putExtra("connected", true)
                                    })
                                }
                            }
                            "auth_fail" -> {
                                Log.w(TAG, "配对码错误")
                                isConnected = false
                                manualConnecting = false
                                handler.post {
                                    updateNotification("配对码错误，请检查")
                                    sendBroadcast(Intent("com.autodial.CONNECTION_CHANGE").apply {
                                        putExtra("connected", false)
                                        putExtra("reason", "pin_wrong")
                                    })
                                }
                                webSocket.close(1000, "auth_fail")
                            }
                            "dial" -> {
                                val number = msg.optString("number", "")
                                val sim = msg.optInt("sim", 1)
                                if (number.isNotEmpty()) {
                                    handler.post { showDialConfirm(number, sim) }
                                }
                            }
                            "pong" -> {}
                            "kicked" -> {
                                Log.w(TAG, "被踢下线")
                                isConnected = false
                                manualConnecting = false
                                handler.post {
                                    updateNotification("已被踢下线")
                                    sendBroadcast(Intent("com.autodial.CONNECTION_CHANGE").apply {
                                        putExtra("connected", false)
                                        putExtra("reason", "kicked")
                                    })
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "处理消息失败: ${e.message}")
                        e.printStackTrace()
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    try { webSocket.close(1000, null) } catch (_: Exception) {}
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket 已关闭 code=$code reason=$reason")
                    onDisconnected()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket 连接失败: ${t.message}")
                    onDisconnected()
                    // 通知 UI 连接失败
                    handler.post {
                        sendBroadcast(Intent("com.autodial.CONNECTION_CHANGE").apply {
                            putExtra("connected", false)
                            putExtra("reason", "connection_failed")
                        })
                    }
                    // 只有自动重连模式才自动重试，手动连接失败不自动重试
                    if (isAutoReconnect && !manualConnecting) {
                        scheduleReconnect()
                    } else {
                        manualConnecting = false
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "创建连接失败: ${e.message}")
            e.printStackTrace()
            updateNotification("连接失败")
            // 通知 UI
            handler.post {
                sendBroadcast(Intent("com.autodial.CONNECTION_CHANGE").apply {
                    putExtra("connected", false)
                    putExtra("reason", "connection_failed")
                })
            }
        }
    }

    private fun onDisconnected() {
        try {
            if (isConnected) {
                isConnected = false
                handler.post {
                    updateNotification("连接已断开")
                    sendBroadcast(Intent("com.autodial.CONNECTION_CHANGE").apply {
                        putExtra("connected", false)
                        putExtra("reason", "disconnected")
                    })
                }
            }
        } catch (_: Exception) {}
    }

    private fun scheduleReconnect() {
        try {
            cancelReconnect()
            reconnectRunnable = Runnable {
                if (lastIp.isNotEmpty() && lastPin.isNotEmpty() && !isConnected && !manualConnecting) {
                    Log.d(TAG, "自动重连到 $lastIp")
                    connectToServer(lastIp, lastPin, isAutoReconnect = true)
                }
            }
            handler.postDelayed(reconnectRunnable!!, 5000)
        } catch (_: Exception) {}
    }

    private fun cancelReconnect() {
        try {
            reconnectRunnable?.let {
                handler.removeCallbacks(it)
            }
            reconnectRunnable = null
        } catch (_: Exception) {}
    }

    private fun disconnect() {
        try {
            cancelReconnect()
            manualConnecting = false
            try { webSocket?.cancel() } catch (_: Exception) {}
            webSocket = null
            isConnected = false
            updateNotification("AutoDial 运行中")
        } catch (_: Exception) {}
    }

    // ==================== 拨号 ====================

    private fun showDialConfirm(number: String, sim: Int) {
        try {
            val intent = Intent(this, DialConfirmActivity::class.java).apply {
                putExtra("number", number)
                putExtra("sim", sim)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==================== 通知 ====================

    private fun createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "AutoDial 服务", NotificationManager.IMPORTANCE_LOW
                ).apply { description = "保持拨号连接" }
                getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
        } catch (_: Exception) {}
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoDial")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }
}
