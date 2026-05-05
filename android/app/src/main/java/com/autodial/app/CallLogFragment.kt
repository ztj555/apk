package com.autodial.app

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telecom.TelecomManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

data class PhoneCallRecord(
    val number: String,
    val time: Long,
    val duration: Long,
    val type: Int,     // CallLog.Calls.TYPE_OUTGOING = 2, INCOMING = 1, MISSED = 3
    val simSlot: Int   // 0=卡1, 1=卡2
)

class CallLogAdapter(
    private val records: List<PhoneCallRecord>,
    private val colors: ThemeColors,
    private val onLongClick: (PhoneCallRecord) -> Unit
) :
    RecyclerView.Adapter<CallLogAdapter.ViewHolder>() {

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.itemCallNumber)
        val time: TextView = view.findViewById(R.id.itemCallTime)
        val callType: TextView = view.findViewById(R.id.itemCallType)
        val simSlot: TextView = view.findViewById(R.id.itemSimSlot)
        val callStatus: TextView = view.findViewById(R.id.itemCallStatus)
        val root: View = view
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_call_log, parent, false)
        // 应用主题到 item 的根布局
        ThemeManager.applyToView(view, colors)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = records[position]

        // 号码脱敏
        val num = record.number
        holder.number.text = if (num.length > 7) {
            num.substring(0, 3) + "****" + num.substring(num.length - 4)
        } else {
            num
        }

        // 时间
        holder.time.text = timeFormat.format(Date(record.time))

        // 通话类型图标
        holder.callType.text = when (record.type) {
            CallLog.Calls.OUTGOING_TYPE -> "📤"
            CallLog.Calls.INCOMING_TYPE -> "📥"
            CallLog.Calls.MISSED_TYPE -> "📵"
            else -> "📞"
        }

        // 通话状态文字 + 颜色
        when (record.type) {
            CallLog.Calls.OUTGOING_TYPE -> {
                if (record.duration > 0) {
                    holder.callStatus.text = formatDuration(record.duration)
                    holder.callStatus.setTextColor(android.graphics.Color.parseColor(colors.green))
                } else {
                    holder.callStatus.text = "未接通"
                    holder.callStatus.setTextColor(android.graphics.Color.parseColor(colors.red))
                }
            }
            CallLog.Calls.INCOMING_TYPE -> {
                if (record.duration > 0) {
                    holder.callStatus.text = formatDuration(record.duration)
                    holder.callStatus.setTextColor(android.graphics.Color.parseColor(colors.green))
                } else {
                    holder.callStatus.text = "未接听"
                    holder.callStatus.setTextColor(android.graphics.Color.parseColor(colors.red))
                }
            }
            CallLog.Calls.MISSED_TYPE -> {
                holder.callStatus.text = "未接"
                holder.callStatus.setTextColor(android.graphics.Color.parseColor(colors.red))
            }
            else -> {
                holder.callStatus.text = "-"
                holder.callStatus.setTextColor(android.graphics.Color.parseColor(colors.text2))
            }
        }

        // SIM卡标识
        holder.simSlot.text = "卡${record.simSlot + 1}"
        if (record.simSlot == 1) {
            holder.simSlot.setTextColor(android.graphics.Color.parseColor(colors.green))
        } else {
            holder.simSlot.setTextColor(android.graphics.Color.parseColor(colors.gold))
        }

        // 长按弹出操作菜单
        holder.root.setOnLongClickListener {
            onLongClick(record)
            true
        }
    }

    private fun formatDuration(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            else -> "${seconds / 60}m${seconds % 60}s"
        }
    }

    override fun getItemCount(): Int = records.size
}

class CallLogFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var countText: TextView
    private lateinit var permissionHint: View
    private lateinit var lastCallHintBanner: View
    private lateinit var lastCallHintText: TextView

    // 连接状态
    private lateinit var connectionStatusDot: ImageView
    private lateinit var connectionStatusText: TextView

    // 拨号模式按钮
    private lateinit var dialModeButtons: List<TextView>
    private lateinit var dialModeKeys: List<String>

    // 主题变更监听
    private val themeListener: () -> Unit = {
        if (isAdded) {
            applyTheme()
            updateDialModeBarUI()
            loadCallLog()
        }
    }

    // 连接状态变更广播监听
    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val connected = intent?.getBooleanExtra("connected", false) ?: false
            if (isAdded) updateConnectionStatus(connected)
        }
    }

    // 主刷新机制：监听通话结束广播，延迟1秒刷新
    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 通话结束后系统需要一点时间写入通话记录，延迟1秒再刷新
            refreshHandler.removeCallbacks(refreshRunnable)
            refreshHandler.postDelayed(refreshRunnable, 1000)
        }
    }

    // 拨号前提示广播：显示上次使用哪张卡
    private val lastCallHintReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val hint = intent?.getStringExtra("hint") ?: return
            if (isAdded) {
                lastCallHintText.text = hint
                lastCallHintBanner.visibility = View.VISIBLE
                // 10秒后自动隐藏
                refreshHandler.postDelayed({
                    if (isAdded) lastCallHintBanner.visibility = View.GONE
                }, 10_000)
            }
        }
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { loadCallLog() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_call_log, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.callLogRecyclerView)
        emptyView = view.findViewById(R.id.callLogEmpty)
        countText = view.findViewById(R.id.callLogCount)
        permissionHint = view.findViewById(R.id.callLogPermissionHint)
        lastCallHintBanner = view.findViewById(R.id.lastCallHintBanner)
        lastCallHintText = view.findViewById(R.id.lastCallHintText)
        connectionStatusDot = view.findViewById(R.id.connectionStatusDot)
        connectionStatusText = view.findViewById(R.id.connectionStatusText)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // 初始化连接状态（根据 DialService 当前状态）
        updateConnectionStatus(DialService.isConnected)

        // 拨号模式按钮
        dialModeButtons = listOf(
            view.findViewById(R.id.dialModePopup),
            view.findViewById(R.id.dialModeRoundSelect),
            view.findViewById(R.id.dialModeSim1),
            view.findViewById(R.id.dialModeSim2),
            view.findViewById(R.id.dialModeAlternate),
            view.findViewById(R.id.dialModeRemember)
        )
        dialModeKeys = listOf(
            DialMode.POPUP.key,
            DialMode.ROUND_SELECT.key,
            DialMode.SIM1.key,
            DialMode.SIM2.key,
            DialMode.ALTERNATE.key,
            DialMode.REMEMBER.key
        )

        dialModeButtons.forEachIndexed { index, btn ->
            btn.setOnClickListener {
                requireActivity().getSharedPreferences("autodial", Context.MODE_PRIVATE)
                    .edit().putString("dial_mode", dialModeKeys[index]).apply()
                updateDialModeBarUI()
            }
        }

        // 注册广播
        ContextCompat.registerReceiver(
            requireContext(),
            callEndedReceiver,
            IntentFilter("com.autodial.CALL_ENDED"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            requireContext(),
            lastCallHintReceiver,
            IntentFilter("com.autodial.LAST_CALL_HINT"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            requireContext(),
            connectionReceiver,
            IntentFilter("com.autodial.CONNECTION_CHANGE"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 主题监听
        ThemeManager.addOnThemeChangedListener(themeListener)

        applyTheme()
        updateDialModeBarUI()
        loadCallLog()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        refreshHandler.removeCallbacks(refreshRunnable)
        ThemeManager.removeOnThemeChangedListener(themeListener)
        try { requireContext().unregisterReceiver(callEndedReceiver) } catch (_: Exception) {}
        try { requireContext().unregisterReceiver(lastCallHintReceiver) } catch (_: Exception) {}
        try { requireContext().unregisterReceiver(connectionReceiver) } catch (_: Exception) {}
    }

    fun refreshIfNeeded() {
        if (isAdded) {
            updateConnectionStatus(DialService.isConnected)
            loadCallLog()
        }
    }

    // ==================== 连接状态 ====================

    private fun updateConnectionStatus(connected: Boolean) {
        if (!isAdded) return
        val colors = ThemeManager.getColors(requireContext())
        if (connected) {
            connectionStatusDot.setImageResource(R.drawable.dot_green)
            connectionStatusText.text = "已连接"
            connectionStatusText.setTextColor(android.graphics.Color.parseColor(colors.green))
        } else {
            connectionStatusDot.setImageResource(R.drawable.dot_gray)
            connectionStatusText.text = "未连接"
            connectionStatusText.setTextColor(android.graphics.Color.parseColor(colors.text2))
        }
    }

    // ==================== 长按操作菜单 ====================

    private fun showCallRecordMenu(record: PhoneCallRecord) {
        if (!isAdded) return
        val colors = ThemeManager.getColors(requireContext())
        val num = record.number

        // 完整号码（用于实际操作，不脱敏）
        val displayNum = if (num.length > 7) {
            num.substring(0, 3) + "****" + num.substring(num.length - 4)
        } else num

        val items = arrayOf("📞 重拨  $displayNum", "💬 发短信给  $displayNum")

        val dialog = AlertDialog.Builder(requireContext())
            .setItems(items) { _, which ->
                when (which) {
                    0 -> redialNumber(num)
                    1 -> sendSmsTo(num)
                }
            }
            .create()

        // 应用主题背景色
        dialog.show()
        try {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.listView?.setBackgroundColor(android.graphics.Color.parseColor(colors.bg2))
            dialog.listView?.dividerHeight = 0
        } catch (_: Exception) {}
    }

    private fun redialNumber(number: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "拨号失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendSmsTo(number: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "打开短信失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 通话记录加载 ====================

    fun loadCallLog() {
        if (!isAdded) return
        val ctx = requireContext()

        val hasPermission = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            permissionHint.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.GONE
            countText.text = "无权限"
            return
        }

        permissionHint.visibility = View.GONE

        val records = mutableListOf<PhoneCallRecord>()

        try {
            val cursor: Cursor? = ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.PHONE_ACCOUNT_ID
                ),
                null, null,
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use {
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = it.getColumnIndex(CallLog.Calls.DURATION)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val subIdIdx = it.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)

                // 获取 SIM 卡订阅列表（用于 subId → slotIndex 映射）
                val simList = try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                        val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? android.telephony.SubscriptionManager
                        sm?.activeSubscriptionInfoList?.filterNotNull() ?: emptyList()
                    } else emptyList()
                } catch (_: Exception) { emptyList() }

                while (it.moveToNext() && records.size < 200) {
                    val num = it.getString(numIdx) ?: continue
                    val date = it.getLong(dateIdx)
                    val dur = it.getLong(durIdx)
                    val type = it.getInt(typeIdx)
                    val subId = it.getString(subIdIdx)

                    var simSlot = 0
                    try {
                        for (info in simList) {
                            if (info.subscriptionId.toString() == subId) {
                                simSlot = info.simSlotIndex
                                break
                            }
                        }
                    } catch (_: Exception) {}

                    records.add(PhoneCallRecord(num, date, dur, type, simSlot))
                }
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, "读取通话记录失败", Toast.LENGTH_SHORT).show()
        }

        val colors = ThemeManager.getColors(ctx)

        if (records.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            countText.text = "0 条记录"
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            countText.text = "${records.size} 条记录"
            recyclerView.adapter = CallLogAdapter(records, colors) { record ->
                showCallRecordMenu(record)
            }
        }
    }

    // ==================== 拨号模式 UI ====================

    fun updateDialModeBarUI() {
        if (!isAdded) return
        val colors = ThemeManager.getColors(requireContext())
        val prefs = requireActivity().getSharedPreferences("autodial", Context.MODE_PRIVATE)
        val currentMode = prefs.getString("dial_mode", DialMode.ALTERNATE.key) ?: DialMode.ALTERNATE.key

        dialModeButtons.forEachIndexed { index, btn ->
            val isSelected = dialModeKeys[index] == currentMode
            if (isSelected) {
                btn.setBackgroundColor(android.graphics.Color.parseColor(colors.gold))
                btn.setTextColor(android.graphics.Color.parseColor(colors.bg))
            } else {
                btn.setBackgroundColor(android.graphics.Color.parseColor(colors.bg3))
                btn.setTextColor(android.graphics.Color.parseColor(colors.text2))
            }
        }
    }

    // ==================== 主题 ====================

    fun onThemeChanged() {
        themeListener()
    }

    private fun applyTheme() {
        if (!isAdded) return
        val colors = ThemeManager.getColors(requireContext())
        ThemeManager.applyToView(requireView(), colors)
        updateConnectionStatus(DialService.isConnected)
    }
}