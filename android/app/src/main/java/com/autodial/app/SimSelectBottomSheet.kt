package com.autodial.app

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * 自定义 SIM 卡选择底部弹窗
 * 替代系统原生的"请选择 SIM 卡"对话框
 * 支持显示上次通话信息，帮助用户选择
 */
class SimSelectBottomSheet : BottomSheetDialogFragment() {

    private var number: String = ""
    private var onSimSelected: ((number: String, simSlot: Int) -> Unit)? = null

    companion object {
        const val TAG = "SimSelectBottomSheet"

        /**
         * 创建实例
         * @param number 要拨打的号码
         * @param lastHint 上次通话提示，如 "卡1  昨天"
         * @param callback 选卡后的回调 (number, simSlot)
         */
        fun newInstance(number: String, lastHint: String, callback: (String, Int) -> Unit): SimSelectBottomSheet {
            return SimSelectBottomSheet().apply {
                this.number = number
                this.onSimSelected = callback
                arguments = Bundle().apply {
                    putString("number", number)
                    putString("last_hint", lastHint)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_sim_select, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val numberText = view.findViewById<TextView>(R.id.simSelectNumber)
        val lastHintText = view.findViewById<TextView>(R.id.simSelectLastHint)
        val btn1 = view.findViewById<LinearLayout>(R.id.simSelectBtn1)
        val btn2 = view.findViewById<LinearLayout>(R.id.simSelectBtn2)
        val cancelBtn = view.findViewById<TextView>(R.id.simSelectCancel)

        // 获取参数
        val num = arguments?.getString("number") ?: ""
        val hint = arguments?.getString("last_hint") ?: ""

        // 号码脱敏显示
        numberText.text = if (num.length > 7) {
            num.substring(0, 3) + "****" + num.substring(num.length - 4)
        } else {
            num
        }

        // 上次通话提示
        if (hint.isNotEmpty()) {
            lastHintText.text = "📌 $hint"
            lastHintText.visibility = View.VISIBLE
        }

        // 卡1 点击
        btn1.setOnClickListener {
            onSimSelected?.invoke(num, 0)
            dismiss()
        }

        // 卡2 点击
        btn2.setOnClickListener {
            onSimSelected?.invoke(num, 1)
            dismiss()
        }

        // 取消
        cancelBtn.setOnClickListener {
            // 用户取消拨号，通知电脑
            DialService.sendDialResult(num, "cancelled")
            dismiss()
        }

        // 按钮按压效果
        setupPressEffect(btn1)
        setupPressEffect(btn2)
    }

    override fun getTheme(): Int {
        // 使用透明背景让圆角生效
        return R.style.Theme_AutoDial_BottomSheetDialog
    }

    /**
     * 简单的按压变色效果
     */
    private fun setupPressEffect(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> v.setBackgroundColor(Color.parseColor("#2E323C"))
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> v.setBackgroundColor(Color.parseColor("#22252E"))
            }
            false
        }
    }
}
