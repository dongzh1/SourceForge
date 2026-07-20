package com.dongzh1.sourceforge.multiblock

import org.bukkit.configuration.file.FileConfiguration

/**
 * 源质锻炉多方块结构配置。直接读取主 config.yml 的 `forge-structure:` 段。
 *
 * 锻造时长实际公式在 ForgeMenuListener 提交作业时计算：ticks = round(配方秒数 * 20 / 外壳倍率)。
 * (2026-07-13 审查发现这里之前还有一套"tier-weight"公式(base-time-seconds/tier-weight.*)，声称是
 * "锁定设计"，但从未被任何代码调用过，纯属死代码——已删除。别再加回第二套时长公式，
 * 只应该有一个地方决定"锻造多久"。)
 */
data class ForgeStructureConfig(
    val enabled: Boolean,
    val coreBlockId: String,
    /** 外壳层级 -> CE 方块 id，例如 "iron" -> "sourceforge:forge_shell_iron" */
    val shellBlocks: Map<String, String>,
    /** 外壳层级 -> 速度倍率，例如 "iron" -> 1.0（越高，同样配方耗时越短）。 */
    val shellMultipliers: Map<String, Double>,
    val hammerId: String,
    val hologram: ForgeHologramConfig
) {
    /** CE 方块 id -> 层级名（反查，用于校验外壳一致性）。 */
    val shellIdToTier: Map<String, String> = shellBlocks.entries.associate { (tier, id) -> id to tier }

    /** 全部锻炉方块 id（核心 + 各层级外壳），用于"只能用源质锤潜行右键拆除"的破坏拦截。 */
    val forgeBlockIds: Set<String> = buildSet {
        add(coreBlockId)
        addAll(shellBlocks.values)
    }

    fun multiplierOf(tier: String): Double = shellMultipliers[tier] ?: 1.0

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
                coreBlockId = coreId,
                shellBlocks = shellBlocks,
                shellMultipliers = multipliers,
                hammerId = hammerId,
                hologram = ForgeHologramConfig.load(config, root)
            )
        }
    }
}
