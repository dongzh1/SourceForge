package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.config.AffixConfig
import com.dongzh1.sourceforge.util.Text
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import java.text.DecimalFormat
import kotlin.random.Random

/** 已鉴定梦魇MOD的一次随机实例。 */
data class NightmareInstance(
    val cost: Int,
    val category: String,
    val affixes: Map<String, Double>
)

class NightmareService(
    private val plugin: SourceForge,
    private val config: NightmareConfig,
    private val affixById: Map<String, AffixConfig>
) {
    private val nmKey = NamespacedKey(plugin, "nm")
    private val nmStateKey = NamespacedKey(plugin, "nm_state")
    private val nmCategoryKey = NamespacedKey(plugin, "nm_category")
    private val nmProgressKey = NamespacedKey(plugin, "nm_progress")
    private val nmGoalKey = NamespacedKey(plugin, "nm_goal")
    private val nmDataKey = NamespacedKey(plugin, "nm_data")

    companion object {
        const val STATE_SEALED = "sealed"
        const val STATE_UNVEILED = "unveiled"

        /** 类别中文名（找不到则用原名）。 */
        private val CATEGORY_NAMES = mapOf(
            "melee" to "近战",
            "melee_light" to "轻近战",
            "melee_heavy" to "重近战",
            "polearm" to "长柄",
            "bow" to "弓",
            "crossbow" to "弩",
            "firearm" to "火器"
        )

        private val PERCENT_AFFIXES = setOf(
            "critical_chance", "critical_damage", "status_chance",
            "ability_strength", "ability_duration", "ability_efficiency", "ability_range"
        )
    }

    fun categories(): Set<String> = config.categories()

    fun isNightmare(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return false
        return item.itemMeta.persistentDataContainer.has(nmKey, PersistentDataType.BYTE)
    }

    fun state(item: ItemStack?): String? {
        if (!isNightmare(item)) return null
        return item!!.itemMeta.persistentDataContainer.get(nmStateKey, PersistentDataType.STRING)
    }

    fun category(item: ItemStack?): String? {
        if (item == null || !item.hasItemMeta()) return null
        return item.itemMeta.persistentDataContainer.get(nmCategoryKey, PersistentDataType.STRING)
    }

    fun parseData(item: ItemStack?): NightmareInstance? {
        if (!isNightmare(item)) return null
        val raw = item!!.itemMeta.persistentDataContainer.get(nmDataKey, PersistentDataType.STRING) ?: return null
        return parseDataString(raw)
    }

    /** nm_data 格式: cost=<int>|cat=<category>|<affixId>:<double>,<affixId>:<double>,... */
    fun parseDataString(raw: String): NightmareInstance? {
        val parts = raw.split("|")
        var cost = config.baseCost
        var category = ""
        val affixes = linkedMapOf<String, Double>()
        for (part in parts) {
            when {
                part.startsWith("cost=") -> cost = part.removePrefix("cost=").toIntOrNull() ?: cost
                part.startsWith("cat=") -> category = part.removePrefix("cat=")
                part.isBlank() -> {}
                else -> {
                    for (pair in part.split(",")) {
                        if (pair.isBlank()) continue
                        val idx = pair.lastIndexOf(':')
                        if (idx <= 0) continue
                        val id = pair.substring(0, idx)
                        val value = pair.substring(idx + 1).toDoubleOrNull() ?: continue
                        affixes[id] = value
                    }
                }
            }
        }
        return NightmareInstance(cost, category, affixes)
    }

    fun serialize(instance: NightmareInstance): String {
        val affixPart = instance.affixes.entries.joinToString(",") { (id, v) -> "$id:$v" }
        return "cost=${instance.cost}|cat=${instance.category}|$affixPart"
    }

    fun createSealed(category: String? = null, amount: Int = 1): ItemStack {
        val cat = (category?.lowercase()?.takeIf { it in config.categories() })
            ?: config.categories().randomOrNull()
            ?: "melee"
        val item = ItemStack(config.material, amount.coerceAtLeast(1))
        val meta = item.itemMeta
        Text.name(meta, "&5梦魇MOD &8· 未鉴定")
        val pdc = meta.persistentDataContainer
        pdc.set(nmKey, PersistentDataType.BYTE, 1)
        pdc.set(nmStateKey, PersistentDataType.STRING, STATE_SEALED)
        pdc.set(nmCategoryKey, PersistentDataType.STRING, cat)
        pdc.set(nmProgressKey, PersistentDataType.INTEGER, 0)
        pdc.set(nmGoalKey, PersistentDataType.INTEGER, config.goalKills)
        item.itemMeta = meta
        renderLore(item)
        return item
    }

    /** 玩家每次击杀怪物，推进其背包中所有未鉴定梦魇MOD的进度。 */
    fun addKillProgress(player: Player) {
        val storage = player.inventory.storageContents
        for (i in storage.indices) {
            val item = storage[i] ?: continue
            if (!isNightmare(item)) continue
            if (state(item) != STATE_SEALED) continue
            val meta = item.itemMeta
            val pdc = meta.persistentDataContainer
            val goal = pdc.get(nmGoalKey, PersistentDataType.INTEGER) ?: config.goalKills
            val progress = (pdc.get(nmProgressKey, PersistentDataType.INTEGER) ?: 0) + 1
            if (progress >= goal) {
                item.itemMeta = meta
                unveil(item)
                player.sendMessage("§5[梦魇MOD] §f揭示完成！")
            } else {
                pdc.set(nmProgressKey, PersistentDataType.INTEGER, progress)
                item.itemMeta = meta
                renderLore(item)
            }
            // storageContents 返回的是引用副本，回写以确保实际背包物品被更新。
            player.inventory.setItem(i, item)
        }
    }

    /** 鉴定：按 nm_category 词条池 roll，写入 nm_data，重渲 lore。 */
    fun unveil(item: ItemStack) {
        if (!isNightmare(item)) return
        val cat = category(item) ?: config.categories().firstOrNull() ?: "melee"
        val pool = config.pool(cat)?.values?.toList() ?: emptyList()
        val affixes = linkedMapOf<String, Double>()
        var hasNegative = false

        if (pool.isNotEmpty()) {
            val positiveCount = Random.nextInt(config.positiveMin, config.positiveMax + 1)
                .coerceAtMost(pool.size)
            val shuffled = pool.shuffled()
            val chosen = shuffled.take(positiveCount)
            for (entry in chosen) {
                affixes[entry.affixId] = rollPositive(entry)
            }
            // 负属性：从池中"负属性可用"且未被选用的词条里挑 1 个；若没有则尝试将已选正属性翻负。
            if (Random.nextDouble() < config.negativeChance) {
                val usedIds = affixes.keys
                val negCandidate = shuffled.firstOrNull { it.negativeAllowed && it.affixId !in usedIds }
                if (negCandidate != null) {
                    affixes[negCandidate.affixId] = rollNegative(negCandidate)
                    hasNegative = true
                } else {
                    val flip = chosen.firstOrNull { it.negativeAllowed }
                    if (flip != null) {
                        affixes[flip.affixId] = rollNegative(flip)
                        hasNegative = true
                    }
                }
            }
        }

        var cost = config.baseCost
        if (hasNegative) cost -= config.negativeCostReduction
        cost = cost.coerceAtLeast(1)

        val instance = NightmareInstance(cost, cat, affixes)
        val meta = item.itemMeta
        val pdc = meta.persistentDataContainer
        pdc.set(nmStateKey, PersistentDataType.STRING, STATE_UNVEILED)
        pdc.set(nmDataKey, PersistentDataType.STRING, serialize(instance))
        pdc.remove(nmProgressKey)
        pdc.remove(nmGoalKey)
        item.itemMeta = meta
        renderLore(item)
    }

    private fun rollPositive(entry: NightmareAffixEntry): Double {
        val scale = affixById[entry.affixId]?.scale ?: 1.0
        return randInRange(entry.min, entry.max) * scale
    }

    private fun rollNegative(entry: NightmareAffixEntry): Double {
        val scale = affixById[entry.affixId]?.scale ?: 1.0
        val nMin = entry.negMin ?: entry.min
        val nMax = entry.negMax ?: entry.max
        return -randInRange(nMin, nMax) * scale
    }

    private fun randInRange(min: Double, max: Double): Double {
        if (max <= min) return min
        return min + Random.nextDouble() * (max - min)
    }

    /** 由实例数据重建一个已鉴定梦魇MOD物品（从槽位取出时使用）。 */
    fun buildFromData(data: NightmareInstance): ItemStack {
        val item = ItemStack(config.material, 1)
        val meta = item.itemMeta
        Text.name(meta, "&5梦魇MOD")
        val pdc = meta.persistentDataContainer
        pdc.set(nmKey, PersistentDataType.BYTE, 1)
        pdc.set(nmStateKey, PersistentDataType.STRING, STATE_UNVEILED)
        pdc.set(nmCategoryKey, PersistentDataType.STRING, data.category)
        pdc.set(nmDataKey, PersistentDataType.STRING, serialize(data))
        item.itemMeta = meta
        renderLore(item)
        return item
    }

    fun renderLore(item: ItemStack) {
        val meta = item.itemMeta
        if (state(item) == STATE_UNVEILED) {
            renderUnveiledLore(meta, item)
        } else {
            renderSealedLore(meta)
        }
        item.itemMeta = meta
    }

    private fun renderSealedLore(meta: ItemMeta) {
        val pdc = meta.persistentDataContainer
        val cat = pdc.get(nmCategoryKey, PersistentDataType.STRING) ?: "melee"
        val progress = pdc.get(nmProgressKey, PersistentDataType.INTEGER) ?: 0
        val goal = pdc.get(nmGoalKey, PersistentDataType.INTEGER) ?: config.goalKills
        Text.name(meta, "&5梦魇MOD &8· 未鉴定")
        Text.lore(
            meta,
            listOf(
                "&5梦魇MOD &8· 未鉴定",
                "&7适用: ${categoryName(cat)}",
                "&7揭示进度: &f$progress&7/&f$goal",
                "&8击杀怪物推进揭示"
            )
        )
    }

    private fun renderUnveiledLore(meta: ItemMeta, item: ItemStack) {
        val raw = meta.persistentDataContainer.get(nmDataKey, PersistentDataType.STRING)
        val instance = raw?.let { parseDataString(it) } ?: NightmareInstance(config.baseCost, category(item) ?: "melee", emptyMap())
        Text.name(meta, "&5梦魇MOD")
        val lines = mutableListOf(
            "&5梦魇MOD",
            "&7容量消耗: &e${instance.cost}",
            ""
        )
        for ((affixId, value) in instance.affixes) {
            lines += affixLine(affixId, value)
        }
        lines += ""
        lines += "&7适用: ${categoryName(instance.category)}"
        Text.lore(meta, lines)
    }

    private fun affixLine(affixId: String, value: Double): String {
        val affix = affixById[affixId]
        val name = affix?.displayName ?: affixId
        val decimals = affix?.decimals ?: 2
        val positive = value >= 0
        val formatted = formatValue(affixId, kotlin.math.abs(value), decimals)
        return if (positive) "&a$name +$formatted" else "&c$name -$formatted"
    }

    private fun formatValue(affixId: String, absValue: Double, decimals: Int): String {
        if (affixId in PERCENT_AFFIXES || affixId.startsWith("ability_")) {
            return "${DecimalFormat("0.##").format(absValue * 100.0)}%"
        }
        if (decimals <= 0) return absValue.toInt().toString()
        return DecimalFormat("0.${"0".repeat(decimals)}").format(absValue)
    }

    private fun categoryName(cat: String): String =
        CATEGORY_NAMES[cat.lowercase()] ?: cat
}
