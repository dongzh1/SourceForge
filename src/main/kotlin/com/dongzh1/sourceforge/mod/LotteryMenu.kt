package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.item.CraftEngineHook
import com.dongzh1.sourceforge.util.Text
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * 附魔台抽奖界面（Feature C）：
 * - inputSlot：放入空白 MOD (CE id "sourceforge:blank_mod")。
 * - drawSlot：抽取按钮，消耗 1 个空白 MOD + 配置的经验等级，按权重随机产出一个普通 MOD。
 * 关闭时归还 inputSlot 内的物品。
 */
class LotteryMenu(
    private val plugin: SourceForge,
    private val player: Player
) : InventoryHolder {
    val inputSlot = 11
    val drawSlot = 15
    val closeSlot = 26

    private val inventory: Inventory =
        Bukkit.createInventory(this, 27, CraftEngineHook.titleComponent("&0源质抽奖"))

    init {
        fillStatic()
        render()
    }

    override fun getInventory(): Inventory = inventory

    fun inputItem(): ItemStack? = inventory.getItem(inputSlot)?.takeIf { it.type != Material.AIR }

    private fun fillStatic() {
        val filler = pane(Material.GRAY_STAINED_GLASS_PANE, " ")
        for (slot in 0 until inventory.size) {
            if (slot != inputSlot) inventory.setItem(slot, filler)
        }
        inventory.setItem(closeSlot, label(Material.BARRIER, "&c关闭", emptyList()))
    }

    fun render() {
        val xpCost = plugin.lotteryConfig.xpCost
        val input = inputItem()
        val hasBlank = isBlankMod(input)
        val enoughXp = player.level >= xpCost
        inventory.setItem(
            drawSlot,
            label(
                if (hasBlank && enoughXp) Material.LIME_DYE else Material.RED_DYE,
                "&b抽取 MOD",
                listOf(
                    "&7放入空白 MOD 并消耗 &e$xpCost &7级经验",
                    "&7空白 MOD: ${if (hasBlank) "&a已放入" else "&c未放入"}",
                    "&7经验等级: ${if (enoughXp) "&a" else "&c"}${player.level}&7/&f$xpCost",
                    if (hasBlank && enoughXp) "&a点击抽取" else "&c条件不足"
                )
            )
        )
    }

    fun isBlankMod(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR) return false
        return CraftEngineHook.itemId(item) == BLANK_MOD_ID
    }

    private fun label(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        Text.name(meta, name)
        if (lore.isNotEmpty()) Text.lore(meta, lore)
        item.itemMeta = meta
        return item
    }

    private fun pane(material: Material, name: String): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        Text.name(meta, name)
        item.itemMeta = meta
        return item
    }

    companion object {
        const val BLANK_MOD_ID = "sourceforge:blank_mod"
    }
}
