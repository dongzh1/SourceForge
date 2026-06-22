package com.dongzh1.sourceforge.multiblock

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.util.color
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * 锻造进度 / 收取界面。9 格：中间显示进度，右侧（slot 8）为收取按钮。
 * 持有核心方块坐标，点击收取时由监听器交给 ForgeStructureManager.collect。
 */
class ForgeProgressMenu(
    private val plugin: SourceForge,
    val coreWorld: String,
    val coreX: Int,
    val coreY: Int,
    val coreZ: Int
) : InventoryHolder {
    val collectSlot = 8

    private val inventory = Bukkit.createInventory(this, 9, color("&0源质锻炉"))

    init {
        refresh()
    }

    override fun getInventory(): Inventory = inventory

    fun coreBlock(): Block? {
        val world = Bukkit.getWorld(coreWorld) ?: return null
        return world.getBlockAt(coreX, coreY, coreZ)
    }

    fun refresh() {
        val filler = pane(Material.GRAY_STAINED_GLASS_PANE, "&8源质锻炉")
        for (i in 0 until inventory.size) inventory.setItem(i, filler)

        val core = coreBlock()
        val job = core?.let { plugin.structureManager.jobAt(it) }
        if (job == null) {
            inventory.setItem(4, button(Material.BARRIER, "&c没有进行中的作业", listOf("&7关闭即可")))
            inventory.setItem(collectSlot, pane(Material.GRAY_STAINED_GLASS_PANE, "&8-"))
            return
        }
        if (job.isDone()) {
            inventory.setItem(
                4,
                button(Material.NETHER_STAR, "&a锻造完成 ✔", listOf("&7点击右侧绿色玻璃收取产物"))
            )
            inventory.setItem(
                collectSlot,
                button(Material.LIME_STAINED_GLASS_PANE, "&a领取产物", listOf("&f点击放入背包"))
            )
        } else {
            val seconds = Math.ceil(job.remainingTicks / 20.0).toInt()
            inventory.setItem(
                4,
                button(
                    Material.BLAST_FURNACE,
                    "&e锻造中…",
                    listOf(
                        "&7剩余 &f${seconds}s",
                        "&7外壳 &f${plugin.structureManager.config.tierDisplay(job.shellTier)} &7速度 &f${job.multiplier}×",
                        "&7完成后右键核心或点此收取"
                    )
                )
            )
            inventory.setItem(collectSlot, pane(Material.YELLOW_STAINED_GLASS_PANE, "&e未完成", listOf("&7锻造中…")))
        }
    }

    private fun button(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta.setDisplayName(color(name))
        meta.lore = color(lore)
        item.itemMeta = meta
        return item
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
