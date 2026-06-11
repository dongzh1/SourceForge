package com.dongzh1.sourceforge.enchant

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.inventory.PrepareAnvilEvent

/**
 * 附魔监听器 v2 — 仅阻止原版附魔和铁砧对 SF 装备的操作。
 */
class SourceEnchantListener(
    private val plugin: SourceForge
) : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onEnchantItem(event: EnchantItemEvent) {
        if (!plugin.itemService.isSourceEquipment(event.item)) return
        event.isCancelled = true
        event.enchanter.sendMessage("§c[SourceForge] §fSourceForge 装备不能使用原版附魔")
    }

    @EventHandler
    fun onPrepareAnvil(event: PrepareAnvilEvent) {
        val first = event.inventory.firstItem
        val second = event.inventory.secondItem
        if (!plugin.itemService.isSourceEquipment(first) && !plugin.itemService.isSourceEquipment(second)) return
        event.result = null
    }
}
