package com.dongzh1.sourceforge.mod

import org.bukkit.Material

data class ModConfig(
    val id: String,
    val displayName: String,
    val itemId: String,
    val material: Material,
    val customModelData: Int?,
    val cost: Int,
    val maxPerEquipment: Int,
    val exclusivityGroup: String?,
    val applicableCategories: Set<String>,
    val applicableEquipment: Set<String>,
    val effects: Map<String, Double>,
    val tags: List<String>,
    val itemLore: List<String>,
    /** 最大段数（Warframe 式）。0 = 不可升级，效果取满（向后兼容）。 */
    val maxRank: Int = 0,
    /** rank r -> r+1 所需升级核心 = upgradeCostBase * (r+1)。 */
    val upgradeCostBase: Int = 1,
    /** 抽奖权重（Feature C）。 */
    val weight: Double = 1.0,
    /** 是否为（武器专属）主动技能MOD：只能装进武器的技能触发栏，按键触发。护甲不允许装这类MOD。 */
    val skill: Boolean = false,
    /** 是否为护甲专属被动技能MOD：只能装进护甲的被动技能槽（1格），装备即生效，无需按键。
     *  与 [skill] 互斥的另一条腿——同一张卡不应该两个都 true。 */
    val passiveSkill: Boolean = false,
    /** 限定安装部位（head/chest/legs/feet），仅护甲件生效；空 = 该MOD适用类别下的任意部位。 */
    val applicableSlots: Set<String> = emptySet(),
    /** 技能MOD 对应的 MythicMobs 物品内部名（mythicmobs:type）。安装到技能槽时盖到装备 PDC 上。 */
    val mmItem: String? = null,
    /**
     * 允许放入的触发栏 id 白名单（见 [TriggerSlot]，如 ["left"]）。
     * 空集 = 不限制（可放任意触发栏，向后兼容旧技能MOD）。约束在 ModService.tryInstallSkill 落地。
     */
    val allowedTriggers: Set<String> = emptySet(),
    /** 卡片头部类别标签覆盖（默认按 tags/skill 推导：属性/元素/技能/测试）。 */
    val typeLabel: String? = null,
    /** 机制说明行（统一排版的说明区，支持 &色码 与 %cfg[:s|t]:path|默认% 占位符）。 */
    val description: List<String> = emptyList(),
    /** 蓝耗显示文本（"消耗" 行，支持 %cfg% 占位符实时取 config.yml 值），null = 不显示。 */
    val manaCost: String? = null,
    /** 冷却显示文本（"冷却" 行，支持 %cfg% 占位符），null = 不显示。 */
    val cooldown: String? = null
) {
    /** 该MOD是否允许放进触发栏 [slot]。空白名单视为全允许。 */
    fun allowsTrigger(slot: TriggerSlot): Boolean =
        allowedTriggers.isEmpty() || slot.id in allowedTriggers

    /** [armorSlot] = head/chest/legs/feet（plugin.itemService.armorSlotKey），仅护甲件非空；
     *  武器/无法识别传 null。applicableSlots 非空时先卡这一关，再走原有 equipment/category 判定。 */
    fun appliesTo(weaponCategory: String?, equipmentId: String?, armorSlot: String? = null): Boolean {
        if (applicableSlots.isNotEmpty() && (armorSlot == null || armorSlot.lowercase() !in applicableSlots)) return false
        if (applicableEquipment.isNotEmpty()) {
            return equipmentId != null && equipmentId.lowercase() in applicableEquipment
        }
        if (applicableCategories.isEmpty()) return true
        return weaponCategory != null && weaponCategory.lowercase() in applicableCategories
    }

    /** 当前段位的效果缩放比例。maxRank=0 -> 1.0（满效果，向后兼容）。 */
    fun rankRatio(rank: Int): Double {
        if (maxRank <= 0) return 1.0
        val r = rank.coerceIn(0, maxRank)
        return (r + 1).toDouble() / (maxRank + 1).toDouble()
    }

    /** 某词条在指定段位的实际数值。 */
    fun effectAtRank(affixId: String, rank: Int): Double {
        val base = effects[affixId] ?: return 0.0
        return base * rankRatio(rank)
    }

    /** rank r -> r+1 所需的升级核心数。 */
    fun upgradeCostFor(rank: Int): Int = upgradeCostBase.coerceAtLeast(1) * (rank.coerceAtLeast(0) + 1)
}
