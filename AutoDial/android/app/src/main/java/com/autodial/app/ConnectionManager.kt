package com.autodial.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * 统一连接管理器
 * 管理 LAN 和 Cloud 两条通道，提供单一入口的 connect/disconnect/send
 * 状态机: DISCONNECTED → DISCOVERING → CONNECTING → CONNECTED
 */
class ConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "ConnectionMgr"
        private const val LAN_PORT = 35432
        private const val DISCOVERY_PORT = 35433
        private const val DISCOVERY_TIMEOUT_MS = 5000L
    }

    // ==================== 状态 ====================

    enum class ConnectionState {
        DISCONNECTED, DISCOVERING, CONNECTING, CONNECTED
    }

    sealed class ConnectionError {
        data class LanDiscoveryFailed(val reason: String) : ConnectionError()
        data class LanConnectFailed(val reason: String) : ConnectionError()
        data class CloudConnectFailed(val server: String, val reason: String) : ConnectionError()
        data class AuthFailed(val reason: String) : ConnectionError()
        data class Disconnected(val reason: String) : ConnectionError()
    }

    interface ConnectionStateListener {
        fun onStateChanged(newState: ConnectionState, oldState: ConnectionState)
        fun onMessageReceived(msg: JSONObject)
        fun onError(error: ConnectionError)
    }

    // ==================== 内部状态 ====================

    private var state: ConnectionState = ConnectionState.DISCONNECTED
    private var transportMode: String = "" // "lan" | "cloud" | "lan+cloud" | ""

    // WebSocket
    private var lanWebSocket: WebSocket? = null
    private var cloudWebSocket: WebSocket? = null

    // OkHttp
    private val lanClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val cloudClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // Handler
    private val handler = Handler(Looper.getMainLooper())

    // 重连
    private var reconnectRunnable: Runnable? = null
    private var cloudReconnectRunnable: Runnable? = null
    private var cloudReconnectAttempts = 0

    // 心跳（应用层 ping，让 PC 端更新 lastHeartbeat）
    private var heartbeatRunnable: Runnable? = null
    private val HEARTBEAT_INTERVAL_MS = 30000L // 30 秒

    // 配置
    private var lastPin = ""
    private var lastLanIp = ""
    private var currentCloudServer = ""
    private var cloudServerList: List<String> = emptyList()
    private var autoReconnect = true
    private var manualConnecting = false

    // SharedPreferences
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("autodial", Context.MODE_PRIVATE)
    }

    // 监听器
    private val listeners = mutableListOf<ConnectionStateListener>()

    // 公开状态（供 DialService 读取，保持向后兼容）
    val isConnected: Boolean get() = state == ConnectionState.CONNECTED
    val connectionMode: String get() = transportMode
    val isCloudConnected: Boolean get() = cloudWebSocket != null && transportMode.contains("cloud")

    // ==================== 公开 API ====================

    /**
     * 单一入口：尝试连接。先 LAN 发现，失败自动 fallback 到 Cloud。
     * @param pin 配对码
     * @param hintIp 可选的已知 IP（从 ConnectFragment 发现获取），非空则跳过 LAN 发现
     */
    fun connect(pin: String, hintIp: String = "") {
        Log.d(TAG, "connect(pin=$pin, hintIp=$hintIp)")
        cancelReconnect()
        cancelCloudReconnect()

        lastPin = pin
        manualConnecting = true

        if (hintIp.isNotEmpty()) {
            // 已知 IP，直接连接 LAN
            lastLanIp = hintIp
            setState(ConnectionState.CONNECTING)
            connectLan(hintIp, pin)
        } else {
            // 从 SharedPreferences 恢复已知 IP
            lastLanIp = prefs.getString("ip", "") ?: ""
            // 开始 LAN 发现
            setState(ConnectionState.DISCOVERING)
            startLanDiscovery(pin)
        }
    }

    /**
     * 同时断开 LAN 和 Cloud 连接
     */
    fun disconnect() {
        Log.d(TAG, "disconnect()")
        manualConnecting = false
        cancelReconnect()
        cancelCloudReconnect()
        cloudReconnectAttempts = 0

        try { lanWebSocket?.cancel() } catch (_: Exception) {}
        lanWebSocket = null
        try { cloudWebSocket?.cancel() } catch (_: Exception) {}
        cloudWebSocket = null

        setState(ConnectionState.DISCONNECTED)
    }

    /**
     * 只断开 Cloud 连接，保留 LAN 连接
     */
    fun disconnectCloud() {
        Log.d(TAG, "disconnectCloud()")
        cancelCloudReconnect()
        cloudReconnectAttempts = 0

        try { cloudWebSocket?.cancel() } catch (_: Exception) {}
        cloudWebSocket = null

        if (transportMode.contains("cloud")) {
            transportMode = if (transportMode.contains("lan")) "lan" else ""
            if (transportMode.isEmpty()) {
                setState(ConnectionState.DISCONNECTED)
            }
            // 如果 LAN 还在，不改变 CONNECTED 状态
        }
    }

    /**
     * 直接连接云端（跳过 LAN 发现）
     * @param pin 配对码
     */
    fun connectCloudOnly(pin: String) {
        Log.d(TAG, "connectCloudOnly(pin=$pin)")
        cancelReconnect()
        cancelCloudReconnect()
        lastPin = pin
        manualConnecting = true

        if (cloudServerList.isEmpty()) {
            Log.d(TAG, "No cloud servers configured, giving up")
            setState(ConnectionState.DISCONNECTED)
            manualConnecting = false
            return
        }
        setState(ConnectionState.CONNECTING)
        connectCloud(cloudServerList, pin)
    }

    /**
     * 发送消息，自动选择 LAN（优先）或 Cloud
     * @return true 如果发送成功
     */
    fun send(msg: JSONObject): Boolean {
        // 优先 LAN
        if (transportMode.contains("lan") && lanWebSocket != null) {
            try {
                val sent = lanWebSocket?.send(msg.toString()) ?: false
                if (sent) return true
            } catch (_: Exception) {
                Log.w(TAG, "LAN send failed, trying fallback")
                lanWebSocket = null
                // LAN 异常，如果云端可用则切换
                if (cloudWebSocket != null && transportMode.contains("cloud")) {
                    transportMode = "cloud"
                } else {
                    transportMode = ""
                    setState(ConnectionState.DISCONNECTED)
                }
            }
        }

        // Cloud 降级
        if (transportMode.contains("cloud") && cloudWebSocket != null) {
            try {
                val sent = cloudWebSocket?.send(msg.toString()) ?: false
                if (sent) return true
            } catch (_: Exception) {
                Log.w(TAG, "Cloud send failed")
                cloudWebSocket = null
                if (transportMode == "cloud") {
                    transportMode = ""
                    setState(ConnectionState.DISCONNECTED)
                }
            }
        }

        // 最后兜底：不管 transportMode 都尝试
        if (lanWebSocket != null) {
            try { if (lanWebSocket?.send(msg.toString()) == true) return true } catch (_: Exception) {}
        }
        if (cloudWebSocket != null) {
            try { cloudWebSocket?.send(msg.toString()); return true } catch (_: Exception) {}
        }

        return false
    }

    fun getState(): ConnectionState = state

    fun getTransportMode(): String = transportMode

    fun addListener(listener: ConnectionStateListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: ConnectionStateListener) {
        listeners.remove(listener)
    }

    /**
     * 从 SharedPreferences 加载保存的配置，用于自动重连
     */
    fun loadSavedConfig() {
        autoReconnect = prefs.getBoolean("auto_reconnect", true)
        val wasConnected = prefs.getBoolean("was_connected", false)
        lastPin = prefs.getString("pin", "") ?: ""
        lastLanIp = prefs.getString("ip", "") ?: ""
        currentCloudServer = prefs.getString("cloud_server", "") ?: ""
        val cloudEnabled = prefs.getBoolean("cloud_enabled", false)
        val serversJson = prefs.getString("cloud_servers", null)

        cloudServerList = if (serversJson != null) {
            try {
                val arr = JSONArray(serversJson)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) {
                if (currentCloudServer.isNotEmpty()) listOf(currentCloudServer) else emptyList()
            }
        } else {
            if (currentCloudServer.isNotEmpty()) listOf(currentCloudServer) else emptyList()
        }

        if (wasConnected && lastLanIp.isNotEmpty() && lastPin.isNotEmpty() && !isConnected) {
            // LAN 自动重连（LAN 失败后会自动 fallback 到云端，不会遗漏云端）
            connect(lastPin, lastLanIp)
        } else if (cloudEnabled && cloudServerList.isNotEmpty() && lastPin.isNotEmpty() && !isConnected) {
            // 只启用云端时，直接连云端
            connectCloud(cloudServerList, lastPin)
        }
    }

    /**
     * 更新云服务器列表
     */
    fun setCloudServers(servers: List<String>) {
        cloudServerList = servers
        if (servers.isNotEmpty()) {
            currentCloudServer = servers[0]
        }
    }

    /**
     * 清理所有资源（在 Service.onDestroy 时调用）
     */
    fun cleanup() {
        disconnect()
        listeners.clear()
    }

    // ==================== 上传协议桩方法 ====================

    /**
     * 桩: 发送文件到 PC
     * 未来实现: 分片读取文件 → Base64 编码 → file_upload_start + file_chunk + file_upload_complete
     */
    fun sendFile(filePath: String, callback: ((Boolean, String?) -> Unit)?): Boolean {
        Log.w(TAG, "[UPLOAD-STUB] sendFile not yet implemented: $filePath")
        callback?.invoke(false, "Not implemented")
        return false
    }

    /**
     * 桩: 发送二进制数据到 PC
     * 未来实现: Base64 编码 → 分片 → file_upload_start + file_chunk + file_upload_complete
     */
    fun sendData(data: ByteArray, mimeType: String, fileName: String, callback: ((Boolean, String?) -> Unit)?): Boolean {
        Log.w(TAG, "[UPLOAD-STUB] sendData not yet implemented: $fileName (${data.size} bytes)")
        callback?.invoke(false, "Not implemented")
        return false
    }

    // ==================== 内部: LAN 发现 ====================

    private fun startLanDiscovery(pin: String) {
        Thread {
            var discoveredIp: String? = null
            try {
                val socket = DatagramSocket(null)
                socket.soTimeout = DISCOVERY_TIMEOUT_MS.toInt()
                socket.reuseAddress = true

                val discoverMsg = JSONObject().apply {
                    put("type", "discover"); put("pin", pin)
                }.toString().toByteArray()

                // 发送 3 次 UDP 广播
                for (i in 0 until 3) {
                    try {
                        val packet = DatagramPacket(
                            discoverMsg, discoverMsg.size,
                            InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT
                        )
                        socket.send(packet)
                    } catch (_: Exception) {}
                    if (i < 2) Thread.sleep(200)
                }

                // 监听响应
                val buffer = ByteArray(1024)
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < DISCOVERY_TIMEOUT_MS) {
                    try {
                        val response = DatagramPacket(buffer, buffer.size)
                        socket.receive(response)
                        val data = JSONObject(String(response.data, 0, response.length))
                        val type = data.optString("type", "")
                        if ((type == "found" || type == "announce") && data.optString("pin") == pin) {
                            discoveredIp = data.optString("ip", "")
                            if (discoveredIp!!.isNotEmpty()) break
                        }
                    } catch (_: Exception) {}
                }
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "LAN discovery error: ${e.message}")
            }

            handler.post {
                if (discoveredIp != null && discoveredIp!!.isNotEmpty()) {
                    Log.d(TAG, "LAN discovered: $discoveredIp")
                    lastLanIp = discoveredIp!!
                    setState(ConnectionState.CONNECTING)
                    connectLan(discoveredIp!!, pin)
                } else {
                    Log.d(TAG, "LAN discovery failed, falling back to cloud")
                    connectCloud(cloudServerList, pin)
                }
            }
        }.start()
    }

    // ==================== 内部: LAN 连接 ====================

    private fun connectLan(ip: String, pin: String) {
        try {
            try { lanWebSocket?.cancel() } catch (_: Exception) {}
            lanWebSocket = null

            val url = "ws://$ip:$LAN_PORT"
            Log.d(TAG, "LAN connecting: $url")

            val request = Request.Builder().url(url).build()
            lanWebSocket = lanClient.newWebSocket(request, createLanListener(pin))
        } catch (e: Exception) {
            Log.e(TAG, "LAN connect error: ${e.message}")
            handler.post {
                notifyError(ConnectionError.LanConnectFailed(e.message ?: "unknown"))
                connectCloud(cloudServerList, pin)
            }
        }
    }

    private fun createLanListener(pin: String): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "LAN WebSocket opened")
                try {
                    val deviceName = android.os.Build.MODEL ?: android.os.Build.DEVICE ?: "Android"
                    ws.send(JSONObject().apply {
                        put("type", "phone_hello"); put("pin", pin)
                        put("deviceName", deviceName)
                    }.toString())
                } catch (e: Exception) { Log.e(TAG, "LAN hello send failed: ${e.message}") }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    when (msg.optString("type", "")) {
                        "auth_ok" -> {
                            Log.d(TAG, "LAN auth OK")
                            manualConnecting = false
                            transportMode = if (isCloudConnected) "lan+cloud" else "lan"
                            setState(ConnectionState.CONNECTED)
                        }
                        "auth_fail" -> {
                            Log.w(TAG, "LAN auth failed")
                            manualConnecting = false
                            handler.post { notifyError(ConnectionError.AuthFailed(msg.optString("reason", ""))) }
                            ws.close(1000, "auth_fail")
                            connectCloud(cloudServerList, pin)
                        }
                        "pong" -> {}
                        "kicked" -> {
                            Log.w(TAG, "Kicked by PC")
                            manualConnecting = false
                            setState(ConnectionState.DISCONNECTED)
                            handler.post { notifyError(ConnectionError.Disconnected("kicked")) }
                        }
                        else -> {
                            // dial, sms, hangup 等业务消息 → 通知 DialService
                            handler.post { notifyMessage(msg) }
                        }
                    }
                } catch (e: Exception) { Log.e(TAG, "LAN message error: ${e.message}") }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                try { ws.close(1000, null) } catch (_: Exception) {}
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "LAN closed code=$code")
                handleLanDisconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "LAN failure: ${t.message}")
                handleLanDisconnect()
                // 如果正在主动连接中，降级到云端
                if (state == ConnectionState.CONNECTING) {
                    connectCloud(cloudServerList, pin)
                }
            }
        }
    }

    private fun handleLanDisconnect() {
        try { lanWebSocket?.cancel() } catch (_: Exception) {}
        lanWebSocket = null

        val wasLan = transportMode.contains("lan")
        if (wasLan) {
            // 如果还有云端连接，切换到云端
            if (isCloudConnected) {
                transportMode = "cloud"
                Log.d(TAG, "LAN disconnected, fell back to cloud")
                // state 保持 CONNECTED，不通知断开
            } else {
                setState(ConnectionState.DISCONNECTED)
                if (autoReconnect) scheduleReconnect()
            }
        }
    }

    // ==================== 内部: Cloud 连接 ====================

    private fun connectCloud(servers: List<String>, pin: String) {
        if (servers.isEmpty() || pin.isEmpty()) {
            Log.d(TAG, "No cloud servers or pin, giving up")
            if (state != ConnectionState.CONNECTED) {
                setState(ConnectionState.DISCONNECTED)
                manualConnecting = false
            }
            return
        }
        tryConnectCloudAtIndex(servers, pin, 0)
    }

    private fun tryConnectCloudAtIndex(servers: List<String>, pin: String, index: Int) {
        if (index >= servers.size) {
            Log.d(TAG, "All cloud servers failed")
            if (state != ConnectionState.CONNECTED) {
                setState(ConnectionState.DISCONNECTED)
                manualConnecting = false
            }
            return
        }

        val server = servers[index]
        Log.d(TAG, "Trying cloud ${index + 1}/${servers.size}: $server")
        currentCloudServer = server

        if (state == ConnectionState.DISCOVERING || state == ConnectionState.DISCONNECTED) {
            setState(ConnectionState.CONNECTING)
        }

        try {
            cancelCloudReconnect()
            try { cloudWebSocket?.cancel() } catch (_: Exception) {}
            cloudWebSocket = null

            val url = if (server.startsWith("ws://") || server.startsWith("wss://")) server
                      else "ws://$server"

            val request = Request.Builder().url(url).build()
            cloudWebSocket = cloudClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d(TAG, "Cloud WebSocket opened")
                    try {
                        val deviceName = android.os.Build.MODEL ?: android.os.Build.DEVICE ?: "Android"
                        ws.send(JSONObject().apply {
                            put("type", "phone_hello"); put("pin", pin)
                            put("deviceName", deviceName)
                        }.toString())
                    } catch (e: Exception) { Log.e(TAG, "Cloud hello send failed: ${e.message}") }
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    try {
                        val msg = JSONObject(text)
                        when (msg.optString("type", "")) {
                            "auth_ok" -> {
                                Log.d(TAG, "Cloud auth OK")
                                cloudReconnectAttempts = 0
                                manualConnecting = false
                                transportMode = if (transportMode.contains("lan")) "lan+cloud" else "cloud"
                                setState(ConnectionState.CONNECTED)
                            }
                            "auth_fail" -> {
                                Log.w(TAG, "Cloud auth failed")
                                handler.post { notifyError(ConnectionError.AuthFailed(msg.optString("reason", ""))) }
                                ws.close(1000, "auth_fail")
                                // 尝试下一个服务器
                                tryConnectCloudAtIndex(servers, pin, index + 1)
                            }
                            "pong" -> {}
                            else -> {
                                // 业务消息 → 通知 DialService
                                handler.post { notifyMessage(msg) }
                            }
                        }
                    } catch (e: Exception) { Log.e(TAG, "Cloud message error: ${e.message}") }
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    try { ws.close(1000, null) } catch (_: Exception) {}
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "Cloud closed code=$code")
                    handleCloudDisconnect()
                    scheduleCloudReconnect()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "Cloud failure: ${t.message}")
                    handleCloudDisconnect()
                    // 如果正在主动连接中，尝试下一个
                    if (state == ConnectionState.CONNECTING) {
                        tryConnectCloudAtIndex(servers, pin, index + 1)
                    } else {
                        scheduleCloudReconnect()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Cloud connect error: ${e.message}")
            tryConnectCloudAtIndex(servers, pin, index + 1)
        }
    }

    private fun handleCloudDisconnect() {
        try { cloudWebSocket?.cancel() } catch (_: Exception) {}
        cloudWebSocket = null

        val wasCloud = transportMode.contains("cloud")
        if (wasCloud) {
            if (transportMode.contains("lan")) {
                transportMode = "lan"
                Log.d(TAG, "Cloud disconnected, LAN still active")
            } else {
                setState(ConnectionState.DISCONNECTED)
            }
        }
    }

    // ==================== 内部: 重连 ====================

    private fun scheduleReconnect() {
        cancelReconnect()
        if (!autoReconnect || lastLanIp.isEmpty() || lastPin.isEmpty()) return
        if (isConnected) return

        reconnectRunnable = Runnable {
            if (!isConnected && lastLanIp.isNotEmpty() && lastPin.isNotEmpty()) {
                Log.d(TAG, "LAN auto-reconnect to $lastLanIp")
                connect(lastPin, lastLanIp)
            }
        }
        handler.postDelayed(reconnectRunnable!!, 3000)
    }

    private fun cancelReconnect() {
        try { reconnectRunnable?.let { handler.removeCallbacks(it) } } catch (_: Exception) {}
        reconnectRunnable = null
    }

    private fun scheduleCloudReconnect() {
        cancelCloudReconnect()
        if (!autoReconnect || lastPin.isEmpty()) return
        if (isCloudConnected) return

        cloudReconnectAttempts++
        val delaySec = when (cloudReconnectAttempts) {
            1 -> 5; 2 -> 10; 3 -> 20; 4 -> 40; else -> 60
        }

        cloudReconnectRunnable = Runnable {
            if (!isCloudConnected && cloudServerList.isNotEmpty()) {
                Log.d(TAG, "Cloud auto-reconnect (attempt $cloudReconnectAttempts)")
                connectCloud(cloudServerList, lastPin)
            }
        }
        handler.postDelayed(cloudReconnectRunnable!!, delaySec * 1000L)
    }

    private fun cancelCloudReconnect() {
        try { cloudReconnectRunnable?.let { handler.removeCallbacks(it) } } catch (_: Exception) {}
        cloudReconnectRunnable = null
    }

    // ==================== 内部: 状态管理 ====================

    private fun setState(newState: ConnectionState) {
        val oldState = state
        if (oldState == newState) return
        state = newState

        Log.d(TAG, "State: $oldState → $newState, transport=$transportMode")
        handler.post { notifyStateChange(oldState, newState) }

        // 连接成功时启动应用层心跳，断开时停止
        if (newState == ConnectionState.CONNECTED) {
            startHeartbeat()
        } else if (newState == ConnectionState.DISCONNECTED) {
            stopHeartbeat()
        }
    }

    private fun notifyStateChange(oldState: ConnectionState, newState: ConnectionState) {
        listeners.forEach { listener ->
            try { listener.onStateChanged(newState, oldState) } catch (_: Exception) {}
        }
    }

    private fun notifyMessage(msg: JSONObject) {
        listeners.forEach { listener ->
            try { listener.onMessageReceived(msg) } catch (_: Exception) {}
        }
    }

    // ==================== 应用层心跳 ====================

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatRunnable = Runnable {
            if (state == ConnectionState.CONNECTED) {
                val pingMsg = JSONObject().put("type", "ping")
                try {
                    lanWebSocket?.send(pingMsg.toString())
                } catch (_: Exception) {}
                try {
                    cloudWebSocket?.send(pingMsg.toString())
                } catch (_: Exception) {}
                handler.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL_MS)
            }
        }
        handler.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL_MS)
        Log.d(TAG, "Heartbeat started")
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let {
            handler.removeCallbacks(it)
            heartbeatRunnable = null
            Log.d(TAG, "Heartbeat stopped")
        }
    }

    private fun notifyError(error: ConnectionError) {
        listeners.forEach { listener ->
            try { listener.onError(error) } catch (_: Exception) {}
        }
    }
}
