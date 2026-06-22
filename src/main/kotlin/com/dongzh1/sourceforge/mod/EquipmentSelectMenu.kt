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
 * 装备选择界面：枚举玩家身上/背包内的 SF 装备，点击卡片打开对应改造界面。
 * cardSlots: guiSlot -> (playerInventorySlot, weaponTypeId)。
 * 记录打开时的 weaponTypeId，点击时校验目标槽位仍是同一件装备，避免玩家整理背包后误开错装备。
 */
class EquipmentSelectMenu(
    private val plugin: SourceForge,
    private val player: Player
) : InventoryHolder {
    private val itemService = plugin.itemService
    private val forgeConfig = plugin.forgeConfig

    private val inventory: Inventory =
        Bukkit.createInventory(this, 54, color(forgeConfig.modCapacity.guiTitle))

    /** guiSlot -> (playerInventorySlot, weaponTypeId) */
    val cardSlots: Map<Int, Pair<Int, String>>

    private val cardGuiSlots: List<Int> = (10..16) + (19..25) + (28..34) + (37..43)

    init {
        cardSlots = buildCards()
        fill()
    }

    override fun getInventory(): Inventory = inventory

    fun playerSlotForGuiSlot(guiSlot: Int): Int? = cardSlots[guiSlot]?.first

    /** guiSlot -> 打开时该装备的 weaponTypeId，用于点击时校验装备未被移动/替换。 */
    fun weaponTypeForGuiSlot(guiSlot: Int): String? = cardSlots[guiSlot]?.second

    private fun buildCards(): Map<Int, Pair<Int, String>> {
        val inv = player.inventory
        val candidates = mutableListOf<Int>()
        // 顺序：头盔、胸甲、护腿、靴子、主手、副手
        for (slot in listOf(39, 38, 37, 36, inv.heldItemSlot, 40)) {
            if (itemService.isSourceEquipment(inv.getItem(slot))) candidates += slot
        }
        // 背包生效装备（storage 0..35，排除主手槽）
        for (idx in 0..35) {
            if (idx == inv.heldItemSlot) continue
            val item = inv.getItem(idx) ?: continue
            if (!itemService.isSourceEquipment(item)) continue
            val effective = itemService.equipmentConfig(item)?.effectiveSlots ?: continue
            if ("inventory" in effective || "backpack" in effective) {
                candidates += idx
            }
        }
        val result = linkedMapOf<Int, Pair<Int, String>>()
        for ((i, playerSlot) in candidates.withIndex()) {
            val guiSlot = cardGuiSlots.getOrNull(i) ?: break
            val item = inv.getItem(playerSlot) ?: continue
            val weaponTypeId = itemService.weaponType(item) ?: continue
            result[guiSlot] = playerSlot to weaponTypeId
        }
        return result
    }

    private fun fill() {
        val filler = pane(Material.GRAY_STAINED_GLASS_PANE, " ", emptyList())
        for (slot in 0 until inventory.size) {
            inventory.setItem(slot, filler)
        }

        inventory.setItem(4, label(Material.NETHER_STAR, "&b源质改造", listOf("&7点击身上装备以安装 MOD")))
        inventory.setItem(49, label(Material.BARRIER, "&c关闭", emptyList()))

        val inv = player.inventory
        for ((guiSlot, entry) in cardSlots) {
            val item = inv.getItem(entry.first) ?: continue
            inventory.setItem(guiSlot, buildCard(item))
        }
    }

    private fun buildCard(equipment: ItemStack): ItemStack {
        val display = equipment.clone()
        val meta = display.itemMeta
        val used = modService().usedCapacity(equipment)
        val max = modService().readCapacity(equipment)
        val installed = modService().readInstalledSlots(equipment).count { it != null }
        val lore = (meta.lore ?: mutableListOf()).toMutableList()
        lore += ""
        lore += color("&8——————")
        val capColor = if (used > max) "&c" else "&e"
        lore += color("&7容量: $capColor$used&7/&f$max")
        lore += color("&7已安装: &f$installed 个 MOD")
        lore += color("&a点击打开改造界面")
        meta.lore = lore
        display.itemMeta = meta
        return display
    }

    private fun modService() = plugin.modService

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
