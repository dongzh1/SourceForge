package com.dongzh1.sourceforge.api

import com.dongzh1.sourceforge.nav.NavigationManager
import org.bukkit.entity.Player

/**
 * SourceForge 对外公开导航 API。供 SourceTasks 等外部插件用 compileOnly + 真调用消费，
 * 避免 console 命令派发。颜色接受命名色（white/red/...）或 #RRGGBB，非法时回退默认白。
 * SourceForge 未启用完成前调用将被静默忽略（返回 false）。
 */
object SourceForgeNavAPI {

    @Volatile
    private var nav: NavigationManager? = null

    @JvmStatic
    fun bind(manager: NavigationManager) {
        nav = manager
    }

    /** 为玩家添加/更新一个追踪目标（同名覆盖）。 */
    @JvmStatic
    @JvmOverloads
    fun track(player: Player, name: String, x: Double, y: Double, z: Double, world: String, color: String? = null): Boolean {
        val n = nav ?: return false
        val c = NavigationManager.resolveColor(color) ?: NavigationManager.resolveColor(null)!!
        n.track(player, name, x, y, z, world, c.hex, c.icon)
        return true
    }

    /** 移除一个追踪目标。返回是否确实存在并移除。 */
    @JvmStatic
    fun untrack(player: Player, name: String): Boolean = nav?.untrack(player, name) ?: false

    /** 清空玩家所有追踪目标。返回是否原本有目标。 */
    @JvmStatic
    fun stopTracking(player: Player): Boolean = nav?.stop(player) ?: false

    @JvmStatic
    fun isTracking(player: Player): Boolean = nav?.isTracking(player) ?: false

    @JvmStatic
    fun targetNames(player: Player): List<String> = nav?.targetNames(player) ?: emptyList()
}
