package com.autodial.app

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DialService : Service() {

    companion object {
        private const val TAG = "DialService"
        private const val CHANNEL_ID = "autodial_service"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_CONNECTION = "com.autodial.CONNECTION_CHANGE"
        private const val ACTION_NEW_DIAL = "com.autodial.NEW_DIAL"
        private const val ACTION_CALL_ENDED = "com.autodial.CALL_ENDED"
        private const val ACTION_LAST_CALL_HINT = "com.autodial.LAST_CALL_HINT"
        /** 拨号前需要用户选卡时发出此广播，MainActivity 弹出 SimSelectBottomSheet */
        const val ACTION_SHOW_SIM_SELECT = "com.autodial.SHOW_SIM_SELECT"
        /** 收到短信发送请求时发出此广播，MainActivity 启动 SmsConfirmActivity */
        const val ACTION_SHOW_SMS_CONFIRM = "com.autodial.SHOW_SMS_CONFIRM"
        /** 云端连接状态变化 */
        const val ACTION_CLOUD_STATUS = "com.autodial.CLOUD_STATUS"

        var isRunning = false
            private set
        var isConnected = false
            private set
        var serverAddress = ""
            private set
        var cloudConnected = false
            private set
        /** 当前云中转服务器地址（用于重连） */
        var currentCloudServer = ""
            private set
        /** 当前使用的 PIN（用于重连） */
        var currentPin = ""
            private set

        fun newIntent(context: Context): Intent = Intent(context, DialService::class.java)

        // 供 DialConfirmActivity 调用，回报拨号结果给电脑
        fun sendDialResult(number: String, status: String) {
            _instance?._sendResultToPC(number, status)
        }
        // 供 SmsConfirmActivity 调用，回报短信发送结果给电脑
        fun sendSmsResult(number: String, status: String) {
            _instance?._sendSmsResultToPC(number, status)
        }
        internal var _instance: DialService? = null
    }

    private var webSocket: WebSocket? = null
    private var cloudWebSocket: WebSocket? = null  // 云中转连接
    /** 当前连接模式: "lan" | "cloud" | "" */
    var connectionMode = ""
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val cloudClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var cloudReconnectRunnable: Runnable? = null
    private var lastPin = ""
    private var lastIp = ""
    private var manualConnecting = false
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var callLogDb: CallLogDb
    private var phoneStateListener: PhoneStateListener? = null

    /** 当前正在等待用户选卡的号码 */
    private var pendingDialNumber: String? = null

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        _instance = this
        try {
            isRunning = true
            callLogDb = CallLogDb(this)
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification("跨屏拨号 运行中"))

            // 保持CPU唤醒，防止MIUI杀掉后台WebSocket连接
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autodial:wake").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // 12小时后自动释放
            }

            // 异步同步系统通话记录到 SIM 缓存（首次安装或数据库升级后）
            Thread {
                try {
                    val count = callLogDb.syncFromSystemCallLog(this@DialService)
                    if (count > 0) Log.d(TAG, "SIM缓存同步完成：$count 个号码")
                } catch (e: Exception) {
                    Log.e(TAG, "SIM缓存同步失败: ${e.message}")
                }
            }.start()

            // 监听通话状态，通话结束时通知UI刷新通话记录
            registerCallStateListener()

            val prefs = getSharedPreferences("autodial", MODE_PRIVATE)
            lastIp = prefs.getString("ip", "") ?: ""
            lastPin = prefs.getString("pin", "") ?: ""
            serverAddress = lastIp

            val wasConnected = prefs.getBoolean("was_connected", false)
            if (wasConnected && lastIp.isNotEmpty() && lastPin.isNotEmpty()) {
                Log.d(TAG, "自动重连到 $lastIp")
                updateNotification("自动重连中...")
                connectToServer(lastIp, lastPin, isAutoReconnect = true)
            }

            // 云中转：如果已配置并启用，自动连接
            val cloudEnabled = prefs.getBoolean("cloud_enabled", false)
            val serversJson = prefs.getString("cloud_servers", null)
            val cloudServer = prefs.getString("cloud_server", "") ?: ""

            // 检查是否有实际配置的服务器
            val hasConfiguredServers = if (serversJson != null) {
                try {
                    val arr = org.json.JSONArray(serversJson)
                    arr.length() > 0
                } catch (_: Exception) { false }
            } else {
                cloudServer.isNotEmpty()
            }

            if (cloudEnabled && hasConfiguredServers && lastPin.isNotEmpty()) {
                if (serversJson != null) {
                    Log.d(TAG, "自动连接云中转（多服务器模式）")
                    connectToCloudServersFromList(serversJson, lastPin)
                } else if (cloudServer.isNotEmpty()) {
                    Log.d(TAG, "自动连接云中转: $cloudServer")
                    connectToCloudServer(cloudServer, lastPin)
                }
            } else if (cloudEnabled) {
                // 没有配置服务器，清除 cloud_enabled 标志
                Log.w(TAG, "cloud_enabled=true 但没有配置服务器，清除标志")
                prefs.edit().putBoolean("cloud_enabled", false).apply()
            }
        } catch (e: Exception) {
            isRunning = true
            callLogDb = CallLogDb(this)
            createNotificationChannel()
            try { startForeground(NOTIFICATION_ID, buildNotification("跨屏拨号 运行中")) } catch (_: Exception) {}
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
                            .putString("ip", ip).putString("pin", pin).apply()
                        cancelReconnect()
                        connectToServer(ip, pin, isAutoReconnect = false)
                    }
                }
                "DISCONNECT" -> {
                    manualConnecting = false
                    getSharedPreferences("autodial", MODE_PRIVATE).edit()
                        .putBoolean("was_connected", false).apply()
                    disconnect()
                }
                "CONNECT_CLOUD" -> {
                    val pin = intent.getStringExtra("pin") ?: lastPin
                    if (pin.isEmpty()) return START_STICKY
                    lastPin = pin

                    // 检查是否有 cloud_servers 列表（多服务器模式）
                    val serversJson = intent.getStringExtra("cloud_servers")
                    if (serversJson != null) {
                        // 多服务器模式：保存列表，从第一个开始遍历
                        getSharedPreferences("autodial", MODE_PRIVATE).edit()
                            .putBoolean("cloud_enabled", true)
                            .putString("cloud_servers", serversJson)
                            .putString("pin", pin)
                            .apply()
                        connectToCloudServersFromList(serversJson, pin)
                    } else {
                        // 单服务器模式（手动切换）：直接连接指定服务器
                        val server = intent.getStringExtra("cloud_server") ?: ""
                        if (server.isNotEmpty()) {
                            currentCloudServer = server
                            currentPin = pin
                            getSharedPreferences("autodial", MODE_PRIVATE).edit()
                                .putBoolean("cloud_enabled", true)
                                .putString("cloud_server", server)
                                .putString("pin", pin)
                                .apply()
                            connectToCloudServer(server, pin)
                        }
                    }
                }
                "DISCONNECT_CLOUD" -> {
                    disconnectCloud()
                }
                /** SimSelectBottomSheet 用户选好卡后回调 */
                "DIAL_WITH_SIM" -> {
                    val number = intent.getStringExtra("number") ?: return START_STICKY
                    val simSlot = intent.getIntExtra("sim_slot", 0)
                    pendingDialNumber = null
                    // 弹窗选卡后，也要通知UI本次使用的卡
                    broadcastDialSimInfo(number, simSlot)
                    performDial(number, simSlot)
                }
                /** SimSelectBottomSheet 用户取消 */
                "DIAL_CANCELLED" -> {
                    pendingDialNumber = null
                    val number = intent.getStringExtra("number") ?: return START_STICKY
                    _sendResultToPC(number, "cancelled")
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            // 注销通话状态监听
            phoneStateListener?.let {
                try {
                    val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    tm.listen(it, PhoneStateListener.LISTEN_NONE)
                } catch (_: Exception) {}
            }
            disconnect()
            disconnectCloud()
            handler.removeCallbacksAndMessages(null)
            isRunning = false; isConnected = false
            wakeLock?.release(); wakeLock = null
            pendingDialNumber = null
            _instance = null
        } catch (_: Exception) {}
    }

    // ==================== WebSocket 连接 ====================

    private fun connectToServer(ip: String, pin: String, isAutoReconnect: Boolean = false) {
        try {
            reconnectRunnable = null
            try { webSocket?.cancel() } catch (_: Exception) {}
            webSocket = null

            val url = "ws://$ip:35432"
            updateNotification("正在连接 $ip ...")
            Log.d(TAG, "连接到 $url")

            val request = Request.Builder().url(url).build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket 已打开")
                    try {
                        val deviceName = android.os.Build.MODEL ?: android.os.Build.DEVICE ?: "Android"
                        ws.send(JSONObject().apply {
                            put("type", "phone_hello"); put("pin", pin)
                            put("deviceName", deviceName)
                        }.toString())
                    } catch (e: Exception) { Log.e(TAG, "发送失败: ${e.message}") }
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    try {
                        val msg = JSONObject(text)
                        when (msg.optString("type", "")) {
                            "auth_ok" -> {
                                Log.d(TAG, "配对成功")
                                isConnected = true; manualConnecting = false
                                connectionMode = "lan"  // LAN 优先
                                handler.post {
                                    updateNotification("已连接到电脑(LAN)")
                                    getSharedPreferences("autodial", MODE_PRIVATE)
                                        .edit().putBoolean("was_connected", true).apply()
                                    notifyConnectionChange(true, null)
                                }
                            }
                            "auth_fail" -> {
                                isConnected = false; manualConnecting = false
                                handler.post {
                                    updateNotification("配对码错误")
                                    notifyConnectionChange(false, "pin_wrong")
                                }
                                ws.close(1000, "auth_fail")
                            }
                            "dial" -> {
                                val number = msg.optString("number", "")
                                if (number.isNotEmpty()) {
                                    Log.d(TAG, "拨号请求: $number")
                                    handler.post { dialNumber(number) }
                                }
                            }
                            "pong" -> {}
                            "kicked" -> {
                                isConnected = false; manualConnecting = false
                                handler.post {
                                    updateNotification("已被踢下线")
                                    notifyConnectionChange(false, "kicked")
                                }
                            }
                            "hangup" -> {
                                Log.d(TAG, "收到挂断指令")
                                handler.post { endCall() }
                            }
                            "sms" -> {
                                val number = msg.optString("number", "")
                                val content = msg.optString("content", "")
                                if (number.isNotEmpty()) {
                                    Log.d(TAG, "短信请求: $number, 内容长度=${content.length}")
                                    handler.post {
                                        val intent = Intent(ACTION_SHOW_SMS_CONFIRM).apply {
                                            putExtra("number", number)
                                            putExtra("content", content)
                                            setPackage(packageName)
                                        }
                                        sendBroadcast(intent)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) { Log.e(TAG, "处理消息失败: ${e.message}") }
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    try { ws.close(1000, null) } catch (_: Exception) {}
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "关闭 code=$code reason=$reason")
                    onDisconnected()
                    if (isAutoReconnect) {
                        handler.post { updateNotification("连接已断开，正在重连...") }
                    } else {
                        handler.post { notifyConnectionChange(false, "disconnected") }
                    }
                    scheduleReconnect()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "连接失败: ${t.message}")
                    onDisconnected()
                    if (isAutoReconnect) {
                        handler.post { updateNotification("连接失败，正在重连...") }
                    } else {
                        handler.post { notifyConnectionChange(false, "connection_failed") }
                    }
                    scheduleReconnect()
                }
            })

            // 设置发送结果的回调 - 使用统一发送方法
            // _sendResultToPC 会自动选择 LAN/云端通道
        } catch (e: Exception) {
            Log.e(TAG, "创建连接失败: ${e.message}")
            handler.post { notifyConnectionChange(false, "connection_failed") }
        }
    }

    private fun onDisconnected() {
        if (isConnected && connectionMode == "lan") {
            isConnected = false
            connectionMode = ""
            // 如果云端已连接，切到云端
            if (cloudConnected && cloudWebSocket != null) {
                connectionMode = "cloud"
                isConnected = true
                handler.post {
                    updateNotification("已切换到云端连接")
                    notifyConnectionChange(true, null)
                }
            } else {
                handler.post {
                    updateNotification("连接已断开")
                    notifyConnectionChange(false, "disconnected")
                }
            }
        }
    }

    private fun scheduleReconnect() {
        cancelReconnect()
        val autoReconnect = getSharedPreferences("autodial", MODE_PRIVATE)
            .getBoolean("auto_reconnect", true)
        if (!autoReconnect) return

        reconnectRunnable = Runnable {
            if (lastIp.isNotEmpty() && lastPin.isNotEmpty() && !isConnected) {
                Log.d(TAG, "自动重连到 $lastIp")
                connectToServer(lastIp, lastPin, isAutoReconnect = true)
            }
        }
        handler.postDelayed(reconnectRunnable!!, 3000)
    }

    private fun cancelReconnect() {
        try { reconnectRunnable?.let { handler.removeCallbacks(it) }; reconnectRunnable = null } catch (_: Exception) {}
    }

    private fun disconnect() {
        cancelReconnect(); manualConnecting = false
        try { webSocket?.cancel() } catch (_: Exception) {}
        webSocket = null
        if (connectionMode == "lan") {
            isConnected = false; connectionMode = ""
        }
        updateNotification("跨屏拨号 运行中")
    }

    // ==================== 统一发送方法（自动选择 LAN/云端通道）====================

    /**
     * 发送消息给 PC，优先 LAN，其次云端
     */
    private fun sendToPC(msg: JSONObject) {
        // 优先 LAN
        if (connectionMode == "lan" && webSocket != null) {
            try {
                webSocket?.send(msg.toString())
                return
            } catch (_: Exception) {}
        }
        // 其次云端
        if (connectionMode == "cloud" && cloudWebSocket != null) {
            try {
                cloudWebSocket?.send(msg.toString())
                return
            } catch (_: Exception) {}
        }
        // 都不可用，尝试另一个
        try { webSocket?.send(msg.toString()); return } catch (_: Exception) {}
        try { cloudWebSocket?.send(msg.toString()); return } catch (_: Exception) {}
    }

    /**
     * 供 companion object 调用的拨号结果回报
     */
    private fun _sendResultToPC(number: String, status: String) {
        try {
            sendToPC(JSONObject().apply {
                put("type", "dial_result"); put("number", number); put("status", status)
            })
        } catch (_: Exception) {}
    }

    /**
     * 供 companion object 调用的短信结果回报
     */
    private fun _sendSmsResultToPC(number: String, status: String) {
        try {
            sendToPC(JSONObject().apply {
                put("type", "sms_result"); put("number", number); put("status", status)
            })
        } catch (_: Exception) {}
    }

    // ==================== 云中转 WebSocket 连接 ====================

    /** 云中转重连退避计数器（0=首次，每次翻倍直到最大值） */
    private var cloudReconnectAttempts = 0

    /**
     * 从云服务器列表中遍历尝试连接，成功一个即停止
     * @param serversJson JSON 数组字符串，如 ["server1:port", "server2:port"]
     * @param pin 配对码
     * @param startIndex 开始尝试的索引（用于重连时从上次成功的位置继续）
     */
    private fun connectToCloudServersFromList(serversJson: String, pin: String, startIndex: Int = 0) {
        try {
            val arr = JSONArray(serversJson)
            if (arr.length() == 0) {
                Log.d(TAG, "云服务器列表为空")
                return
            }

            // 从 startIndex 开始，循环遍历所有服务器
            tryConnectCloudAtIndex(arr, pin, startIndex)
        } catch (e: Exception) {
            Log.e(TAG, "解析云服务器列表失败: ${e.message}")
        }
    }

    /**
     * 尝试连接列表中指定索引的服务器
     * 如果失败，自动尝试下一个
     */
    private fun tryConnectCloudAtIndex(arr: JSONArray, pin: String, index: Int) {
        if (index >= arr.length()) {
            Log.d(TAG, "所有云服务器均连接失败")
            handler.post {
                updateNotification("所有云服务器连接失败")
                notifyConnectionChange(false, "cloud_disconnected")
            }
            return
        }

        val server = arr.getString(index)
        Log.d(TAG, "尝试云服务器 ${index + 1}/${arr.length()}: $server")
        updateNotification("正在连接云端 ${index + 1}/${arr.length()}...")

        connectToCloudServer(server, pin, onResult = { success ->
            if (success) {
                // 连接成功，保存当前服务器
                getSharedPreferences("autodial", MODE_PRIVATE).edit()
                    .putString("cloud_server", server)
                    .apply()
            } else {
                // 连接失败，尝试下一个
                Log.d(TAG, "云服务器 $server 连接失败，尝试下一个")
                tryConnectCloudAtIndex(arr, pin, index + 1)
            }
        })
    }
    private fun connectToCloudServer(serverUrl: String, pin: String, onResult: ((Boolean) -> Unit)? = null) {
        // 保存当前连接参数，供重连使用
        currentCloudServer = serverUrl
        currentPin = pin
        // 注意：cloudReconnectAttempts 不在每次连接时重置，
        // 而是在 auth_ok（连接成功）时才重置，以保证退避生效
        try {
            cancelCloudReconnect()
            try { cloudWebSocket?.cancel() } catch (_: Exception) {}
            cloudWebSocket = null

            // 确保 URL 格式正确
            val url = if (serverUrl.startsWith("ws://") || serverUrl.startsWith("wss://")) serverUrl
                      else "ws://$serverUrl"

            updateNotification("正在连接云端...")
            Log.d(TAG, "连接到云中转: $url")

            val request = Request.Builder().url(url).build()
            cloudWebSocket = cloudClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d(TAG, "云端 WebSocket 已打开")
                    try {
                        val deviceName = android.os.Build.MODEL ?: android.os.Build.DEVICE ?: "Android"
                        ws.send(JSONObject().apply {
                            put("type", "phone_hello"); put("pin", pin)
                            put("deviceName", deviceName)
                        }.toString())
                    } catch (e: Exception) { Log.e(TAG, "云端发送失败: ${e.message}") }
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    try {
                        val msg = JSONObject(text)
                        when (msg.optString("type", "")) {
                            "auth_ok" -> {
                                Log.d(TAG, "云端配对成功")
                                cloudConnected = true
                                cloudReconnectAttempts = 0
                                // 如果 LAN 没连接，则使用云端
                                if (connectionMode != "lan") {
                                    connectionMode = "cloud"
                                    isConnected = true
                                }
                                handler.post {
                                    val modeText = if (connectionMode == "lan") "LAN" else "云端"
                                    updateNotification("已连接到电脑($modeText)")
                                    notifyConnectionChange(true, null)
                                    onResult?.invoke(true)
                                }
                                notifyCloudStatus()
                            }
                            "auth_fail" -> {
                                cloudConnected = false
                                handler.post {
                                    updateNotification("云端配对码错误")
                                    notifyConnectionChange(false, "pin_wrong")
                                    onResult?.invoke(false)
                                }
                                ws.close(1000, "auth_fail")
                                notifyCloudStatus()
                            }
                            "dial" -> {
                                val number = msg.optString("number", "")
                                if (number.isNotEmpty()) {
                                    Log.d(TAG, "云端拨号请求: $number")
                                    handler.post { dialNumber(number) }
                                }
                            }
                            "pong" -> {}
                            "hangup" -> {
                                Log.d(TAG, "云端收到挂断指令")
                                handler.post { endCall() }
                            }
                            "sms" -> {
                                val number = msg.optString("number", "")
                                val content = msg.optString("content", "")
                                if (number.isNotEmpty()) {
                                    Log.d(TAG, "云端短信请求: $number")
                                    handler.post {
                                        val intent = Intent(ACTION_SHOW_SMS_CONFIRM).apply {
                                            putExtra("number", number)
                                            putExtra("content", content)
                                            setPackage(packageName)
                                        }
                                        sendBroadcast(intent)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) { Log.e(TAG, "云端处理消息失败: ${e.message}") }
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    try { ws.close(1000, null) } catch (_: Exception) {}
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "云端关闭 code=$code reason=$reason")
                    onCloudDisconnected()
                    handler.post { onResult?.invoke(false) }
                    scheduleCloudReconnect()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "云端连接失败: ${t.message}")
                    onCloudDisconnected()
                    handler.post { onResult?.invoke(false) }
                    scheduleCloudReconnect()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "创建云端连接失败: ${e.message}")
        }
    }

    private fun onCloudDisconnected() {
        val wasConnected = cloudConnected
        cloudConnected = false
        if (connectionMode == "cloud") {
            isConnected = false
            connectionMode = ""
            handler.post {
                updateNotification("云端连接已断开")
                notifyConnectionChange(false, "cloud_disconnected")
            }
        }
        notifyCloudStatus(if (wasConnected) "disconnected" else null)
    }

    private fun scheduleCloudReconnect() {
        cancelCloudReconnect()
        val autoReconnect = getSharedPreferences("autodial", MODE_PRIVATE)
            .getBoolean("auto_reconnect", true)
        if (!autoReconnect) return
        if (currentPin.isEmpty()) return

        cloudReconnectAttempts++
        // 退避时间：5s, 10s, 20s, 40s, 60s（最大）
        val delaySec = when (cloudReconnectAttempts) {
            1 -> 5
            2 -> 10
            3 -> 20
            4 -> 40
            else -> 60
        }
        val delayMs = delaySec * 1000L

        cloudReconnectRunnable = Runnable {
            if (!cloudConnected) {
                Log.d(TAG, "云端自动重连（第${cloudReconnectAttempts}次）")
                // 尝试从服务器列表遍历连接
                val serversJson = getSharedPreferences("autodial", MODE_PRIVATE)
                    .getString("cloud_servers", null)
                if (serversJson != null) {
                    connectToCloudServersFromList(serversJson, currentPin)
                } else if (currentCloudServer.isNotEmpty()) {
                    // 向后兼容：单服务器模式
                    connectToCloudServer(currentCloudServer, currentPin)
                }
            }
        }
        handler.postDelayed(cloudReconnectRunnable!!, delayMs)
    }

    private fun cancelCloudReconnect() {
        try { cloudReconnectRunnable?.let { handler.removeCallbacks(it) }; cloudReconnectRunnable = null } catch (_: Exception) {}
    }

    private fun disconnectCloud() {
        cancelCloudReconnect()
        cloudReconnectAttempts = 0
        try { cloudWebSocket?.cancel() } catch (_: Exception) {}
        cloudWebSocket = null
        cloudConnected = false
        if (connectionMode == "cloud") {
            isConnected = false; connectionMode = ""
        }
        // 注意：不在这里修改 cloud_enabled，只清理连接状态
        // cloud_enabled 由用户在 UI 中手动控制
        notifyCloudStatus()
        updateNotification("跨屏拨号 运行中")
        Log.d(TAG, "已断开云端连接")
    }

    private fun notifyCloudStatus(reason: String? = null) {
        try {
            val intent = Intent(ACTION_CLOUD_STATUS).apply {
                putExtra("connected", cloudConnected)
                putExtra("mode", connectionMode)
                reason?.let { putExtra("reason", it) }
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    // ==================== 通话状态监听 ====================

    private fun registerCallStateListener() {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            phoneStateListener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    when (state) {
                        TelephonyManager.CALL_STATE_IDLE -> {
                            Log.d(TAG, "通话结束，通知刷新通话记录")
                            notifyCallEnded()
                        }
                        TelephonyManager.CALL_STATE_OFFHOOK -> {
                            Log.d(TAG, "通话中")
                        }
                        TelephonyManager.CALL_STATE_RINGING -> {
                            Log.d(TAG, "来电响铃")
                        }
                    }
                }
            }
            tm.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            Log.d(TAG, "已注册通话状态监听")
        } catch (e: Exception) {
            Log.e(TAG, "注册通话状态监听失败: ${e.message}")
        }
    }

    private fun notifyCallEnded() {
        try {
            val intent = Intent(ACTION_CALL_ENDED).apply { setPackage(packageName) }
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    // ==================== SIM 卡信息 ====================

    /**
     * 获取当前可用的 SIM 卡列表（subscriptionId → simSlotIndex 映射）
     */
    private fun getSimInfoList(): List<SubscriptionInfo> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                sm?.activeSubscriptionInfoList?.filterNotNull() ?: emptyList()
            } else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    /**
     * 根据 simSlot (0/1) 获取对应的 PhoneAccountHandle
     */
    private fun getPhoneAccountHandle(simSlot: Int): PhoneAccountHandle? {
        return try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val simList = getSimInfoList()
            val targetInfo = simList.find { it.simSlotIndex == simSlot } ?: return null
            val subscriptionId = targetInfo.subscriptionId

            // 遍历所有 PhoneAccount，找到属于该 subscriptionId 的
            telecomManager.callCapablePhoneAccounts.forEach { handle ->
                val acc = telecomManager.getPhoneAccount(handle)
                if (acc != null && acc.hasCapabilities(android.telecom.PhoneAccount.CAPABILITY_CALL_PROVIDER)) {
                    // 通过 extras 里的 subscription id 匹配
                    val accSubId = acc.extras?.getInt("subscriptionId", -1) ?: -1
                    if (accSubId == subscriptionId) return handle
                }
            }

            // 备选：直接用 ComponentName 构造（部分厂商有效）
            PhoneAccountHandle(
                ComponentName("com.android.phone", "com.android.services.telephony.TelephonyConnectionService"),
                subscriptionId.toString()
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取 PhoneAccountHandle 失败(slot=$simSlot): ${e.message}")
            null
        }
    }

    // ==================== 拨号卡选择逻辑 ====================

    /**
     * 根据当前拨号模式决定使用哪张卡，或是否弹窗让用户选
     * @return simSlot (0=卡1, 1=卡2)，-1 表示需要弹窗
     */
    private fun resolveSimSlot(number: String): Int {
        val prefs = getSharedPreferences("autodial", MODE_PRIVATE)
        val modeKey = prefs.getString("dial_mode", DialMode.POPUP.key) ?: DialMode.POPUP.key
        val mode = DialMode.fromKey(modeKey)

        return when (mode) {
            DialMode.SIM1 -> 0
            DialMode.SIM2 -> 1

            DialMode.ALTERNATE -> {
                // 循环模式：全局交替，不看号码，查上一次拨号用了哪张卡，就用另一张
                val lastSlot = callLogDb.getLastSimSlotGlobal()
                if (lastSlot >= 0) {
                    val next = 1 - lastSlot
                    Log.d(TAG, "循环模式（全局）：上次卡${lastSlot + 1}，本次卡${next + 1}")
                    next
                } else {
                    Log.d(TAG, "循环模式（全局）：无历史记录，默认卡1")
                    0
                }
            }

            DialMode.REMEMBER -> {
                // 查询该号码上次用的卡，有就用同一张，没有则弹窗让用户选
                val lastSlot = callLogDb.getLastSimSlot(number)
                if (lastSlot >= 0) {
                    Log.d(TAG, "记忆模式：上次卡${lastSlot + 1}，继续用卡${lastSlot + 1}")
                    lastSlot
                } else {
                    Log.d(TAG, "记忆模式：首次拨打，弹窗选择")
                    -1
                }
            }

            DialMode.POPUP -> {
                Log.d(TAG, "弹窗模式：发送广播弹出选卡卡片")
                -1
            }

            DialMode.OPPOSITE -> {
                // 相反模式：
                // 1. 如果号码从未拨打过 → 按循环模式拨号
                // 2. 如果号码拨打过 → 用另一张卡（卡1→卡2，卡2→卡1）
                val lastDialInfo = callLogDb.getLastDialInfo(number)
                if (lastDialInfo != null && lastDialInfo.first >= 0) {
                    // 号码拨打过，用另一张卡
                    val lastSlot = lastDialInfo.first
                    val oppositeSlot = 1 - lastSlot
                    Log.d(TAG, "相反模式：号码已拨打过（上次卡${lastSlot + 1}），本次用卡${oppositeSlot + 1}")
                    oppositeSlot
                } else {
                    // 号码从未拨打过，按循环模式拨号
                    val globalLast = callLogDb.getLastSimSlotGlobal()
                    val next = if (globalLast >= 0) 1 - globalLast else 0
                    Log.d(TAG, "相反模式：号码未拨打过，按循环模式拨卡${next + 1}")
                    next
                }
            }

            DialMode.ROUND_SELECT -> {
                // 轮选模式：未识别（APP数据库中无此号码）→ 按循环模式拨号；已识别 → 弹窗让用户选择
                // 使用 APP 自身数据库查询（dial_log + sim_cache），不依赖实时查系统通话记录
                // 避免 MIUI 后台限制 contentResolver.query 导致查询失败
                val lastDialInfo = callLogDb.getLastDialInfo(number)
                if (lastDialInfo != null && lastDialInfo.first >= 0) {
                    // APP 数据库中有此号码 → 弹窗
                    Log.d(TAG, "轮选模式：号码已识别（上次卡${lastDialInfo.first + 1}），弹窗选择")
                    -1
                } else {
                    // 未识别 → 按循环模式拨号
                    val globalLast = callLogDb.getLastSimSlotGlobal()
                    val next = if (globalLast >= 0) 1 - globalLast else 0
                    Log.d(TAG, "轮选模式：号码未识别，按循环模式拨卡${next + 1}")
                    next
                }
            }
        }
    }

    /**
     * 从 APP 自身数据库查询该号码最近一次拨号信息（供弹窗显示）
     * 优先查 dial_log（APP 拨号记录），fallback 查 sim_cache（系统通话记录同步缓存）
     * @return Pair(simSlot, timeMs) 或 null
     */
    private fun getLastDialHintForPopup(number: String): Pair<Int, Long>? {
        return try {
            callLogDb.getLastDialInfo(number)
        } catch (e: Exception) {
            Log.e(TAG, "查询上次拨号信息失败: ${e.message}")
            null
        }
    }

    // ==================== 拨号 ====================

    /**
     * 拨号入口：根据拨号模式决定卡选择策略
     */
    private fun dialNumber(number: String) {
        try {
            // 先查上次通话记录，广播提示（给 CallLogFragment 显示）
            notifyLastCallHint(number)

            if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "没有拨号权限")
                _sendResultToPC(number, "error")
                return
            }

            val simSlot = resolveSimSlot(number)

            if (simSlot >= 0) {
                // 直接拨号（指定SIM卡）
                // 广播通知 UI 显示"本次使用卡X"
                broadcastDialSimInfo(number, simSlot)
                performDial(number, simSlot)
            } else {
                // 需要弹窗选择
                pendingDialNumber = number
                val lastHint = getLastDialHintForPopup(number)
                val lastSlot = lastHint?.first ?: -1
                val lastTime = lastHint?.second ?: 0L

                // 直接通过 Service 调用悬浮窗（不依赖 Activity 广播，避免 MIUI 后台丢广播）
                if (SimSelectOverlay.hasPermission(this)) {
                    SimSelectOverlay.show(this, number, lastSlot, lastTime)
                } else {
                    // 没有悬浮窗权限，回退到广播方式（需要 Activity 在前台）
                    Log.w(TAG, "无悬浮窗权限，回退到广播方式弹窗")
                    val intent = Intent(ACTION_SHOW_SIM_SELECT).apply {
                        putExtra("number", number)
                        putExtra("last_sim_slot", lastSlot)
                        putExtra("last_dial_time", lastTime)
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "拨号失败: ${e.message}")
            _sendResultToPC(number, "error")
        }
    }

    /**
     * 实际执行拨号（指定 SIM 卡槽）
     */
    private fun performDial(number: String, simSlot: Int) {
        try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val handle = getPhoneAccountHandle(simSlot)

            val uri = Uri.parse("tel:$number")
            val extras = Bundle()

            if (handle != null) {
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }

            try {
                telecomManager.placeCall(uri, extras)
                Log.d(TAG, "已拨号(TelecomManager, 卡${simSlot + 1}): $number")
                _sendResultToPC(number, "ok")
                callLogDb.insertDial(number, "ok", simSlot)
                notifyNewDial(number)
                copyNumberToClipboard(number)
                showDialAnimation()
                return
            } catch (e: SecurityException) {
                Log.e(TAG, "TelecomManager无权限: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "TelecomManager拨号失败: ${e.message}, 尝试ACTION_CALL")
            }

            // fallback: ACTION_CALL（不带 SIM 指定）
            try {
                val intent = Intent(Intent.ACTION_CALL).apply {
                    data = uri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                Log.d(TAG, "已拨号(ACTION_CALL fallback): $number")
                _sendResultToPC(number, "ok")
                callLogDb.insertDial(number, "ok", simSlot)
                notifyNewDial(number)
                copyNumberToClipboard(number)
                showDialAnimation()
            } catch (e: Exception) {
                Log.e(TAG, "ACTION_CALL也失败: ${e.message}")
                _sendResultToPC(number, "error")
                callLogDb.insertDial(number, "error", simSlot)
            }
        } catch (e: Exception) {
            Log.e(TAG, "拨号异常: ${e.message}")
            _sendResultToPC(number, "error")
        }
    }

    /**
     * 复制号码到剪贴板并弹 Toast 提示（受设置开关控制）
     */
    private fun copyNumberToClipboard(number: String) {
        val prefs = getSharedPreferences("autodial", MODE_PRIVATE)
        val autoCopy = prefs.getBoolean("auto_copy_number", true)
        val copyToast = prefs.getBoolean("copy_toast", false)  // 默认关闭弹窗提醒

        if (!autoCopy) return

        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("phone_number", number))
            Log.d(TAG, "已复制号码到剪贴板: $number")

            // 弹 Toast 提示（受开关控制）
            if (copyToast) {
                handler.post {
                    try {
                        android.widget.Toast.makeText(
                            this@DialService,
                            "已复制$number",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "复制号码到剪贴板失败: ${e.message}")
        }
    }

    /**
     * 显示拨号成功动画（受设置开关控制）
     */
    private fun showDialAnimation() {
        try {
            val prefs = getSharedPreferences("autodial", MODE_PRIVATE)
            val mode = prefs.getInt("dial_animation_mode", DialAnimationOverlay.MODE_OFF)
            if (mode == DialAnimationOverlay.MODE_OFF) return
            handler.post {
                DialAnimationOverlay.show(this@DialService)
            }
        } catch (e: Exception) {
            Log.e(TAG, "显示拨号动画失败: ${e.message}")
        }
    }

    /**
     * 广播通知 UI 当前拨号使用的是哪张卡
     */
    private fun broadcastDialSimInfo(number: String, simSlot: Int) {
        try {
            val intent = Intent(ACTION_LAST_CALL_HINT).apply {
                putExtra("number", number)
                putExtra("hint", "本次使用：卡${simSlot + 1}")
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    // ==================== 上次通话提示 ====================

    private fun notifyLastCallHint(number: String) {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) return

            @Suppress("DEPRECATION")
            val cursor = contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(
                    android.provider.CallLog.Calls.NUMBER,
                    android.provider.CallLog.Calls.DATE,
                    android.provider.CallLog.Calls.PHONE_ACCOUNT_ID
                ),
                "${android.provider.CallLog.Calls.NUMBER} = ?",
                arrayOf(number),
                "${android.provider.CallLog.Calls.DATE} DESC"
            ) ?: return

            cursor.use {
                if (it.moveToFirst()) {
                    val date = it.getLong(it.getColumnIndex(android.provider.CallLog.Calls.DATE))
                    val subId = it.getString(it.getColumnIndex(android.provider.CallLog.Calls.PHONE_ACCOUNT_ID))

                    var simSlot = 0
                    try {
                        val simList = getSimInfoList()
                        if (subId != null) {
                            for (info in simList) {
                                if (info.subscriptionId.toString() == subId) {
                                    simSlot = info.simSlotIndex
                                    break
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    val cal = java.util.Calendar.getInstance()
                    val today = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(cal.time)
                    cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
                    val yesterday = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(cal.time)
                    val dateStr = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(java.util.Date(date))
                    val displayDate = when (dateStr) {
                        today -> "今天"
                        yesterday -> "昨天"
                        else -> dateStr
                    }

                    val hint = "上次：卡${simSlot + 1}  $displayDate"
                    val intent = Intent(ACTION_LAST_CALL_HINT).apply {
                        putExtra("number", number)
                        putExtra("hint", hint)
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "查询上次通话失败: ${e.message}")
        }
    }

    // ==================== 挂断 ====================

    private fun endCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS)
                    != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "缺少 ANSWER_PHONE_CALLS 权限，无法挂断电话")
                    return
                }
                val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                try {
                    tm.endCall()
                    Log.d(TAG, "挂断电话成功")
                } catch (e: SecurityException) {
                    Log.e(TAG, "endCall 权限不足: ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "endCall 失败: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "endCall 异常: ${e.message}")
        }
    }

    // ==================== 通知 UI ====================

    private fun notifyConnectionChange(connected: Boolean, reason: String?) {
        val intent = Intent(ACTION_CONNECTION).apply {
            putExtra("connected", connected)
            putExtra("mode", connectionMode)
            reason?.let { putExtra("reason", it) }
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun notifyNewDial(number: String) {
        try {
            val intent = Intent(ACTION_NEW_DIAL).apply {
                putExtra("number", number)
                putExtra("time", System.currentTimeMillis())
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    // ==================== 通知栏 ====================

    private fun createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, "跨屏拨号 服务", NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        description = "保持拨号连接"
                        setVibrationPattern(longArrayOf(0))  // 禁用振动
                        enableVibration(false)
                    }
                getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
        } catch (_: Exception) {}
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("跨屏拨号").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true).setSilent(true)
            .setVibrate(longArrayOf(0))  // 禁用振动
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }
}
