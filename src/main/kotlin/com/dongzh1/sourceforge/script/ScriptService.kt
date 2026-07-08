package com.dongzh1.sourceforge.script

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourcejs.api.SandboxOptions
import com.dongzh1.sourcejs.api.ScriptEngineService
import com.dongzh1.sourcejs.api.ScriptSandbox
import org.bukkit.Bukkit
import java.io.File

/**
 * 技能脚本引擎。GraalJS 运行时已抽到独立插件 SourceJS，SF 不再自带 graal——
 * 改为向 SourceJS 申请隔离沙箱（safe 策略：仅 @JsExport 标注的 sf 原语对脚本可见）。
 *
 * 每个技能 = 数据目录 skills/<id>.js，可定义钩子函数：
 *   onToggle(playerId)            右键开关
 *   onTick(playerId)             每秒（仅对开启的玩家）
 *   onKillNearby(playerId, dist) 附近有生物死亡，返回掉落倍率（1=不变）
 *   onDamaged(playerId, damage, cause) 返回 true 免疫该次伤害
 *   onAttack(playerId)           左键挥砍攻击技能
 * 脚本里用全局 `sf` 调 SF 原语（见 SfScriptApi）。
 *
 * 每脚本独立沙箱（全局隔离，互不串名）。所有调用都在主线程，沙箱单线程使用，安全。
 */
class ScriptService(private val plugin: SourceForge) {

    private class Skill(val id: String, val sandbox: ScriptSandbox)

    val api = SfScriptApi(plugin)
    private val skills = HashMap<String, Skill>()

    fun loadedSkillIds(): Set<String> = skills.keys

    fun load() {
        close()
        val dir = File(plugin.dataFolder, "skills")
        if (!dir.isDirectory) return
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("js", true) } ?: return
        val engine = Bukkit.getServicesManager().getRegistration(ScriptEngineService::class.java)?.provider
        if (engine == null) {
            plugin.logger.warning("[skill-script] SourceJS 的 ScriptEngineService 未就绪，技能脚本未加载（请确认已安装 SourceJS）")
            return
        }
        var ok = 0
        for (file in files.sortedBy { it.name }) {
            val id = file.nameWithoutExtension
            try {
                val sandbox = engine.newSandbox(SandboxOptions.safe())
                sandbox.bind("sf", api)
                sandbox.eval(file.readText())
                skills[id] = Skill(id, sandbox)
                ok++
            } catch (e: Exception) {
                plugin.logger.warning("[skill-script] 加载 ${file.name} 失败: ${e.message}")
            }
        }
        plugin.logger.info("[skill-script] 已加载 $ok 个技能脚本: ${skills.keys.joinToString(", ")}")
    }

    fun fireToggle(skillId: String, playerId: String) {
        skills[skillId]?.sandbox?.callVoid("onToggle", playerId)
    }

    fun fireTick(skillId: String, playerId: String) {
        skills[skillId]?.sandbox?.callVoid("onTick", playerId)
    }

    /** 返回掉落倍率（无 onKillNearby 或出错时按 1）。 */
    fun fireKillNearby(skillId: String, playerId: String, dist: Double): Int {
        val s = skills[skillId]?.sandbox ?: return 1
        return s.callInt("onKillNearby", 1, playerId, dist).coerceAtLeast(1)
    }

    /** 玩家受击：脚本返回 true 表示要免疫（取消该次伤害事件）。无 onDamaged 或出错时按 false。 */
    fun fireDamaged(skillId: String, playerId: String, damage: Double, cause: String): Boolean {
        val s = skills[skillId]?.sandbox ?: return false
        return s.callBool("onDamaged", false, playerId, damage, cause)
    }

    /** 玩家挥砍(左键) → 攻击技能(如三段斩)。无 onAttack 或出错时静默。 */
    fun fireAttack(skillId: String, playerId: String) {
        skills[skillId]?.sandbox?.callVoid("onAttack", playerId)
    }

    /**
     * 触发栏激活 → 统一 onActivate 钩子（技能槽技能：左键/右键/Shift组合/空中，具体由所在触发栏决定）。
     * 无 onActivate 或出错时静默。
     */
    fun fireActivate(skillId: String, playerId: String) {
        skills[skillId]?.sandbox?.callVoid("onActivate", playerId)
    }

    fun close() {
        skills.values.forEach { runCatching { it.sandbox.close() } }
        skills.clear()
    }
}
