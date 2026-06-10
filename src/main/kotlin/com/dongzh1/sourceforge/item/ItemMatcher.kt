package com.dongzh1.sourceforge.item

import org.bukkit.Material
import org.bukkit.inventory.ItemStack

object ItemMatcher {
    fun matches(item: ItemStack?, id: String): Boolean {
        if (item == null || item.type == Material.AIR) return false
        val normalized = normalize(id)
        if (normalized.startsWith("minecraft:")) {
            val material = Material.matchMaterial(normalized.substringAfter("minecraft:").uppercase()) ?: return false
            return item.type == material
        }
        val ceId = CraftEngineHook.itemId(item)
        if (ceId != null) {
            return ceId.equals(normalized, ignoreCase = true)
                || ceId.equals(normalized.removePrefix("ce_"), ignoreCase = true)
        }
        val material = Material.matchMaterial(normalized.uppercase())
        return material != null && item.type == material
    }

    fun display(id: String): String {
        val normalized = normalize(id)
        return if (normalized.startsWith("minecraft:")) {
            normalized.substringAfter("minecraft:").uppercase()
        } else {
            normalized
        }
    }

    private fun normalize(id: String): String = id.trim().lowercase()
}
