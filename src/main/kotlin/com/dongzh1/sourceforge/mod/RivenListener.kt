package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent

class RivenListener(private val plugin: SourceForge) : Listener {
    @EventHandler
    fun onDeath(event: EntityDeathEvent) {
        plugin.rivenService.addKillProgress(event)
    }
}
