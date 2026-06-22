package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import kotlin.random.Random

/**
 * 处理：
 * - 附魔台劫持 -> 打开抽奖界面（Feature C）
 * - LotteryMenu 点击（抽取）
 * - ModUpgradeMenu 点击（升级）
 */
class LotteryListener(
    private val plugin: SourceForge
) : Listener {

    // ==================== 附魔台劫持 ====================

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.ENCHANTING_TABLE) return
        val player = event.player
        if (player.isSneaking) return
        event.isCancelled = true
        player.openInventory(LotteryMenu(plugin, player).inventory)
    }

    // ==================== 点击分派 ====================

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        when (val holder = event.inventory.holder) {
            is LotteryMenu -> handleLotteryClick(event, holder)
            is ModUpgradeMenu -> handleUpgradeClick(event, holder)
            else -> return
        }
    }

    private fun handleLotteryClick(event: InventoryClickEvent, menu: LotteryMenu) {
        val player = event.whoClicked as? Player ?: return
        if (event.click == ClickType.DOUBLE_CLICK) {
            event.isCancelled = true
            return
        }
        val rawSlot = event.rawSlot
        if (rawSlot >= event.inventory.size) {
            if (event.action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                event.isCancelled = true
                player.sendMessage("§c[SourceForge] §f请手动把空白 MOD 放入输入槽")
                playDeny(player)
            }
            return
        }
        when (rawSlot) {
            menu.inputSlot -> {
                // 放行；下一 tick 刷新
                plugin.server.scheduler.runTask(plugin, Runnable { menu.render() })
            }
            menu.closeSlot -> {
                event.isCancelled = true
                player.closeInventory()
            }
            menu.drawSlot -> {
                event.isCancelled = true
                doDraw(player, menu)
            }
            else -> event.isCancelled = true
        }
    }

    private fun doDraw(player: Player, menu: LotteryMenu) {
        val input = menu.inputItem()
        if (!menu.isBlankMod(input)) {
            player.sendMessage("§c[SourceForge] §f请放入空白 MOD")
            playDeny(player)
            return
        }
        val xpCost = plugin.lotteryConfig.xpCost
        if (player.level < xpCost) {
            player.sendMessage("§c[SourceForge] §f经验等级不足，需要 $xpCost 级")
            playDeny(player)
            return
        }
        val mods = plugin.modService.mods.values.filter { it.weight > 0.0 }
        if (mods.isEmpty()) {
            player.sendMessage("§c[SourceForge] §f当前没有可抽取的 MOD")
            playDeny(player)
            return
        }
        // 消耗 1 个空白 MOD + 扣经验
        val blank = input!!
        blank.amount -= 1
        if (blank.amount <= 0) menu.inventory.setItem(menu.inputSlot, null)
        player.level -= xpCost

        val picked = weightedPick(mods)
        val reward = plugin.modService.createModItem(picked.id, 1, 0)
        if (reward == null) {
            player.sendMessage("§c[SourceForge] §f无法生成 MOD: ${picked.id}")
            return
        }
        player.inventory.addItem(reward).values.forEach { player.world.dropItemNaturally(player.location, it) }
        player.sendMessage("§a[源质抽奖] §f你抽到了: §e${plugin.modService.mods[picked.id]?.displayName ?: picked.id}")
        player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.4f)
        menu.render()
    }

    private fun weightedPick(mods: List<ModConfig>): ModConfig {
        val total = mods.sumOf { it.weight }
        if (total <= 0.0) return mods.random()
        var roll = Random.nextDouble(total)
        for (mod in mods) {
            roll -= mod.weight
            if (roll <= 0.0) return mod
        }
        return mods.last()
    }

    private fun handleUpgradeClick(event: InventoryClickEvent, menu: ModUpgradeMenu) {
        val player = event.whoClicked as? Player ?: return
        if (event.click == ClickType.DOUBLE_CLICK) {
            event.isCancelled = true
            return
        }
        val rawSlot = event.rawSlot
        if (rawSlot >= event.inventory.size) {
            if (event.action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                event.isCancelled = true
                player.sendMessage("§c[SourceForge] §f请手动把 MOD 放入升级槽")
                playDeny(player)
            }
            return
        }
        when (rawSlot) {
            menu.modSlot -> {
                plugin.server.scheduler.runTask(plugin, Runnable { menu.render() })
            }
            menu.closeSlot -> {
                event.isCancelled = true
                player.closeInventory()
            }
            menu.upgradeSlot -> {
                event.isCancelled = true
                doUpgrade(player, menu)
            }
            else -> event.isCancelled = true
        }
    }

    private fun doUpgrade(player: Player, menu: ModUpgradeMenu) {
        val mod = menu.modItem()
        if (mod == null || !plugin.modService.isModItem(mod)) {
            player.sendMessage("§c[SourceForge] §f请放入要升级的 MOD")
            playDeny(player)
            return
        }
        val id = plugin.modService.modId(mod) ?: return
        val config = plugin.modService.mods[id]
        if (config == null || config.maxRank <= 0) {
            player.sendMessage("§c[SourceForge] §f该 MOD 不可升级")
            playDeny(player)
            return
        }
        val rank = plugin.modService.modRank(mod)
        if (rank >= config.maxRank) {
            player.sendMessage("§c[SourceForge] §f该 MOD 已满段位")
            playDeny(player)
            return
        }
        val need = config.upgradeCostFor(rank)
        if (menu.countCores() < need) {
            player.sendMessage("§c[SourceForge] §f升级核心不足，需要 $need 个")
            playDeny(player)
            return
        }
        // 消耗升级核心
        consumeCores(player, menu, need)
        // 重建升级后的 MOD 物品（保持数量）
        val upgraded = plugin.modService.createModItem(id, mod.amount.coerceAtLeast(1), rank + 1)
        if (upgraded == null) {
            player.sendMessage("§c[SourceForge] §f无法升级 MOD")
            return
        }
        menu.inventory.setItem(menu.modSlot, upgraded)
        player.sendMessage("§a[MOD 升级] §f${config.displayName} §f段位 $rank → ${rank + 1}")
        player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 0.7f, 1.2f)
        menu.render()
    }

    private fun consumeCores(player: Player, menu: ModUpgradeMenu, amount: Int) {
        var remaining = amount
        val storage = player.inventory.storageContents
        for (i in storage.indices) {
            if (remaining <= 0) break
            val item = storage[i] ?: continue
            if (!menu.isUpgradeCore(item)) continue
            val take = minOf(remaining, item.amount)
            item.amount -= take
            remaining -= take
            player.inventory.setItem(i, if (item.amount <= 0) null else item)
        }
    }

    // ==================== 拖拽与关闭 ====================

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        val holder = event.inventory.holder
        if (holder !is LotteryMenu && holder !is ModUpgradeMenu) return
        val inputSlot = when (holder) {
            is LotteryMenu -> holder.inputSlot
            is ModUpgradeMenu -> holder.modSlot
            else -> -1
        }
        if (event.rawSlots.any { it < event.inventory.size && it != inputSlot }) {
            event.isCancelled = true
        } else {
            (event.whoClicked as? Player)?.let { p ->
                plugin.server.scheduler.runTask(plugin, Runnable {
                    when (holder) {
                        is LotteryMenu -> holder.render()
                        is ModUpgradeMenu -> holder.render()
                        else -> {}
                    }
                })
            }
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        when (val holder = event.inventory.holder) {
            is LotteryMenu -> {
                val item = event.inventory.getItem(holder.inputSlot) ?: return
                if (item.type == Material.AIR) return
                player.inventory.addItem(item).values.forEach { player.world.dropItemNaturally(player.location, it) }
                event.inventory.setItem(holder.inputSlot, null)
            }
            is ModUpgradeMenu -> {
                val item = event.inventory.getItem(holder.modSlot) ?: return
                if (item.type == Material.AIR) return
                player.inventory.addItem(item).values.forEach { player.world.dropItemNaturally(player.location, it) }
                event.inventory.setItem(holder.modSlot, null)
            }
            else -> {}
        }
    }

    private fun playDeny(player: Player) {
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.45f, 0.65f)
    }
}
