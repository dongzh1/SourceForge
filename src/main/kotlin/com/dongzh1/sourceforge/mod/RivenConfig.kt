package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.config.AffixConfig
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

data class RivenAffixEntry(
    val affixId: String,
    val positive: Double,
    val negative: Double?,
    val positiveOnly: Boolean
) {
    val canBeNegative: Boolean get() = !positiveOnly && negative != null
}

data class RivenGroupConfig(
    val id: String,
    val displayName: String,
    val categories: Set<String>,
    val pool: Map<String, RivenAffixEntry>
)

data class RivenChallengeConfig(
    val id: String,
    val type: String,
    val goal: Int,
    val display: String
) {
    fun displayText(): String = display.replace("%goal%", goal.toString())
}

data class RivenConfig(
    val material: Material,
    val dreamCoreItemId: String,
    val maxRank: Int,
    val baseDrain: Int,
    val drainPerRank: Int,
    val rankUpgradeCosts: List<Double>,
    val positiveMin: Int,
    val positiveMax: Int,
    val negativeChance: Double,
    val rerollCosts: List<Int>,
    val groups: Map<String, RivenGroupConfig>,
    val challenges: Map<String, RivenChallengeConfig>,
    val categoryDispositions: Map<String, Double>,
    val equipmentDispositions: Map<String, Double>
) {
    fun resolveGroup(raw: String?): RivenGroupConfig? {
        val normalized = raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return groups.values.firstOrNull()
        return groups[normalized]
            ?: groups.values.firstOrNull { normalized == it.displayName.lowercase() }
            ?: groups.values.firstOrNull { normalized in it.categories }
    }

    fun dispositionFor(equipmentId: String, category: String?): Double {
        return equipmentDispositions[equipmentId.lowercase()]
            ?: category?.lowercase()?.let { categoryDispositions[it] }
            ?: 1.0
    }

    fun rerollCost(rolls: Int): Int = rerollCosts.getOrElse(rolls.coerceAtLeast(0)) { rerollCosts.lastOrNull() ?: 1 }

    fun rankUpgradeCost(rank: Int): Double = rankUpgradeCosts.getOrElse(rank.coerceAtLeast(0)) {
        rankUpgradeCosts.lastOrNull() ?: 0.0
    }

    companion object {
        private val supportedChallengeTypes = setOf("kill", "sneak_kill", "airborne_kill", "melee_kill", "ranged_kill")

        fun load(file: File, affixes: Map<String, AffixConfig>): Pair<RivenConfig, List<String>> {
            val warnings = mutableListOf<String>()
            val yaml = YamlConfiguration.loadConfiguration(file)
            val materialRaw = yaml.getString("material")
            val material = materialRaw?.let { Material.matchMaterial(it.substringAfter("minecraft:").uppercase()) } ?: Material.PAPER
            val maxRank = yaml.getInt("rank.max", 8).coerceIn(1, 20)
            val baseDrain = yaml.getInt("rank.base-drain", 10).coerceAtLeast(1)
            val drainPerRank = yaml.getInt("rank.drain-per-rank", 1).coerceAtLeast(0)
            val rankUpgradeCosts = yaml.getDoubleList("rank.upgrade-costs").ifEmpty { List(maxRank) { 100.0 + it * 50.0 } }
            val positiveMin = yaml.getInt("generation.positive-count.min", 2).coerceIn(1, 3)
            val positiveMax = yaml.getInt("generation.positive-count.max", 3).coerceIn(positiveMin, 3)
            val negativeChance = yaml.getDouble("generation.negative-chance", 0.5).coerceIn(0.0, 1.0)
            val rerollCosts = yaml.getIntegerList("reroll.costs").map { it.coerceAtLeast(1) }
                .ifEmpty { listOf(9, 10, 12, 14, 17, 20, 24, 28, 32, 35) }

            val groups = linkedMapOf<String, RivenGroupConfig>()
            yaml.getConfigurationSection("groups")?.getKeys(false)?.forEach { rawId ->
                val id = rawId.lowercase()
                val path = "groups.$rawId"
                val categories = yaml.getStringList("$path.categories").map { it.lowercase() }.filter { it.isNotBlank() }.toSet()
                val pool = linkedMapOf<String, RivenAffixEntry>()
                yaml.getConfigurationSection("$path.pool")?.getKeys(false)?.forEach { affixId ->
                    val affixPath = "$path.pool.$affixId"
                    if (affixId !in affixes) warnings += "彼端遗纹分组 $id 引用了不存在词条 $affixId"
                    val positive = yaml.getDouble("$affixPath.positive")
                    if (positive <= 0.0) warnings += "彼端遗纹分组 $id 的词条 $affixId 缺少正向数值"
                    val negative = if (yaml.isSet("$affixPath.negative")) yaml.getDouble("$affixPath.negative").coerceAtLeast(0.0) else null
                    pool[affixId] = RivenAffixEntry(
                        affixId = affixId,
                        positive = positive.coerceAtLeast(0.0),
                        negative = negative,
                        positiveOnly = yaml.getBoolean("$affixPath.positive-only", false)
                    )
                }
                if (categories.isEmpty()) warnings += "彼端遗纹分组 $id 未配置适用装备类别"
                if (pool.size < positiveMin) warnings += "彼端遗纹分组 $id 的词条池不足以生成正词条"
                groups[id] = RivenGroupConfig(
                    id = id,
                    displayName = yaml.getString("$path.display-name") ?: id,
                    categories = categories,
                    pool = pool
                )
            }
            if (groups.isEmpty()) warnings += "彼端遗纹未配置任何分组"

            val challenges = linkedMapOf<String, RivenChallengeConfig>()
            yaml.getConfigurationSection("challenges")?.getKeys(false)?.forEach { rawId ->
                val id = rawId.lowercase()
                val path = "challenges.$rawId"
                val type = (yaml.getString("$path.type") ?: "kill").lowercase()
                if (type !in supportedChallengeTypes) warnings += "彼端遗纹试炼 $id 使用了未知类型 $type"
                challenges[id] = RivenChallengeConfig(
                    id = id,
                    type = type,
                    goal = yaml.getInt("$path.goal", 1).coerceAtLeast(1),
                    display = yaml.getString("$path.display") ?: "击杀 %goal% 名敌人"
                )
            }
            if (challenges.isEmpty()) warnings += "彼端遗纹未配置任何解封试炼"

            val categoryDispositions = linkedMapOf<String, Double>()
            yaml.getConfigurationSection("disposition.by-category")?.getKeys(false)?.forEach { category ->
                categoryDispositions[category.lowercase()] = yaml.getDouble("disposition.by-category.$category", 1.0).coerceAtLeast(0.1)
            }
            val equipmentDispositions = linkedMapOf<String, Double>()
            yaml.getConfigurationSection("disposition.by-equipment")?.getKeys(false)?.forEach { equipmentId ->
                equipmentDispositions[equipmentId.lowercase()] = yaml.getDouble("disposition.by-equipment.$equipmentId", 1.0).coerceAtLeast(0.1)
            }

            return RivenConfig(
                material = material,
                dreamCoreItemId = yaml.getString("reroll.dream-core-item", "sourceforge:dream_core") ?: "sourceforge:dream_core",
                maxRank = maxRank,
                baseDrain = baseDrain,
                drainPerRank = drainPerRank,
                rankUpgradeCosts = rankUpgradeCosts,
                positiveMin = positiveMin,
                positiveMax = positiveMax,
                negativeChance = negativeChance,
                rerollCosts = rerollCosts,
                groups = groups,
                challenges = challenges,
                categoryDispositions = categoryDispositions,
                equipmentDispositions = equipmentDispositions
            ) to warnings
        }
    }
}
