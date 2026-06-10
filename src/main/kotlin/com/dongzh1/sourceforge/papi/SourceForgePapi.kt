package com.dongzh1.sourceforge.papi

import com.dongzh1.sourceforge.SourceForge
import com.xbaimiao.easylib.bridge.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.text.DecimalFormat

object SourceForgePapi : PlaceholderExpansion() {
    private lateinit var plugin: SourceForge

    override val identifier: String
        get() = "sourceforge"

    override val version: String
        get() = "1.0.0"

    fun register(plugin: SourceForge) {
        this.plugin = plugin
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            plugin.logger.info("未检测到 PlaceholderAPI，SourceForge PAPI 占位符未注册")
            return
        }
        super.register()
        plugin.logger.info("SourceForge PAPI 占位符已注册")
    }

    override fun onRequest(p: OfflinePlayer, params: String): String? {
        val player = p.player ?: return "0"
        val key = params.lowercase()
        val mainHand = player.inventory.itemInMainHand
        return when {
            key == "type" || key == "item_type" -> plugin.itemService.weaponType(mainHand) ?: ""
            key == "category" || key == "weapon_category" -> plugin.itemService.weaponCategory(mainHand) ?: ""
            key == "tier" -> plugin.itemService.equipmentTier(mainHand).toString()
            key == "score" -> plugin.itemService.readScore(mainHand).toString()
            key == "price" -> format(plugin.itemService.readPrice(mainHand), 1)

            key == "physical_damage" -> format(plugin.itemService.readFlatPhysicalDamage(mainHand), 1)
            key == "magic_damage" -> format(plugin.itemService.readFlatMagicDamage(mainHand), 1)
            key == "true_damage" -> format(plugin.itemService.readTrueDamage(mainHand), 1)
            key.endsWith("_damage") && key.substringBefore("_damage") in ELEMENTS ->
                format(plugin.itemService.readElementDamage(mainHand, key.substringBefore("_damage")), 1)

            key == "crit_chance" -> format(plugin.itemService.readAffixValue(mainHand, "crit_chance"), 4)
            key == "crit_chance_percent" -> percent(plugin.itemService.readAffixValue(mainHand, "crit_chance"))
            key == "crit_damage" -> format(plugin.itemService.readAffixValue(mainHand, "crit_damage"), 4)
            key == "crit_damage_percent" -> percent(plugin.itemService.readAffixValue(mainHand, "crit_damage"))
            key == "armor_pierce" -> format(plugin.itemService.readAffixValue(mainHand, "armor_pierce"), 1)

            key == "physical_resistance" -> format(armorAffixTotal(player, "physical_resistance"), 4)
            key == "physical_resistance_percent" -> percent(armorAffixTotal(player, "physical_resistance"))
            key == "magic_resistance" -> format(armorAffixTotal(player, "magic_resistance"), 4)
            key == "magic_resistance_percent" -> percent(armorAffixTotal(player, "magic_resistance"))
            key == "dodge_chance" -> format(armorAffixTotal(player, "dodge_chance"), 4)
            key == "dodge_chance_percent" -> percent(armorAffixTotal(player, "dodge_chance"))
            key.endsWith("_resistance") && key.substringBefore("_resistance") in ELEMENTS ->
                format(armorAffixTotal(player, "${key.substringBefore("_resistance")}_resistance"), 1)

            key == "enchant_slots" -> plugin.enchantService.enchantSlots(mainHand).toString()
            key == "enchant_used" -> plugin.enchantService.readEnchantLevels(mainHand).size.toString()
            key.startsWith("enchant_") -> plugin.enchantService
                .readEnchantLevel(mainHand, key.removePrefix("enchant_"))
                .toString()

            key.startsWith("affix_") -> format(plugin.itemService.readAffixValue(mainHand, key.removePrefix("affix_")), 4)

            key == "vanilla_attack_damage" -> format(attribute(player, Attribute.ATTACK_DAMAGE), 2)
            key == "vanilla_attack_speed" -> format(attribute(player, Attribute.ATTACK_SPEED), 2)
            key == "vanilla_physical_defense" || key == "vanilla_armor" -> format(attribute(player, Attribute.ARMOR), 2)
            key == "vanilla_magic_defense" || key == "vanilla_armor_toughness" -> format(attribute(player, Attribute.ARMOR_TOUGHNESS), 2)

            else -> null
        }
    }

    private fun armorAffixTotal(player: Player, affixId: String): Double {
        return sourceDefensiveItems(player).sumOf { plugin.itemService.readAffixValue(it, affixId) }
    }

    private fun sourceDefensiveItems(player: Player): List<ItemStack> {
        val armor = player.inventory.armorContents.filterNotNull().filter { plugin.itemService.isSourceEquipment(it) }
        val inventoryItems = player.inventory.contents
            .filterNotNull()
            .filter { plugin.itemService.isSourceEquipment(it) }
            .filter {
                val equipment = plugin.itemService.equipmentConfig(it) ?: return@filter false
                "inventory" in equipment.effectiveSlots || "backpack" in equipment.effectiveSlots
            }
        return armor + inventoryItems
    }

    private fun attribute(player: Player, attribute: Attribute): Double {
        return player.getAttribute(attribute)?.value ?: 0.0
    }

    private fun percent(value: Double): String {
        return format(value * 100.0, 2)
    }

    private fun format(value: Double, decimals: Int): String {
        if (decimals <= 0) return value.toInt().toString()
        return DecimalFormat("0." + "0".repeat(decimals)).format(value)
    }

    private val ELEMENTS = setOf("fire", "ice", "lightning", "water", "wood")
}
