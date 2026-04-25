package com.autodial.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.*

class StatsFragment : Fragment() {

    private lateinit var todayCount: TextView
    private lateinit var totalCount: TextView
    private lateinit var chartContainer: LinearLayout
    private lateinit var dateLabels: LinearLayout
    private lateinit var detailList: LinearLayout

    private val newDialReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshIfNeeded()
        }
    }

    // 通话结束时延迟1秒刷新统计
    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshHandler.removeCallbacks(refreshRunnable)
            refreshHandler.postDelayed(refreshRunnable, 1000)
        }
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshIfNeeded() }

    private val dayOfWeekFormat = SimpleDateFormat("E", Locale.getDefault())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_stats, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        todayCount = view.findViewById(R.id.statsTodayCount)
        totalCount = view.findViewById(R.id.statsTotalCount)
        chartContainer = view.findViewById(R.id.statsChartContainer)
        dateLabels = view.findViewById(R.id.statsDateLabels)
        detailList = view.findViewById(R.id.statsDetailList)

        // 注册新拨号广播
        try {
            ContextCompat.registerReceiver(requireActivity(), newDialReceiver,
                IntentFilter("com.autodial.NEW_DIAL"),
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (_: Exception) {}

        // 注册通话结束广播
        try {
            ContextCompat.registerReceiver(requireActivity(), callEndedReceiver,
                IntentFilter("com.autodial.CALL_ENDED"),
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (_: Exception) {}

        refreshIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshIfNeeded()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        refreshHandler.removeCallbacks(refreshRunnable)
        try { requireActivity().unregisterReceiver(newDialReceiver) } catch (_: Exception) {}
        try { requireActivity().unregisterReceiver(callEndedReceiver) } catch (_: Exception) {}
    }

    fun refreshIfNeeded() {
        if (isAdded && !isDetached) {
            loadStats()
        }
    }

    private fun loadStats() {
        if (!isAdded) return

        val db = CallLogDb(requireContext())

        // 概览数据
        val today = db.getTodayCount()
        val total = db.getTotalCount()
        todayCount.text = today.toString()
        totalCount.text = total.toString()

        // 近7天趋势
        val stats = db.getDailyStats(7)
        buildChart(stats)
        buildDetailList(stats)
    }

    private fun buildChart(stats: List<CallLogDb.DayStats>) {
        chartContainer.removeAllViews()
        dateLabels.removeAllViews()

        val maxCount = (stats.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)

        val barColors = intArrayOf(
            Color.parseColor("#C9A84C"),
            Color.parseColor("#D4B35A"),
            Color.parseColor("#F0C040"),
            Color.parseColor("#2ECC71"),
            Color.parseColor("#C9A84C"),
            Color.parseColor("#D4B35A"),
            Color.parseColor("#F0C040")
        )

        val maxBarHeightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 90f, resources.displayMetrics).toInt()
        val barWidthPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt()
        val minHeightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt()
        val chartHeightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 120f, resources.displayMetrics).toInt()

        for (i in stats.indices) {
            val s = stats[i]

            // 柱子
            val barWrapper = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, chartHeightPx, 1f)
            }

            // 数量标签
            val countLabel = TextView(requireContext()).apply {
                text = if (s.count > 0) s.count.toString() else ""
                textSize = 11f
                setTextColor(Color.parseColor("#A09070"))
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 4 }
            }
            barWrapper.addView(countLabel)

            // 柱子本身
            val actualBarHeight = if (s.count > 0) {
                ((s.count.toFloat() / maxCount) * maxBarHeightPx).toInt().coerceAtLeast(barWidthPx)
            } else {
                minHeightPx
            }
            val bar = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(barWidthPx, actualBarHeight).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                setBackgroundColor(if (s.count > 0) barColors[i] else Color.parseColor("#22262F"))
            }
            barWrapper.addView(bar)

            chartContainer.addView(barWrapper)

            // 日期标签
            val dateLabel = TextView(requireContext()).apply {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_MONTH, -(6 - i))
                val dayOfWeek = dayOfWeekFormat.format(cal.time)
                val dateStr = s.date.substring(5) // MM-dd
                text = "$dayOfWeek\n$dateStr"
                textSize = 10f
                setTextColor(Color.parseColor("#605040"))
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { topMargin = 8 }
                setPadding(0, 0, 0, 0)
            }
            dateLabels.addView(dateLabel)
        }
    }

    private fun buildDetailList(stats: List<CallLogDb.DayStats>) {
        detailList.removeAllViews()

        // 倒序显示（最近的在上面）
        val reversed = stats.reversed()

        for (s in reversed) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 14, 16, 14)
                setBackgroundColor(Color.parseColor("#111318"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // 日期
            val dateText = TextView(requireContext()).apply {
                text = s.date
                textSize = 14f
                setTextColor(Color.parseColor("#E8DCC8"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            // 是否今天
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            if (s.date == today) {
                dateText.text = "$s.date（今天）"
                dateText.setTextColor(Color.parseColor("#F0C040"))
            }

            // 拨号次数
            val countText = TextView(requireContext()).apply {
                text = "${s.count} 次"
                textSize = 14f
                setTextColor(
                    if (s.count > 0) Color.parseColor("#C9A84C")
                    else Color.parseColor("#605040")
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            row.addView(dateText)
            row.addView(countText)
            detailList.addView(row)

            // 分割线
            val divider = View(requireContext()).apply {
                setBackgroundColor(Color.parseColor("#22262F"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply {
                    marginStart = 16
                    marginEnd = 16
                }
            }
            detailList.addView(divider)
        }
    }
}
