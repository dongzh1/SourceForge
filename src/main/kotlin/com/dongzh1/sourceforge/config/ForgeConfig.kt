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
    val itemDisplayNames: Map<String, String>,
    val equipment: Map<String, EquipmentConfig>,
    val affixes: Map<String, AffixConfig>,
    val recipes: Map<String, ForgeRecipe>,
    val modCapacity: ModCapacityConfig,
    val forgeUi: ForgeUiConfig,
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
            recipesFile: File? = null,
            combatConfig: YamlConfiguration = YamlConfiguration()
        ): ForgeConfig {
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
                    chunkWorldLevelMode = config.getString("$path.chunkworld-level", "tier")!!,
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

            val recipes = loadRecipes(recipesFile)

            return ForgeConfig(
                guiTitle = config.getString("gui.title", "&0源质锻造")!!,
                debugCombat = config.getBoolean("debug.combat", false),
                betterHud = BetterHudConfig(
                    enabled = config.getBoolean("betterhud.enabled", true),
                    skillCdPopup = config.getString("betterhud.skill-cd-popup", "sourceforge_skill_cd")!!,
                    navigatorPopup = config.getString("betterhud.navigator-popup", "sourceforge_navigator")!!,
                    debug = config.getBoolean("betterhud.debug", false)
                ),
                forge = loadForgeSystem(config),
                score = loadScore(config),
                combat = loadCombat(combatConfig),
                itemDisplayNames = itemDisplayNames,
                equipment = equipment,
                affixes = affixes,
                recipes = recipes,
                modCapacity = loadModCapacity(config),
                forgeUi = loadForgeUi(config),
                validationWarnings = validate(recipes, equipment, affixes)
            )
        }

        private fun loadRecipes(recipesFile: File?): Map<String, ForgeRecipe> {
            if (recipesFile == null || !recipesFile.isFile) return emptyMap()
            val yaml = YamlConfiguration.loadConfiguration(recipesFile)
            val section = yaml.getConfigurationSection("recipes") ?: return emptyMap()
            val result = linkedMapOf<String, ForgeRecipe>()
            for (blueprintId in section.getKeys(false)) {
                val path = "recipes.$blueprintId"
                val materials = yaml.getMapList("$path.materials").mapNotNull { map ->
                    val itemId = map["item"]?.toString() ?: return@mapNotNull null
                    RecipeMaterial(itemId, map["amount"]?.toString()?.toIntOrNull() ?: 1)
                }
                result[blueprintId] = ForgeRecipe(
                    blueprintId = blueprintId,
                    equipmentId = yaml.getString("$path.equipment", blueprintId)!!,
                    tier = yaml.getInt("$path.tier", 1).coerceAtLeast(1),
                    timeSeconds = yaml.getDouble("$path.time-seconds", 60.0).coerceAtLeast(0.0),
                    materials = materials
                )
            }
            return result
        }

        private fun loadForgeUi(config: FileConfiguration): ForgeUiConfig {
            val defaults = ForgeUiConfig()
            val section = config.getConfigurationSection("forge-ui") ?: return defaults
            val materialSlots = section.getIntegerList("material-slots")
                .takeIf { it.isNotEmpty() } ?: defaults.materialSlots
            return ForgeUiConfig(
                size = section.getInt("size", defaults.size),
                blueprintSlot = section.getInt("blueprint-slot", defaults.blueprintSlot),
                actionSlot = section.getInt("action-slot", defaults.actionSlot),
                outputSlot = section.getInt("output-slot", defaults.outputSlot),
                materialSlots = materialSlots,
                hammerMaterial = parseMaterial(section.getString("hammer-material"), defaults.hammerMaterial),
                arrowMaterial = parseMaterial(section.getString("arrow-material"), defaults.arrowMaterial),
                fillerMaterial = parseMaterial(section.getString("filler-material"), defaults.fillerMaterial)
            )
        }

        private fun loadModCapacity(config: FileConfiguration): ModCapacityConfig {
            val guiTitle = config.getString("mods.gui-title", "&0源质改造")!!
            val capacityByCategory = linkedMapOf<String, ModCapacityEntry>()
            config.getConfigurationSection("mods.capacity")?.getKeys(false)?.forEach { cat ->
                val base = "mods.capacity.$cat"
                capacityByCategory[cat.lowercase()] = ModCapacityEntry(
                    base = config.getInt("$base.base", 20),
                    tierIncrement = config.getInt("$base.tier-increment", 5)
                )
            }
            if ("default" !in capacityByCategory) {
                capacityByCategory["default"] = ModCapacityEntry(20, 5)
            }
            val maxModSlotsByCategory = linkedMapOf<String, Int>()
            config.getConfigurationSection("mods.max-mod-slots")?.getKeys(false)?.forEach { cat ->
                maxModSlotsByCategory[cat.lowercase()] = config.getInt("mods.max-mod-slots.$cat", 6)
            }
            if ("default" !in maxModSlotsByCategory) {
                maxModSlotsByCategory["default"] = 6
            }
            return ModCapacityConfig(guiTitle, capacityByCategory, maxModSlotsByCategory)
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
            recipes: Map<String, ForgeRecipe>,
            equipment: Map<String, EquipmentConfig>,
            affixes: Map<String, AffixConfig>
        ): List<String> {
            val warnings = mutableListOf<String>()
            for (recipe in recipes.values) {
                if (recipe.equipmentId !in equipment) {
                    warnings += "配方 ${recipe.blueprintId} 引用了不存在的装备 ${recipe.equipmentId}"
                }
                if (recipe.materials.isEmpty()) {
                    warnings += "配方 ${recipe.blueprintId} 没有配置 materials"
                }
                for (material in recipe.materials) {
                    if (material.amount <= 0) {
                        warnings += "配方 ${recipe.blueprintId} 材料 ${material.ceId} amount 必须大于 0"
                    }
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
    val navigatorPopup: String,
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

/** 锻造 GUI 布局配置（3 行 / 27 格箱子界面）。所有槽位可在 config.yml 的 forge-ui 段配置。 */
data class ForgeUiConfig(
    val size: Int = 27,
    val blueprintSlot: Int = 18,
    val actionSlot: Int = 19,
    val outputSlot: Int = 20,
    val materialSlots: List<Int> = listOf(4, 5, 6, 7, 13, 14, 15, 16, 22, 23, 24, 25),
    val hammerMaterial: Material = Material.IRON_AXE,
    val arrowMaterial: Material = Material.SPECTRAL_ARROW,
    val fillerMaterial: Material = Material.GRAY_STAINED_GLASS_PANE
)

/** 锻造配方。键 = 蓝图 CE 物品 id。 */
data class ForgeRecipe(
    val blueprintId: String,
    val equipmentId: String,
    val tier: Int,
    val timeSeconds: Double,
    val materials: List<RecipeMaterial>
)

/** 配方所需材料：CE 物品 id + 数量。 */
data class RecipeMaterial(
    val ceId: String,
    val amount: Int
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

data class ModCapacityEntry(val base: Int, val tierIncrement: Int)

data class ModCapacityConfig(
    val guiTitle: String,
    val capacityByCategory: Map<String, ModCapacityEntry>,
    val maxModSlotsByCategory: Map<String, Int>
) {
    fun computeCapacity(weaponCategory: String, tier: Int): Int {
        val e = capacityByCategory[weaponCategory.lowercase()]
            ?: capacityByCategory["default"]
            ?: ModCapacityEntry(20, 5)
        return e.base + (tier - 1).coerceAtLeast(0) * e.tierIncrement
    }

    fun computeMaxSlots(weaponCategory: String): Int =
        (maxModSlotsByCategory[weaponCategory.lowercase()] ?: maxModSlotsByCategory["default"] ?: 6).coerceIn(0, 8)
}
