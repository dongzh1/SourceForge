package com.dongzh1.sourceforge.forge

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.config.AffixConfig
import com.dongzh1.sourceforge.config.AffixEffectConfig
import com.dongzh1.sourceforge.config.BlueprintConfig
import com.dongzh1.sourceforge.config.MaterialRequirement
import com.dongzh1.sourceforge.config.MonsterProfileConfig
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
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import kotlin.random.Random

class ForgeListener(
    private val plugin: SourceForge
) : Listener {
    private val skillDamageKey = NamespacedKey(plugin, "skill_damage")
    private val commandDamageTypeKey = NamespacedKey(plugin, "command_damage_type")
    private val commandDamageElementKey = NamespacedKey(plugin, "command_damage_element")

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

    @EventHandler(ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val target = event.entity as? LivingEntity ?: return
        var sourceDamage: IncomingDamage? = null
        when (val damager = event.damager) {
            is Player -> {
                val weapon = damager.inventory.itemInMainHand
                if (plugin.itemService.isSourceEquipment(weapon)) {
                    plugin.itemService.stripVanillaEnchantments(weapon)
                    sourceDamage = applyCombat(
                        player = damager,
                        target = target,
                        mode = "melee",
                        weaponType = plugin.itemService.weaponType(weapon),
                        weaponCategory = plugin.itemService.weaponCategory(weapon),
                        baseDamage = event.damage,
                        enchantLevels = plugin.enchantService.readEnchantLevels(weapon),
                        readValue = { affixId -> plugin.itemService.readAffixValue(weapon, affixId) }
                    ) { event.damage = it }
                }
            }
            is Projectile -> {
                val player = damager.shooter as? Player
                if (player != null && plugin.itemService.isSourceProjectile(damager)) {
                    sourceDamage = applyCombat(
                        player = player,
                        target = target,
                        mode = "ranged",
                        weaponType = plugin.itemService.projectileWeaponType(damager),
                        weaponCategory = plugin.itemService.projectileWeaponCategory(damager),
                        baseDamage = event.damage,
                        enchantLevels = plugin.enchantService.readEnchantLevels(damager.persistentDataContainer),
                        readValue = { affixId -> plugin.itemService.readAffixValue(damager.persistentDataContainer, affixId) }
                    ) { event.damage = it }
                }
            }
            else -> Unit
        }
        applyDefense(target, event, sourceDamage)
    }

    @EventHandler(ignoreCancelled = true)
    fun onGenericDamage(event: EntityDamageEvent) {
        if (event is EntityDamageByEntityEvent) return
        val target = event.entity as? LivingEntity ?: return
        applyDefense(target, event, null)
    }

    private fun applyDefense(target: LivingEntity, event: EntityDamageEvent, sourceDamage: IncomingDamage?) {
        if (target.persistentDataContainer.has(skillDamageKey, PersistentDataType.INTEGER)) {
            setCustomDamage(event, event.damage)
            return
        }
        val incoming = event.damage
        val sourceResistances = sourceArmorResistances(target)
        if (incoming > 0.0 && sourceResistances.dodgeChance > 0.0 && Random.nextDouble() < sourceResistances.dodgeChance) {
            setCustomDamage(event, 0.0)
            if (plugin.forgeConfig.debugCombat) {
                target.sendDebugMessage("§8[SourceForge Debug] §7闪避成功: 伤害=${"%.2f".format(incoming)} -> 0.00")
            }
            return
        }
        val damageInfo = classifyDamage(event)
        val physicalDefense = target.getAttribute(Attribute.ARMOR)?.value ?: 0.0
        val magicDefense = target.getAttribute(Attribute.ARMOR_TOUGHNESS)?.value ?: 0.0
        val attackPower = attackPower(event, incoming)

        if (sourceDamage != null) {
            val physical = calculateDefendedDamage(sourceDamage.physical, attackPower, physicalDefense) *
                (1.0 - sourceResistances.physical)
            val magic = calculateDefendedDamage(sourceDamage.magic, attackPower, magicDefense) *
                (1.0 - sourceResistances.magic)
            var elementalHealing = 0.0
            val elemental = sourceDamage.elemental.entries.sumOf { (element, amount) ->
                val result = calculateElementDamageResult(amount, sourceResistances.element(element))
                elementalHealing += result.healing
                result.damage
            }
            val finalDamage = physical + magic + elemental + sourceDamage.trueDamage
            if (elementalHealing > 0.0) {
                elementalHealing = heal(target, elementalHealing)
            }
            setCustomDamage(event, finalDamage)
            applyThornGuard(target, event)
            if (plugin.forgeConfig.debugCombat) {
                target.sendDebugMessage(
                    "§8[SourceForge Debug] §7防御结算: 来源=SourceForge, 物防=${"%.2f".format(physicalDefense)}, 法防=${"%.2f".format(magicDefense)}, " +
                        "类型=混合, 治疗=${"%.2f".format(elementalHealing)}, 伤害=${"%.2f".format(incoming)} -> ${"%.2f".format(finalDamage)}"
                )
            }
            return
        }

        var healing = 0.0
        val targetProfile: MonsterProfileConfig? = if (target is Player) null else targetProfile(target)
        val defended = when (damageInfo.category) {
            DamageCategory.PHYSICAL -> {
                val profileResistance = targetProfile?.physicalResistance ?: 0.0
                calculateDefendedDamage(incoming, attackPower, physicalDefense) *
                    (1.0 - sourceResistances.physical) *
                    (1.0 - profileResistance.coerceIn(-1.0, 0.9))
            }
            DamageCategory.MAGIC -> {
                if (damageInfo.element == "none") {
                    val profileResistance = targetProfile?.magicResistance ?: 0.0
                    calculateDefendedDamage(incoming, attackPower, magicDefense) *
                        (1.0 - sourceResistances.magic) *
                        (1.0 - profileResistance.coerceIn(-1.0, 0.9))
                } else {
                    val elementResistance = targetProfile?.elementResistances?.get(damageInfo.element)
                        ?: sourceResistances.element(damageInfo.element)
                    val result = calculateElementDamageResult(incoming, elementResistance)
                    healing = result.healing
                    result.damage * (targetProfile?.let { elementReactionMultiplier(damageInfo.element, it) } ?: 1.0)
                }
            }
            DamageCategory.TRUE -> incoming.coerceAtLeast(0.0) * plugin.forgeConfig.combat.trueDamageScale
        }
        if (healing > 0.0) {
            healing = heal(target, healing)
        }
        setCustomDamage(event, defended)
        applyThornGuard(target, event)
        if (plugin.forgeConfig.debugCombat) {
            target.sendDebugMessage(
                "§8[SourceForge Debug] §7防御结算: 类型=${damageInfo.category.displayName}/${damageInfo.element}, " +
                    "攻击力=${"%.2f".format(attackPower)}, 物防=${"%.2f".format(physicalDefense)}, 法防=${"%.2f".format(magicDefense)}, " +
                    "治疗=${"%.2f".format(healing)}, 伤害=${"%.2f".format(incoming)} -> ${"%.2f".format(defended)}"
            )
        }
    }

    private fun calculateDefendedDamage(rawDamage: Double, attackPower: Double, defense: Double): Double {
        if (rawDamage <= 0.0) return 0.0
        if (defense <= 0.0) return maxOf(1.0, rawDamage)
        val effectiveAttack = attackPower.coerceAtLeast(0.0)
        val multiplier = maxOf(0.10, effectiveAttack / (effectiveAttack + defense))
        return maxOf(1.0, rawDamage * multiplier)
    }

    private fun calculateElementDamage(rawDamage: Double, elementResistance: Double): Double {
        return calculateElementDamageResult(rawDamage, elementResistance).damage
    }

    private fun calculateElementDamageResult(rawDamage: Double, elementResistance: Double): ElementDamageResult {
        if (rawDamage <= 0.0) return ElementDamageResult(0.0, 0.0)
        val multiplier = 1.0 - elementResistance / 100.0
        if (multiplier < 0.0) {
            return ElementDamageResult(0.0, rawDamage * -multiplier)
        }
        return ElementDamageResult((rawDamage * multiplier).coerceAtLeast(0.0), 0.0)
    }

    private fun heal(target: LivingEntity, amount: Double): Double {
        if (amount <= 0.0) return 0.0
        val limit = plugin.forgeConfig.combat.elementAbsorbMaxHealPerHit
        if (limit <= 0.0) return 0.0
        val maxHealth = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: return 0.0
        val actual = minOf(limit, amount, maxHealth - target.health).coerceAtLeast(0.0)
        if (actual > 0.0) {
            target.health += actual
        }
        return actual
    }

    private fun attackPower(event: EntityDamageEvent, rawDamage: Double): Double {
        val source = event.damageSource.causingEntity as? LivingEntity
            ?: event.damageSource.directEntity as? LivingEntity
            ?: ((event as? EntityDamageByEntityEvent)?.damager as? Projectile)?.shooter as? LivingEntity
            ?: (event as? EntityDamageByEntityEvent)?.damager as? LivingEntity
        val attributeDamage = source?.getAttribute(Attribute.ATTACK_DAMAGE)?.value ?: 0.0
        return maxOf(1.0, rawDamage, attributeDamage)
    }

    private fun classifyDamage(event: EntityDamageEvent): ClassifiedDamage {
        commandDamage(event)?.let { return it }
        monsterAttackDamage(event)?.let { return it }
        val key = event.damageSource.damageType.key.key.lowercase()
        return when (key) {
            "cramming", "drown", "dry_out", "ender_pearl", "fall", "fly_into_wall", "generic_kill",
            "in_wall", "out_of_world", "outside_border", "starve", "stalagmite" ->
                ClassifiedDamage(DamageCategory.TRUE)

            "bad_respawn_point", "campfire", "dragon_breath", "explosion", "fireball", "fireworks", "freeze",
            "hot_floor", "in_fire", "indirect_magic", "lava", "lightning_bolt", "magic", "on_fire",
            "player_explosion", "sonic_boom", "unattributed_fireball", "wither", "wither_skull" ->
                ClassifiedDamage(DamageCategory.MAGIC, elementForDamageType(key))

            else -> ClassifiedDamage(DamageCategory.PHYSICAL)
        }
    }

    private fun commandDamage(event: EntityDamageEvent): ClassifiedDamage? {
        val target = event.entity as? LivingEntity ?: return null
        val type = target.persistentDataContainer.get(commandDamageTypeKey, PersistentDataType.STRING)?.lowercase() ?: return null
        val element = target.persistentDataContainer.get(commandDamageElementKey, PersistentDataType.STRING)?.lowercase() ?: "none"
        return when (type) {
            "physical", "物理" -> ClassifiedDamage(DamageCategory.PHYSICAL)
            "magic", "法术", "魔法" -> ClassifiedDamage(DamageCategory.MAGIC)
            "element", "元素" -> ClassifiedDamage(DamageCategory.MAGIC, element)
            "true", "真实" -> ClassifiedDamage(DamageCategory.TRUE)
            else -> null
        }
    }

    private fun monsterAttackDamage(event: EntityDamageEvent): ClassifiedDamage? {
        if (event !is EntityDamageByEntityEvent) return null
        val attacker = event.damageSource.causingEntity as? LivingEntity
            ?: event.damageSource.directEntity as? LivingEntity
            ?: (event.damager as? Projectile)?.shooter as? LivingEntity
            ?: event.damager as? LivingEntity
            ?: return null
        if (attacker is Player) return null
        val profile = targetProfile(attacker)
        return when (profile.attackType) {
            "magic", "法术", "魔法" -> ClassifiedDamage(DamageCategory.MAGIC)
            "element", "元素" -> ClassifiedDamage(DamageCategory.MAGIC, profile.attackElement.takeIf { it.isNotBlank() } ?: "none")
            "true", "真实" -> ClassifiedDamage(DamageCategory.TRUE)
            else -> null
        }
    }

    private fun elementForDamageType(key: String): String {
        return when (key) {
            "campfire", "fireball", "hot_floor", "in_fire", "lava", "on_fire", "unattributed_fireball" -> "fire"
            "lightning_bolt" -> "lightning"
            "freeze" -> "ice"
            else -> "none"
        }
    }

    private fun sourceArmorResistances(target: LivingEntity): SourceArmorResistances {
        val player = target as? Player ?: return SourceArmorResistances.NONE
        val armor = player.inventory.armorContents.filter { plugin.itemService.isSourceEquipment(it) }
        val inventoryItems = player.inventory.contents
            .filter { plugin.itemService.isSourceEquipment(it) }
            .filter {
                val equipment = plugin.itemService.equipmentConfig(it) ?: return@filter false
                "inventory" in equipment.effectiveSlots || "backpack" in equipment.effectiveSlots
            }
        val sources = armor + inventoryItems
        if (sources.isEmpty()) return SourceArmorResistances.NONE
        val bulwarkPieces = armor.count { plugin.itemService.weaponType(it)?.startsWith("bulwark_") == true }
        val wardPieces = armor.count { plugin.itemService.weaponType(it)?.startsWith("ward_") == true }
        val bulwarkPhysicalBonus = when {
            bulwarkPieces >= 4 -> 0.12
            bulwarkPieces >= 2 -> 0.05
            else -> 0.0
        }
        val wardMagicBonus = when {
            wardPieces >= 4 -> 0.12
            wardPieces >= 2 -> 0.05
            else -> 0.0
        }
        val wardElementBonus = when {
            wardPieces >= 4 -> 8.0
            wardPieces >= 2 -> 3.0
            else -> 0.0
        }
        return SourceArmorResistances(
            physical = (sources.sumOf { plugin.itemService.readAffixValue(it, "physical_resistance") } + bulwarkPhysicalBonus).coerceIn(0.0, 0.75),
            magic = (sources.sumOf { plugin.itemService.readAffixValue(it, "magic_resistance") } + wardMagicBonus).coerceIn(0.0, 0.75),
            fire = sources.sumOf { plugin.itemService.readAffixValue(it, "fire_resistance") } + wardElementBonus,
            ice = sources.sumOf { plugin.itemService.readAffixValue(it, "ice_resistance") } + wardElementBonus,
            lightning = sources.sumOf { plugin.itemService.readAffixValue(it, "lightning_resistance") } + wardElementBonus,
            water = sources.sumOf { plugin.itemService.readAffixValue(it, "water_resistance") },
            wood = sources.sumOf { plugin.itemService.readAffixValue(it, "wood_resistance") },
            dodgeChance = sources.sumOf { plugin.itemService.readAffixValue(it, "dodge_chance") }.coerceIn(0.0, 0.75)
        )
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

    private fun LivingEntity.sendDebugMessage(message: String) {
        (this as? Player)?.sendMessage(message)
    }

    private fun applyCombat(
        player: Player,
        target: LivingEntity,
        mode: String,
        weaponType: String?,
        weaponCategory: String?,
        baseDamage: Double,
        enchantLevels: Map<String, Int>,
        readValue: (String) -> Double,
        applyDamage: (Double) -> Unit
    ): IncomingDamage {
        val configuredPhysicalDamage = readValue("physical_damage")
        val physicalBase = if (mode.equals("melee", ignoreCase = true) && configuredPhysicalDamage > 0.0) {
            configuredPhysicalDamage * meleeDamageScale(baseDamage, configuredPhysicalDamage)
        } else {
            baseDamage
        }
        var physicalDamage = physicalBase
        var magicDamage = 0.0
        var trueDamage = 0.0
        val elementDamage = linkedMapOf<String, Double>()
        var armorPierce = 0.0
        var critChance = 0.0
        var critDamageBonus = 0.0
        var poisonChance = 0.0
        var burnDuration = 80
        var burnChance = 0.0
        var slowDuration = 60
        var slowChance = 0.0
        var slowAmplifier = 0
        var poisonDuration = 80
        var poisonAmplifier = 0
        var enchantLifestealRate = 0.0
        for (affix in plugin.forgeConfig.affixes.values) {
            val effect = resolveEffect(affix, mode, weaponType, weaponCategory)
            val value = readValue(affix.id)
            if (value <= 0.0) continue
            when (effect.combat) {
                "damage", "physical_damage", "magic_damage", "element_damage", "true_damage" -> {
                    if (mode.equals("melee", ignoreCase = true) && effect.damageType == "physical") {
                        continue
                    }
                    addDamage(effect, value, elementDamage) { type, amount ->
                        when (type) {
                            "true" -> trueDamage += amount
                            "magic" -> magicDamage += amount
                            else -> physicalDamage += amount
                        }
                    }
                }
                "pierce" -> armorPierce += value
                "crit_chance" -> critChance = maxOf(critChance, value)
                "crit_damage" -> critDamageBonus = maxOf(critDamageBonus, value)
                "poison" -> {
                    addElementDamage(effect, value, elementDamage)
                    poisonChance = maxOf(poisonChance, effect.statusChance)
                    poisonDuration = maxOf(poisonDuration, effect.durationTicks)
                    poisonAmplifier = maxOf(poisonAmplifier, effect.amplifier)
                }
                "burn" -> {
                    addElementDamage(effect, value, elementDamage)
                    burnChance = maxOf(burnChance, effect.statusChance)
                    burnDuration = maxOf(burnDuration, effect.durationTicks)
                }
                "slow" -> {
                    addElementDamage(effect, value, elementDamage)
                    slowChance = maxOf(slowChance, effect.statusChance)
                    slowDuration = maxOf(slowDuration, effect.durationTicks)
                    slowAmplifier = maxOf(slowAmplifier, effect.amplifier)
                }
            }
        }

        var knockbackTriggered = false
        var elementalProcText = ""
        for ((enchantId, level) in enchantLevels) {
            val params = plugin.enchantService.params(enchantId, level)
            physicalDamage += params.double("physical-damage")
            magicDamage += params.double("magic-damage")
            trueDamage += params.double("true-damage")
            physicalDamage *= 1.0 + params.double("physical-damage-rate")
            magicDamage *= 1.0 + params.double("magic-damage-rate")
            val damageBonusRate = params.double("damage-bonus-rate")
            if (damageBonusRate != 0.0) {
                physicalDamage *= 1.0 + damageBonusRate
                magicDamage *= 1.0 + damageBonusRate
            }
            armorPierce += params.double("armor-pierce")
            critChance += params.double("crit-chance-bonus")
            critDamageBonus += params.double("crit-damage-bonus")
            enchantLifestealRate += params.double("lifesteal-rate")

            for (element in ELEMENTS) {
                val amount = params.double("$element-damage")
                if (amount <= 0.0) continue
                val chance = params.double("$element-chance", params.double("chance", 1.0)).coerceIn(0.0, 1.0)
                if (Random.nextDouble() <= chance) {
                    elementDamage[element] = (elementDamage[element] ?: 0.0) + amount
                    elementalProcText += " $element=${"%.1f".format(amount)}"
                }
            }
            val extraSlowChance = params.double("slow-chance")
            if (extraSlowChance > 0.0) {
                slowChance = maxOf(slowChance, extraSlowChance)
                slowDuration = maxOf(slowDuration, params.int("slow-duration-ticks", slowDuration))
                slowAmplifier = maxOf(slowAmplifier, params.int("slow-amplifier", slowAmplifier))
            }
            val extraPoisonChance = params.double("poison-chance")
            if (extraPoisonChance > 0.0) {
                poisonChance = maxOf(poisonChance, extraPoisonChance)
                poisonDuration = maxOf(poisonDuration, params.int("poison-duration-ticks", poisonDuration))
                poisonAmplifier = maxOf(poisonAmplifier, params.int("poison-amplifier", poisonAmplifier))
            }
            val extraBurnChance = params.double("burn-chance")
            if (extraBurnChance > 0.0) {
                burnChance = maxOf(burnChance, extraBurnChance)
                burnDuration = maxOf(burnDuration, params.int("burn-duration-ticks", burnDuration))
            }
            val knockbackChance = params.double("knockback-chance", params.double("chance"))
            if (knockbackChance > 0.0 && Random.nextDouble() < knockbackChance) {
                knockbackTriggered = true
                knockback(player, target, params.double("knockback-strength", 0.5))
            }
        }

        var critTriggered = false
        if (critChance > 0.0 && Random.nextDouble() < critChance) {
            critTriggered = true
            val critDamage = if (critDamageBonus > 0.0) critDamageBonus else 0.5
            val multiplier = 1.0 + critDamage
            physicalDamage *= multiplier
            magicDamage *= multiplier
        }

        val profile = targetProfile(target)
        val physicalAfterResistance = applyResistance(physicalDamage, profile.physicalResistance - armorPierce * 0.05)
        val magicAfterResistance = applyResistance(magicDamage, profile.magicResistance)
        var targetElementHealing = 0.0
        val elementalAfterResistanceByElement = elementDamage.mapValues { (element, amount) ->
            val result = calculateElementDamageResult(amount, profile.elementResistances[element] ?: 0.0)
            targetElementHealing += result.healing
            result.damage * elementReactionMultiplier(element, profile)
        }
        val elementalAfterResistance = elementalAfterResistanceByElement.values.sum()
        val trueAfterScale = trueDamage * plugin.forgeConfig.combat.trueDamageScale
        val damage = physicalAfterResistance + magicAfterResistance + elementalAfterResistance + trueAfterScale
        if (targetElementHealing > 0.0) {
            targetElementHealing = heal(target, targetElementHealing)
        }
        val enchantLifestealHeal = if (enchantLifestealRate > 0.0) heal(player, damage * enchantLifestealRate) else 0.0

        val poisonTriggered = poisonChance > 0.0 && Random.nextDouble() < poisonChance
        if (poisonTriggered) {
            target.addPotionEffect(PotionEffect(PotionEffectType.POISON, poisonDuration, poisonAmplifier))
        }
        val burnTriggered = burnChance > 0.0 && Random.nextDouble() < burnChance
        if (burnTriggered) {
            target.fireTicks = maxOf(target.fireTicks, burnDuration)
        }
        val slowTriggered = slowChance > 0.0 && Random.nextDouble() < slowChance
        if (slowTriggered) {
            target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, slowDuration, slowAmplifier))
        }
        applyDamage(damage)
        if (plugin.forgeConfig.debugCombat) {
            player.sendMessage(
                "§8[SourceForge Debug] §7模式=$mode, 类型=${weaponType ?: "unknown"}, 分类=${weaponCategory ?: "unknown"}, 基础=${"%.2f".format(baseDamage)}, " +
                    "目标=${profile.id}, 物理=${"%.2f".format(physicalAfterResistance)}, 魔法=${"%.2f".format(magicAfterResistance + elementalAfterResistance)}, 真实=${"%.2f".format(trueAfterScale)}, 破甲=${"%.2f".format(armorPierce)}, " +
                    "元素治疗=${"%.2f".format(targetElementHealing)}, 附魔吸血=${"%.2f".format(enchantLifestealHeal)}, " +
                    "会心=${"%.2f".format(critChance)}(${if (critTriggered) "触发" else "未触发"}), " +
                    "破势=${"%.2f".format(critDamageBonus)}, " +
                    "附魔元素=${elementalProcText.ifBlank { "none" }}, 震退=${if (knockbackTriggered) "触发" else "未触发"}, " +
                    "淬毒=${"%.2f".format(poisonChance)}(${if (poisonTriggered) "触发" else "未触发"}), " +
                    "灼烧=${"%.2f".format(burnChance)}(${if (burnTriggered) "触发" else "未触发"}), " +
                    "寒缓=${"%.2f".format(slowChance)}(${if (slowTriggered) "触发" else "未触发"}), " +
                    "最终=${"%.2f".format(damage)}"
            )
        }
        return IncomingDamage(
            physical = physicalAfterResistance,
            magic = magicAfterResistance,
            elemental = elementalAfterResistanceByElement,
            trueDamage = trueAfterScale
        )
    }

    private fun applyThornGuard(target: LivingEntity, event: EntityDamageEvent) {
        val player = target as? Player ?: return
        val attacker = (event as? EntityDamageByEntityEvent)?.damager as? LivingEntity ?: return
        if (attacker.uniqueId == player.uniqueId || attacker.isDead) return
        val level = player.inventory.armorContents.maxOfOrNull { plugin.enchantService.readEnchantLevel(it, "thorn_guard") } ?: 0
        if (level <= 0) return
        val reflect = plugin.enchantService.params("thorn_guard", level).double("reflect-damage")
        if (reflect <= 0.0) return
        attacker.persistentDataContainer.set(skillDamageKey, PersistentDataType.INTEGER, 1)
        attacker.damage(reflect, player)
        attacker.persistentDataContainer.remove(skillDamageKey)
    }

    private fun knockback(source: LivingEntity, target: LivingEntity, strength: Double) {
        val direction = target.location.toVector().subtract(source.location.toVector()).normalizeOrZero()
        target.velocity = target.velocity.add(direction.multiply(strength).setY(0.18))
    }

    private fun meleeDamageScale(baseDamage: Double, configuredPhysicalDamage: Double): Double {
        if (configuredPhysicalDamage <= 0.0) return 1.0
        return (baseDamage / configuredPhysicalDamage).coerceIn(0.0, 1.0)
    }

    private fun Vector.normalizeOrZero(): Vector {
        return if (lengthSquared() <= 0.0) Vector(0.0, 0.0, 0.0) else normalize()
    }

    private fun addDamage(
        effect: AffixEffectConfig,
        value: Double,
        elementDamage: MutableMap<String, Double>,
        addTypedDamage: (String, Double) -> Unit
    ) {
        if (effect.damageType == "magic" && effect.element != "none") {
            addElementDamage(effect, value, elementDamage)
            return
        }
        addTypedDamage(effect.damageType, value)
    }

    private fun addElementDamage(
        effect: AffixEffectConfig,
        value: Double,
        elementDamage: MutableMap<String, Double>
    ) {
        val element = effect.element.takeIf { it.isNotBlank() && it != "none" } ?: "neutral"
        elementDamage[element] = (elementDamage[element] ?: 0.0) + value
    }

    private fun applyResistance(amount: Double, resistance: Double): Double {
        val clamped = resistance.coerceIn(-1.0, 0.9)
        return amount * (1.0 - clamped)
    }

    private fun elementReactionMultiplier(attackerElement: String, profile: MonsterProfileConfig): Double {
        if (profile.elements.isEmpty()) return 1.0
        var multiplier = 1.0
        val rules = plugin.forgeConfig.combat.elementReactions[attackerElement] ?: return 1.0
        for (targetElement in profile.elements) {
            multiplier *= rules[targetElement] ?: 1.0
        }
        return multiplier.coerceIn(0.25, 2.5)
    }

    private fun targetProfile(target: LivingEntity): MonsterProfileConfig {
        val combat = plugin.forgeConfig.combat
        target.scoreboardTags
            .firstOrNull { it.lowercase().startsWith("sf_profile:") }
            ?.substringAfter(":", "")
            ?.lowercase()
            ?.let { combat.monsterProfiles[it] }
            ?.let { return it }

        val tags = target.scoreboardTags.map { it.lowercase() }.toSet()
        val type = target.type.name.lowercase()
        combat.monsterProfiles.values.firstOrNull { profile ->
            profile.tags.any { it in tags } ||
                type in profile.entityTypes
        }?.let { return it }
        return combat.monsterProfiles[combat.defaultMonsterProfile]
            ?: MonsterProfileConfig("default", "默认", emptySet(), emptySet(), emptySet(), emptySet(), "physical", "none", 0.0, 0.0, emptyMap())
    }

    private fun resolveEffect(
        affix: AffixConfig,
        mode: String,
        weaponType: String?,
        weaponCategory: String?
    ): AffixEffectConfig {
        val type = weaponType?.lowercase()
        val category = weaponCategory?.lowercase()
        val candidates = listOfNotNull(
            type?.let { "${it}_${mode.lowercase()}" },
            type,
            category?.let { "${it}_${mode.lowercase()}" },
            category,
            mode.lowercase()
        )
        for (key in candidates) {
            affix.effects[key]?.let { return it }
        }
        return if (mode.equals("ranged", ignoreCase = true)) affix.ranged else affix.melee
    }

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
}

private data class IncomingDamage(
    val physical: Double,
    val magic: Double,
    val elemental: Map<String, Double>,
    val trueDamage: Double
)

private data class ClassifiedDamage(
    val category: DamageCategory,
    val element: String = "none"
)

private data class ElementDamageResult(
    val damage: Double,
    val healing: Double
)

private enum class DamageCategory(
    val displayName: String
) {
    PHYSICAL("物理"),
    MAGIC("魔法"),
    TRUE("真实")
}

private data class SourceArmorResistances(
    val physical: Double,
    val magic: Double,
    val fire: Double,
    val ice: Double,
    val lightning: Double,
    val water: Double,
    val wood: Double,
    val dodgeChance: Double
) {
    fun element(element: String): Double {
        return when (element) {
            "fire" -> fire
            "ice" -> ice
            "lightning" -> lightning
            "water" -> water
            "wood" -> wood
            else -> 0.0
        }
    }

    companion object {
        val NONE = SourceArmorResistances(
            physical = 0.0,
            magic = 0.0,
            fire = 0.0,
            ice = 0.0,
            lightning = 0.0,
            water = 0.0,
            wood = 0.0,
            dodgeChance = 0.0
        )
    }
}

private val ELEMENTS = setOf("fire", "ice", "lightning", "water", "wood")

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
