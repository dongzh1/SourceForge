package com.dongzh1.sourceforge.forge

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.util.color
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

class ForgeMenu(
    private val plugin: SourceForge
) : InventoryHolder {
    val blueprintSlot = 10
    val baseMaterialSlots = setOf(12, 13, 14, 21, 22, 23)
    val specialMaterialSlots = setOf(15, 16, 17, 24, 25, 26, 33, 34, 35)
    val inputSlots = setOf(blueprintSlot) + baseMaterialSlots + specialMaterialSlots
    val forgeSlot = 49
    private val inventory = Bukkit.createInventory(this, 54, color(plugin.forgeConfig.guiTitle))

    init {
        fill()
    }

    override fun getInventory(): Inventory = inventory

    private fun fill() {
        val filler = pane(
            Material.GRAY_STAINED_GLASS_PANE,
            "&8不可放置",
            listOf("&7只有带颜色标记的空格可以放入物品")
        )
        for (slot in 0 until inventory.size) {
            if (slot !in inputSlots && slot != forgeSlot) {
                inventory.setItem(slot, filler)
            }
        }
        inventory.setItem(1, label(Material.MAP, "&9蓝图区", listOf("&f对应下方单个蓝图槽", "&7只能放 SourceForge 蓝图")))
        inventory.setItem(4, label(Material.IRON_INGOT, "&e基础材料区", listOf("&f对应左侧 6 个基础材料格", "&7只放当前蓝图 requirements 的材料")))
        inventory.setItem(7, label(Material.AMETHYST_SHARD, "&d附加材料区", listOf("&f对应右侧 9 个附加材料格", "&7只放 materials/*.yml 配置过的材料")))

        inventory.setItem(0, pane(Material.BLUE_STAINED_GLASS_PANE, "&9蓝图边框", listOf("&7蓝图槽在中间")))
        inventory.setItem(2, pane(Material.BLUE_STAINED_GLASS_PANE, "&9蓝图边框", listOf("&7蓝图槽在中间")))
        inventory.setItem(9, pane(Material.BLUE_STAINED_GLASS_PANE, "&9蓝图边框", listOf("&7蓝图槽只允许蓝图")))
        inventory.setItem(11, pane(Material.BLUE_STAINED_GLASS_PANE, "&9蓝图边框", listOf("&7蓝图槽只允许蓝图")))

        inventory.setItem(18, pane(Material.YELLOW_STAINED_GLASS_PANE, "&e基础材料边框", listOf("&7基础材料区起点")))
        inventory.setItem(19, pane(Material.YELLOW_STAINED_GLASS_PANE, "&e基础材料边框", listOf("&7基础材料区起点")))
        inventory.setItem(20, pane(Material.YELLOW_STAINED_GLASS_PANE, "&e基础材料边框", listOf("&7基础材料区起点")))
        inventory.setItem(27, pane(Material.YELLOW_STAINED_GLASS_PANE, "&e基础材料边框", listOf("&7基础材料区下排")))
        inventory.setItem(28, pane(Material.YELLOW_STAINED_GLASS_PANE, "&e基础材料边框", listOf("&7基础材料区下排")))
        inventory.setItem(29, pane(Material.YELLOW_STAINED_GLASS_PANE, "&e基础材料边框", listOf("&7基础材料区下排")))
        inventory.setItem(36, pane(Material.YELLOW_STAINED_GLASS_PANE, "&e基础材料边框", listOf("&7背景板，不能放物品")))
        inventory.setItem(37, pane(Material.YELLOW_STAINED_GLASS_PANE, "&e基础材料边框", listOf("&7背景板，不能放物品")))
        inventory.setItem(38, pane(Material.YELLOW_STAINED_GLASS_PANE, "&e基础材料边框", listOf("&7背景板，不能放物品")))

        inventory.setItem(30, pane(Material.PURPLE_STAINED_GLASS_PANE, "&d附加材料边框", listOf("&7附加材料区左边界")))
        inventory.setItem(31, pane(Material.PURPLE_STAINED_GLASS_PANE, "&d附加材料边框", listOf("&7附加材料区左边界")))
        inventory.setItem(32, pane(Material.PURPLE_STAINED_GLASS_PANE, "&d附加材料边框", listOf("&7附加材料区左边界")))
        inventory.setItem(39, pane(Material.PURPLE_STAINED_GLASS_PANE, "&d附加材料边框", listOf("&7附加材料区下边界")))
        inventory.setItem(40, pane(Material.PURPLE_STAINED_GLASS_PANE, "&d附加材料边框", listOf("&7附加材料区下边界")))
        inventory.setItem(41, pane(Material.PURPLE_STAINED_GLASS_PANE, "&d附加材料边框", listOf("&7附加材料区下边界")))
        inventory.setItem(42, pane(Material.PURPLE_STAINED_GLASS_PANE, "&d附加材料边框", listOf("&7附加材料区右下边界")))
        inventory.setItem(43, pane(Material.PURPLE_STAINED_GLASS_PANE, "&d附加材料边框", listOf("&7附加材料区右下边界")))
        inventory.setItem(44, pane(Material.PURPLE_STAINED_GLASS_PANE, "&d附加材料边框", listOf("&7附加材料区右下边界")))

        inventory.setItem(
            forgeSlot,
            button(
                Material.ANVIL,
                "&a④ 开始锻造",
                listOf("&f检查蓝图、基础材料和附加材料", "&7附加材料不是必需品，不放也会生成等级基础词条")
            )
        )
    }

    private fun button(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta.setDisplayName(color(name))
        meta.lore = color(lore)
        item.itemMeta = meta
        return item
    }

    private fun label(material: Material, name: String, lore: List<String>): ItemStack {
        return button(material, name, lore)
    }

    private fun pane(material: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta.setDisplayName(color(name))
        if (lore.isNotEmpty()) meta.lore = color(lore)
        item.itemMeta = meta
        return item
    }
}
