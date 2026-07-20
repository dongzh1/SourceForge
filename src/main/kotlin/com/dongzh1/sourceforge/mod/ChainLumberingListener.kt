package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import java.util.ArrayDeque

class ChainLumberingListener(private val plugin: SourceForge) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (event.player.gameMode == GameMode.CREATIVE) return

        val tool = event.player.inventory.itemInMainHand
        if (!isAxe(tool)) return

        val rank = plugin.modService.installedModRank(tool, MOD_ID) ?: return
        val config = plugin.modService.mods[MOD_ID] ?: return
        val limit = config.effectAtRank(AFFIX_ID, rank).toInt().coerceIn(0, MAX_CHAIN)
        if (limit <= 0) return

        val origin = event.block
        val originType = origin.type
        if (!isTreeLog(originType)) return

        val visited = HashSet<Long>()
        visited.add(pack(origin.x, origin.y, origin.z))
        val queue = ArrayDeque<Block>()
        queue.add(origin)

        var broken = 0
        while (queue.isNotEmpty() && broken < limit) {
            val current = queue.poll()
            if (current !== origin) {
                if (current.type == originType && current.breakNaturally(tool)) broken++
                if (broken >= limit) break
            }
            for ((dx, dy, dz) in FACES) {
                val next = current.getRelative(dx, dy, dz)
                if (!visited.add(pack(next.x, next.y, next.z))) continue
                if (next.type == originType && isTreeLog(next.type)) queue.add(next)
            }
        }
    }

    companion object {
        private const val MOD_ID = "chain_lumbering"
        private const val AFFIX_ID = "chain_lumbering_count"
        private const val MAX_CHAIN = 15

        private val FACES = arrayOf(
            Triple(1, 0, 0), Triple(-1, 0, 0),
            Triple(0, 1, 0), Triple(0, -1, 0),
            Triple(0, 0, 1), Triple(0, 0, -1)
        )

        private fun isAxe(item: ItemStack): Boolean = item.type.name.endsWith("_AXE")

        private fun isTreeLog(material: Material): Boolean {
            val name = material.name
            if (!name.endsWith("_LOG") && !name.endsWith("_WOOD")) return false
            return !name.startsWith("CRIMSON_") && !name.startsWith("WARPED_")
        }

        private fun pack(x: Int, y: Int, z: Int): Long =
            ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)
    }
}
