package com.dongzh1.sourceforge.forge

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.api.SourceForgeCombatAPI
import org.bukkit.Material
import org.bukkit.NamespacedKey
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
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffectType
import kotlin.random.Random

/**
 * SF 战斗结算 + 投射物词条标记。
 * 护盾→ShieldService，能量→EnergyService，锻炉 GUI/锻造→ForgeMenuListener（均已拆出，评审 #5）。
 */
class ForgeListener(
    private val plugin: SourceForge
) : Listener {
    private val skillDamageKey = NamespacedKey(plugin, "skill_damage")
    private val lastRealHitKey = NamespacedKey(plugin, "invuln_last_hit")

    // ==================== 投射物事件 ====================

    @EventHandler(ignoreCancelled = true)
    fun onShoot(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        val weapon = event.bow ?: return
        if (!plugin.itemService.isSourceEquipment(weapon)) return
        val projectile = event.projectile as? Projectile ?: return
        plugin.itemService.markProjectile(projectile, weapon)
        // 2026-07-13 改回原版行为：弓正常消耗箭矢，无限只走"附魔无限箭矢"这一条路——
        // 不用写任何代码，原版对 Infinity 附魔的处理天然就是"不消耗但要求背包至少有1支箭"，
        // SF只要不覆盖 consumeArrow 就自动继承这个行为(前提是 Infinity 在附魔白名单里，
        // 默认放行除诅咒外全部附魔，见 EnchantBridgeConfig，本来就允许)。
        plugin.combatDebug.send(player, "§8[SourceForge Debug] §7远程武器已注入词条数据: ${weapon.type.name}", plugin.forgeConfig.debugCombat)
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
        plugin.combatDebug.send(player, "§8[SourceForge Debug] §7投射武器已注入词条数据: ${weapon.type.name}", plugin.forgeConfig.debugCombat)
    }

    // ==================== 伤害事件 ====================

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val target = event.entity as? LivingEntity ?: return
        // 投射物不伤害发射者本人：高速位移(冲刺/加速类MOD、以后的弩类右键MOD)可能追上自己射出的
        // 箭矢，原版对此本来就有个短暂宽限期，但扛不住 SF 的位移速度，这里直接显式拦掉——
        // 2026-07-13 用户明确要求(弩改右键直射后，追上自己箭矢秒死自己的风险更高)。
        val damagerEntity = event.damager
        if (damagerEntity is Projectile && damagerEntity.shooter === target) {
            event.isCancelled = true
            return
        }
        // 原版盾牌格挡优先：玩家成功格挡这一击时，整段交还给原版处理，
        // 不做 SF 护盾吸收/护甲结算（避免清零 BLOCKING 减伤导致格挡失效）。
        if (isVanillaBlocked(event)) return
        // 无敌帧优先：距上次算数的命中还没过半窗口，本次强制清零（连护盾都不吃），不再往下结算。
        if (isInvulnerabilitySuppressed(target)) {
            setCustomDamage(event, 0.0)
            return
        }
        markRealHit(target)
        when (val damager = event.damager) {
            is Player -> {
                val weapon = damager.inventory.itemInMainHand
                val isSfWeapon = plugin.itemService.isSourceEquipment(weapon)
                val isSfOffhand = plugin.itemService.isSourceEquipment(damager.inventory.itemInOffHand)
                val hasSfArmor = plugin.itemService.hasSourceArmor(damager)
                // 无真实SF装备但有外部词缀加成(如 PixelRPG 副本临时强化，纯靠 ExternalAffixProvider
                // 生效、从不发真实SF物品)：这类玩家也要走战斗结算，否则暴击/元素/调试全部被跳过，
                // 只有 readTotalAffix 查询能读到加成，实际命中却是"裸伤害"。
                val hasExternalAffix = plugin.itemService.externalProviderCount() > 0 &&
                    plugin.itemService.externalProviderTotals(damager).isNotEmpty()
                val hasCombatModifier = SourceForgeCombatAPI.hasDamageMultipliers(damager)
                if (isSfWeapon || isSfOffhand || hasSfArmor || hasExternalAffix || hasCombatModifier) {
                    if (isSfWeapon) plugin.itemService.stripVanillaEnchantments(weapon)
                    applyCombat(
                        player = damager,
                        target = target,
                        weapon = weapon.takeIf { isSfWeapon },
                        baseDamage = normalizeGoldenWineIndicatorDamage(damager, event.damage)
                    ) { event.damage = it }
                }
            }
            is Projectile -> {
                val player = damager.shooter as? Player
                val isSfProjectile = plugin.itemService.isSourceProjectile(damager)
                val hasSfOffhand = player?.let { plugin.itemService.isSourceEquipment(it.inventory.itemInOffHand) } == true
                if (player != null && (isSfProjectile || hasSfOffhand ||
                        SourceForgeCombatAPI.hasDamageMultipliers(player))) {
                    val projectileBaseDamage = if (isSfProjectile) {
                        plugin.itemService.readAffixValue(damager.persistentDataContainer, "base_damage")
                            .takeIf { it > 0.0 } ?: event.damage
                    } else {
                        event.damage
                    }
                    applyCombat(
                        player = player,
                        target = target,
                        weapon = null,
                        projectilePdc = damager.persistentDataContainer,
                        baseDamage = projectileBaseDamage
                    ) { event.damage = it }
                }
            }
            else -> Unit
        }
        plugin.shieldService.applyShield(target, event)
        applyDefense(target, event)
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    fun onGenericDamage(event: EntityDamageEvent) {
        if (event is EntityDamageByEntityEvent) return
        val target = event.entity as? LivingEntity ?: return
        // 原版盾牌格挡优先：交还给原版处理，跳过 SF 护盾/护甲结算。
        if (isVanillaBlocked(event)) return
        // 无敌帧优先：岩浆/烈焰/仙人掌等每 tick 触发的重复伤害，距上次算数命中还没过半窗口就强制清零。
        if (isInvulnerabilitySuppressed(target)) {
            setCustomDamage(event, 0.0)
            return
        }
        markRealHit(target)
        plugin.shieldService.applyShield(target, event)
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
        // 元素/暴击/技能属性一律按【全身合计】读取（readTotalAffix，含防具 / 各槽位 MOD / 外部 Provider
        // 如 PixelRPG 副本临时强化），与 /sf stats、暴击、MythicMobs 触发路径口径完全一致。
        // 旧版元素块曾误用「只读手持武器 PDC」的 readValue：外部 Provider 注入的 status_chance / 基础元素
        // 在近战与弹射物命中时都读到 0，导致异常【完全不触发】，尽管 /sf stats 显示 150%。暴击当初踩过
        // 同一个坑（见下方注释）并已切到 readTotalAffix，这里把最后一个还在旧路径上的消费者（元素）也一并归位。

        // 暴击率 / 暴击伤害按【全身合计】读取，与 /sf stats 显示口径一致。
        // 旧版只读手持武器 PDC（readValue）——若暴击来自防具/MOD，武器上读到 0，导致即使总暴击率 100% 也永不暴击。
        val critChance = plugin.itemService.readTotalAffix(player, "critical_chance")
        val critDamageBonus = plugin.itemService.readTotalAffix(player, "critical_damage")
        // 技能强度按设计【不计入】伤害（仅作为 PAPI 值，由 MM 技能在 amount 里自行乘算）；此处仅供调试展示。
        val abilityStrength = plugin.itemService.readTotalAffix(player, "ability_strength")

        // 劫持传入伤害：以本次事件的【传入伤害】为基准——
        //   · MM damage 技能：调用方用 PAPI(基础伤害[×强度])算好的 amount
        //   · 原版攻击：武器 base_damage 已写入 ATTACK_DAMAGE 属性后的攻击伤害
        // 不再用武器 base_damage 覆盖，避免抹掉调用方(MM/PAPI)已经算入的加成。
        val incomingDamage = baseDamage
        var totalDamage = incomingDamage

        // 暴击判定（全身暴击率/暴击伤害）
        val critRoll = Random.nextDouble()
        val critTriggered = critChance > 0.0 && critRoll < critChance
        val critMultiplier = if (critTriggered) 1.0 + (if (critDamageBonus > 0.0) critDamageBonus else 0.5) else 1.0
        val damageBeforeCrit = totalDamage
        if (critTriggered) totalDamage *= critMultiplier

        // ===== 元素：直伤附加 + 异常触发（status_chance 可超100%，多次触发）=====
        var elementDirect = 0.0
        val ec = plugin.elementConfig
        if (ec.enabled) {
            val baseVals = LinkedHashMap<com.dongzh1.sourceforge.status.ElementType, Double>()
            var elemSum = 0.0
            for (def in ec.active) {
                if (!def.type.isBase) continue   // 组合元素由怪物侧检测得到，不直接从装备读
                val v = plugin.itemService.readTotalAffix(player, def.affix)
                if (v > 0.0) { baseVals[def.type] = v; elemSum += v }
            }
            if (elemSum > 0.0) {
                // 没触发也有用：各基础元素属性之和按系数直接加进伤害
                elementDirect = elemSum * ec.directDamageFactor
                totalDamage += elementDirect
                // 注：AMP 增伤（病毒/腐蚀）改由通用伤害监听 ElementDamageListener 统一放大，
                // 覆盖 SF 近战/MM/原版/DoT 所有来源，这里不再单独乘，避免双重。
                val statusChance = plugin.itemService.readTotalAffix(player, "status_chance")
                val guaranteed = kotlin.math.floor(statusChance).toInt()
                val frac = statusChance - guaranteed
                val debugOn = plugin.statusManager.isDebug(player.uniqueId)
                val dbg = if (debugOn) StringBuilder() else null
                // 内置触发 CD：同一(玩家→怪)0.15s 内只触发一次，堵住 SF+MM 双路径与高频多段
                val gateOpen = statusChance > 0.0 && plugin.statusManager.tryTriggerGate(player, target)
                if (gateOpen) {
                    // 基础元素各自独立按 status_chance 叠到怪身上（不在武器侧融合）
                    for ((type, _) in baseVals) {
                        var procs = guaranteed
                        if (frac > 0.0 && Random.nextDouble() < frac) procs++
                        if (procs > 0) plugin.statusManager.applyStacks(target, type, procs, player, cause = "普攻")
                        dbg?.append("${type.id}×$procs ")
                    }
                    // 复合检测（怪物侧，非消耗）：怪身上多种基础同时存在时额外触发对应组合（含队友凑出的元素）
                    plugin.statusManager.applyCombosFromMonster(target, player)
                } else if (debugOn && statusChance > 0.0) {
                    dbg?.append("触发CD中跳过")
                }
                if (debugOn) {
                    player.sendMessage(
                        "§8[元素debug·普攻命中] §7基础元素=§f${baseVals.entries.joinToString(",") { "${it.key.id}${"%.1f".format(it.value)}" }.ifEmpty { "无" }} " +
                            "§7status=§f${"%.2f".format(statusChance)}"
                    )
                    player.sendMessage(
                        "§8[元素debug·普攻命中] §7本次触发=§a${dbg?.toString()?.trim()?.ifEmpty { "无" } ?: "无"} " +
                            "§7增伤×${"%.2f".format(plugin.statusManager.outgoingDamageMultiplier(target))} §7目标层数: §f${plugin.statusManager.stacksSummary(target)}"
                    )
                }
            }
        }

        val outputMultiplier = SourceForgeCombatAPI.outgoingDamageMultiplier(player, target)
        totalDamage *= outputMultiplier

        applyDamage(totalDamage)

        if (plugin.forgeConfig.debugCombat || plugin.combatDebug.isWatched(player)) {
            val srcDesc = when {
                projectilePdc != null -> "弹射物"
                weapon != null -> "武器(${weapon.type.name})"
                else -> "全身防具(无SF武器)"
            }
            val targetName = (target as? Player)?.name ?: target.type.name
            plugin.combatDebug.send(player, "§6[SF战斗] §f${player.name} §7→ §f$targetName  §8| 来源: $srcDesc", plugin.forgeConfig.debugCombat)
            plugin.combatDebug.send(player, "  §7① 传入伤害(MM/原版): §f${"%.2f".format(incomingDamage)}", plugin.forgeConfig.debugCombat)
            if (critTriggered) {
                plugin.combatDebug.send(player,
                    "  §7② 暴击: §f${"%.1f".format(critChance * 100)}%§7 掷骰 §f${"%.3f".format(critRoll)} §7→ §a暴击! " +
                        "§7倍率 §f×${"%.2f".format(critMultiplier)} §7(+${"%.0f".format(critDamageBonus * 100)}%)  " +
                        "§f${"%.2f".format(damageBeforeCrit)}§7→§f${"%.2f".format(damageBeforeCrit * critMultiplier)}",
                    plugin.forgeConfig.debugCombat
                )
            } else {
                plugin.combatDebug.send(
                    player,
                    "  §7② 暴击: §f${"%.1f".format(critChance * 100)}%§7 掷骰 §f${"%.3f".format(critRoll)} §7→ §c未暴击",
                    plugin.forgeConfig.debugCombat
                )
            }
            if (elementDirect > 0.0) plugin.combatDebug.send(player, "  §7③ 元素直伤: §f+${"%.2f".format(elementDirect)}", plugin.forgeConfig.debugCombat)
            if (outputMultiplier != 1.0) plugin.combatDebug.send(player, "  §7输出倍率: §e×${"%.2f".format(outputMultiplier)}", plugin.forgeConfig.debugCombat)
            plugin.combatDebug.send(player, "  §8· 技能强度(全身)=${"%.1f".format(abilityStrength * 100)}% §8[仅PAPI, 不计入伤害]", plugin.forgeConfig.debugCombat)
            plugin.combatDebug.send(player, "  §e最终伤害: §c${"%.2f".format(totalDamage)}", plugin.forgeConfig.debugCombat)
        }

        return totalDamage.toFloat()
    }

    private fun normalizeGoldenWineIndicatorDamage(player: Player, damage: Double): Double {
        if (!plugin.sectService.hasGoldenWineIndicator(player)) return damage
        val strength = player.getPotionEffect(PotionEffectType.STRENGTH) ?: return damage
        val vanillaStrengthBonus = 3.0 * (strength.amplifier + 1)
        return (damage - vanillaStrengthBonus).coerceAtLeast(0.0)
    }

    /**
     * 防御结算：
     * - 读取目标护甲值 (Attribute.ARMOR)
     * - 使用攻防公式: attackPower / (attackPower + armor)
     * - 所有原版减伤阶段归零，换成上面这条自研公式
     * - 公式算完后，再乘一层原版"保护"附魔家族的减伤（VanillaEnchantBridge.armorDamageMultiplier）——
     *   这层是独立叠加的原版增益，不是自研公式的一部分，顺序与原版真实计算顺序一致（先护甲值、后附魔）。
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
        val protMult = com.dongzh1.sourceforge.enchant.VanillaEnchantBridge.armorDamageMultiplier(plugin, target, event.cause)
        val final = (defended * protMult).coerceAtLeast(0.0)
        setCustomDamage(event, final)

        if (plugin.forgeConfig.debugCombat || plugin.combatDebug.isWatched(target as? Player ?: return)) {
            val msg = "§8[SourceForge Debug] §7防御结算: " +
                "攻击力=${"%.2f".format(attackPower)}, " +
                "护甲=${"%.2f".format(armor)}, " +
                "伤害=${"%.2f".format(incoming)} -> ${"%.2f".format(defended)} " +
                "-> 附魔保护×${"%.2f".format(protMult)} -> ${"%.2f".format(final)}"
            (target as? Player)?.let { player -> plugin.combatDebug.send(player, msg, plugin.forgeConfig.debugCombat) }
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

    /**
     * 本次伤害是否被原版盾牌成功格挡。
     * 原版在玩家正面举盾格挡可格挡伤害时，会写入负值的 BLOCKING 减伤阶段。
     * 只有 LivingEntity 受击且 BLOCKING 阶段非零才算格挡——非玩家、未格挡或不可格挡来源均为 false。
     */
    @Suppress("DEPRECATION")
    private fun isVanillaBlocked(event: EntityDamageEvent): Boolean {
        if (event.entity !is Player) return false
        return try {
            event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING) &&
                event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) != 0.0
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
    }

    /**
     * 本次伤害是否落在原版"无敌帧"窗口内，应被压制。
     *
     * 第一版实现读 Bukkit EntityDamageEvent 的 INVULNERABILITY_REDUCTION 修正阶段，结果实测无效——
     * 服务器是否往这个阶段的 modifiers map 里塞值、塞的符号是什么，是混淆过的 Paper 服务端内部行为，
     * Bukkit API 本身不保证、我们也验证不了，岩浆这个 cause 大概率根本没走这条路径。
     *
     * 现在改成完全不依赖该 modifier：用插件自己的 PDC 时间戳独立记录"上一次算数的命中是什么时候"，
     * 距今不到半个无敌帧窗口（maximumNoDamageTicks/2 个 tick，换算成毫秒）就判定为压制。窗口时长跟着
     * 玩家当前的 maximumNoDamageTicks 走（正常是原版默认 20 tick → 500ms），不用魔法数字硬编码窗口。
     * 只做时间门控，不复刻原版"更强一击能提前打破无敌帧"的极少见分支——同伤害源重复 tick(岩浆/烈焰/
     * 仙人掌)本来就是同一伤害量的重复命中，纯时间门控已经够用，且偏向"判压制"对玩家更安全。
     */
    private fun isInvulnerabilitySuppressed(target: LivingEntity): Boolean {
        val player = target as? Player ?: return false
        val maxTicks = player.maximumNoDamageTicks
        if (maxTicks <= 0) return false
        val windowMs = (maxTicks / 2) * 50L
        if (windowMs <= 0L) return false
        val last = player.persistentDataContainer.get(lastRealHitKey, PersistentDataType.LONG) ?: return false
        return System.currentTimeMillis() - last < windowMs
    }

    /** 记录本次是"算数"的命中，作为下一次无敌帧压制判定的基准时间点。 */
    private fun markRealHit(target: LivingEntity) {
        val player = target as? Player ?: return
        player.persistentDataContainer.set(lastRealHitKey, PersistentDataType.LONG, System.currentTimeMillis())
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
