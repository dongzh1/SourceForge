package com.dongzh1.sourceforge.script

import com.dongzh1.sourceforge.SourceForge
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Value
import java.io.File

/**
 * 技能脚本引擎（GraalJS，解释器模式）。
 * 每个技能 = 数据目录 skills/<id>.js，可定义钩子函数：
 *   onToggle(playerId)            右键开关
 *   onTick(playerId)             每秒（仅对开启的玩家）
 *   onKillNearby(playerId, dist) 附近有生物死亡，返回掉落倍率（1=不变）
 * 脚本里用全局 `sf` 调 SF 原语（见 SfScriptApi）。
 *
 * 共享一个 Engine（省内存、并关掉解释器告警），每个脚本独立 Context（全局隔离，互不串名）。
 * 所有调用都在主线程（Bukkit 事件/tick），Context 单线程使用，安全。
 */
class ScriptService(private val plugin: SourceForge) {

    private class Skill(
        val id: String,
        val context: Context,
        val onToggle: Value?,
        val onTick: Value?,
        val onKillNearby: Value?
    )

    val api = SfScriptApi(plugin)
    private val engine: Engine = Engine.newBuilder("js")
        .option("engine.WarnInterpreterOnly", "false") // 解释器告警关掉
        .build()
    private val skills = HashMap<String, Skill>()

    fun loadedSkillIds(): Set<String> = skills.keys

    fun load() {
        close()
        val dir = File(plugin.dataFolder, "skills")
        if (!dir.isDirectory) return
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("js", true) } ?: return
        var ok = 0
        for (file in files.sortedBy { it.name }) {
            val id = file.nameWithoutExtension
            try {
                val ctx = Context.newBuilder("js")
                    .engine(engine)
                    .allowHostAccess(HostAccess.EXPLICIT)
                    .build()
                ctx.getBindings("js").putMember("sf", api)
                ctx.eval("js", file.readText())
                val b = ctx.getBindings("js")
                fun fn(name: String): Value? = b.getMember(name)?.takeIf { it.canExecute() }
                skills[id] = Skill(id, ctx, fn("onToggle"), fn("onTick"), fn("onKillNearby"))
                ok++
            } catch (e: Exception) {
                plugin.logger.warning("[skill-script] 加载 ${file.name} 失败: ${e.message}")
            }
        }
        plugin.logger.info("[skill-script] 已加载 $ok 个技能脚本: ${skills.keys.joinToString(", ")}")
    }

    fun fireToggle(skillId: String, playerId: String) {
        val fn = skills[skillId]?.onToggle ?: return
        runCatching { fn.executeVoid(playerId) }.onFailure { warn(skillId, "onToggle", it) }
    }

    fun fireTick(skillId: String, playerId: String) {
        val fn = skills[skillId]?.onTick ?: return
        runCatching { fn.executeVoid(playerId) }.onFailure { warn(skillId, "onTick", it) }
    }

    /** 返回掉落倍率（无 onKillNearby 或出错时按 1）。 */
    fun fireKillNearby(skillId: String, playerId: String, dist: Double): Int {
        val fn = skills[skillId]?.onKillNearby ?: return 1
        return runCatching { fn.execute(playerId, dist).asInt().coerceAtLeast(1) }
            .onFailure { warn(skillId, "onKillNearby", it) }
            .getOrDefault(1)
    }

    private fun warn(skillId: String, hook: String, t: Throwable) =
        plugin.logger.warning("[skill-script] $skillId.$hook 执行出错: ${t.message}")

    fun close() {
        skills.values.forEach { runCatching { it.context.close(true) } }
        skills.clear()
    }
}
