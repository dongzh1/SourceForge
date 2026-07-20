package com.dongzh1.sourceforge.config

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.math.pow

/**
 * 单段强化所需花费 + 加成。levels[i] 表示升到 level i+1 的需求与收益。
 * 强化只花钱(Vault经济)不消耗材料(用户明确要求)——[cost] 单位是货币，不是 CE 材料数量。
 */
data class EnhanceLevel(
    val cost: Double,
    val baseDamage: Double,
    val modCapacity: Int,
    val shieldCapacity: Double = 0.0,
    val health: Double = 0.0
)

data class EnhanceCategory(
    val maxLevel: Int,
    val levels: List<EnhanceLevel>
)

data class EnhancementCostModel(
    val startCost: Double,
    val endCost: Double
) {
    fun costFor(index: Int, lastIndex: Int): Double {
        if (lastIndex <= 0) return endCost
        val progress = (index.toDouble() / lastIndex).coerceIn(0.0, 1.0)
        return startCost * (endCost / startCost).pow(progress)
    }
}

/**
 * MOD 段位升级花费曲线（enhancement.yml 的 mod-upgrade 段）。MOD 段位提升复用锻炉"强化"流程
 * （钱+时间，不耗材料，见 [EnhancementConfig.enhanceTimeSeconds]），不再走旧的"升级核心"实物消耗。
 * 单级花费 = baseCostPerSlotCost × 该MOD的cost(改造容量点数，越贵代表越强) ×
 * upgradeCostBase(该MOD自身稀有度系数) × levelMultiplier[rank]。
 */
data class ModUpgradeCurve(
    val baseCostPerSlotCost: Double,
    val levelMultiplier: List<Double>
) {
    /** rank -> rank+1 所需金币花费。rank 超出表长时沿用最后一档倍率。 */
    fun costFor(mod: com.dongzh1.sourceforge.mod.ModConfig, rank: Int): Double {
        val multiplier = levelMultiplier.getOrNull(rank) ?: levelMultiplier.lastOrNull() ?: 1.0
        return baseCostPerSlotCost * mod.cost * mod.upgradeCostBase * multiplier
    }
}

/**
 * 武器强化配置（enhancement.yml）。按武器 weaponCategory 查询；缺失时回退 default。
 */
data class EnhancementConfig(
    val enhanceTimeSeconds: Double,
    val categories: Map<String, EnhanceCategory>,
    val modUpgrade: ModUpgradeCurve = ModUpgradeCurve(8.0, listOf(1.0, 1.4, 2.0, 2.9, 4.2)),
    val weaponCostModel: EnhancementCostModel? = null
) {
    fun category(weaponCategory: String?): EnhanceCategory? {
        val key = weaponCategory?.lowercase()
        return (key?.let { categories[it] }) ?: categories["default"]
    }

    fun maxLevel(weaponCategory: String?): Int = category(weaponCategory)?.maxLevel ?: 0

    /** 从 currentLevel 升到 currentLevel+1 的需求；已满级或无配置返回 null。 */
    fun nextLevel(weaponCategory: String?, currentLevel: Int): EnhanceLevel? {
        val cat = category(weaponCategory) ?: return null
        if (currentLevel >= cat.maxLevel) return null
        return cat.levels.getOrNull(currentLevel)
    }

    fun isMaxLevel(weaponCategory: String?, currentLevel: Int): Boolean {
        val cat = category(weaponCategory) ?: return true
        return currentLevel >= cat.maxLevel
    }

    companion object {
        fun load(file: File): EnhancementConfig {
            if (!file.isFile) return EnhancementConfig(30.0, emptyMap())
            val yaml = YamlConfiguration.loadConfiguration(file)
            val time = yaml.getDouble("enhance-time-seconds", 30.0).coerceAtLeast(0.0)
            val weaponCostModel = yaml.getConfigurationSection("weapon-cost-model")?.let { section ->
                val type = section.getString("type", "")?.lowercase()
                val start = section.getDouble("start-cost", 0.0)
                val end = section.getDouble("end-cost", 0.0)
                if (type == "exponential" && start > 0.0 && end > 0.0) {
                    EnhancementCostModel(start, end)
                } else {
                    null
                }
            }
            val categories = linkedMapOf<String, EnhanceCategory>()
            yaml.getConfigurationSection("categories")?.getKeys(false)?.forEach { cat ->
                val path = "categories.$cat"
                val maxLevel = yaml.getInt("$path.max-level", 0).coerceAtLeast(0)
                val rawLevels = yaml.getMapList("$path.levels")
                val levels = rawLevels.mapIndexed { index, map ->
                    val cost = (map["cost"] as? Number)?.toDouble()
                        ?: map["cost"]?.toString()?.toDoubleOrNull() ?: 0.0
                    val modeledCost = weaponCostModel?.costFor(index, rawLevels.lastIndex) ?: cost
                    val baseDamage = (map["base-damage"] as? Number)?.toDouble()
                        ?: map["base-damage"]?.toString()?.toDoubleOrNull() ?: 0.0
                    val modCapacity = (map["mod-capacity"] as? Number)?.toInt()
                        ?: map["mod-capacity"]?.toString()?.toIntOrNull() ?: 0
                    val shieldCapacity = (map["shield-capacity"] as? Number)?.toDouble()
                        ?: map["shield-capacity"]?.toString()?.toDoubleOrNull() ?: 0.0
                    val health = (map["health"] as? Number)?.toDouble()
                        ?: map["health"]?.toString()?.toDoubleOrNull() ?: 0.0
                    EnhanceLevel(modeledCost, baseDamage, modCapacity, shieldCapacity, health)
                }
                categories[cat.lowercase()] = EnhanceCategory(maxLevel, levels)
            }
            val modUpgrade = ModUpgradeCurve(
                baseCostPerSlotCost = yaml.getDouble("mod-upgrade.base-cost-per-slot-cost", 8.0).coerceAtLeast(0.0),
                levelMultiplier = yaml.getDoubleList("mod-upgrade.level-multiplier")
                    .takeIf { it.isNotEmpty() } ?: listOf(1.0, 1.4, 2.0, 2.9, 4.2)
            )
            return EnhancementConfig(time, categories, modUpgrade, weaponCostModel)
        }
    }
}

/** 附魔台抽奖配置（config.yml lottery 段）。 */
data class LotteryConfig(
    val xpCost: Int,
    /** 打开抽奖界面所需的环绕书架数（类似原版顶级附魔台）。在附魔台周围 5×5 外圈(±2)上下两层统计 BOOKSHELF。 */
    val requiredBookshelves: Int
) {
    companion object {
        fun load(config: org.bukkit.configuration.file.FileConfiguration): LotteryConfig {
            return LotteryConfig(
                xpCost = config.getInt("lottery.xp-cost", 30).coerceAtLeast(0),
                requiredBookshelves = config.getInt("lottery.required-bookshelves", 16).coerceAtLeast(0)
            )
        }
    }
}
