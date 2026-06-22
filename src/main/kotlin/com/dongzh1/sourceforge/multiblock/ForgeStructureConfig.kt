package com.dongzh1.sourceforge.multiblock

import org.bukkit.configuration.file.FileConfiguration

/**
 * 源质锻炉多方块结构配置。直接读取主 config.yml 的 `forge-structure:` 段，
 * 不掺入巨大的 ForgeConfig 数据类，保持 MVP 独立。
 *
 * 锻造时长公式（锁定设计）：
 *   ticks = round(baseTimeSeconds * (tier*tierWeightPerTier + tierWeightBase) / shellMultiplier * 20)
 */
data class ForgeStructureConfig(
    val enabled: Boolean,
    val baseTimeSeconds: Double,
    val tierWeightBase: Double,
    val tierWeightPerTier: Double,
    val coreBlockId: String,
    /** 外壳层级 -> CE 方块 id，例如 "iron" -> "sourceforge:forge_shell_iron" */
    val shellBlocks: Map<String, String>,
    /** 外壳层级 -> 速度倍率，例如 "iron" -> 1.0 */
    val shellMultipliers: Map<String, Double>,
    val hammerId: String
) {
    /** CE 方块 id -> 层级名（反查，用于校验外壳一致性）。 */
    val shellIdToTier: Map<String, String> = shellBlocks.entries.associate { (tier, id) -> id to tier }

    /** 全部锻炉方块 id（核心 + 各层级外壳），用于“只能用源质锤潜行右键拆除”的破坏拦截。 */
    val forgeBlockIds: Set<String> = buildSet {
        add(coreBlockId)
        addAll(shellBlocks.values)
    }

    /** 层级名 -> 在公式里使用的层级序号（1 起）。顺序由 shellBlocks 声明顺序决定。 */
    val tierIndex: Map<String, Int> = shellBlocks.keys.withIndex().associate { (i, tier) -> tier to (i + 1) }

    fun multiplierOf(tier: String): Double = shellMultipliers[tier] ?: 1.0

    fun durationTicks(tier: String): Long {
        val tierNo = tierIndex[tier] ?: 1
        val mult = multiplierOf(tier)
        val seconds = baseTimeSeconds * (tierNo * tierWeightPerTier + tierWeightBase) / mult
        return Math.round(seconds * 20.0).coerceAtLeast(1L)
    }

    fun tierDisplay(tier: String): String = when (tier) {
        "iron" -> "铁"
        "gold" -> "金"
        "diamond" -> "钻石"
        "netherite" -> "下界合金"
        else -> tier
    }

    companion object {
        fun load(config: FileConfiguration): ForgeStructureConfig {
            val root = "forge-structure"
            val enabled = config.getBoolean("$root.enabled", true)
            val baseTime = config.getDouble("$root.base-time-seconds", 10.0)
            val tierBase = config.getDouble("$root.tier-weight.base", 0.5)
            val tierPer = config.getDouble("$root.tier-weight.per-tier", 0.5)
            val coreId = config.getString("$root.core-block", "sourceforge:forge_core")!!

            val shellBlocks = linkedMapOf<String, String>()
            val shellSection = config.getConfigurationSection("$root.shell-blocks")
            if (shellSection != null) {
                for (tier in shellSection.getKeys(false)) {
                    config.getString("$root.shell-blocks.$tier")?.let { shellBlocks[tier] = it }
                }
            }
            if (shellBlocks.isEmpty()) {
                shellBlocks["iron"] = "sourceforge:forge_shell_iron"
                shellBlocks["gold"] = "sourceforge:forge_shell_gold"
                shellBlocks["diamond"] = "sourceforge:forge_shell_diamond"
                shellBlocks["netherite"] = "sourceforge:forge_shell_netherite"
            }

            val multipliers = linkedMapOf<String, Double>()
            val multSection = config.getConfigurationSection("$root.shell-multipliers")
            if (multSection != null) {
                for (tier in multSection.getKeys(false)) {
                    multipliers[tier] = config.getDouble("$root.shell-multipliers.$tier", 1.0)
                }
            }
            if (multipliers.isEmpty()) {
                multipliers["iron"] = 1.0
                multipliers["gold"] = 1.5
                multipliers["diamond"] = 2.0
                multipliers["netherite"] = 3.0
            }

            val hammerId = config.getString("$root.hammer", "sourceforge:forge_hammer")!!

            return ForgeStructureConfig(
                enabled = enabled,
                baseTimeSeconds = baseTime,
                tierWeightBase = tierBase,
                tierWeightPerTier = tierPer,
                coreBlockId = coreId,
                shellBlocks = shellBlocks,
                shellMultipliers = multipliers,
                hammerId = hammerId
            )
        }
    }
}
