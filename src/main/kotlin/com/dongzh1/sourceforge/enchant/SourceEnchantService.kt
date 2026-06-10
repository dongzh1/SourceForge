package com.dongzh1.sourceforge.enchant

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.util.color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import java.text.DecimalFormat

class SourceEnchantService(
    private val plugin: SourceForge,
    enchantsConfig: YamlConfiguration
) {
    private val bookEnchantKey = NamespacedKey(plugin, "enchant_book_id")
    private val bookLevelKey = NamespacedKey(plugin, "enchant_book_level")
    private val slotItemKey = NamespacedKey(plugin, "enchant_slot_item")
    private val enchantsKey = NamespacedKey(plugin, "enchants")
    private val enchantSlotsKey = NamespacedKey(plugin, "enchant_slots")

    val defaultEnchantSlots = 3
    val maxEnchantSlots = 6
    private val enchants = loadEnchants(enchantsConfig)

    fun enchants(): Collection<SourceEnchant> {
        return enchants.values
    }

    fun enchant(id: String): SourceEnchant? {
        return enchants[id.lowercase()]
    }

    fun params(enchantId: String, level: Int): EnchantLevelConfig {
        return enchant(enchantId)?.level(level) ?: EnchantLevelConfig()
    }

    fun createBook(enchantId: String, level: Int = 1): ItemStack? {
        val enchant = enchant(enchantId) ?: return null
        val normalizedLevel = level.coerceIn(1, enchant.maxLevel)
        val item = ItemStack(Material.ENCHANTED_BOOK)
        val meta = item.itemMeta
        meta.setDisplayName(color("&d附魔书: ${enchant.displayName} ${roman(normalizedLevel)}"))
        meta.lore = color(
            listOf(
                "&7类型: &f${enchant.typeName}",
                "&7效果: &f${enchant.description(normalizedLevel)}",
                "",
                "&e手持装备使用 /sf enchant apply 应用"
            )
        )
        meta.persistentDataContainer.set(bookEnchantKey, PersistentDataType.STRING, enchant.id)
        meta.persistentDataContainer.set(bookLevelKey, PersistentDataType.INTEGER, normalizedLevel)
        item.itemMeta = meta
        return item
    }

    fun createSlotItem(amount: Int = 1): ItemStack {
        val item = ItemStack(Material.AMETHYST_SHARD, amount.coerceAtLeast(1))
        val meta = item.itemMeta
        meta.setDisplayName(color("&d附魔扩容晶核"))
        meta.lore = color(
            listOf(
                "&7效果: &f为主手 SourceForge 装备增加 1 个附魔槽",
                "&7上限: &f$maxEnchantSlots 个附魔槽",
                "",
                "&e主手持装备，副手放晶核，使用 /sf enchant slot"
            )
        )
        meta.persistentDataContainer.set(slotItemKey, PersistentDataType.BYTE, 1)
        item.itemMeta = meta
        return item
    }

    fun applyBook(equipment: ItemStack, book: ItemStack): ApplyResult {
        val enchantId = bookEnchantId(book) ?: return ApplyResult.NOT_BOOK
        val enchant = enchant(enchantId) ?: return ApplyResult.NOT_BOOK
        val level = bookLevel(book).coerceIn(1, enchant.maxLevel)
        if (!plugin.itemService.isSourceEquipment(equipment)) return ApplyResult.NOT_EQUIPMENT
        plugin.itemService.stripVanillaEnchantments(equipment)
        val current = readEnchantLevel(equipment, enchant.id)
        if (current >= enchant.maxLevel) return ApplyResult.MAX_LEVEL
        if (current <= 0 && readEnchantIds(equipment).size >= enchantSlots(equipment)) return ApplyResult.NO_SLOT
        val next = if (current == level) (current + 1).coerceAtMost(enchant.maxLevel) else maxOf(current, level)
        writeEnchantLevel(equipment, enchant, next)
        book.amount -= 1
        return ApplyResult.SUCCESS
    }

    fun applySlotItem(equipment: ItemStack, item: ItemStack): SlotApplyResult {
        if (!isSlotItem(item)) return SlotApplyResult.NOT_ITEM
        if (!plugin.itemService.isSourceEquipment(equipment)) return SlotApplyResult.NOT_EQUIPMENT
        val current = enchantSlots(equipment)
        if (current >= maxEnchantSlots) return SlotApplyResult.MAX_SLOT
        writeEnchantSlots(equipment, current + 1)
        item.amount -= 1
        return SlotApplyResult.SUCCESS
    }

    fun readEnchantLevel(item: ItemStack?, enchantId: String): Int {
        if (item == null || !item.hasItemMeta()) return 0
        val pdc = item.itemMeta.persistentDataContainer
        return pdc.get(enchantKey(enchantId), PersistentDataType.INTEGER) ?: 0
    }

    fun readEnchantLevel(container: PersistentDataContainer, enchantId: String): Int {
        return container.get(enchantKey(enchantId), PersistentDataType.INTEGER) ?: 0
    }

    fun readEnchantLevels(item: ItemStack?): Map<String, Int> {
        if (item == null || !item.hasItemMeta()) return emptyMap()
        return readEnchantLevels(item.itemMeta.persistentDataContainer)
    }

    fun readEnchantLevels(container: PersistentDataContainer): Map<String, Int> {
        return enchants.keys.mapNotNull { id ->
            val level = readEnchantLevel(container, id)
            if (level > 0) id to level else null
        }.toMap()
    }

    fun copyEnchantLevels(source: ItemStack, target: PersistentDataContainer) {
        for ((id, level) in readEnchantLevels(source)) {
            target.set(enchantKey(id), PersistentDataType.INTEGER, level)
        }
    }

    fun initializeEnchantSlots(item: ItemStack) {
        if (!plugin.itemService.isSourceEquipment(item)) return
        val slots = enchantSlots(item)
        writeEnchantSlots(item, slots)
    }

    fun enchantSlots(item: ItemStack?): Int {
        if (item == null || !item.hasItemMeta()) return defaultEnchantSlots
        return item.itemMeta.persistentDataContainer.get(enchantSlotsKey, PersistentDataType.INTEGER)
            ?.coerceIn(1, maxEnchantSlots)
            ?: defaultEnchantSlots
    }

    private fun writeEnchantLevel(item: ItemStack, enchant: SourceEnchant, level: Int) {
        val meta = item.itemMeta
        val pdc = meta.persistentDataContainer
        pdc.set(enchantKey(enchant.id), PersistentDataType.INTEGER, level)
        val ids = readEnchantIds(item).toMutableSet()
        ids += enchant.id
        pdc.set(enchantsKey, PersistentDataType.STRING, ids.joinToString(","))
        meta.lore = rebuildEnchantLore(meta.lore, pdc)
        item.itemMeta = meta
    }

    private fun writeEnchantSlots(item: ItemStack, slots: Int) {
        val meta = item.itemMeta
        val pdc = meta.persistentDataContainer
        pdc.set(enchantSlotsKey, PersistentDataType.INTEGER, slots.coerceIn(1, maxEnchantSlots))
        meta.lore = rebuildEnchantLore(meta.lore, pdc)
        item.itemMeta = meta
    }

    private fun rebuildEnchantLore(currentLore: List<String>?, pdc: PersistentDataContainer): List<String> {
        val baseLore = currentLore
            ?.takeWhile { !it.contains("§d附魔:") && !it.contains("附魔:") }
            ?.dropLastWhile { it.isBlank() || it == "§r" }
            ?.toMutableList()
            ?: mutableListOf()
        val ids = pdc.get(enchantsKey, PersistentDataType.STRING)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val slots = (pdc.get(enchantSlotsKey, PersistentDataType.INTEGER) ?: defaultEnchantSlots).coerceIn(1, maxEnchantSlots)
        baseLore += ""
        baseLore += color("&d附魔: &7(${ids.size}/$slots)")
        if (ids.isEmpty()) {
            baseLore += color("&8未附魔")
        } else {
            for (id in ids) {
                val enchant = enchant(id) ?: continue
                val level = pdc.get(enchantKey(id), PersistentDataType.INTEGER) ?: continue
                baseLore += color("&d${enchant.displayName} ${roman(level)} &7- ${enchant.shortDescription(level)}")
            }
        }
        return baseLore
    }

    private fun readEnchantIds(item: ItemStack): List<String> {
        if (!item.hasItemMeta()) return emptyList()
        return item.itemMeta.persistentDataContainer.get(enchantsKey, PersistentDataType.STRING)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    private fun bookEnchantId(item: ItemStack?): String? {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return null
        return item.itemMeta.persistentDataContainer.get(bookEnchantKey, PersistentDataType.STRING)
    }

    private fun bookLevel(item: ItemStack?): Int {
        if (item == null || !item.hasItemMeta()) return 1
        return item.itemMeta.persistentDataContainer.get(bookLevelKey, PersistentDataType.INTEGER) ?: 1
    }

    private fun isSlotItem(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return false
        return item.itemMeta.persistentDataContainer.has(slotItemKey, PersistentDataType.BYTE)
    }

    private fun enchantKey(enchantId: String): NamespacedKey {
        return NamespacedKey(plugin, "enchant_$enchantId")
    }

    private fun roman(level: Int): String {
        return when (level) {
            1 -> "I"
            2 -> "II"
            3 -> "III"
            4 -> "IV"
            5 -> "V"
            else -> level.toString()
        }
    }

    private fun loadEnchants(config: YamlConfiguration): Map<String, SourceEnchant> {
        val section = config.getConfigurationSection("enchants") ?: return emptyMap()
        return section.getKeys(false).associateWith { id ->
            val path = "enchants.$id"
            val levels = config.getConfigurationSection("$path.levels")
                ?.getKeys(false)
                ?.mapNotNull { rawLevel ->
                    val level = rawLevel.toIntOrNull() ?: return@mapNotNull null
                    level to loadLevel(config, "$path.levels.$rawLevel")
                }
                ?.toMap()
                ?: emptyMap()
            val maxLevel = config.getInt("$path.max-level", levels.keys.maxOrNull() ?: 1).coerceAtLeast(1)
            SourceEnchant(
                id = id.lowercase(),
                displayName = config.getString("$path.display-name", id)!!,
                maxLevel = maxLevel,
                typeName = config.getString("$path.type-name", "被动")!!,
                action = config.getString("$path.action", "passive")!!.lowercase(),
                mythicSkill = config.getString("$path.mythic-skill", "")!!.takeIf { it.isNotBlank() },
                descriptionFormat = config.getString("$path.description-format", "%id% %level%")!!,
                shortFormat = config.getString("$path.short-format", config.getString("$path.description-format", "%id% %level%"))!!,
                levels = levels
            )
        }
    }

    private fun loadLevel(config: YamlConfiguration, path: String): EnchantLevelConfig {
        val params = config.getConfigurationSection(path)
            ?.getKeys(false)
            ?.associate { key -> key to config.getDouble("$path.$key", 0.0) }
            ?: emptyMap()
        return EnchantLevelConfig(params)
    }

    enum class ApplyResult {
        SUCCESS,
        NOT_BOOK,
        NOT_EQUIPMENT,
        MAX_LEVEL,
        NO_SLOT
    }

    enum class SlotApplyResult {
        SUCCESS,
        NOT_ITEM,
        NOT_EQUIPMENT,
        MAX_SLOT
    }
}

data class SourceEnchant(
    val id: String,
    val displayName: String,
    val maxLevel: Int,
    val typeName: String,
    val action: String,
    val mythicSkill: String?,
    val descriptionFormat: String,
    val shortFormat: String,
    val levels: Map<Int, EnchantLevelConfig>
) {
    fun level(level: Int): EnchantLevelConfig {
        val normalized = level.coerceIn(1, maxLevel)
        return levels[normalized]
            ?: levels.filterKeys { it <= normalized }.maxByOrNull { it.key }?.value
            ?: EnchantLevelConfig()
    }

    fun description(level: Int): String {
        return render(descriptionFormat, level)
    }

    fun shortDescription(level: Int): String {
        return render(shortFormat, level)
    }

    private fun render(format: String, level: Int): String {
        val params = level(level)
        val replacements = linkedMapOf(
            "id" to id,
            "level" to level.toString(),
            "lifesteal_percent" to params.percent("lifesteal-rate", 0),
            "chance_percent" to params.percent("chance", 0),
            "damage_bonus_percent" to params.percent("damage-bonus-rate", 0),
            "physical_damage" to params.format("physical-damage", 1),
            "magic_damage" to params.format("magic-damage", 1),
            "true_damage" to params.format("true-damage", 1),
            "physical_damage_percent" to params.percent("physical-damage-rate", 0),
            "magic_damage_percent" to params.percent("magic-damage-rate", 0),
            "crit_chance_percent" to params.percent("crit-chance-bonus", 0),
            "crit_damage_percent" to params.percent("crit-damage-bonus", 0),
            "armor_pierce" to params.format("armor-pierce", 1),
            "fire_damage" to params.format("fire-damage", 1),
            "ice_damage" to params.format("ice-damage", 1),
            "lightning_damage" to params.format("lightning-damage", 1),
            "water_damage" to params.format("water-damage", 1),
            "wood_damage" to params.format("wood-damage", 1),
            "reflect_damage" to params.format("reflect-damage", 1),
            "knockback_chance_percent" to params.percent("knockback-chance", 0),
            "knockback_strength" to params.format("knockback-strength", 2),
            "poison_chance_percent" to params.percent("poison-chance", 0),
            "burn_chance_percent" to params.percent("burn-chance", 0),
            "slow_chance_percent" to params.percent("slow-chance", 0),
            "slow_seconds" to params.formatTicksAsSeconds("slow-duration-ticks", 1),
            "radius" to params.format("radius", 1),
            "damage" to params.format("damage", 1),
            "heal" to params.format("heal", 1),
            "shield" to params.format("shield", 1),
            "duration_seconds" to params.formatMillisAsSeconds("duration-millis", 1),
            "distance" to params.format("distance", 1),
            "cooldown_seconds" to params.formatMillisAsSeconds("cooldown-millis", 1)
        )
        return replacements.entries.fold(format) { text, (key, value) ->
            text.replace("%$key%", value)
        }
    }
}

data class EnchantLevelConfig(
    val values: Map<String, Double> = emptyMap()
) {
    fun double(key: String, fallback: Double = 0.0): Double {
        return values[key] ?: fallback
    }

    fun int(key: String, fallback: Int = 0): Int {
        return double(key, fallback.toDouble()).toInt()
    }

    fun format(key: String, decimals: Int = 1): String {
        return formatDouble(double(key), decimals)
    }

    fun percent(key: String, decimals: Int = 0): String {
        return formatDouble(double(key) * 100.0, decimals)
    }

    fun formatTicksAsSeconds(key: String, decimals: Int = 1): String {
        return formatDouble(double(key) / 20.0, decimals)
    }

    fun formatMillisAsSeconds(key: String, decimals: Int = 1): String {
        return formatDouble(double(key) / 1000.0, decimals)
    }

    private fun formatDouble(value: Double, decimals: Int): String {
        if (decimals <= 0) return value.toInt().toString()
        return DecimalFormat("0." + "0".repeat(decimals)).format(value)
    }
}
