package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.GameMode
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import java.util.ArrayDeque

/**
 * 连锁挖矿(chain_mining MOD)——硬编码而非JS脚本，理由见 mods/chain_mining.yml 头部注释：
 * 单次事件内最多批量处理15个方块，脚本引擎(GraalJS)逐次调用的桥接开销相对原生Kotlin更高。
 *
 * 算法：BFS，6方向面相邻，只收纳与最初打破的方块同 Material 的方块。参考了开源 Veinminer 插件
 * (github.com/MiraculixxT/Veinminer 的 Veinmining.kt)后按需简化：
 * - 它的通用"形状策略"要处理任意几何图案(球形/隧道等)，候选方块不一定跟已选方块同类型，
 *   所以它专门用一个"visited.size >= maxChain"来兜底限制总探索量。本实现只做"同类型flood fill"，
 *   类型检查就在入队那一刻做，队列里存的方块必然都合格——探索量天然跟"实际挖到的方块数"同阶，
 *   不需要再叠一层探索量上限，靠 [broken] 计数本身控制循环终止即可。
 * - visited 用 packed long 坐标做 HashSet key（不用 Block 对象本身，也不拼字符串），入队即标记，
 *   避免同一坐标从多个邻居方向被重复塞进队列。
 * - 显式 ArrayDeque 做 BFS，不用递归——避免大矿脉在极端形状下撑爆调用栈的理论风险。
 * - 上限硬编码 [MAX_CHAIN]=15，MOD 词条数值(随段位0~15浮动)只决定"这次连锁看不看得到15"，
 *   绝不允许配置把这个安全上限本身撑大——这是专门防止单次主线程事件处理过多方块导致卡顿的兜底。
 */
class ChainMiningListener(private val plugin: SourceForge) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        // 创造模式玩家不需要连锁挖矿的辅助，且避免误连锁清空大片地形。
        if (event.player.gameMode == GameMode.CREATIVE) return

        val tool = event.player.inventory.itemInMainHand
        val rank = plugin.modService.installedModRank(tool, MOD_ID) ?: return
        val config = plugin.modService.mods[MOD_ID] ?: return
        val limit = config.effectAtRank(AFFIX_ID, rank).toInt().coerceIn(0, MAX_CHAIN)
        if (limit <= 0) return

        val origin = event.block
        val originType = origin.type

        val visited = HashSet<Long>()
        visited.add(pack(origin.x, origin.y, origin.z))
        val queue = ArrayDeque<Block>()
        queue.add(origin)

        var broken = 0
        while (queue.isNotEmpty() && broken < limit) {
            val current = queue.poll()
            // origin 本身交给原版事件后续流程去破坏(此时它在世界里还没被移除)，这里只处理额外连锁到的方块。
            if (current !== origin) {
                if (current.type == originType && current.breakNaturally(tool)) broken++
                if (broken >= limit) break
            }
            for ((dx, dy, dz) in FACES) {
                val next = current.getRelative(dx, dy, dz)
                if (!visited.add(pack(next.x, next.y, next.z))) continue // 已入队/已判定过，跳过
                if (next.type == originType) queue.add(next)
            }
        }
    }

    companion object {
        private const val MOD_ID = "chain_mining"
        private const val AFFIX_ID = "chain_mining_count"
        private const val MAX_CHAIN = 15

        private val FACES = arrayOf(
            Triple(1, 0, 0), Triple(-1, 0, 0),
            Triple(0, 1, 0), Triple(0, -1, 0),
            Triple(0, 0, 1), Triple(0, 0, -1)
        )

        /** 坐标打包成一个 long 作 HashSet key，避免 Block 对象本身的 equals/hashCode 开销。 */
        private fun pack(x: Int, y: Int, z: Int): Long =
            ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)
    }
}
