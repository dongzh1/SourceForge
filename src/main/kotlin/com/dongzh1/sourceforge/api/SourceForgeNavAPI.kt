package com.dongzh1.sourceforge.api

import com.dongzh1.sourceforge.nav.NavigationManager
import org.bukkit.entity.Entity
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

    /**
     * 为玩家添加/更新一个追踪目标（同名覆盖）。[worldLabel] 是跨世界弹窗行展示给玩家的世界
     * 友好名（如"主城"/"荒野·XX的世界"）——SourceForge 不认识调用方的世界体系（ChunkWorld
     * 世界名是 UUID/自定义字符串，SourceForge 无从翻译），一律由调用方传入；缺省时退化显示
     * 原始 Bukkit 世界名（world 参数本身）。
     */
    @JvmStatic
    @JvmOverloads
    fun track(player: Player, name: String, x: Double, y: Double, z: Double, world: String, color: String? = null, worldLabel: String? = null): Boolean {
        val n = nav ?: return false
        val c = NavigationManager.resolveColor(color) ?: NavigationManager.resolveColor(null)!!
        n.track(player, name, x, y, z, world, c.hex, c.icon, worldLabel ?: world)
        return true
    }

    /**
     * 为玩家添加/更新一个追踪目标，绑定一个会移动的实体（Player/NPC/坐骑等，不限 Player）——
     * 与 [track] 添加的坐标目标共用同一份列表，同一个弹窗/指南针/路标渲染，按当前距离自动排序。
     * 实体消失（下线/死亡/被移除）后会在下一次刷新自动从追踪列表摘除，调用方无需自行监听清理。
     *
     * @return false：SourceForge 未 bind（未启用完成），或调用时 entity 已死亡/失效/所在世界不可解析。
     */
    @JvmStatic
    @JvmOverloads
    fun trackEntity(player: Player, name: String, entity: Entity, color: String? = null, worldLabel: String? = null): Boolean {
        val n = nav ?: return false
        val c = NavigationManager.resolveColor(color) ?: NavigationManager.resolveColor(null)!!
        return n.trackEntity(player, name, entity, c.hex, c.icon, worldLabel)
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
