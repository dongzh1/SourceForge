package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * 给 SourceForge 装备盖上 / 清除 MythicMobs 物品身份，使 MM 对该装备触发其 item-skills。
 *
 * MM 5.13 通过物品 PDC 识别自己的物品（见 io.lumine.mythic.core.constants.ItemKeys）：
 *  - mythicmobs:type    = STRING  -> MM 物品内部名
 *  - mythicmobs:version = INTEGER -> 物品版本；低于模板版本时 MM 会“更新”(重建)该物品。
 *
 * 我们把 version 钉成一个极大值，让 MM 永远认为它不过期、不去重建——避免 MM 用模板覆盖掉
 * SF/CraftEngine 物品的外观与数据。type 仍然有效，所以 item-skills 照常触发。
 *
 * 不直接编译依赖 MM：只用 Bukkit PDC 写值；namespace 用 MM 插件注册名 "mythicmobs"。
 */
class MythicItemHook(private val plugin: SourceForge) {

    private val typeKey = NamespacedKey("mythicmobs", "type")
    private val versionKey = NamespacedKey("mythicmobs", "version")

    /** 钉死的高版本号：>= 任何真实模板版本，使 MM 不触发物品更新/重建。 */
    private val pinnedVersion = 2_000_000_000

    private fun mmAvailable(): Boolean = Bukkit.getPluginManager().isPluginEnabled("MythicMobs")

    /**
     * 设置装备的 MM 物品身份。
     * @param mmItem MM 物品内部名；传 null 表示清除身份。
     */
    fun applyIdentity(item: ItemStack, mmItem: String?) {
        val meta = item.itemMeta ?: return
        val pdc = meta.persistentDataContainer
        val hasType = pdc.has(typeKey, PersistentDataType.STRING)
        val hasVersion = pdc.has(versionKey, PersistentDataType.INTEGER)

        if (mmItem.isNullOrBlank()) {
            if (!hasType && !hasVersion) return   // 无身份可清，避免无谓写 meta
            pdc.remove(typeKey)
            pdc.remove(versionKey)
            item.itemMeta = meta
            return
        }

        // MM 不在线时不盖身份（盖了也没意义，还可能在 MM 缺失时残留无效 PDC）
        if (!mmAvailable()) {
            if (hasType || hasVersion) { pdc.remove(typeKey); pdc.remove(versionKey); item.itemMeta = meta }
            return
        }

        val current = pdc.get(typeKey, PersistentDataType.STRING)
        if (current == mmItem && hasVersion) return   // 已是目标身份，跳过
        pdc.set(typeKey, PersistentDataType.STRING, mmItem)
        pdc.set(versionKey, PersistentDataType.INTEGER, pinnedVersion)
        item.itemMeta = meta
    }
}
