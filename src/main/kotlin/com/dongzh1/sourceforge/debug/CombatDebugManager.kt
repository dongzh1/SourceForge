package com.dongzh1.sourceforge.debug

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CombatDebugManager {
    private val watchers = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    fun setWatcher(viewer: UUID, target: UUID, enabled: Boolean) {
        if (enabled) {
            watchers.computeIfAbsent(target) { ConcurrentHashMap.newKeySet() }.add(viewer)
        } else {
            watchers[target]?.let { viewers ->
                viewers.remove(viewer)
                if (viewers.isEmpty()) watchers.remove(target, viewers)
            }
        }
    }

    fun isWatched(target: Player): Boolean = watchers[target.uniqueId]?.isNotEmpty() == true

    fun isWatching(viewer: UUID, target: UUID): Boolean = watchers[target]?.contains(viewer) == true

    fun send(target: Player, message: String, global: Boolean) {
        if (global) target.sendMessage(message)
        watchers[target.uniqueId]?.forEach { viewerId ->
            if (viewerId != target.uniqueId) Bukkit.getPlayer(viewerId)?.sendMessage(message)
        }
    }
}
