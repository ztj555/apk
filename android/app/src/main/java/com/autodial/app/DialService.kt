package com.autodial.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * 核心后台服务：保持 WebSocket 长连接，接收拨号指令并执行
 */
class DialService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "autodial_service"

        // 服务状态，供 Activity 读取
        var isConnected = false
            private set
        var isPaired = false
            private set
        var pairCode: String? = null
            private set
        var phoneOnline: Boolean = false
            private set
        var statusMessage: String = "未连接"
            private set

        // 状态回调
        var onStatusChanged: ((Boolean, Boolean, String?, String, Boolean) -> Unit)? = null

        private fun updateStatus(connected: Boolean, paired: Boolean, code: String?, msg: String, online: Boolean) {
            isConnected = connected
            isPaired = paired
            pairCode = code
            statusMessage = msg
            phoneOnline = online
            onStatusChanged?.invoke(connected, paired, code, msg, online)
        }
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val gson = Gson()
    private var serviceJob: Job? = null

    // ==================== 服务生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("AutoDial 运行中"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverUrl = getServerUrl()
        if (serverUrl.isNotEmpty()) {
            connectWebSocket(serverUrl)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "Service destroyed")
        serviceJob?.cancel()
        updateStatus(false, false, null, "服务已停止", false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== WebSocket 连接 ====================

    private fun connectWebSocket(url: String) {
        updateStatus(false, false, null, "正在连接...", false)

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                updateNotification("已连接 - 等待配对")
                updateStatus(true, false, null, "已连接服务器", false)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                updateNotification("已断开")
                updateStatus(false, false, null, "连接已断开", false)
                // 自动重连
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                updateNotification("连接失败 - 将重试")
                updateStatus(false, false, null, "连接失败: ${t.message}", false)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        serviceJob?.cancel()
        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            delay(5000)
            val url = getServerUrl()
            if (url.isNotEmpty()) {
                connectWebSocket(url)
            }
        }
    }

    private fun sendToServer(data: Map<String, Any>) {
        val json = gson.toJson(data)
        webSocket?.send(json)
    }

    // ==================== 消息处理 ====================

    private fun handleServerMessage(text: String) {
        try {
            val data = gson.fromJson(text, JsonObject::class.java)
            val type = data.get("type")?.asString ?: return

            when (type) {
                "connected" -> {
                    val deviceId = data.get("deviceId")?.asString ?: ""
                    sendToServer(mapOf("type" to "register", "deviceType" to "phone"))
                    // 查询已有配对
                    delaySend(mapOf("type" to "get_status"), 500)
                }

                "pair_code_generated" -> {
                    val code = data.get("pairCode")?.asString ?: ""
                    runOnUiThread {
                        pairCode = code
                        updateNotification("配对码: $code - 等待电脑连接")
                        updateStatus(true, false, code, "配对码: $code，等待电脑连接", false)
                    }
                }

                "pair_success" -> {
                    val code = data.get("pairCode")?.asString ?: ""
                    val msg = data.get("message")?.asString ?: "配对成功"
                    runOnUiThread {
                        updateStatus(true, true, code, msg, true)
                        updateNotification("已配对 - $code")
                        Toast.makeText(this, "✅ $msg", Toast.LENGTH_SHORT).show()
                    }
                }

                "pair_failed" -> {
                    val msg = data.get("message")?.asString ?: "配对失败"
                    runOnUiThread {
                        Toast.makeText(this, "❌ $msg", Toast.LENGTH_SHORT).show()
                    }
                }

                "unpaired" -> {
                    val reason = data.get("reason")?.asString ?: "已解绑"
                    runOnUiThread {
                        updateStatus(true, false, null, reason, false)
                        updateNotification("已断开配对")
                        Toast.makeText(this, "🔓 $reason", Toast.LENGTH_SHORT).show()
                    }
                }

                "unpair_success" -> {
                    runOnUiThread {
                        updateStatus(true, false, null, "已解绑", false)
                        updateNotification("未配对")
                        Toast.makeText(this, "已解绑", Toast.LENGTH_SHORT).show()
                    }
                }

                "dial_request" -> {
                    val number = data.get("number")?.asString ?: return
                    val simSlot = data.get("simSlot")?.asInt ?: 1
                    makePhoneCall(number, simSlot)
                }

                "pair_status" -> {
                    val status = data.get("status")?.asString ?: ""
                    if (status == "phone_offline") {
                        runOnUiThread {
                            updateNotification("电脑离线")
                            Toast.makeText(this, "💻 电脑已离线", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                "status_update" -> {
                    val status = data.get("status")?.asString ?: "unpaired"
                    val code = data.get("pairCode")?.asString
                    val online = data.get("pairedDeviceOnline")?.asBoolean ?: false

                    runOnUiThread {
                        if (status == "paired") {
                            updateStatus(true, true, code, "已配对", online)
                            updateNotification("已配对 - ${if (online) "电脑在线" else "电脑离线"}")
                        } else {
                            updateStatus(true, false, null, "未配对", false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略解析错误
        }
    }

    // ==================== 拨号执行 ====================

    private fun makePhoneCall(number: String, simSlot: Int) {
        runOnUiThread {
            // 先显示选择对话框让用户确认
            showDialConfirmDialog(number, simSlot)
        }
    }

    private fun showDialConfirmDialog(number: String, simSlot: Int) {
        val intent = Intent(this, DialConfirmActivity::class.java).apply {
            putExtra("number", number)
            putExtra("simSlot", simSlot)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)

        // 同时发送通知
        val intent2 = Intent(this, DialConfirmActivity::class.java).apply {
            putExtra("number", number)
            putExtra("simSlot", simSlot)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent2,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("📞 来自拔号指令")
            .setContentText("号码: $number (SIM$simSlot)")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(2001, notification)
    }

    // ==================== 配对操作（供 Activity 调用） ====================

    fun requestPair() {
        sendToServer(mapOf("type" to "phone_request_pair"))
    }

    fun unpair() {
        sendToServer(mapOf("type" to "unpair"))
    }

    // ==================== 通知管理 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AutoDial 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持WebSocket连接并接收拨号指令"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("AutoDial")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ==================== 辅助方法 ====================

    private fun getServerUrl(): String {
        val prefs = getSharedPreferences("autodial", MODE_PRIVATE)
        return prefs.getString("server_url", "") ?: ""
    }

    private fun runOnUiThread(action: () -> Unit) {
        CoroutineScope(Dispatchers.Main).launch { action() }
    }

    private fun delaySend(data: Map<String, Any>, delayMs: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            delay(delayMs)
            sendToServer(data)
        }
    }
}
