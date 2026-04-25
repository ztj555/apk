package com.autodial.app

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
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
        private const val ACTION_CONNECTION = "com.autodial.CONNECTION_CHANGE"
        private const val ACTION_NEW_DIAL = "com.autodial.NEW_DIAL"
        private const val ACTION_CALL_ENDED = "com.autodial.CALL_ENDED"

        var isRunning = false
            private set
        var isConnected = false
            private set
        var serverAddress = ""
            private set

        fun newIntent(context: Context): Intent = Intent(context, DialService::class.java)

        // 供 DialConfirmActivity 调用，回报拨号结果给电脑
        fun sendDialResult(number: String, status: String) {
            _sendResult?.invoke(number, status)
        }
        private var _sendResult: ((String, String) -> Unit)? = null
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var lastPin = ""
    private var lastIp = ""
    private var manualConnecting = false
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var callLogDb: CallLogDb
    private var phoneStateListener: PhoneStateListener? = null

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        try {
            isRunning = true
            callLogDb = CallLogDb(this)
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification("AutoDial 运行中"))

            // 保持CPU唤醒，防止MIUI杀掉后台WebSocket连接
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autodial:wake").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // 12小时后自动释放
            }

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
        } catch (e: Exception) {
            isRunning = true
            callLogDb = CallLogDb(this)
            createNotificationChannel()
            try { startForeground(NOTIFICATION_ID, buildNotification("AutoDial 运行中")) } catch (_: Exception) {}
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
            disconnect(); handler.removeCallbacksAndMessages(null)
            isRunning = false; isConnected = false; wakeLock?.release(); wakeLock = null
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
                        ws.send(JSONObject().apply {
                            put("type", "phone_hello"); put("pin", pin)
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
                                handler.post {
                                    updateNotification("已连接到电脑")
                                    getSharedPreferences("autodial", MODE_PRIVATE).edit()
                                        .putBoolean("was_connected", true).apply()
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
                        }
                    } catch (e: Exception) { Log.e(TAG, "处理消息失败: ${e.message}") }
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    try { ws.close(1000, null) } catch (_: Exception) {}
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "关闭 code=$code reason=$reason")
                    onDisconnected()
                    // 自动重连的断开只更新通知栏，不弹Toast
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
                    // 自动重连的失败只更新通知栏，不弹Toast
                    if (isAutoReconnect) {
                        handler.post { updateNotification("连接失败，正在重连...") }
                    } else {
                        handler.post { notifyConnectionChange(false, "connection_failed") }
                    }
                    scheduleReconnect()
                }
            })

            // 设置发送结果的回调
            _sendResult = { number, status ->
                try {
                    webSocket?.send(JSONObject().apply {
                        put("type", "dial_result"); put("number", number); put("status", status)
                    }.toString())
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建连接失败: ${e.message}")
            handler.post { notifyConnectionChange(false, "connection_failed") }
        }
    }

    private fun onDisconnected() {
        if (isConnected) {
            isConnected = false
            handler.post {
                updateNotification("连接已断开")
                notifyConnectionChange(false, "disconnected")
            }
        }
    }

    private fun scheduleReconnect() {
        cancelReconnect()
        // 检查用户是否开启了自动连接
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
        webSocket = null; isConnected = false
        _sendResult = null
        updateNotification("AutoDial 运行中")
    }

    // ==================== 通话状态监听 ====================

    /**
     * 监听系统通话状态。当通话从非空闲变为空闲(IDLE)时，说明通话结束了。
     * 此时发广播通知 CallLogFragment 和 StatsFragment 延迟1秒刷新。
     * PhoneStateListener 是被动回调，不轮询，CPU 开销几乎为零。
     */
    private fun registerCallStateListener() {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            phoneStateListener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    when (state) {
                        TelephonyManager.CALL_STATE_IDLE -> {
                            // 通话结束，广播通知刷新
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
            val intent = Intent(ACTION_CALL_ENDED).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    // ==================== 通知 UI ====================

    private fun notifyConnectionChange(connected: Boolean, reason: String?) {
        val intent = Intent(ACTION_CONNECTION).apply {
            putExtra("connected", connected)
            reason?.let { putExtra("reason", it) }
            // Android 8.0+ 需要显式设置包名
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

    // ==================== 拨号 ====================

    private fun dialNumber(number: String) {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "没有拨号权限")
                _sendResult?.invoke(number, "error")
                return
            }

            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager

            // 用 TelecomManager.placeCall() 直接拨号（不指定SIM卡，用系统默认）
            // 这样不会弹SIM选择框，且不需要启动Activity（MIUI不会拦截）
            val uri = Uri.parse("tel:$number")
            val extras = Bundle()
            try {
                telecomManager.placeCall(uri, extras)
                Log.d(TAG, "已拨号(TelecomManager): $number")
                _sendResult?.invoke(number, "ok")
                callLogDb.insertDial(number, "ok")
                notifyNewDial(number)
                return
            } catch (e: SecurityException) {
                Log.e(TAG, "TelecomManager无权限: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "TelecomManager拨号失败: ${e.message}, 尝试ACTION_CALL")
            }

            // fallback: ACTION_CALL
            try {
                val intent = Intent(Intent.ACTION_CALL).apply {
                    data = uri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                Log.d(TAG, "已拨号(ACTION_CALL): $number")
                _sendResult?.invoke(number, "ok")
                callLogDb.insertDial(number, "ok")
                notifyNewDial(number)
            } catch (e: Exception) {
                Log.e(TAG, "ACTION_CALL也失败: ${e.message}")
                _sendResult?.invoke(number, "error")
                callLogDb.insertDial(number, "error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "拨号失败: ${e.message}")
            _sendResult?.invoke(number, "error")
        }
    }

    // ==================== 挂断 ====================

    private fun endCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // 先检查是否有 ANSWER_PHONE_CALLS 权限
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

    // ==================== 通知栏 ====================

    private fun createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, "AutoDial 服务", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "保持拨号连接" }
                getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
        } catch (_: Exception) {}
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoDial").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true).setSilent(true).build()
    }

    private fun updateNotification(text: String) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }
}
