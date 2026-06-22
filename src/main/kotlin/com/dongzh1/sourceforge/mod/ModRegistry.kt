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
                    itemLore = config.getStringList("item-lore")
                )
            }
        return mods to warnings
    }
}
