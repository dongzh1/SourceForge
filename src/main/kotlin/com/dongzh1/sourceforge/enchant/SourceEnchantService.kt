package com.dongzh1.sourceforge.enchant

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.configuration.file.YamlConfiguration

/**
 * 附魔服务 v2 — 已移除内置附魔系统。
 * MythicMobs 技能通过 PAPI 占位符读取装备属性来实现。
 */
class SourceEnchantService(
    private val plugin: SourceForge,
    enchantsConfig: YamlConfiguration
) {
    fun enchants(): Collection<Any> = emptyList()
    fun enchant(id: String): Any? = null
    fun params(enchantId: String, level: Int): EnchantLevelConfig = EnchantLevelConfig()
    fun createBook(enchantId: String, level: Int = 1): org.bukkit.inventory.ItemStack? = null
    fun createSlotItem(amount: Int = 1): org.bukkit.inventory.ItemStack? = null
    fun applyBook(equipment: org.bukkit.inventory.ItemStack, book: org.bukkit.inventory.ItemStack): ApplyResult = ApplyResult.NOT_BOOK
    fun applySlotItem(equipment: org.bukkit.inventory.ItemStack, item: org.bukkit.inventory.ItemStack): SlotApplyResult = SlotApplyResult.NOT_ITEM
    fun readEnchantLevel(item: org.bukkit.inventory.ItemStack?, enchantId: String): Int = 0
    fun readEnchantLevel(container: org.bukkit.persistence.PersistentDataContainer, enchantId: String): Int = 0
    fun readEnchantLevels(item: org.bukkit.inventory.ItemStack?): Map<String, Int> = emptyMap()
    fun readEnchantLevels(container: org.bukkit.persistence.PersistentDataContainer): Map<String, Int> = emptyMap()
    fun copyEnchantLevels(source: org.bukkit.inventory.ItemStack, target: org.bukkit.persistence.PersistentDataContainer) {}
    fun initializeEnchantSlots(item: org.bukkit.inventory.ItemStack) {}
    fun enchantSlots(item: org.bukkit.inventory.ItemStack?): Int = 0

    enum class ApplyResult { SUCCESS, NOT_BOOK, NOT_EQUIPMENT, MAX_LEVEL, NO_SLOT }
    enum class SlotApplyResult { SUCCESS, NOT_ITEM, NOT_EQUIPMENT, MAX_SLOT }
}

data class EnchantLevelConfig(
    val values: Map<String, Double> = emptyMap()
) {
    fun double(key: String, fallback: Double = 0.0): Double = values[key] ?: fallback
    fun int(key: String, fallback: Int = 0): Int = double(key, fallback.toDouble()).toInt()
    fun format(key: String, decimals: Int = 1): String = "0"
    fun percent(key: String, decimals: Int = 0): String = "0"
    fun formatTicksAsSeconds(key: String, decimals: Int = 1): String = "0"
    fun formatMillisAsSeconds(key: String, decimals: Int = 1): String = "0"
}
