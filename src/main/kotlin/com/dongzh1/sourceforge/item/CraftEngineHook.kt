package com.dongzh1.sourceforge.item

import net.momirealms.craftengine.bukkit.api.CraftEngineItems
import net.momirealms.craftengine.core.util.Key
import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack

object CraftEngineHook {
    val enabled: Boolean
        get() = Bukkit.getPluginManager().isPluginEnabled("CraftEngine")

    fun itemId(item: ItemStack): String? {
        if (!enabled) return null
        return runCatching { CraftEngineItems.getCustomItemId(item)?.asString() }.getOrNull()
    }

    fun build(id: String, amount: Int = 1): ItemStack? {
        if (!enabled) return null
        val key = parseKey(id) ?: return null
        return runCatching { CraftEngineItems.byId(key)?.buildItemStack(amount) }.getOrNull()
    }

    private fun parseKey(raw: String): Key? {
        val normalized = raw.removePrefix("ce_")
        val parts = normalized.split(":", limit = 2)
        return when (parts.size) {
            2 -> Key(parts[0], parts[1])
            1 -> Key("default", parts[0])
            else -> null
        }
    }
}
