package com.dongzh1.sourceforge.enchant

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.hud.BetterHudHook
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.EventExecutor
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class SourceForgeSkillListener(private val plugin: SourceForge) : Listener {
    private val warnedRuntimeFailures = Collections.synchronizedSet(mutableSetOf<String>())
    private val cdNameKeys = Array(4) { NamespacedKey(plugin, "cd_${it + 1}_name") }
    private val cdMaxKeys  = Array(4) { NamespacedKey(plugin, "cd_${it + 1}_max") }
    private val cdStartKeys = Array(4) { NamespacedKey(plugin, "cd_${it + 1}_start") }

    // 反射 Method 缓存，避免每次技能事件都全量扫描方法列表
    private val noArgMethodCache = ConcurrentHashMap<String, Method>()
    private val cdGetMethodCache = ConcurrentHashMap<String, Method>()
    private val cdSetMethodCache = ConcurrentHashMap<String, Method>()

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

            // 效率在事件触发时（主线程）即可安全读取（缓存已预热）
            val efficiency = plugin.itemService.readTotalAffix(player, "ability_efficiency").coerceAtLeast(0.0)
            if (efficiency <= 0.0) return // 无效率则不干预，保留 MM 原版冷却
            val multiplier = (1.0 - efficiency).coerceAtLeast(0.0)
            val skillName = skillName(skill)
            val rawName = (callNoArg(skill, "getInternalName") as? String).orEmpty()

            // MM 在技能执行期间（本 tick 稍后）才把冷却应用到 caster，事件触发时
            // getCooldown(caster) 仍为 0。因此推迟到下一 tick 读取"已应用的剩余冷却"，
            // 再按 ×(1-效率) 缩减回写。这样无需技能基础 CD 配置，对 MM 时序健壮。
            plugin.server.scheduler.runTask(plugin, Runnable {
                val applied = skillCooldown(skill, caster) ?: return@Runnable
                if (applied <= 0.0) return@Runnable // 该(子)技能无冷却，跳过
                val afterCd = applied * multiplier
                val cdTicks = (afterCd * 20).toLong()
                setSkillCooldown(skill, caster, afterCd)
                recordSkillCd(player, skillName, cdTicks)
                val betterHud = plugin.forgeConfig.betterHud
                if (betterHud.enabled) {
                    BetterHudHook.showSkillCd(plugin, player, betterHud.skillCdPopup, rawName, afterCd, cdTicks)
                }
                if (plugin.forgeConfig.debugCombat) {
                    plugin.logger.info(
                        "[SF-DBG] CD缩减 ${player.name} $skillName: " +
                            "${"%.2f".format(applied)}s -> ${"%.2f".format(afterCd)}s " +
                            "(效率=${"%.0f".format(efficiency * 100)}%)"
                    )
                }
            })
        } catch (e: Exception) {
            warnOnce("skill:${e.javaClass.name}:${e.message}", "MM 技能效率处理失败: ${e.message}")
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
        val cacheKey = skill.javaClass.name + ":" + caster.javaClass.name
        cdGetMethodCache[cacheKey]?.let { return it }
        val method = skill.javaClass.methods.firstOrNull {
            it.name == "getCooldown" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0].isAssignableFrom(caster.javaClass)
        } ?: return null
        cdGetMethodCache[cacheKey] = method
        return method
    }

    private fun setSkillCooldownMethod(skill: Any, caster: Any): Method? {
        val cacheKey = skill.javaClass.name + ":" + caster.javaClass.name
        cdSetMethodCache[cacheKey]?.let { return it }
        val method = skill.javaClass.methods.firstOrNull {
            it.name == "setCooldown" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0].isAssignableFrom(caster.javaClass) &&
                it.parameterTypes[1] in cooldownNumberTypes
        } ?: return null
        cdSetMethodCache[cacheKey] = method
        return method
    }

    private fun casterPlayer(caster: Any): Player? {
        val entity = callNoArg(caster, "getEntity") ?: return null
        return callNoArg(entity, "getBukkitEntity") as? Player
    }

    private fun skillName(skill: Any): String {
        val name = callNoArg(skill, "getInternalName") as? String
        return if (name.isNullOrBlank()) "" else "[$name]"
    }

    // ==================== CD 追踪 ====================

    data class ActiveCd(val name: String, val maxTicks: Long, val startTime: Long) {
        val remainingTicks: Long
            get() = (maxTicks - (System.currentTimeMillis() - startTime) / 50).coerceAtLeast(0)
    }

    fun getActiveCds(player: Player): List<ActiveCd> {
        val pdc = player.persistentDataContainer
        val result = mutableListOf<ActiveCd>()
        for (i in 0 until 4) {
            val name = pdc.get(cdNameKeys[i], PersistentDataType.STRING) ?: continue
            val max = pdc.get(cdMaxKeys[i], PersistentDataType.LONG) ?: 0L
            val start = pdc.get(cdStartKeys[i], PersistentDataType.LONG) ?: 0L
            val cd = ActiveCd(name, max, start)
            if (cd.remainingTicks > 0) {
                result += cd
            }
        }
        return result
    }

    private fun recordSkillCd(player: Player, name: String, cdTicks: Long) {
        val pdc = player.persistentDataContainer
        val existing = getActiveCds(player).toMutableList()
        // 移除同名技能旧记录
        existing.removeAll { it.name == name }
        // 添加到头部
        existing.add(0, ActiveCd(name, cdTicks, System.currentTimeMillis()))
        // 保留前4个
        val keep = existing.take(4)
        // 写入 PDC
        for (i in 0 until 4) {
            val cd = keep.getOrNull(i)
            if (cd != null) {
                pdc.set(cdNameKeys[i], PersistentDataType.STRING, cd.name)
                pdc.set(cdMaxKeys[i], PersistentDataType.LONG, cd.maxTicks)
                pdc.set(cdStartKeys[i], PersistentDataType.LONG, cd.startTime)
            } else {
                pdc.remove(cdNameKeys[i])
                pdc.remove(cdMaxKeys[i])
                pdc.remove(cdStartKeys[i])
            }
        }
    }

    private fun isCancelled(event: Any): Boolean {
        return (callNoArg(event, "isCancelled") as? Boolean) == true
    }

    private fun callNoArg(target: Any, name: String): Any? {
        val cacheKey = target.javaClass.name + "#" + name
        val method = noArgMethodCache[cacheKey]
            ?: target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                ?.also { noArgMethodCache[cacheKey] = it }
            ?: return null
        return method.invoke(target)
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
