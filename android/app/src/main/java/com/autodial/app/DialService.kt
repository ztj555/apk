package com.autodial.app

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.ComponentName
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
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
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
        private const val ACTION_LAST_CALL_HINT = "com.autodial.LAST_CALL_HINT"
        /** 拨号前需要用户选卡时发出此广播，MainActivity 弹出 SimSelectBottomSheet */
        const val ACTION_SHOW_SIM_SELECT = "com.autodial.SHOW_SIM_SELECT"

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

    /** 当前正在等待用户选卡的号码 */
    private var pendingDialNumber: String? = null

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
                    _sendResult?.invoke(number, "cancelled")
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
            isRunning = false; isConnected = false
            wakeLock?.release(); wakeLock = null
            pendingDialNumber = null
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
        val modeKey = prefs.getString("dial_mode", DialMode.ALTERNATE.key) ?: DialMode.ALTERNATE.key
        val mode = DialMode.fromKey(modeKey)

        return when (mode) {
            DialMode.SIM1 -> 0
            DialMode.SIM2 -> 1

            DialMode.ALTERNATE -> {
                // 查询 APP 自身数据库，最近一次该号码用了哪张卡，就用另一张
                val lastSlot = callLogDb.getLastSimSlot(number)
                if (lastSlot >= 0) {
                    val next = 1 - lastSlot
                    Log.d(TAG, "轮流模式：上次卡${lastSlot + 1}，本次卡${next + 1}")
                    next
                } else {
                    Log.d(TAG, "轮流模式：无历史记录，默认卡1")
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
        }
    }

    /**
     * 从 APP 自身数据库查询该号码最近一次拨号信息（供弹窗显示）
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
                _sendResult?.invoke(number, "error")
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
                val intent = Intent(ACTION_SHOW_SIM_SELECT).apply {
                    putExtra("number", number)
                    putExtra("last_sim_slot", lastHint?.first ?: -1)
                    putExtra("last_dial_time", lastHint?.second ?: 0L)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "拨号失败: ${e.message}")
            _sendResult?.invoke(number, "error")
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
                _sendResult?.invoke(number, "ok")
                callLogDb.insertDial(number, "ok", simSlot)
                notifyNewDial(number)
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
                _sendResult?.invoke(number, "ok")
                callLogDb.insertDial(number, "ok", simSlot)
                notifyNewDial(number)
            } catch (e: Exception) {
                Log.e(TAG, "ACTION_CALL也失败: ${e.message}")
                _sendResult?.invoke(number, "error")
                callLogDb.insertDial(number, "error", simSlot)
            }
        } catch (e: Exception) {
            Log.e(TAG, "拨号异常: ${e.message}")
            _sendResult?.invoke(number, "error")
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
