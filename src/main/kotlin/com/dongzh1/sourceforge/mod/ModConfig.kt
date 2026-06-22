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
    val itemLore: List<String>
) {
    fun appliesTo(weaponCategory: String?, equipmentId: String?): Boolean {
        if (applicableEquipment.isNotEmpty()) {
            return equipmentId != null && equipmentId.lowercase() in applicableEquipment
        }
        if (applicableCategories.isEmpty()) return true
        return weaponCategory != null && weaponCategory.lowercase() in applicableCategories
    }
}
