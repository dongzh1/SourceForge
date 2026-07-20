package com.dongzh1.sourceforge.api

import com.dongzh1.sourceforge.tracker.TaskTrackerManager
import org.bukkit.entity.Player
import java.util.UUID

data class SourceForgeTaskTrackerObjective(
    val text: String,
    val completed: Boolean
)

data class SourceForgeTaskTrackerTask(
    val id: String,
    val name: String,
    val objectives: List<SourceForgeTaskTrackerObjective>
)

/**
 * SourceForge 统一任务追踪 API。
 * 调用方只提供任务列表与完成状态；BetterHud 的布局、显示状态和重载恢复均由 SourceForge 管理。
 */
object SourceForgeTaskTrackerAPI {

    @Volatile
    private var tracker: TaskTrackerManager? = null

    @JvmStatic
    fun bind(manager: TaskTrackerManager) {
        tracker = manager
    }

    @JvmStatic
    fun unbind(manager: TaskTrackerManager) {
        if (tracker === manager) tracker = null
    }

    @JvmStatic
    fun update(player: Player, tasks: List<SourceForgeTaskTrackerTask>): Boolean =
        tracker?.update(player, tasks) ?: false

    @JvmStatic
    fun isAvailable(): Boolean = tracker != null

    @JvmStatic
    fun isTaskTracked(player: Player, taskId: String): Boolean =
        tracker?.isTaskTracked(player, taskId) ?: true

    @JvmStatic
    fun setTaskTracked(player: Player, taskId: String, tracked: Boolean): Boolean =
        tracker?.setTaskTracked(player, taskId, tracked) ?: false

    @JvmStatic
    fun hide(player: Player): Boolean = tracker?.hide(player) ?: false

    @JvmStatic
    fun forget(playerId: UUID): Boolean = tracker?.forget(playerId) ?: false
}
