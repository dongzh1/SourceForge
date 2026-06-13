package com.dongzh1.sourceforge.hud

import com.dongzh1.sourceforge.SourceForge
import kr.toxicity.hud.api.BetterHudAPI
import kr.toxicity.hud.api.bukkit.event.CustomPopupEvent
import kr.toxicity.hud.api.bukkit.update.BukkitEventUpdateEvent
import kr.toxicity.hud.api.popup.PopupUpdater
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.ConcurrentHashMap

/**
 * BetterHud 软依赖集成。仅在 BetterHud 存在时才会触碰 kr.toxicity.hud.* 类型，
 * 与 [com.dongzh1.sourceforge.item.CraftEngineHook] 同款守卫写法，缺失时安全跳过。
 *
 * 每次 MM 技能进入冷却，按「技能名」作为堆叠 key 弹出/更新一条 popup，并在冷却
 * 结束时主动移除。同一技能重复施法会先移除旧条目再弹新的，保证每技能仅一条。
 */
object BetterHudHook {

    val enabled: Boolean
        get() = Bukkit.getPluginManager().isPluginEnabled("BetterHud")

    private data class Active(val updater: PopupUpdater, val removalTask: BukkitTask)

    /** key = "uuid:skill" -> 当前活跃的 popup 条目及其到期移除任务 */
    private val active = ConcurrentHashMap<String, Active>()

    /**
     * 在主线程调用。弹出/更新某技能的冷却 popup。
     *
     * @param popupName  BetterHud 中定义的 popup 名
     * @param skillName  技能内部名（作为堆叠 key 与 (custom_variable:skill)）
     * @param seconds    冷却秒数
     * @param cdTicks    冷却总 tick，到期后移除该条目
     */
    fun showSkillCd(
        plugin: SourceForge,
        player: Player,
        popupName: String,
        skillName: String,
        seconds: Double,
        cdTicks: Long
    ) {
        if (!enabled || skillName.isBlank() || cdTicks <= 0L) return
        runCatching {
            val hud = BetterHudAPI.inst()
            val hudPlayer = hud.playerManager.getHudPlayer(player.uniqueId) ?: return@runCatching
            val popup = hud.popupManager.getPopup(popupName) ?: return@runCatching

            val key = player.uniqueId.toString() + ":" + skillName
            // 同技能旧条目先移除，避免重复堆叠
            active.remove(key)?.let { old ->
                old.removalTask.cancel()
                runCatching { old.updater.remove() }
            }

            val event = CustomPopupEvent(player, popupName)
            event.variables["skill"] = skillName
            event.variables["cooldown"] = "%.1f".format(seconds)
            event.variables["cooldown_ticks"] = cdTicks.toString()
            Bukkit.getPluginManager().callEvent(event)

            val updater = popup.show(BukkitEventUpdateEvent(event, key), hudPlayer) ?: return@runCatching
            val task = plugin.server.scheduler.runTaskLater(plugin, Runnable {
                active.remove(key)?.let { runCatching { it.updater.remove() } }
            }, cdTicks)
            active[key] = Active(updater, task)
        }
    }

    /** 玩家退出时清理其残留条目与定时任务。 */
    fun clear(player: Player) {
        val prefix = player.uniqueId.toString() + ":"
        val it = active.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key.startsWith(prefix)) {
                entry.value.removalTask.cancel()
                runCatching { entry.value.updater.remove() }
                it.remove()
            }
        }
    }
}
