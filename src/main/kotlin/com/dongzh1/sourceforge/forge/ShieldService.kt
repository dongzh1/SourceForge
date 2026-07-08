package com.dongzh1.sourceforge.forge

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.persistence.PersistentDataType

/**
 * 护盾系统。从 ForgeListener 拆出（评审 #5），行为不变。
 * 受击先吃盾；脱战 5s 后每 3tick 回 1/20（20 次回满）。存玩家 PDC key shield_current / shield_last_damage。
 * applyShield 由 ForgeListener 的伤害结算调用；回复 tick 由 start() 启动。
 */
class ShieldService(private val plugin: SourceForge) {
    private val shieldCurrentKey = NamespacedKey(plugin, "shield_current")
    private val shieldLastDamageKey = NamespacedKey(plugin, "shield_last_damage")

    fun start() {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            for (player in plugin.server.onlinePlayers) tickShieldRegen(player)
        }, 3L, 3L) // 每 3 tick (0.15s)，20 次回满 = 3 秒
    }

    fun applyShield(target: LivingEntity, event: EntityDamageEvent) {
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
        val stored = player.persistentDataContainer.get(shieldCurrentKey, PersistentDataType.DOUBLE)
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
}
