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
        val debug = plugin.forgeConfig.betterHud.debug
        if (!enabled) { if (debug) plugin.logger.info("[SF-BHUD] 跳过：BetterHud 插件未启用"); return }
        if (skillName.isBlank()) { if (debug) plugin.logger.info("[SF-BHUD] 跳过：技能内部名为空（getInternalName 反射失败？）"); return }
        if (cdTicks <= 0L) { if (debug) plugin.logger.info("[SF-BHUD] 跳过：cdTicks<=0，无冷却可显示（如效率100%缩到0）"); return }
        try {
            val hud = BetterHudAPI.inst()
            val hudPlayer = hud.playerManager.getHudPlayer(player.uniqueId) ?: run {
                if (debug) plugin.logger.info("[SF-BHUD] getHudPlayer=null：玩家 ${player.name} 未被 BetterHud 追踪")
                return
            }
            val popup = hud.popupManager.getPopup(popupName) ?: run {
                if (debug) plugin.logger.info("[SF-BHUD] getPopup('$popupName')=null：popup 未加载或名字不匹配。当前已加载 popups=${hud.popupManager.allNames}")
                return
            }

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

            val updater = popup.show(BukkitEventUpdateEvent(event, key), hudPlayer) ?: run {
                if (debug) plugin.logger.info("[SF-BHUD] popup.show 返回 null：显示失败（条件不满足/分组冲突？）skill=$skillName")
                return
            }
            val task = plugin.server.scheduler.runTaskLater(plugin, Runnable {
                active.remove(key)?.let { runCatching { it.updater.remove() } }
            }, cdTicks)
            active[key] = Active(updater, task)
            if (debug) plugin.logger.info(
                "[SF-BHUD] 已推送 popup '$popupName'：skill=$skillName, cd=${"%.1f".format(seconds)}s, ticks=$cdTicks, vars={skill,cooldown,cooldown_ticks} ✓"
            )
        } catch (e: Exception) {
            // 不再静默吞异常——这正是之前看不到原因的根源
            plugin.logger.warning("[SF-BHUD] 推送 popup 异常：${e.javaClass.name}: ${e.message}")
            if (debug) e.printStackTrace()
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
