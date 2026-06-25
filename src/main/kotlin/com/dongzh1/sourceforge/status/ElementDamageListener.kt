package com.dongzh1.sourceforge.status

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent

/**
 * 通用「元素增伤」放大器：怪物受到伤害时，如果它身上有 AMP 类异常（病毒/腐蚀），按层数放大本次伤害。
 *
 * 走原版 EntityDamageEvent，覆盖一切来源——SF 近战、MythicMobs 技能伤害(MM 多数伤害最终都会触发 Bukkit
 * 伤害事件)、原版攻击、以及本系统自己的 DoT(于是病毒也能放大你的灼烧/中毒)。
 *
 * 在 HIGHEST 触发：晚于 ForgeListener.onDamage(它先把 SF 武器伤害算好)，所以这里乘的是最终伤害。
 * 注册顺序需在 ForgeListener 之后(见 SourceForge.enable)。
 */
class ElementDamageListener(private val plugin: SourceForge) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val victim = event.entity as? LivingEntity ?: return
        if (victim is Player) return
        val mult = plugin.statusManager.outgoingDamageMultiplier(victim)
        if (mult <= 1.0) return
        val before = event.damage
        event.damage = before * mult

        // debug：若造成本次伤害的是开了调试的玩家，打出放大前后与伤害类型
        val dmgr = (event as? org.bukkit.event.entity.EntityDamageByEntityEvent)?.damager
        if (dmgr is Player && plugin.statusManager.isDebug(dmgr.uniqueId)) {
            dmgr.sendMessage(
                "§8[元素] §d增伤×${"%.2f".format(mult)}: §f${"%.1f".format(before)}→§c${"%.1f".format(event.damage)} §8(${event.cause})"
            )
        }
    }
}
