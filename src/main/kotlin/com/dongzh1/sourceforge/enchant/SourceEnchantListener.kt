package com.dongzh1.sourceforge.enchant

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.config.MonsterProfileConfig
import com.dongzh1.sourceforge.enchant.EnchantLevelConfig
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.block.Action
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import java.util.UUID

class SourceEnchantListener(
    private val plugin: SourceForge
) : Listener {
    private val activeSkillCooldowns = mutableMapOf<SkillCooldownKey, Long>()
    private val activeShields = mutableMapOf<UUID, ShieldState>()
    private val skillDamageKey = org.bukkit.NamespacedKey(plugin, "skill_damage")
    private val mythicMobs = MythicMobsHook()

    @EventHandler(ignoreCancelled = true)
    fun onEnchantItem(event: EnchantItemEvent) {
        if (!plugin.itemService.isSourceEquipment(event.item)) return
        event.isCancelled = true
        event.enchanter.sendMessage("§c[SourceForge] §fSourceForge 装备不能使用原版附魔")
    }

    @EventHandler
    fun onPrepareAnvil(event: PrepareAnvilEvent) {
        val first = event.inventory.firstItem
        val second = event.inventory.secondItem
        if (!plugin.itemService.isSourceEquipment(first) && !plugin.itemService.isSourceEquipment(second)) return
        event.result = null
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action !in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)) return
        val player = event.player
        val weapon = player.inventory.itemInMainHand
        if (!plugin.itemService.isSourceEquipment(weapon)) return
        val active = plugin.enchantService.enchants()
            .firstNotNullOfOrNull { enchant ->
                if (enchant.action == "passive") return@firstNotNullOfOrNull null
                val level = plugin.enchantService.readEnchantLevel(weapon, enchant.id)
                if (level > 0) enchant to level else null
            }
            ?: return
        val (enchant, level) = active

        val now = System.currentTimeMillis()
        val params = plugin.enchantService.params(enchant.id, level)
        val cooldownMillis = params.double("cooldown-millis", 4_000.0).toLong().coerceAtLeast(1_000L)
        val skillId = enchant.id
        val readyAt = activeSkillCooldowns[SkillCooldownKey(player.uniqueId, skillId)] ?: 0L
        if (now < readyAt) {
            val seconds = ((readyAt - now) / 1000.0).coerceAtLeast(0.1)
            player.sendMessage("§c[SourceForge] §f${enchant.displayName}冷却中: ${"%.1f".format(seconds)} 秒")
            return
        }

        if (!castActiveEnchant(player, enchant, params)) return
        activeSkillCooldowns[SkillCooldownKey(player.uniqueId, skillId)] = now + cooldownMillis
        applyActiveSkillItemCooldown(player, weapon, cooldownMillis)
    }

    private fun castActiveEnchant(player: Player, enchant: SourceEnchant, params: EnchantLevelConfig): Boolean {
        return when (enchant.action) {
            "blink" -> {
                castBlink(player, params)
                true
            }
            "element_nova" -> {
                castElementNova(player, params)
                true
            }
            "heal" -> {
                castHeal(player, params)
                true
            }
            "shield" -> {
                castShield(player, params)
                true
            }
            "shockwave_active" -> {
                castShockwave(player, params)
                true
            }
            "thunder_dash" -> {
                castThunderDash(player, params)
                true
            }
            "mythic_skill" -> castMythicSkill(player, enchant)
            else -> false
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onShieldDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val shield = activeShields[player.uniqueId] ?: return
        val now = System.currentTimeMillis()
        if (shield.expiresAt <= now || shield.remaining <= 0.0) {
            activeShields.remove(player.uniqueId)
            return
        }
        val absorbed = minOf(event.damage, shield.remaining)
        event.damage = (event.damage - absorbed).coerceAtLeast(0.0)
        val remaining = shield.remaining - absorbed
        if (remaining <= 0.0) {
            activeShields.remove(player.uniqueId)
            player.world.playSound(player.location, Sound.BLOCK_GLASS_BREAK, 0.6f, 1.6f)
        } else {
            activeShields[player.uniqueId] = shield.copy(remaining = remaining)
        }
    }

    private fun castMythicSkill(player: Player, enchant: SourceEnchant): Boolean {
        val skill = enchant.mythicSkill
        if (skill.isNullOrBlank()) {
            player.sendMessage("§c[SourceForge] §f${enchant.displayName} 未配置 mythic-skill")
            return false
        }
        return when (mythicMobs.castSkill(player, skill)) {
            MythicMobsHook.CastResult.SUCCESS -> true
            MythicMobsHook.CastResult.MISSING_PLUGIN -> {
                player.sendMessage("§c[SourceForge] §f该技能需要前置插件 MythicMobs")
                false
            }
            MythicMobsHook.CastResult.UNKNOWN_SKILL -> {
                player.sendMessage("§c[SourceForge] §fMythicMobs 技能不存在或释放失败: $skill")
                false
            }
            MythicMobsHook.CastResult.UNSUPPORTED_API -> {
                player.sendMessage("§c[SourceForge] §f当前 MythicMobs API 不兼容，无法释放: $skill")
                false
            }
        }
    }

    private fun applyActiveSkillItemCooldown(player: Player, weapon: ItemStack, cooldownMillis: Long) {
        val ticks = (cooldownMillis / 50L).toInt().coerceAtLeast(1)
        player.setCooldown(weapon.type, ticks)
        plugin.forgeConfig.equipment.values
            .asSequence()
            .filter { !it.weaponCategory.startsWith("armor_") }
            .map { it.material }
            .distinct()
            .forEach { player.setCooldown(it, ticks) }
    }

    private fun castBlink(player: Player, params: EnchantLevelConfig) {
        val distance = params.double("distance", 5.0)
        val destination = player.location.clone().add(player.location.direction.normalizeOrZero().multiply(distance))
        destination.y = destination.world.getHighestBlockYAt(destination).toDouble() + 1.0
        player.world.spawnParticle(Particle.PORTAL, player.location.add(0.0, 1.0, 0.0), 48, 0.4, 0.6, 0.4, 0.08)
        player.teleport(destination)
        player.world.spawnParticle(Particle.PORTAL, player.location.add(0.0, 1.0, 0.0), 48, 0.4, 0.6, 0.4, 0.08)
        player.world.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.25f)
    }

    private fun castElementNova(player: Player, params: EnchantLevelConfig) {
        val element = elementFromParams(params)
        val damage = params.double("damage", elementDamageFromParams(params))
        val radius = params.double("radius", 4.0)
        val particle = when (element) {
            "fire" -> Particle.FLAME
            "ice" -> Particle.SNOWFLAKE
            "lightning" -> Particle.ELECTRIC_SPARK
            "wood" -> Particle.HAPPY_VILLAGER
            "water" -> Particle.SPLASH
            else -> Particle.ENCHANT
        }
        player.world.spawnParticle(particle, player.location.add(0.0, 1.0, 0.0), 72, radius / 2.0, 0.6, radius / 2.0, 0.03)
        player.world.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7f, 1.1f)
        damageNearby(player, radius, element, damage)
    }

    private fun castHeal(player: Player, params: EnchantLevelConfig) {
        val amount = params.double("heal", params.double("heal-amount", 4.0))
        heal(player, amount)
        player.world.spawnParticle(Particle.HEART, player.location.add(0.0, 1.4, 0.0), 8, 0.35, 0.35, 0.35, 0.02)
        player.world.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.45f, 1.8f)
    }

    private fun castShield(player: Player, params: EnchantLevelConfig) {
        val amount = params.double("shield", params.double("shield-amount", 6.0))
        val duration = params.double("duration-millis", 6000.0).toLong().coerceAtLeast(1000L)
        activeShields[player.uniqueId] = ShieldState(amount, System.currentTimeMillis() + duration)
        player.world.spawnParticle(Particle.ENCHANT, player.location.add(0.0, 1.0, 0.0), 48, 0.5, 0.7, 0.5, 0.04)
        player.world.playSound(player.location, Sound.ITEM_SHIELD_BLOCK, 0.8f, 1.1f)
    }

    private fun castShockwave(player: Player, params: EnchantLevelConfig) {
        val radius = params.double("radius", 4.0)
        val damage = params.double("damage", 4.0)
        val strength = params.double("knockback-strength", 0.9)
        player.world.spawnParticle(Particle.CLOUD, player.location, 64, radius / 2.0, 0.15, radius / 2.0, 0.08)
        player.world.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 0.45f, 1.45f)
        player.getNearbyEntities(radius, radius, radius)
            .asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it.uniqueId != player.uniqueId && !it.isDead }
            .forEach { target ->
                val direction = target.location.toVector().subtract(player.location.toVector()).normalizeOrZero()
                target.velocity = target.velocity.add(direction.multiply(strength).setY(0.25))
                target.damage(damage, player)
            }
    }

    private fun castThunderDash(player: Player, params: EnchantLevelConfig) {
        val direction = player.location.direction
        val launch = direction.clone().normalizeOrZero()
            .multiply(params.double("launch-strength", 0.8))
            .setY(params.double("launch-y", 0.25))
        player.velocity = player.velocity.add(launch)
        player.world.spawnParticle(Particle.ELECTRIC_SPARK, player.location.add(0.0, 1.0, 0.0), 32, 0.6, 0.5, 0.6, 0.08)
        player.world.playSound(player.location, Sound.ITEM_TRIDENT_THUNDER, 0.65f, 1.35f)

        val radius = params.double("radius", 2.4)
        val damage = params.double("lightning-damage", 0.0)
        val source = DamageSource.builder(DamageType.LIGHTNING_BOLT)
            .withDamageLocation(player.location)
            .build()
        player.getNearbyEntities(radius, radius, radius)
            .asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it.uniqueId != player.uniqueId && !it.isDead }
            .forEach { target ->
                damageSkillTarget(target, player, "lightning", damage, source)
            }
    }

    private fun damageNearby(player: Player, radius: Double, element: String, damage: Double) {
        val source = DamageSource.builder(damageTypeForElement(element))
            .withDamageLocation(player.location)
            .build()
        player.getNearbyEntities(radius, radius, radius)
            .asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it.uniqueId != player.uniqueId && !it.isDead }
            .forEach { damageSkillTarget(it, player, element, damage, source) }
    }

    private fun damageSkillTarget(
        target: LivingEntity,
        player: Player,
        element: String,
        damage: Double,
        source: DamageSource
    ) {
        val finalDamage = if (target is Player) {
            damage
        } else {
            calculateMonsterElementDamage(target, element, damage)
        }
        if (finalDamage <= 0.0) return
        if (target !is Player) {
            target.persistentDataContainer.set(skillDamageKey, PersistentDataType.INTEGER, 1)
        }
        target.damage(finalDamage, source)
        if (target !is Player) {
            target.persistentDataContainer.remove(skillDamageKey)
        }
    }

    private fun damageTypeForElement(element: String): DamageType {
        return when (element) {
            "fire" -> DamageType.IN_FIRE
            "ice" -> DamageType.FREEZE
            "lightning" -> DamageType.LIGHTNING_BOLT
            else -> DamageType.INDIRECT_MAGIC
        }
    }

    private fun elementFromParams(params: EnchantLevelConfig): String {
        return listOf("fire", "ice", "lightning", "water", "wood")
            .firstOrNull { params.double("$it-damage") > 0.0 }
            ?: "none"
    }

    private fun elementDamageFromParams(params: EnchantLevelConfig): Double {
        return listOf("fire", "ice", "lightning", "water", "wood")
            .maxOf { params.double("$it-damage") }
    }

    private fun calculateMonsterElementDamage(target: LivingEntity, element: String, rawDamage: Double): Double {
        if (rawDamage <= 0.0) return 0.0
        val profile = targetProfile(target)
        val resistance = profile.elementResistances[element] ?: 0.0
        val multiplier = 1.0 - resistance / 100.0
        if (multiplier < 0.0) {
            heal(target, rawDamage * -multiplier)
            return 0.0
        }
        val reaction = elementReactionMultiplier(element, profile)
        return maxOf(1.0, rawDamage * multiplier * reaction)
    }

    private fun elementReactionMultiplier(attackerElement: String, profile: MonsterProfileConfig): Double {
        if (profile.elements.isEmpty()) return 1.0
        val rules = plugin.forgeConfig.combat.elementReactions[attackerElement] ?: return 1.0
        var multiplier = 1.0
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

    private fun Vector.normalizeOrZero(): Vector {
        return if (lengthSquared() <= 0.0) Vector(0.0, 0.0, 0.0) else normalize()
    }
}

private data class SkillCooldownKey(
    val playerId: UUID,
    val skillId: String
)

private data class ShieldState(
    val remaining: Double,
    val expiresAt: Long
)
