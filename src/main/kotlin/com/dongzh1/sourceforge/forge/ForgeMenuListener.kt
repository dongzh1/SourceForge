package com.dongzh1.sourceforge.forge

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.config.ForgeRecipe
import com.dongzh1.sourceforge.config.RecipeMaterial
import com.dongzh1.sourceforge.item.CraftEngineHook
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

/**
 * 源质锻炉 GUI + 锻造/强化逻辑。从 ForgeListener 拆出（评审 #5），行为不变。
 */
class ForgeMenuListener(private val plugin: SourceForge) : Listener {

    // ==================== GUI 事件 ====================

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val menu = event.inventory.holder as? ForgeMenu ?: return
        val player = event.whoClicked as? Player ?: return
        val rawSlot = event.rawSlot
        if (rawSlot < 0) return

        if (event.click == ClickType.DOUBLE_CLICK) {
            event.isCancelled = true
            return
        }

        if (rawSlot >= event.inventory.size && event.action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            event.isCancelled = true
            player.sendMessage("§c[SourceForge] §f请手动把蓝图放进蓝图槽，本界面不支持 Shift 快速放入")
            playDenySound(player)
            return
        }

        if (rawSlot < event.inventory.size) {
            if (rawSlot == menu.actionSlot) {
                event.isCancelled = true
                onActionClick(player, menu)
                return
            }
            if (rawSlot != menu.blueprintSlot) {
                // 材料展示 / 产出预览等只读槽
                event.isCancelled = true
                if (menu.isReadonlySlot(rawSlot)) {
                    // 静默拒绝（产出预览/材料展示），仅当玩家试图拿取/放入时提示
                    if (event.currentItem != null || event.cursor?.type?.isAir == false) {
                        player.sendMessage("§c[SourceForge] §f这里不能放置或拿取物品")
                        playDenySound(player)
                    }
                } else {
                    player.sendMessage("§c[SourceForge] §f这里不能放置物品")
                    playDenySound(player)
                }
                return
            }
            // 蓝图槽：放行点击，1 tick 后按最新内容刷新材料展示与产出预览
            scheduleRefresh(menu, player)
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        val menu = event.inventory.holder as? ForgeMenu ?: return
        val player = event.whoClicked as? Player ?: return
        val topSlots = event.rawSlots.filter { it < event.inventory.size }
        if (topSlots.any { it != menu.blueprintSlot }) {
            event.isCancelled = true
            player.sendMessage("§c[SourceForge] §f只能把蓝图放进蓝图槽")
            playDenySound(player)
            return
        }
        scheduleRefresh(menu, player)
    }

    /** 蓝图槽内容应用后，下一 tick 重新渲染材料需求展示与产出预览。 */
    private fun scheduleRefresh(menu: ForgeMenu, player: Player) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            menu.renderMaterialsAndPreview(player)
            menu.renderAction(player)
        })
    }

    /**
     * 动作槽点击：根据当前作业状态分派。
     * - 锻造中：仅刷新（不响应）。
     * - 完成：收取产物并回到空闲态。
     * - 空闲：执行锻造启动逻辑（命令模式即时产出）。
     */
    private fun onActionClick(player: Player, menu: ForgeMenu) {
        val job = menu.currentJob()
        when {
            job != null && !job.isDone() -> {
                val seconds = Math.ceil(job.remainingTicks / 20.0).toInt()
                player.sendMessage("§e[源质锻炉] §f还在锻造中，剩余 §f${seconds}s")
                menu.renderAction(player)
            }
            job != null && job.isDone() -> {
                val core = menu.coreBlock()
                if (core == null) {
                    player.sendMessage("§c[源质锻炉] §f核心所在世界未加载")
                    playDenySound(player)
                    return
                }
                if (plugin.structureManager.collect(core, player)) {
                    player.sendMessage("§a[源质锻炉] §f已收取产物")
                    playForgeSound(player)
                    menu.renderAll(player)
                }
            }
            else -> forge(player, menu)
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val menu = event.inventory.holder as? ForgeMenu ?: return
        val player = event.player as? Player ?: return
        menu.stopProgressTask()
        val item = event.inventory.getItem(menu.blueprintSlot) ?: return
        if (item.type == Material.AIR) return
        player.inventory.addItem(item).values.forEach { player.world.dropItemNaturally(player.location, it) }
        event.inventory.setItem(menu.blueprintSlot, null)
    }

    // ==================== 锻造逻辑 ====================

    private fun forge(player: Player, menu: ForgeMenu) {
        val inv = menu.inventory
        val blueprintItem = inv.getItem(menu.blueprintSlot)
        if (blueprintItem == null || blueprintItem.type == Material.AIR) {
            player.sendMessage("§c[SourceForge] §f请放入有效蓝图或要强化的武器")
            playDenySound(player)
            return
        }
        val ceId = CraftEngineHook.itemId(blueprintItem)
        val recipe = ceId?.let { plugin.forgeConfig.recipes[it] }
        if (recipe == null) {
            // 不是蓝图：尝试武器强化
            if (plugin.itemService.isSourceEquipment(blueprintItem)) {
                enhance(player, menu, inv)
                return
            }
            player.sendMessage("§c[SourceForge] §f请放入有效蓝图或要强化的武器")
            playDenySound(player)
            return
        }

        // 校验玩家背包材料是否充足
        val shortage = recipe.materials.firstOrNull { countInInventory(player, it.ceId) < it.amount }
        if (shortage != null) {
            val have = countInInventory(player, shortage.ceId)
            val name = plugin.forgeConfig.displayName(shortage.ceId)
            player.sendMessage("§c[SourceForge] §f材料不足: §f$name §c还差 §e${shortage.amount - have} §c个")
            playDenySound(player)
            return
        }

        val ctx = menu.structureContext
        if (ctx != null) {
            // 结构模式：核心已有作业则拒绝
            val core = org.bukkit.Bukkit.getWorld(ctx.world)?.getBlockAt(ctx.x, ctx.y, ctx.z)
            if (core == null) {
                player.sendMessage("§c[SourceForge] §f锻炉核心所在世界未加载")
                playDenySound(player)
                return
            }
            if (plugin.structureManager.hasJob(core)) {
                player.sendMessage("§c[SourceForge] §f该锻炉已有作业")
                playDenySound(player)
                return
            }
            // 消耗材料 + 蓝图，并记录被消耗的 CE 物品快照（核心被破坏时退还）
            val consumedSnapshot = consumeMaterials(player, recipe.materials)
            consumeBlueprint(inv, menu.blueprintSlot)?.let { consumedSnapshot.add(it) }

            val ticks = Math.round(recipe.timeSeconds * 20.0 / ctx.multiplier).coerceAtLeast(1L)
            plugin.structureManager.submitJob(
                core = core,
                blueprintId = recipe.blueprintId,
                equipmentId = recipe.equipmentId,
                tier = recipe.tier,
                shellTier = ctx.shellTier,
                multiplier = ctx.multiplier,
                remainingTicks = ticks,
                consumedSnapshot = consumedSnapshot
            )
            val seconds = Math.ceil(ticks / 20.0).toInt()
            player.sendMessage("§a[源质锻炉] §f开始锻造，预计 §e${seconds}s")
            playForgeSound(player)
            // 不再关闭界面：动作槽切换为进度箭头，并刷新材料展示（已扣除）
            menu.renderAll(player)
            return
        }

        // 命令/管理路径：即时产出
        consumeMaterials(player, recipe.materials)
        consumeBlueprint(inv, menu.blueprintSlot)
        val result = plugin.itemService.createDirectEquipment(recipe.equipmentId, recipe.tier, null)
        if (result == null) {
            player.sendMessage("§c[SourceForge] §f配方装备不存在: ${recipe.equipmentId}")
            playDenySound(player)
            return
        }
        player.inventory.addItem(result).values.forEach { player.world.dropItemNaturally(player.location, it) }
        player.sendMessage("§a[SourceForge] §f锻造完成: §e${plugin.forgeConfig.equipmentDisplayName(recipe.equipmentId)}")
        com.dongzh1.sourceforge.api.SourceForgeActionEvent(player, com.dongzh1.sourceforge.api.SourceForgeActionEvent.Action.FORGE_COMPLETE, recipe.equipmentId).callEvent()
        playForgeSound(player)
        menu.renderAll(player)
    }

    /** 武器强化：校验下一段、扣材料、提交 enhance 作业（结构模式）或即时强化（命令模式）。 */
    private fun enhance(player: Player, menu: ForgeMenu, inv: org.bukkit.inventory.Inventory) {
        val weapon = inv.getItem(menu.blueprintSlot)
        if (weapon == null || !plugin.itemService.isSourceEquipment(weapon)) {
            player.sendMessage("§c[SourceForge] §f请放入要强化的武器")
            playDenySound(player)
            return
        }
        val category = plugin.itemService.weaponCategory(weapon)
        val level = plugin.itemService.enhanceLevel(weapon)
        val next = plugin.enhancementConfig.nextLevel(category, level)
        if (next == null) {
            player.sendMessage("§c[源质锻炉] §f该武器已满级")
            playDenySound(player)
            return
        }
        // 材料校验
        val shortage = next.materials.firstOrNull { countInInventory(player, it.ceId) < it.amount }
        if (shortage != null) {
            val have = countInInventory(player, shortage.ceId)
            val name = plugin.forgeConfig.displayName(shortage.ceId)
            player.sendMessage("§c[SourceForge] §f材料不足: §f$name §c还差 §e${shortage.amount - have} §c个")
            playDenySound(player)
            return
        }

        val ctx = menu.structureContext
        if (ctx != null) {
            val core = org.bukkit.Bukkit.getWorld(ctx.world)?.getBlockAt(ctx.x, ctx.y, ctx.z)
            if (core == null) {
                player.sendMessage("§c[SourceForge] §f锻炉核心所在世界未加载")
                playDenySound(player)
                return
            }
            if (plugin.structureManager.hasJob(core)) {
                player.sendMessage("§c[SourceForge] §f该锻炉已有作业")
                playDenySound(player)
                return
            }
            // 消耗材料；取出武器本体（含在退还快照中，核心破坏不丢失）
            val consumedSnapshot = consumeMaterials(player, next.materials)
            val weaponClone = weapon.clone()
            consumedSnapshot.add(weaponClone)
            inv.setItem(menu.blueprintSlot, null)

            val ticks = Math.round(plugin.enhancementConfig.enhanceTimeSeconds * 20.0 / ctx.multiplier).coerceAtLeast(1L)
            plugin.structureManager.submitEnhanceJob(
                core = core,
                inputWeapon = weaponClone,
                targetLevel = level + 1,
                shellTier = ctx.shellTier,
                multiplier = ctx.multiplier,
                remainingTicks = ticks,
                consumedSnapshot = consumedSnapshot
            )
            val seconds = Math.ceil(ticks / 20.0).toInt()
            player.sendMessage("§b[源质锻炉] §f开始强化，预计 §e${seconds}s")
            playForgeSound(player)
            menu.renderAll(player)
            return
        }

        // 命令模式：即时强化
        consumeMaterials(player, next.materials)
        val result = weapon.clone()
        plugin.itemService.applyEnhancement(result, level + 1, next.baseDamage, next.modCapacity)
        inv.setItem(menu.blueprintSlot, null)
        player.inventory.addItem(result).values.forEach { player.world.dropItemNaturally(player.location, it) }
        player.sendMessage("§b[SourceForge] §f强化完成: Lv.${level + 1}")
        playForgeSound(player)
        menu.renderAll(player)
    }

    /** 从玩家背包扣除材料（配方或强化通用），返回被消耗物品的克隆快照（按消耗数量）。 */
    private fun consumeMaterials(player: Player, materials: List<RecipeMaterial>): MutableList<ItemStack> {
        val snapshot = mutableListOf<ItemStack>()
        val storage = player.inventory.storageContents
        for (material in materials) {
            var remaining = material.amount
            for (i in storage.indices) {
                if (remaining <= 0) break
                val item = storage[i] ?: continue
                if (item.type == Material.AIR) continue
                if (CraftEngineHook.itemId(item) != material.ceId) continue
                val take = minOf(remaining, item.amount)
                snapshot.add(item.clone().apply { amount = take })
                item.amount -= take
                remaining -= take
                player.inventory.setItem(i, if (item.amount <= 0) null else item)
            }
        }
        return snapshot
    }

    /** 数背包内匹配 CE id 的物品数量（仅 storageContents，含快捷栏）。 */
    private fun countInInventory(player: Player, ceId: String): Int {
        var total = 0
        for (item in player.inventory.storageContents) {
            if (item == null || item.type == Material.AIR) continue
            if (CraftEngineHook.itemId(item) == ceId) total += item.amount
        }
        return total
    }

    /** 从蓝图槽消耗 1 张蓝图，返回被消耗的 1 个单位克隆（用于退还快照）。 */
    private fun consumeBlueprint(inv: org.bukkit.inventory.Inventory, slot: Int): ItemStack? {
        val item = inv.getItem(slot) ?: return null
        if (item.type == Material.AIR) return null
        val clone = item.clone().apply { amount = 1 }
        item.amount -= 1
        if (item.amount <= 0) inv.setItem(slot, null)
        return clone
    }

    private fun playDenySound(player: Player) {
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.45f, 0.65f)
    }

    private fun playForgeSound(player: Player) {
        player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 0.8f, 1.05f)
    }
}
