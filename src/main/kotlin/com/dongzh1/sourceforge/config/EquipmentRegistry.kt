package com.dongzh1.sourceforge.config

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * 从 equipment/ 文件夹递归加载装备定义，取代旧版 config.yml 的 equipment: 段。
 * 支持任意深度子文件夹(含中文文件/文件夹名，纯用于人工分类，不影响解析)，
 * 每个 yml 文件顶层键直接是装备 id，一个文件可以装一件或多件装备。
 * 字段解析逻辑与老版 ForgeConfig 内联解析完全一致，复用 ForgeConfig 的
 * parseMaterial/defaultWeaponCategory/defaultEffectiveSlots/loadTierAffixes(已放宽为 internal)。
 */
object EquipmentRegistry {
    fun load(folder: File?, affixes: Map<String, AffixConfig>): Pair<Map<String, EquipmentConfig>, List<String>> {
        if (folder == null || !folder.isDirectory) return emptyMap<String, EquipmentConfig>() to emptyList()
        val equipment = linkedMapOf<String, EquipmentConfig>()
        val warnings = mutableListOf<String>()
        folder.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("yml", "yaml") }
            // equipment-tiers.yml 是装备品阶配置(见 EquipmentTierConfig)，不是装备定义——
            // 结构完全不同(顶层是 tiers:，不是装备id)，混进来解析会生成一堆垃圾"装备"条目。
            .filter { !it.name.equals(EquipmentTierConfig.FILE_NAME, ignoreCase = true) }
            .sortedBy { it.path }
            .forEach { file ->
                val config = YamlConfiguration.loadConfiguration(file)
                config.getKeys(false).forEach { id ->
                    if (id in equipment) {
                        warnings += "装备 $id 在 ${file.path} 重复定义，后出现的覆盖先前的"
                    }
                    equipment[id] = EquipmentConfig(
                        id = id,
                        displayName = config.getString("$id.display-name", id)!!,
                        material = ForgeConfig.parseMaterial(config.getString("$id.material"), Material.IRON_SWORD),
                        ceId = config.getString("$id.ce-id")?.takeIf { it.isNotBlank() },
                        weaponCategory = config.getString(
                            "$id.weapon-category",
                            ForgeConfig.defaultWeaponCategory(id, config.getString("$id.material"))
                        )!!.lowercase(),
                        chunkWorldLevelMode = config.getString("$id.chunkworld-level", "tier")!!,
                        pixelShopPrice = config.getDouble("$id.pixelshop-price", 0.0),
                        effectiveSlots = config.getStringList("$id.effective-slots")
                            .ifEmpty {
                                ForgeConfig.defaultEffectiveSlots(
                                    id,
                                    config.getString(
                                        "$id.weapon-category",
                                        ForgeConfig.defaultWeaponCategory(id, config.getString("$id.material"))
                                    )!!.lowercase()
                                )
                            }
                            .map { it.lowercase() }
                            .toSet(),
                        baseLore = config.getStringList("$id.base-lore"),
                        affixIds = config.getStringList("$id.affixes").ifEmpty { affixes.keys.toList() },
                        tierAffixes = ForgeConfig.loadTierAffixes(config, "$id.tier-affixes"),
                        freeAnvilEdit = config.getBoolean("$id.free-anvil-edit", false)
                    )
                }
            }
        return equipment to warnings
    }
}
