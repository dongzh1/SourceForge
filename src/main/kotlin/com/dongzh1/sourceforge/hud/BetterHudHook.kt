package com.dongzh1.sourceforge.hud

import com.dongzh1.sourceforge.SourceForge
import kr.toxicity.hud.api.BetterHudAPI
import kr.toxicity.hud.api.adapter.LocationWrapper
import kr.toxicity.hud.api.adapter.WorldWrapper
import kr.toxicity.hud.api.bukkit.event.CustomPopupEvent
import kr.toxicity.hud.api.bukkit.update.BukkitEventUpdateEvent
import kr.toxicity.hud.api.bukkit.event.PluginReloadedEvent
import kr.toxicity.hud.api.player.PointedLocation
import kr.toxicity.hud.api.player.PointedLocationProvider
import kr.toxicity.hud.api.player.PointedLocationSource
import kr.toxicity.hud.api.popup.PopupUpdater
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BetterHud 软依赖集成。仅在 BetterHud 存在时才会触碰 kr.toxicity.hud.* 类型，
 * 与 [com.dongzh1.sourceforge.item.CraftEngineHook] 同款守卫写法，缺失时安全跳过。
 *
 * 每次 MM 技能进入冷却，按「技能名」作为堆叠 key 弹出/更新一条 popup，并在冷却
 * 结束时主动移除。同一技能重复施法会先移除旧条目再弹新的，保证每技能仅一条。
 */
object BetterHudHook : Listener {

    val enabled: Boolean
        get() = Bukkit.getPluginManager().isPluginEnabled("BetterHud")

    @Volatile
    private var reloadListenerRegistered = false

    /**
     * 注册 BetterHud 重载监听（仅一次）。`bh reload` 的 preReload 会清空每个玩家的
     * popupKeyMap/popupGroupIteratorMap，我们手里的 PopupUpdater 随之失效；重载完成后
     * 触发 [PluginReloadedEvent]，此时丢弃失效的导航条目引用，下一 tick 的导航推送会
     * 自动重建弹窗。
     */
    fun registerReloadListener(plugin: SourceForge) {
        if (!enabled || reloadListenerRegistered) return
        try {
            Bukkit.getPluginManager().registerEvents(this, plugin)
            reloadListenerRegistered = true
            if (plugin.forgeConfig.betterHud.debug) plugin.logger.info("[SF-NAV] BetterHud 重载监听已注册 ✓")
        } catch (e: Exception) {
            plugin.logger.warning("[SF-NAV] 注册 BetterHud 重载监听失败：${e.javaClass.name}: ${e.message}")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onBetterHudReloaded(event: PluginReloadedEvent) {
        // 重载已清空 BetterHud 内部弹窗状态；丢弃失效引用（不调用其 remove，已失效且本事件可能异步）。
        // 导航弹窗由 NavigationManager 每 2 tick 重新推送，会据此重建。
        navActive.clear()
    }

    private data class Active(val updater: PopupUpdater, val removalTask: BukkitTask)

    /** key = "uuid:skill" -> 当前活跃的 popup 条目及其到期移除任务 */
    private val active = ConcurrentHashMap<String, Active>()

    private class NavEntry(val updater: PopupUpdater, val event: CustomPopupEvent)

    /** uuid -> 玩家当前的导航 popup 条目（每玩家一条，持续更新） */
    private val navActive = ConcurrentHashMap<java.util.UUID, NavEntry>()

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
            event.variables["cooldown"] = "%.1f".format(seconds)        // 剩余秒数（下面每2tick实时刷新）
            event.variables["cooldown_total"] = "%.1f".format(seconds)  // 总冷却秒数（固定）
            event.variables["cooldown_ticks"] = cdTicks.toString()      // 总冷却tick（固定，白条长度）
            event.variables["remaining_ticks"] = cdTicks.toString()     // 剩余tick（下面实时刷新，白条进度）
            Bukkit.getPluginManager().callEvent(event)

            val updater = popup.show(BukkitEventUpdateEvent(event, key), hudPlayer) ?: run {
                if (debug) plugin.logger.info("[SF-BHUD] popup.show 返回 null：显示失败（条件不满足/分组冲突？）skill=$skillName")
                return
            }
            // 每 2 tick 把剩余冷却写回 cooldown/remaining_ticks 变量，BetterHud 实时重渲染实现倒计时；
            // 到期（剩余<=0）移除该条目并停止任务。
            val endAt = System.currentTimeMillis() + cdTicks * 50L
            val task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
                val remainMs = endAt - System.currentTimeMillis()
                if (remainMs <= 0L) {
                    active.remove(key)?.let { it.removalTask.cancel(); runCatching { it.updater.remove() } }
                    return@Runnable
                }
                event.variables["cooldown"] = "%.1f".format(remainMs / 1000.0)
                event.variables["remaining_ticks"] = (remainMs / 50L).toString()
                runCatching { updater.update() }
            }, 2L, 2L)
            active[key] = Active(updater, task)
            if (debug) plugin.logger.info(
                "[SF-BHUD] 已推送 popup '$popupName'：skill=$skillName, cd=${"%.1f".format(seconds)}s, ticks=$cdTicks（剩余每2tick刷新）✓"
            )
        } catch (e: Exception) {
            // 不再静默吞异常——这正是之前看不到原因的根源
            plugin.logger.warning("[SF-BHUD] 推送 popup 异常：${e.javaClass.name}: ${e.message}")
            if (debug) e.printStackTrace()
        }
    }

    /**
     * 显示或更新某玩家的导航 popup。已有则更新变量并重渲染（更新失败说明 popup 已
     * 失效——duration 过期/被顶掉——则移除后重建）；没有则新建。每 2 tick 由
     * NavigationManager 调用。
     */
    fun showNavigator(plugin: SourceForge, player: Player, popupName: String, vars: Map<String, String>) {
        if (!enabled || popupName.isBlank()) return
        val debug = plugin.forgeConfig.betterHud.debug
        try {
            navActive[player.uniqueId]?.let { existing ->
                existing.event.variables.putAll(vars)
                val ok = runCatching { existing.updater.update() }.getOrDefault(false)
                if (ok) return
                navActive.remove(player.uniqueId) // 已失效，下面重建
            }
            val hud = BetterHudAPI.inst()
            val hudPlayer = hud.playerManager.getHudPlayer(player.uniqueId) ?: run {
                if (debug) plugin.logger.info("[SF-NAV] getHudPlayer=null：${player.name} 未被 BetterHud 追踪")
                return
            }
            val popup = hud.popupManager.getPopup(popupName) ?: run {
                if (debug) plugin.logger.info("[SF-NAV] getPopup('$popupName')=null：popup 未加载或名字不匹配。当前已加载=${hud.popupManager.allNames}")
                return
            }
            val event = CustomPopupEvent(player, popupName)
            event.variables.putAll(vars)
            Bukkit.getPluginManager().callEvent(event)
            val updater = popup.show(BukkitEventUpdateEvent(event, "nav:" + player.uniqueId), hudPlayer) ?: run {
                if (debug) plugin.logger.info("[SF-NAV] popup.show 返回 null：${player.name}")
                return
            }
            navActive[player.uniqueId] = NavEntry(updater, event)
            if (debug) plugin.logger.info("[SF-NAV] 导航 popup 已显示 ${player.name} -> ${vars["target"]}")
        } catch (e: Exception) {
            plugin.logger.warning("[SF-NAV] 导航 popup 异常：${e.javaClass.name}: ${e.message}")
        }
    }

    /** 停止某玩家的导航 popup。 */
    fun hideNavigator(player: Player) {
        navActive.remove(player.uniqueId)?.let { runCatching { it.updater.remove() } }
    }

    // ==================== 指南针指针（多目标方向三角） ====================
    // 用 BetterHud 原生 compass pointer，但走官方 PointedLocationProvider：注册一次后，
    // BetterHud 每次渲染主动调 provide(player) 拉取 SF 指针——天生 reload 安全，且不会
    // 像直接 pointers().add 那样被 postReload 的 HudPlayer.reload() 从存档冲掉。
    // icon 对应 compass 配置里的 custom-icon（不同颜色三角）。

    private const val POINTER_PREFIX = "sf:"

    /** 给目标名加上 SF 指针前缀（与玩家手动 point add 隔离）。 */
    fun pointerName(targetName: String): String = POINTER_PREFIX + targetName

    /** provider 回传的轻量 DTO（不暴露 kr.toxicity 类型给调用方）。 */
    class SfPointer(
        val name: String,
        val icon: String?,
        val world: String,
        val x: Double,
        val y: Double,
        val z: Double
    )

    @Volatile
    private var pointerProviderRegistered = false

    /**
     * 注册 SF 的指南针指针 provider（仅注册一次）。BetterHud 会以渲染频率对每个被追踪的
     * HudPlayer 调用 [provider]，传入其 uuid，期望返回该玩家当前要显示的全部 SF 指针。
     * 目标增删只需改 provider 的数据源即可即时反映，无需手动加/删指针。
     */
    fun registerPointerProvider(plugin: SourceForge, provider: (UUID) -> List<SfPointer>) {
        if (!enabled || pointerProviderRegistered) return
        try {
            val located = PointedLocationProvider { hudPlayer ->
                provider(hudPlayer.uuid()).map {
                    PointedLocation(
                        PointedLocationSource.GPS,
                        it.name,
                        it.icon,
                        LocationWrapper(WorldWrapper(it.world), it.x, it.y, it.z, 0f, 0f)
                    )
                }
            }
            BetterHudAPI.inst().playerManager.addLocationProvider(located)
            pointerProviderRegistered = true
            if (plugin.forgeConfig.betterHud.debug) plugin.logger.info("[SF-NAV] 指针 provider 已注册 ✓")
        } catch (e: Exception) {
            plugin.logger.warning("[SF-NAV] 注册指针 provider 失败：${e.javaClass.name}: ${e.message}")
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
