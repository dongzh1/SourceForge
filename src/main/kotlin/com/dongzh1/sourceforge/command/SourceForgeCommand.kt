package com.dongzh1.sourceforge.command

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.forge.ForgeMenu
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.text.DecimalFormat

class SourceForgeCommand(
    private val plugin: SourceForge
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.getOrNull(0)?.lowercase()) {
            null, "forge" -> {
                val player = sender as? Player
                if (player == null) {
                    sender.sendMessage("只有玩家可以打开锻造界面")
                    return true
                }
                player.openInventory(ForgeMenu(plugin).inventory)
            }
            "reload" -> {
                if (!sender.hasPermission("sourceforge.admin")) {
                    sender.sendMessage("§c你没有权限")
                    return true
                }
                plugin.reloadAll()
                sender.sendMessage("§a[SourceForge] §f配置已重载")
                sendValidationSummary(sender)
            }
            "validate" -> {
                if (!sender.hasPermission("sourceforge.admin")) {
                    sender.sendMessage("§c你没有权限")
                    return true
                }
                sendValidationSummary(sender, verbose = true)
            }
            "giveblueprint" -> {
                if (!sender.hasPermission("sourceforge.admin")) {
                    sender.sendMessage("§c你没有权限")
                    return true
                }
                val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
                val blueprint = plugin.forgeConfig.blueprints[args.getOrNull(2)]
                if (target == null || blueprint == null) {
                    sender.sendMessage("§e用法: /$label giveblueprint <玩家> <蓝图ID> [等级] [数量]")
                    return true
                }
                val tierRange = parseTierRange(args.getOrNull(3), blueprint.defaultTierRange())
                val amount = args.getOrNull(4)?.toIntOrNull() ?: 1
                target.inventory.addItem(plugin.itemService.createBlueprint(blueprint, tierRange = tierRange, amount = amount))
                    .values.forEach { target.world.dropItemNaturally(target.location, it) }
                sender.sendMessage("§a[SourceForge] §f已给予 ${target.name} 蓝图 ${blueprint.id}")
            }
            "giveequipment" -> {
                if (!sender.hasPermission("sourceforge.admin")) {
                    sender.sendMessage("§c你没有权限")
                    return true
                }
                val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
                val equipmentId = args.getOrNull(2)
                val tier = args.getOrNull(3)?.toIntOrNull() ?: 1
                val amount = args.getOrNull(4)?.toIntOrNull() ?: 1
                val affixes = args.getOrNull(5)?.toIntOrNull()
                if (target == null || equipmentId == null) {
                    sender.sendMessage("§e用法: /$label giveequipment <玩家> <装备ID> [等级] [数量] [词条数]")
                    return true
                }
                if (equipmentId !in plugin.forgeConfig.equipment) {
                    sender.sendMessage("§c[SourceForge] §f未知装备: $equipmentId")
                    return true
                }
                var generated = 0
                repeat(amount.coerceAtLeast(1)) {
                    val item = plugin.itemService.createDirectEquipment(equipmentId, tier, affixes) ?: return@repeat
                    target.inventory.addItem(item).values.forEach { target.world.dropItemNaturally(target.location, it) }
                    generated++
                }
                sender.sendMessage("§a[SourceForge] §f已给予 ${target.name} 装备 $equipmentId 等级 $tier x$generated 词条数 ${affixes?.toString() ?: "默认"}")
            }
            "debug" -> {
                if (!sender.hasPermission("sourceforge.admin")) {
                    sender.sendMessage("§c你没有权限")
                    return true
                }
                val target = args.getOrNull(1)?.lowercase()
                val value = args.getOrNull(2)?.lowercase()
                if (target != "combat" || value !in setOf("on", "off", "true", "false")) {
                    sender.sendMessage("§e用法: /$label debug combat <on|off>")
                    return true
                }
                val enabled = value == "on" || value == "true"
                plugin.config.set("debug.combat", enabled)
                plugin.saveConfig()
                plugin.reloadAll()
                sender.sendMessage("§a[SourceForge] §f战斗调试已${if (enabled) "开启" else "关闭"}")
            }
            "givematerial" -> {
                if (!sender.hasPermission("sourceforge.admin")) {
                    sender.sendMessage("§c你没有权限")
                    return true
                }
                val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
                val material = plugin.forgeConfig.forgeMaterials.firstOrNull { it.id.equals(args.getOrNull(2), ignoreCase = true) }
                if (target == null || material == null) {
                    sender.sendMessage("§e用法: /$label givematerial <玩家> <材料ID> [数量]")
                    return true
                }
                val amount = args.getOrNull(3)?.toIntOrNull() ?: 1
                val item = plugin.itemService.buildConfiguredItem(material.itemId, amount)
                if (item == null) {
                    sender.sendMessage("§c[SourceForge] §f无法生成材料: ${material.itemId}")
                    return true
                }
                target.inventory.addItem(item).values.forEach { target.world.dropItemNaturally(target.location, it) }
                sender.sendMessage("§a[SourceForge] §f已给予 ${target.name} 材料 ${material.displayName} x${amount.coerceAtLeast(1)}")
            }
            "give" -> {
                if (!sender.hasPermission("sourceforge.admin")) {
                    sender.sendMessage("§c你没有权限")
                    return true
                }
                val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
                val expression = args.getOrNull(2)
                if (target == null || expression == null) {
                    sender.sendMessage("§e用法: /$label give <玩家> <sf表达式> [数量]")
                    return true
                }
                val amount = args.getOrNull(3)?.toIntOrNull() ?: 1
                if (isEquipmentExpression(expression)) {
                    var generated = 0
                    repeat(amount.coerceAtLeast(1)) {
                        val item = plugin.buildItemExpression(expression, target, 1) ?: return@repeat
                        target.inventory.addItem(item).values.forEach { target.world.dropItemNaturally(target.location, it) }
                        generated++
                    }
                    if (generated <= 0) {
                        sender.sendMessage("§c[SourceForge] §f无法生成物品: $expression")
                        return true
                    }
                    sender.sendMessage("§a[SourceForge] §f已给予 ${target.name}: $expression x$generated")
                } else {
                    val item = plugin.buildItemExpression(expression, target, amount)
                    if (item == null) {
                        sender.sendMessage("§c[SourceForge] §f无法生成物品: $expression")
                        return true
                    }
                    target.inventory.addItem(item).values.forEach { target.world.dropItemNaturally(target.location, it) }
                    sender.sendMessage("§a[SourceForge] §f已给予 ${target.name}: $expression")
                }
            }
            "testdamage", "mmdamage" -> {
                if (!sender.hasPermission("sourceforge.admin")) {
                    sender.sendMessage("§c你没有权限")
                    return true
                }
                val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
                val amount = args.getOrNull(2)?.toDoubleOrNull()
                if (target == null || amount == null || amount <= 0.0) {
                    sender.sendMessage("§e用法: /$label testdamage <玩家> <伤害>")
                    return true
                }
                target.damage(amount)
                sender.sendMessage("§a[SourceForge] §f已对 ${target.name} 施加测试伤害: $amount")
            }
            "reroll" -> {
                if (!sender.hasPermission("sourceforge.admin")) {
                    sender.sendMessage("§c你没有权限")
                    return true
                }
                val player = sender as? Player
                if (player == null) {
                    sender.sendMessage("只有玩家可以重铸手持装备")
                    return true
                }
                val item = player.inventory.itemInMainHand
                if (!plugin.itemService.rerollEquipment(item)) {
                    sender.sendMessage("§c[SourceForge] §f请手持 SourceForge 装备")
                    return true
                }
                sender.sendMessage("§a[SourceForge] §f手持装备已重铸")
            }
            "upgrade" -> {
                if (!sender.hasPermission("sourceforge.admin")) {
                    sender.sendMessage("§c你没有权限")
                    return true
                }
                val player = sender as? Player
                if (player == null) {
                    sender.sendMessage("只有玩家可以升级手持装备")
                    return true
                }
                val item = player.inventory.itemInMainHand
                if (!plugin.itemService.upgradeEquipment(item)) {
                    sender.sendMessage("§c[SourceForge] §f请手持未满级的 SourceForge 装备")
                    return true
                }
                sender.sendMessage("§a[SourceForge] §f手持装备已升级")
            }
            "stats" -> {
                val player = sender as? Player
                if (player == null) {
                    sender.sendMessage("只有玩家可以使用此命令")
                    return true
                }
                showStats(player)
            }
            else -> sender.sendMessage("§e用法: /$label <forge|reload|validate|giveblueprint|giveequipment|givematerial|give|testdamage|mmdamage|reroll|upgrade|stats|debug>")
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("forge", "reload", "validate", "giveblueprint", "giveequipment", "givematerial", "give", "testdamage", "mmdamage", "reroll", "upgrade", "stats", "debug").filter { it.startsWith(args[0], true) }
            2 -> when {
                args[0].equals("giveblueprint", true) || args[0].equals("giveequipment", true) || args[0].equals("givematerial", true) || args[0].equals("give", true) || args[0].equals("testdamage", true) || args[0].equals("mmdamage", true) -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) }
                args[0].equals("debug", true) -> listOf("combat").filter { it.startsWith(args[1], true) }
                else -> emptyList()
            }
            3 -> when {
                args[0].equals("giveblueprint", true) -> plugin.forgeConfig.blueprints.keys.filter { it.startsWith(args[2], true) }
                args[0].equals("giveequipment", true) -> plugin.forgeConfig.equipment.keys.filter { it.startsWith(args[2], true) }
                args[0].equals("givematerial", true) -> plugin.forgeConfig.forgeMaterials.map { it.id }.filter { it.startsWith(args[2], true) }
                args[0].equals("give", true) -> expressionSuggestions().filter { it.startsWith(args[2], true) }
                args[0].equals("debug", true) -> listOf("on", "off").filter { it.startsWith(args[2], true) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun expressionSuggestions(): List<String> {
        val blueprintExpressions = plugin.forgeConfig.blueprints.keys.flatMap {
            listOf("sf:blueprint:$it", "sf:blueprint:$it?tier=random:1-3")
        }
        val equipmentExpressions = plugin.forgeConfig.equipment.keys.map {
            "sf:equipment:$it?tier=1"
        }
        return blueprintExpressions + equipmentExpressions
    }

    private fun sendValidationSummary(sender: CommandSender, verbose: Boolean = false) {
        val warnings = plugin.forgeConfig.validationWarnings
        if (warnings.isEmpty()) {
            sender.sendMessage("§a[SourceForge] §f配置校验通过")
            return
        }
        sender.sendMessage("§e[SourceForge] §f配置校验发现 §c${warnings.size} §f个问题")
        if (verbose) {
            warnings.forEach { sender.sendMessage("§7- §f$it") }
        } else {
            sender.sendMessage("§7使用 /sf validate 查看详细列表")
        }
    }

    private fun parseTierRange(raw: String?, fallback: IntRange): IntRange {
        if (raw.isNullOrBlank()) return fallback
        val value = raw.removePrefix("random:")
        val parts = value.split("-", limit = 2).map { it.trim().toIntOrNull() }
        if (parts.size == 2 && parts[0] != null && parts[1] != null) {
            return minOf(parts[0]!!, parts[1]!!)..maxOf(parts[0]!!, parts[1]!!)
        }
        val fixed = value.toIntOrNull() ?: return fallback
        return fixed..fixed
    }

    private fun isEquipmentExpression(expression: String): Boolean {
        if (!expression.startsWith("sf:", ignoreCase = true) && !expression.startsWith("sourceforge:", ignoreCase = true)) {
            return false
        }
        val body = expression.substringAfter(":")
        val path = body.substringBefore("?")
        return path.substringBefore(":", "").equals("equipment", ignoreCase = true)
    }

    private fun showStats(player: Player) {
        val slots = linkedMapOf(
            "头盔" to player.inventory.helmet,
            "胸甲" to player.inventory.chestplate,
            "护腿" to player.inventory.leggings,
            "靴子" to player.inventory.boots,
            "主手" to player.inventory.itemInMainHand,
            "副手" to player.inventory.itemInOffHand
        )

        val pieces = slots.mapNotNull { (slotName, item) ->
            if (plugin.itemService.isSourceEquipment(item)) slotName to item else null
        }
        val backpackItems = plugin.itemService.backpackSourceItems(player)
        val allItems = plugin.itemService.effectiveSourceItems(player)

        player.sendMessage("§6========== SourceForge 属性总览 ==========")

        // 已装备
        if (pieces.isNotEmpty()) {
            for ((slotName, item) in pieces) {
                val type = plugin.itemService.weaponType(item) ?: "?"
                val tier = plugin.itemService.equipmentTier(item)
                val displayName = plugin.forgeConfig.equipment[type]?.displayName ?: type
                player.sendMessage("  §7$slotName: §f$displayName §eLv.$tier")
            }
        }
        if (backpackItems.isNotEmpty()) {
            player.sendMessage("  §7背包生效: §f${backpackItems.size} 件")
        }
        if (pieces.isEmpty() && backpackItems.isEmpty()) {
            player.sendMessage("  §7当前未装备任何 SourceForge 物品")
        }

        val totals = plugin.forgeConfig.affixes.values.associate { affix ->
            affix.id to plugin.itemService.readDisplayTotalAffix(player, affix.id)
        }

        player.sendMessage("")
        sendAffixGroup(
            player,
            "战斗属性",
            totals,
            listOf("base_damage", "critical_chance", "critical_damage", "status_chance", "armor")
        )
        sendAffixGroup(
            player,
            "生存属性",
            totals,
            listOf("health", "shield", "shield_capacity")
        )
        sendAffixGroup(
            player,
            "技能属性",
            totals,
            listOf("energy_max", "ability_strength", "ability_duration", "ability_efficiency", "ability_range")
        )

        val displayedIds = defaultAffixOrder.toSet()
        val extraAffixes = plugin.forgeConfig.affixes.keys.filter { it !in displayedIds }
        if (extraAffixes.isNotEmpty()) {
            sendAffixGroup(player, "其他属性", totals, extraAffixes)
        }

        // 评分
        val totalScore = allItems.sumOf { plugin.itemService.readScore(it) }
        if (totalScore > 0) {
            player.sendMessage("")
            player.sendMessage("§e▎综合")
            player.sendMessage("  §7总评分: §b$totalScore")
        }

        player.sendMessage("§6==========================================")
    }

    private fun sendAffixGroup(player: Player, title: String, totals: Map<String, Double>, ids: List<String>) {
        val affixes = ids.mapNotNull { plugin.forgeConfig.affixes[it] }
        if (affixes.isEmpty()) return
        player.sendMessage("§e▎$title")
        for (affix in affixes) {
            val value = totals[affix.id] ?: 0.0
            player.sendMessage("  §7${affix.displayName}: §f${formatAffixValue(affix.id, value, affix.decimals)}")
        }
    }

    private fun formatAffixValue(id: String, value: Double, decimals: Int): String {
        if (id in percentAffixes) {
            return "${DecimalFormat("0.##").format(value * 100.0)}%"
        }
        if (decimals <= 0) return value.toInt().toString()
        return DecimalFormat("0.${"0".repeat(decimals)}").format(value)
    }

    private companion object {
        val defaultAffixOrder = listOf(
            "base_damage",
            "critical_chance",
            "critical_damage",
            "status_chance",
            "armor",
            "shield",
            "health",
            "shield_capacity",
            "energy_max",
            "ability_strength",
            "ability_duration",
            "ability_efficiency",
            "ability_range"
        )
        val percentAffixes = setOf(
            "critical_chance",
            "critical_damage",
            "status_chance",
            "ability_strength",
            "ability_duration",
            "ability_efficiency",
            "ability_range"
        )
    }
}
