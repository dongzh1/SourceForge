package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.ItemStack

class ModListener(
    private val plugin: SourceForge
) : Listener {
    private val itemService = plugin.itemService
    private val modService get() = plugin.modService

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder
        when (holder) {
            is EquipmentSelectMenu -> handleSelectClick(event, holder)
            is ModInstallMenu -> handleInstallClick(event, holder)
            else -> return
        }
    }

    private fun handleSelectClick(event: InventoryClickEvent, menu: EquipmentSelectMenu) {
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.click == ClickType.DOUBLE_CLICK) return
        val rawSlot = event.rawSlot
        if (rawSlot < 0 || rawSlot >= event.inventory.size) return
        if (rawSlot == 49) {
            player.closeInventory()
            return
        }
        val playerSlot = menu.playerSlotForGuiSlot(rawSlot) ?: return
        val storedWeaponTypeId = menu.weaponTypeForGuiSlot(rawSlot) ?: return
        val live = player.inventory.getItem(playerSlot)
        if (!itemService.isSourceEquipment(live)) {
            player.sendMessage("§c[SourceForge] §f该装备已不在原位")
            return
        }
        if (itemService.weaponType(live) != storedWeaponTypeId) {
            player.sendMessage("§c[SourceForge] §f装备已移动，请重新打开改造界面")
            return
        }
        val install = ModInstallMenu(plugin, player, playerSlot)
        player.openInventory(install.inventory)
    }

    private fun handleInstallClick(event: InventoryClickEvent, menu: ModInstallMenu) {
        val player = event.whoClicked as? Player ?: return
        val live = menu.liveItem()
        if (live == null) {
            event.isCancelled = true
            player.closeInventory()
            player.sendMessage("§c[SourceForge] §f装备已不在原位，已关闭改造界面")
            return
        }
        if (event.click == ClickType.DOUBLE_CLICK) {
            event.isCancelled = true
            return
        }
        val rawSlot = event.rawSlot
        // 玩家背包区
        if (rawSlot >= event.inventory.size) {
            if (event.action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                event.isCancelled = true
                player.sendMessage("§c[SourceForge] §f请将 MOD 放到光标上点击空槽安装，不支持 Shift 快速放入")
                playDeny(player)
            }
            return
        }
        when (rawSlot) {
            menu.closeSlot -> {
                event.isCancelled = true
                player.closeInventory()
                return
            }
            menu.backSlot -> {
                event.isCancelled = true
                player.openInventory(EquipmentSelectMenu(plugin, player).inventory)
                return
            }
            menu.infoSlot, menu.equipDisplaySlot -> {
                event.isCancelled = true
                return
            }
        }
        val i = menu.modSlotGuiIndices.indexOf(rawSlot)
        if (i < 0) {
            // filler 等
            event.isCancelled = true
            return
        }
        event.isCancelled = true
        val maxSlots = modService.maxSlots(live)
        if (i >= maxSlots) {
            player.sendMessage("§c该装备不支持更多 MOD 槽")
            playDeny(player)
            return
        }
        val slots = modService.readInstalledSlots(live)
        if (slots.getOrNull(i) != null) {
            // 占用 -> 取出
            val returned = modService.tryRemove(live, i)
            if (returned != null) {
                player.inventory.addItem(returned).values.forEach {
                    player.world.dropItemNaturally(player.location, it)
                }
            }
            itemService.invalidateStatCache(player)
            menu.populate()
        } else {
            // 空 -> 安装
            val cursor = event.cursor
            if (cursor == null || cursor.type == Material.AIR) {
                player.sendMessage("§e光标上没有 MOD 物品")
                return
            }
            val result = modService.tryInstall(live, cursor, i)
            if (result == ModService.InstallResult.SUCCESS) {
                player.setItemOnCursor(if (cursor.amount <= 0) ItemStack(Material.AIR) else cursor)
                itemService.invalidateStatCache(player)
                menu.populate()
                playPlace(player)
            } else {
                player.sendMessage(reasonMessage(result))
                playDeny(player)
            }
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        val holder = event.inventory.holder
        if (holder !is EquipmentSelectMenu && holder !is ModInstallMenu) return
        if (event.rawSlots.any { it < event.inventory.size }) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder
        if (holder is ModInstallMenu) {
            val player = event.player as? Player ?: return
            itemService.invalidateStatCache(player)
        }
    }

    private fun reasonMessage(result: ModService.InstallResult): String {
        return when (result) {
            ModService.InstallResult.SUCCESS -> "§a安装成功"
            ModService.InstallResult.CAPACITY_EXCEEDED -> "§c容量不足，无法安装该 MOD"
            ModService.InstallResult.WRONG_CATEGORY -> "§c该 MOD 不适用于这件装备"
            ModService.InstallResult.MAX_COUNT_EXCEEDED -> "§c该 MOD 已达到本装备的最大安装数量"
            ModService.InstallResult.EXCLUSIVITY_CONFLICT -> "§c与已安装的同类 MOD 冲突"
            ModService.InstallResult.SLOT_OCCUPIED -> "§c该槽位已被占用"
            ModService.InstallResult.INVALID_MOD -> "§c光标上的物品不是有效 MOD"
            ModService.InstallResult.NOT_EQUIPMENT -> "§c目标不是 SourceForge 装备"
        }
    }

    private fun playPlace(player: Player) {
        player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.35f, 1.35f)
    }

    private fun playDeny(player: Player) {
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.45f, 0.65f)
    }
}
