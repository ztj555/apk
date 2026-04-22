package com.autodial.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * 拨号确认弹窗 Activity
 * 当收到拨号指令时弹出，显示号码和SIM卡选择，用户确认后执行拨号
 */
class DialConfirmActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 显示为对话框样式
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        setContentView(R.layout.activity_dial_confirm)

        val number = intent.getStringExtra("number") ?: ""
        var simSlot = intent.getIntExtra("simSlot", 1)

        val numberText: TextView = findViewById(R.id.confirmNumber)
        val sim1Btn: Button = findViewById(R.id.sim1Btn)
        val sim2Btn: Button = findViewById(R.id.sim2Btn)
        val dialBtn: Button = findViewById(R.id.confirmDialBtn)
        val cancelBtn: Button = findViewById(R.id.confirmCancelBtn)

        numberText.text = number
        updateSimButtons(sim1Btn, sim2Btn, simSlot)

        sim1Btn.setOnClickListener {
            simSlot = 1
            updateSimButtons(sim1Btn, sim2Btn, simSlot)
        }

        sim2Btn.setOnClickListener {
            simSlot = 2
            updateSimButtons(sim1Btn, sim2Btn, simSlot)
        }

        dialBtn.setOnClickListener {
            performDial(number, simSlot)
            finish()
        }

        cancelBtn.setOnClickListener {
            // 通知电脑端取消
            finish()
        }
    }

    private fun updateSimButtons(sim1Btn: Button, sim2Btn: Button, slot: Int) {
        sim1Btn.isSelected = (slot == 1)
        sim2Btn.isSelected = (slot == 2)
    }

    private fun performDial(number: String, simSlot: Int) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "❌ 没有拨号权限，请在设置中授权", Toast.LENGTH_LONG).show()
            return
        }

        try {
            // 指定SIM卡拨号
            if (simSlot == 2) {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                @Suppress("DEPRECATION")
                val phoneAccounts = telecomManager.callCapablePhoneAccounts

                // 尝试通过不同的 PhoneAccountHandle 选择 SIM 卡
                if (phoneAccounts.size >= 2) {
                    // phoneAccounts 通常按 SIM 卡槽排序
                    val handle = phoneAccounts[simSlot - 1]
                    telecomManager.placeCall(
                        Uri.parse("tel:$number"),
                        Bundle().apply {
                            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                        }
                    )
                    Toast.makeText(this, "📞 正在通过 SIM$simSlot 拨号 $number", Toast.LENGTH_SHORT).show()
                    return
                }
            }

            // 默认拨号（SIM1 或无法区分SIM卡时）
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            Toast.makeText(this, "📞 正在拨号 $number", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "❌ 拨号失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onBackPressed() {
        // 按返回键等同于取消
        super.onBackPressed()
    }
}
