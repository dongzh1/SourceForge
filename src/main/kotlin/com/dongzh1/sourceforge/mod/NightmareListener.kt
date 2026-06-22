package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent

class NightmareListener(
    private val plugin: SourceForge
) : Listener {
    @EventHandler
    fun onDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer ?: return
        plugin.nightmareService.addKillProgress(killer)
    }
}
