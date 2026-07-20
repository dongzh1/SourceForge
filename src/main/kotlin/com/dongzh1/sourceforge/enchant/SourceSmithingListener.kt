package com.dongzh1.sourceforge.enchant

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.PrepareSmithingEvent
import org.bukkit.inventory.meta.ArmorMeta
import org.bukkit.inventory.meta.trim.ArmorTrim
import org.bukkit.inventory.meta.trim.TrimMaterial
import org.bukkit.inventory.meta.trim.TrimPattern

/**
 * 强制支持锻造台给 SourceForge 四件护甲(头盔/胸甲/护腿/靴子)上花纹。用户明确要求"强制"——
 * 不依赖原版自己判断"这件物品算不算护甲"(原版按物品注册id/标签识别，SF 装备经 CraftEngine
 * 自定义组件包装后，理论上不该出问题，但不应该把"能不能上花纹"这个功能寄托在原版恰好认得出来上)：
 * 只要基材槽放的是 SF 装备且分类是护甲(armor_ 前缀，见 [com.dongzh1.sourceforge.config.EquipmentConfig.weaponCategory])，
 * 花纹模板槽/花纹材料槽是合法的原版花纹模板与材料，就自己解析出 (TrimPattern, TrimMaterial) 手动
 * 拼出结果物品——不管原版自己算出来的 event.result 是不是 null。
 */
class SourceSmithingListener(private val plugin: SourceForge) : Listener {

    @EventHandler
    fun onPrepareSmithing(event: PrepareSmithingEvent) {
        val inv = event.inventory
        // 锻造台槽位固定：0=花纹模板/升级模板，1=基材(护甲)，2=花纹材料/合金锭。
        val template = inv.getItem(0) ?: return
        val base = inv.getItem(1) ?: return
        val addition = inv.getItem(2) ?: return

        // 必须先是护甲，再是 SF 的装备——两个条件都得满足，武器/工具类 SF 装备不在此列。
        val category = plugin.itemService.weaponCategory(base) ?: return
        if (!category.startsWith("armor_")) return
        if (!plugin.itemService.isSourceEquipment(base)) return

        val pattern = trimPatternOf(template.type) ?: return
        val material = trimMaterialOf(addition.type) ?: return

        if (base.itemMeta !is ArmorMeta) return
        val result = base.clone()
        val resultMeta = result.itemMeta as ArmorMeta
        resultMeta.trim = ArmorTrim(material, pattern)
        result.itemMeta = resultMeta
        event.result = result
    }

    /** 花纹模板物品 id 固定形如 minecraft:<pattern>_armor_trim_smithing_template，直接反推花纹键名。 */
    private fun trimPatternOf(templateType: Material): TrimPattern? {
        val name = templateType.name
        if (!name.endsWith(TEMPLATE_SUFFIX)) return null
        val patternKey = name.removeSuffix(TEMPLATE_SUFFIX).lowercase()
        return Registry.TRIM_PATTERN.get(NamespacedKey.minecraft(patternKey))
    }

    private fun trimMaterialOf(additionType: Material): TrimMaterial? {
        val key = TRIM_MATERIAL_BY_ITEM[additionType] ?: return null
        return Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft(key))
    }

    companion object {
        private const val TEMPLATE_SUFFIX = "_ARMOR_TRIM_SMITHING_TEMPLATE"

        /** 花纹材料("锭"/矿物)物品 -> TrimMaterial 键名。原版自 1.20 花纹系统上线起固定这 10 种，
         * 键名与物品 id 不是简单后缀关系(如 iron_ingot -> iron、lapis_lazuli -> lapis)，手动列出。 */
        private val TRIM_MATERIAL_BY_ITEM: Map<Material, String> = mapOf(
            Material.IRON_INGOT to "iron",
            Material.COPPER_INGOT to "copper",
            Material.GOLD_INGOT to "gold",
            Material.LAPIS_LAZULI to "lapis",
            Material.EMERALD to "emerald",
            Material.DIAMOND to "diamond",
            Material.NETHERITE_INGOT to "netherite",
            Material.REDSTONE to "redstone",
            Material.QUARTZ to "quartz",
            Material.AMETHYST_SHARD to "amethyst"
        )
    }
}
