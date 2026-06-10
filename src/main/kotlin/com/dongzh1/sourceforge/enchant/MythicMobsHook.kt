package com.dongzh1.sourceforge.enchant

import org.bukkit.Bukkit
import org.bukkit.entity.Player

class MythicMobsHook {
    fun isAvailable(): Boolean {
        return Bukkit.getPluginManager().isPluginEnabled("MythicMobs")
    }

    fun castSkill(player: Player, skillName: String): CastResult {
        if (!isAvailable()) return CastResult.MISSING_PLUGIN
        if (skillName.isBlank()) return CastResult.UNKNOWN_SKILL
        return runCatching {
            val mythicBukkitClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit")
            val instance = mythicBukkitClass.getMethod("inst").invoke(null)
            val apiHelper = mythicBukkitClass.getMethod("getAPIHelper").invoke(instance)
            val method = apiHelper.javaClass.methods.firstOrNull { method ->
                method.name == "castSkill" &&
                    method.parameterTypes.size >= 2 &&
                    Player::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                    method.parameterTypes[1] == String::class.java
            } ?: return CastResult.UNSUPPORTED_API
            val args = arrayOfNulls<Any>(method.parameterTypes.size)
            args[0] = player
            args[1] = skillName
            for (index in 2 until args.size) {
                args[index] = defaultValue(method.parameterTypes[index])
            }
            val result = method.invoke(apiHelper, *args)
            if (result is Boolean && !result) CastResult.UNKNOWN_SKILL else CastResult.SUCCESS
        }.getOrDefault(CastResult.UNSUPPORTED_API)
    }

    private fun defaultValue(type: Class<*>): Any? {
        return when (type) {
            java.lang.Boolean.TYPE -> true
            java.lang.Integer.TYPE -> 1
            java.lang.Double.TYPE -> 1.0
            java.lang.Float.TYPE -> 1.0f
            java.lang.Long.TYPE -> 0L
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> 0.toChar()
            else -> null
        }
    }

    enum class CastResult {
        SUCCESS,
        MISSING_PLUGIN,
        UNKNOWN_SKILL,
        UNSUPPORTED_API
    }
}
