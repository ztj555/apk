package com.autodial.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.TelecomManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class DialConfirmActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dial_confirm)

        val number = intent.getStringExtra("number") ?: return
        val sim = intent.getIntExtra("sim", 1)

        val titleText = findViewById<TextView>(R.id.confirmTitle)
        val numberText = findViewById<TextView>(R.id.confirmNumber)
        val simInfo = findViewById<TextView>(R.id.confirmSim)
        val btnDial = findViewById<Button>(R.id.btnDial)
        val btnCancel = findViewById<Button>(R.id.btnCancel)

        titleText.text = "📞 来电拨号请求"
        numberText.text = formatPhone(number)
        simInfo.text = "使用 SIM $sim 拨号"
        btnDial.text = "📶  SIM $sim 拨号 $number"

        // 直接拨号（使用电脑选择的SIM卡）
        btnDial.setOnClickListener {
            makeCall(number, sim)
            finish()
        }

        btnCancel.setOnClickListener { finish() }

        // 震动提醒用户
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(100, 100, 100), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(100, 100, 100), -1)
            }
        }
    }

    private fun makeCall(number: String, simSlot: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 100)
            Toast.makeText(this, "需要拨号权限", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager

            // 尝试用 TelecomManager 精确指定 SIM 卡
            @Suppress("DEPRECATION")
            val phoneAccounts = telecomManager.callCapablePhoneAccounts
            if (phoneAccounts.isNotEmpty()) {
                val accountIndex = if (simSlot == 2 && phoneAccounts.size >= 2) 1 else 0
                val handle = phoneAccounts[accountIndex]
                try {
                    telecomManager.placeCall(
                        Uri.parse("tel:$number"),
                        Bundle().apply {
                            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                        }
                    )
                    // 回报拨号结果给电脑
                    DialService.sendDialResult(number, "ok")
                    return
                } catch (e: Exception) {
                    // TelecomManager 失败，fallback
                }
            }

            // fallback: 直接拨号
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
            }
            startActivity(intent)
            DialService.sendDialResult(number, "ok")
        } catch (e: Exception) {
            Toast.makeText(this, "拨号失败: ${e.message}", Toast.LENGTH_LONG).show()
            DialService.sendDialResult(number, "error")
        }
    }

    private fun formatPhone(number: String): String {
        return when {
            number.length == 11 && number.startsWith("1") ->
                "${number.substring(0, 3)} ${number.substring(3, 7)} ${number.substring(7)}"
            number.length > 7 -> number
            else -> number
        }
    }

    override fun onBackPressed() {
        finish()
    }
}
