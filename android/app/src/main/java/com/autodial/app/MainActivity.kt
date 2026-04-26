package com.autodial.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2

class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabConnect: LinearLayout
    private lateinit var tabCallLog: LinearLayout
    private lateinit var tabStats: LinearLayout
    private lateinit var tabConnectLabel: TextView
    private lateinit var tabCallLogLabel: TextView
    private lateinit var tabStatsLabel: TextView

    private val fragments = listOf<Fragment>(
        ConnectFragment(),
        CallLogFragment(),
        StatsFragment()
    )

    // 监听连接状态变化，连接成功时自动跳转通话记录页
    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val connected = intent?.getBooleanExtra("connected", false) ?: return
            if (connected) {
                switchTab(1)
            }
        }
    }

    // 监听 DialService 的选卡广播，弹出透明选卡 Activity
    private val simSelectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val number = intent?.getStringExtra("number") ?: return
            val lastSimSlot = intent.getIntExtra("last_sim_slot", -1)
            val lastDialTime = intent.getLongExtra("last_dial_time", 0L)
            showSimSelectSheet(number, lastSimSlot, lastDialTime)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewPager = findViewById(R.id.viewPager)
        tabConnect = findViewById(R.id.tabConnect)
        tabCallLog = findViewById(R.id.tabCallLog)
        tabStats = findViewById(R.id.tabStats)
        tabConnectLabel = findViewById(R.id.tabConnectLabel)
        tabCallLogLabel = findViewById(R.id.tabCallLogLabel)
        tabStatsLabel = findViewById(R.id.tabStatsLabel)

        // 设置 ViewPager 适配器
        viewPager.adapter = ViewPagerAdapter(this, fragments)
        viewPager.isUserInputEnabled = false  // 禁止滑动切换，仅通过底部导航

        // 底部导航点击事件
        tabConnect.setOnClickListener { switchTab(0) }
        tabCallLog.setOnClickListener { switchTab(1) }
        tabStats.setOnClickListener { switchTab(2) }

        // 注册连接状态广播
        ContextCompat.registerReceiver(this, connectionReceiver,
            IntentFilter("com.autodial.CONNECTION_CHANGE"),
            ContextCompat.RECEIVER_EXPORTED
        )

        // 注册选卡广播
        ContextCompat.registerReceiver(this, simSelectReceiver,
            IntentFilter(DialService.ACTION_SHOW_SIM_SELECT),
            ContextCompat.RECEIVER_EXPORTED
        )

        // 启动后台服务
        startService(DialService.newIntent(this))

        // 请求权限
        requestPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(connectionReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(simSelectReceiver) } catch (_: Exception) {}
    }

    /**
     * 弹出透明 Activity 选卡（可在任何界面弹出，包括桌面）
     */
    private fun showSimSelectSheet(number: String, lastSimSlot: Int, lastDialTime: Long) {
        try {
            startActivity(SimSelectActivity.newIntent(this, number, lastSimSlot, lastDialTime))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun switchTab(index: Int) {
        viewPager.currentItem = index

        // 重置所有标签颜色
        tabConnectLabel.setTextColor(getColor(R.color.text_sub))
        tabCallLogLabel.setTextColor(getColor(R.color.text_sub))
        tabStatsLabel.setTextColor(getColor(R.color.text_sub))

        // 高亮当前标签
        when (index) {
            0 -> tabConnectLabel.setTextColor(getColor(R.color.gold_light))
            1 -> {
                tabCallLogLabel.setTextColor(getColor(R.color.gold_light))
                // 切换到记录页时刷新通话记录
                supportFragmentManager.fragments.forEach { frag ->
                    if (frag is CallLogFragment) frag.refreshIfNeeded()
                }
            }
            2 -> tabStatsLabel.setTextColor(getColor(R.color.gold_light))
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            perms.add(Manifest.permission.READ_PHONE_NUMBERS)
        }

        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            startService(DialService.newIntent(this))
            // 通知通话记录页面刷新
            supportFragmentManager.fragments.forEach { frag ->
                if (frag is CallLogFragment) frag.refreshIfNeeded()
            }
        }
    }
}
