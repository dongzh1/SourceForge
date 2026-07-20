package com.dongzh1.sourceforge.nav

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * 追踪路标——发包展示 Text Display，只对"正在追踪的那名玩家"本人可见，不广播、不生成真实实体。
 * 一个追踪目标对应一个路标（服务器 /sf track 本就支持同时追踪多个目标，2026-07-08 之前的版本
 * 只渲染最近那一个是缺陷，现在按 [NavigationManager.sameWorldTargets] 的全部同世界目标逐个渲染，
 * 内容固定为“图标 / 目标名称 / 距离”三行，颜色取该目标的 [NavigationManager.NavTarget.hex]，与
 * BetterHud 指南针同色）。
 *
 * 核心公式：`渲染位置 = 玩家视角位置 + 方向 × min(真实距离, maxHoverDistance)`。
 * 目标很远时 min() 取到 maxHoverDistance，路标贴着玩家面前固定距离悬浮，只指方向——真实坐标很远时
 * 直接在那儿摆实体，客户端对应区块根本没加载/渲染距离外，等于白发包。玩家靠近后 min() 自然取到
 * 真实距离，路标随之"追上"到真实目的地——不是两套逻辑切换，同一条公式自然过渡、无缝衔接（用户
 * 2026-07-08 明确要求的行为）。多个路标各自独立套用这条公式，互不影响。
 *
 * 带宽控制（用户 2026-07-08 明确要求"不要过多发包 频率不要太高"，多目标后风险更大，控制更重要）：
 * - 只发给追踪者本人（非广播），包量只随"当前有多少人在用这个功能"增长，不随"附近有多少人"增长。
 * - 更新周期可配置（waypoint.yml `update-interval-ticks`，默认6tick≈3.3Hz），远低于逐tick；移动过程
 *   靠 Text Display 独有的 teleport_duration 插值动画平滑，不靠高频发包（见 WaypointPacketBridge）。
 * - 同时渲染的路标数上限可配置（`max-markers`，默认5，与 BetterHud 弹窗行数上限一致）——追踪目标
 *   一多，只渲染最近的这么多个，防止刷屏和发包量线性失控。
 * - 每个路标各自判定"值有没有变"：位置未变化(<0.05格)不发送 Teleport，取整后的显示距离未变化不发送
 *   Metadata。静止对着已经锁定在目的地的路标时，稳定状态下不再发任何包。
 *
 * 所有 PacketEvents 类引用隔离在 [WaypointPacketBridge]——服务器没装 PE 时 [tryStart] 整体跳过。
 *
 * 位移预判（用户 2026-07-08 反馈"鞘翅飞行时路标会贴到脸上"）：偏移锚点不用玩家"此刻"的位置，
 * 而是用 [Player.getVelocity] 预判"这次插值滑动结束时"玩家大概所在的位置——否则持续高速位移
 * (鞘翅冲刺)时锚点永远基于几个 tick 前的旧位置，追不上前进速度，视觉上路标被越甩越近。详见
 * [updateMarker] 内注释。
 */
class PacketWaypointRenderer(private val plugin: SourceForge, private var config: WaypointConfig) : Listener {

    private class MarkerState(val entityId: Int, val uuid: UUID) {
        var spawned = false
        var lastX = 0.0; var lastY = 0.0; var lastZ = 0.0
        var lastDistanceText: String? = null
    }

    /** 玩家 -> (追踪目标名 -> 该目标的路标状态)。一个玩家可以同时有多个路标。 */
    private val states = ConcurrentHashMap<UUID, MutableMap<String, MarkerState>>()

    /** 伪实体 ID 从一个远高于正常实体计数的基址递增，避免与真实实体 ID 撞车。 */
    private val idCounter = AtomicInteger(1_900_000_000)

    private var task: BukkitTask? = null

    /** 首次启用尝试启动；PE 不在/不可用时安全跳过，不影响插件其余功能。 */
    fun tryStart() {
        if (task != null) return
        if (!config.enabled) return
        val pePlugin = plugin.server.pluginManager.getPlugin("packetevents")
        if (pePlugin == null || !pePlugin.isEnabled) {
            plugin.logger.info("[waypoint] 未检测到 PacketEvents 插件，追踪路标功能不启用（不影响其它功能）")
            return
        }
        val available = runCatching { WaypointPacketBridge.isAvailable() }
            .onFailure { plugin.logger.warning("[waypoint] PacketEvents 桥接初始化失败: ${it.message}") }
            .getOrDefault(false)
        if (!available) return
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, config.updateIntervalTicks, config.updateIntervalTicks)
        plugin.logger.info("[waypoint] 追踪路标已启用（每 ${config.updateIntervalTicks} tick 刷新一次，最多 ${config.maxMarkers} 个/人）")
    }

    /** /sf reload：配置可能变了（含开关/周期/上限），整体停掉重启。 */
    fun reload(newConfig: WaypointConfig) {
        config = newConfig
        stopAll()
        tryStart()
    }

    /** 插件禁用时调用：清掉所有已发出去的伪实体，避免客户端留下孤儿路标。 */
    fun stopAll() {
        task?.cancel()
        task = null
        for ((uuid, byName) in states) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            for (state in byName.values) {
                runCatching { WaypointPacketBridge.destroy(player, state.entityId) }
            }
        }
        states.clear()
    }

    private fun tick() {
        for (player in Bukkit.getOnlinePlayers()) {
            val live = plugin.navigationManager.sameWorldTargets(player).take(config.maxMarkers)
            if (live.isEmpty()) {
                removeAllMarkers(player)
                continue
            }
            val byName = states.getOrPut(player.uniqueId) { ConcurrentHashMap() }
            // 清掉不再追踪(或被上限挤出)的目标对应的路标
            val liveNames = live.mapTo(HashSet()) { it.name }
            val iterator = byName.entries.iterator()
            while (iterator.hasNext()) {
                val (name, state) = iterator.next()
                if (name !in liveNames) {
                    runCatching { WaypointPacketBridge.destroy(player, state.entityId) }
                    iterator.remove()
                }
            }
            for (target in live) {
                runCatching { updateMarker(player, target, byName) }
                    .onFailure { plugin.logger.warning("[waypoint] 更新路标失败(${player.name}, ${target.name}): ${it.message}") }
            }
        }
    }

    private fun removeAllMarkers(player: Player) {
        val byName = states.remove(player.uniqueId) ?: return
        for (state in byName.values) {
            runCatching { WaypointPacketBridge.destroy(player, state.entityId) }
        }
    }

    private fun updateMarker(player: Player, target: NavigationManager.NavTarget, byName: MutableMap<String, MarkerState>) {
        val eye = player.eyeLocation
        val realDx = target.x - eye.x
        val realDy = target.y - eye.y
        val realDz = target.z - eye.z
        val realDist = sqrt(realDx * realDx + realDy * realDy + realDz * realDz)
        if (realDist < 0.01) {
            byName.remove(target.name)?.let { runCatching { WaypointPacketBridge.destroy(player, it.entityId) } }
            return
        }
        val distanceText = config.distanceFormat.replace("%distance%", realDist.toInt().toString())

        // 位移预判锚点：不用玩家"此刻"的位置算偏移方向，而是预判"这次插值滑动大约结束时"(update-
        // interval-ticks 之后)玩家大概所在的位置。否则鞘翅之类持续高速位移时，锚点永远基于几个
        // tick 前的旧位置，追不上前进速度——路标被越甩越近，直到贴脸（用户2026-07-08反馈）。
        // 预判距离封顶 maxHoverDistance，避免烟花冲刺等极端瞬时高速把锚点甩出老远显得突兀。
        val lead = config.updateIntervalTicks / 20.0
        val vel = player.velocity
        val velLen = sqrt(vel.x * vel.x + vel.y * vel.y + vel.z * vel.z)
        var anchorX = eye.x; var anchorY = eye.y; var anchorZ = eye.z
        if (velLen > 0.001) {
            val leadDist = (velLen * lead).coerceAtMost(config.maxHoverDistance)
            val s = leadDist / velLen
            anchorX += vel.x * s; anchorY += vel.y * s; anchorZ += vel.z * s
        }

        var adx = target.x - anchorX
        var ady = target.y - anchorY
        var adz = target.z - anchorZ
        var adist = sqrt(adx * adx + ady * ady + adz * adz)
        if (adist < 0.01) {
            // 预判锚点意外撞上/穿过了目标坐标(贴着目标高速掠过一类的极端情况)：退回真实位置兜底
            anchorX = eye.x; anchorY = eye.y; anchorZ = eye.z
            adx = realDx; ady = realDy; adz = realDz
            adist = realDist
        }

        val offset = adist.coerceAtMost(config.maxHoverDistance)
        val inv = offset / adist
        val x = anchorX + adx * inv
        val y = anchorY + ady * inv
        val z = anchorZ + adz * inv

        val state = byName.getOrPut(target.name) { MarkerState(idCounter.incrementAndGet(), UUID.randomUUID()) }

        if (!state.spawned) {
            WaypointPacketBridge.spawn(player, state.entityId, state.uuid, x, y, z, config, target.hex, target.name, distanceText)
            state.spawned = true
            state.lastX = x; state.lastY = y; state.lastZ = z
            state.lastDistanceText = distanceText
            return
        }

        val moved = sq(x - state.lastX) + sq(y - state.lastY) + sq(z - state.lastZ) > POSITION_EPSILON_SQ
        if (moved) {
            WaypointPacketBridge.teleport(player, state.entityId, x, y, z)
            state.lastX = x; state.lastY = y; state.lastZ = z
        }
        if (distanceText != state.lastDistanceText) {
            WaypointPacketBridge.updateText(player, state.entityId, config, target.hex, target.name, distanceText)
            state.lastDistanceText = distanceText
        }
    }

    private fun sq(v: Double) = v * v

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        // 无需发 destroy 包（客户端已断连，包发不出去也无所谓）；只清内存状态防泄漏。
        states.remove(event.player.uniqueId)
    }

    companion object {
        /** 位置变化小于这个阈值(格²)不重发 Teleport 包（0.05² ≈ 肉眼不可辨的抖动）。 */
        private const val POSITION_EPSILON_SQ = 0.0025
    }
}
