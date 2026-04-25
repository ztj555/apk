package com.autodial.app

import android.Manifest
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

        // 启动后台服务
        startService(DialService.newIntent(this))

        // 请求权限
        requestPermissions()
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
            1 -> tabCallLogLabel.setTextColor(getColor(R.color.gold_light))
            2 -> tabStatsLabel.setTextColor(getColor(R.color.gold_light))
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )
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
