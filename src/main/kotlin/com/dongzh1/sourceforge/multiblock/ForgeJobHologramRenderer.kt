package com.dongzh1.sourceforge.multiblock

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.util.Text
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 源质锻炉作业穿墙悬浮字：发包 Text Display 固定悬浮在核心正上方，锻造中显示剩余秒数，
 * 完成后显示"可收取"——玩家不用凑近开GUI就能远远看到进度，且能穿墙(见 [ForgeHologramPacketBridge]
 * 的 see_through 协议字段)。只对 render-radius 内的玩家发包，不广播真实实体。
 *
 * 悬浮字位置固定不动，比 [com.dongzh1.sourceforge.nav.PacketWaypointRenderer] 简单——不需要追着
 * 玩家挪动位置，只需要按需生成/改字/销毁。
 */
class ForgeJobHologramRenderer(
    private val plugin: SourceForge,
    private val manager: ForgeStructureManager
) : Listener {

    private class MarkerState(val entityId: Int, val uuid: UUID) {
        var spawned = false
        var lastText: String? = null
    }

    /** 玩家 -> (作业key -> 该核心的悬浮字状态)。 */
    private val states = ConcurrentHashMap<UUID, MutableMap<String, MarkerState>>()

    /** 伪实体 ID 从一个远高于正常实体计数、且与 WaypointPacketBridge 基址不同的区段递增，避免撞车。 */
    private val idCounter = AtomicInteger(1_920_000_000)

    private var task: BukkitTask? = null

    /** 首次启用尝试启动；PE 不在/不可用时安全跳过，不影响插件其余功能。 */
    fun tryStart() {
        if (task != null) return
        val cfg = manager.config.hologram
        if (!cfg.enabled) return
        val pePlugin = plugin.server.pluginManager.getPlugin("packetevents")
        if (pePlugin == null || !pePlugin.isEnabled) {
            plugin.logger.info("[锻炉悬浮字] 未检测到 PacketEvents 插件，功能不启用（不影响其它功能）")
            return
        }
        val available = runCatching { ForgeHologramPacketBridge.isAvailable() }
            .onFailure { plugin.logger.warning("[锻炉悬浮字] PacketEvents 桥接初始化失败: ${it.message}") }
            .getOrDefault(false)
        if (!available) return
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, cfg.updateIntervalTicks, cfg.updateIntervalTicks)
        plugin.logger.info("[锻炉悬浮字] 已启用（每 ${cfg.updateIntervalTicks} tick 刷新一次，范围 ${cfg.renderRadius} 格）")
    }

    /** /sf reload：结构配置可能变了（含开关/周期/范围），整体停掉重启。 */
    fun reload() {
        stopAll()
        tryStart()
    }

    /** 插件禁用时调用：清掉所有已发出去的伪实体，避免客户端留下孤儿悬浮字。 */
    fun stopAll() {
        task?.cancel()
        task = null
        for ((uuid, byKey) in states) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            for (state in byKey.values) {
                runCatching { ForgeHologramPacketBridge.destroy(player, state.entityId) }
            }
        }
        states.clear()
    }

    private fun tick() {
        if (!manager.enabled) return
        val cfg = manager.config.hologram
        val jobs = manager.activeJobs()
        if (jobs.isEmpty()) {
            for (player in Bukkit.getOnlinePlayers()) removeAllMarkers(player)
            return
        }
        val radiusSq = cfg.renderRadius * cfg.renderRadius
        for (player in Bukkit.getOnlinePlayers()) {
            val loc = player.location
            val worldName = loc.world?.name
            val nearby = jobs.filter { job ->
                job.coreWorld == worldName &&
                    sq(job.coreX + 0.5 - loc.x) + sq(job.coreY + 0.5 - loc.y) + sq(job.coreZ + 0.5 - loc.z) <= radiusSq
            }
            val byKey = states.getOrPut(player.uniqueId) { ConcurrentHashMap() }
            if (nearby.isEmpty()) {
                if (byKey.isNotEmpty()) removeAllMarkers(player)
                continue
            }
            val liveKeys = nearby.mapTo(HashSet()) { jobKey(it) }
            val iterator = byKey.entries.iterator()
            while (iterator.hasNext()) {
                val (key, state) = iterator.next()
                if (key !in liveKeys) {
                    runCatching { ForgeHologramPacketBridge.destroy(player, state.entityId) }
                    iterator.remove()
                }
            }
            for (job in nearby) {
                runCatching { updateMarker(player, job, cfg, byKey) }
                    .onFailure { plugin.logger.warning("[锻炉悬浮字] 更新失败(${player.name}): ${it.message}") }
            }
        }
    }

    private fun removeAllMarkers(player: Player) {
        val byKey = states.remove(player.uniqueId) ?: return
        for (state in byKey.values) {
            runCatching { ForgeHologramPacketBridge.destroy(player, state.entityId) }
        }
    }

    private fun updateMarker(player: Player, job: ForgeJob, cfg: ForgeHologramConfig, byKey: MutableMap<String, MarkerState>) {
        val key = jobKey(job)
        val state = byKey.getOrPut(key) { MarkerState(idCounter.incrementAndGet(), UUID.randomUUID()) }
        val text = textFor(job, cfg)
        if (!state.spawned) {
            val x = job.coreX + 0.5
            val y = job.coreY + cfg.heightOffset
            val z = job.coreZ + 0.5
            ForgeHologramPacketBridge.spawn(player, state.entityId, state.uuid, x, y, z, cfg, Text.comp(text))
            state.spawned = true
            state.lastText = text
            return
        }
        if (text != state.lastText) {
            ForgeHologramPacketBridge.updateText(player, state.entityId, Text.comp(text))
            state.lastText = text
        }
    }

    private fun textFor(job: ForgeJob, cfg: ForgeHologramConfig): String {
        if (job.isDone()) return cfg.doneText
        val seconds = Math.ceil(job.remainingTicks() / 20.0).toInt()
        return cfg.forgingFormat.replace("%seconds%", seconds.toString())
    }

    private fun jobKey(job: ForgeJob): String = "${job.coreWorld}:${ForgeJob.pack(job.coreX, job.coreY, job.coreZ)}"

    private fun sq(v: Double) = v * v

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        // 无需发 destroy 包（客户端已断连）；只清内存状态防泄漏。
        states.remove(event.player.uniqueId)
    }
}
