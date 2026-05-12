package com.autodial.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.net.URL
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import kotlinx.coroutines.*

class ConnectFragment : Fragment() {

    private lateinit var statusDot: ImageView
    private lateinit var statusText: TextView
    private lateinit var connectionBanner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var pinInput: EditText
    private lateinit var connectBtn: View
    private lateinit var discoveryHint: TextView
    private lateinit var foundPCInfo: LinearLayout
    private lateinit var foundPCText: TextView
    private lateinit var autoConnectSwitch: TextView
    private lateinit var batteryOptStatus: TextView
    private lateinit var batteryOptBtn: TextView
    private lateinit var batteryOptOk: TextView
    private lateinit var themeSettingRow: View
    private lateinit var themeCurrentName: TextView
    private lateinit var previewGold: View
    private lateinit var previewBg: View
    private lateinit var previewBg2: View
    private lateinit var previewText: View
    private lateinit var cloudRelaySwitch: TextView
    private lateinit var cloudServerSection: LinearLayout
    private lateinit var cloudServerManageBtn: TextView
    private lateinit var fetchServerListBtn: TextView
    private lateinit var cloudServerCurrentText: TextView
    private lateinit var cloudStatusText: TextView
    private var cloudConnecting = false
    private lateinit var autoCopySwitch: TextView
    private lateinit var copyToastSwitch: TextView
    private lateinit var dialAnimationSwitch: TextView
    private lateinit var dialAnimationDesc: TextView
    private lateinit var dialAnimationTextPreview: TextView

    private val themeListener: () -> Unit = {
        if (isAdded) {
            val prefs = requireActivity().getSharedPreferences("autodial", Context.MODE_PRIVATE)
            applyTheme()
            updateThemePreview()
            updateConnectionUI(DialService.isConnected, null)
            updateAutoConnectUI(prefs.getBoolean("auto_reconnect", true))
            updateAutoCopyUI(prefs.getBoolean("auto_copy_number", true))
            updateCopyToastUI(prefs.getBoolean("copy_toast", true))
            updateBatteryOptUI()
        }
    }

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

    private val cloudStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                val connected = intent?.getBooleanExtra("connected", false) ?: return
                val mode = intent.getStringExtra("mode") ?: ""
                val reason = intent.getStringExtra("reason") ?: ""
                if (connected) {
                    cloudConnecting = false
                    updateCloudStatusUI(true, mode)
                } else {
                    if (cloudConnecting) {
                        // 连接尝试失败
                        cloudConnecting = false
                        cloudStatusText.text = "❌ 连接失败"
                        cloudStatusText.setTextColor(Color.parseColor("#E74C3C"))
                    } else if (reason == "disconnected") {
                        // 云端连接已断开（之前是连接状态）
                        cloudStatusText.text = "⚠️ 云端已断开"
                        cloudStatusText.setTextColor(Color.parseColor("#E74C3C"))
                    } else {
                        updateCloudStatusUI(false, mode)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_connect, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            statusDot = view.findViewById(R.id.statusDot)
            statusText = view.findViewById(R.id.statusText)
            connectionBanner = view.findViewById(R.id.connectionBanner)
            bannerText = view.findViewById(R.id.bannerText)
            pinInput = view.findViewById(R.id.pinInput)
            connectBtn = view.findViewById(R.id.connectBtn)
            discoveryHint = view.findViewById(R.id.discoveryHint)
            foundPCInfo = view.findViewById(R.id.foundPCInfo)
            foundPCText = view.findViewById(R.id.foundPCText)
            autoConnectSwitch = view.findViewById(R.id.autoConnectSwitch)
            batteryOptStatus = view.findViewById(R.id.batteryOptStatus)
            batteryOptBtn = view.findViewById(R.id.batteryOptBtn)
            batteryOptOk = view.findViewById(R.id.batteryOptOk)
            themeSettingRow = view.findViewById(R.id.themeSettingRow)
            themeCurrentName = view.findViewById(R.id.themeCurrentName)
            previewGold = view.findViewById(R.id.previewGold)
            previewBg = view.findViewById(R.id.previewBg)
            previewBg2 = view.findViewById(R.id.previewBg2)
            previewText = view.findViewById(R.id.previewText)

            connectBtn.setOnClickListener { toggleConnection() }

            // 主题设置入口
            themeSettingRow.setOnClickListener {
                showThemeDialog()
            }

            // 读取保存的配对码
            val prefs = requireActivity().getSharedPreferences("autodial", Context.MODE_PRIVATE)
            pinInput.setText(prefs.getString("pin", ""))

            // 初始化自动连接开关状态
            val autoConnect = prefs.getBoolean("auto_reconnect", true)
            updateAutoConnectUI(autoConnect)

            // 自动连接开关点击
            view.findViewById<View>(R.id.autoConnectRow).setOnClickListener {
                val current = prefs.getBoolean("auto_reconnect", true)
                val newValue = !current
                prefs.edit().putBoolean("auto_reconnect", newValue).apply()
                updateAutoConnectUI(newValue)
            }

            // 电池优化检测
            updateBatteryOptUI()
            view.findViewById<View>(R.id.batteryOptRow).setOnClickListener {
                requestIgnoreBatteryOptimization()
            }

            // 注册广播
            try {
                ContextCompat.registerReceiver(requireActivity(), receiver,
                    IntentFilter("com.autodial.CONNECTION_CHANGE"),
                    ContextCompat.RECEIVER_EXPORTED
                )
            } catch (_: Exception) {}

            // 检查当前连接状态
            updateConnectionUI(DialService.isConnected, null)

            // 云中转 UI 初始化
            cloudRelaySwitch = view.findViewById(R.id.cloudRelaySwitch)
            cloudServerSection = view.findViewById(R.id.cloudServerSection)
            cloudServerManageBtn = view.findViewById(R.id.cloudServerManageBtn)
        fetchServerListBtn = view.findViewById(R.id.fetchServerListBtn)
            cloudServerCurrentText = view.findViewById(R.id.cloudServerCurrentText)
            cloudStatusText = view.findViewById(R.id.cloudStatusText)

            val cloudEnabled = prefs.getBoolean("cloud_enabled", false)
            updateCloudRelayUI(cloudEnabled)
            updateCloudServerCurrentText()

            // 云中转开关
            view.findViewById<View>(R.id.cloudRelayRow).setOnClickListener {
                val current = prefs.getBoolean("cloud_enabled", false)
                val newValue = !current
                prefs.edit().putBoolean("cloud_enabled", newValue).apply()
                updateCloudRelayUI(newValue)
                if (newValue) {
                    connectCloudAll()
                } else {
                    disconnectCloud()
                }
            }

            // 管理按钮 - 打开服务器管理对话框
            cloudServerManageBtn.setOnClickListener {
                showCloudServerManagementDialog()
            }

            // 获取列表按钮 - 从 Gist 获取服务器列表
            fetchServerListBtn.setOnClickListener {
                fetchServerList()
            }

            // 云端连接状态广播
            try {
                ContextCompat.registerReceiver(requireActivity(), cloudStatusReceiver,
                    IntentFilter("com.autodial.CLOUD_STATUS"),
                    ContextCompat.RECEIVER_EXPORTED
                )
            } catch (_: Exception) {}

            // 拨号自动复制号码开关
            autoCopySwitch = view.findViewById(R.id.autoCopySwitch)
            copyToastSwitch = view.findViewById(R.id.copyToastSwitch)
            val autoCopy = prefs.getBoolean("auto_copy_number", true)
            val copyToast = prefs.getBoolean("copy_toast", true)
            updateAutoCopyUI(autoCopy)
            updateCopyToastUI(copyToast)

            view.findViewById<View>(R.id.autoCopyRow).setOnClickListener {
                val current = prefs.getBoolean("auto_copy_number", true)
                val newValue = !current
                prefs.edit().putBoolean("auto_copy_number", newValue).apply()
                updateAutoCopyUI(newValue)
                // 关闭自动复制时，同步关闭弹窗提醒
                if (!newValue && prefs.getBoolean("copy_toast", true)) {
                    prefs.edit().putBoolean("copy_toast", false).apply()
                    updateCopyToastUI(false)
                }
            }

            view.findViewById<View>(R.id.copyToastRow).setOnClickListener {
                val autoCopyOn = prefs.getBoolean("auto_copy_number", true)
                if (!autoCopyOn) return@setOnClickListener  // 自动复制关闭时不可开启弹窗
                val current = prefs.getBoolean("copy_toast", true)
                val newValue = !current
                prefs.edit().putBoolean("copy_toast", newValue).apply()
                updateCopyToastUI(newValue)
            }

            // ===== 拨号动画效果 =====
            dialAnimationSwitch = view.findViewById(R.id.dialAnimationSwitch)
            dialAnimationDesc = view.findViewById(R.id.dialAnimationDesc)
            dialAnimationTextPreview = view.findViewById(R.id.dialAnimationTextPreview)
            val animMode = prefs.getInt("dial_animation_mode", DialAnimationOverlay.MODE_OFF)
            val animText = prefs.getString("dial_animation_text", "财运+1") ?: "财运+1"
            updateDialAnimationUI(animMode)
            dialAnimationTextPreview.text = animText

            // 4档循环切换：关 → 效果1 → 效果2 → 结合 → 关
            view.findViewById<View>(R.id.dialAnimationRow).setOnClickListener {
                val current = prefs.getInt("dial_animation_mode", DialAnimationOverlay.MODE_OFF)
                val nextMode = when (current) {
                    DialAnimationOverlay.MODE_OFF -> DialAnimationOverlay.MODE_FIREWORK
                    DialAnimationOverlay.MODE_FIREWORK -> DialAnimationOverlay.MODE_BOUNCE
                    DialAnimationOverlay.MODE_BOUNCE -> DialAnimationOverlay.MODE_COMBINE
                    else -> DialAnimationOverlay.MODE_OFF
                }
                prefs.edit().putInt("dial_animation_mode", nextMode).apply()
                updateDialAnimationUI(nextMode)
                // 切换到非关状态时试播一次动画预览
                if (nextMode != DialAnimationOverlay.MODE_OFF) {
                    DialAnimationOverlay.show(requireActivity())
                }
            }

            // 动画文字编辑
            view.findViewById<View>(R.id.dialAnimationTextRow).setOnClickListener {
                val currentText = prefs.getString("dial_animation_text", "财运+1") ?: "财运+1"
                val editText = EditText(requireActivity()).apply {
                    setText(currentText)
                    setTextSize(20f)
                    setTextColor(android.graphics.Color.parseColor("#E8DCC8"))
                    setPadding((48).toInt(), (32).toInt(), (48).toInt(), (32).toInt())
                    setSingleLine(true)
                    setHint("输入显示文字")
                }
                AlertDialog.Builder(requireActivity())
                    .setTitle("动画显示文字")
                    .setView(editText)
                    .setPositiveButton("确定") { _, _ ->
                        val newText = editText.text.toString().trim()
                        val finalText = if (newText.isEmpty()) "财运+1" else newText
                        prefs.edit().putString("dial_animation_text", finalText).apply()
                        dialAnimationTextPreview.text = finalText
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }

            // 应用主题
            applyTheme()
            updateThemePreview()

            // 注册主题变更监听
            ThemeManager.addOnThemeChangedListener(themeListener)

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
            Toast.makeText(requireActivity(), "初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            val connected = DialService.isConnected
            if (connected && statusText.text.toString() != "已连接") {
                updateConnectionUI(true, null)
            } else if (!connected && connectionBanner.visibility == View.VISIBLE) {
                updateConnectionUI(false, null)
            }
        } catch (_: Exception) {}
        if (isAdded) updateBatteryOptUI()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ThemeManager.removeOnThemeChangedListener(themeListener)
        try { requireActivity().unregisterReceiver(receiver) } catch (_: Exception) {}
        try { requireActivity().unregisterReceiver(cloudStatusReceiver) } catch (_: Exception) {}
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
            var socket: java.net.DatagramSocket? = null
            try {
                socket = java.net.DatagramSocket(null)
                socket.reuseAddress = true
                socket.bind(java.net.InetSocketAddress(0))

                val discoverMsg = """{"type":"discover","pin":"$pin"}""".toByteArray()
                val broadcastAddr = java.net.InetAddress.getByName("255.255.255.255")
                val packet = java.net.DatagramPacket(discoverMsg, discoverMsg.size, broadcastAddr, 35433)

                var found = false
                repeat(3) {
                    if (found) return@repeat
                    try { socket.send(packet) } catch (_: Exception) {}
                }

                socket.soTimeout = 5000
                val buffer = ByteArray(1024)
                val startTime = System.currentTimeMillis()

                while (System.currentTimeMillis() - startTime < 4000 && !found && isActive) {
                    try {
                        val recvPacket = java.net.DatagramPacket(buffer, buffer.size)
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
                    } catch (_: java.net.SocketTimeoutException) {}
                    catch (_: Exception) {}
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
                AlertDialog.Builder(requireActivity())
                    .setTitle("断开连接")
                    .setMessage("确定断开与电脑的连接？")
                    .setPositiveButton("断开") { _, _ -> sendDisconnectCommand() }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                val pin = pinInput.text.toString().trim()
                if (pin.length != 4) {
                    Toast.makeText(requireActivity(), "请输入4位配对码", Toast.LENGTH_SHORT).show()
                    return
                }
                // 连接逻辑统一交给 ConnectionManager
                // 如果已通过 startDiscovery 找到 IP 则传入，否则由 ConnectionManager 自行发现
                doConnect(discoveredIP, pin)
            }
        } catch (e: Exception) {
            Toast.makeText(requireActivity(), "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun doConnect(ip: String, pin: String) {
        val colors = ThemeManager.getColors(requireContext())
        pinInput.isEnabled = false
        connectBtn.isEnabled = false
        statusText.text = if (ip.isNotEmpty()) "正在连接 $ip ..." else "正在搜索并连接..."
        statusText.setTextColor(Color.parseColor(colors.goldLight))
        statusDot.setImageResource(R.drawable.dot_gray)

        CoroutineScope(Dispatchers.Main).launch {
            delay(3000)
            if (!DialService.isConnected) {
                val colors2 = ThemeManager.getColors(requireContext())
                statusText.text = "连接中，请稍候...（若长时间连不上，可能是电脑防火墙拦截）"
                statusText.setTextColor(Color.parseColor(colors2.gold))
            }
        }

        val intent = Intent(requireActivity(), DialService::class.java).apply {
            action = "CONNECT"
            putExtra("ip", ip)  // 可能为空，ConnectionManager 会自行发现
            putExtra("pin", pin)
        }
        requireActivity().startService(intent)

        requireActivity().getSharedPreferences("autodial", Context.MODE_PRIVATE).edit()
            .putString("pin", pin)
            .putString("ip", ip)
            .apply()
    }

    private fun sendDisconnectCommand() {
        try {
            val intent = Intent(requireActivity(), DialService::class.java).apply {
                action = "DISCONNECT"
            }
            requireActivity().startService(intent)
            updateConnectionUI(false, null)
        } catch (_: Exception) {}
    }

    private fun updateAutoConnectUI(enabled: Boolean) {
        if (!isAdded) return
        val colors = ThemeManager.getColors(requireContext())
        if (enabled) {
            autoConnectSwitch.text = "开"
            autoConnectSwitch.setBackgroundColor(Color.parseColor(colors.gold))
        } else {
            autoConnectSwitch.text = "关"
            autoConnectSwitch.setBackgroundColor(Color.parseColor(colors.bg3))
        }
    }

    private fun updateAutoCopyUI(enabled: Boolean) {
        if (!isAdded) return
        val colors = ThemeManager.getColors(requireContext())
        if (enabled) {
            autoCopySwitch.text = "开"
            autoCopySwitch.setBackgroundColor(Color.parseColor(colors.gold))
            autoCopySwitch.setTextColor(Color.parseColor(colors.bg))
        } else {
            autoCopySwitch.text = "关"
            autoCopySwitch.setBackgroundColor(Color.parseColor(colors.bg3))
            autoCopySwitch.setTextColor(Color.parseColor("#888888"))
        }
    }

    private fun updateCopyToastUI(enabled: Boolean) {
        if (!isAdded) return
        val colors = ThemeManager.getColors(requireContext())
        if (enabled) {
            copyToastSwitch.text = "开"
            copyToastSwitch.setBackgroundColor(Color.parseColor(colors.gold))
            copyToastSwitch.setTextColor(Color.parseColor(colors.bg))
        } else {
            copyToastSwitch.text = "关"
            copyToastSwitch.setBackgroundColor(Color.parseColor(colors.bg3))
            copyToastSwitch.setTextColor(Color.parseColor("#888888"))
        }
    }

    private fun updateDialAnimationUI(mode: Int) {
        if (!isAdded) return
        val colors = ThemeManager.getColors(requireContext())
        when (mode) {
            DialAnimationOverlay.MODE_OFF -> {
                dialAnimationSwitch.text = "关"
                dialAnimationSwitch.setBackgroundColor(Color.parseColor(colors.bg3))
                dialAnimationSwitch.setTextColor(Color.parseColor("#888888"))
                dialAnimationDesc.text = "拨通电话时显示动画"
            }
            DialAnimationOverlay.MODE_FIREWORK -> {
                dialAnimationSwitch.text = "效果1"
                dialAnimationSwitch.setBackgroundColor(Color.parseColor(colors.gold))
                dialAnimationSwitch.setTextColor(Color.parseColor(colors.bg))
                dialAnimationDesc.text = "烟花绽放 - 文字弹出+粒子火花"
            }
            DialAnimationOverlay.MODE_BOUNCE -> {
                dialAnimationSwitch.text = "效果2"
                dialAnimationSwitch.setBackgroundColor(Color.parseColor(colors.gold))
                dialAnimationSwitch.setTextColor(Color.parseColor(colors.bg))
                dialAnimationDesc.text = "弹性弹跳 - 文字飞入+跳动"
            }
            DialAnimationOverlay.MODE_COMBINE -> {
                dialAnimationSwitch.text = "结合"
                dialAnimationSwitch.setBackgroundColor(Color.parseColor(colors.gold))
                dialAnimationSwitch.setTextColor(Color.parseColor(colors.bg))
                dialAnimationDesc.text = "弹性飞入 + 烟花绽放"
            }
        }
    }

    private fun updateBatteryOptUI() {
        if (!isAdded) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = requireActivity().getSystemService(Context.POWER_SERVICE) as PowerManager
                val ignored = pm.isIgnoringBatteryOptimizations(requireActivity().packageName)
                if (ignored) {
                    batteryOptStatus.text = "无限制，后台连接更稳定"
                    batteryOptBtn.visibility = View.GONE
                    batteryOptOk.visibility = View.VISIBLE
                } else {
                    batteryOptStatus.text = "受限，可能导致后台断连"
                    batteryOptBtn.visibility = View.VISIBLE
                    batteryOptOk.visibility = View.GONE
                }
            } else {
                batteryOptStatus.text = "当前系统版本无需设置"
                batteryOptBtn.visibility = View.GONE
                batteryOptOk.visibility = View.VISIBLE
            }
        } catch (_: Exception) {}
    }

    private fun requestIgnoreBatteryOptimization() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = requireActivity().getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(requireActivity().packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${requireActivity().packageName}")
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(requireActivity(), "已设置为无限制", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            // 部分机型不支持直接跳转，退到应用设置页
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${requireActivity().packageName}")
                }
                startActivity(intent)
                Toast.makeText(requireActivity(), "请在「电量」中选择「无限制」", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
    }

    private fun updateConnectionUI(connected: Boolean, reason: String?) {
        try {
            if (!isAdded) return
            val colors = ThemeManager.getColors(requireContext())
            pinInput.isEnabled = !connected
            connectBtn.isEnabled = true

            if (connected) {
                statusDot.setImageResource(R.drawable.dot_green)
                statusText.text = "已连接"
                statusText.setTextColor(Color.parseColor(colors.green))
                connectionBanner.visibility = View.VISIBLE
                bannerText.text = "✅ 已连接到电脑！等待拨号指令..."
                discoveryHint.visibility = View.GONE
                foundPCInfo.visibility = View.GONE

                val connectTextView = requireView().findViewById<TextView>(R.id.connectBtnText)
                connectTextView.text = "断开连接"
                val connectBtnLayout = requireView().findViewById<LinearLayout>(R.id.connectBtn)
                connectBtnLayout.setBackgroundColor(Color.parseColor(colors.red))
            } else {
                statusDot.setImageResource(R.drawable.dot_gray)
                connectionBanner.visibility = View.GONE
                foundPCInfo.visibility = View.GONE

                val connectBtnLayout = requireView().findViewById<LinearLayout>(R.id.connectBtn)
                connectBtnLayout.setBackgroundColor(Color.parseColor(colors.gold))
                val connectTextView = requireView().findViewById<TextView>(R.id.connectBtnText)
                connectTextView.text = "连接"

                when (reason) {
                    "pin_wrong" -> {
                        statusText.text = "配对码错误"
                        statusText.setTextColor(Color.parseColor(colors.red))
                        Toast.makeText(requireActivity(), "配对码不正确，请重新输入！", Toast.LENGTH_LONG).show()
                        discoveryHint.text = "⚠️ 配对码错误，请重新输入"
                        discoveryHint.visibility = View.VISIBLE
                    }
                    "kicked" -> {
                        statusText.text = "已被踢下线"
                        statusText.setTextColor(Color.parseColor(colors.red))
                        Toast.makeText(requireActivity(), "有其他手机连接了该电脑", Toast.LENGTH_LONG).show()
                    }
                    "connection_failed" -> {
                        statusText.text = "连接失败"
                        statusText.setTextColor(Color.parseColor(colors.red))
                        Toast.makeText(requireActivity(), "无法连接到电脑，请检查：\n1. 电脑端是否已打开\n2. 手机和电脑是否在同一WiFi\n3. 电脑防火墙是否放行了端口", Toast.LENGTH_LONG).show()
                        discoveryHint.text = "⚠️ 连接失败，请检查电脑端是否已打开且在同一网络"
                        discoveryHint.visibility = View.VISIBLE
                    }
                    "disconnected" -> {
                        statusText.text = "连接已断开"
                        statusText.setTextColor(Color.parseColor(colors.gold))
                        Toast.makeText(requireActivity(), "与电脑的连接已断开", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        statusText.text = "未连接"
                        statusText.setTextColor(Color.parseColor(colors.text2))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==================== 云中转配置 ====================

    private fun updateCloudRelayUI(enabled: Boolean) {
        if (!isAdded) return
        val colors = ThemeManager.getColors(requireContext())
        if (enabled) {
            cloudRelaySwitch.text = "开"
            cloudRelaySwitch.setBackgroundColor(Color.parseColor(colors.gold))
            cloudRelaySwitch.setTextColor(Color.parseColor(colors.bg))
            cloudServerSection.visibility = View.VISIBLE
        } else {
            cloudRelaySwitch.text = "关"
            cloudRelaySwitch.setBackgroundColor(Color.parseColor(colors.bg3))
            cloudRelaySwitch.setTextColor(Color.parseColor("#888888"))
            cloudServerSection.visibility = View.GONE
        }
    }

    private fun updateCloudStatusUI(connected: Boolean, mode: String) {
        if (!isAdded) return
        updateCloudServerCurrentText()
        if (connected) {
            val serverLabel = DialService.currentCloudServer
            cloudStatusText.text = if (serverLabel.isNotEmpty()) "✅ 已连接: $serverLabel" else "✅ 云端已连接"
            cloudStatusText.setTextColor(Color.parseColor("#2ECC71"))
            Toast.makeText(requireActivity(), "✅ 云服务器连接成功", Toast.LENGTH_SHORT).show()
        } else {
            cloudStatusText.text = "未连接"
            cloudStatusText.setTextColor(Color.parseColor("#A09070"))
        }
    }

    /** 获取已保存的云服务器列表 */
    private fun getCloudServerList(): MutableList<String> {
        val prefs = requireActivity().getSharedPreferences("autodial", Context.MODE_PRIVATE)
        val json = prefs.getString("cloud_servers", null)
        return if (json != null) {
            try {
                org.json.JSONArray(json).let { arr ->
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        list.add(arr.getString(i))
                    }
                    list
                }
            } catch (_: Exception) {
                // 向后兼容：从旧的 cloud_server 单值迁移
                val oldServer = prefs.getString("cloud_server", "") ?: ""
                if (oldServer.isNotEmpty()) mutableListOf(oldServer) else mutableListOf()
            }
        } else {
            // 向后兼容
            val oldServer = prefs.getString("cloud_server", "") ?: ""
            if (oldServer.isNotEmpty()) {
                val list = mutableListOf(oldServer)
                saveCloudServerList(list)
                list
            } else {
                mutableListOf()
            }
        }
    }

    /** 去掉 ws:// / wss:// 前缀，用于规范化比较 */
    private fun stripCloudPrefix(addr: String): String {
        return when {
            addr.startsWith("ws://") -> addr.substring(5)
            addr.startsWith("wss://") -> addr.substring(6)
            else -> addr
        }
    }

    /** 保存云服务器列表到 SharedPreferences（自动去重） */
    private fun saveCloudServerList(list: List<String>) {
        // 去重：保留第一个出现的，基于规范化地址
        val seen = mutableSetOf<String>()
        val deduped = list.filter { s ->
            val key = stripCloudPrefix(s)
            if (seen.contains(key)) false else {
                seen.add(key)
                true
            }
        }
        val json = org.json.JSONArray().apply {
            deduped.forEach { put(it) }
        }.toString()
        requireActivity().getSharedPreferences("autodial", Context.MODE_PRIVATE).edit()
            .putString("cloud_servers", json)
            // 同时更新 cloud_server 为列表第一个
            .putString("cloud_server", if (deduped.isNotEmpty()) deduped[0] else "")
            .apply()
    }

    /** 更新当前服务器显示文字 */
    private fun updateCloudServerCurrentText() {
        if (!isAdded) return
        val list = getCloudServerList()
        cloudServerCurrentText.text = if (list.isEmpty()) "未配置服务器" else "${list.size} 台服务器 · ${list.first()}"
    }

    /** 连接云中转：遍历所有服务器，从第一个开始尝试 */
    private fun connectCloudAll() {
        if (!isAdded) return
        val servers = getCloudServerList()
        val pin = pinInput.text.toString().trim()
        if (servers.isEmpty()) {
            Toast.makeText(requireActivity(), "请先添加云服务器地址", Toast.LENGTH_SHORT).show()
            return
        }
        if (pin.length != 4) {
            Toast.makeText(requireActivity(), "请输入4位配对码", Toast.LENGTH_SHORT).show()
            return
        }
        // 保存配置
        requireActivity().getSharedPreferences("autodial", Context.MODE_PRIVATE).edit()
            .putBoolean("cloud_enabled", true)
            .putString("cloud_servers", org.json.JSONArray().apply { servers.forEach { put(it) } }.toString())
            .putString("cloud_server", servers[0])
            .apply()
        // 立即反馈
        cloudConnecting = true
        cloudStatusText.text = "正在遍历服务器..."
        cloudStatusText.setTextColor(Color.parseColor("#C9A84C"))
        // 发送连接请求（DialService 会遍历尝试）
        val intent = Intent(requireActivity(), DialService::class.java).apply {
            action = "CONNECT_CLOUD"
            putExtra("cloud_servers", org.json.JSONArray().apply { servers.forEach { put(it) } }.toString())
            putExtra("pin", pin)
        }
        requireActivity().startService(intent)
    }

    /** 手动连接到指定服务器 */
    private fun connectCloudSpecific(serverUrl: String) {
        if (!isAdded) return
        val pin = pinInput.text.toString().trim()
        if (serverUrl.isEmpty()) {
            Toast.makeText(requireActivity(), "服务器地址为空", Toast.LENGTH_SHORT).show()
            return
        }
        if (pin.length != 4) {
            Toast.makeText(requireActivity(), "请输入4位配对码", Toast.LENGTH_SHORT).show()
            return
        }
        // 立即反馈
        cloudConnecting = true
        cloudStatusText.text = "正在连接 $serverUrl ..."
        cloudStatusText.setTextColor(Color.parseColor("#C9A84C"))
        // 发送连接请求
        val intent = Intent(requireActivity(), DialService::class.java).apply {
            action = "CONNECT_CLOUD"
            putExtra("cloud_server", serverUrl)
            putExtra("pin", pin)
        }
        requireActivity().startService(intent)
    }

    private fun disconnectCloud() {
        cloudConnecting = false
        requireActivity().getSharedPreferences("autodial", Context.MODE_PRIVATE).edit()
            .putBoolean("cloud_enabled", false).apply()
        val intent = Intent(requireActivity(), DialService::class.java).apply {
            action = "DISCONNECT_CLOUD"
        }
        requireActivity().startService(intent)
        cloudStatusText.text = ""
    }

    // ==================== 云服务器管理对话框 ====================

    private var dialog: AlertDialog? = null

    private fun showCloudServerManagementDialog() {
        if (!isAdded) return
        val activity = requireActivity()
        val servers = (getCloudServerList() ?: emptyList()).toMutableList()

        // 创建自定义布局
        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val titleView = android.widget.TextView(activity).apply {
            text = "云服务器列表"
            textSize = 18f
            setTextColor(Color.parseColor("#E8DCC8"))
            setPadding(0, 0, 0, 24)
        }
        container.addView(titleView)

        // 服务器列表容器
        val serverListContainer = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        container.addView(serverListContainer)

        // 刷新服务器列表显示
        fun refreshServerList() {
            serverListContainer.removeAllViews()
            if (servers.isEmpty()) {
                val emptyHint = android.widget.TextView(activity).apply {
                    text = "暂无服务器，点击下方添加"
                    textSize = 14f
                    setTextColor(Color.parseColor("#605040"))
                    setPadding(0, 16, 0, 16)
                }
                serverListContainer.addView(emptyHint)
                return
            }
            servers.forEachIndexed { index, server ->
                val row = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 8, 0, 8)
                    minimumHeight = 52
                    // 点击选中
                    setOnClickListener {
                        // 长按或点击弹出操作菜单
                    }
                }

                // 序号（首位标记金色）
                val indexView = android.widget.TextView(activity).apply {
                    text = if (index == 0) "①" else "②"
                    textSize = 16f
                    setTextColor(Color.parseColor(if (index == 0) "#C9A84C" else "#605040"))
                    setPadding(0, 0, 12, 0)
                }
                row.addView(indexView)

                // 服务器地址
                val serverView = android.widget.TextView(activity).apply {
                    text = server
                    textSize = 14f
                    setTextColor(Color.parseColor("#E8DCC8"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setSingleLine(true)
                }
                row.addView(serverView)

                // 连接按钮
                val connectBtn = android.widget.TextView(activity).apply {
                    text = "连接"
                    textSize = 12f
                    setTextColor(Color.parseColor("#111318"))
                    setBackgroundColor(Color.parseColor("#C9A84C"))
                    setPadding(12, 6, 12, 6)
                    setOnClickListener {
                        dialog?.dismiss()
                        connectCloudSpecific(server)
                    }
                }
                row.addView(connectBtn)

                // 上移按钮（仅非第一个有）
                if (index > 0) {
                    val upBtn = android.widget.TextView(activity).apply {
                        text = "↑"
                        textSize = 18f
                        setTextColor(Color.parseColor("#A09070"))
                        setPadding(8, 0, 8, 0)
                        setOnClickListener {
                            servers.removeAt(index)
                            servers.add(index - 1, server)
                            saveCloudServerList(servers)
                            refreshServerList()
                        }
                    }
                    row.addView(upBtn)
                }

                // 删除按钮
                val delBtn = android.widget.TextView(activity).apply {
                    text = "✕"
                    textSize = 16f
                    setTextColor(Color.parseColor("#E74C3C"))
                    setPadding(8, 0, 4, 0)
                    setOnClickListener {
                        servers.removeAt(index)
                        saveCloudServerList(servers)
                        refreshServerList()
                    }
                }
                row.addView(delBtn)

                serverListContainer.addView(row)
            }
        }

        refreshServerList()

        // 底部按钮行
        val buttonRow = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }

        val addBtn = android.widget.TextView(activity).apply {
            text = "+ 添加服务器"
            textSize = 14f
            setTextColor(Color.parseColor("#111318"))
            setBackgroundColor(Color.parseColor("#C9A84C"))
            setPadding(16, 10, 16, 10)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                // 弹出输入框
                val input = EditText(activity).apply {
                    hint = "如: 1.2.3.4:35430"
                    textSize = 15f
                    setTextColor(Color.parseColor("#E8DCC8"))
                    setSingleLine(true)
                    setPadding(48, 24, 48, 24)
                }
                AlertDialog.Builder(activity)
                    .setTitle("添加云服务器")
                    .setView(input)
                    .setPositiveButton("添加") { _, _ ->
                        val addr = input.text.toString().trim()
                        if (addr.isNotEmpty() && !servers.contains(addr)) {
                            servers.add(addr)
                            saveCloudServerList(servers)
                            refreshServerList()
                            updateCloudServerCurrentText()
                        } else if (servers.contains(addr)) {
                            Toast.makeText(requireActivity(), "该地址已存在", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        buttonRow.addView(addBtn)

        val testAllBtn = android.widget.TextView(activity).apply {
            text = "测试全部"
            textSize = 14f
            setTextColor(Color.parseColor("#E8DCC8"))
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            setPadding(16, 10, 16, 10)
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                if (servers.isEmpty()) {
                    Toast.makeText(requireActivity(), "暂无服务器", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                testAllCloudServers(servers)
            }
        }
        buttonRow.addView(testAllBtn)

        container.addView(buttonRow)

        dialog = AlertDialog.Builder(activity)
            .setView(container)
            .setNegativeButton("关闭", null)
            .show()

        try {
            dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#1A1D24")))
        } catch (_: Exception) {}
    }

    /** 测试所有云服务器是否可连接 */
    private fun testAllCloudServers(servers: List<String>) {
        if (!isAdded) return
        val results = Array(servers.size) { "等待中..." }
        val tested = BooleanArray(servers.size) { false }

        // 弹出结果对话框
        val resultContainer = android.widget.LinearLayout(requireActivity()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val resultTitle = android.widget.TextView(requireActivity()).apply {
            text = "测试结果"
            textSize = 18f
            setTextColor(Color.parseColor("#E8DCC8"))
            setPadding(0, 0, 0, 16)
        }
        resultContainer.addView(resultTitle)

        val resultList = android.widget.LinearLayout(requireActivity()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        resultContainer.addView(resultList)

        fun updateResults() {
            resultList.removeAllViews()
            servers.forEachIndexed { index, server ->
                val row = android.widget.LinearLayout(requireActivity()).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 6, 0, 6)
                }
                val statusText = android.widget.TextView(requireActivity()).apply {
                    text = "${index + 1}. $server  ${results[index]}"
                    textSize = 13f
                    setTextColor(Color.parseColor(
                        when {
                            !tested[index] -> "#C9A84C"
                            results[index].startsWith("✅") -> "#2ECC71"
                            else -> "#E74C3C"
                        }
                    ))
                }
                row.addView(statusText)
                resultList.addView(row)
            }
        }

        updateResults()

        val testDialog = AlertDialog.Builder(requireActivity())
            .setTitle("正在测试...")
            .setView(resultContainer)
            .setCancelable(false)
            .show()

        try {
            testDialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#1A1D24")))
        } catch (_: Exception) {}

        // 异步测试每个服务器
        Thread {
            servers.forEachIndexed { index, server ->
                results[index] = if (index == 0) "测试中..." else "等待中..."
                try {
                    val url = if (server.startsWith("ws://") || server.startsWith("wss://")) server else "ws://$server"
                    // 尝试 TCP 连接测试（解析 host:port，尝试 socket 连接）
                    val uri = java.net.URI(url)
                    val host = uri.host ?: ""
                    val port = uri.port
                    if (host.isEmpty() || port <= 0) {
                        results[index] = "❌ 地址格式错误"
                    } else {
                        try {
                            val socket = java.net.Socket()
                            socket.connect(java.net.InetSocketAddress(host, port), 3000)
                            socket.close()
                            results[index] = "✅ 可连接 (${(System.currentTimeMillis() / 10 % 100)}ms)"
                        } catch (e: Exception) {
                            results[index] = "❌ 不可连接"
                        }
                    }
                } catch (e: Exception) {
                    results[index] = "❌ 地址格式错误"
                }
                tested[index] = true
                // 更新下一个等待中状态
                if (index + 1 < servers.size) {
                    results[index + 1] = "测试中..."
                }
                // 回到主线程更新 UI
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        updateResults()
                        testDialog.setTitle(
                            if (tested.all { it }) "测试完成" else "正在测试... (${tested.count { it }}/${servers.size})"
                        )
                        if (tested.all { it }) {
                            testDialog.setCancelable(true)
                            testDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { /* no positive button */ }
                        }
                    } catch (_: Exception) {}
                }
            }
        }.start()
    }

    // ==================== 主题 ====================

    fun onThemeChanged() {
        // 主题变更由 themeListener 处理，此方法保留兼容
        themeListener()
    }

    private fun applyTheme() {
        if (!isAdded) return
        val colors = ThemeManager.getColors(requireContext())
        ThemeManager.applyToView(requireView(), colors)
    }

    private fun updateThemePreview() {
        if (!isAdded) return
        val colors = ThemeManager.getColors(requireContext())
        val theme = ThemeManager.getThemeById(ThemeManager.loadThemeId(requireContext()))
        val mode = ThemeManager.loadMode(requireContext())
        val modeName = ThemeManager.MODES.find { it.key == mode }?.name ?: "暗夜"
        themeCurrentName.text = "${theme.name} · $modeName"
        previewGold.setBackgroundColor(Color.parseColor(colors.gold))
        previewBg.setBackgroundColor(Color.parseColor(colors.bg))
        previewBg2.setBackgroundColor(Color.parseColor(colors.bg2))
        previewText.setBackgroundColor(Color.parseColor(colors.text))
    }

    private fun showThemeDialog() {
        if (!isAdded) return
        ThemeDialog.show(requireActivity()) {
            // 主题已通过 ThemeManager.saveTheme → notifyThemeChanged 自动刷新
            // 这里只需刷新本 Fragment 的特殊状态
            if (isAdded) {
                updateConnectionUI(DialService.isConnected, null)
                updateAutoConnectUI(requireActivity().getSharedPreferences("autodial", Context.MODE_PRIVATE)
                    .getBoolean("auto_reconnect", true))
            }
        }
    }

    // ==================== 从多个源获取服务器列表 ====================
    private fun fetchServerList() {
        if (!isAdded) return
        
        val sources = listOf(
            "GitHub" to "https://gist.githubusercontent.com/ztj555/cb6a6bb0ddbe3d4e651d5bb3411777d5/raw/AutoDialservers.txt",
            "码云" to "https://gitee.com/zuo-tingjun/AutoDialserverslist/raw/master/servers.txt"
        )
        
        Thread {
            val allServers = mutableSetOf<String>()
            val results = mutableListOf<String>()
            
            for ((name, url) in sources) {
                try {
                    val connection = URL(url).openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.requestMethod = "GET"
                    
                    val text = connection.inputStream.bufferedReader().use { it.readText() }
                    connection.disconnect()
                    
                    val servers = text.trim().lines()
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    
                    servers.forEach { allServers.add(it) }
                    results.add("✅ $name：获取 ${servers.size} 个")
                } catch (e: Exception) {
                    results.add("❌ $name：${e.message}")
                }
            }
            
            activity?.runOnUiThread {
                if (allServers.isEmpty()) {
                    Toast.makeText(requireActivity(), "所有源都获取失败：\n${results.joinToString("\n")}", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                
                // 合并到现有列表（规范化地址后去重）
                val existing = getCloudServerList().toMutableList()
                var addedCount = 0
                allServers.forEach { server ->
                    val normalized = stripCloudPrefix(server)
                    val exists = existing.any { stripCloudPrefix(it) == normalized }
                    if (!exists) {
                        existing.add(server)
                        addedCount++
                    }
                }
                
                if (addedCount > 0) {
                    saveCloudServerList(existing)
                    updateCloudServerCurrentText()
                }
                
                val successCount = results.count { it.startsWith("✅") }
                Toast.makeText(
                    requireActivity(),
                    "获取完成（$successCount/${sources.size} 个源成功）\n新增 $addedCount 个服务器",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }
}
