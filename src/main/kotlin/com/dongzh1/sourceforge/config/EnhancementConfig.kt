package com.dongzh1.sourceforge.config

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/** 单段强化所需材料 + 加成。levels[i] 表示升到 level i+1 的需求与收益。 */
data class EnhanceLevel(
    val materials: List<RecipeMaterial>,
    val baseDamage: Double,
    val modCapacity: Int
)

data class EnhanceCategory(
    val maxLevel: Int,
    val levels: List<EnhanceLevel>
)

/**
 * 武器强化配置（enhancement.yml）。按武器 weaponCategory 查询；缺失时回退 default。
 */
data class EnhancementConfig(
    val enhanceTimeSeconds: Double,
    val categories: Map<String, EnhanceCategory>
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
            val categories = linkedMapOf<String, EnhanceCategory>()
            yaml.getConfigurationSection("categories")?.getKeys(false)?.forEach { cat ->
                val path = "categories.$cat"
                val maxLevel = yaml.getInt("$path.max-level", 0).coerceAtLeast(0)
                val levels = yaml.getMapList("$path.levels").map { map ->
                    @Suppress("UNCHECKED_CAST")
                    val matsRaw = (map["materials"] as? List<Map<*, *>>) ?: emptyList()
                    val mats = matsRaw.mapNotNull { m ->
                        val item = m["item"]?.toString() ?: return@mapNotNull null
                        val amount = (m["amount"] as? Number)?.toInt() ?: m["amount"]?.toString()?.toIntOrNull() ?: 1
                        RecipeMaterial(item, amount)
                    }
                    val baseDamage = (map["base-damage"] as? Number)?.toDouble()
                        ?: map["base-damage"]?.toString()?.toDoubleOrNull() ?: 0.0
                    val modCapacity = (map["mod-capacity"] as? Number)?.toInt()
                        ?: map["mod-capacity"]?.toString()?.toIntOrNull() ?: 0
                    EnhanceLevel(mats, baseDamage, modCapacity)
                }
                categories[cat.lowercase()] = EnhanceCategory(maxLevel, levels)
            }
            return EnhancementConfig(time, categories)
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
