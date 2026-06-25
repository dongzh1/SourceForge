package com.dongzh1.sourceforge.status

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor

/**
 * 让元素增伤（病毒/腐蚀等 AMP 异常）作用于 MythicMobs 的伤害类型。
 *
 * 服务器 MM 5.13.0：MM 的技能/元素伤害走自己的伤害系统，不一定经过 Bukkit 的 EntityDamageEvent，
 * 因此普通的伤害监听抓不全。这里直接监听 MM 的 MythicDamageEvent，命中带 AMP 异常的怪时按层数放大 MM 伤害。
 *
 * 用反射注册/读取（与 SourceForgeSkillListener 一致），避免硬编译依赖与版本锁定；MM 不在或方法签名不符时静默降级。
 */
class MythicDamageAmpListener(private val plugin: SourceForge) : Listener {

    fun registerIfAvailable() {
        if (!Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) return
        try {
            val eventClass = Class.forName("io.lumine.mythic.bukkit.events.MythicDamageEvent")
            @Suppress("UNCHECKED_CAST")
            val typed = eventClass as Class<Event>
            Bukkit.getPluginManager().registerEvent(
                typed,
                this,
                EventPriority.HIGH,
                EventExecutor { _, event -> if (eventClass.isInstance(event)) onDamage(event) },
                plugin,
                true
            )
            plugin.logger.info("SourceForge 元素增伤已接入 MythicMobs 伤害(MythicDamageEvent)")
        } catch (e: Exception) {
            plugin.logger.warning("无法接入 MM 伤害增幅: ${e.message}")
        }
    }

    private fun onDamage(event: Any) {
        try {
            val target = event.javaClass.getMethod("getTarget").invoke(event) ?: return
            val bukkit = target.javaClass.getMethod("getBukkitEntity").invoke(target)
            val victim = bukkit as? LivingEntity ?: return

            // MM 伤害按施法玩家的元素属性触发异常叠层（增伤由通用 ElementDamageListener 统一放大，这里不再乘，避免双重）。
            val player = casterPlayer(event)
            if (player != null) plugin.statusManager.procFromPlayerTotals(player, victim)
        } catch (e: Exception) {
            // 不同 MM 版本方法签名可能不同；失败则静默，不影响其它功能。
        }
    }

    /** 尽力从 MythicDamageEvent 取出造成伤害的玩家：先 getDamager()（Bukkit 实体），再退到 getCaster()。 */
    private fun casterPlayer(event: Any): Player? {
        runCatching {
            val d = event.javaClass.getMethod("getDamager").invoke(event)
            if (d is Player) return d
        }
        runCatching {
            val caster = event.javaClass.getMethod("getCaster").invoke(event) ?: return null
            val ae = runCatching { caster.javaClass.getMethod("getEntity").invoke(caster) }.getOrNull() ?: caster
            val be = ae.javaClass.getMethod("getBukkitEntity").invoke(ae)
            if (be is Player) return be
        }
        return null
    }
}
