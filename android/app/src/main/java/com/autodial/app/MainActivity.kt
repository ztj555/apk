package com.autodial.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
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

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val connected = intent?.getBooleanExtra("connected", false) ?: return
            if (connected) {
                switchTab(1)
            }
        }
    }

    private val simSelectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val number = intent?.getStringExtra("number") ?: return
            val lastSimSlot = intent.getIntExtra("last_sim_slot", -1)
            val lastDialTime = intent.getLongExtra("last_dial_time", 0L)
            showSimSelectSheet(number, lastSimSlot, lastDialTime)
        }
    }

    private val smsConfirmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val number = intent?.getStringExtra("number") ?: return
            val content = intent?.getStringExtra("content") ?: ""
            startActivity(Intent(this@MainActivity, SmsConfirmActivity::class.java).apply {
                putExtra("number", number)
                putExtra("content", content)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
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
        viewPager.isUserInputEnabled = false

        // 底部导航点击事件
        tabConnect.setOnClickListener { switchTab(0) }
        tabCallLog.setOnClickListener { switchTab(1) }
        tabStats.setOnClickListener { switchTab(2) }

        // 注册广播
        ContextCompat.registerReceiver(this, connectionReceiver,
            IntentFilter("com.autodial.CONNECTION_CHANGE"),
            ContextCompat.RECEIVER_EXPORTED
        )
        ContextCompat.registerReceiver(this, simSelectReceiver,
            IntentFilter(DialService.ACTION_SHOW_SIM_SELECT),
            ContextCompat.RECEIVER_EXPORTED
        )
        ContextCompat.registerReceiver(this, smsConfirmReceiver,
            IntentFilter(DialService.ACTION_SHOW_SMS_CONFIRM),
            ContextCompat.RECEIVER_EXPORTED
        )

        // 启动后台服务
        startService(DialService.newIntent(this))

        // 请求权限
        requestPermissions()

        // 应用主题
        applyTheme()
        // 默认选中第一个 tab
        switchTab(0)
    }

    override fun onResume() {
        super.onResume()
        // 从主题选择弹窗返回时刷新主题
        applyTheme()
    }

    /**
     * 应用主题到 MainActivity 及子 View
     */
    private fun applyTheme() {
        val colors = ThemeManager.getColors(this)

        // 状态栏 + 导航栏
        val bgColor = Color.parseColor(colors.bg)
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor
        // 浅色模式时文字用深色
        val isLight = ThemeManager.isLightMode(this)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = isLight
            isAppearanceLightNavigationBars = isLight
        }

        // 应用颜色到 main layout
        ThemeManager.applyToView(findViewById<View>(android.R.id.content), colors)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(connectionReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(simSelectReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(smsConfirmReceiver) } catch (_: Exception) {}
    }

    private fun showSimSelectSheet(number: String, lastSimSlot: Int, lastDialTime: Long) {
        try {
            if (SimSelectOverlay.hasPermission(this)) {
                SimSelectOverlay.show(this, number, lastSimSlot, lastDialTime)
            } else {
                Toast.makeText(this, "请开启悬浮窗权限以显示选卡弹窗", Toast.LENGTH_LONG).show()
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun switchTab(index: Int) {
        viewPager.currentItem = index
        val colors = ThemeManager.getColors(this)
        val inactiveColor = Color.parseColor(colors.text2)
        val activeColor = Color.parseColor(colors.goldLight)

        tabConnectLabel.setTextColor(inactiveColor)
        tabCallLogLabel.setTextColor(inactiveColor)
        tabStatsLabel.setTextColor(inactiveColor)

        when (index) {
            0 -> tabConnectLabel.setTextColor(activeColor)
            1 -> {
                tabCallLogLabel.setTextColor(activeColor)
                supportFragmentManager.fragments.forEach { frag ->
                    if (frag is CallLogFragment) frag.refreshIfNeeded()
                }
            }
            2 -> tabStatsLabel.setTextColor(activeColor)
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.SEND_SMS
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "弹窗选卡需要悬浮窗权限，请允许", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            } catch (_: Exception) {}
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            startService(DialService.newIntent(this))
            supportFragmentManager.fragments.forEach { frag ->
                if (frag is CallLogFragment) frag.refreshIfNeeded()
            }
        }
    }
}
