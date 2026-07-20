package com.dongzh1.sourceforge.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * 统一数值/词条格式化：去尾随0，比例词条转百分比。ForgeItemService(装备lore)与 ModLoreBuilder(MOD卡lore)
 * 原先各自维护一份几乎相同的实现，容易改漏导致两边数值显示口径不一致，故抽到这里共用一份算法。
 * 固定用 [Locale.ROOT] 的小数点符号——不这样做的话，服务器 JVM 若被启动在小数点用逗号的区域设置下
 * (如 de/fr/pl/ru)，DecimalFormat 默认走 JVM 当前 Locale，数值会被渲染成"2,7"这种格式。
 */
object AffixFormat {
    private val SYMBOLS = DecimalFormatSymbols(Locale.ROOT)

    fun number(value: Double, decimals: Int): String {
        val pattern = if (decimals <= 0) "0" else "0." + "0".repeat(decimals)
        val s = DecimalFormat(pattern, SYMBOLS).format(value)
        return if ('.' in s) s.trimEnd('0').trimEnd('.') else s
    }

    /** 比例词条 ×100 加 %(小数位相应减2)，其余按 decimals；统一去掉尾随0。 */
    fun affixValue(percent: Boolean, decimals: Int, value: Double): String {
        val v = if (percent) value * 100.0 else value
        val d = if (percent) (decimals - 2).coerceAtLeast(0) else decimals
        val s = number(v, d)
        return if (percent) "$s%" else s
    }
}
