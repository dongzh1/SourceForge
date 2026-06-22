package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.config.AffixConfig
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/** 单个词条在某武器类别下可 roll 的区间。neg-min/neg-max 存在则允许 roll 为负属性。 */
data class NightmareAffixEntry(
    val affixId: String,
    val min: Double,
    val max: Double,
    val negMin: Double?,
    val negMax: Double?
) {
    val negativeAllowed: Boolean get() = negMin != null && negMax != null
}

data class NightmareConfig(
    val material: Material,
    val goalKills: Int,
    val baseCost: Int,
    val negativeChance: Double,
    val negativeCostReduction: Int,
    val positiveMin: Int,
    val positiveMax: Int,
    /** category(lowercase) -> (affixId -> entry) */
    val pools: Map<String, Map<String, NightmareAffixEntry>>
) {
    fun categories(): Set<String> = pools.keys
    fun pool(category: String?): Map<String, NightmareAffixEntry>? =
        category?.let { pools[it.lowercase()] }

    companion object {
        fun load(file: File, affixes: Map<String, AffixConfig>): Pair<NightmareConfig, List<String>> {
            val warnings = mutableListOf<String>()
            val config = YamlConfiguration.loadConfiguration(file)
            val materialRaw = config.getString("material")
            val material = materialRaw?.let {
                Material.matchMaterial(it.substringAfter("minecraft:").uppercase())
            } ?: Material.PAPER
            val goalKills = config.getInt("goal-kills", 50).coerceAtLeast(1)
            val baseCost = config.getInt("base-cost", 18)
            val negativeChance = config.getDouble("negative-chance", 0.5)
            val negativeCostReduction = config.getInt("negative-cost-reduction", 6)
            val positiveMin = config.getInt("positive-count.min", 2).coerceAtLeast(1)
            val positiveMax = config.getInt("positive-count.max", 3).coerceAtLeast(positiveMin)

            val pools = linkedMapOf<String, Map<String, NightmareAffixEntry>>()
            val poolsSection = config.getConfigurationSection("pools")
            poolsSection?.getKeys(false)?.forEach { category ->
                val catSection = config.getConfigurationSection("pools.$category") ?: return@forEach
                val entries = linkedMapOf<String, NightmareAffixEntry>()
                catSection.getKeys(false).forEach { affixId ->
                    val base = "pools.$category.$affixId"
                    if (affixId !in affixes) {
                        warnings += "梦魇MOD 类别 $category 词条池引用了不存在的词条 $affixId"
                    }
                    val negMin = if (config.isSet("$base.neg-min")) config.getDouble("$base.neg-min") else null
                    val negMax = if (config.isSet("$base.neg-max")) config.getDouble("$base.neg-max") else null
                    entries[affixId] = NightmareAffixEntry(
                        affixId = affixId,
                        min = config.getDouble("$base.min"),
                        max = config.getDouble("$base.max"),
                        negMin = negMin,
                        negMax = negMax
                    )
                }
                if (entries.isEmpty()) {
                    warnings += "梦魇MOD 类别 $category 词条池为空"
                }
                pools[category.lowercase()] = entries
            }
            if (pools.isEmpty()) {
                warnings += "梦魇MOD 未配置任何武器类别词条池 (pools)"
            }
            return NightmareConfig(
                material = material,
                goalKills = goalKills,
                baseCost = baseCost,
                negativeChance = negativeChance,
                negativeCostReduction = negativeCostReduction,
                positiveMin = positiveMin,
                positiveMax = positiveMax,
                pools = pools
            ) to warnings
        }
    }
}
