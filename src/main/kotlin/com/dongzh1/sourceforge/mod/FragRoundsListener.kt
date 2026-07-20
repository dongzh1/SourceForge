package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.particle.SfTargeters
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent

/**
 * 破片弹(frag_rounds MOD)——硬编码而非纯 effects 数值缩放，理由见 mods/frag_rounds.yml 头部注释：
 * 溅射需要"命中点半径内额外找目标 + 按本次实际伤害算比例 + 上限截断"，属于自定义结算逻辑，
 * 不是简单的"叠加到SF内部战斗数值"。
 *
 * 挂载点：参考 shock 元素"命中后向邻近目标转移1层电"的做法(StatusEffectManager.applyStacks 里
 * ElementEffect.BURST 分支，nearestOther 找附近目标)——但那条路径是在 ForgeListener.applyCombat
 * 内部触发的，改不了共享文件，所以这里改用完全独立的 EntityDamageByEntityEvent 监听器，
 * 在 MONITOR 优先级读取，此时 ForgeListener(HIGHEST) 已经把暴击/元素直伤/护盾/护甲全部结算完，
 * event.finalDamage 就是这一下【实际】打在目标身上的伤害——用它 ×词条值 作为溅射基数，
 * 语义对应设计里"本次伤害×X"最自然的解读(打多少算多少，而不是打之前的原始数值)。
 *
 * 判定"这是不是SF远程武器命中"：不依赖武器 ItemStack 本身(命中时玩家手上可能已经换了别的武器)，
 * 而是读弹射物自己的 PDC——markProjectile() 在弓/弩/后续火器发射时，把武器身上 splash_power 的
 * 【已按段位缩放好的最终值】原样复制到了弹射物 PDC 的同名词条键上（这一步和 critical_chance /
 * status_chance 等其它词条走的是同一条既有管线，见 ForgeListener.applyCombat 的 projectilePdc 分支），
 * 所以这里只需要 readAffixValue(弹射物PDC, "splash_power") 就能拿到"这一箭"该按多少比例溅射，
 * 完全不用重新查 ModService 的段位/rank。
 *
 * 找溅射目标复用 SfTargeters.radius()（项目现成的"周围活体"轮子，自带排除施法目标本人/排除玩家/
 * 排除死亡实体/球形精确半径过滤），中心用弹射物命中瞬间的坐标(不是原目标的坐标，虽然两者通常
 * 只差几格，但语义上"命中点"更贴近箭矢自身位置)，caster 传原目标以复用其"排除自己"逻辑。
 * 按距离排序取最近的 5 个，对应设计"单次最多波及5个额外目标"的硬上限。
 *
 * 每个额外目标调用 LivingEntity.damage(amount)（无 damager，同 StatusEffectManager.damageMob 的写法），
 * 会各自重新触发一次 EntityDamageEvent(非ByEntity)，ForgeListener.onGenericDamage 照常对它们应用
 * 护盾/护甲结算——这跟毒气云(GAS)波及周围怪的既有行为完全一致，不需要特殊处理；也因为它不是
 * EntityDamageByEntityEvent，不会递归回本监听器造成无限连锁。
 */
class FragRoundsListener(private val plugin: SourceForge) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHit(event: EntityDamageByEntityEvent) {
        val target = event.entity as? LivingEntity ?: return
        val projectile = event.damager as? Projectile ?: return
        if (!plugin.itemService.isSourceProjectile(projectile)) return

        val splashPower = plugin.itemService.readAffixValue(projectile.persistentDataContainer, AFFIX_ID)
        if (splashPower <= 0.0) return

        val finalDamage = event.finalDamage
        if (finalDamage <= 0.0) return
        val splashDamage = finalDamage * splashPower
        if (splashDamage <= 0.0) return

        val impact = projectile.location
        val victims = SfTargeters.radius(impact, RADIUS, target, SfTargeters.Filter.ENEMIES)
            .sortedBy { it.location.distanceSquared(impact) }
            .take(MAX_EXTRA_TARGETS)

        for (victim in victims) {
            runCatching {
                victim.noDamageTicks = 0
                victim.damage(splashDamage)
            }
        }
    }

    companion object {
        private const val AFFIX_ID = "splash_power"
        private const val RADIUS = 2.5
        private const val MAX_EXTRA_TARGETS = 5
    }
}
