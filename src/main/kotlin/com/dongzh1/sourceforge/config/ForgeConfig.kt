package com.dongzh1.sourceforge.config
import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

data class ForgeConfig(
    val guiTitle: String,
    val debugCombat: Boolean,
    val betterHud: BetterHudConfig,
    val forge: ForgeSystemConfig,
    val score: ScoreConfig,
    val combat: CombatConfig,
    val blueprintItem: BlueprintItemConfig,
    val itemDisplayNames: Map<String, String>,
    val blueprints: Map<String, BlueprintConfig>,
    val equipment: Map<String, EquipmentConfig>,
    val affixes: Map<String, AffixConfig>,
    val forgeMaterials: List<ForgeMaterialConfig>,
    val validationWarnings: List<String>
) {
    fun displayName(id: String): String {
        val normalized = id.trim().lowercase()
        itemDisplayNames[normalized]?.let { return it }
        itemDisplayNames[normalized.removePrefix("minecraft:")]?.let { return it }
        return if (normalized.startsWith("minecraft:")) {
            normalized.substringAfter("minecraft:").uppercase()
        } else {
            normalized
        }
    }

    fun equipmentDisplayName(id: String): String {
        return equipment[id]?.displayName ?: id
    }

    companion object {
        fun load(
            config: FileConfiguration,
            affixesConfig: YamlConfiguration = YamlConfiguration(),
            materialsFolder: File? = null,
            combatConfig: YamlConfiguration = YamlConfiguration()
        ): ForgeConfig {
            val blueprintItem = BlueprintItemConfig(
                material = parseMaterial(config.getString("blueprint-item.material"), Material.PAPER),
                ceId = config.getString("blueprint-item.ce-id")?.takeIf { it.isNotBlank() },
                customModelData = config.getInt("blueprint-item.custom-model-data", 0).takeIf { it > 0 },
                nameFormat = config.getString("blueprint-item.name-format", "&9蓝图: %name%")!!,
                lore = config.getStringList("blueprint-item.lore")
            )

            val itemDisplayNames = linkedMapOf<String, String>()
            config.getConfigurationSection("item-display-names")?.getKeys(false)?.forEach { id ->
                config.getString("item-display-names.$id")?.let { itemDisplayNames[id.lowercase()] = it }
            }

            val affixes = linkedMapOf<String, AffixConfig>()
            config.getConfigurationSection("affixes")?.getKeys(false)?.forEach { id ->
                val path = "affixes.$id"
                affixes[id] = loadAffix(config, path, id)
            }
            affixesConfig.getConfigurationSection("affixes")?.getKeys(false)?.forEach { id ->
                val path = "affixes.$id"
                affixes[id] = loadAffix(affixesConfig, path, id)
            }

            val equipment = linkedMapOf<String, EquipmentConfig>()
            config.getConfigurationSection("equipment")?.getKeys(false)?.forEach { id ->
                val path = "equipment.$id"
                equipment[id] = EquipmentConfig(
                    id = id,
                    displayName = config.getString("$path.display-name", id)!!,
                    material = parseMaterial(config.getString("$path.material"), Material.IRON_SWORD),
                    ceId = config.getString("$path.ce-id")?.takeIf { it.isNotBlank() },
                    weaponCategory = config.getString("$path.weapon-category", defaultWeaponCategory(id, config.getString("$path.material")))!!.lowercase(),
                    chunkWorldLevelMode = config.getString("$path.chunkworld-level", "blueprint-tier")!!,
                    pixelShopPrice = config.getDouble("$path.pixelshop-price", 0.0),
                    effectiveSlots = config.getStringList("$path.effective-slots")
                        .ifEmpty { defaultEffectiveSlots(id, config.getString("$path.weapon-category", defaultWeaponCategory(id, config.getString("$path.material")))!!.lowercase()) }
                        .map { it.lowercase() }
                        .toSet(),
                    baseLore = config.getStringList("$path.base-lore"),
                    affixIds = config.getStringList("$path.affixes").ifEmpty { affixes.keys.toList() },
                    tierAffixes = loadTierAffixes(config, "$path.tier-affixes")
                )
            }

            val blueprints = linkedMapOf<String, BlueprintConfig>()
            config.getConfigurationSection("blueprints")?.getKeys(false)?.forEach { id ->
                val path = "blueprints.$id"
                val requirements = config.getMapList("$path.requirements").mapNotNull { map ->
                    val itemId = map["id"]?.toString() ?: return@mapNotNull null
                    MaterialRequirement(itemId, map["amount"]?.toString()?.toIntOrNull() ?: 1)
                }
                blueprints[id] = BlueprintConfig(
                    id = id,
                    displayName = config.getString("$path.display-name", id)!!,
                    equipmentType = config.getString("$path.equipment-type", id)!!,
                    tierMin = config.getInt("$path.tier.min", 1).coerceAtLeast(1),
                    tierMax = config.getInt("$path.tier.max", 1).coerceAtLeast(1),
                    fixedTier = config.getInt("$path.tier.fixed", 0).takeIf { it > 0 },
                    affixSlotsMin = config.getInt("$path.affix-slots.min", 1).coerceAtLeast(0),
                    affixSlotsMax = config.getInt("$path.affix-slots.max", 1).coerceAtLeast(0),
                    maxAffixes = config.getInt("$path.max-affixes", config.getInt("$path.affix-slots.max", 1)).coerceAtLeast(0),
                    requirements = requirements
                )
            }

            val forgeMaterials = loadForgeMaterials(materialsFolder)

            return ForgeConfig(
                guiTitle = config.getString("gui.title", "&0源质锻造")!!,
                debugCombat = config.getBoolean("debug.combat", false),
                betterHud = BetterHudConfig(
                    enabled = config.getBoolean("betterhud.enabled", true),
                    skillCdPopup = config.getString("betterhud.skill-cd-popup", "sourceforge_skill_cd")!!,
                    debug = config.getBoolean("betterhud.debug", false)
                ),
                forge = loadForgeSystem(config),
                score = loadScore(config),
                combat = loadCombat(combatConfig),
                blueprintItem = blueprintItem,
                itemDisplayNames = itemDisplayNames,
                blueprints = blueprints,
                equipment = equipment,
                affixes = affixes,
                forgeMaterials = forgeMaterials,
                validationWarnings = validate(blueprints, equipment, affixes, forgeMaterials)
            )
        }

        private fun parseMaterial(raw: String?, fallback: Material): Material {
            if (raw.isNullOrBlank()) return fallback
            return Material.matchMaterial(raw.substringAfter("minecraft:", raw).uppercase()) ?: fallback
        }

        private fun loadAffix(config: FileConfiguration, path: String, id: String): AffixConfig {
            return AffixConfig(
                id = id,
                displayName = config.getString("$path.display-name", id)!!,
                pdcKey = config.getString("$path.pdc-key", id)!!,
                valueType = config.getString("$path.value-type", "double")!!.lowercase(),
                min = config.getDouble("$path.min", 0.0),
                max = config.getDouble("$path.max", 0.0),
                decimals = config.getInt("$path.decimals", 1).coerceAtLeast(0),
                combat = config.getString("$path.combat", id)!!.lowercase(),
                scale = config.getDouble("$path.scale", 1.0),
                lore = config.getString("$path.lore", "&7%name% +%value%")!!
            )
        }

        private fun defaultWeaponCategory(id: String, material: String?): String {
            val normalizedId = id.lowercase()
            val normalizedMaterial = material?.substringAfter("minecraft:", material)?.lowercase().orEmpty()
            return when {
                "flintlock" in normalizedId || "gun" in normalizedId -> "firearm"
                "crossbow" in normalizedId || normalizedMaterial == "crossbow" -> "crossbow"
                "bow" in normalizedId || normalizedMaterial == "bow" -> "bow"
                "dagger" in normalizedId || "short" in normalizedId -> "melee_light"
                "long" in normalizedId || "great" in normalizedId -> "melee_heavy"
                "spear" in normalizedId || normalizedMaterial == "trident" -> "polearm"
                else -> "melee"
            }
        }

        private fun defaultEffectiveSlots(id: String, weaponCategory: String): List<String> {
            if (!weaponCategory.startsWith("armor_")) return listOf("mainhand")
            val normalized = id.lowercase()
            return when {
                "helmet" in normalized -> listOf("head")
                "chestplate" in normalized -> listOf("chest")
                "leggings" in normalized -> listOf("legs")
                "boots" in normalized -> listOf("feet")
                else -> listOf("armor")
            }
        }

        private fun loadTierAffixes(config: FileConfiguration, path: String): Map<Int, List<AffixRollConfig>> {
            val result = linkedMapOf<Int, List<AffixRollConfig>>()
            config.getConfigurationSection(path)?.getKeys(false)?.forEach { tierKey ->
                val tier = tierKey.toIntOrNull() ?: return@forEach
                result[tier] = loadRolls(config, "$path.$tierKey")
            }
            return result
        }

        private fun loadForgeMaterials(folder: File?): List<ForgeMaterialConfig> {
            if (folder == null || !folder.isDirectory) return emptyList()
            return folder.listFiles { file -> file.isFile && file.extension.lowercase() in setOf("yml", "yaml") }
                ?.sortedBy { it.name }
                ?.mapNotNull { file ->
                    val config = YamlConfiguration.loadConfiguration(file)
                    val id = config.getString("id") ?: file.nameWithoutExtension
                    val itemId = config.getString("item") ?: config.getString("item-id") ?: return@mapNotNull null
                    ForgeMaterialConfig(
                        id = id,
                        displayName = config.getString("display-name", id)!!,
                        itemId = itemId,
                        amount = config.getInt("amount", 1).coerceAtLeast(1),
                        affixes = loadRolls(config, "affixes")
                    )
                }
                ?: emptyList()
        }

        private fun loadRolls(config: FileConfiguration, path: String): List<AffixRollConfig> {
            val section = config.getConfigurationSection(path) ?: return emptyList()
            return section.getKeys(false).mapNotNull { affixId ->
                val base = "$path.$affixId"
                AffixRollConfig(
                    affixId = affixId,
                    chance = config.getDouble("$base.chance", 1.0).coerceAtLeast(0.0),
                    min = config.getDouble("$base.min", 0.0),
                    max = config.getDouble("$base.max", 0.0)
                )
            }
        }

        private fun loadForgeSystem(config: FileConfiguration): ForgeSystemConfig {
            return ForgeSystemConfig(
                guaranteeMaterialAffix = config.getBoolean("forge.guarantee-material-affix", false)
            )
        }

        private fun loadScore(config: FileConfiguration): ScoreConfig {
            return ScoreConfig(
                basePerTier = config.getDouble("score.base-per-tier", 100.0),
                minScore = config.getInt("score.min-score", 1).coerceAtLeast(1),
                priceMultiplierBase = config.getDouble("score.price.multiplier-base", 0.5),
                priceScoreDivisor = config.getDouble("score.price.score-divisor", 250.0).takeIf { it > 0.0 } ?: 250.0,
                minPrice = config.getDouble("score.price.min-price", 1.0).coerceAtLeast(0.0),
                combatWeights = loadScoreWeights(config, "score.combat-weights"),
                affixWeights = loadScoreWeights(config, "score.affix-weights")
            )
        }

        private fun loadScoreWeights(config: FileConfiguration, path: String): Map<String, Double> {
            val section = config.getConfigurationSection(path) ?: return emptyMap()
            return section.getKeys(false).associate { key ->
                key.lowercase() to config.getDouble("$path.$key", 0.0)
            }
        }

        private fun loadCombat(config: FileConfiguration): CombatConfig {
            return CombatConfig(
                defenseFloor = config.getDouble("defense-floor", 0.10).coerceIn(0.0, 1.0)
            )
        }

        private fun validate(
            blueprints: Map<String, BlueprintConfig>,
            equipment: Map<String, EquipmentConfig>,
            affixes: Map<String, AffixConfig>,
            forgeMaterials: List<ForgeMaterialConfig>
        ): List<String> {
            val warnings = mutableListOf<String>()
            for (blueprint in blueprints.values) {
                if (blueprint.equipmentType !in equipment) {
                    warnings += "蓝图 ${blueprint.id} 引用了不存在的装备 ${blueprint.equipmentType}"
                }
                if (blueprint.requirements.isEmpty()) {
                    warnings += "蓝图 ${blueprint.id} 没有配置 requirements"
                }
            }
            for (item in equipment.values) {
                for (affixId in item.affixIds) {
                    if (affixId !in affixes) {
                        warnings += "装备 ${item.id} affixes 引用了不存在的词条 $affixId"
                    }
                }
                for ((tier, rolls) in item.tierAffixes) {
                    if (tier <= 0) warnings += "装备 ${item.id} tier-affixes 使用了非法等级 $tier"
                    validateRolls("装备 ${item.id} 等级 $tier", rolls, affixes, warnings)
                }
            }
            for (material in forgeMaterials) {
                if (material.amount <= 0) {
                    warnings += "材料 ${material.id} amount 必须大于 0"
                }
                validateRolls("材料 ${material.id}", material.affixes, affixes, warnings)
            }
            return warnings
        }

        private fun validateRolls(
            owner: String,
            rolls: List<AffixRollConfig>,
            affixes: Map<String, AffixConfig>,
            warnings: MutableList<String>
        ) {
            for (roll in rolls) {
                val affix = affixes[roll.affixId]
                if (affix == null) {
                    warnings += "$owner 引用了不存在的词条 ${roll.affixId}"
                    continue
                }
                if (roll.chance <= 0.0) warnings += "$owner 词条 ${roll.affixId} chance <= 0，不会出现"
                if (roll.max > 0.0 && roll.max < roll.min) warnings += "$owner 词条 ${roll.affixId} max 小于 min"
            }
        }
    }
}

data class ForgeSystemConfig(
    val guaranteeMaterialAffix: Boolean
)

data class BetterHudConfig(
    val enabled: Boolean,
    val skillCdPopup: String,
    val debug: Boolean
)

data class ScoreConfig(
    val basePerTier: Double,
    val minScore: Int,
    val priceMultiplierBase: Double,
    val priceScoreDivisor: Double,
    val minPrice: Double,
    val combatWeights: Map<String, Double>,
    val affixWeights: Map<String, Double>
)

data class BlueprintItemConfig(
    val material: Material,
    val ceId: String?,
    val customModelData: Int?,
    val nameFormat: String,
    val lore: List<String>
)

data class BlueprintConfig(
    val id: String,
    val displayName: String,
    val equipmentType: String,
    val tierMin: Int,
    val tierMax: Int,
    val fixedTier: Int?,
    val affixSlotsMin: Int,
    val affixSlotsMax: Int,
    val maxAffixes: Int,
    val requirements: List<MaterialRequirement>
) {
    fun defaultTier(): Int = fixedTier ?: tierMin
    fun defaultTierRange(): IntRange {
        val fixed = fixedTier
        if (fixed != null) return fixed..fixed
        val min = minOf(tierMin, tierMax)
        val max = maxOf(tierMin, tierMax)
        return min..max
    }
}

data class EquipmentConfig(
    val id: String,
    val displayName: String,
    val material: Material,
    val ceId: String?,
    val weaponCategory: String,
    val chunkWorldLevelMode: String,
    val pixelShopPrice: Double,
    val effectiveSlots: Set<String>,
    val baseLore: List<String>,
    val affixIds: List<String>,
    val tierAffixes: Map<Int, List<AffixRollConfig>>
)

data class MaterialRequirement(
    val id: String,
    val amount: Int
)

data class ForgeMaterialConfig(
    val id: String,
    val displayName: String,
    val itemId: String,
    val amount: Int,
    val affixes: List<AffixRollConfig>
)

data class AffixRollConfig(
    val affixId: String,
    val chance: Double,
    val min: Double,
    val max: Double
)

data class AffixConfig(
    val id: String,
    val displayName: String,
    val pdcKey: String,
    val valueType: String,
    val min: Double,
    val max: Double,
    val decimals: Int,
    val combat: String,
    val scale: Double,
    val lore: String
)

data class CombatConfig(
    val defenseFloor: Double
)
