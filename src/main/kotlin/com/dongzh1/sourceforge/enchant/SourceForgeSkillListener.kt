package com.dongzh1.sourceforge.enchant

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import java.lang.reflect.Method
import java.util.Collections

class SourceForgeSkillListener(private val plugin: SourceForge) : Listener {
    private val warnedRuntimeFailures = Collections.synchronizedSet(mutableSetOf<String>())

    fun registerIfAvailable() {
        if (!Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) return
        try {
            val eventClass = Class.forName("io.lumine.mythic.bukkit.events.MythicSkillEvent")
            @Suppress("UNCHECKED_CAST")
            val typedClass = eventClass as Class<Event>
            Bukkit.getPluginManager().registerEvent(
                typedClass,
                this,
                EventPriority.MONITOR,
                EventExecutor { _, event ->
                    if (eventClass.isInstance(event) && !isCancelled(event)) {
                        onMythicSkill(event)
                    }
                },
                plugin,
                true
            )
            plugin.logger.info("SourceForge MM 技能效率监听已注册")
        } catch (e: Exception) {
            plugin.logger.warning("无法注册 MM 技能效率: ${e.message}")
        }
    }

    private fun onMythicSkill(event: Any) {
        try {
            val metadata = callNoArg(event, "getSkillMetadata") ?: return
            val skill = callNoArg(event, "getSkill") ?: return
            val caster = callNoArg(metadata, "getCaster") ?: return
            val player = casterPlayer(caster) ?: return
            val efficiency = plugin.itemService.readTotalAffix(player, "ability_efficiency").coerceAtLeast(0.0)
            if (efficiency <= 0.0) return

            val multiplier = (1.0 - efficiency).coerceAtLeast(0.0)
            plugin.server.scheduler.runTask(plugin, Runnable {
                adjustCooldown(player, caster, skill, multiplier, efficiency)
            })
        } catch (e: Exception) {
            warnOnce("skill:${e.javaClass.name}:${e.message}", "MM 技能效率处理失败: ${e.message}")
        }
    }

    private fun adjustCooldown(player: Player, caster: Any, skill: Any, multiplier: Double, efficiency: Double) {
        val before = skillCooldown(skill, caster) ?: return
        if (before <= 0.0) return

        val after = before * multiplier
        setSkillCooldown(skill, caster, after)

        if (plugin.forgeConfig.debugCombat) {
            val name = skillName(skill)
            player.sendMessage(
                "§8[SourceForge Debug] §7MM CD${name}: " +
                    "${"%.2f".format(before)}s -> ${"%.2f".format(after)}s " +
                    "(效率=${"%.0f".format(efficiency * 100)}%)"
            )
        }
    }

    private fun skillCooldown(skill: Any, caster: Any): Double? {
        return try {
            (skillCooldownMethod(skill, caster)?.invoke(skill, caster) as? Number)?.toDouble()
        } catch (e: Exception) {
            warnOnce("getSkill:${skill.javaClass.name}", "读取 MM 技能冷却失败: ${e.message}")
            null
        }
    }

    private fun setSkillCooldown(skill: Any, caster: Any, cooldown: Double) {
        try {
            val method = setSkillCooldownMethod(skill, caster) ?: return
            val value = when (method.parameterTypes[1]) {
                java.lang.Float.TYPE, java.lang.Float::class.java -> cooldown.toFloat()
                java.lang.Double.TYPE, java.lang.Double::class.java -> cooldown
                java.lang.Integer.TYPE, java.lang.Integer::class.java -> cooldown.toInt()
                java.lang.Long.TYPE, java.lang.Long::class.java -> cooldown.toLong()
                else -> cooldown
            }
            method.invoke(skill, caster, value)
        } catch (e: Exception) {
            warnOnce("setSkill:${skill.javaClass.name}", "写入 MM 技能冷却失败: ${e.message}")
        }
    }

    private fun skillCooldownMethod(skill: Any, caster: Any): Method? {
        return skill.javaClass.methods.firstOrNull {
            it.name == "getCooldown" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0].isAssignableFrom(caster.javaClass)
        }
    }

    private fun setSkillCooldownMethod(skill: Any, caster: Any): Method? {
        return skill.javaClass.methods.firstOrNull {
            it.name == "setCooldown" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0].isAssignableFrom(caster.javaClass) &&
                it.parameterTypes[1] in cooldownNumberTypes
        }
    }

    private fun casterPlayer(caster: Any): Player? {
        val entity = callNoArg(caster, "getEntity") ?: return null
        return callNoArg(entity, "getBukkitEntity") as? Player
    }

    private fun skillName(skill: Any): String {
        val name = callNoArg(skill, "getInternalName") as? String
        return if (name.isNullOrBlank()) "" else "[$name]"
    }

    private fun isCancelled(event: Any): Boolean {
        return (callNoArg(event, "isCancelled") as? Boolean) == true
    }

    private fun callNoArg(target: Any, name: String): Any? {
        return target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(target)
    }

    private fun warnOnce(key: String, message: String) {
        if (warnedRuntimeFailures.add(key)) {
            plugin.logger.warning(message)
        }
    }

    private companion object {
        val cooldownNumberTypes = setOf(
            java.lang.Float.TYPE,
            java.lang.Float::class.java,
            java.lang.Double.TYPE,
            java.lang.Double::class.java,
            java.lang.Integer.TYPE,
            java.lang.Integer::class.java,
            java.lang.Long.TYPE,
            java.lang.Long::class.java
        )
    }
}
