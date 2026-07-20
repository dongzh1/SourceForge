package com.dongzh1.sourceforge.config
import com.dongzh1.sourceforge.enchant.EnchantBridgeConfig
import com.dongzh1.sourceforge.item.CraftEngineHook
import net.kyori.adventure.text.Component
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
    val equipment: Map<String, EquipmentConfig>,
    val equipmentTiers: EquipmentTierConfig,
    val affixes: Map<String, AffixConfig>,
    val modCapacity: ModCapacityConfig,
    val forgeUi: ForgeUiConfig,
    val enchantBridge: EnchantBridgeConfig,
    val validationWarnings: List<String>
) {
    /**
     * 材料/物品的"裸名"(无强制颜色)Component，聊天提示与GUI图标命名统一走这个口子：
     * 1. CE 自定义物品直接读它自己配置的 data.item_name(CraftEngine 已经解析好的 Component)；
     * 2. 否则原版物品用 minecraft 翻译键(item./block. 由 Material.isBlock 判断)交给客户端按自己的
     *    语言/字体渲染——服务端不需要、也不应该为每个原版材料硬编码中文名。
     * 3. 都对不上(比如 CE 未启用又不是合法原版 id)才退回 id 原始字符串。
     */
    fun bareNameComponent(id: String): Component {
        val normalized = id.trim().lowercase()
        CraftEngineHook.build(id, 1)?.itemMeta?.displayName()?.let { return it }

        val vanillaPath = normalized.substringAfter(':')
        val material = Material.matchMaterial(normalized)
        if (material != null) {
            val prefix = if (material.isBlock) "block" else "item"
            return Component.translatable("$prefix.minecraft.$vanillaPath")
        }
        return Component.text(id)
    }

    fun equipmentDisplayName(id: String): String {
        return equipment[id]?.displayName ?: id
    }

    companion object {
        fun load(
            config: FileConfiguration,
            affixesConfig: YamlConfiguration = YamlConfiguration(),
            combatConfig: YamlConfiguration = YamlConfiguration(),
            forgeUiFile: File? = null,
            enchantsConfig: YamlConfiguration = YamlConfiguration(),
            equipmentFolder: File? = null
        ): ForgeConfig {
            val affixes = linkedMapOf<String, AffixConfig>()
            config.getConfigurationSection("affixes")?.getKeys(false)?.forEach { id ->
                val path = "affixes.$id"
                affixes[id] = loadAffix(config, path, id)
            }
            affixesConfig.getConfigurationSection("affixes")?.getKeys(false)?.forEach { id ->
                val path = "affixes.$id"
                affixes[id] = loadAffix(affixesConfig, path, id)
            }

            // 装备定义已从 config.yml 的 equipment: 段迁移到独立的 equipment/ 文件夹
            // （支持任意深度中文子文件夹分类），见 EquipmentRegistry。
            val (equipment, equipmentWarnings) = EquipmentRegistry.load(equipmentFolder, affixes)
            // 装备品阶(强化花费倍率分级，见 EquipmentTierConfig)：同样放在 equipment/ 文件夹下，
            // 用保留文件名 equipment-tiers.yml，EquipmentRegistry 已知要跳过它、不当装备定义解析。
            val equipmentTiers = EquipmentTierConfig.load(
                equipmentFolder?.let { File(it, EquipmentTierConfig.FILE_NAME) },
                equipment
            )
            val forgeUi = loadForgeUi(forgeUiFile, config.getString("gui.title", "&0源质锻造")!!)

            return ForgeConfig(
                guiTitle = config.getString("gui.title", "&0源质锻造")!!,
                debugCombat = config.getBoolean("debug.combat", false),
                betterHud = BetterHudConfig(
                    enabled = config.getBoolean("betterhud.enabled", true),
                    skillCdPopup = config.getString("betterhud.skill-cd-popup", "sourceforge_skill_cd")!!,
                    navigatorPopup = config.getString("betterhud.navigator-popup", "sourceforge_navigator")!!,
                    taskTrackerPopup = config.getString("betterhud.task-tracker-popup", "sourceforge_task_tracker")!!,
                    debug = config.getBoolean("betterhud.debug", false)
                ),
                forge = loadForgeSystem(config),
                score = loadScore(config),
                combat = loadCombat(combatConfig),
                equipment = equipment,
                equipmentTiers = equipmentTiers,
                affixes = affixes,
                modCapacity = loadModCapacity(config),
                forgeUi = forgeUi,
                enchantBridge = EnchantBridgeConfig.load(enchantsConfig),
                validationWarnings = equipmentWarnings + validate(equipment, affixes) + validateForgeUiSlots(forgeUi)
            )
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

            return ForgeUiConfig(
                hammerButton = loadButton(yaml, "buttons.hammer", defaults.hammerButton),
                progressButton = loadButton(yaml, "buttons.progress", defaults.progressButton),
                collectButton = loadButton(yaml, "buttons.collect", defaults.collectButton),
                fillerMaterial = parseMaterial(yaml.getString("filler-material"), defaults.fillerMaterial),
                craft = loadCraftUi(yaml, defaults.craft),
                enhance = loadEnhanceUi(yaml, defaults.enhance),
                upgrade = loadUpgradeUi(yaml, defaults.upgrade)
            )
        }

        /** title: section 内缺省键时用代码默认;显式写空串 "" 则视为无背景图(当前项目实际生效的分支)。 */
        private fun sectionTitle(section: org.bukkit.configuration.ConfigurationSection, defaults: String): String =
            if (section.isSet("title")) section.getString("title", "")!! else defaults

        private fun loadCraftUi(yaml: YamlConfiguration, defaults: ForgeCraftUiConfig): ForgeCraftUiConfig {
            val section = yaml.getConfigurationSection("craft") ?: return defaults
            val slots = section.getConfigurationSection("slots")
            val materialSlots = (slots?.getIntegerList("materials") ?: emptyList())
                .takeIf { it.isNotEmpty() } ?: defaults.materialSlots
            return ForgeCraftUiConfig(
                size = section.getInt("size", defaults.size),
                title = sectionTitle(section, defaults.title),
                blueprintSlot = slots?.getInt("blueprint", defaults.blueprintSlot) ?: defaults.blueprintSlot,
                actionSlot = slots?.getInt("action", defaults.actionSlot) ?: defaults.actionSlot,
                outputSlot = slots?.getInt("output", defaults.outputSlot) ?: defaults.outputSlot,
                modeToggleSlot = slots?.getInt("mode-toggle", defaults.modeToggleSlot) ?: defaults.modeToggleSlot,
                materialSlots = materialSlots,
                barrierSlots = slots?.getIntegerList("barriers") ?: emptyList()
            )
        }

        private fun loadEnhanceUi(yaml: YamlConfiguration, defaults: ForgeEnhanceUiConfig): ForgeEnhanceUiConfig {
            val section = yaml.getConfigurationSection("enhance") ?: return defaults
            val slots = section.getConfigurationSection("slots")
            return ForgeEnhanceUiConfig(
                size = section.getInt("size", defaults.size),
                title = sectionTitle(section, defaults.title),
                weaponSlot = slots?.getInt("weapon", defaults.weaponSlot) ?: defaults.weaponSlot,
                actionSlot = slots?.getInt("action", defaults.actionSlot) ?: defaults.actionSlot,
                outputSlot = slots?.getInt("output", defaults.outputSlot) ?: defaults.outputSlot,
                modeToggleSlot = slots?.getInt("mode-toggle", defaults.modeToggleSlot) ?: defaults.modeToggleSlot,
                barrierSlots = slots?.getIntegerList("barriers") ?: emptyList()
            )
        }

        private fun loadUpgradeUi(yaml: YamlConfiguration, defaults: ForgeUpgradeUiConfig): ForgeUpgradeUiConfig {
            val section = yaml.getConfigurationSection("upgrade") ?: return defaults
            val slots = section.getConfigurationSection("slots")
            val materialSlots = (slots?.getIntegerList("materials") ?: emptyList())
                .takeIf { it.isNotEmpty() } ?: defaults.materialSlots
            return ForgeUpgradeUiConfig(
                size = section.getInt("size", defaults.size),
                title = sectionTitle(section, defaults.title),
                blueprintSlot = slots?.getInt("blueprint", defaults.blueprintSlot) ?: defaults.blueprintSlot,
                upgradeWeaponSlot = slots?.getInt("upgrade-weapon", defaults.upgradeWeaponSlot) ?: defaults.upgradeWeaponSlot,
                actionSlot = slots?.getInt("action", defaults.actionSlot) ?: defaults.actionSlot,
                outputSlot = slots?.getInt("output", defaults.outputSlot) ?: defaults.outputSlot,
                modeToggleSlot = slots?.getInt("mode-toggle", defaults.modeToggleSlot) ?: defaults.modeToggleSlot,
                materialSlots = materialSlots,
                barrierSlots = slots?.getIntegerList("barriers") ?: emptyList()
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

        internal fun parseMaterial(raw: String?, fallback: Material): Material {
            if (raw.isNullOrBlank()) return fallback
            return Material.matchMaterial(raw.substringAfter("minecraft:", raw).uppercase()) ?: fallback
        }

        /** 比例型词条(MOD 卡上默认按百分比显示)：yml 可用 percent: true/false 覆盖。 */
        private val DEFAULT_PERCENT_AFFIXES = setOf(
            "critical_chance", "critical_damage", "status_chance", "summon_damage"
        )

        /** 词条在 MOD 卡上的数值颜色默认值(元素按元素色)：yml 可用 color 覆盖。 */
        private val DEFAULT_AFFIX_COLORS = mapOf(
            "heat_damage" to "&c", "cold_damage" to "&b",
            "toxin_damage" to "&a", "electric_damage" to "&e"
        )

        private fun loadAffix(config: FileConfiguration, path: String, id: String): AffixConfig {
            return AffixConfig(
                id = id,
                displayName = config.getString("$path.display-name", id)!!,
                pdcKey = config.getString("$path.pdc-key", id)!!,
                valueType = config.getString("$path.value-type", "double")!!.lowercase(),
                decimals = config.getInt("$path.decimals", 1).coerceAtLeast(0),
                combat = config.getString("$path.combat", id)!!.lowercase(),
                scale = config.getDouble("$path.scale", 1.0),
                lore = config.getString("$path.lore", "&7%name% +%value%")!!,
                percent = config.getBoolean(
                    "$path.percent",
                    id in DEFAULT_PERCENT_AFFIXES || id.startsWith("ability_")
                ),
                color = config.getString("$path.color", DEFAULT_AFFIX_COLORS[id] ?: "&f")!!
            )
        }

        internal fun defaultWeaponCategory(id: String, material: String?): String {
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

        internal fun defaultEffectiveSlots(id: String, weaponCategory: String): List<String> {
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

        internal fun loadTierAffixes(config: FileConfiguration, path: String): Map<Int, List<AffixRollConfig>> {
            val result = linkedMapOf<Int, List<AffixRollConfig>>()
            config.getConfigurationSection(path)?.getKeys(false)?.forEach { tierKey ->
                val tier = tierKey.toIntOrNull() ?: return@forEach
                result[tier] = loadRolls(config, "$path.$tierKey")
            }
            return result
        }

        /**
         * 词条现在是写死的固定值，不再是 {chance,min,max} 随机区间：
         * `tier-affixes.<tier>.<affixId>` 直接是一个裸数字。
         * 如果读到的不是数字（说明还是老结构没迁移），用 NaN 占位——
         * [validateRolls] 会据此报出具体是哪个装备/等级/词条，[ForgeItemService] 会安全跳过而不是写入 NaN。
         */
        private fun loadRolls(config: FileConfiguration, path: String): List<AffixRollConfig> {
            val section = config.getConfigurationSection(path) ?: return emptyList()
            return section.getKeys(false).map { affixId ->
                val raw = config.get("$path.$affixId")
                val value = (raw as? Number)?.toDouble() ?: Double.NaN
                AffixRollConfig(affixId = affixId, value = value)
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

        /**
         * 锻造GUI槽位重叠校验(经济向重复物品漏洞防线)。2026-07-16 重构：锻造/强化/重铸已改成三个
         * 完全独立的 Inventory(见 ForgeMenu/ForgeCraftMenu/ForgeEnhanceMenu/ForgeUpgradeMenu)，
         * 不同模式之间的槽位数字重叠不再是问题(各自是不同的 Inventory 对象)；但同一模式自己内部
         * 的功能槽(蓝图/武器/动作/产出/模式切换)仍必须互不相同、不能落在自己的 materials 展示槽里，
         * 否则重演旧版"模式切换按钮被当成玩家真实物品在关闭界面时送出"那类重复物品漏洞。
         */
        private fun validateForgeUiSlots(ui: ForgeUiConfig): List<String> {
            val warnings = mutableListOf<String>()
            warnings += validateSlotSection(
                "craft",
                listOf(
                    "blueprint" to ui.craft.blueprintSlot,
                    "action" to ui.craft.actionSlot,
                    "output" to ui.craft.outputSlot,
                    "mode-toggle" to ui.craft.modeToggleSlot
                ),
                ui.craft.materialSlots
            )
            warnings += validateSlotSection(
                "enhance",
                listOf(
                    "weapon" to ui.enhance.weaponSlot,
                    "action" to ui.enhance.actionSlot,
                    "output" to ui.enhance.outputSlot,
                    "mode-toggle" to ui.enhance.modeToggleSlot
                ),
                emptyList()
            )
            warnings += validateSlotSection(
                "upgrade",
                listOf(
                    "blueprint" to ui.upgrade.blueprintSlot,
                    "upgrade-weapon" to ui.upgrade.upgradeWeaponSlot,
                    "action" to ui.upgrade.actionSlot,
                    "output" to ui.upgrade.outputSlot,
                    "mode-toggle" to ui.upgrade.modeToggleSlot
                ),
                ui.upgrade.materialSlots
            )
            return warnings
        }

        private fun validateSlotSection(section: String, named: List<Pair<String, Int>>, materialSlots: List<Int>): List<String> {
            val warnings = mutableListOf<String>()
            for (i in named.indices) {
                for (j in i + 1 until named.size) {
                    val (nameA, slotA) = named[i]
                    val (nameB, slotB) = named[j]
                    if (slotA == slotB) {
                        warnings += "锻造GUI[$section]槽位冲突: $nameA 与 $nameB 都指向槽位 $slotA" +
                            "（同一界面内两个功能槽重叠，属重复物品漏洞风险，请检查 forge_gui.yml）"
                    }
                }
            }
            for ((name, slot) in named) {
                if (slot in materialSlots) {
                    warnings += "锻造GUI[$section]槽位冲突: $name(槽位 $slot) 与 materials 展示槽重叠，请检查 forge_gui.yml"
                }
            }
            return warnings
        }

        private fun validate(
            equipment: Map<String, EquipmentConfig>,
            affixes: Map<String, AffixConfig>
        ): List<String> {
            val warnings = mutableListOf<String>()
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
                if (roll.value.isNaN()) {
                    warnings += "$owner 词条 ${roll.affixId} 不是固定数值（可能还是旧的 {chance,min,max} 结构没迁移），锻造时会跳过这条词条"
                }
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
    val taskTrackerPopup: String,
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
    val tierAffixes: Map<Int, List<AffixRollConfig>>,
    /** 是否允许铁砧完全自由操作(改名/修复/合并/附魔书附魔，走原版流程，不受 SourceEnchantListener
     * 的白名单限制)。CE 战斗装备分类下的装备默认应该是 true——见 equipment 目录下 yml 里的 free-anvil-edit
     * 字段与用户要求(2026-07-13)。非白名单附魔/诅咒仍会被 ForgeItemService.stripVanillaEnchantments
     * 在战斗结算前清掉，所以这里放开铁砧本身是安全的。 */
    val freeAnvilEdit: Boolean = false
)

/**
 * 锻造 GUI 布局配置。2026-07-16 重构：锻造/强化/重铸三态从"共用一个 Inventory、按 mode 解释
 * 同一批槽位的含义"改成三个完全独立的界面各自持有独立的 Inventory 与槽位布局(见 [craft]/[enhance]/
 * [upgrade]，以及 ForgeCraftMenu/ForgeEnhanceMenu/ForgeUpgradeMenu)——不同模式的槽位号即使数值
 * 相同也毫无关系，从根上消除"某个模式的功能槽跟另一模式的功能槽撞在同一槽位号"这一类重复物品漏洞
 * (历史事故：blueprint 与 mode-toggle 曾经共享同一个 Inventory、都指向槽位 20)。点击模式切换按钮时
 * 关闭当前界面、打开另一模式对应的全新 Inventory(见 ForgeMenus)。
 * 由专用文件 forge_gui.yml 加载（见 ForgeConfig.loadForgeUi），按钮外观(hammer/progress/collect)
 * 与填充材质三态共用，槽位布局在各自的 craft:/enhance:/upgrade: 段独立配置。
 *
 * 所有文本字段（title / 按钮 name / lore）支持完整 MiniMessage，含 CE 标签
 * （<image>/<shift>/<font>/<i18n>/<gradient> 等），并兼容传统 §/& 颜色码。
 */
data class ForgeUiConfig(
    val hammerButton: ForgeButtonConfig = ForgeButtonConfig(
        material = Material.IRON_AXE,
        name = "<green>开始锻造",
        lore = listOf("<gray>放入蓝图开始锻造")
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
    val fillerMaterial: Material = Material.GRAY_STAINED_GLASS_PANE,
    val craft: ForgeCraftUiConfig = ForgeCraftUiConfig(),
    val enhance: ForgeEnhanceUiConfig = ForgeEnhanceUiConfig(),
    val upgrade: ForgeUpgradeUiConfig = ForgeUpgradeUiConfig()
)

/** 锻造模式独立界面：蓝图槽 + 材料展示 + 产出预览 + 动作/模式切换按钮。 */
data class ForgeCraftUiConfig(
    val size: Int = 45,
    /** 留空（""）时回退到 config.yml 的 gui.title 文字标题；当前项目暂时禁用背景图(见 forge_gui.yml)。 */
    val title: String = "",
    val blueprintSlot: Int = 20,
    val actionSlot: Int = 22,
    val outputSlot: Int = 24,
    /** 锻造/强化/重铸 三态切换按钮槽位(用户明确要求显式切换，不再靠"往蓝图槽塞什么"反推模式)。 */
    val modeToggleSlot: Int = 23,
    val materialSlots: List<Int> = listOf(10, 11, 12, 13, 14, 15, 16),
    val barrierSlots: List<Int> = emptyList()
) {
    val hasBackground: Boolean get() = title.isNotBlank()
}

/** 强化模式独立界面：用户明确要求"强化只花钱不消耗材料"，所以只留一个武器槽——不留材料展示槽，
 * 也不需要蓝图槽——外加产出(强化后)预览 + 动作/模式切换按钮。 */
data class ForgeEnhanceUiConfig(
    val size: Int = 45,
    val title: String = "",
    val weaponSlot: Int = 20,
    val actionSlot: Int = 22,
    val outputSlot: Int = 24,
    val modeToggleSlot: Int = 23,
    val barrierSlots: List<Int> = emptyList()
) {
    val hasBackground: Boolean get() = title.isNotBlank()
}

/** 重铸模式独立界面：蓝图槽 + 待重铸武器槽 + 材料展示 + 产出预览 + 动作/模式切换按钮。 */
data class ForgeUpgradeUiConfig(
    val size: Int = 45,
    val title: String = "",
    val blueprintSlot: Int = 20,
    /** 待重铸武器槽——蓝图槽已被蓝图占用，装不下第二件东西。 */
    val upgradeWeaponSlot: Int = 19,
    val actionSlot: Int = 22,
    val outputSlot: Int = 24,
    val modeToggleSlot: Int = 23,
    val materialSlots: List<Int> = listOf(10, 11, 12, 13, 14, 15, 16),
    val barrierSlots: List<Int> = emptyList()
) {
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
    val materials: List<RecipeMaterial>,
    /** CREATE：从零锻造新装备（默认）。UPGRADE：蓝图原地重铸——把玩家主手武器换成 equipmentId/tier 的外观与属性。 */
    val mode: ForgeRecipeMode = ForgeRecipeMode.CREATE,
    /** UPGRADE 专用：允许重铸的武器类型；null 时回退到目标装备自己的 weapon-category。 */
    val requiresWeaponCategory: String? = null,
    /** UPGRADE 专用：输入武器的 sourceforge:tier 必须 >= 此值才能使用该蓝图。 */
    val minTier: Int = 0
)

enum class ForgeRecipeMode { CREATE, UPGRADE }

/** 配方所需材料：CE 物品 id + 数量。 */
data class RecipeMaterial(
    val ceId: String,
    val amount: Int
)

/** 装备某一等级的一条词条固定值（不再是随机区间）。value 为 NaN 表示配置未迁移完成，见 [ForgeConfig.validate]。 */
data class AffixRollConfig(
    val affixId: String,
    val value: Double
)

data class AffixConfig(
    val id: String,
    val displayName: String,
    val pdcKey: String,
    val valueType: String,
    val decimals: Int,
    val combat: String,
    val scale: Double,
    val lore: String,
    /** MOD 卡上按百分比显示(值×100 加 %)。 */
    val percent: Boolean = false,
    /** MOD 卡上数值的颜色码(元素词条用元素色)。 */
    val color: String = "&f"
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
