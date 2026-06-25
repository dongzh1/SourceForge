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
        Bukkit.createInventory(this, 54, CraftEngineHook.titleComponent(forgeConfig.modCapacity.guiTitle))

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
        // 装备栏：头盔、胸甲、护腿、靴子、副手
        for (slot in listOf(39, 38, 37, 36, 40)) {
            if (itemService.isSourceEquipment(inv.getItem(slot))) candidates += slot
        }
        // 物品栏 + 背包空间（storage 0..35，含快捷栏与主背包 27 格）：任意 SF 装备都可点选改造，
        // 不再要求 effective-slots 含 inventory/backpack（改造台应能改身上任意一件装备，而不只是手持/生效中的）。
        for (idx in 0..35) {
            if (itemService.isSourceEquipment(inv.getItem(idx))) candidates += idx
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
        val lore = (meta.lore() ?: mutableListOf()).toMutableList()
        val capColor = if (used > max) "&c" else "&e"
        lore += Text.comp("")
        lore += Text.comp("&8——————")
        lore += Text.comp("&7容量: $capColor$used&7/&f$max")
        lore += Text.comp("&7已安装: &f$installed 个 MOD")
        lore += Text.comp("&a点击打开改造界面")
        meta.lore(lore)
        display.itemMeta = meta
        return display
    }

    private fun modService() = plugin.modService

    private fun label(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        Text.name(meta, name)
        if (lore.isNotEmpty()) Text.lore(meta, lore)
        item.itemMeta = meta
        return item
    }

    private fun pane(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        Text.name(meta, name)
        if (lore.isNotEmpty()) Text.lore(meta, lore)
        item.itemMeta = meta
        return item
    }
}
