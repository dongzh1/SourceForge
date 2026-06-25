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
            combatConfig: YamlConfiguration = YamlConfiguration(),
            forgeUiFile: File? = null
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
                forgeUi = loadForgeUi(forgeUiFile, config.getString("gui.title", "&0源质锻造")!!),
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

        /**
         * 从专用文件 forge_gui.yml 加载锻造界面布局（嵌套 schema）。
         * 文件不存在时返回全默认配置（含背景图标题）。
         * @param fallbackTitle config.yml 的 gui.title，仅作为 title 空串时的纯文字标题回退（不在此处替换，
         *        由 ForgeMenu 在渲染时决定；此处保留 title 原值）。
         */
        private fun loadForgeUi(file: File?, @Suppress("UNUSED_PARAMETER") fallbackTitle: String): ForgeUiConfig {
            val defaults = ForgeUiConfig()
            if (file == null || !file.isFile) return defaults
            val yaml = YamlConfiguration.loadConfiguration(file)

            val slots = yaml.getConfigurationSection("slots")
            val materialSlots = (slots?.getIntegerList("materials") ?: emptyList())
                .takeIf { it.isNotEmpty() } ?: defaults.materialSlots
            val barrierSlots = slots?.getIntegerList("barriers") ?: emptyList()

            // title: 缺省键时用代码默认(带背景图字形);显式写空串 "" 则视为无背景图。
            val title = if (yaml.isSet("title")) yaml.getString("title", "")!! else defaults.title

            return ForgeUiConfig(
                size = yaml.getInt("size", defaults.size),
                title = title,
                blueprintSlot = slots?.getInt("blueprint", defaults.blueprintSlot) ?: defaults.blueprintSlot,
                actionSlot = slots?.getInt("action", defaults.actionSlot) ?: defaults.actionSlot,
                outputSlot = slots?.getInt("output", defaults.outputSlot) ?: defaults.outputSlot,
                materialSlots = materialSlots,
                barrierSlots = barrierSlots,
                hammerButton = loadButton(yaml, "buttons.hammer", defaults.hammerButton),
                progressButton = loadButton(yaml, "buttons.progress", defaults.progressButton),
                collectButton = loadButton(yaml, "buttons.collect", defaults.collectButton),
                fillerMaterial = parseMaterial(yaml.getString("filler-material"), defaults.fillerMaterial)
            )
        }

        private fun loadButton(yaml: YamlConfiguration, path: String, defaults: ForgeButtonConfig): ForgeButtonConfig {
            val section = yaml.getConfigurationSection(path) ?: return defaults
            val lore = if (section.isSet("lore")) section.getStringList("lore") else defaults.lore
            return ForgeButtonConfig(
                material = parseMaterial(section.getString("material"), defaults.material),
                name = section.getString("name", defaults.name)!!,
                lore = lore
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

/**
 * 锻造 GUI 布局配置（默认 5 行 / 45 格箱子界面，带 CraftEngine 自定义背景图）。
 * 由专用文件 forge_gui.yml 加载（见 ForgeConfig.loadForgeUi(file, fallbackTitle)），
 * 不再从主 config.yml 的 forge-ui 段读取。
 *
 * 所有文本字段（title / 按钮 name / lore）支持完整 MiniMessage，含 CE 标签
 * （<image>/<shift>/<font>/<i18n>/<gradient> 等），并兼容传统 §/& 颜色码。
 *
 * title: 容器标题字符串。默认含 <shift> 偏移 + sourceforge:forge_gui 背景图字形，
 * 由 CraftEngine 完整解析器渲染为 forge.png 背景图。
 * 留空（""）时回退到 config.yml 的 gui.title 文字标题。
 *
 * 当 title 设置了背景图时，空槽不再填灰玻璃（否则会盖住背景图）；
 * 仅 barrierSlots 列出的槽放屏障锁死，其余非功能槽留空（空气）。
 */
data class ForgeUiConfig(
    val size: Int = 45,
    val title: String = "<shift:-8><image:sourceforge:forge_gui>",
    val blueprintSlot: Int = 20,
    val actionSlot: Int = 22,
    val outputSlot: Int = 24,
    val materialSlots: List<Int> = listOf(10, 11, 12, 13, 14, 15, 16),
    val barrierSlots: List<Int> = emptyList(),
    val hammerButton: ForgeButtonConfig = ForgeButtonConfig(
        material = Material.IRON_AXE,
        name = "<green>开始锻造",
        lore = listOf("<gray>放入蓝图开始锻造，或放入武器进行强化")
    ),
    val progressButton: ForgeButtonConfig = ForgeButtonConfig(
        material = Material.SPECTRAL_ARROW,
        name = "<yellow>锻造中…",
        lore = emptyList()
    ),
    val collectButton: ForgeButtonConfig = ForgeButtonConfig(
        material = Material.SPECTRAL_ARROW,
        name = "<green>点击收取",
        lore = emptyList()
    ),
    val fillerMaterial: Material = Material.GRAY_STAINED_GLASS_PANE
) {
    /** 标题非空白即视为使用了背景图标题（避免用玻璃盖住背景）。 */
    val hasBackground: Boolean get() = title.isNotBlank()
}

/** 动作按钮单态外观：物品 + 名称 + 基础 lore（运行时可追加动态行）。 */
data class ForgeButtonConfig(
    val material: Material,
    val name: String,
    val lore: List<String>
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
