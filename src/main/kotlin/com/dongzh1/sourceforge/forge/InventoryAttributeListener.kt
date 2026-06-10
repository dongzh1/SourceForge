package com.dongzh1.sourceforge.forge

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlotGroup

class InventoryAttributeListener(
    private val plugin: SourceForge
) : Listener {
    private val attackDamageKey = NamespacedKey(plugin, "inventory_attack_damage")
    private val attackSpeedKey = NamespacedKey(plugin, "inventory_attack_speed")
    private val armorKey = NamespacedKey(plugin, "inventory_physical_defense")
    private val toughnessKey = NamespacedKey(plugin, "inventory_magic_defense")

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        syncLater(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        clear(event.player)
    }

    @EventHandler
    fun onHeld(event: PlayerItemHeldEvent) {
        syncLater(event.player)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        syncLater(player)
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        syncLater(player)
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        syncLater(player)
    }

    private fun syncLater(player: Player) {
        plugin.server.scheduler.runTask(plugin, Runnable { sync(player) })
    }

    private fun sync(player: Player) {
        clear(player)
        var attackDamage = 0.0
        var attackSpeed = 0.0
        var physicalDefense = 0.0
        var magicDefense = 0.0
        for (item in player.inventory.contents.filterNotNull()) {
            val equipment = plugin.itemService.equipmentConfig(item) ?: continue
            if ("inventory" !in equipment.effectiveSlots && "backpack" !in equipment.effectiveSlots) continue
            attackDamage += plugin.itemService.readFlatPhysicalDamage(item)
            attackSpeed += inventoryAttackSpeed(equipment.weaponCategory)
            physicalDefense += equipment.physicalDefense
            magicDefense += equipment.magicDefense
        }
        add(player, Attribute.ATTACK_DAMAGE, attackDamageKey, attackDamage)
        add(player, Attribute.ATTACK_SPEED, attackSpeedKey, attackSpeed)
        add(player, Attribute.ARMOR, armorKey, physicalDefense)
        add(player, Attribute.ARMOR_TOUGHNESS, toughnessKey, magicDefense)
    }

    private fun clear(player: Player) {
        player.getAttribute(Attribute.ATTACK_DAMAGE)?.removeModifier(attackDamageKey)
        player.getAttribute(Attribute.ATTACK_SPEED)?.removeModifier(attackSpeedKey)
        player.getAttribute(Attribute.ARMOR)?.removeModifier(armorKey)
        player.getAttribute(Attribute.ARMOR_TOUGHNESS)?.removeModifier(toughnessKey)
    }

    private fun add(player: Player, attribute: Attribute, key: NamespacedKey, amount: Double) {
        if (amount <= 0.0) return
        player.getAttribute(attribute)?.addTransientModifier(
            AttributeModifier(
                key,
                amount,
                AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.ANY
            )
        )
    }

    private fun inventoryAttackSpeed(category: String): Double {
        return when (category.lowercase()) {
            "melee_light" -> 2.1
            "melee_heavy" -> 1.25
            "polearm" -> 1.45
            "bow" -> 1.0
            "crossbow" -> 0.85
            "firearm" -> 0.65
            else -> 0.0
        }
    }
}
