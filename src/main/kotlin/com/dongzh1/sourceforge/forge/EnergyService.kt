package com.dongzh1.sourceforge.forge

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.persistence.PersistentDataType

/**
 * 能量(MANA)系统。从 ForgeListener 拆出（评审 #5），行为不变。
 * MANA = config.yml mana.base（默认100，数值策划 2026-07-08 由 10 上调）+ 装备 energy_max。
 * 存玩家 PDC key "energy_current"。
 */
class EnergyService(private val plugin: SourceForge) : Listener {
    private val energyCurrentKey = NamespacedKey(plugin, "energy_current")

    fun getEnergyMax(player: Player): Double {
        val base = plugin.config.getDouble("mana.base", 100.0)
        return base + plugin.itemService.readDisplayTotalAffix(player, "energy_max")
    }

    /** MANA 被动回复：每秒回 perSecond，由 SkillModListener 的 1s tick 调用。 */
    fun regenMana(player: Player, perSecond: Double) {
        if (perSecond <= 0.0) return
        val max = getEnergyMax(player)
        val cur = getEnergyCurrent(player)
        if (cur < max) setEnergy(player, (cur + perSecond).coerceAtMost(max))
    }

    fun getEnergyCurrent(player: Player): Double {
        val max = getEnergyMax(player)
        val stored = player.persistentDataContainer.get(energyCurrentKey, PersistentDataType.DOUBLE)
        return if (stored == null) {
            setEnergy(player, max)
            max
        } else {
            stored.coerceAtMost(max)
        }
    }

    fun setEnergy(player: Player, value: Double) {
        val max = getEnergyMax(player)
        player.persistentDataContainer.set(energyCurrentKey, PersistentDataType.DOUBLE, value.coerceIn(0.0, max))
    }

    fun deductEnergy(player: Player, amount: Double): Boolean {
        val current = getEnergyCurrent(player)
        if (current < amount) return false
        setEnergy(player, current - amount)
        return true
    }

    @EventHandler
    fun onJoinInitEnergy(event: PlayerJoinEvent) {
        val player = event.player
        if (getEnergyMax(player) > 0) getEnergyCurrent(player) // 触发初始化到满
    }
}
