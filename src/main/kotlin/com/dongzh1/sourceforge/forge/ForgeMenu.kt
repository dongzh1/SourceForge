package com.dongzh1.sourceforge.forge

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.config.ForgeRecipe
import com.dongzh1.sourceforge.item.CraftEngineHook
import com.dongzh1.sourceforge.util.color
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

class ForgeMenu(
    private val plugin: SourceForge,
    /** 结构模式上下文：来自有效核心时非空，命令路径为 null。 */
    val structureContext: StructureContext? = null
) : InventoryHolder {
    /** 结构模式上下文：核心方块坐标 + 外壳层级 + 速度倍率。 */
    data class StructureContext(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val shellTier: String,
        val multiplier: Double
    )

    /** 唯一输入槽：放蓝图。 */
    val blueprintSlot = 29
    /** 锻造按钮：蓝图槽正下方一行、居中。 */
    val forgeSlot = 38

    /** 材料需求展示槽（只读信息图标），蓝图槽上方区域。 */
    private val materialDisplaySlots = listOf(10, 11, 12, 13, 14, 15, 16)

    private val inventory = Bukkit.createInventory(this, 54, color(plugin.forgeConfig.guiTitle))

    init {
        fillStatic()
        renderMaterials(null)
    }

    override fun getInventory(): Inventory = inventory

    /** 静态背景：除蓝图槽、按钮槽、材料展示槽外全部填充。 */
    private fun fillStatic() {
        val filler = pane(
            Material.GRAY_STAINED_GLASS_PANE,
            "&8不可放置",
            listOf("&7只有蓝图槽可以放入物品")
        )
        val reserved = materialDisplaySlots.toSet() + blueprintSlot + forgeSlot
        for (slot in 0 until inventory.size) {
            if (slot !in reserved) {
                inventory.setItem(slot, filler)
            }
        }
        // 蓝图槽边框
        inventory.setItem(28, pane(Material.BLUE_STAINED_GLASS_PANE, "&9蓝图槽", listOf("&7中间放入蓝图")))
        inventory.setItem(30, pane(Material.BLUE_STAINED_GLASS_PANE, "&9蓝图槽", listOf("&7中间放入蓝图")))
        inventory.setItem(20, label(Material.MAP, "&9蓝图", listOf("&f在下方槽放入 CraftEngine 蓝图", "&7材料从背包自动扣除")))
    }

    /**
     * 根据当前蓝图槽内容刷新材料需求展示。
     * blueprint 为 null 时清空展示槽并放占位提示。
     */
    fun renderMaterials(viewer: Player?) {
        val recipe = currentRecipe()
        // 清空材料展示槽
        for (slot in materialDisplaySlots) inventory.setItem(slot, null)

        if (recipe == null) {
            inventory.setItem(
                materialDisplaySlots.first(),
                label(Material.BARRIER, "&c无有效蓝图", listOf("&7在下方蓝图槽放入有效蓝图", "&7以查看所需材料"))
            )
            renderButton(false)
            return
        }

        var allEnough = true
        recipe.materials.forEachIndexed { index, material ->
            if (index >= materialDisplaySlots.size) return@forEachIndexed
            val have = if (viewer != null) countInInventory(viewer, material.ceId) else 0
            val enough = have >= material.amount
            if (!enough) allEnough = false
            val icon = CraftEngineHook.build(material.ceId, 1)?.clone()
                ?: ItemStack(Material.PAPER)
            val meta = icon.itemMeta
            val name = plugin.forgeConfig.displayName(material.ceId)
            meta.setDisplayName(color(if (enough) "&a$name" else "&c$name"))
            meta.lore = color(
                listOf(
                    "&7材料: &f$name",
                    "&7拥有 ${if (enough) "&a" else "&c"}$have &7/ 需要 &e${material.amount}"
                )
            )
            icon.itemMeta = meta
            inventory.setItem(materialDisplaySlots[index], icon)
        }
        renderButton(allEnough)
    }

    private fun renderButton(ready: Boolean) {
        val recipe = currentRecipe()
        val lore = mutableListOf<String>()
        if (recipe != null) {
            lore += "&7产出: &f${plugin.forgeConfig.equipmentDisplayName(recipe.equipmentId)} &7等级 &e${recipe.tier}"
            lore += "&7耗时: &e${recipe.timeSeconds.toInt()}s"
            lore += if (ready) "&a材料充足，点击开始锻造" else "&c材料不足"
        } else {
            lore += "&7放入有效蓝图后点击锻造"
        }
        inventory.setItem(
            forgeSlot,
            button(if (ready) Material.ANVIL else Material.DAMAGED_ANVIL, "&a开始锻造", lore)
        )
    }

    fun currentRecipe(): ForgeRecipe? {
        val item = inventory.getItem(blueprintSlot) ?: return null
        if (item.type == Material.AIR) return null
        val ceId = CraftEngineHook.itemId(item) ?: return null
        return plugin.forgeConfig.recipes[ceId]
    }

    private fun countInInventory(player: Player, ceId: String): Int {
        var total = 0
        for (item in player.inventory.storageContents) {
            if (item == null || item.type == Material.AIR) continue
            if (CraftEngineHook.itemId(item) == ceId) total += item.amount
        }
        return total
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
