package com.dongzh1.sourceforge.enchant

import org.bukkit.entity.LivingEntity
import org.bukkit.enchantments.Enchantment
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import kotlin.random.Random

/**
 * 荆棘(Thorns) 反伤——不落在"增伤"或"减伤"任何一类，是主动 proc 效果，单独一个监听器处理。
 *
 * 与原版口径一致：受击时（伤害来自另一个 LivingEntity 的直接攻击）按 `等级×15%`(封顶100%) 概率反弹
 * 1~4 点固定伤害给攻击者。优先级 MONITOR，在 SF 自己的攻防公式（ForgeListener HIGHEST）算完之后再判定，
 * 反伤基于"这一下最终吃了多少伤害"（[EntityDamageByEntityEvent.getFinalDamage]），不影响本次伤害结算。
 *
 * 反伤用不带 damager 的 `LivingEntity.damage(amount)` 单参重载——这不会触发
 * [org.bukkit.event.entity.EntityDamageByEntityEvent]（避免被 ForgeListener 当成"一次武器攻击"重新走
 * 暴击/元素结算），但仍会触发 [org.bukkit.event.entity.EntityDamageEvent]，攻击者是玩家时其自身的护甲
 * 公式(含保护类附魔)照常对反伤生效，与原版"荆棘伤害不是纯粹的真实伤害"的直觉一致。
 */
class ThornsListener : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? LivingEntity ?: return
        val attacker = event.damager as? LivingEntity ?: return
        if (event.finalDamage <= 0.0) return

        var level = 0
        for (piece in victim.equipment?.armorContents.orEmpty()) {
            level = maxOf(level, piece?.getEnchantmentLevel(Enchantment.THORNS) ?: 0)
        }
        if (level <= 0) return

        val chance = (level * 0.15).coerceAtMost(1.0)
        if (Random.nextDouble() >= chance) return
        attacker.damage((1 + Random.nextInt(4)).toDouble())
    }
}
