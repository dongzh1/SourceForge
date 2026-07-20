package com.dongzh1.sourceforge.config

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * 装备品阶配置（equipment/equipment-tiers.yml）：跟 CE 战斗装备分类(weapon-category)、
 * 装备强度(tier，1/2/3…)是两码事——这是"这套装备属于哪个品阶"，用来给强化(升级武器)每级花费
 * 乘一个倍率。预留 5 个品阶槽位；未分配到任何品阶的装备倍率按 1.0（强化花费不受影响）。
 *
 * yml 里按 CE 物品 id 登记套装成员（人工好辨认），加载时用已经解析好的 equipment 目录下各 yml
 * (ce-id -> 装备内部id) 反查成内部装备 id，运行期强化流程直接用 `weaponType(weapon)`
 * （PDC 读取，不依赖 CraftEngine）做 O(1) 查询，不需要在强化那一刻再去解析 CE id。
 */
data class EquipmentTierConfig(
    val displayNames: Map<Int, String>,
    val multipliers: Map<Int, Double>,
    private val equipmentTier: Map<String, Int>
) {
    /** 强化花费倍率；装备未分配品阶(equipmentId 为 null 或查不到)返回 1.0。 */
    fun multiplierFor(equipmentId: String?): Double {
        if (equipmentId == null) return 1.0
        val tier = equipmentTier[equipmentId] ?: return 1.0
        return multipliers[tier] ?: 1.0
    }

    /** 该装备所属品阶编号；未分配返回 null。 */
    fun tierOf(equipmentId: String?): Int? = equipmentId?.let { equipmentTier[it] }

    companion object {
        /** 保留文件名：EquipmentRegistry 递归扫描 equipment/ 文件夹时需要显式跳过它，
         * 否则会把 `tiers:` 顶层键当成一件装备定义解析，生成一堆垃圾条目。 */
        const val FILE_NAME = "equipment-tiers.yml"

        fun load(file: File?, equipment: Map<String, EquipmentConfig>): EquipmentTierConfig {
            if (file == null || !file.isFile) return EquipmentTierConfig(emptyMap(), emptyMap(), emptyMap())
            val yaml = YamlConfiguration.loadConfiguration(file)
            // ce-id -> 装备内部id 反查表（同一 ce-id 理论上只应对应一件装备）。
            val ceIdToEquipmentId = equipment.values.mapNotNull { eq -> eq.ceId?.let { it to eq.id } }.toMap()

            val displayNames = linkedMapOf<Int, String>()
            val multipliers = linkedMapOf<Int, Double>()
            val equipmentTier = linkedMapOf<String, Int>()

            yaml.getConfigurationSection("tiers")?.getKeys(false)?.forEach { key ->
                val tierNum = key.toIntOrNull() ?: return@forEach
                val path = "tiers.$key"
                displayNames[tierNum] = yaml.getString("$path.display-name", "")!!
                multipliers[tierNum] = yaml.getDouble("$path.cost-multiplier", 1.0)
                yaml.getStringList("$path.items").forEach { ceId ->
                    val eqId = ceIdToEquipmentId[ceId] ?: return@forEach
                    equipmentTier[eqId] = tierNum
                }
            }
            return EquipmentTierConfig(displayNames, multipliers, equipmentTier)
        }
    }
}
