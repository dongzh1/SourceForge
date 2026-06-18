package com.dongzh1.sourceforge.nav

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.hud.BetterHudHook
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt

/**
 * 多目标坐标追踪导航。用 /sf track 为玩家累加目标，每 2 tick 计算各目标的距离与
 * Y 差，按距离升序（最多 5 个）推到 BetterHud popup 列表显示；方向交给 BetterHud
 * 原生指南针——每个目标一个 pointer，颜色由指令显式指定（默认白），对应不同颜色
 * 三角(custom-icon)，弹窗行的目标名也用同色。
 *
 * 不直接引用 kr.toxicity.* 类型——所有 BetterHud 交互经 [BetterHudHook]，
 * 缺少 BetterHud 时安全降级（仅不显示）。
 */
class NavigationManager(private val plugin: SourceForge) : Listener {

    data class NavTarget(
        val name: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val world: String,
        /** 弹窗行/箭头文字色（hex 去#）。 */
        val hex: String,
        /** 指南针三角 custom-icon 名（任意 hex 时取最接近的预设）。 */
        val icon: String
    )

    /**
     * uuid -> 该玩家的目标列表。用 CopyOnWriteArrayList：BetterHud 的指针 provider 可能
     * 在非主线程以渲染频率读取本列表，CoW 保证迭代不会抛 ConcurrentModificationException。
     */
    private val targets = ConcurrentHashMap<UUID, CopyOnWriteArrayList<NavTarget>>()

    fun start() {
        // 注册原生指南针指针 provider：BetterHud 主动来拉，reload 安全。
        BetterHudHook.registerPointerProvider(plugin) { uuid ->
            targets[uuid]?.map {
                BetterHudHook.SfPointer(BetterHudHook.pointerName(it.name), it.icon, it.world, it.x, it.y, it.z)
            } ?: emptyList()
        }
        // 监听 BetterHud 重载：重载会清空弹窗状态，需丢弃失效引用以便下一 tick 重建导航弹窗。
        BetterHudHook.registerReloadListener(plugin)
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (targets.isEmpty()) return@Runnable
            for ((uuid, list) in targets) {
                if (list.isEmpty()) continue
                val player = Bukkit.getPlayer(uuid) ?: continue
                if (!player.isOnline) continue
                runCatching { pushNavigation(player, list) }
            }
        }, 2L, 2L)
    }

    /** 累加/更新一个追踪目标（同名则覆盖坐标与颜色）。指针由 provider 自动反映。 */
    fun track(player: Player, name: String, x: Double, y: Double, z: Double, world: String, hex: String, icon: String) {
        val list = targets.getOrPut(player.uniqueId) { CopyOnWriteArrayList() }
        list.removeAll { it.name.equals(name, true) }
        list.add(NavTarget(name, x, y, z, world, hex, icon))
    }

    /** 移除一个目标。返回是否确实存在并移除。指针由 provider 自动反映。 */
    fun untrack(player: Player, name: String): Boolean {
        val list = targets[player.uniqueId] ?: return false
        val removed = list.removeAll { it.name.equals(name, true) }
        if (!removed) return false
        if (list.isEmpty()) {
            targets.remove(player.uniqueId)
            BetterHudHook.hideNavigator(player)
        }
        return true
    }

    /** 清空该玩家所有目标。返回是否原本有目标。指针由 provider 自动反映。 */
    fun stop(player: Player): Boolean {
        val had = targets.remove(player.uniqueId) != null
        BetterHudHook.hideNavigator(player)
        return had
    }

    fun isTracking(player: Player): Boolean = targets[player.uniqueId]?.isNotEmpty() == true

    /** 当前目标名列表（用于 Tab 补全/反馈）。 */
    fun targetNames(player: Player): List<String> = targets[player.uniqueId]?.map { it.name } ?: emptyList()

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        // 保留 targets（重连后由 provider 自动恢复指针与显示），仅清掉弹窗句柄。
        // provider 只对在线/被追踪的 HudPlayer 调用，离线玩家不会产生孤儿指针。
        BetterHudHook.hideNavigator(event.player)
    }

    private fun pushNavigation(player: Player, list: List<NavTarget>) {
        val loc = player.location
        val playerWorld = loc.world?.name

        data class Row(val target: NavTarget, val sameWorld: Boolean, val dist: Double, val dy: Int)

        val rows = list.map { t ->
            val sameWorld = playerWorld == t.world
            if (sameWorld) {
                val dx = t.x - loc.x
                val dyv = t.y - loc.y
                val dz = t.z - loc.z
                Row(t, true, sqrt(dx * dx + dyv * dyv + dz * dz), dyv.toInt())
            } else {
                Row(t, false, Double.MAX_VALUE, 0)
            }
        }

        // 同世界按距离升序在前；跨世界排在最后。取前 5。
        val sorted = rows.sortedWith(compareBy({ !it.sameWorld }, { it.dist })).take(MAX_ROWS)

        val vars = HashMap<String, String>()
        vars["count"] = list.size.toString()
        for (i in 0 until MAX_ROWS) {
            val row = sorted.getOrNull(i)
            val key = "row${i + 1}"
            if (row == null) {
                vars[key] = ""
                vars["${key}_color"] = "FFFFFF"
                continue
            }
            val t = row.target
            // 行色（布局开头 <#rowN_color>）染目标名；之后切白色 <#FFFFFF>，用 | 分隔其余元素。
            // Y 差显示为 上方/下方 + 高度差。
            val yText = when {
                row.dy > 0 -> "上方${row.dy}"
                row.dy < 0 -> "下方${-row.dy}"
                else -> "持平"
            }
            val text = if (row.sameWorld) {
                "${t.name} <#FFFFFF>| ${row.dist.toInt()}m | $yText"
            } else {
                "${t.name} <#FFFFFF>| ${t.world}"
            }
            vars[key] = text
            vars["${key}_color"] = t.hex
        }

        BetterHudHook.showNavigator(plugin, player, plugin.forgeConfig.betterHud.navigatorPopup, vars)
    }

    companion object {
        private const val MAX_ROWS = 5

        /** 默认颜色（未指定时）。 */
        const val DEFAULT_COLOR = "white"

        data class NavColor(val hex: String, val icon: String)

        /**
         * 命名颜色 -> (弹窗行文字 hex 去#, 指南针 custom-icon 名)。
         * icon 名需与 compass 配置里的 custom-icon、以及 assets 里的三角 PNG 一一对应。
         */
        val COLORS: Map<String, NavColor> = linkedMapOf(
            "white"  to NavColor("FFFFFF", "sf_nav_white"),
            "red"    to NavColor("FF5555", "sf_nav_red"),
            "orange" to NavColor("FFAA00", "sf_nav_orange"),
            "yellow" to NavColor("FFFF55", "sf_nav_yellow"),
            "green"  to NavColor("55FF55", "sf_nav_green"),
            "aqua"   to NavColor("55FFFF", "sf_nav_aqua"),
            "blue"   to NavColor("5555FF", "sf_nav_blue"),
            "pink"   to NavColor("FF55FF", "sf_nav_pink")
        )

        /** 解析结果：弹窗 hex（精确，可任意）+ 指南针三角 icon（命名预设/最接近）+ 显示标签。 */
        data class ResolvedColor(val hex: String, val icon: String, val label: String)

        private val HEX6 = Regex("[0-9a-fA-F]{6}")

        /**
         * 解析颜色输入：命名色 / #RRGGBB / RRGGBB。null 输入用默认白；非法返回 null。
         * 任意 hex 时弹窗用精确色，指南针三角取最接近的预设色。
         */
        fun resolveColor(input: String?): ResolvedColor? {
            if (input == null) {
                val c = COLORS.getValue(DEFAULT_COLOR)
                return ResolvedColor(c.hex, c.icon, DEFAULT_COLOR)
            }
            COLORS[input.lowercase()]?.let { return ResolvedColor(it.hex, it.icon, input.lowercase()) }
            val hex = input.removePrefix("#")
            if (HEX6.matches(hex)) {
                val up = hex.uppercase()
                return ResolvedColor(up, nearestPresetIcon(up), "#$up")
            }
            return null
        }

        /** 在预设调色板里取与给定 hex 最接近的三角 icon（RGB 欧氏距离）。 */
        private fun nearestPresetIcon(hex: String): String {
            val r = hex.substring(0, 2).toInt(16)
            val g = hex.substring(2, 4).toInt(16)
            val b = hex.substring(4, 6).toInt(16)
            return COLORS.values.minByOrNull {
                val pr = it.hex.substring(0, 2).toInt(16)
                val pg = it.hex.substring(2, 4).toInt(16)
                val pb = it.hex.substring(4, 6).toInt(16)
                val dr = r - pr; val dg = g - pg; val db = b - pb
                dr * dr + dg * dg + db * db
            }!!.icon
        }
    }
}
