package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.config.AffixConfig
import com.dongzh1.sourceforge.config.ForgeConfig
import com.dongzh1.sourceforge.item.CraftEngineHook
import com.dongzh1.sourceforge.util.color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import java.text.DecimalFormat

class ModService(
    private val plugin: SourceForge,
    private val forgeConfig: ForgeConfig,
    val mods: Map<String, ModConfig>
) {
    private val modCapacityKey = NamespacedKey(plugin, "mod_capacity")
    private val modInstalledKey = NamespacedKey(plugin, "mod_installed")
    private val modIdKey = NamespacedKey(plugin, "mod_id")

    /** affixId -> AffixConfig 直接查表，热路径复用。 */
    private val affixById: Map<String, AffixConfig> = forgeConfig.affixes

    /** affixId -> mod_delta_<pdcKey> NamespacedKey，避免反复构造。 */
    private val modDeltaKeys: Map<String, NamespacedKey> =
        forgeConfig.affixes.values.associate { it.id to NamespacedKey(plugin, "mod_delta_${it.pdcKey}") }

    private val marker = color("&7---- 改造 ----")

    enum class InstallResult {
        SUCCESS,
        CAPACITY_EXCEEDED,
        WRONG_CATEGORY,
        MAX_COUNT_EXCEEDED,
        EXCLUSIVITY_CONFLICT,
        SLOT_OCCUPIED,
        INVALID_MOD,
        NOT_EQUIPMENT
    }

    fun allModIds(): Set<String> = mods.keys

    fun isModItem(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return false
        return item.itemMeta.persistentDataContainer.has(modIdKey, PersistentDataType.STRING)
    }

    fun modId(item: ItemStack?): String? {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return null
        return item.itemMeta.persistentDataContainer.get(modIdKey, PersistentDataType.STRING)
    }

    fun modConfig(item: ItemStack?): ModConfig? {
        val id = modId(item) ?: return null
        return mods[id]
    }

    fun createModItem(id: String, amount: Int = 1): ItemStack? {
        val mod = mods[id] ?: return null
        val item = CraftEngineHook.build(mod.itemId, amount.coerceAtLeast(1))
            ?: ItemStack(mod.material, amount.coerceAtLeast(1))
        val meta = item.itemMeta
        meta.setDisplayName(color(mod.displayName))
        val loreLines = if (mod.itemLore.isNotEmpty()) {
            mod.itemLore.map { line ->
                var l = line.replace("%cost%", mod.cost.toString())
                for ((affixId, value) in mod.effects) {
                    val affix = affixById[affixId]
                    val formatted = if (affix != null) format(value, affix.decimals) else format(value, 1)
                    l = l.replace("%effect_$affixId%", formatted)
                }
                l
            }
        } else {
            val def = mutableListOf("&8MOD", "&7容量消耗: &e${mod.cost}", "")
            for ((affixId, value) in mod.effects) {
                val affix = affixById[affixId]
                val name = affix?.displayName ?: affixId
                val formatted = if (affix != null) format(value, affix.decimals) else format(value, 1)
                def += "&7$name &f+$formatted"
            }
            def
        }
        meta.lore = color(loreLines)
        mod.customModelData?.let { meta.setCustomModelData(it) }
        meta.persistentDataContainer.set(modIdKey, PersistentDataType.STRING, id)
        item.itemMeta = meta
        return item
    }

    fun readInstalledSlots(item: ItemStack?): List<String?> {
        val raw = if (item == null || !item.hasItemMeta()) null
        else item.itemMeta.persistentDataContainer.get(modInstalledKey, PersistentDataType.STRING)
        val tokens = (raw ?: "").split(",")
        val slots = MutableList<String?>(8) { null }
        for (i in 0 until 8) {
            val token = tokens.getOrNull(i)?.trim()
            slots[i] = if (token.isNullOrBlank()) null else token
        }
        return slots
    }

    private fun writeInstalledSlots(meta: ItemMeta, slots: List<String?>) {
        val padded = (0 until 8).map { slots.getOrNull(it) ?: "" }
        meta.persistentDataContainer.set(modInstalledKey, PersistentDataType.STRING, padded.joinToString(","))
    }

    fun usedCapacity(item: ItemStack?): Int {
        return readInstalledSlots(item).sumOf { id ->
            id?.let { mods[it]?.cost } ?: 0
        }
    }

    fun readCapacity(item: ItemStack): Int {
        val meta = item.itemMeta
        val existing = meta.persistentDataContainer.get(modCapacityKey, PersistentDataType.INTEGER)
        if (existing != null) return existing
        val category = plugin.itemService.weaponCategory(item) ?: "default"
        val tier = plugin.itemService.equipmentTier(item)
        val computed = forgeConfig.modCapacity.computeCapacity(category, tier)
        meta.persistentDataContainer.set(modCapacityKey, PersistentDataType.INTEGER, computed)
        item.itemMeta = meta
        return computed
    }

    fun maxSlots(item: ItemStack): Int =
        forgeConfig.modCapacity.computeMaxSlots(plugin.itemService.weaponCategory(item) ?: "default")

    fun validateInstall(item: ItemStack, mod: ModConfig, targetSlot: Int): InstallResult {
        val category = plugin.itemService.weaponCategory(item)
        val equipId = plugin.itemService.weaponType(item)
        if (!mod.appliesTo(category, equipId)) return InstallResult.WRONG_CATEGORY
        val slots = readInstalledSlots(item)
        val sameCount = slots.count { it == mod.id }
        if (sameCount >= mod.maxPerEquipment) return InstallResult.MAX_COUNT_EXCEEDED
        val group = mod.exclusivityGroup
        if (!group.isNullOrBlank()) {
            val conflict = slots.withIndex().any { (idx, installedId) ->
                idx != targetSlot && installedId != null && installedId != mod.id &&
                    mods[installedId]?.exclusivityGroup?.takeIf { it.isNotBlank() } == group
            }
            if (conflict) return InstallResult.EXCLUSIVITY_CONFLICT
        }
        if (usedCapacity(item) + mod.cost > readCapacity(item)) return InstallResult.CAPACITY_EXCEEDED
        return InstallResult.SUCCESS
    }

    fun tryInstall(item: ItemStack, modItem: ItemStack, slotIndex: Int): InstallResult {
        if (!plugin.itemService.isSourceEquipment(item)) return InstallResult.NOT_EQUIPMENT
        val mod = modConfig(modItem) ?: return InstallResult.INVALID_MOD
        val slots = readInstalledSlots(item).toMutableList()
        if (slotIndex !in 0 until 8) return InstallResult.INVALID_MOD
        if (slots[slotIndex] != null) return InstallResult.SLOT_OCCUPIED
        val v = validateInstall(item, mod, slotIndex)
        if (v != InstallResult.SUCCESS) return v
        slots[slotIndex] = mod.id
        val meta = item.itemMeta
        writeInstalledSlots(meta, slots)
        item.itemMeta = meta
        reapplyModEffects(item)
        modItem.amount -= 1
        return InstallResult.SUCCESS
    }

    fun tryRemove(item: ItemStack, slotIndex: Int): ItemStack? {
        val slots = readInstalledSlots(item).toMutableList()
        if (slotIndex !in 0 until 8) return null
        val id = slots[slotIndex] ?: return null
        slots[slotIndex] = null
        val meta = item.itemMeta
        writeInstalledSlots(meta, slots)
        item.itemMeta = meta
        reapplyModEffects(item)
        return createModItem(id, 1) ?: fallbackModItem(id)
    }

    private fun fallbackModItem(id: String): ItemStack {
        val item = ItemStack(Material.GRAY_DYE, 1)
        val meta = item.itemMeta
        meta.setDisplayName(color("&8未知 MOD: $id"))
        meta.persistentDataContainer.set(modIdKey, PersistentDataType.STRING, id)
        item.itemMeta = meta
        return item
    }

    fun reapplyModEffects(item: ItemStack) {
        val meta = item.itemMeta
        val pdc = meta.persistentDataContainer
        // 清掉所有 mod_delta_*
        for (key in modDeltaKeys.values) {
            pdc.remove(key)
        }
        val slots = readInstalledSlots(item)
        // 聚合各 affixId 的增量
        val deltaMap = HashMap<String, Double>()
        for (id in slots) {
            if (id == null) continue
            val mod = mods[id] ?: continue
            for ((affixId, value) in mod.effects) {
                if (affixId !in affixById) continue
                deltaMap[affixId] = (deltaMap[affixId] ?: 0.0) + value
            }
        }
        for ((affixId, delta) in deltaMap) {
            val key = modDeltaKeys[affixId] ?: continue
            pdc.set(key, PersistentDataType.DOUBLE, delta)
        }

        // 重建 lore：保留 marker 之前的内容
        val used = usedCapacity(item)
        // 容量直接通过 in-scope 的 pdc 读取/计算，使其与 lore 一起在末尾的单次 item.itemMeta = meta 中提交，
        // 避免调用 readCapacity(item) 时其内部的 item.itemMeta = meta2 被本方法稍后的 meta 提交覆盖丢失。
        val capacity = pdc.get(modCapacityKey, PersistentDataType.INTEGER) ?: run {
            val cat = plugin.itemService.weaponCategory(item) ?: "default"
            val tier = plugin.itemService.equipmentTier(item)
            val c = forgeConfig.modCapacity.computeCapacity(cat, tier)
            pdc.set(modCapacityKey, PersistentDataType.INTEGER, c)
            c
        }
        val existing = meta.lore ?: emptyList()
        val kept = mutableListOf<String>()
        for (line in existing) {
            if (line == marker) break
            kept += line
        }
        // 去掉 marker 前的尾随空行（避免反复叠加空行）
        while (kept.isNotEmpty() && kept.last().isBlank()) kept.removeAt(kept.size - 1)
        val rebuilt = kept.toMutableList()
        rebuilt += ""
        rebuilt += marker
        val remaining = (capacity - used).coerceAtLeast(0)
        val capColor = if (used > capacity) "&c" else "&a"
        rebuilt += color("&7剩余容量: $capColor$remaining&7/&f$capacity")
        meta.lore = rebuilt
        item.itemMeta = meta

        // 原版属性：护甲与生命/护盾
        val armorDelta = deltaMap.entries.sumOf { (affixId, value) ->
            if (affixById[affixId]?.combat == "armor") value else 0.0
        }
        val healthDelta = deltaMap.entries.sumOf { (affixId, value) ->
            val combat = affixById[affixId]?.combat
            if (combat == "health" || combat == "shield_capacity") value else 0.0
        }
        plugin.itemService.applyModVanillaAttributes(item, armorDelta, healthDelta)
    }

    private fun format(value: Double, decimals: Int): String {
        if (decimals <= 0) return value.toInt().toString()
        return DecimalFormat("0." + "0".repeat(decimals)).format(value)
    }
}
