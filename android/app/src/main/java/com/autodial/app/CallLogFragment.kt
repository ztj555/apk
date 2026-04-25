package com.autodial.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.os.Bundle
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

        // 通话类型
        holder.callType.text = when (record.type) {
            CallLog.Calls.OUTGOING_TYPE -> "📞"
            CallLog.Calls.INCOMING_TYPE -> "📥"
            CallLog.Calls.MISSED_TYPE -> "❌"
            else -> "📞"
        }

        // SIM卡标识
        holder.simSlot.text = "卡${record.simSlot + 1}"
        if (record.simSlot == 1) {
            holder.simSlot.setTextColor(0xFF2ECC71.toInt())
        } else {
            holder.simSlot.setTextColor(0xFFC9A84C.toInt())
        }
    }

    override fun getItemCount(): Int = records.size
}

class CallLogFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var countText: TextView
    private lateinit var permissionHint: View

    private val newDialReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 有新拨号时刷新列表
            refreshIfNeeded()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_call_log, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.callLogRecyclerView)
        emptyView = view.findViewById(R.id.callLogEmpty)
        countText = view.findViewById(R.id.callLogCount)
        permissionHint = view.findViewById(R.id.callLogPermissionHint)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // 注册新拨号广播
        try {
            ContextCompat.registerReceiver(requireActivity(), newDialReceiver,
                IntentFilter("com.autodial.NEW_DIAL"),
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (_: Exception) {}

        loadCallLog()
    }

    override fun onResume() {
        super.onResume()
        loadCallLog()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { requireActivity().unregisterReceiver(newDialReceiver) } catch (_: Exception) {}
    }

    fun refreshIfNeeded() {
        if (isAdded && !isDetached) {
            loadCallLog()
        }
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
            // 查询系统通话记录，按时间倒序，最多100条
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
                "${CallLog.Calls.DATE} DESC",
                "100"
            )

            cursor?.use {
                val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val simIdx = it.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)

                // 先收集 subId，再映射到 simSlot
                val subscriptionManager = try {
                    requireActivity().getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                            as? android.telephony.SubscriptionManager
                } catch (e: Exception) { null }

                // 获取所有活跃的 SIM 卡信息
                val simInfoList = try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                        subscriptionManager?.activeSubscriptionInfoList
                    } else null
                } catch (e: Exception) { null }

                while (it.moveToNext()) {
                    val number = it.getString(numberIdx) ?: continue
                    val time = it.getLong(dateIdx)
                    val duration = it.getLong(durationIdx)
                    val type = it.getInt(typeIdx)
                    val subId = it.getString(simIdx)

                    // 映射 subId 到 simSlot
                    var simSlot = 0  // 默认卡1
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
