package com.autodial.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

class CallLogAdapter(private val records: List<PhoneCallRecord>) :
    RecyclerView.Adapter<CallLogAdapter.ViewHolder>() {

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.itemCallNumber)
        val time: TextView = view.findViewById(R.id.itemCallTime)
        val callType: TextView = view.findViewById(R.id.itemCallType)
        val simSlot: TextView = view.findViewById(R.id.itemSimSlot)
        val callStatus: TextView = view.findViewById(R.id.itemCallStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_call_log, parent, false)
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
            CallLog.Calls.OUTGOING_TYPE -> "📞"
            CallLog.Calls.INCOMING_TYPE -> "📥"
            CallLog.Calls.MISSED_TYPE -> "❌"
            else -> "📞"
        }

        // 通话状态文字 + 颜色
        when (record.type) {
            CallLog.Calls.OUTGOING_TYPE -> {
                if (record.duration > 0) {
                    holder.callStatus.text = formatDuration(record.duration)
                    holder.callStatus.setTextColor(0xFF2ECC71.toInt())   // 绿色：接通
                } else {
                    holder.callStatus.text = "未接通"
                    holder.callStatus.setTextColor(0xFFE74C3C.toInt())   // 红色：未接通
                }
            }
            CallLog.Calls.INCOMING_TYPE -> {
                if (record.duration > 0) {
                    holder.callStatus.text = formatDuration(record.duration)
                    holder.callStatus.setTextColor(0xFF2ECC71.toInt())
                } else {
                    holder.callStatus.text = "未接听"
                    holder.callStatus.setTextColor(0xFFE74C3C.toInt())
                }
            }
            CallLog.Calls.MISSED_TYPE -> {
                holder.callStatus.text = "未接"
                holder.callStatus.setTextColor(0xFFE74C3C.toInt())
            }
            else -> {
                holder.callStatus.text = "-"
                holder.callStatus.setTextColor(0xFFA09070.toInt())
            }
        }

        // SIM卡标识
        holder.simSlot.text = "卡${record.simSlot + 1}"
        if (record.simSlot == 1) {
            holder.simSlot.setTextColor(0xFF2ECC71.toInt())
        } else {
            holder.simSlot.setTextColor(0xFFC9A84C.toInt())
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
                // 10秒后自动消失
                refreshHandler.postDelayed({ lastCallHintBanner.visibility = View.GONE }, 10000)
            }
        }
    }

    // 兜底轮询：30秒检查一次，防止遗漏（如App刚打开错过了广播）
    private var lastKnownDate: Long = 0L
    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            checkAndRefresh()
            pollHandler.postDelayed(this, 30000)
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

        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // 注册通话结束广播
        try {
            ContextCompat.registerReceiver(requireActivity(), callEndedReceiver,
                IntentFilter("com.autodial.CALL_ENDED"),
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (_: Exception) {}

        // 注册上次通话提示广播
        try {
            ContextCompat.registerReceiver(requireActivity(), lastCallHintReceiver,
                IntentFilter("com.autodial.LAST_CALL_HINT"),
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (_: Exception) {}

        // 首次加载
        loadCallLog()

        // 启动兜底轮询（30秒）
        pollHandler.postDelayed(pollRunnable, 30000)
    }

    override fun onResume() {
        super.onResume()
        loadCallLog()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        refreshHandler.removeCallbacks(refreshRunnable)
        pollHandler.removeCallbacks(pollRunnable)
        try { requireActivity().unregisterReceiver(callEndedReceiver) } catch (_: Exception) {}
        try { requireActivity().unregisterReceiver(lastCallHintReceiver) } catch (_: Exception) {}
    }

    fun refreshIfNeeded() {
        if (isAdded && !isDetached) {
            loadCallLog()
        }
    }

    /**
     * 兜底轮询：只查最新一条记录的日期，和 lastKnownDate 比较
     */
    private fun checkAndRefresh() {
        if (!isAdded) return

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CALL_LOG)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return

        try {
            @Suppress("DEPRECATION")
            val cursor: Cursor? = requireActivity().contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.DATE),
                null, null,
                "${CallLog.Calls.DATE} DESC"
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val latestDate = it.getLong(it.getColumnIndex(CallLog.Calls.DATE))
                    if (latestDate != lastKnownDate) {
                        loadCallLog()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun loadCallLog() {
        if (!isAdded) return

        // 检查权限
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CALL_LOG)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionHint.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.GONE
            countText.text = "需要权限"
            return
        }

        permissionHint.visibility = View.GONE

        val records = mutableListOf<PhoneCallRecord>()

        try {
            @Suppress("DEPRECATION")
            val cursor: Cursor? = requireActivity().contentResolver.query(
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
                val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val simIdx = it.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)

                val subscriptionManager = try {
                    requireActivity().getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                            as? android.telephony.SubscriptionManager
                } catch (e: Exception) { null }

                val simInfoList = try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                        subscriptionManager?.activeSubscriptionInfoList
                    } else null
                } catch (e: Exception) { null }

                while (it.moveToNext() && records.size < 100) {
                    val number = it.getString(numberIdx) ?: continue
                    val time = it.getLong(dateIdx)
                    val duration = it.getLong(durationIdx)
                    val type = it.getInt(typeIdx)
                    val subId = it.getString(simIdx)

                    var simSlot = 0
                    if (subId != null && simInfoList != null) {
                        for (info in simInfoList) {
                            if (info.subscriptionId.toString() == subId) {
                                simSlot = info.simSlotIndex
                                break
                            }
                        }
                    }

                    records.add(PhoneCallRecord(number, time, duration, type, simSlot))
                }
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "读取通话记录失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        // 记录最新一条的日期用于轮询比对
        if (records.isNotEmpty()) {
            lastKnownDate = records[0].time
        }

        countText.text = "${records.size} 条记录"

        if (records.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            recyclerView.adapter = CallLogAdapter(records)
        }
    }
}
