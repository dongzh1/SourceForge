package com.dongzh1.sourceforge.forge

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.config.BlueprintConfig
import com.dongzh1.sourceforge.config.MaterialRequirement
import com.dongzh1.sourceforge.item.ItemMatcher
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
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
    private val commandDamageTypeKey = NamespacedKey(plugin, "command_damage_type")

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
            player.sendMessage("§c[SourceForge] §f请手动放入对应区域，锻造界面不支持 Shift 快速放入")
            playDenySound(player)
            return
        }

        if (rawSlot < event.inventory.size) {
            if (rawSlot == menu.forgeSlot) {
                event.isCancelled = true
                forge(player, menu)
                return
            }
            if (rawSlot !in menu.inputSlots) {
                event.isCancelled = true
                player.sendMessage("§c[SourceForge] §f这里是背景板，不能放置物品")
                playDenySound(player)
                return
            }

            val incoming = incomingItem(event, player)
            if (incoming != null && !isAllowedInSlot(menu, rawSlot, incoming)) {
                event.isCancelled = true
                player.sendMessage(slotRejectMessage(menu, rawSlot))
                playDenySound(player)
            } else if (incoming != null) {
                playPlaceSound(player)
            }
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        val menu = event.inventory.holder as? ForgeMenu ?: return
        val player = event.whoClicked as? Player ?: return
        val topSlots = event.rawSlots.filter { it < event.inventory.size }
        if (topSlots.any { it !in menu.inputSlots }) {
            event.isCancelled = true
            player.sendMessage("§c[SourceForge] §f背景板不能放置物品")
            playDenySound(player)
            return
        }
        val item = event.oldCursor.takeIf { it.type != Material.AIR } ?: return
        val blockedSlot = topSlots.firstOrNull { !isAllowedInSlot(menu, it, item) }
        if (blockedSlot != null) {
            event.isCancelled = true
            player.sendMessage(slotRejectMessage(menu, blockedSlot))
            playDenySound(player)
        } else {
            playPlaceSound(player)
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val menu = event.inventory.holder as? ForgeMenu ?: return
        val player = event.player as? Player ?: return
        for (slot in menu.inputSlots) {
            val item = event.inventory.getItem(slot) ?: continue
            if (item.type == Material.AIR) continue
            player.inventory.addItem(item).values.forEach { player.world.dropItemNaturally(player.location, it) }
            event.inventory.setItem(slot, null)
        }
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

    @EventHandler(ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val target = event.entity as? LivingEntity ?: return
        var sourceDamage: Float? = null
        when (val damager = event.damager) {
            is Player -> {
                val weapon = damager.inventory.itemInMainHand
                if (plugin.itemService.isSourceEquipment(weapon)) {
                    plugin.itemService.stripVanillaEnchantments(weapon)
                    sourceDamage = applyCombat(
                        player = damager,
                        target = target,
                        weapon = weapon,
                        baseDamage = event.damage
                    ) { event.damage = it }
                } else if (plugin.itemService.hasSourceArmor(damager)) {
                    val strengthTotal = plugin.itemService.readTotalAffix(damager, "ability_strength")
                    val multiplier = 1.0 + strengthTotal
                    event.damage = event.damage * multiplier
                    if (plugin.forgeConfig.debugCombat) {
                        damager.sendMessage("§8[SourceForge Debug] §7非SF武器 + SF防具: 强度=${"%.2f".format(strengthTotal)}, 倍率=${"%.2f".format(multiplier)}, 伤害=${"%.2f".format(event.damage)}")
                    }
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
        applyDefense(target, event)
    }

    @EventHandler(ignoreCancelled = true)
    fun onGenericDamage(event: EntityDamageEvent) {
        if (event is EntityDamageByEntityEvent) return
        val target = event.entity as? LivingEntity ?: return
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
        // 读取词条值
        val readValue: (String) -> Double = if (projectilePdc != null) {
            { affixId -> plugin.itemService.readAffixValue(projectilePdc, affixId) }
        } else {
            { affixId -> plugin.itemService.readAffixValue(weapon, affixId) }
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

    // ==================== 锻造逻辑 ====================

    private fun forge(player: Player, menu: ForgeMenu) {
        val inv = menu.inventory
        val blueprintItem = inv.getItem(menu.blueprintSlot)
        val blueprintId = plugin.itemService.blueprintId(blueprintItem)
        if (blueprintId == null) {
            player.sendMessage("§c[SourceForge] §f请在左侧放入蓝图")
            playDenySound(player)
            return
        }
        val blueprint = plugin.forgeConfig.blueprints[blueprintId]
        if (blueprint == null) {
            player.sendMessage("§c[SourceForge] §f未知蓝图: $blueprintId")
            playDenySound(player)
            return
        }
        val equipment = plugin.forgeConfig.equipment[blueprint.equipmentType]
        if (equipment == null) {
            player.sendMessage("§c[SourceForge] §f蓝图缺少装备配置: ${blueprint.equipmentType}")
            playDenySound(player)
            return
        }
        val availableSlots = menu.inputSlots.filter { it != menu.blueprintSlot }
        val invalidBase = menu.baseMaterialSlots.firstNotNullOfOrNull { slot ->
            inv.getItem(slot)?.takeIf { it.type != Material.AIR && !isBaseMaterial(blueprint, it) }
                ?.let { slot to it }
        }
        if (invalidBase != null) {
            player.sendMessage("§c[SourceForge] §f基础材料区有不属于当前蓝图的物品，请取出后再锻造")
            playDenySound(player)
            return
        }
        val invalidSpecial = menu.specialMaterialSlots.firstNotNullOfOrNull { slot ->
            inv.getItem(slot)?.takeIf { it.type != Material.AIR && !isSpecialMaterial(it) }
                ?.let { slot to it }
        }
        if (invalidSpecial != null) {
            player.sendMessage("§c[SourceForge] §f附加材料区有未配置的材料，请取出后再锻造")
            playDenySound(player)
            return
        }
        val inputs = availableSlots.mapNotNull { slot -> inv.getItem(slot)?.takeIf { it.type != Material.AIR } }
        val missing = blueprint.requirements.firstOrNull { count(inputs, it) < it.amount }
        if (missing != null) {
            player.sendMessage("§c[SourceForge] §f材料不足: ${plugin.forgeConfig.displayName(missing.id)} x${missing.amount}")
            playDenySound(player)
            return
        }

        val remainingInputs = remainingAfter(inputs, blueprint.requirements)
        val selectedMaterials = plugin.forgeConfig.forgeMaterials
            .filter { count(remainingInputs, MaterialRequirement(it.itemId, it.amount)) >= it.amount }
        consume(inv, availableSlots, blueprint.requirements)
        selectedMaterials.forEach {
            consume(inv, availableSlots, listOf(MaterialRequirement(it.itemId, it.amount)))
        }
        consumeBlueprint(inv, menu.blueprintSlot)

        val tierRange = plugin.itemService.blueprintTierRange(blueprintItem, blueprint)
        val tier = if (tierRange.first == tierRange.last) {
            tierRange.first
        } else {
            Random.nextInt(tierRange.first, tierRange.last + 1)
        }
        val result = plugin.itemService.createEquipment(
            blueprint = blueprint,
            equipment = equipment,
            tier = tier,
            materials = selectedMaterials
        )
        player.inventory.addItem(result).values.forEach { player.world.dropItemNaturally(player.location, it) }
        player.sendMessage("§a[SourceForge] §f锻造完成: §e${equipment.displayName}")
        playForgeSound(player)
    }

    // ==================== 辅助方法 ====================

    private fun playPlaceSound(player: Player) {
        player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.35f, 1.35f)
    }

    private fun playDenySound(player: Player) {
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.45f, 0.65f)
    }

    private fun playForgeSound(player: Player) {
        player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 0.8f, 1.05f)
    }

    private fun count(items: List<ItemStack>, requirement: MaterialRequirement): Int {
        return items.filter { ItemMatcher.matches(it, requirement.id) }.sumOf { it.amount }
    }

    private fun incomingItem(event: InventoryClickEvent, player: Player): ItemStack? {
        if (event.click == ClickType.NUMBER_KEY) {
            val button = event.hotbarButton
            return if (button in 0..8) player.inventory.getItem(button)?.takeIf { it.type != Material.AIR } else null
        }
        return event.cursor.takeIf { it.type != Material.AIR }
    }

    private fun isAllowedInSlot(menu: ForgeMenu, slot: Int, item: ItemStack): Boolean {
        return when (slot) {
            menu.blueprintSlot -> plugin.itemService.blueprintId(item) != null
            in menu.baseMaterialSlots -> {
                val blueprint = currentBlueprint(menu) ?: return false
                isBaseMaterial(blueprint, item)
            }
            in menu.specialMaterialSlots -> isSpecialMaterial(item)
            else -> false
        }
    }

    private fun slotRejectMessage(menu: ForgeMenu, slot: Int): String {
        return when (slot) {
            menu.blueprintSlot -> "§c[SourceForge] §f蓝图槽只能放 SourceForge 蓝图"
            in menu.baseMaterialSlots -> {
                if (currentBlueprint(menu) == null) {
                    "§c[SourceForge] §f请先放入蓝图，再放当前蓝图需要的基础材料"
                } else {
                    "§c[SourceForge] §f基础材料区只能放当前蓝图 requirements 里的材料"
                }
            }
            in menu.specialMaterialSlots -> "§c[SourceForge] §f附加材料区只能放 SourceForge materials 文件夹配置过的材料"
            else -> "§c[SourceForge] §f这里不能放置物品"
        }
    }

    private fun currentBlueprint(menu: ForgeMenu): BlueprintConfig? {
        val id = plugin.itemService.blueprintId(menu.inventory.getItem(menu.blueprintSlot)) ?: return null
        return plugin.forgeConfig.blueprints[id]
    }

    private fun isBaseMaterial(blueprint: BlueprintConfig, item: ItemStack): Boolean {
        return blueprint.requirements.any { ItemMatcher.matches(item, it.id) }
    }

    private fun isSpecialMaterial(item: ItemStack): Boolean {
        return plugin.forgeConfig.forgeMaterials.any { ItemMatcher.matches(item, it.itemId) }
    }

    private fun remainingAfter(items: List<ItemStack>, requirements: List<MaterialRequirement>): List<ItemStack> {
        val remaining = items.map { it.clone() }.toMutableList()
        for (requirement in requirements) {
            var needed = requirement.amount
            for (item in remaining) {
                if (needed <= 0) break
                if (!ItemMatcher.matches(item, requirement.id)) continue
                val take = minOf(needed, item.amount)
                item.amount -= take
                needed -= take
            }
        }
        return remaining.filter { it.type != Material.AIR && it.amount > 0 }
    }

    private fun consume(inv: org.bukkit.inventory.Inventory, slots: List<Int>, requirements: List<MaterialRequirement>) {
        for (requirement in requirements) {
            var remaining = requirement.amount
            for (slot in slots) {
                if (remaining <= 0) break
                val item = inv.getItem(slot) ?: continue
                if (!ItemMatcher.matches(item, requirement.id)) continue
                val take = minOf(remaining, item.amount)
                item.amount -= take
                remaining -= take
                if (item.amount <= 0) inv.setItem(slot, null)
            }
        }
    }

    private fun consumeBlueprint(inv: org.bukkit.inventory.Inventory, slot: Int) {
        val item = inv.getItem(slot) ?: return
        item.amount -= 1
        if (item.amount <= 0) inv.setItem(slot, null)
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
