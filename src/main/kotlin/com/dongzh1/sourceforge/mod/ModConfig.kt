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
    val weight: Double = 1.0
) {
    fun appliesTo(weaponCategory: String?, equipmentId: String?): Boolean {
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
