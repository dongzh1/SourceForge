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
}
