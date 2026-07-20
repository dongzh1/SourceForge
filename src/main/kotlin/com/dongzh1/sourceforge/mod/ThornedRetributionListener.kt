package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 荆棘反震(thorned_retribution MOD)——护甲(armor_physical)专属被动。佩戴者受到【近战】命中时，
 * 把这一下的最终伤害(event.finalDamage)按段位比例反弹给攻击者，反弹的是真实伤害(绕开护盾/护甲/抗性)。
 *
 * 挂在 MONITOR：ForgeListener.onDamage 已在 HIGHEST 优先级完成暴击/元素/护盾吸收/护甲减伤的全部结算，
 * 此时 finalDamage 就是佩戴者这一下真正掉的血——按"实际吃了多少伤害"的比例反震，口径与
 * SiphonRoundsListener("造成伤害的X%回血"用 finalDamage)一致。
 *
 * 近战判定：damager 是 LivingEntity 且不是 Projectile(排除箭矢/三叉戟等弹射物命中；弹射物命中不算
 * "近战命中")。攻击者与佩戴者相同时(理论上不该出现)显式跳过，避免自伤自反成环。
 *
 * 装备判定：applicable-categories 是 armor_physical(护甲单件类别，不是"一整套")，所以任意一件已装
 * 备的物理护甲上都可能装了这张MOD——遍历四个护甲槽取已安装段位的最大值(同一佩戴者理论上只会在
 * 一件护甲上装，取max只是防御性写法，和 ThornsListener 扫全套护甲取原版荆棘等级同一个思路)。
 *
 * 铁壁架势(iron_stance)联动：iron_stance 是纯JS技能，激活状态由 SfScriptApi 统一维护
 * (skillId, playerUUID)。这里直接读 plugin.scriptService.api.activePlayers("iron_stance")，
 * 和技能脚本里 sf.isActive("iron_stance", playerId) 是同一份数据源，不需要额外桥接。
 *
 * 真实伤害实现：不走 LivingEntity.damage(...)，因为那会重新产生一个 EntityDamage(ByEntity)Event，
 * 被 ForgeListener(HIGHEST，共享文件、不可改)当成"新的一次攻击"重新走一遍护盾/护甲/暴击结算——
 * 那样"真实伤害"就名不副实了(哪怕攻击者身上没有护甲，事件本身仍会被 shieldService/applyDefense
 * 摸一遍)。改为直接改 attacker.health，完全绕开 Bukkit 伤害事件管线，是本文件在不碰任何共享文件
 * 前提下唯一能做到"真实伤害"的办法。已知代价(已确认可接受、写在任务报告里)：
 * 1) 若这一下反震直接打死攻击者，不会经过正常的 EntityDamageEvent 死亡归因链路，死亡消息/某些
 *    插件的击杀统计可能不会正确把"击杀者"记成反震佩戴者；
 * 2) 不会触发攻击者身上的"受到伤害"类被动/图腾/无敌帧联动(因为压根没有伤害事件)。
 *
 * 内部冷却：按【佩戴者】UUID 记 0.3 秒冷却(不区分具体攻击者)，防止扫射/多重连击类武器一次连招内
 * 反复触发反震导致数值失控。冷却表存于本类实例内存，随进程存在，不做持久化，插件重载会重置——
 * 可接受(最坏情况只是重载瞬间冷却清零，不会导致伤害计算错误)。
 */
class ThornedRetributionListener(private val plugin: SourceForge) : Listener {

    private val lastProcAt = ConcurrentHashMap<UUID, Long>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? LivingEntity ?: return
        val dealt = event.finalDamage
        if (dealt <= 0.0) return

        val damagerEntity = event.damager
        if (damagerEntity is Projectile) return // 只反震近战命中，弹射物命中不触发
        val attacker = damagerEntity as? LivingEntity ?: return
        if (attacker === victim) return

        val equipment = victim.equipment ?: return
        var bestRank = -1
        for (piece in equipment.armorContents.orEmpty()) {
            val rank = plugin.modService.installedModRank(piece, MOD_ID) ?: continue
            if (rank > bestRank) bestRank = rank
        }
        if (bestRank < 0) return // 没有任何一件护甲装了这张MOD

        val now = System.currentTimeMillis()
        val nextAllowed = lastProcAt[victim.uniqueId] ?: 0L
        if (now < nextAllowed) return // 内部冷却中，跳过这一下

        val config = plugin.modService.mods[MOD_ID] ?: return
        var ratio = config.effectAtRank(AFFIX_ID, bestRank)
        if (ratio <= 0.0) return

        if (plugin.scriptService.api.activePlayers(IRON_STANCE_ID).contains(victim.uniqueId)) {
            ratio += IRON_STANCE_BONUS
        }
        ratio = ratio.coerceAtMost(1.0) // 防御性封顶，避免铁壁架势叠加后反弹超过100%伤害

        lastProcAt[victim.uniqueId] = now + PROC_COOLDOWN_MS

        val reflected = dealt * ratio
        applyTrueDamage(attacker, reflected)
    }

    /** 直接扣血，绕开 EntityDamage(ByEntity)Event，不受护盾/护甲/抗性/无敌帧影响——真正的"真实伤害"。 */
    private fun applyTrueDamage(entity: LivingEntity, amount: Double) {
        if (amount <= 0.0) return
        if (entity.isDead) return
        val maxHealth = entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: entity.health
        entity.health = (entity.health - amount).coerceIn(0.0, maxHealth)
    }

    companion object {
        private const val MOD_ID = "thorned_retribution"
        private const val AFFIX_ID = "thorns_reflect"
        private const val IRON_STANCE_ID = "iron_stance"
        private const val IRON_STANCE_BONUS = 0.20
        private const val PROC_COOLDOWN_MS = 300L
    }
}
