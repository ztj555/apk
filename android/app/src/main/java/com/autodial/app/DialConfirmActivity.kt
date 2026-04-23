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
        val btnSim1 = findViewById<Button>(R.id.btnDialSim1)
        val btnSim2 = findViewById<Button>(R.id.btnDialSim2)
        val btnCancel = findViewById<Button>(R.id.btnCancel)

        titleText.text = "来电拨号请求"
        numberText.text = formatPhone(number)
        simInfo.text = "电脑选择: SIM $sim"

        // 高亮电脑选择的卡
        if (sim == 1) {
            btnSim1.setBackgroundColor(ContextCompat.getColor(this, R.color.gold))
            btnSim1.setTextColor(ContextCompat.getColor(this, R.color.dark_bg))
        } else {
            btnSim2.setBackgroundColor(ContextCompat.getColor(this, R.color.gold))
            btnSim2.setTextColor(ContextCompat.getColor(this, R.color.dark_bg))
        }

        btnSim1.setOnClickListener {
            makeCall(number, 1)
            finish()
        }

        btnSim2.setOnClickListener {
            makeCall(number, 2)
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
            if (simSlot == 2) {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                @Suppress("DEPRECATION")
                val phoneAccounts = telecomManager.callCapablePhoneAccounts
                if (phoneAccounts.size >= 2) {
                    val handle = phoneAccounts[1]
                    telecomManager.placeCall(
                        Uri.parse("tel:$number"),
                        Bundle().apply {
                            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                        }
                    )
                    Toast.makeText(this, "SIM2 拨号 $number", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            // 默认直接拨号（用系统默认SIM卡）
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
            }
            startActivity(intent)
            Toast.makeText(this, "拨号 $number", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "拨号失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatPhone(number: String): String {
        return when {
            number.length == 11 && number.startsWith("1") ->
                "${number.substring(0, 3)} ${number.substring(3, 7)} ${number.substring(7)}"
            number.length > 7 ->
                number
            else -> number
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 返回键等同于取消
        finish()
    }
}
