package com.autodial.app

/**
 * 拨号卡选择模式
 */
enum class DialMode(val label: String, val key: String) {
    /** 每次拨号弹出自定义选卡卡片，用户手动选择 */
    POPUP("弹窗", "popup"),
    /** 未被对比通话记录识别→按循环模式拨号；已识别→弹窗让用户选择 */
    ROUND_SELECT("轮选", "round_select"),
    /** 始终使用卡1 */
    SIM1("卡1", "sim1"),
    /** 始终使用卡2 */
    SIM2("卡2", "sim2"),
    /** 卡1 → 卡2 → 卡1 全局交替，根据最近一次拨号用的卡自动切换 */
    ALTERNATE("循环", "alternate"),
    /** 记住每个号码上次用的卡，下次自动用同一张；首次拨打弹窗选择 */
    REMEMBER("记忆", "remember");

    companion object {
        fun fromKey(key: String): DialMode =
            entries.firstOrNull { it.key == key } ?: ALTERNATE
    }
}
