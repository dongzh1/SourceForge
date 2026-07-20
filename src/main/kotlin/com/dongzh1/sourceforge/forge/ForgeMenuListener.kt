package com.dongzh1.sourceforge.forge

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.config.ForgeRecipeMode
import com.dongzh1.sourceforge.config.RecipeMaterial
import com.dongzh1.sourceforge.economy.EconomyBridge
import com.dongzh1.sourceforge.item.CraftEngineHook
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.Block
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
 * 源质锻炉 GUI + 锻造/强化/重铸逻辑。从 ForgeListener 拆出（评审 #5），行为不变。
 * 2026-07-16 重构：锻造/强化/重铸三态改成三个完全独立的 [ForgeMenu] 子类实例——模式切换按钮
 * 不再原地重绘同一个 Inventory，而是关闭当前界面、[ForgeMenus.open] 打开另一模式的全新实例。
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
            player.sendMessage("§c[SourceForge] §f请手动把物品放进对应槽位，本界面不支持 Shift 快速放入")
            playDenySound(player)
            return
        }

        if (rawSlot < event.inventory.size) {
            if (rawSlot == menu.actionSlot) {
                event.isCancelled = true
                // 延后到下一tick再处理：见下方 modeToggleSlot 分支同样的注释——避开"同tick内改写
                // 被点击槽位"跟 Shift点击/数字键换栏 的竞态。
                plugin.server.scheduler.runTask(plugin, Runnable { onActionClick(player, menu) })
                return
            }
            if (rawSlot == menu.modeToggleSlot) {
                event.isCancelled = true
                // 延后到下一tick：切换模式会关闭当前界面、打开另一模式的全新 Inventory，
                // 必须避开"同tick内改写被点击槽位"跟 Shift点击/数字键换栏 的竞态(历史事故见 ForgeConfig)。
                plugin.server.scheduler.runTask(plugin, Runnable {
                    val job = menu.currentJob()
                    if (job != null && !job.isDone()) {
                        player.sendMessage("§c[源质锻炉] §f锻造中不能切换模式")
                        playDenySound(player)
                    } else {
                        val nextMode = when (menu.mode) {
                            ForgeMenu.Mode.CRAFT -> ForgeMenu.Mode.ENHANCE
                            ForgeMenu.Mode.ENHANCE -> ForgeMenu.Mode.UPGRADE
                            ForgeMenu.Mode.UPGRADE -> ForgeMenu.Mode.CRAFT
                        }
                        ForgeMenus.open(plugin, player, menu.structureContext, nextMode)
                        playForgeSound(player)
                    }
                })
                return
            }
            if (rawSlot !in menu.inputSlots()) {
                // 材料展示 / 产出预览等只读槽
                event.isCancelled = true
                if (rawSlot in menu.materialDisplaySlots) {
                    // 材料槽是自动读背包的展示位，不接受手动放入——单独提示，别让玩家以为要往这儿塞材料
                    if (event.currentItem != null || event.cursor?.type?.isAir == false) {
                        player.sendMessage("§c[SourceForge] §f材料会自动从背包识别扣除，无需放到这里")
                        playDenySound(player)
                    }
                } else if (menu.isReadonlySlot(rawSlot)) {
                    // 静默拒绝（产出预览等），仅当玩家试图拿取/放入时提示
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
            // 输入槽当前显示的是占位玻璃板(视觉上"空")时不走原版拾取/交换——
            // 否则占位板会原地跑到玩家光标上。手动吃掉占位板、把光标物品放进去。
            if (menu.isPlaceholder(event.currentItem)) {
                handlePlaceholderSlotClick(event, player, menu, rawSlot)
                return
            }
            // 槽位已经是真实物品(或本来就是空气)：放行点击，1 tick 后按最新内容刷新材料展示与产出预览
            scheduleRefresh(menu, player)
        } else {
            // 玩家自己背包(下半区)的普通点击：不限制，但也要刷新材料展示——否则玩家在自己背包里
            // 整理/合并材料后，材料槽显示的"拥有数量"会停留在最后一次点击 GUI 上半区时的旧值，
            // 看起来像是"识别不到背包变化"。
            scheduleRefresh(menu, player)
        }
    }

    /**
     * 输入槽显示占位玻璃板时的点击：不取消不了了之，而是直接把光标物品"放"进槽位，
     * 占位板本身丢弃(不还给玩家、也不留在光标上)。左键放整组，右键放 1 个，对齐原版空槽放置手感。
     * 光标为空时(玩家只是点了一下占位板)什么都不做。拖拽(InventoryDragEvent)不在此覆盖范围内，
     * 极少数情况下用 shift/多槽拖拽把物品分散拖到这些槽上时可能表现不一致，属已知的窄口子。
     */
    private fun handlePlaceholderSlotClick(event: InventoryClickEvent, player: Player, menu: ForgeMenu, slot: Int) {
        event.isCancelled = true
        val cursor = event.cursor
        if (cursor.type == Material.AIR) return
        val toPlace = if (event.click == ClickType.RIGHT) cursor.clone().apply { amount = 1 } else cursor.clone()
        event.inventory.setItem(slot, toPlace)
        val remaining = cursor.amount - toPlace.amount
        player.setItemOnCursor(if (remaining > 0) cursor.clone().apply { amount = remaining } else null)
        scheduleRefresh(menu, player)
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        val menu = event.inventory.holder as? ForgeMenu ?: return
        val player = event.whoClicked as? Player ?: return
        val topSlots = event.rawSlots.filter { it < event.inventory.size }
        if (topSlots.any { it !in menu.inputSlots() }) {
            event.isCancelled = true
            player.sendMessage("§c[SourceForge] §f只能把物品放进对应输入槽位")
            playDenySound(player)
            return
        }
        scheduleRefresh(menu, player)
    }

    /** 输入槽内容应用后，下一 tick 重新渲染材料需求展示与产出预览。 */
    private fun scheduleRefresh(menu: ForgeMenu, player: Player) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            menu.renderContent(player)
            menu.renderAction(player)
        })
    }

    /**
     * 动作槽点击：根据当前作业状态分派。
     * - 锻造中：仅刷新（不响应）。
     * - 完成：收取产物并回到空闲态。
     * - 空闲：按具体子类执行对应的锻造/强化/重铸启动逻辑（命令模式即时产出）。
     */
    private fun onActionClick(player: Player, menu: ForgeMenu) {
        val job = menu.currentJob()
        when {
            job != null && !job.isDone() -> {
                val seconds = Math.ceil(job.remainingTicks() / 20.0).toInt()
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
            else -> when (menu) {
                is ForgeCraftMenu -> forge(player, menu)
                is ForgeEnhanceMenu -> enhance(player, menu)
                is ForgeUpgradeMenu -> upgrade(player, menu)
            }
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val menu = event.inventory.holder as? ForgeMenu ?: return
        val player = event.player as? Player ?: return
        menu.stopProgressTask()
        for (slot in menu.inputSlots()) {
            // 防御性检查：输入槽理论上不应该跟只读功能槽(动作/产出/材料展示/模式切换按钮)重叠，
            // 但历史上出现过配置把两类槽位配成同一个槽位号的事故——那种情况下这里会把模式切换
            // 按钮(砧/经验瓶/下界合金升级模板)当成玩家放的真实物品，每次关闭界面白送一个，是实打实的
            // 重复物品漏洞。哪怕以后配置又不小心重叠，也绝不能把只读槽的内容当成玩家物品送出去。
            if (menu.isReadonlySlot(slot)) continue
            val item = event.inventory.getItem(slot) ?: continue
            if (item.type == Material.AIR || menu.isPlaceholder(item)) continue
            player.inventory.addItem(item).values.forEach { player.world.dropItemNaturally(player.location, it) }
            event.inventory.setItem(slot, null)
        }
    }

    // ==================== 锻造逻辑 ====================

    private fun forge(player: Player, menu: ForgeCraftMenu) {
        val recipe = menu.currentRecipe()
        if (recipe == null || recipe.mode != ForgeRecipeMode.CREATE) {
            player.sendMessage("§c[SourceForge] §f请放入有效的锻造配方蓝图")
            playDenySound(player)
            return
        }

        // 校验玩家背包材料是否充足
        val shortage = recipe.materials.firstOrNull { plugin.itemService.countInInventory(player, it.ceId) < it.amount }
        if (shortage != null) {
            val have = plugin.itemService.countInInventory(player, shortage.ceId)
            sendShortageMessage(player, shortage.ceId, have, shortage.amount)
            playDenySound(player)
            return
        }

        val ctx = menu.structureContext
        if (ctx != null) {
            submitStructureJob(player, menu, ctx, recipe.timeSeconds, "§a", "开始锻造") { core, ticks, claimed ->
                // 消耗材料 + 蓝图，并记录被消耗的 CE 物品快照（结构被拆时退还）
                val consumedSnapshot = consumeMaterials(player, recipe.materials)
                consumeBlueprint(menu.inventory, menu.blueprintSlot)?.let { consumedSnapshot.add(it) }
                plugin.structureManager.submitJob(
                    core = core,
                    blueprintId = recipe.blueprintId,
                    equipmentId = recipe.equipmentId,
                    tier = recipe.tier,
                    shellTier = ctx.shellTier,
                    multiplier = ctx.multiplier,
                    totalDurationTicks = ticks,
                    claimedBlocks = claimed,
                    consumedSnapshot = consumedSnapshot
                )
            }
            return
        }

        // 命令/管理路径：即时产出
        consumeMaterials(player, recipe.materials)
        consumeBlueprint(menu.inventory, menu.blueprintSlot)
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

    /**
     * 强化：武器走原有的等级强化；MOD 走段位强化（2026-07-17 新增，复用同一套锻炉强化流程，
     * 取代旧的独立 ModUpgradeMenu）。不消耗任何材料。
     */
    private fun enhance(player: Player, menu: ForgeEnhanceMenu) {
        val riven = menu.enhanceRiven()
        if (riven != null) {
            enhanceRiven(player, menu, riven)
            return
        }
        val mod = menu.enhanceMod()
        if (mod != null) {
            enhanceMod(player, menu, mod)
            return
        }
        val weapon = menu.weaponItem()
        if (weapon == null || !plugin.itemService.isSourceEquipment(weapon)) {
            player.sendMessage("§c[SourceForge] §f请放入要强化的武器或 MOD")
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
        // 实际扣款 = 该级花费 × 装备品阶倍率(equipment/equipment-tiers.yml；未分配品阶固定 1.0)。
        val equipmentId = plugin.itemService.weaponType(weapon)
        val cost = next.cost * plugin.forgeConfig.equipmentTiers.multiplierFor(equipmentId)
        // 强化只花钱不消耗材料(用户明确要求)：校验 Vault 余额
        if (!EconomyBridge.isAvailable()) {
            player.sendMessage("§c[源质锻炉] §f强化需要经济插件(Vault)支持，服务器未安装/未启用")
            playDenySound(player)
            return
        }
        if (!EconomyBridge.has(player, cost)) {
            player.sendMessage("§c[源质锻炉] §f金币不足，强化需要 §e${cost.toInt()} §c金币")
            playDenySound(player)
            return
        }

        val ctx = menu.structureContext
        if (ctx != null) {
            submitStructureJob(player, menu, ctx, plugin.enhancementConfig.enhanceTimeSeconds, "§b", "开始强化") { core, ticks, claimed ->
                // 扣款；取出武器本体（含在退还快照中，核心破坏不丢失——被拆时不退款，只退武器本体）
                EconomyBridge.withdraw(player, cost)
                val weaponClone = weapon.clone()
                val consumedSnapshot = mutableListOf<ItemStack>(weaponClone)
                menu.inventory.setItem(menu.weaponSlot, null)
                plugin.structureManager.submitEnhanceJob(
                    core = core,
                    inputWeapon = weaponClone,
                    targetLevel = level + 1,
                    shellTier = ctx.shellTier,
                    multiplier = ctx.multiplier,
                    totalDurationTicks = ticks,
                    claimedBlocks = claimed,
                    consumedSnapshot = consumedSnapshot
                )
            }
            return
        }

        // 命令模式：即时强化
        EconomyBridge.withdraw(player, cost)
        val result = weapon.clone()
        plugin.itemService.applyEnhancement(
            result,
            level + 1,
            next.baseDamage,
            next.modCapacity,
            next.shieldCapacity,
            next.health
        )
        menu.inventory.setItem(menu.weaponSlot, null)
        player.inventory.addItem(result).values.forEach { player.world.dropItemNaturally(player.location, it) }
        player.sendMessage("§b[SourceForge] §f强化完成: Lv.${level + 1}")
        playForgeSound(player)
        menu.renderAll(player)
    }

    /** MOD 段位强化：校验段位上限/金币，提交 enhance 作业（结构模式）或即时升级（命令模式）。不消耗材料。 */
    private fun enhanceMod(player: Player, menu: ForgeEnhanceMenu, mod: ItemStack) {
        val config = plugin.modService.modConfig(mod)
        if (config == null || config.maxRank <= 0) {
            player.sendMessage("§c[源质锻炉] §f该 MOD 不可升级段位")
            playDenySound(player)
            return
        }
        val rank = plugin.modService.modRank(mod)
        if (rank >= config.maxRank) {
            player.sendMessage("§c[源质锻炉] §f该 MOD 已满段位")
            playDenySound(player)
            return
        }
        val cost = plugin.enhancementConfig.modUpgrade.costFor(config, rank)
        if (!EconomyBridge.isAvailable()) {
            player.sendMessage("§c[源质锻炉] §f强化需要经济插件(Vault)支持，服务器未安装/未启用")
            playDenySound(player)
            return
        }
        if (!EconomyBridge.has(player, cost)) {
            player.sendMessage("§c[源质锻炉] §f金币不足，强化需要 §e${cost.toInt()} §c金币")
            playDenySound(player)
            return
        }

        val ctx = menu.structureContext
        if (ctx != null) {
            submitStructureJob(player, menu, ctx, plugin.enhancementConfig.enhanceTimeSeconds, "§b", "开始强化") { core, ticks, claimed ->
                EconomyBridge.withdraw(player, cost)
                val modClone = mod.clone()
                val consumedSnapshot = mutableListOf<ItemStack>(modClone)
                menu.inventory.setItem(menu.weaponSlot, null)
                plugin.structureManager.submitEnhanceJob(
                    core = core,
                    inputWeapon = modClone,
                    targetLevel = rank + 1,
                    shellTier = ctx.shellTier,
                    multiplier = ctx.multiplier,
                    totalDurationTicks = ticks,
                    claimedBlocks = claimed,
                    consumedSnapshot = consumedSnapshot
                )
            }
            return
        }

        // 命令模式：即时升级
        EconomyBridge.withdraw(player, cost)
        val result = plugin.modService.createModItem(config.id, mod.amount.coerceAtLeast(1), rank + 1)
        if (result == null) {
            player.sendMessage("§c[SourceForge] §f无法升级 MOD")
            return
        }
        menu.inventory.setItem(menu.weaponSlot, null)
        player.inventory.addItem(result).values.forEach { player.world.dropItemNaturally(player.location, it) }
        player.sendMessage("§b[SourceForge] §f强化完成: 段位 ${rank + 1}/${config.maxRank}")
        playForgeSound(player)
        menu.renderAll(player)
    }

    private fun enhanceRiven(player: Player, menu: ForgeEnhanceMenu, riven: ItemStack) {
        val service = plugin.rivenService
        val instance = service.parseData(riven)
        if (instance == null || !service.isUnveiled(riven)) {
            player.sendMessage("§c[源质锻炉] §f封缄彼端遗纹不可强化")
            playDenySound(player)
            return
        }
        if (service.hasPendingRoll(riven)) {
            player.sendMessage("§c[源质锻炉] §f请先确认彼端遗纹的梦织候选词条")
            playDenySound(player)
            return
        }
        if (instance.rank >= service.maxRank()) {
            player.sendMessage("§c[源质锻炉] §f该彼端遗纹已满段位")
            playDenySound(player)
            return
        }
        val cost = service.rankUpgradeCost(instance.rank)
        if (!EconomyBridge.isAvailable()) {
            player.sendMessage("§c[源质锻炉] §f强化需要经济插件(Vault)支持，服务器未安装/未启用")
            playDenySound(player)
            return
        }
        if (!EconomyBridge.has(player, cost)) {
            player.sendMessage("§c[源质锻炉] §f金币不足，强化需要 §e${cost.toInt()} §c金币")
            playDenySound(player)
            return
        }
        val targetRank = instance.rank + 1
        val ctx = menu.structureContext
        if (ctx != null) {
            submitStructureJob(player, menu, ctx, plugin.enhancementConfig.enhanceTimeSeconds, "§5", "开始强化") { core, ticks, claimed ->
                EconomyBridge.withdraw(player, cost)
                val rivenClone = riven.clone()
                val consumedSnapshot = mutableListOf<ItemStack>(rivenClone)
                menu.inventory.setItem(menu.weaponSlot, null)
                plugin.structureManager.submitEnhanceJob(
                    core = core,
                    inputWeapon = rivenClone,
                    targetLevel = targetRank,
                    shellTier = ctx.shellTier,
                    multiplier = ctx.multiplier,
                    totalDurationTicks = ticks,
                    claimedBlocks = claimed,
                    consumedSnapshot = consumedSnapshot
                )
            }
            return
        }
        EconomyBridge.withdraw(player, cost)
        val result = service.withRank(riven.clone(), targetRank)
        if (result == null) {
            player.sendMessage("§c[源质锻炉] §f无法强化彼端遗纹")
            playDenySound(player)
            return
        }
        menu.inventory.setItem(menu.weaponSlot, null)
        player.inventory.addItem(result).values.forEach { player.world.dropItemNaturally(player.location, it) }
        player.sendMessage("§5[彼端遗纹] §f强化完成: 段位 $targetRank/${service.maxRank()}")
        playForgeSound(player)
        menu.renderAll(player)
    }

    /**
     * 蓝图原地重铸：蓝图放蓝图槽，待重铸的武器放 upgradeWeaponSlot（两者都在锻炉GUI里可见，
     * 跟锻造/强化一样必须通过多方块锻炉核心方块打开界面操作——命令模式仅用于管理员即时测试）。
     */
    private fun upgrade(player: Player, menu: ForgeUpgradeMenu) {
        val recipe = menu.currentRecipe()
        if (recipe == null || recipe.mode != ForgeRecipeMode.UPGRADE) {
            player.sendMessage("§c[SourceForge] §f请放入有效的重铸配方蓝图")
            playDenySound(player)
            return
        }
        val weapon = menu.upgradeWeaponItem()
        if (weapon == null) {
            player.sendMessage("§c[SourceForge] §f请在武器槽放入要重铸的武器")
            playDenySound(player)
            return
        }
        val target = plugin.forgeConfig.equipment[recipe.equipmentId]
        if (target == null) {
            player.sendMessage("§c[SourceForge] §f配方目标装备不存在: ${recipe.equipmentId}")
            playDenySound(player)
            return
        }
        // 重铸输入接受两类：①已锻造的同类SF装备(按类别+等级校验，重铸后保留已装MOD)；
        // ②配方目标要求的原版基材(如 duskgold_helmet 要求 NETHERITE_HELMET，视为全新胚子，
        // 重铸后保留原版附魔，无等级门槛——原版物品没有 tier 概念)。
        val requiredCategory = recipe.requiresWeaponCategory ?: target.weaponCategory
        if (plugin.itemService.isSourceEquipment(weapon)) {
            val weaponCategory = plugin.itemService.weaponCategory(weapon)
            if (!requiredCategory.equals(weaponCategory, ignoreCase = true)) {
                player.sendMessage("§c[SourceForge] §f这张蓝图只能用于 §e$requiredCategory §c类型的武器")
                playDenySound(player)
                return
            }
            val weaponTier = plugin.itemService.equipmentTier(weapon)
            if (weaponTier < recipe.minTier) {
                player.sendMessage("§c[SourceForge] §f武器等级不足：需要 tier §e${recipe.minTier} §c及以上，当前 tier §e$weaponTier")
                playDenySound(player)
                return
            }
        } else if (weapon.type != target.material) {
            player.sendMessage("§c[SourceForge] §f这张蓝图需要放入 §e${target.material.name} §c或对应已锻造装备")
            playDenySound(player)
            return
        }
        val shortage = recipe.materials.firstOrNull { plugin.itemService.countInInventory(player, it.ceId) < it.amount }
        if (shortage != null) {
            val have = plugin.itemService.countInInventory(player, shortage.ceId)
            sendShortageMessage(player, shortage.ceId, have, shortage.amount)
            playDenySound(player)
            return
        }

        val ctx = menu.structureContext
        if (ctx != null) {
            submitStructureJob(player, menu, ctx, recipe.timeSeconds, "§d", "开始重铸") { core, ticks, claimed ->
                val consumedSnapshot = consumeMaterials(player, recipe.materials)
                consumeBlueprint(menu.inventory, menu.blueprintSlot)?.let { consumedSnapshot.add(it) }
                val weaponClone = weapon.clone()
                consumedSnapshot.add(weaponClone)
                menu.inventory.setItem(menu.upgradeWeaponSlot, null)
                plugin.structureManager.submitUpgradeJob(
                    core = core,
                    inputWeapon = weaponClone,
                    targetEquipmentId = recipe.equipmentId,
                    targetTier = recipe.tier,
                    shellTier = ctx.shellTier,
                    multiplier = ctx.multiplier,
                    totalDurationTicks = ticks,
                    claimedBlocks = claimed,
                    consumedSnapshot = consumedSnapshot
                )
            }
            return
        }

        // 命令/管理路径：即时重铸
        consumeMaterials(player, recipe.materials)
        consumeBlueprint(menu.inventory, menu.blueprintSlot)
        val result = plugin.itemService.reforgeEquipment(weapon, target, recipe.tier)
        if (result == null) {
            player.sendMessage("§c[SourceForge] §f重铸失败")
            playDenySound(player)
            return
        }
        menu.inventory.setItem(menu.upgradeWeaponSlot, null)
        player.inventory.addItem(result).values.forEach { player.world.dropItemNaturally(player.location, it) }
        player.sendMessage("§d[SourceForge] §f重铸完成: §e${plugin.forgeConfig.equipmentDisplayName(recipe.equipmentId)}")
        com.dongzh1.sourceforge.api.SourceForgeActionEvent(player, com.dongzh1.sourceforge.api.SourceForgeActionEvent.Action.UPGRADE, recipe.equipmentId).callEvent()
        playForgeSound(player)
        menu.renderAll(player)
    }

    /**
     * 结构模式作业提交的共同骨架：核心世界校验 + hasJob 判重 + 耗时/占用清单计算 + 提交后的提示音效刷新。
     * forge()/enhance()/upgrade() 三处原先各自拷贝一份这套骨架，容易在只改其中一处时漏改另外两处
     * （比如曾经就漏过 hasJob 判重）；现在统一走这里，[onSubmit] 只负责"扣料 + 调用对应的 submit*Job"。
     */
    private fun submitStructureJob(
        player: Player,
        menu: ForgeMenu,
        ctx: ForgeMenu.StructureContext,
        timeSeconds: Double,
        startColor: String,
        startVerb: String,
        onSubmit: (core: Block, ticks: Long, claimed: List<Long>) -> Unit
    ) {
        val core = Bukkit.getWorld(ctx.world)?.getBlockAt(ctx.x, ctx.y, ctx.z)
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
        val ticks = Math.round(timeSeconds * 20.0 / ctx.multiplier).coerceAtLeast(1L)
        val claimed = plugin.structureManager.claimedBlocksFor(core, ctx.direction)
        onSubmit(core, ticks, claimed)
        val seconds = Math.ceil(ticks / 20.0).toInt()
        player.sendMessage("$startColor[源质锻炉] §f$startVerb，预计 §e${seconds}s")
        playForgeSound(player)
        // 不再关闭界面：动作槽切换为进度箭头，并刷新材料展示（已扣除）
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
                if (!CraftEngineHook.matches(item, material.ceId)) continue
                val take = minOf(remaining, item.amount)
                snapshot.add(item.clone().apply { amount = take })
                item.amount -= take
                remaining -= take
                player.inventory.setItem(i, if (item.amount <= 0) null else item)
            }
        }
        return snapshot
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

    /** 材料不足提示：物品名走 CE/原版翻译 Component(见 ForgeConfig.bareNameComponent)，
     * 原版材料名交给客户端本地化渲染，不在服务端硬编码中文。 */
    private fun sendShortageMessage(player: Player, ceId: String, have: Int, need: Int) {
        val message = net.kyori.adventure.text.Component.text("[SourceForge] ", net.kyori.adventure.text.format.NamedTextColor.RED)
            .append(net.kyori.adventure.text.Component.text("材料不足: ", net.kyori.adventure.text.format.NamedTextColor.WHITE))
            .append(plugin.forgeConfig.bareNameComponent(ceId).color(net.kyori.adventure.text.format.NamedTextColor.WHITE))
            .append(net.kyori.adventure.text.Component.text(" 还差 ", net.kyori.adventure.text.format.NamedTextColor.RED))
            .append(net.kyori.adventure.text.Component.text((need - have).toString(), net.kyori.adventure.text.format.NamedTextColor.YELLOW))
            .append(net.kyori.adventure.text.Component.text(" 个", net.kyori.adventure.text.format.NamedTextColor.RED))
        player.sendMessage(message)
    }

    private fun playDenySound(player: Player) {
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.45f, 0.65f)
    }

    private fun playForgeSound(player: Player) {
        player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 0.8f, 1.05f)
    }
}
