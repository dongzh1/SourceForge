package com.dongzh1.sourceforge.mod

/**
 * 技能触发栏：武器的技能槽是【定型槽】，槽位下标固定对应一种触发方式。
 * 技能MOD 放进哪个触发栏，就由那种操作触发（onActivate）。MOD 用 allowed-triggers 白名单约束能放哪些栏
 * （例：砍击类只允许 left，即只能放左键栏）。
 *
 * 触发消歧（见 SkillModListener）：
 *  - 地面 + 左键     -> LEFT
 *  - 空中 + 左键     -> MIDAIR（"跳跃时在空中执行"）
 *  - 右键            -> RIGHT
 *  - 潜行 + 左键     -> SHIFT_LEFT
 *  - 潜行 + 右键     -> SHIFT_RIGHT
 *  - 潜行 + F(换手)  -> SHIFT_F
 *
 * 下标 [index] 即技能槽数组下标，务必与 ModService.readSkillSlots 的顺序一致、且连续从 0 起。
 */
enum class TriggerSlot(val index: Int, val id: String, val display: String) {
    LEFT(0, "left", "左键"),
    RIGHT(1, "right", "右键"),
    SHIFT_LEFT(2, "shift_left", "Shift+左键"),
    SHIFT_RIGHT(3, "shift_right", "Shift+右键"),
    SHIFT_F(4, "shift_f", "Shift+F"),
    MIDAIR(5, "midair", "空中");

    companion object {
        /** 触发栏总数（= 技能槽上限）。 */
        val COUNT = entries.size

        fun byIndex(i: Int): TriggerSlot? = entries.firstOrNull { it.index == i }

        /** 解析配置里的触发 id（大小写不敏感，容忍别名）。 */
        fun byId(s: String?): TriggerSlot? {
            val k = s?.trim()?.lowercase() ?: return null
            return when (k) {
                "left", "left_click", "attack" -> LEFT
                "right", "right_click", "use" -> RIGHT
                "shift_left", "sneak_left", "shift+left" -> SHIFT_LEFT
                "shift_right", "sneak_right", "shift+right" -> SHIFT_RIGHT
                "shift_f", "sneak_f", "swap", "shift+f" -> SHIFT_F
                "midair", "air", "jump", "aerial" -> MIDAIR
                else -> entries.firstOrNull { it.id == k }
            }
        }
    }
}
