package com.dongzh1.sourceforge.forge

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

class EnhancementRebalanceListener(
    private val plugin: SourceForge
) : Listener {
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            plugin.rebalancePlayerInventory(event.player)
        })
    }
}
