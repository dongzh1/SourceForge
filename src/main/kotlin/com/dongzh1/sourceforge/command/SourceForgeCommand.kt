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
            "mods" -> {
                val player = sender as? Player
                if (player == null) {
                    sender.sendMessage("只有玩家可以打开改造界面")
                    return true
                }
                player.openInventory(com.dongzh1.sourceforge.mod.EquipmentSelectMenu(plugin, player).inventory)
            }
            "upgrademod" -> {
                val player = sender as? Player
                if (player == null) {
                    sender.sendMessage("只有玩家可以打开 MOD 升级界面")
                    return true
                }
                player.openInventory(com.dongzh1.sourceforge.mod.ModUpgradeMenu(plugin, player).inventory)
            }
            // 升级核心改由 CraftEngine 配置 + /ce 指令获取（SF 仅按 CE id "sourceforge:upgrade_core" 识别），故移除 SF 发放指令。
            "giveblankmod" -> {
                if (!requireAdmin(sender)) return true
                val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
                if (target == null) {
                    sender.sendMessage("§e用法: /$label giveblankmod <玩家> [数量]")
                    return true
                }
                val amount = args.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val item = com.dongzh1.sourceforge.item.CraftEngineHook.build("sourceforge:blank_mod", amount)
                    ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER, amount)
                target.inventory.addItem(item).values.forEach { target.world.dropItemNaturally(target.location, it) }
                sender.sendMessage("§a[SourceForge] §f已给予 ${target.name} 空白 MOD x$amount")
            }
            "givemod" -> {
                if (!requireAdmin(sender)) return true
                val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
                val query = args.getOrNull(2)
                if (target == null || query == null) {
                    sender.sendMessage("§e用法: /$label givemod <玩家> <MOD中文名或id> [数量]")
                    return true
                }
                // 支持中文显示名 / 英文id / 唯一部分匹配（方便中文辨认，不必记英文id）
                val modId = plugin.modService.resolveModId(query)
                if (modId == null) {
                    sender.sendMessage("§c[SourceForge] §f未找到 MOD: §e$query §7(按 Tab 可补全中文名/英文id)")
                    return true
                }
                val amount = args.getOrNull(3)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val item = plugin.modService.createModItem(modId, amount)
                if (item == null) {
                    sender.sendMessage("§c[SourceForge] §f无法生成 MOD: $modId")
                    return true
                }
                target.inventory.addItem(item).values.forEach { target.world.dropItemNaturally(target.location, it) }
                sender.sendMessage("§a[SourceForge] §f已给予 ${target.name} §b${plugin.modService.modDisplayName(modId)} §7($modId) §fx$amount")
            }
            "givenightmare" -> {
                if (!requireAdmin(sender)) return true
                val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
                if (target == null) {
                    sender.sendMessage("§e用法: /$label givenightmare <玩家> [武器类别]")
                    return true
                }
                val category = args.getOrNull(2)
                if (category != null && category.lowercase() !in plugin.nightmareService.categories()) {
                    sender.sendMessage("§c[SourceForge] §f未知武器类别: $category，可用: ${plugin.nightmareService.categories().joinToString(", ")}")
                    return true
                }
                val item = plugin.nightmareService.createSealed(category, 1)
                target.inventory.addItem(item).values.forEach { target.world.dropItemNaturally(target.location, it) }
                sender.sendMessage("§a[SourceForge] §f已给予 ${target.name} 梦魇MOD (${category ?: "随机类别"})")
            }
            "reload" -> {
                if (!requireAdmin(sender)) return true
                plugin.reloadRuntime()
                sender.sendMessage("§a[SourceForge] §f配置/脚本已重载")
                sendValidationSummary(sender)
            }
            "validate" -> {
                if (!requireAdmin(sender)) return true
                sendValidationSummary(sender, verbose = true)
            }
            "giveequipment" -> {
                if (!requireAdmin(sender)) return true
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
                if (!requireAdmin(sender)) return true
                // /sf debug forgeinfo：诊断你准星指向的方块（6格内）的 CE 识别情况
                if (args.getOrNull(1)?.equals("forgeinfo", true) == true) {
                    val player = sender as? Player
                    if (player == null) {
                        sender.sendMessage("只有玩家可以使用此命令")
                        return true
                    }
                    val block = player.getTargetBlockExact(6)
                    if (block == null) {
                        player.sendMessage("§c[forgeinfo] §f请把准星对准一个方块（6格内）")
                        return true
                    }
                    val cfg = plugin.structureManager.config
                    val hook = com.dongzh1.sourceforge.item.CraftEngineHook
                    val isCe = hook.isCustomBlock(block)
                    val blockId = hook.blockId(block)
                    val handId = hook.itemId(player.inventory.itemInMainHand)
                    val lines = listOf(
                        "看向方块: ${block.type} @ ${block.x},${block.y},${block.z}",
                        "isCustomBlock(CE): $isCe",
                        "blockId(CE): ${blockId ?: "null(读取失败/非CE方块)"}",
                        "是锻炉方块: ${blockId in cfg.forgeBlockIds}",
                        "enabled=${cfg.enabled} coreId=${cfg.coreBlockId} hammerId=${cfg.hammerId}",
                        "手持物品 CE id: ${handId ?: "null"}"
                    )
                    player.sendMessage("§6==== forgeinfo ====")
                    lines.forEach { player.sendMessage("§7$it") }
                    // 同步写入控制台日志，便于离线排查
                    plugin.logger.info("[forgeinfo] ${player.name}: " + lines.joinToString(" | "))
                    return true
                }
                // /sf debug fulltest [装备ID]：一次性自检（物品属性写入/属性总计/外部Provider/伤害推演/实打）
                if (args.getOrNull(1)?.equals("fulltest", true) == true) {
                    val player = sender as? Player
                    if (player == null) {
                        sender.sendMessage("只有玩家可以使用此命令")
                        return true
                    }
                    runFullTest(player, args.getOrNull(2), args.getOrNull(3))
                    return true
                }
                // /sf debug element <on|off>：开启后命中带元素+触发的装备时，打印触发计算与目标层数
                if (args.getOrNull(1)?.equals("element", true) == true) {
                    val player = sender as? Player
                    if (player == null) {
                        sender.sendMessage("只有玩家可以使用此命令")
                        return true
                    }
                    val on = when (args.getOrNull(2)?.lowercase()) {
                        "on", "true" -> true
                        "off", "false" -> false
                        null, "toggle" -> !plugin.statusManager.isDebug(player.uniqueId)
                        else -> {
                            sender.sendMessage("§e用法: /$label debug element <on|off>")
                            return true
                        }
                    }
                    plugin.statusManager.setDebug(player.uniqueId, on)
                    sender.sendMessage("§a[SourceForge] §f元素触发调试已${if (on) "开启" else "关闭"}")
                    return true
                }
                val target = args.getOrNull(1)?.lowercase()
                val value = args.getOrNull(2)?.lowercase()
                if (target !in setOf("combat", "betterhud") || value !in setOf("on", "off", "true", "false")) {
                    sender.sendMessage("§e用法: /$label debug <combat|betterhud> <on|off>  |  /$label debug forgeinfo")
                    return true
                }
                val enabled = value == "on" || value == "true"
                val path = if (target == "combat") "debug.combat" else "betterhud.debug"
                plugin.config.set(path, enabled)
                plugin.saveConfig()
                plugin.reloadAll()
                val label2 = if (target == "combat") "战斗" else "BetterHud"
                sender.sendMessage("§a[SourceForge] §f${label2}调试已${if (enabled) "开启" else "关闭"}")
            }
            "energy" -> {
                val sub = args.getOrNull(1)?.lowercase()
                when (sub) {
                    "deduct" -> {
                        val target = Bukkit.getPlayerExact(args.getOrNull(2) ?: "")
                        val amount = args.getOrNull(3)?.toDoubleOrNull()
                        if (target == null || amount == null || amount <= 0) {
                            sender.sendMessage("§e用法: /$label energy deduct <玩家> <数量>")
                            return true
                        }
                        val ok = plugin.energyService.deductEnergy(target, amount)
                        if (ok) {
                            sender.sendMessage("§a[SourceForge] §f已扣除 ${target.name} 能量 $amount, 剩余: ${"%.0f".format(plugin.energyService.getEnergyCurrent(target))}")
                        } else {
                            sender.sendMessage("§c[SourceForge] §f${target.name} 能量不足 ($amount), 当前: ${"%.0f".format(plugin.energyService.getEnergyCurrent(target))}")
                        }
                    }
                    "get" -> {
                        val target = args.getOrNull(2)?.let { Bukkit.getPlayerExact(it) } ?: (sender as? Player)
                        if (target == null) {
                            sender.sendMessage("§e用法: /$label energy get [玩家]")
                            return true
                        }
                        val cur = plugin.energyService.getEnergyCurrent(target)
                        val max = plugin.energyService.getEnergyMax(target)
                        sender.sendMessage("§a[SourceForge] §f${target.name} 能量: ${"%.0f".format(cur)}/${"%.0f".format(max)}")
                    }
                    "set" -> {
                        if (!sender.hasPermission("sourceforge.admin")) { sender.sendMessage("§c你没有权限"); return true }
                        val target = Bukkit.getPlayerExact(args.getOrNull(2) ?: "")
                        val amount = args.getOrNull(3)?.toDoubleOrNull()
                        if (target == null || amount == null || amount < 0) {
                            sender.sendMessage("§e用法: /$label energy set <玩家> <数量>")
                            return true
                        }
                        plugin.energyService.setEnergy(target, amount)
                        sender.sendMessage("§a[SourceForge] §f已设置 ${target.name} 能量: ${"%.0f".format(amount)}/${"%.0f".format(plugin.energyService.getEnergyMax(target))}")
                    }
                    else -> sender.sendMessage("§e用法: /$label energy <deduct|get|set> [玩家] [数量]")
                }
            }
            "give" -> {
                if (!requireAdmin(sender)) return true
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
                if (!requireAdmin(sender)) return true
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
                if (!requireAdmin(sender)) return true
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
                plugin.itemService.invalidateStatCache(player)
                sender.sendMessage("§a[SourceForge] §f手持装备已重铸")
            }
            "upgrade" -> {
                if (!requireAdmin(sender)) return true
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
                plugin.itemService.invalidateStatCache(player)
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
            "cd" -> {
                val player = sender as? Player
                if (player == null) {
                    sender.sendMessage("只有玩家可以切换 CD 显示")
                    return true
                }
                val enabled = when (args.getOrNull(1)?.lowercase()) {
                    "on", "true" -> true
                    "off", "false" -> false
                    null, "toggle" -> !plugin.skillListener.isCdDisplayEnabled(player)
                    else -> {
                        sender.sendMessage("§e用法: /$label cd <on|off>")
                        return true
                    }
                }
                plugin.skillListener.setCdDisplay(player, enabled)
                sender.sendMessage("§a[SourceForge] §f技能 CD 显示已${if (enabled) "开启" else "关闭"}")
            }
            "track", "nav", "navigate" -> {
                if (!requireAdmin(sender)) return true
                val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
                if (target == null) {
                    sender.sendMessage("§e用法: /$label track <玩家> <目标名> <x> <y> <z> [世界] [颜色]  |  /$label track <玩家> off")
                    return true
                }
                if (args.getOrNull(2)?.equals("off", true) == true) {
                    val had = plugin.navigationManager.stop(target)
                    sender.sendMessage(if (had) "§a[SourceForge] §f已清空 ${target.name} 的全部追踪目标" else "§e[SourceForge] §f${target.name} 当前没有追踪目标")
                    return true
                }
                val name = args.getOrNull(2)
                val x = args.getOrNull(3)?.toDoubleOrNull()
                val y = args.getOrNull(4)?.toDoubleOrNull()
                val z = args.getOrNull(5)?.toDoubleOrNull()
                if (name == null || x == null || y == null || z == null) {
                    sender.sendMessage("§e用法: /$label track <玩家> <目标名> <x> <y> <z> [世界] [颜色]  |  /$label track <玩家> off")
                    return true
                }
                val world = args.getOrNull(6) ?: target.world.name
                val colorArg = args.getOrNull(7)
                val resolved = com.dongzh1.sourceforge.nav.NavigationManager.resolveColor(colorArg) ?: run {
                    sender.sendMessage("§c[SourceForge] §f未知颜色: $colorArg，可用命名色: ${com.dongzh1.sourceforge.nav.NavigationManager.COLORS.keys.joinToString(", ")}，或直接写 §b#RRGGBB")
                    return true
                }
                plugin.navigationManager.track(target, name, x, y, z, world, resolved.hex, resolved.icon)
                sender.sendMessage("§a[SourceForge] §f已为 §e${target.name} §f追踪 §b$name §7(${x.toInt()}, ${y.toInt()}, ${z.toInt()} @ $world) §f颜色 §b${resolved.label}§f，当前共 ${plugin.navigationManager.targetNames(target).size} 个目标")
            }
            "untrack" -> {
                if (!requireAdmin(sender)) return true
                val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
                val name = args.getOrNull(2)
                if (target == null || name == null) {
                    sender.sendMessage("§e用法: /$label untrack <玩家> <目标名>")
                    return true
                }
                val ok = plugin.navigationManager.untrack(target, name)
                sender.sendMessage(if (ok) "§a[SourceForge] §f已移除 ${target.name} 的追踪目标 §b$name" else "§e[SourceForge] §f${target.name} 没有名为 §b$name §f的追踪目标")
            }
            else -> sender.sendMessage("§e用法: /$label <forge|mods|upgrademod|givemod|givenightmare|giveblankmod|reload|validate|giveequipment|give|testdamage|mmdamage|reroll|upgrade|stats|track|debug>")
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("forge", "mods", "upgrademod", "givemod", "givenightmare", "giveblankmod", "reload", "validate", "giveequipment", "give", "testdamage", "mmdamage", "reroll", "upgrade", "stats", "track", "untrack", "cd", "debug").filter { it.startsWith(args[0], true) }
            2 -> when {
                args[0].equals("giveequipment", true) || args[0].equals("give", true) || args[0].equals("givemod", true) || args[0].equals("givenightmare", true) || args[0].equals("giveblankmod", true) || args[0].equals("testdamage", true) || args[0].equals("mmdamage", true) || args[0].equals("track", true) || args[0].equals("untrack", true) || args[0].equals("nav", true) || args[0].equals("navigate", true) -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) }
                args[0].equals("debug", true) -> listOf("combat", "betterhud", "forgeinfo", "element", "fulltest").filter { it.startsWith(args[1], true) }
                args[0].equals("cd", true) -> listOf("on", "off").filter { it.startsWith(args[1], true) }
                else -> emptyList()
            }
            3 -> when {
                args[0].equals("giveequipment", true) -> plugin.forgeConfig.equipment.keys.filter { it.startsWith(args[2], true) }
                args[0].equals("give", true) -> expressionSuggestions().filter { it.startsWith(args[2], true) }
                args[0].equals("givemod", true) -> plugin.modService.modSuggestions().filter { it.startsWith(args[2], true) }
                args[0].equals("giveblankmod", true) -> listOf("1", "8", "16", "64").filter { it.startsWith(args[2], true) }
                args[0].equals("givenightmare", true) -> plugin.nightmareService.categories().filter { it.startsWith(args[2], true) }
                args[0].equals("debug", true) -> listOf("on", "off").filter { it.startsWith(args[2], true) }
                args[0].equals("track", true) || args[0].equals("nav", true) || args[0].equals("navigate", true) -> listOf("off").filter { it.startsWith(args[2], true) }
                args[0].equals("untrack", true) -> (Bukkit.getPlayerExact(args[1])?.let { plugin.navigationManager.targetNames(it) } ?: emptyList()).filter { it.startsWith(args[2], true) }
                else -> emptyList()
            }
            // track 的坐标/世界/颜色补全：默认补全 args[1] 指定玩家的当前坐标与所在世界。
            4 -> when {
                args[0].equals("givemod", true) -> listOf("1", "5", "10").filter { it.startsWith(args[3], true) }
                isTrackAlias(args[0]) -> trackPlayerLoc(args[1])?.let { listOf(it.blockX.toString()) }?.filter { it.startsWith(args[3], true) } ?: emptyList()
                else -> emptyList()
            }
            5 -> if (isTrackAlias(args[0])) trackPlayerLoc(args[1])?.let { listOf(it.blockY.toString()) }?.filter { it.startsWith(args[4], true) } ?: emptyList() else emptyList()
            6 -> if (isTrackAlias(args[0])) trackPlayerLoc(args[1])?.let { listOf(it.blockZ.toString()) }?.filter { it.startsWith(args[5], true) } ?: emptyList() else emptyList()
            7 -> if (isTrackAlias(args[0])) {
                val worlds = LinkedHashSet<String>()
                Bukkit.getPlayerExact(args[1])?.world?.name?.let { worlds.add(it) }
                Bukkit.getWorlds().forEach { worlds.add(it.name) }
                worlds.filter { it.startsWith(args[6], true) }
            } else emptyList()
            8 -> if (isTrackAlias(args[0])) com.dongzh1.sourceforge.nav.NavigationManager.COLORS.keys.filter { it.startsWith(args[7], true) } else emptyList()
            else -> emptyList()
        }
    }

    /** 管理员权限校验：无权限时提示并返回 false（调用方据此 return true 结束命令）。 */
    private fun requireAdmin(sender: CommandSender): Boolean {
        if (!sender.hasPermission("sourceforge.admin")) {
            sender.sendMessage("§c你没有权限")
            return false
        }
        return true
    }

    private fun isTrackAlias(sub: String): Boolean =
        sub.equals("track", true) || sub.equals("nav", true) || sub.equals("navigate", true)

    private fun trackPlayerLoc(name: String): org.bukkit.Location? = Bukkit.getPlayerExact(name)?.location

    private fun expressionSuggestions(): List<String> {
        return plugin.forgeConfig.equipment.keys.map {
            "sf:equipment:$it?tier=1"
        }
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

    private fun isEquipmentExpression(expression: String): Boolean {
        if (!expression.startsWith("sf:", ignoreCase = true) && !expression.startsWith("sourceforge:", ignoreCase = true)) {
            return false
        }
        val body = expression.substringAfter(":")
        val path = body.substringBefore("?")
        return path.substringBefore(":", "").equals("equipment", ignoreCase = true)
    }

    /**
     * 一次性自检：物品属性写入 / 属性总计(含外部Provider) / 伤害链路推演 / 看向实体实打。
     * 用于快速定位“词条在但属性没加成 / 外部临时属性失效”等回归。
     */
    private fun runFullTest(player: Player, equipmentArg: String?, skillArg: String?) {
        val svc = plugin.itemService
        val ns = plugin.name.lowercase()
        var pass = 0
        var fail = 0
        fun check(ok: Boolean, label: String, detail: String) {
            if (ok) pass++ else fail++
            player.sendMessage("  ${if (ok) "§a✔" else "§c✘"} §7$label: §f$detail")
        }
        fun sfModSum(meta: org.bukkit.inventory.meta.ItemMeta?, attr: org.bukkit.attribute.Attribute): Double =
            meta?.getAttributeModifiers(attr)?.filter { it.key.namespace == ns }?.sumOf { it.amount } ?: 0.0

        // 临时注入一套已知测试属性（仅本次、仅本玩家；finally 必清，绝不泄漏）
        val testStats = mapOf(
            "base_damage" to 10.0, "critical_chance" to 1.0, "critical_damage" to 2.0,
            "ability_strength" to 2.0, "ability_efficiency" to 0.9, "ability_range" to 2.0,
            "ability_duration" to 2.0, "armor" to 20.0, "health" to 20.0,
            "shield_capacity" to 20.0, "energy_max" to 50.0
        )
        val testProvider = com.dongzh1.sourceforge.item.ExternalAffixProvider { p ->
            if (p.uniqueId == player.uniqueId) testStats else emptyMap()
        }
        svc.registerExternalAffixProvider(testProvider)
        svc.invalidateStatCache(player)
        try {
        player.sendMessage("§6===== SourceForge 自检 fulltest =====")
        player.sendMessage("§7已注入临时测试属性: base_damage+10 暴击+100% 暴伤+200% 强度+200% 效率0.9 范围/持续+200% 护甲/生命/护盾+20 能量+50")

        // A. 物品 & 属性写入
        player.sendMessage("§e▎A. 物品 & 属性写入")
        val weapon = player.inventory.itemInMainHand
        if (!svc.isSourceEquipment(weapon)) {
            val id = equipmentArg ?: "netherite_sword"
            if (id !in plugin.forgeConfig.equipment) {
                player.sendMessage("  §c主手非 SF 装备，且装备ID 不存在: §f$id §7(用法: /sf debug fulltest [装备ID])")
                return
            }
            val item = svc.createDirectEquipment(id, 1, null)
            if (item == null) {
                player.sendMessage("  §c生成测试装备失败: $id")
                return
            }
            player.inventory.addItem(item).values.forEach { player.world.dropItemNaturally(player.location, it) }
            player.sendMessage("  §7主手非 SF 装备，已发放 §f$id §7到背包。请手持后重跑 §f/sf debug fulltest§7。")
            return
        }
        val baseAffix = svc.readBaseDamage(weapon)
        val atkMod = sfModSum(weapon.itemMeta, org.bukkit.attribute.Attribute.ATTACK_DAMAGE)
        check(
            kotlin.math.abs(atkMod - baseAffix) < 1e-4,
            "武器 ATTACK_DAMAGE 修饰符",
            "词条base_damage=${"%.2f".format(baseAffix)} → 物品修饰符=${"%.2f".format(atkMod)}"
        )
        for ((slot, armorItem) in listOf(
            "头盔" to player.inventory.helmet,
            "胸甲" to player.inventory.chestplate,
            "护腿" to player.inventory.leggings,
            "靴子" to player.inventory.boots
        )) {
            if (!svc.isSourceEquipment(armorItem)) continue
            val am = armorItem?.itemMeta
            val armorAffix = svc.readAffixValue(armorItem, "armor")
            val healthAffix = svc.readAffixValue(armorItem, "health") + svc.readAffixValue(armorItem, "shield_capacity")
            if (armorAffix > 0.0) check(
                kotlin.math.abs(sfModSum(am, org.bukkit.attribute.Attribute.ARMOR) - armorAffix) < 1e-4,
                "$slot ARMOR", "词条=${"%.2f".format(armorAffix)} 修饰符=${"%.2f".format(sfModSum(am, org.bukkit.attribute.Attribute.ARMOR))}"
            )
            if (healthAffix > 0.0) check(
                kotlin.math.abs(sfModSum(am, org.bukkit.attribute.Attribute.MAX_HEALTH) - healthAffix) < 1e-4,
                "$slot MAX_HEALTH", "词条=${"%.2f".format(healthAffix)} 修饰符=${"%.2f".format(sfModSum(am, org.bukkit.attribute.Attribute.MAX_HEALTH))}"
            )
        }

        // B. 属性总计 & 外部 Provider
        player.sendMessage("§e▎B. 属性总计 (readTotalAffix，含外部Provider)")
        val ext = svc.externalProviderTotals(player)
        player.sendMessage("  §7外部 Provider 数: §f${svc.externalProviderCount()}§7  贡献: §f${if (ext.isEmpty()) "无" else ext.entries.joinToString(", ") { "${it.key}+${"%.2f".format(it.value)}" }}")
        if (svc.externalProviderCount() > 0 && ext.isEmpty()) {
            player.sendMessage("  §e提示: 已注册 Provider 但当前贡献 0（副本外正常；副本内应有值则为联动异常）")
        }
        for (affix in plugin.forgeConfig.affixes.values) {
            val total = svc.readTotalAffix(player, affix.id)
            if (total != 0.0) player.sendMessage("  §7${affix.displayName}(${affix.id}): §f${"%.3f".format(total)}")
        }

        // C. 伤害链路推演
        player.sendMessage("§e▎C. 伤害推演 (incoming = 武器ATTACK_DAMAGE属性)")
        val incoming = if (atkMod > 0.0) atkMod else baseAffix
        val critChance = svc.readTotalAffix(player, "critical_chance")
        val critBonus = svc.readTotalAffix(player, "critical_damage")
        val critMult = 1.0 + (if (critBonus > 0.0) critBonus else 0.5)
        var elemSum = 0.0
        val ec = plugin.elementConfig
        if (ec.enabled) for (def in ec.active) {
            if (def.type.isBase) {
                val v = svc.readTotalAffix(player, def.affix)
                if (v > 0.0) elemSum += v
            }
        }
        val elemDirect = if (ec.enabled) elemSum * ec.directDamageFactor else 0.0
        val noCrit = incoming + elemDirect
        val forcedCrit = incoming * critMult + elemDirect
        val avgEv = incoming * (1.0 + critChance * (critMult - 1.0)) + elemDirect
        player.sendMessage("  §7① 基础 incoming: §f${"%.2f".format(incoming)}")
        player.sendMessage("  §7② 暴击: 率§f${"%.1f".format(critChance * 100)}%§7 倍率§f×${"%.2f".format(critMult)}")
        player.sendMessage("  §7③ 元素直伤: 和§f${"%.2f".format(elemSum)}§7 ×系数§f${"%.2f".format(if (ec.enabled) ec.directDamageFactor else 0.0)}§7 = §f${"%.2f".format(elemDirect)}")
        player.sendMessage("  §7④ 小计: 不暴§f${"%.2f".format(noCrit)}§7 / 必暴§f${"%.2f".format(forcedCrit)}§7 / 期望§f${"%.2f".format(avgEv)}")
        val floor = plugin.forgeConfig.combat.defenseFloor
        for (armor in listOf(0.0, 10.0, 50.0)) {
            val ap = maxOf(1.0, noCrit)
            val mult = maxOf(floor, ap / (ap + armor))
            val defended = maxOf(1.0, noCrit * mult)
            player.sendMessage("  §7⑤ 对护甲§f${"%.0f".format(armor)}§7: 减伤系数§f${"%.3f".format(mult)}§7 → 最终§c${"%.2f".format(defended)}")
        }
        check(floor in 0.0..1.0, "护甲下限 defense-floor", "${"%.2f".format(floor)} (combat.yml)")

        // D. 实打验证
        player.sendMessage("§e▎D. 实打验证")
        val target = player.getTargetEntity(5) as? org.bukkit.entity.LivingEntity
        if (target == null) {
            player.sendMessage("  §7skipped (没有看向 5 格内的实体)")
        } else {
            val before = target.health
            target.damage(incoming, player)
            plugin.server.scheduler.runTask(plugin, Runnable {
                val after = target.health
                player.sendMessage("  §7对 §f${target.type}§7 实际掉血: §c${"%.2f".format(before - after)} §8(${"%.1f".format(before)}→${"%.1f".format(after)}, 推演不暴≈${"%.2f".format(noCrit)})")
            })
        }

        // F. MM 联动 (CD / 伤害影响) — 临时测试属性已注入，期望值确定
        player.sendMessage("§e▎F. MM 联动 (CD/伤害影响)")
        val effTotal = svc.readTotalAffix(player, "ability_efficiency")
        val strTotal = svc.readTotalAffix(player, "ability_strength")
        val totalBase = svc.readTotalAffix(player, "base_damage")
        val expCdMult = (1.0 - effTotal).coerceAtLeast(0.0)
        val expStrMult = 1.0 + strTotal
        val papiCd = resolvePapi(player, "%sourceforge_cooldown_multiplier%")?.toDoubleOrNull()
        val papiStr = resolvePapi(player, "%sourceforge_strength_multiplier%")?.toDoubleOrNull()
        if (papiCd == null && papiStr == null) {
            player.sendMessage("  §7PAPI 未装/不可用，仅按公式核对(装 PlaceholderAPI 可实测 %sourceforge_% 占位符)")
        }
        check(
            papiCd == null || kotlin.math.abs(papiCd - expCdMult) < 1e-3,
            "CD倍率 %sourceforge_cooldown_multiplier%",
            "效率${"%.2f".format(effTotal)} 公式期望${"%.3f".format(expCdMult)}" + (papiCd?.let { " / PAPI${"%.3f".format(it)}" } ?: "")
        )
        player.sendMessage("  §7CD示例: 基础5s × ${"%.3f".format(expCdMult)} = §f${"%.2f".format(5.0 * expCdMult)}s")
        check(
            papiStr == null || kotlin.math.abs(papiStr - expStrMult) < 1e-3,
            "强度倍率 %sourceforge_strength_multiplier%",
            "强度${"%.2f".format(strTotal)} 公式期望${"%.3f".format(expStrMult)}" + (papiStr?.let { " / PAPI${"%.3f".format(it)}" } ?: "")
        )
        val expSkillDmg = totalBase * expStrMult
        player.sendMessage("  §7技能伤害期望: 总基础${"%.2f".format(totalBase)} × 强度倍率${"%.2f".format(expStrMult)} = §c${"%.2f".format(expSkillDmg)}")
        val skill = (skillArg ?: "SF_DEBUG_STRIKE")
        val mmHook = com.dongzh1.sourceforge.enchant.MythicMobsHook()
        if (!mmHook.isAvailable()) {
            player.sendMessage("  §7MythicMobs 未启用，跳过实释放")
        } else {
            val tgt = player.getTargetEntity(5) as? org.bukkit.entity.LivingEntity
            val hpBefore = tgt?.health
            val res = mmHook.castSkill(player, skill)
            check(
                res == com.dongzh1.sourceforge.enchant.MythicMobsHook.CastResult.SUCCESS,
                "实释放 MM 技能 §f$skill",
                res.name
            )
            if (tgt != null && hpBefore != null) {
                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    val d = hpBefore - tgt.health
                    player.sendMessage("  §7§f$skill§7 → §f${tgt.type}§7 掉血 §c${"%.2f".format(d)} §8(期望≈${"%.2f".format(expSkillDmg)}, 经SF防御后可能更低)")
                }, 2L)
            } else {
                player.sendMessage("  §7已释放(没看向5格内实体，无目标对照)")
            }
        }
        } finally {
            svc.unregisterExternalAffixProvider(testProvider)
            svc.invalidateStatCache(player)
        }

        // E. 汇总
        player.sendMessage("§e▎E. 汇总: ${if (fail == 0) "§a全部通过" else "§c$fail 项失败"} §7(PASS=$pass FAIL=$fail)")
        player.sendMessage("§6======================================")
    }

    /** 反射调用 PlaceholderAPI 解析占位符；未装/未解析返回 null。 */
    private fun resolvePapi(player: Player, placeholder: String): String? = runCatching {
        val clazz = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
        val m = clazz.getMethod("setPlaceholders", org.bukkit.OfflinePlayer::class.java, String::class.java)
        val out = m.invoke(null, player, placeholder) as? String
        if (out == null || out == placeholder || out.contains('%')) null else out
    }.getOrNull()

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
            listOf("health", "shield_capacity")
        )
        sendAffixGroup(
            player,
            "技能属性",
            totals,
            listOf("energy_max", "ability_strength", "ability_duration", "ability_efficiency", "ability_range")
        )
        sendAffixGroup(
            player,
            "元素属性",
            totals,
            listOf("heat_damage", "cold_damage", "toxin_damage", "electric_damage")
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
            "health",
            "shield_capacity",
            "energy_max",
            "ability_strength",
            "ability_duration",
            "ability_efficiency",
            "ability_range",
            "heat_damage",
            "cold_damage",
            "toxin_damage",
            "electric_damage"
        )
        val percentAffixes = setOf(
            "critical_chance",
            "critical_damage",
            "status_chance",
            "ability_strength",
            "ability_duration",
            "ability_efficiency"
        )
    }
}
