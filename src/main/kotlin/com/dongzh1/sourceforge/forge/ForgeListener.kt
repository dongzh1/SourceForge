package com.dongzh1.sourceforge.forge

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.config.ForgeRecipe
import com.dongzh1.sourceforge.item.CraftEngineHook
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import kotlin.random.Random

class ForgeListener(
    private val plugin: SourceForge
) : Listener {
    private val skillDamageKey = NamespacedKey(plugin, "skill_damage")
    private val shieldCurrentKey = NamespacedKey(plugin, "shield_current")
    private val shieldLastDamageKey = NamespacedKey(plugin, "shield_last_damage")
    private val energyCurrentKey = NamespacedKey(plugin, "energy_current")

    init {
        startShieldRegen()
    }

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

    // ==================== 投射物事件 ====================

    @EventHandler(ignoreCancelled = true)
    fun onShoot(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        val weapon = event.bow ?: return
        if (!plugin.itemService.isSourceEquipment(weapon)) return
        val projectile = event.projectile as? Projectile ?: return
        plugin.itemService.markProjectile(projectile, weapon)
        if (plugin.forgeConfig.debugCombat) {
            player.sendMessage("§8[SourceForge Debug] §7远程武器已注入词条数据: ${weapon.type.name}")
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        val projectile = event.entity
        val player = projectile.shooter as? Player ?: return
        val weapon = player.inventory.itemInMainHand
        if (!plugin.itemService.isSourceEquipment(weapon)) return
        if (plugin.itemService.isSourceProjectile(projectile)) return
        if (weapon.type !in setOf(Material.TRIDENT, Material.SNOWBALL, Material.EGG)) return
        plugin.itemService.markProjectile(projectile, weapon)
        if (plugin.forgeConfig.debugCombat) {
            player.sendMessage("§8[SourceForge Debug] §7投射武器已注入词条数据: ${weapon.type.name}")
        }
    }

    // ==================== 伤害事件 ====================

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val target = event.entity as? LivingEntity ?: return
        var sourceDamage: Float? = null
        when (val damager = event.damager) {
            is Player -> {
                val weapon = damager.inventory.itemInMainHand
                val isSfWeapon = plugin.itemService.isSourceEquipment(weapon)
                val hasSfArmor = plugin.itemService.hasSourceArmor(damager)
                if (isSfWeapon || hasSfArmor) {
                    if (isSfWeapon) plugin.itemService.stripVanillaEnchantments(weapon)
                    sourceDamage = applyCombat(
                        player = damager,
                        target = target,
                        weapon = weapon.takeIf { isSfWeapon },
                        baseDamage = event.damage
                    ) { event.damage = it }
                }
            }
            is Projectile -> {
                val player = damager.shooter as? Player
                if (player != null && plugin.itemService.isSourceProjectile(damager)) {
                    sourceDamage = applyCombat(
                        player = player,
                        target = target,
                        weapon = null,
                        projectilePdc = damager.persistentDataContainer,
                        baseDamage = event.damage
                    ) { event.damage = it }
                }
            }
            else -> Unit
        }
        applyShield(target, event)
        applyDefense(target, event)
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    fun onGenericDamage(event: EntityDamageEvent) {
        if (event is EntityDamageByEntityEvent) return
        val target = event.entity as? LivingEntity ?: return
        applyShield(target, event)
        applyDefense(target, event)
    }

    // ==================== 战斗计算 ====================

    /**
     * 应用SourceForge攻击方属性
     * 读取 base_damage, critical_chance, critical_damage
     * 暴击判定，计算最终伤害
     */
    private fun applyCombat(
        player: Player,
        target: LivingEntity,
        weapon: ItemStack?,
        projectilePdc: org.bukkit.persistence.PersistentDataContainer? = null,
        baseDamage: Double,
        applyDamage: (Double) -> Unit
    ): Float {
        // 读取词条值。武器路径只克隆一次 itemMeta，避免每个词条都克隆。
        val weaponPdc = weapon?.itemMeta?.persistentDataContainer
        val readValue: (String) -> Double = when {
            projectilePdc != null -> { affixId -> plugin.itemService.readAffixValue(projectilePdc, affixId) }
            weaponPdc != null -> { affixId -> plugin.itemService.readAffixValue(weaponPdc, affixId) }
            else -> {
                // 无SF武器但有SF防具 — 从全身读取（走缓存）
                { affixId -> plugin.itemService.readTotalAffix(player, affixId) }
            }
        }

        val configuredBaseDamage = readValue("base_damage")
        val critChance = readValue("critical_chance")
        val critDamageBonus = readValue("critical_damage")
        val abilityStrength = plugin.itemService.readTotalAffix(player, "ability_strength")

        // 基础伤害：优先用词条值，否则用原版基础
        var totalDamage = if (configuredBaseDamage > 0.0) {
            configuredBaseDamage
        } else {
            baseDamage
        }

        // 技能强度加成
        totalDamage *= (1.0 + abilityStrength)

        // 暴击判定
        var critTriggered = false
        if (critChance > 0.0 && Random.nextDouble() < critChance) {
            critTriggered = true
            val multiplier = 1.0 + if (critDamageBonus > 0.0) critDamageBonus else 0.5
            totalDamage *= multiplier
        }

        applyDamage(totalDamage)

        if (plugin.forgeConfig.debugCombat) {
            player.sendMessage(
                "§8[SourceForge Debug] §7基础=${"%.2f".format(baseDamage)}, " +
                    "配置=${"%.2f".format(configuredBaseDamage)}, " +
                    "强度=${"%.2f".format(abilityStrength)}, " +
                    "暴击=${"%.2f".format(critChance)}(${if (critTriggered) "触发" else "未触发"}), " +
                    "倍率=${"%.2f".format(critDamageBonus)}, " +
                    "最终=${"%.2f".format(totalDamage)}"
            )
        }

        return totalDamage.toFloat()
    }

    /**
     * 防御结算：
     * - 读取目标护甲值 (Attribute.ARMOR)
     * - 使用攻防公式: attackPower / (attackPower + armor)
     * - 所有原版减伤阶段归零
     */
    private fun applyDefense(target: LivingEntity, event: EntityDamageEvent) {
        // 自定义护甲减伤公式只对玩家生效；非玩家实体保持原版减伤
        if (target !is Player) return
        // 技能伤害直接跳过
        if (target.persistentDataContainer.has(skillDamageKey, PersistentDataType.INTEGER)) {
            setCustomDamage(event, event.damage)
            return
        }

        val incoming = event.damage
        val armor = target.getAttribute(Attribute.ARMOR)?.value ?: 0.0
        val attackPower = attackPower(event, incoming)

        val defended = calculateDefendedDamage(incoming, attackPower, armor)
        setCustomDamage(event, defended)

        if (plugin.forgeConfig.debugCombat) {
            val msg = "§8[SourceForge Debug] §7防御结算: " +
                "攻击力=${"%.2f".format(attackPower)}, " +
                "护甲=${"%.2f".format(armor)}, " +
                "伤害=${"%.2f".format(incoming)} -> ${"%.2f".format(defended)}"
            (target as? Player)?.sendMessage(msg)
        }
    }

    private fun calculateDefendedDamage(rawDamage: Double, attackPower: Double, defense: Double): Double {
        if (rawDamage <= 0.0) return 0.0
        if (defense <= 0.0) return maxOf(1.0, rawDamage)
        val floor = plugin.forgeConfig.combat.defenseFloor
        val effectiveAttack = attackPower.coerceAtLeast(0.0)
        val multiplier = maxOf(floor, effectiveAttack / (effectiveAttack + defense))
        return maxOf(1.0, rawDamage * multiplier)
    }

    private fun attackPower(event: EntityDamageEvent, rawDamage: Double): Double {
        val source = event.damageSource.causingEntity as? LivingEntity
            ?: event.damageSource.directEntity as? LivingEntity
            ?: ((event as? EntityDamageByEntityEvent)?.damager as? Projectile)?.shooter as? LivingEntity
            ?: (event as? EntityDamageByEntityEvent)?.damager as? LivingEntity
        val attributeDamage = source?.getAttribute(Attribute.ATTACK_DAMAGE)?.value ?: 0.0
        return maxOf(1.0, rawDamage, attributeDamage)
    }

    @Suppress("DEPRECATION")
    private fun setCustomDamage(event: EntityDamageEvent, damage: Double) {
        event.damage = damage.coerceAtLeast(0.0)
        for (modifier in VANILLA_REDUCTION_MODIFIERS) {
            try {
                if (event.isApplicable(modifier)) {
                    event.setDamage(modifier, 0.0)
                }
            } catch (_: IllegalArgumentException) {
            } catch (_: UnsupportedOperationException) {
            }
        }
    }

    // ==================== 护盾系统 ====================

    private fun applyShield(target: LivingEntity, event: EntityDamageEvent) {
        val player = target as? Player ?: return
        val maxShield = plugin.itemService.readDisplayTotalAffix(player, "shield_capacity")
        val currentShield = getCurrentShield(player, maxShield)
        // 记录受伤时间（用于脱战回复）
        player.persistentDataContainer.set(shieldLastDamageKey, PersistentDataType.LONG, System.currentTimeMillis())
        if (currentShield <= 0.0) return
        val absorbed = minOf(event.damage, currentShield)
        val remaining = event.damage - absorbed
        setCurrentShield(player, currentShield - absorbed, maxShield)
        event.damage = remaining
        if (plugin.forgeConfig.debugCombat) {
            player.sendMessage("§8[SourceForge Debug] §7护盾: ${"%.1f".format(absorbed)} 吸收, 剩余=${"%.1f".format(currentShield - absorbed)}/${"%.1f".format(maxShield)}, 穿透=${"%.1f".format(remaining)}")
        }
    }

    fun getCurrentShieldPublic(player: Player): Double {
        return getCurrentShield(player, 10.0 + plugin.itemService.readTotalAffix(player, "shield_capacity"))
    }

    private fun getCurrentShield(player: Player, maxShield: Double): Double {
        val pdc = player.persistentDataContainer
        val stored = pdc.get(shieldCurrentKey, PersistentDataType.DOUBLE)
        return if (stored == null) {
            setCurrentShield(player, maxShield, maxShield)
            maxShield
        } else {
            stored.coerceAtMost(maxShield)
        }
    }

    private fun setCurrentShield(player: Player, value: Double, maxShield: Double) {
        player.persistentDataContainer.set(shieldCurrentKey, PersistentDataType.DOUBLE, value.coerceIn(0.0, maxShield))
    }

    // ==================== 能量系统 ====================

    fun getEnergyMax(player: Player): Double {
        return plugin.itemService.readDisplayTotalAffix(player, "energy_max")
    }

    fun getEnergyCurrent(player: Player): Double {
        val max = getEnergyMax(player)
        val stored = player.persistentDataContainer.get(energyCurrentKey, PersistentDataType.DOUBLE)
        return if (stored == null) {
            setEnergy(player, max)
            max
        } else {
            stored.coerceAtMost(max)
        }
    }

    fun setEnergy(player: Player, value: Double) {
        val max = getEnergyMax(player)
        player.persistentDataContainer.set(energyCurrentKey, PersistentDataType.DOUBLE, value.coerceIn(0.0, max))
    }

    fun deductEnergy(player: Player, amount: Double): Boolean {
        val current = getEnergyCurrent(player)
        if (current < amount) return false
        setEnergy(player, current - amount)
        return true
    }

    @EventHandler
    fun onJoinInitEnergy(event: org.bukkit.event.player.PlayerJoinEvent) {
        val player = event.player
        val max = getEnergyMax(player)
        if (max > 0) {
            getEnergyCurrent(player) // triggers init to max
        }
    }

    private fun startShieldRegen() {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            for (player in plugin.server.onlinePlayers) {
                tickShieldRegen(player)
            }
        }, 3L, 3L) // 每 3 tick (0.15s)，20 次回满 = 3 秒
    }

    private fun tickShieldRegen(player: Player) {
        val maxShield = plugin.itemService.readDisplayTotalAffix(player, "shield_capacity")
        val current = getCurrentShield(player, maxShield)
        if (current >= maxShield) return
        val lastDamage = player.persistentDataContainer.get(shieldLastDamageKey, PersistentDataType.LONG) ?: 0L
        val elapsed = System.currentTimeMillis() - lastDamage
        if (elapsed < 5000) return // 受伤后 5 秒内不回复
        val regen = maxShield / 20.0 // 每次 1/20，20 次回满
        val newValue = minOf(maxShield, current + regen)
        setCurrentShield(player, newValue, maxShield)
        if (plugin.forgeConfig.debugCombat && (newValue.toInt() % 10 == 0 || newValue >= maxShield || current < regen)) {
            player.sendMessage("§8[SourceForge Debug] §7护盾回复: +${"%.1f".format(regen)}, ${"%.1f".format(newValue)}/${"%.1f".format(maxShield)}")
        }
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
            val consumedSnapshot = consumeMaterials(player, recipe)
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
        consumeMaterials(player, recipe)
        consumeBlueprint(inv, menu.blueprintSlot)
        val result = plugin.itemService.createDirectEquipment(recipe.equipmentId, recipe.tier, null)
        if (result == null) {
            player.sendMessage("§c[SourceForge] §f配方装备不存在: ${recipe.equipmentId}")
            playDenySound(player)
            return
        }
        player.inventory.addItem(result).values.forEach { player.world.dropItemNaturally(player.location, it) }
        player.sendMessage("§a[SourceForge] §f锻造完成: §e${plugin.forgeConfig.equipmentDisplayName(recipe.equipmentId)}")
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
            val consumedSnapshot = consumeEnhanceMaterials(player, next.materials)
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
        consumeEnhanceMaterials(player, next.materials)
        val result = weapon.clone()
        plugin.itemService.applyEnhancement(result, level + 1, next.baseDamage, next.modCapacity)
        inv.setItem(menu.blueprintSlot, null)
        player.inventory.addItem(result).values.forEach { player.world.dropItemNaturally(player.location, it) }
        player.sendMessage("§b[SourceForge] §f强化完成: Lv.${level + 1}")
        playForgeSound(player)
        menu.renderAll(player)
    }

    /** 扣除强化材料，返回被消耗物品克隆快照。 */
    private fun consumeEnhanceMaterials(player: Player, materials: List<com.dongzh1.sourceforge.config.RecipeMaterial>): MutableList<ItemStack> {
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

    /** 从玩家背包扣除配方材料，返回被消耗物品的克隆快照（按消耗数量）。 */
    private fun consumeMaterials(player: Player, recipe: ForgeRecipe): MutableList<ItemStack> {
        val snapshot = mutableListOf<ItemStack>()
        val storage = player.inventory.storageContents
        for (material in recipe.materials) {
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

    // ==================== 辅助方法 ====================

    private fun playDenySound(player: Player) {
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.45f, 0.65f)
    }

    private fun playForgeSound(player: Player) {
        player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 0.8f, 1.05f)
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

    companion object {
        @Suppress("DEPRECATION")
        private val VANILLA_REDUCTION_MODIFIERS = listOf(
            EntityDamageEvent.DamageModifier.INVULNERABILITY_REDUCTION,
            EntityDamageEvent.DamageModifier.FREEZING,
            EntityDamageEvent.DamageModifier.HARD_HAT,
            EntityDamageEvent.DamageModifier.BLOCKING,
            EntityDamageEvent.DamageModifier.ARMOR,
            EntityDamageEvent.DamageModifier.RESISTANCE,
            EntityDamageEvent.DamageModifier.MAGIC
        )
    }
}
