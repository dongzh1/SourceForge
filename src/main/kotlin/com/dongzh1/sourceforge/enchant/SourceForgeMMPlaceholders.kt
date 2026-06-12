package com.dongzh1.sourceforge.enchant

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import java.text.DecimalFormat

/**
 * 注册 SourceForge 12 属性为 MythicMobs 原生占位符
 * 技能中可用 <sourceforge.base_damage> 等格式直接读取玩家全身属性
 */
class SourceForgeMMPlaceholders(private val plugin: SourceForge) : Listener {

    fun registerIfAvailable() {
        if (!Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) return
        try {
            val eventClass = Class.forName("io.lumine.mythic.bukkit.events.MythicPlaceholdersLoadEvent")
            @Suppress("UNCHECKED_CAST")
            val typedClass = eventClass as Class<org.bukkit.event.Event>
            Bukkit.getPluginManager().registerEvent(
                typedClass,
                this,
                org.bukkit.event.EventPriority.NORMAL,
                EventExecutor { _, event ->
                    if (eventClass.isInstance(event)) {
                        onPlaceholdersLoad(event)
                    }
                },
                plugin
            )
            plugin.logger.info("SourceForge MM 占位符监听已注册")
        } catch (e: Exception) {
            plugin.logger.warning("无法注册 MM 占位符: ${e.message}")
        }
    }

    private fun onPlaceholdersLoad(event: Any) {
        try {
            val manager = event.javaClass.getMethod("getManager").invoke(event)
            val registerMethod = manager.javaClass.getMethod("register", Array<String>::class.java, Class.forName("io.lumine.mythic.core.skills.placeholders.Placeholder"))
            val placeholderClass = Class.forName("io.lumine.mythic.core.skills.placeholders.Placeholder")
            val entityMethod = placeholderClass.getMethod("entity", java.util.function.BiFunction::class.java)

            val affixes = plugin.forgeConfig.affixes.values
            for (affix in affixes) {
                val names = arrayOf("sourceforge.${affix.id}", "sf.${affix.id}")
                val placeholder = entityMethod.invoke(null, java.util.function.BiFunction { entity: Any, _: String? ->
                    try {
                        val bukkitEntity = entity.javaClass.getMethod("getBukkitEntity").invoke(entity)
                        if (bukkitEntity is Player) {
                            val value = plugin.itemService.readDisplayTotalAffix(bukkitEntity, affix.id)
                            formatAffixValue(value, affix.decimals)
                        } else "0"
                    } catch (_: Exception) { "0" }
                })
                registerMethod.invoke(manager, names, placeholder)
            }

            // 额外: total 复合占位符
            registerTotal(manager, registerMethod, placeholderClass, entityMethod)

            plugin.logger.info("SourceForge MM 占位符已注册: ${affixes.size} 个属性")
        } catch (e: Exception) {
            plugin.logger.warning("注册 MM 占位符失败: ${e.message}")
        }
    }

    private fun registerTotal(manager: Any, registerMethod: java.lang.reflect.Method, placeholderClass: Class<*>, entityMethod: java.lang.reflect.Method) {
        try {
            val total = java.util.function.BiFunction { entity: Any, _: String? ->
                try {
                    val bukkitEntity = entity.javaClass.getMethod("getBukkitEntity").invoke(entity)
                    if (bukkitEntity is Player) {
                        val totalScore = plugin.forgeConfig.affixes.values.sumOf {
                            plugin.itemService.readDisplayTotalAffix(bukkitEntity, it.id)
                        }
                        DecimalFormat("0.#").format(totalScore)
                    } else "0"
                } catch (_: Exception) { "0" }
            }
            val placeholder = entityMethod.invoke(null, total)
            registerMethod.invoke(manager, arrayOf("sourceforge.total", "sf.total"), placeholder)
        } catch (_: Exception) {}
    }

    private fun formatAffixValue(value: Double, decimals: Int): String {
        if (decimals <= 0) return value.toInt().toString()
        return DecimalFormat("0.${
            "0".repeat(decimals)
        }").format(value)
    }

    @EventHandler
    fun onDisable() {
        HandlerList.unregisterAll(this as Listener)
    }
}
