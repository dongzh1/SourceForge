package com.dongzh1.sourceforge.relic

import me.th2403y.sourcetasks.api.SourceTasksAPI
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * SourceTasks 软依赖桥接：SF 内目前所有"调用外部插件 API"的软依赖写法都遵循同一惯用法——
 * 见 [com.dongzh1.sourceforge.item.CraftEngineHook]（CraftEngine 同样是 compileOnly 编译期依赖，
 * 直接类型调用，非反射）、[com.dongzh1.sourceforge.hud.BetterHudHook]、[com.dongzh1.sourceforge.mod.MythicItemHook]
 * 等：统一用 `Bukkit.getPluginManager().isPluginEnabled("X")` 判断可用性，调用前置守卫 + runCatching
 * 包裹实际调用。runCatching 捕获 Throwable，天然兜住版本不兼容时的 LinkageError/NoSuchMethodError
 * （对照 build.gradle.kts 里 CraftEngine 依赖处的注释：接口不兼容时旧版本 API 会在运行时抛
 * NoSuchMethodError，被 runCatching 吞掉退化为默认值），无需像 SourceWild 侧另建 broken 状态位缓存。
 *
 * SourceForge 是这几个软依赖里唯一被别的插件依赖的一方；SourceTasksAPI 是 SF 第一次反过来
 * 主动调用别的插件（此前都是被 SourceWild/PixelRPG 等 compileOnly 依赖）。SourceTasks 未安装/
 * 未启用/API 不兼容时，本桥接的两个方法都静默降级，不影响遗物系统之外的其它功能。
 */
object TasksBridge {
    val enabled: Boolean
        get() = Bukkit.getPluginManager().isPluginEnabled("SourceTasks")

    /** 让玩家接取任务。SourceTasksAPI 内部对已接/已完成/任务id不存在时静默无副作用，可放心重复调用。 */
    fun startQuest(player: Player, questId: String) {
        if (!enabled) return
        runCatching { SourceTasksAPI.startQuest(player, questId) }
    }

    /** 该任务是否已完成。SourceTasks 未启用/调用异常返回 false。 */
    fun isCompleted(player: Player, questId: String): Boolean {
        if (!enabled) return false
        return runCatching { SourceTasksAPI.isCompleted(player, questId) }.getOrDefault(false)
    }
}
