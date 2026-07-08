package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.config.AffixConfig
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

object ModRegistry {
    fun load(folder: File?, affixes: Map<String, AffixConfig>): Pair<Map<String, ModConfig>, List<String>> {
        if (folder == null || !folder.isDirectory) return emptyMap<String, ModConfig>() to emptyList()
        val mods = linkedMapOf<String, ModConfig>()
        val warnings = mutableListOf<String>()
        folder.listFiles { file -> file.isFile && file.extension.lowercase() in setOf("yml", "yaml") }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                val config = YamlConfiguration.loadConfiguration(file)
                val id = config.getString("id") ?: file.nameWithoutExtension
                val itemId = config.getString("item") ?: config.getString("item-id") ?: return@forEach
                val materialRaw = config.getString("material")
                val material = materialRaw?.let {
                    Material.matchMaterial(it.substringAfter("minecraft:").uppercase())
                } ?: Material.PAPER
                val effects = linkedMapOf<String, Double>()
                config.getConfigurationSection("effects")?.getKeys(false)?.forEach { affixId ->
                    effects[affixId] = config.getDouble("effects.$affixId")
                    if (affixId !in affixes) {
                        warnings += "MOD ${id} effects 引用了不存在的词条 $affixId"
                    }
                }
                mods[id] = ModConfig(
                    id = id,
                    displayName = config.getString("display-name", id)!!,
                    itemId = itemId,
                    material = material,
                    customModelData = config.getInt("custom-model-data", 0).takeIf { it > 0 },
                    cost = config.getInt("cost", 0),
                    maxPerEquipment = config.getInt("max-per-equipment", 1),
                    exclusivityGroup = config.getString("exclusivity-group")?.takeIf { it.isNotBlank() },
                    applicableCategories = config.getStringList("applicable-categories").map { it.lowercase() }.toSet(),
                    applicableEquipment = config.getStringList("applicable-equipment").map { it.lowercase() }.toSet(),
                    effects = effects,
                    tags = config.getStringList("tags"),
                    itemLore = config.getStringList("item-lore"),
                    maxRank = config.getInt("max-rank", 0).coerceAtLeast(0),
                    upgradeCostBase = config.getInt("upgrade-cost", 1).coerceAtLeast(1),
                    weight = config.getDouble("weight", 1.0).coerceAtLeast(0.0),
                    // 技能MOD：声明 mm-item 即视为技能MOD（也可显式 skill: true）
                    mmItem = config.getString("mm-item")?.takeIf { it.isNotBlank() },
                    skill = config.getBoolean("skill", config.getString("mm-item")?.isNotBlank() == true),
                    // 触发栏白名单（空=不限）：归一成 TriggerSlot.id，容忍别名。
                    // 评审#4：无法识别的条目写 warnings（与 effects 未知词条一致），避免拼错被静默当成"不限"。
                    allowedTriggers = config.getStringList("allowed-triggers").let { raw ->
                        raw.forEach {
                            if (TriggerSlot.byId(it) == null) warnings += "MOD ${id} allowed-triggers 含无法识别的触发栏 '$it'（已忽略）"
                        }
                        raw.mapNotNull { TriggerSlot.byId(it)?.id }.toSet()
                    },
                    // 统一排版（ModLoreBuilder）用的展示字段，全部可选。
                    typeLabel = config.getString("type-label")?.takeIf { it.isNotBlank() },
                    description = config.getStringList("description"),
                    manaCost = config.getString("mana")?.takeIf { it.isNotBlank() },
                    cooldown = config.getString("cooldown")?.takeIf { it.isNotBlank() }
                )
            }
        return mods to warnings
    }
}
