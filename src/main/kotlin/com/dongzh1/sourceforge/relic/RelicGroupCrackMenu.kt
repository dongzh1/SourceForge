package com.dongzh1.sourceforge.relic

import com.dongzh1.sourceforge.item.CraftEngineHook
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * 组队开箱选择界面：每个参与者各持有自己独立的 Inventory 实例(不是同一个 Inventory 对象共享给
 * 所有人)，底层数据全部指向同一个 [session]——本插件族多人 GUI 的通用写法(照抄 ExtractVoteGui/
 * PartyGui 的"每人一个 Inventory + 共享会话对象"惯例)。展示格 = outcomes 每项各建一份真实蓝图
 * 展示物(自带名字/lore，不需要额外做图标)，点击即可领取(见 RelicGroupCrackListener)。哪怕只有
 * 1 份结果也照样开这个界面(不做"单人跳过界面直接发"的特殊分支)。
 */
class RelicGroupCrackMenu(val session: RelicGroupCrackSession, val viewerId: UUID) : InventoryHolder {

    private val inv: Inventory = run {
        val rows = ((session.outcomes.size + 8) / 9 + 1).coerceIn(1, 6)
        Bukkit.createInventory(this, rows * 9, "§d遗物战利品 · 请选择一份")
    }

    override fun getInventory(): Inventory = inv

    /** 按当前 session.claimedBy 状态重绘：已被这个查看者领取的格子额外加"已领取"lore。 */
    fun render() {
        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        filler.itemMeta = filler.itemMeta?.also { it.setDisplayName(" ") }
        for (i in 0 until inv.size) inv.setItem(i, filler)
        val claimedIndex = session.claimedBy[viewerId]
        session.outcomes.forEachIndexed { index, outcome ->
            val item = CraftEngineHook.build(outcome.blueprintId, 1) ?: return@forEachIndexed
            if (index == claimedIndex) {
                item.itemMeta = item.itemMeta?.also { meta ->
                    val lore = (meta.lore ?: mutableListOf()).toMutableList()
                    lore.add("§a§l✔ 已领取")
                    meta.lore = lore
                }
            }
            inv.setItem(index, item)
        }
    }

    fun open(player: Player) {
        render()
        player.openInventory(inv)
    }
}
