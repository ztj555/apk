package com.autodial.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*
import java.util.concurrent.TimeUnit

class DialService : Service() {

    companion object {
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
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .reconnectOnWebsocketFailure(true)
        .build()
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var lastPin = ""
    private var lastIp = ""

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("AutoDial 运行中"))
        // 读取保存的连接信息
        val prefs = getSharedPreferences("autodial", MODE_PRIVATE)
        lastIp = prefs.getString("ip", "") ?: ""
        lastPin = prefs.getString("pin", "") ?: ""
        serverAddress = lastIp
        if (lastIp.isNotEmpty() && lastPin.isNotEmpty()) {
            connectToServer(lastIp, lastPin)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "CONNECT" -> {
                val ip = intent.getStringExtra("ip") ?: ""
                val pin = intent.getStringExtra("pin") ?: ""
                if (ip.isNotEmpty() && pin.isNotEmpty()) {
                    lastIp = ip
                    lastPin = pin
                    serverAddress = ip
                    getSharedPreferences("autodial", MODE_PRIVATE).edit()
                        .putString("ip", ip)
                        .putString("pin", pin)
                        .apply()
                    connectToServer(ip, pin)
                }
            }
            "DISCONNECT" -> disconnect()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        handler.removeCallbacksAndMessages(null)
        isRunning = false
        isConnected = false
    }

    // ==================== WebSocket 连接 ====================

    private fun connectToServer(ip: String, pin: String) {
        // 先断开旧连接
        webSocket?.close(1000, "reconnect")
        handler.removeCallbacks(reconnectRunnable ?: return)
        reconnectRunnable = null

        val url = "ws://$ip:35432"
        updateNotification("正在连接 $ip ...")

        try {
            val request = Request.Builder().url(url).build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // 发送配对验证
                    val msg = JsonObject().apply {
                        addProperty("type", "phone_hello")
                        addProperty("pin", pin)
                    }
                    webSocket.send(msg.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val msg = gson.fromJson(text, JsonObject::class.java)
                        when (msg.get("type")?.asString) {
                            "auth_ok" -> {
                                isConnected = true
                                handler.post {
                                    updateNotification("已连接到电脑")
                                    sendBroadcast(Intent("com.autodial.CONNECTION_CHANGE").apply {
                                        putExtra("connected", true)
                                    })
                                }
                            }
                            "auth_fail" -> {
                                isConnected = false
                                handler.post {
                                    updateNotification("配对码错误，请检查")
                                    Toast.makeText(this@DialService, "配对码错误！", Toast.LENGTH_LONG).show()
                                    sendBroadcast(Intent("com.autodial.CONNECTION_CHANGE").apply {
                                        putExtra("connected", false)
                                        putExtra("reason", "pin_wrong")
                                    })
                                }
                                webSocket.close(1000, "auth_fail")
                            }
                            "dial" -> {
                                val number = msg.get("number")?.asString ?: return
                                val sim = msg.get("sim")?.asInt ?: 1
                                handler.post { showDialConfirm(number, sim) }
                            }
                            "pong" -> { /* 心跳响应 */ }
                            "kicked" -> {
                                isConnected = false
                                handler.post {
                                    updateNotification("已被踢下线")
                                    Toast.makeText(this@DialService, "有其他手机连接了", Toast.LENGTH_LONG).show()
                                    sendBroadcast(Intent("com.autodial.CONNECTION_CHANGE").apply {
                                        putExtra("connected", false)
                                        putExtra("reason", "kicked")
                                    })
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // 忽略解析错误
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onDisconnected()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    onDisconnected()
                    // 5秒后自动重连
                    scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            updateNotification("连接失败")
            scheduleReconnect()
        }
    }

    private fun onDisconnected() {
        if (isConnected) {
            isConnected = false
            handler.post {
                updateNotification("连接已断开")
                sendBroadcast(Intent("com.autodial.CONNECTION_CHANGE").apply {
                    putExtra("connected", false)
                })
            }
        }
    }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnectRunnable ?: return)
        reconnectRunnable = Runnable {
            if (lastIp.isNotEmpty() && lastPin.isNotEmpty() && !isConnected) {
                connectToServer(lastIp, lastPin)
            }
        }
        handler.postDelayed(reconnectRunnable!!, 5000)
    }

    private fun disconnect() {
        handler.removeCallbacks(reconnectRunnable ?: return)
        reconnectRunnable = null
        webSocket?.close(1000, "user_disconnect")
        webSocket = null
        isConnected = false
        updateNotification("AutoDial 运行中")
    }

    // ==================== 拨号 ====================

    private fun showDialConfirm(number: String, sim: Int) {
        val intent = Intent(this, DialConfirmActivity::class.java).apply {
            putExtra("number", number)
            putExtra("sim", sim)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    // ==================== 通知 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "AutoDial 服务", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "保持拨号连接" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
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
        val nm = getSystemService(NotificationManager::class.java) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
