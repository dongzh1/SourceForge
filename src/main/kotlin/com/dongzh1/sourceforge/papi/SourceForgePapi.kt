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
        dynamicPlaceholder(player, mainHand, key)?.let { return it }

        return when {
            // 装备基本信息
            key == "type" || key == "item_type" -> plugin.itemService.weaponType(mainHand) ?: ""
            key == "category" || key == "weapon_category" -> plugin.itemService.weaponCategory(mainHand) ?: ""
            key == "tier" -> plugin.itemService.equipmentTier(mainHand).toString()
            key == "score" -> plugin.itemService.readScore(mainHand).toString()
            key == "price" -> format(plugin.itemService.readPrice(mainHand), 1)

            // 核心战斗属性 (手持物品)
            key == "base_damage" -> format(plugin.itemService.readAffixValue(mainHand, "base_damage"), 1)
            key == "critical_chance" -> format(plugin.itemService.readAffixValue(mainHand, "critical_chance"), 4)
            key == "critical_chance_percent" -> percent(plugin.itemService.readAffixValue(mainHand, "critical_chance"))
            key == "critical_damage" -> format(plugin.itemService.readAffixValue(mainHand, "critical_damage"), 4)
            key == "critical_damage_percent" -> percent(plugin.itemService.readAffixValue(mainHand, "critical_damage"))
            key == "status_chance" -> format(plugin.itemService.readAffixValue(mainHand, "status_chance"), 4)
            key == "status_chance_percent" -> percent(plugin.itemService.readAffixValue(mainHand, "status_chance"))

            // 技能属性 (手持物品)
            key == "ability_strength" -> format(plugin.itemService.readAffixValue(mainHand, "ability_strength"), 4)
            key == "ability_strength_percent" -> percent(plugin.itemService.readAffixValue(mainHand, "ability_strength"))
            key == "ability_duration" -> format(plugin.itemService.readAffixValue(mainHand, "ability_duration"), 4)
            key == "ability_duration_percent" -> percent(plugin.itemService.readAffixValue(mainHand, "ability_duration"))
            key == "ability_efficiency" -> format(plugin.itemService.readAffixValue(mainHand, "ability_efficiency"), 4)
            key == "ability_efficiency_percent" -> percent(plugin.itemService.readAffixValue(mainHand, "ability_efficiency"))
            key == "ability_range" -> format(plugin.itemService.readAffixValue(mainHand, "ability_range"), 4)
            key == "ability_range_percent" -> percent(plugin.itemService.readAffixValue(mainHand, "ability_range"))

            // 生存属性 (全身)
            key == "health" -> format(totalAffix(player, "health"), 0)
            key == "shield_capacity" -> format(totalAffix(player, "shield_capacity"), 0)
            key == "total_health" -> format(totalAffix(player, "health") + totalAffix(player, "shield_capacity"), 0)
            key == "armor" -> format(totalAffix(player, "armor"), 0)
            key == "energy_max" -> format(totalAffix(player, "energy_max"), 0)

            // 总属性 (全身)
            key == "total_base_damage" -> format(totalAffix(player, "base_damage"), 1)
            key == "total_critical_chance" -> format(totalAffix(player, "critical_chance"), 4)
            key == "total_critical_damage" -> format(totalAffix(player, "critical_damage"), 4)
            key == "total_status_chance" -> format(totalAffix(player, "status_chance"), 4)
            key == "total_ability_strength" -> format(totalAffix(player, "ability_strength"), 4)
            key == "total_ability_duration" -> format(totalAffix(player, "ability_duration"), 4)
            key == "total_ability_efficiency" -> format(totalAffix(player, "ability_efficiency"), 4)
            key == "total_ability_range" -> format(totalAffix(player, "ability_range"), 4)

            // 原版属性
            key == "vanilla_attack_damage" -> format(attribute(player, Attribute.ATTACK_DAMAGE), 2)
            key == "vanilla_attack_speed" -> format(attribute(player, Attribute.ATTACK_SPEED), 2)
            key == "vanilla_armor" -> format(attribute(player, Attribute.ARMOR), 2)
            key == "vanilla_armor_toughness" -> format(attribute(player, Attribute.ARMOR_TOUGHNESS), 2)
            key == "vanilla_max_health" -> format(attribute(player, Attribute.MAX_HEALTH), 2)

            else -> null
        }
    }

    private fun dynamicPlaceholder(player: Player, mainHand: ItemStack, key: String): String? {
        when (key) {
            "cooldown_multiplier", "cooldown_mult" -> {
                return format(cooldownMultiplier(totalAffix(player, "ability_efficiency")), 4)
            }
            "hand_cooldown_multiplier", "hand_cooldown_mult" -> {
                return format(cooldownMultiplier(plugin.itemService.readAffixValue(mainHand, "ability_efficiency")), 4)
            }
            "strength_multiplier", "strength_mult" -> {
                return format(1.0 + totalAffix(player, "ability_strength"), 4)
            }
            "duration_multiplier", "duration_mult" -> {
                return format(1.0 + totalAffix(player, "ability_duration"), 4)
            }
            "range_multiplier", "range_mult" -> {
                return format(1.0 + totalAffix(player, "ability_range"), 4)
            }
        }

        if (key.startsWith("hand_cooldown_")) {
            val base = key.removePrefix("hand_cooldown_").placeholderNumber() ?: return null
            val efficiency = plugin.itemService.readAffixValue(mainHand, "ability_efficiency")
            return format(base * cooldownMultiplier(efficiency), 4)
        }
        if (key.startsWith("cooldown_")) {
            val base = key.removePrefix("cooldown_").placeholderNumber() ?: return null
            return format(base * cooldownMultiplier(totalAffix(player, "ability_efficiency")), 4)
        }

        if (key.startsWith("hand_")) {
            val affixId = key.removePrefix("hand_")
            return affixValue(affixId) { plugin.itemService.readAffixValue(mainHand, affixId) }
        }
        if (key.startsWith("attr_")) {
            val affixId = key.removePrefix("attr_")
            return affixValue(affixId) { totalAffix(player, affixId) }
        }
        if (key != "total_health" && key.startsWith("total_")) {
            val affixId = key.removePrefix("total_")
            return affixValue(affixId) { totalAffix(player, affixId) }
        }
        return null
    }

    private fun affixValue(affixId: String, read: () -> Double): String? {
        val affix = plugin.forgeConfig.affixes[affixId] ?: return null
        return format(read(), affix.decimals)
    }

    /** 获取玩家全身 SourceForge 装备某属性总和 */
    private fun totalAffix(player: Player, affixId: String): Double {
        return sourceItems(player).sumOf { plugin.itemService.readAffixValue(it, affixId) }
    }

    /** 获取所有生效的 SourceForge 装备（装甲槽+背包内 effective-slots 包含 inventory 的物品） */
    private fun sourceItems(player: Player): List<ItemStack> {
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

    private fun cooldownMultiplier(efficiency: Double): Double {
        return (1.0 - efficiency).coerceAtLeast(0.0)
    }

    private fun String.placeholderNumber(): Double? {
        return replace(',', '.').replace('_', '.').toDoubleOrNull()
    }

    private fun format(value: Double, decimals: Int): String {
        if (decimals <= 0) return value.toInt().toString()
        return DecimalFormat("0." + "0".repeat(decimals)).format(value)
    }
}
