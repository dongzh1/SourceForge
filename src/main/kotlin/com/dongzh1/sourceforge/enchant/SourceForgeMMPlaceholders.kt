package com.dongzh1.sourceforge.enchant

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.text.DecimalFormat

class SourceForgeMMPlaceholders(private val plugin: SourceForge) {

    fun registerIfAvailable() {
        if (!Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) return
        try {
            val mythicBukkitClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit")
            val inst = mythicBukkitClass.getMethod("inst").invoke(null)
            val manager = mythicBukkitClass.getMethod("getPlaceholderManager").invoke(inst)
            val registerMethod = manager.javaClass.getMethod("register", Array<String>::class.java, Class.forName("io.lumine.mythic.core.skills.placeholders.Placeholder"))
            val placeholderClass = Class.forName("io.lumine.mythic.core.skills.placeholders.Placeholder")
            val entityMethod = placeholderClass.getMethod("entity", java.util.function.BiFunction::class.java)

            var count = 0
            for (affix in plugin.forgeConfig.affixes.values) {
                val names = arrayOf("sourceforge.${affix.id}", "sf.${affix.id}")
                val placeholder = entityMethod.invoke(null, java.util.function.BiFunction { entity: Any, _: String? ->
                    try {
                        val be = entity.javaClass.getMethod("getBukkitEntity").invoke(entity)
                        if (be is Player) {
                            val value = plugin.itemService.readDisplayTotalAffix(be, affix.id)
                            formatAffix(value, affix.decimals)
                        } else "0"
                    } catch (_: Exception) { "0" }
                })
                registerMethod.invoke(manager, names, placeholder)
                count++
            }
            plugin.logger.info("SourceForge MM 占位符已注册: $count 个属性")

            // 额外注册运行时属性
            val runtime = java.util.function.BiFunction { entity: Any, _: String? ->
                try {
                    val be = entity.javaClass.getMethod("getBukkitEntity").invoke(entity)
                    if (be is Player) {
                        "%.0f".format(plugin.forgeListener.getEnergyCurrent(be))
                    } else "0"
                } catch (_: Exception) { "0" }
            }
            val rp = entityMethod.invoke(null, runtime)
            registerMethod.invoke(manager, arrayOf("sourceforge.energy_current", "sf.energy_current"), rp)
        } catch (e: Exception) {
            plugin.logger.warning("注册 MM 占位符失败: ${e.message}")
        }
    }

    private fun formatAffix(value: Double, decimals: Int): String {
        if (decimals <= 0) return value.toInt().toString()
        return DecimalFormat("0.${
            "0".repeat(decimals)
        }").format(value)
    }
}
