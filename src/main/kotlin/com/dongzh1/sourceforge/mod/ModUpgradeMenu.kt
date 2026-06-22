package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.util.color
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * MOD 升级界面（Feature B）：
 * - modSlot：放入要升级的 MOD（可拿取）。
 * - 升级核心从玩家背包自动统计（识别 CE id "sourceforge:upgrade_core"）。
 * - upgradeSlot：升级按钮，显示 "rank r → r+1, 需要 N 个升级核心"。
 * 关闭时归还 modSlot 内的物品。
 */
class ModUpgradeMenu(
    private val plugin: SourceForge,
    private val player: Player
) : InventoryHolder {
    private val modService = plugin.modService

    val modSlot = 11
    val upgradeSlot = 15
    val closeSlot = 26

    private val inventory: Inventory =
        Bukkit.createInventory(this, 27, color("&0MOD 升级"))

    init {
        fillStatic()
        render()
    }

    override fun getInventory(): Inventory = inventory

    fun modItem(): ItemStack? = inventory.getItem(modSlot)?.takeIf { it.type != Material.AIR }

    private fun fillStatic() {
        val filler = pane(Material.GRAY_STAINED_GLASS_PANE, " ")
        for (slot in 0 until inventory.size) {
            if (slot != modSlot) inventory.setItem(slot, filler)
        }
        inventory.setItem(closeSlot, label(Material.BARRIER, "&c关闭", emptyList()))
    }

    /** 升级核心识别。 */
    fun isUpgradeCore(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR) return false
        return com.dongzh1.sourceforge.item.CraftEngineHook.itemId(item) == UPGRADE_CORE_ID
    }

    fun countCores(): Int {
        var total = 0
        for (item in player.inventory.storageContents) {
            if (isUpgradeCore(item)) total += item!!.amount
        }
        return total
    }

    /** 渲染升级按钮（根据 modSlot 中的 MOD 状态）。 */
    fun render() {
        val mod = modItem()
        if (mod == null || !modService.isModItem(mod)) {
            inventory.setItem(upgradeSlot, label(Material.GRAY_DYE, "&7放入要升级的 MOD", listOf("&7左侧放入 MOD 物品")))
            return
        }
        val id = modService.modId(mod) ?: return
        val config = modService.mods[id]
        if (config == null) {
            inventory.setItem(upgradeSlot, label(Material.BARRIER, "&c未知 MOD", emptyList()))
            return
        }
        if (config.maxRank <= 0) {
            inventory.setItem(upgradeSlot, label(Material.BARRIER, "&c该 MOD 不可升级", emptyList()))
            return
        }
        val rank = modService.modRank(mod)
        if (rank >= config.maxRank) {
            inventory.setItem(upgradeSlot, label(Material.BARRIER, "&c已满段位 (${rank}/${config.maxRank})", emptyList()))
            return
        }
        val need = config.upgradeCostFor(rank)
        val have = countCores()
        val enough = have >= need
        inventory.setItem(
            upgradeSlot,
            label(
                if (enough) Material.LIME_DYE else Material.RED_DYE,
                "&b升级: 段位 $rank → ${rank + 1}",
                listOf(
                    "&7需要 &e$need &7个升级核心",
                    "&7拥有 ${if (enough) "&a" else "&c"}$have&7/&f$need",
                    if (enough) "&a点击升级" else "&c升级核心不足"
                )
            )
        )
    }

    private fun label(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta.setDisplayName(color(name))
        if (lore.isNotEmpty()) meta.lore = color(lore)
        item.itemMeta = meta
        return item
    }

    private fun pane(material: Material, name: String): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta.setDisplayName(color(name))
        item.itemMeta = meta
        return item
    }

    companion object {
        const val UPGRADE_CORE_ID = "sourceforge:upgrade_core"
    }
}
