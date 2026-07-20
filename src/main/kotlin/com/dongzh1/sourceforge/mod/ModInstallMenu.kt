package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.item.CraftEngineHook
import com.dongzh1.sourceforge.util.Text
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
    private val itemService get() = plugin.itemService
    private val modService get() = plugin.modService

    val modSlotGuiIndices = listOf(9, 10, 11, 12, 18, 19, 20, 21)
    // 技能触发栏（独立区域，6 个；GUI 下标顺序 == TriggerSlot 下标，见 ModService.maxSkillSlots=TriggerSlot.COUNT）。
    // 右侧两排：14,15,16 = 左键/右键/Shift+左键；23,24,25 = Shift+右键/Shift+F/空中。
    val skillSlotGuiIndices = listOf(14, 15, 16, 23, 24, 25)
    val infoSlot = 4
    val equipDisplaySlot = 31
    val appearanceToggleSlot = 40
    val backSlot = 45
    val closeSlot = 53

    private val inventory: Inventory =
        Bukkit.createInventory(this, 54, CraftEngineHook.titleComponent(plugin.forgeConfig.modCapacity.guiTitle))

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
                val display = if (modService.isNightmareSlot(installedId)) {
                    nightmareSlotDisplay(item, i) ?: unknownModDisplay(installedId)
                } else if (modService.isRivenSlot(installedId)) {
                    rivenSlotDisplay(item, i) ?: unknownModDisplay(installedId)
                } else {
                    val parsed = modService.parseSlotToken(installedId)
                    if (parsed != null) modService.createModItem(parsed.first, 1, parsed.second) ?: unknownModDisplay(installedId)
                    else unknownModDisplay(installedId)
                }
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

        if (modService.isArmorCategory(item)) {
            // 护甲：主动技能触发栏武器专属，这里只渲染 1 格被动技能槽（复用触发栏区域第0格 GUI 14），
            // 其余5格留白融入背景，不再显示"锁定触发栏"（那是武器等级解锁语义，护甲不适用）。
            val passiveId = modService.readPassiveSkill(item)
            for (i in skillSlotGuiIndices.indices) {
                val gui = skillSlotGuiIndices[i]
                if (i != 0) {
                    inventory.setItem(gui, filler)
                    continue
                }
                if (passiveId != null) {
                    val display = modService.createModItem(passiveId, 1, 0) ?: unknownModDisplay(passiveId)
                    val meta = display.itemMeta
                    val lore = (meta.lore ?: mutableListOf()).toMutableList()
                    lore += color("&8—— 护甲被动 ——")
                    lore += color("&a装备即生效 &7(无需按键)")
                    lore += color("&e左键取出")
                    meta.lore = lore
                    display.itemMeta = meta
                    inventory.setItem(gui, display)
                } else {
                    inventory.setItem(gui, pane(
                        Material.LIME_STAINED_GLASS_PANE,
                        "&a空被动技能槽",
                        listOf("&7放入护甲专属被动技能MOD", "&8装备即生效，无需按键触发")
                    ))
                }
            }
        } else {
            // 武器：技能触发栏（独立于普通 MOD 槽）——每个 GUI 格固定对应一种触发方式（TriggerSlot），
            // 下标 i == 触发栏下标；技能MOD 放进哪格，就由那种操作(左键/右键/Shift组合/空中)触发 onActivate。
            val skillCount = modService.skillSlotCount(item)
            val skillSlots = modService.readSkillSlots(item)
            for (i in skillSlotGuiIndices.indices) {
                val gui = skillSlotGuiIndices[i]
                val trigger = TriggerSlot.byIndex(i)
                if (trigger == null || i >= skillCount) {
                    inventory.setItem(gui, pane(Material.GRAY_STAINED_GLASS_PANE, "&8锁定触发栏", listOf("&8此装备不支持更多技能触发栏")))
                    continue
                }
                val id = skillSlots.getOrNull(i)
                if (id != null) {
                    val display = modService.createModItem(id, 1, 0) ?: unknownModDisplay(id)
                    val meta = display.itemMeta
                    val lore = (meta.lore ?: mutableListOf()).toMutableList()
                    lore += color("&8—— 触发方式 ——")
                    lore += color("&d${trigger.display} &7触发")
                    lore += color("&e左键取出")
                    meta.lore = lore
                    display.itemMeta = meta
                    inventory.setItem(gui, display)
                } else {
                    inventory.setItem(gui, pane(
                        Material.MAGENTA_STAINED_GLASS_PANE,
                        "&d空触发栏 · &f${trigger.display}",
                        listOf(
                            "&7放入技能MOD → 由 &f${trigger.display} &7触发",
                            "&8(MOD 的 allowed-triggers 决定能否放入此栏)"
                        )
                    ))
                }
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
                "&b占用",
                listOf(
                    "&7当前占用: $capColor$used &7/ &f$max",
                    "&8$bar",
                    "&7已装 MOD: &f$installedCount/$maxSlots"
                )
            )
        )

        inventory.setItem(equipDisplaySlot, item.clone())

        if (modService.canToggleAppearance(item)) {
            val appearanceHidden = modService.isAppearanceHidden(item)
            inventory.setItem(
                appearanceToggleSlot,
                if (appearanceHidden) label(
                    Material.GLASS,
                    "&b外观：已隐藏",
                    listOf("&7穿在身上近乎透明", "&7点击恢复原本外观", "&8不影响手持贴图与词条数值")
                ) else label(
                    Material.SPYGLASS,
                    "&e外观：显示中",
                    listOf("&7点击隐藏穿戴外观(近乎透明)", "&8只影响穿在身上的显示", "&8不影响手持贴图与词条数值")
                )
            )
        }

        inventory.setItem(backSlot, label(Material.ARROW, "&e返回", emptyList()))
        inventory.setItem(closeSlot, label(Material.BARRIER, "&c关闭", emptyList()))
    }

    private fun nightmareSlotDisplay(equipment: ItemStack, slotIndex: Int): ItemStack? {
        val data = modService.nightmareSlotData(equipment, slotIndex) ?: return null
        val instance = plugin.nightmareService.parseDataString(data) ?: return null
        return plugin.nightmareService.buildFromData(instance)
    }

    private fun rivenSlotDisplay(equipment: ItemStack, slotIndex: Int): ItemStack? {
        val data = modService.rivenSlotData(equipment, slotIndex) ?: return null
        val instance = plugin.rivenService.parseDataString(data) ?: return null
        return plugin.rivenService.buildFromData(instance)
    }

    private fun unknownModDisplay(id: String): ItemStack {
        val item = ItemStack(Material.GRAY_DYE)
        val meta = item.itemMeta
        Text.name(meta, "&8未知 MOD")
        Text.lore(meta, listOf("&8id: $id"))
        item.itemMeta = meta
        return item
    }

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
