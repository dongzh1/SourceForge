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
 * MOD 安装界面：编辑指定背包槽位 (equipmentSlot) 的装备 MOD。
 * 每次操作立即提交到 live item，GUI 只是编辑器。
 */
class ModInstallMenu(
    private val plugin: SourceForge,
    private val player: Player,
    val equipmentSlot: Int
) : InventoryHolder {
    private val itemService = plugin.itemService
    private val modService = plugin.modService

    val modSlotGuiIndices = listOf(9, 10, 11, 12, 18, 19, 20, 21)
    val infoSlot = 4
    val equipDisplaySlot = 31
    val backSlot = 45
    val closeSlot = 53

    private val inventory: Inventory =
        Bukkit.createInventory(this, 54, color(plugin.forgeConfig.modCapacity.guiTitle))

    init {
        populate()
    }

    override fun getInventory(): Inventory = inventory

    fun liveItem(): ItemStack? =
        player.inventory.getItem(equipmentSlot)?.takeIf { itemService.isSourceEquipment(it) }

    fun populate() {
        val filler = pane(Material.GRAY_STAINED_GLASS_PANE, " ", emptyList())
        for (slot in 0 until inventory.size) {
            inventory.setItem(slot, filler)
        }

        val item = liveItem() ?: return
        val maxSlots = modService.maxSlots(item)
        val slots = modService.readInstalledSlots(item)

        for (i in 0..7) {
            val gui = modSlotGuiIndices[i]
            if (i >= maxSlots) {
                inventory.setItem(gui, pane(Material.GRAY_STAINED_GLASS_PANE, "&8锁定槽", listOf("&8此装备不支持更多 MOD 槽")))
                continue
            }
            val installedId = slots.getOrNull(i)
            if (installedId != null) {
                val display = modService.createModItem(installedId) ?: unknownModDisplay(installedId)
                val meta = display.itemMeta
                val lore = (meta.lore ?: mutableListOf()).toMutableList()
                lore += color("&e左键取出")
                meta.lore = lore
                display.itemMeta = meta
                inventory.setItem(gui, display)
            } else {
                inventory.setItem(gui, pane(Material.LIME_STAINED_GLASS_PANE, "&7空 MOD 槽", listOf("&7将 MOD 物品拖入安装")))
            }
        }

        val used = modService.usedCapacity(item)
        val max = modService.readCapacity(item)
        val installedCount = slots.count { it != null }
        val capColor = if (used > max) "&c" else "&e"
        val filled = if (max > 0) (used * 20 / max).coerceIn(0, 20) else 0
        val bar = "█".repeat(filled) + "░".repeat(20 - filled)
        inventory.setItem(
            infoSlot,
            label(
                Material.COMPARATOR,
                "&b容量",
                listOf(
                    "&7已用: $capColor$used &7/ &f$max",
                    "&8$bar",
                    "&7已装 MOD: &f$installedCount/$maxSlots"
                )
            )
        )

        inventory.setItem(equipDisplaySlot, item.clone())
        inventory.setItem(backSlot, label(Material.ARROW, "&e返回", emptyList()))
        inventory.setItem(closeSlot, label(Material.BARRIER, "&c关闭", emptyList()))
    }

    private fun unknownModDisplay(id: String): ItemStack {
        val item = ItemStack(Material.GRAY_DYE)
        val meta = item.itemMeta
        meta.setDisplayName(color("&8未知 MOD"))
        meta.lore = color(listOf("&8id: $id"))
        item.itemMeta = meta
        return item
    }

    private fun label(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta.setDisplayName(color(name))
        if (lore.isNotEmpty()) meta.lore = color(lore)
        item.itemMeta = meta
        return item
    }

    private fun pane(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta.setDisplayName(color(name))
        if (lore.isNotEmpty()) meta.lore = color(lore)
        item.itemMeta = meta
        return item
    }
}
