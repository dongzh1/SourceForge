package com.dongzh1.sourceforge.tracker

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.api.SourceForgeTaskTrackerObjective
import com.dongzh1.sourceforge.api.SourceForgeTaskTrackerTask
import com.dongzh1.sourceforge.hud.BetterHudHook
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TaskTrackerManager(private val plugin: SourceForge) {

    private data class Snapshot(val tasks: List<SourceForgeTaskTrackerTask>)

    private val snapshots = ConcurrentHashMap<UUID, Snapshot>()
    private val renderRetries = ConcurrentHashMap<UUID, BukkitTask>()
    private val hiddenTaskIds = ConcurrentHashMap<UUID, MutableSet<String>>()
    private val visibilityFile = File(plugin.dataFolder, "task-tracker.yml")

    init {
        loadVisibility()
    }

    fun update(player: Player, tasks: List<SourceForgeTaskTrackerTask>): Boolean {
        if (tasks.isEmpty()) {
            hide(player)
            return false
        }
        val snapshot = Snapshot(
            tasks.map { task ->
                SourceForgeTaskTrackerTask(
                    task.id,
                    task.name,
                    task.objectives.map { objective ->
                        SourceForgeTaskTrackerObjective(objective.text, objective.completed)
                    }
                )
            }
        )
        snapshots[player.uniqueId] = snapshot
        return render(player, snapshot)
    }

    fun isTaskTracked(player: Player, taskId: String): Boolean =
        normalizeTaskId(taskId) !in (hiddenTaskIds[player.uniqueId] ?: emptySet())

    fun setTaskTracked(player: Player, taskId: String, tracked: Boolean): Boolean {
        val normalizedTaskId = normalizeTaskId(taskId)
        if (normalizedTaskId.isEmpty()) return false
        val playerId = player.uniqueId
        val changed = if (tracked) {
            val hidden = hiddenTaskIds[playerId]
            if (hidden == null) {
                false
            } else {
                val removed = hidden.remove(normalizedTaskId)
                if (hidden.isEmpty()) hiddenTaskIds.remove(playerId, hidden)
                removed
            }
        } else {
            hiddenTaskIds.computeIfAbsent(playerId) { ConcurrentHashMap.newKeySet() }.add(normalizedTaskId)
        }
        if (changed) saveVisibility()
        snapshots[playerId]?.let { render(player, it) }
        return tracked
    }

    fun hide(player: Player): Boolean {
        cancelRenderRetry(player.uniqueId)
        val removed = snapshots.remove(player.uniqueId) != null
        return BetterHudHook.hideTaskTracker(player) || removed
    }

    fun forget(playerId: UUID): Boolean {
        cancelRenderRetry(playerId)
        val removed = snapshots.remove(playerId) != null
        BetterHudHook.forgetTaskTracker(playerId)
        return removed
    }

    fun restore() {
        plugin.server.scheduler.runTask(plugin, Runnable {
            for ((playerId, snapshot) in snapshots) {
                val player = Bukkit.getPlayer(playerId) ?: continue
                if (player.isOnline) render(player, snapshot)
            }
        })
    }

    fun clear() {
        for (playerId in renderRetries.keys) cancelRenderRetry(playerId)
        for (playerId in snapshots.keys) {
            Bukkit.getPlayer(playerId)?.let { BetterHudHook.hideTaskTracker(it) }
        }
        snapshots.clear()
    }

    private fun render(player: Player, snapshot: Snapshot): Boolean {
        if (!plugin.forgeConfig.betterHud.enabled) {
            cancelRenderRetry(player.uniqueId)
            BetterHudHook.hideTaskTracker(player)
            return false
        }
        val visibleTasks = snapshot.tasks.filter { isTaskTracked(player, it.id) }
        if (visibleTasks.isEmpty()) {
            cancelRenderRetry(player.uniqueId)
            BetterHudHook.hideTaskTracker(player)
            return false
        }
        val shown = BetterHudHook.showTaskTracker(
            plugin,
            player,
            plugin.forgeConfig.betterHud.taskTrackerPopup,
            variables(visibleTasks)
        )
        if (shown) cancelRenderRetry(player.uniqueId) else scheduleRenderRetry(player.uniqueId)
        return shown
    }

    private fun scheduleRenderRetry(playerId: UUID) {
        if (renderRetries.containsKey(playerId) || Bukkit.getPluginManager().getPlugin("BetterHud") == null) return
        var attempts = 0
        lateinit var task: BukkitTask
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val player = Bukkit.getPlayer(playerId)
            val snapshot = snapshots[playerId]
            if (player == null || !player.isOnline || snapshot == null || ++attempts > MAX_RENDER_RETRIES) {
                renderRetries.remove(playerId, task)
                task.cancel()
                return@Runnable
            }
            if (render(player, snapshot)) {
                renderRetries.remove(playerId, task)
                task.cancel()
            }
        }, RENDER_RETRY_PERIOD_TICKS, RENDER_RETRY_PERIOD_TICKS)
        renderRetries.putIfAbsent(playerId, task)?.let { task.cancel() }
    }

    private fun cancelRenderRetry(playerId: UUID) {
        renderRetries.remove(playerId)?.cancel()
    }

    private fun variables(tasks: List<SourceForgeTaskTrackerTask>): Map<String, String> {
        val lines = ArrayList<String>()
        lines += "<#FFD866>◆ 任务追踪"
        for (objective in tasks.first().objectives) {
            val objectiveText = plainTaskText(objective.text)
            lines += if (objective.completed) {
                "<#55FF55>  ☑ $objectiveText"
            } else {
                "<#C9C9C9>  - $objectiveText"
            }
        }
        if (tasks.size > 1) lines += "<#8E98A3>  …另有 ${tasks.size - 1} 个任务已折叠"
        if (lines.size > MAX_LINES) {
            val hidden = lines.size - MAX_LINES
            lines.subList(MAX_LINES - 1, lines.size).clear()
            lines += "<#8E98A3>…还有 $hidden 项"
        }
        return buildMap {
            for (index in 1..MAX_LINES) {
                put("task_tracker_line$index", lines.getOrElse(index - 1) { "" })
            }
        }
    }

    private fun loadVisibility() {
        if (!visibilityFile.isFile) return
        val config = YamlConfiguration.loadConfiguration(visibilityFile)
        val hiddenSection = config.getConfigurationSection("hidden-tasks") ?: return
        for (playerIdText in hiddenSection.getKeys(false)) {
            val playerId = runCatching { UUID.fromString(playerIdText) }.getOrNull() ?: continue
            val taskIds = hiddenSection.getStringList(playerIdText)
                .map(::normalizeTaskId)
                .filter(String::isNotEmpty)
            if (taskIds.isNotEmpty()) {
                hiddenTaskIds[playerId] = ConcurrentHashMap.newKeySet<String>().also { it.addAll(taskIds) }
            }
        }
    }

    private fun saveVisibility() {
        val config = YamlConfiguration()
        for ((playerId, taskIds) in hiddenTaskIds.entries.sortedBy { it.key.toString() }) {
            if (taskIds.isNotEmpty()) config.set("hidden-tasks.$playerId", taskIds.sorted())
        }
        runCatching { config.save(visibilityFile) }.onFailure {
            plugin.logger.warning("[SourceForge] 保存任务追踪开关失败: ${it.message}")
        }
    }

    private fun normalizeTaskId(taskId: String): String = taskId.trim().lowercase()

    private fun plainTaskText(text: String): String = text.replace(Regex("<[^>]+>"), "")

    companion object {
        const val MAX_LINES = 16
        private const val RENDER_RETRY_PERIOD_TICKS = 20L
        private const val MAX_RENDER_RETRIES = 90
    }
}
