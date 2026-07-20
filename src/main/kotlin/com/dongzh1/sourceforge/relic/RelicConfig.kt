package com.dongzh1.sourceforge.relic

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/** 遗物权重表里的一条候选：普通蓝图或动态生成的彼端遗纹。 */
data class RelicEntry(
    val blueprintId: String?,
    val weight: Double,
    val dreammarkGroup: String? = null,
    val dreammarkProfileId: String? = null,
    val dreammarkQuestId: String? = null
) {
    val isDreammark: Boolean get() = !dreammarkGroup.isNullOrBlank() || !dreammarkProfileId.isNullOrBlank()
}

/** 解锁池里的一条候选：SourceTasks任务id + 权重。 */
data class RelicUnlockEntry(
    val questId: String,
    val weight: Double
)

/** 一个遗物（CE id）对应的完整权重池。 */
data class RelicDef(
    val id: String,
    val entries: List<RelicEntry>,
    val unlock: List<RelicUnlockEntry> = emptyList()   // 空=这个遗物类型不走"任务解锁"这条路径(比如武器遗物)
)

/**
 * 遗物开蓝图配置（relics.yml）。按遗物 CE id 查询其权重池。
 * [groupCrackTimeoutSeconds]：组队开箱界面(RelicGroupCrackMenu)未领取自动保底的等待秒数。
 */
data class RelicConfig(
    val relics: Map<String, RelicDef>,
    val groupCrackTimeoutSeconds: Int = 30
) {
    companion object {
        fun load(file: File): RelicConfig {
            if (!file.isFile) return RelicConfig(emptyMap())
            val yaml = YamlConfiguration.loadConfiguration(file)
            val timeout = yaml.getInt("group-crack.timeout-seconds", 30).coerceAtLeast(1)
            val section = yaml.getConfigurationSection("relics") ?: return RelicConfig(emptyMap(), timeout)
            val result = linkedMapOf<String, RelicDef>()
            for (relicId in section.getKeys(false)) {
                val path = "relics.$relicId"
                val entries = yaml.getMapList("$path.entries").mapNotNull { map ->
                    val blueprintId = map["blueprint"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    val dreammarkGroup = map["dreammark"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    val dreammarkProfileId = (map["dreammark-profile"] ?: map["dreammark-relic"])
                        ?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    if (blueprintId == null && dreammarkGroup == null && dreammarkProfileId == null) return@mapNotNull null
                    val weight = (map["weight"] as? Number)?.toDouble()
                        ?: map["weight"]?.toString()?.toDoubleOrNull() ?: 0.0
                    RelicEntry(
                        blueprintId = blueprintId,
                        weight = weight,
                        dreammarkGroup = dreammarkGroup,
                        dreammarkProfileId = dreammarkProfileId,
                        dreammarkQuestId = map["start-quest"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    )
                }
                val unlock = yaml.getMapList("$path.unlock").mapNotNull { map ->
                    val questId = map["quest"]?.toString() ?: return@mapNotNull null
                    val weight = (map["weight"] as? Number)?.toDouble()
                        ?: map["weight"]?.toString()?.toDoubleOrNull() ?: 0.0
                    RelicUnlockEntry(questId, weight)
                }
                result[relicId] = RelicDef(relicId, entries, unlock)
            }
            return RelicConfig(result, timeout)
        }
    }
}
