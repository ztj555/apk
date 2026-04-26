package com.autodial.app

/**
 * 拨号卡选择模式
 */
enum class DialMode(val label: String, val key: String) {
    /** 卡1 → 卡2 → 卡1 交替，根据通话记录最近一次用的卡自动切换 */
    ALTERNATE("轮流", "alternate"),
    /** 记住每个号码上次用的卡，下次自动用同一张；首次拨打弹窗选择 */
    REMEMBER("记忆", "remember"),
    /** 始终使用卡1 */
    SIM1("卡1", "sim1"),
    /** 始终使用卡2 */
    SIM2("卡2", "sim2"),
    /** 每次拨号弹出自定义选卡卡片，用户手动选择 */
    POPUP("弹窗", "popup");

    companion object {
        fun fromKey(key: String): DialMode =
            entries.firstOrNull { it.key == key } ?: ALTERNATE
    }
}
