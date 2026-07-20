package com.dongzh1.sourceforge.relic

import com.dongzh1.sourceforge.config.ForgeConfig
import com.dongzh1.sourceforge.mod.RivenConfig
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.random.Random

data class DreammarkRelicWeapon(
    val equipmentId: String,
    val weight: Double
)

data class DreammarkRelicAffix(
    val min: Double,
    val max: Double
) {
    fun roll(): Double = if (min == max) min else Random.nextDouble(min, max)
}

data class DreammarkRelicDefinition(
    val relicId: String,
    val unlockQuestId: String,
    val unveilQuestId: String?,
    val challengeId: String?,
    val weapons: List<DreammarkRelicWeapon>,
    val affixes: Map<String, DreammarkRelicAffix>,
    val baseDrain: Int,
    val drainPerRank: Int
) {
    fun rollWeaponId(): String? {
        val candidates = weapons.filter { it.weight > 0.0 }
        if (candidates.isEmpty()) return null
        val total = candidates.sumOf { it.weight }
        if (total <= 0.0) return candidates.random().equipmentId
        var roll = Random.nextDouble(total)
        for (entry in candidates) {
            roll -= entry.weight
            if (roll <= 0.0) return entry.equipmentId
        }
        return candidates.last().equipmentId
    }

    fun rollAffixes(): Map<String, Double> = affixes.mapValues { (_, range) -> range.roll() }
}

class DreammarkRelicRegistry private constructor(
    private val definitions: Map<String, DreammarkRelicDefinition>
) {
    fun get(relicId: String): DreammarkRelicDefinition? = definitions[relicId.lowercase()]

    fun ids(): Set<String> = definitions.keys

    companion object {
        fun empty(): DreammarkRelicRegistry = DreammarkRelicRegistry(emptyMap())

        fun load(
            folder: File,
            forgeConfig: ForgeConfig,
            rivenConfig: RivenConfig
        ): Pair<DreammarkRelicRegistry, List<String>> {
            val warnings = mutableListOf<String>()
            val definitions = linkedMapOf<String, DreammarkRelicDefinition>()
            if (!folder.isDirectory) return DreammarkRelicRegistry(definitions) to warnings

            folder.walkTopDown()
                .filter { it.isFile && it.extension.equals("yml", ignoreCase = true) }
                .sortedBy { it.relativeTo(folder).path }
                .forEach { file ->
                    val yaml = YamlConfiguration.loadConfiguration(file)
                    val label = file.relativeTo(folder).path
                    val relicId = yaml.getString("relic-id")?.trim()?.lowercase().orEmpty()
                    if (relicId.isBlank()) {
                        warnings += "遗纹遗物配置 $label 缺少 relic-id"
                        return@forEach
                    }
                    if (definitions.containsKey(relicId)) {
                        warnings += "遗纹遗物配置 $label 与已有 relic-id $relicId 重复"
                        return@forEach
                    }

                    val unlockQuestId = yaml.getString("tasks.unlock")?.trim().orEmpty()
                    if (unlockQuestId.isBlank()) {
                        warnings += "遗纹遗物配置 $label 缺少 tasks.unlock"
                        return@forEach
                    }
                    val unveilQuestId = yaml.getString("tasks.unveil")?.trim()?.takeIf { it.isNotBlank() }
                    val challengeId = yaml.getString("reward.challenge")?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                    if (challengeId != null && challengeId !in rivenConfig.challenges) {
                        warnings += "遗纹遗物配置 $label 引用了不存在的解封试炼 $challengeId"
                        return@forEach
                    }

                    val weapons = parseWeapons(yaml)
                    if (weapons.isEmpty()) {
                        warnings += "遗纹遗物配置 $label 未配置 reward.weapons"
                        return@forEach
                    }
                    for (weapon in weapons) {
                        val equipment = forgeConfig.equipment[weapon.equipmentId]
                        if (equipment == null) {
                            warnings += "遗纹遗物配置 $label 引用了不存在的装备 ${weapon.equipmentId}"
                            return@forEach
                        }
                        if (rivenConfig.resolveGroup(equipment.weaponCategory) == null) {
                            warnings += "遗纹遗物配置 $label 的装备 ${weapon.equipmentId} 没有匹配的遗纹分组"
                            return@forEach
                        }
                    }

                    val affixSection = yaml.getConfigurationSection("reward.affixes")
                    if (affixSection == null) {
                        warnings += "遗纹遗物配置 $label 缺少 reward.affixes"
                        return@forEach
                    }
                    val affixes = linkedMapOf<String, DreammarkRelicAffix>()
                    for (rawId in affixSection.getKeys(false)) {
                        val affixId = rawId.trim().lowercase()
                        val path = "reward.affixes.$rawId"
                        val range = parseAffixRange(yaml, path)
                        if (affixId !in forgeConfig.affixes || range == null) {
                            warnings += "遗纹遗物配置 $label 的词条 $rawId 无效"
                            return@forEach
                        }
                        if (range.min == 0.0 || range.max == 0.0 || (range.min < 0.0) != (range.max < 0.0)) {
                            warnings += "遗纹遗物配置 $label 的词条 $rawId 不能跨越或取到 0"
                            return@forEach
                        }
                        affixes[affixId] = range
                    }
                    val positiveCount = affixes.values.count { it.min > 0.0 }
                    val negativeCount = affixes.values.count { it.max < 0.0 }
                    if (positiveCount !in 1..3 || negativeCount > 1) {
                        warnings += "遗纹遗物配置 $label 必须配置 1-3 条正词条，且至多 1 条负词条"
                        return@forEach
                    }

                    val configuredBaseDrain = configuredInt(
                        yaml,
                        "reward.capacity.base",
                        "reward.capacity.base-drain",
                        "reward.mod.base-drain",
                        fallback = rivenConfig.baseDrain
                    )
                    val configuredDrainPerRank = configuredInt(
                        yaml,
                        "reward.capacity.per-rank",
                        "reward.capacity.drain-per-rank",
                        "reward.mod.drain-per-rank",
                        fallback = rivenConfig.drainPerRank
                    )
                    if (configuredBaseDrain < 1) warnings += "遗纹遗物配置 $label 的占用基值小于 1，已修正为 1"
                    if (configuredDrainPerRank < 0) warnings += "遗纹遗物配置 $label 的每段占用增量小于 0，已修正为 0"

                    definitions[relicId] = DreammarkRelicDefinition(
                        relicId = relicId,
                        unlockQuestId = unlockQuestId,
                        unveilQuestId = unveilQuestId,
                        challengeId = challengeId,
                        weapons = weapons,
                        affixes = affixes,
                        baseDrain = configuredBaseDrain.coerceAtLeast(1),
                        drainPerRank = configuredDrainPerRank.coerceAtLeast(0)
                    )
                }
            return DreammarkRelicRegistry(definitions) to warnings
        }

        private fun parseWeapons(yaml: YamlConfiguration): List<DreammarkRelicWeapon> {
            val result = linkedMapOf<String, Double>()
            val values = yaml.getList("reward.weapons").orEmpty().ifEmpty {
                listOfNotNull(yaml.getString("reward.weapon"))
            }
            for (value in values) {
                val (rawId, rawWeight) = when (value) {
                    is String -> value to 1.0
                    is Map<*, *> -> {
                        val id = value["id"] ?: value["equipment"] ?: continue
                        val weight = number(value["weight"]) ?: 1.0
                        id.toString() to weight
                    }
                    else -> continue
                }
                val equipmentId = rawId.trim().lowercase()
                if (equipmentId.isBlank() || !rawWeight.isFinite() || rawWeight <= 0.0) continue
                result[equipmentId] = (result[equipmentId] ?: 0.0) + rawWeight
            }
            return result.map { (equipmentId, weight) -> DreammarkRelicWeapon(equipmentId, weight) }
        }

        private fun parseAffixRange(yaml: YamlConfiguration, path: String): DreammarkRelicAffix? {
            val section = yaml.getConfigurationSection(path)
            val range = if (section == null) {
                val value = number(yaml.get(path)) ?: return null
                value to value
            } else {
                val fixed = number(section.get("value"))
                if (fixed != null) {
                    fixed to fixed
                } else {
                    val min = number(section.get("min")) ?: return null
                    val max = number(section.get("max")) ?: return null
                    min to max
                }
            }
            if (!range.first.isFinite() || !range.second.isFinite()) return null
            return DreammarkRelicAffix(minOf(range.first, range.second), maxOf(range.first, range.second))
        }

        private fun configuredInt(yaml: YamlConfiguration, vararg paths: String, fallback: Int): Int {
            for (path in paths) {
                if (yaml.isSet(path)) return yaml.getInt(path)
            }
            return fallback
        }

        private fun number(value: Any?): Double? = when (value) {
            is Number -> value.toDouble()
            else -> value?.toString()?.toDoubleOrNull()
        }
    }
}
