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
 * 2026-07-17：MOD 段位升级已并入锻炉"强化"流程（见 ForgeEnhanceMenu/ForgeMenuListener.enhanceMod），
 * 不再有独立的 ModUpgradeMenu/升级核心实物消耗，本类不再处理升级点击。
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
        // 需要环绕书架达标才能开抽奖界面（类似原版顶级附魔台）
        val required = plugin.lotteryConfig.requiredBookshelves
        if (required > 0) {
            val have = countBookshelves(block)
            if (have < required) {
                player.sendMessage("§c[源质抽奖] §f需要 §e$required §f个书架环绕（当前 §e$have§f）—— 在附魔台周围摆满 5×5 书架")
                playDeny(player)
                return
            }
        }
        player.openInventory(LotteryMenu(plugin, player).inventory)
    }

    /**
     * 统计附魔台周围的书架数：5×5 外圈（水平距离=±2 的 16 个格）在附魔台同层与上一层(dy 0/1)统计 BOOKSHELF。
     * 与原版"顶级附魔台"的书架环绕形态一致，达到所需数量即可。
     */
    private fun countBookshelves(table: org.bukkit.block.Block): Int {
        val world = table.world
        var count = 0
        for (dy in 0..1) {
            for (dx in -2..2) {
                for (dz in -2..2) {
                    if (kotlin.math.max(kotlin.math.abs(dx), kotlin.math.abs(dz)) != 2) continue // 仅 5×5 外圈
                    if (world.getBlockAt(table.x + dx, table.y + dy, table.z + dz).type == Material.BOOKSHELF) count++
                }
            }
        }
        return count
    }

    // ==================== 点击分派 ====================

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        when (val holder = event.inventory.holder) {
            is LotteryMenu -> handleLotteryClick(event, holder)
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

    // ==================== 拖拽与关闭 ====================

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        val holder = event.inventory.holder as? LotteryMenu ?: return
        val inputSlot = holder.inputSlot
        if (event.rawSlots.any { it < event.inventory.size && it != inputSlot }) {
            event.isCancelled = true
        } else {
            (event.whoClicked as? Player)?.let {
                plugin.server.scheduler.runTask(plugin, Runnable { holder.render() })
            }
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val holder = event.inventory.holder as? LotteryMenu ?: return
        val item = event.inventory.getItem(holder.inputSlot) ?: return
        if (item.type == Material.AIR) return
        player.inventory.addItem(item).values.forEach { player.world.dropItemNaturally(player.location, it) }
        event.inventory.setItem(holder.inputSlot, null)
    }

    private fun playDeny(player: Player) {
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.45f, 0.65f)
    }
}
