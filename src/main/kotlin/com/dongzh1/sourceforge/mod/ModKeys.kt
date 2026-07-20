package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.config.AffixConfig
import org.bukkit.NamespacedKey
import org.bukkit.plugin.Plugin

/**
 * MOD 系统与物品系统共用的 PDC NamespacedKey 工厂。
 * 集中定义，避免 ForgeItemService 与 ModService 各建一份导致约定漂移（评审 #8）。
 */
object ModKeys {
    /** 装备的 MOD 容量上限 key。 */
    fun modCapacity(plugin: Plugin): NamespacedKey = NamespacedKey(plugin, "mod_capacity")

    /** affixId -> mod_delta_<pdcKey>：MOD 额外加成层，与基础词条相加。 */
    fun modDeltaKeys(plugin: Plugin, affixes: Collection<AffixConfig>): Map<String, NamespacedKey> =
        affixes.associate { it.id to NamespacedKey(plugin, "mod_delta_${it.pdcKey}") }

    /** 8 个普通MOD槽的安装记录（逗号分隔 token，`id`/`id#rank`/`~nm`）。 */
    fun modInstalled(plugin: Plugin): NamespacedKey = NamespacedKey(plugin, "mod_installed")

    /** 技能槽（独立于 8 个普通MOD槽，武器专属）的安装记录：逗号分隔的技能MOD id。 */
    fun modSkillInstalled(plugin: Plugin): NamespacedKey = NamespacedKey(plugin, "mod_skill_installed")

    /** 护甲专属被动技能槽（1个，独立于普通8槽与武器技能触发栏）的安装记录：单个被动技能MOD id。 */
    fun modPassiveInstalled(plugin: Plugin): NamespacedKey = NamespacedKey(plugin, "mod_passive_installed")

    /** 8 个槽位各自的梦魇MOD 实例数据 key（仅当该槽 mod_installed token == ~nm 时有效）。 */
    fun nmSlots(plugin: Plugin): List<NamespacedKey> = (0 until 8).map { NamespacedKey(plugin, "nm_slot_$it") }

    /** 8 个槽位各自的裂罅MOD 实例数据 key（仅当该槽 mod_installed token == ~rv 时有效）。 */
    fun rivenSlots(plugin: Plugin): List<NamespacedKey> = (0 until 8).map { NamespacedKey(plugin, "riven_slot_$it") }

    /** 外观隐藏开关：value 是隐藏前原始 item_model 的字符串备份（可能为空串）；key 存在即代表当前处于隐藏状态。 */
    fun modHiddenAppearance(plugin: Plugin): NamespacedKey = NamespacedKey(plugin, "mod_hidden_appearance")
}
