package com.dongzh1.sourceforge.command

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.forge.ForgeMenus
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.text.DecimalFormat

/**
 * `/sourceforge` 命令入口。每个子命令拆成一个独立的 cmdXxx 私有方法（评审：onCommand 曾是
 * 865 行、20+ 分支全堆在一个 when 块里的 god-method），onCommand 本身只做子命令名分发。
 */
class SourceForgeCommand(
    private val plugin: SourceForge
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.getOrNull(0)?.lowercase()) {
            null, "forge" -> cmdForge(sender)
            "mods" -> cmdMods(sender)
            // MOD 段位升级已并入锻炉"强化"(2026-07-17，见 ForgeEnhanceMenu)，不再有独立的 upgrademod 命令。
            // 升级核心改由 CraftEngine 配置 + /ce 指令获取（SF 仅按 CE id "sourceforge:upgrade_core" 识别），故移除 SF 发放指令。
            "giveblankmod" -> cmdGiveBlankMod(sender, label, args)
            "givemod" -> cmdGiveMod(sender, label, args)
            "givenightmare", "givedreammark", "giveriven" -> cmdGiveDreamMark(sender, label, args)
            "givedreamcore", "givekuva" -> cmdGiveDreamCore(sender, label, args)
            "dreammark", "riven" -> cmdDreamMark(sender, label, args)
            "reload" -> cmdReload(sender)
            "validate" -> cmdValidate(sender)
            "giveequipment" -> cmdGiveEquipment(sender, label, args)
            "giverelic" -> cmdGiveRelic(sender, label, args)
            "debug" -> cmdDebug(sender, label, args)
            "energy" -> cmdEnergy(sender, label, args)
            "give" -> cmdGive(sender, label, args)
            "testdamage", "mmdamage" -> cmdTestDamage(sender, label, args)
            "reroll" -> cmdReroll(sender)
            "upgrade" -> cmdUpgrade(sender)
            "stats" -> cmdStats(sender)
            "cd" -> cmdCd(sender, label, args)
            "track", "nav", "navigate" -> cmdTrack(sender, label, args)
            "untrack" -> cmdUntrack(sender, label, args)
            "sect", "cult", "faction" -> cmdSect(sender, label, args)
            else -> sender.sendMessage("§e用法: /$label <forge|mods|givemod|givedreammark|dreammark|givedreamcore|giveblankmod|giverelic|reload|validate|giveequipment|give|testdamage|mmdamage|reroll|upgrade|stats|track|sect|debug>")
        }
        return true
    }

    // ==================== 子命令 ====================

    private fun cmdForge(sender: CommandSender) {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("只有玩家可以打开锻造界面")
            return
        }
        ForgeMenus.open(plugin, player, null)
    }

    private fun cmdSect(sender: CommandSender, label: String, args: Array<out String>) {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("只有玩家可以使用教派功能")
            return
        }
        when (args.getOrNull(1)?.lowercase()) {
            "npc" -> cmdSectNpc(sender)
            null, "list" -> {
                sender.sendMessage("§6========== 教派 ==========")
                plugin.sectService.sects().forEach { sect ->
                    sender.sendMessage("§e${sect.id} §f${sect.displayName} §7- ${sect.description.firstOrNull() ?: ""}")
                }
                sender.sendMessage("§7当前教派: §f${plugin.sectService.selected(player)?.displayName ?: "未选择"}")
                sender.sendMessage("§7用法: §f/$label sect join <id> §7| §f/$label sect prepare §7| §f/$label sect status")
            }
            "join", "select" -> {
                val id = args.getOrNull(2)
                val sect = id?.let { plugin.sectService.select(player, it) }
                if (sect == null) {
                    sender.sendMessage("§c未知教派。可用: ${plugin.sectService.sects().joinToString(", ") { it.id }}")
                } else {
                    sender.sendMessage("§a[教派] §f已选择 §e${sect.displayName}§f。出行前使用 §e/$label sect prepare §f领取奖励。")
                }
            }
            "buy", "purchase" -> {
                val result = plugin.sectService.purchaseReward(player, args.getOrNull(2))
                when (result.status) {
                    com.dongzh1.sourceforge.sect.SectPurchaseStatus.SUCCESS ->
                        sender.sendMessage("§a[教派] §f已购买 §e${result.reward?.id ?: "蓝图"}§f，花费 §e${result.price.toInt()} §f金币。")
                    com.dongzh1.sourceforge.sect.SectPurchaseStatus.NO_SECT ->
                        sender.sendMessage("§e[教派] §f请先加入并选择一个教派。")
                    com.dongzh1.sourceforge.sect.SectPurchaseStatus.UNKNOWN_REWARD ->
                        sender.sendMessage("§c[教派] §f当前教派没有这件商品。")
                    com.dongzh1.sourceforge.sect.SectPurchaseStatus.NOT_FOR_SALE ->
                        sender.sendMessage("§e[教派] §f这件物品不对外出售。")
                    com.dongzh1.sourceforge.sect.SectPurchaseStatus.ECONOMY_UNAVAILABLE ->
                        sender.sendMessage("§c[教派] §f购买需要 Vault 经济系统支持。")
                    com.dongzh1.sourceforge.sect.SectPurchaseStatus.INSUFFICIENT_FUNDS ->
                        sender.sendMessage("§c[教派] §f金币不足，需要 §e${result.price.toInt()} §f金币。")
                    com.dongzh1.sourceforge.sect.SectPurchaseStatus.REWARD_BUILD_FAILED ->
                        sender.sendMessage("§c[教派] §f商品生成失败，请联系管理员。")
                }
            }
            "prepare", "departure" -> {
                val result = plugin.sectService.prepareJourney(player)
                when (result.status) {
                    com.dongzh1.sourceforge.sect.JourneyPreparationStatus.SUCCESS ->
                        sender.sendMessage("§a[教派] §f已完成出行准备，获得 §e${result.grantedRewards} §f件教派奖励。")
                    com.dongzh1.sourceforge.sect.JourneyPreparationStatus.NO_SECT ->
                        sender.sendMessage("§e[教派] §f请先选择教派: §e/$label sect join golden")
                    com.dongzh1.sourceforge.sect.JourneyPreparationStatus.ALREADY_PREPARED ->
                        sender.sendMessage("§e[教派] §f本次出行已经准备过了。")
                    com.dongzh1.sourceforge.sect.JourneyPreparationStatus.NO_REWARDS ->
                        sender.sendMessage("§a[教派] §f该教派本次没有配置奖励，已标记为准备完成。")
                    com.dongzh1.sourceforge.sect.JourneyPreparationStatus.REWARD_BUILD_FAILED ->
                        sender.sendMessage("§c[教派] §f奖励构建失败，请检查 sects/<教派id>.yml 配置。")
                }
            }
            "finish", "end" -> sender.sendMessage(
                if (plugin.sectService.finishJourney(player)) "§a[教派] §f已结束本次出行。"
                else "§e[教派] §f当前没有进行中的出行。"
            )
            "status" -> {
                val sect = plugin.sectService.selected(player)
                sender.sendMessage("§6[教派] §f当前: §e${sect?.displayName ?: "未选择"}")
                sender.sendMessage("§7出行准备: §f${if (plugin.sectService.isJourneyPrepared(player)) "已完成" else "未完成"}")
                sender.sendMessage("§7黄金酒: §f${plugin.sectService.goldenWineRemainingSeconds(player)} 秒")
            }
            "leave" -> {
                plugin.sectService.clearSelection(player)
                sender.sendMessage("§a[教派] §f已离开当前教派。")
            }
            else -> sender.sendMessage("§e用法: /$label sect <list|join|prepare|finish|status|leave> [教派id]")
        }
    }

    private fun cmdSectNpc(sender: CommandSender) {
        sender.sendMessage("§7[教派] NPC 对话由 SourceTasks 驱动；请按教派文件中的 npc.id 使用 /snpc 创建或配置 NPC。")
    }

    private fun cmdMods(sender: CommandSender) {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("只有玩家可以打开改造界面")
            return
        }
        player.openInventory(com.dongzh1.sourceforge.mod.EquipmentSelectMenu(plugin, player).inventory)
    }

    private fun cmdGiveBlankMod(sender: CommandSender, label: String, args: Array<out String>) {
        if (!requireAdmin(sender)) return
        val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
        if (target == null) {
            sender.sendMessage("§e用法: /$label giveblankmod <玩家> [数量]")
            return
        }
        val amount = args.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val item = com.dongzh1.sourceforge.item.CraftEngineHook.build("sourceforge:blank_mod", amount)
            ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER, amount)
        target.inventory.addItem(item).values.forEach { target.world.dropItemNaturally(target.location, it) }
        sender.sendMessage("§a[SourceForge] §f已给予 ${target.name} 空白 MOD x$amount")
    }

    private fun cmdGiveMod(sender: CommandSender, label: String, args: Array<out String>) {
        if (!requireAdmin(sender)) return
        val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
        val query = args.getOrNull(2)
        if (target == null || query == null) {
            sender.sendMessage("§e用法: /$label givemod <玩家> <MOD中文名或id> [数量]")
            return
        }
        // 支持中文显示名 / 英文id / 唯一部分匹配（方便中文辨认，不必记英文id）
        val modId = plugin.modService.resolveModId(query)
        if (modId == null) {
            sender.sendMessage("§c[SourceForge] §f未找到 MOD: §e$query §7(按 Tab 可补全中文名/英文id)")
            return
        }
        val amount = args.getOrNull(3)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val item = plugin.modService.createModItem(modId, amount)
        if (item == null) {
            sender.sendMessage("§c[SourceForge] §f无法生成 MOD: $modId")
            return
        }
        target.inventory.addItem(item).values.forEach { target.world.dropItemNaturally(target.location, it) }
        sender.sendMessage("§a[SourceForge] §f已给予 ${target.name} §b${plugin.modService.modDisplayName(modId)} §7($modId) §fx$amount")
    }

    private fun cmdGiveNightmare(sender: CommandSender, label: String, args: Array<out String>) {
        if (!requireAdmin(sender)) return
        val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
        if (target == null) {
            sender.sendMessage("§e用法: /$label givenightmare <玩家> [武器类别]")
            return
        }
        val category = args.getOrNull(2)
        if (category != null && category.lowercase() !in plugin.nightmareService.categories()) {
            sender.sendMessage("§c[SourceForge] §f未知武器类别: $category，可用: ${plugin.nightmareService.categories().joinToString(", ")}")
            return
        }
        val item = plugin.nightmareService.createSealed(category, 1)
        target.inventory.addItem(item).values.forEach { target.world.dropItemNaturally(target.location, it) }
        sender.sendMessage("§a[SourceForge] §f已给予 ${target.name} 梦魇MOD (${category ?: "随机类别"})")
    }

    private fun cmdGiveDreamMark(sender: CommandSender, label: String, args: Array<out String>) {
        if (!requireAdmin(sender)) return
        val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
        if (target == null) {
            sender.sendMessage("§e用法: /$label givedreammark <玩家> [遗物ID|近战|远程]")
            return
        }
        val selector = args.getOrNull(2)
        val definition = selector?.let { plugin.dreammarkRelics.get(it) }
        if (selector != null && definition == null && !plugin.rivenService.resolvesGroup(selector)) {
            val options = (plugin.dreammarkRelics.ids() + plugin.rivenService.groupIds()).joinToString(", ")
            sender.sendMessage("§c[SourceForge] §f未知彼端遗纹类型: $selector，可用: $options")
            return
        }
        val item = definition?.let { plugin.rivenService.createVeiled(it) }
            ?: plugin.rivenService.createVeiled(selector)
        if (item == null) {
            sender.sendMessage("§c[SourceForge] §f无法生成彼端遗纹，请检查 dreammark.yml 与 dreammark-relics")
            return
        }
        target.inventory.addItem(item).values.forEach { target.world.dropItemNaturally(target.location, it) }
        sender.sendMessage("§a[SourceForge] §f已给予 ${target.name} 封缄彼端遗纹 (${selector ?: "随机类型"})")
    }

    private fun cmdGiveDreamCore(sender: CommandSender, label: String, args: Array<out String>) {
        if (!requireAdmin(sender)) return
        val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
        if (target == null) {
            sender.sendMessage("§e用法: /$label givedreamcore <玩家> [数量]")
            return
        }
        val amount = args.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        if (!com.dongzh1.sourceforge.item.CraftEngineHook.giveItem(target, "sourceforge:dream_core", amount)) {
            sender.sendMessage("§c[SourceForge] §f无法生成梦髓晶核，请检查 CraftEngine 物品配置")
            return
        }
        sender.sendMessage("§a[SourceForge] §f已给予 ${target.name} 梦髓晶核 x$amount")
    }

    private fun cmdDreamMark(sender: CommandSender, label: String, args: Array<out String>) {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("只有玩家可以操作彼端遗纹")
            return
        }
        val item = player.inventory.itemInMainHand
        when (args.getOrNull(1)?.lowercase()) {
            "weave", "roll" -> {
                val cost = plugin.rivenService.rerollCost(item)
                when (plugin.rivenService.reroll(player, item)) {
                    com.dongzh1.sourceforge.mod.RivenRollResult.SUCCESS -> sender.sendMessage("§5[彼端遗纹] §f已消耗 §d$cost 梦髓晶核§f，使用 /$label dreammark accept 或 /$label dreammark keep 选择结果")
                    com.dongzh1.sourceforge.mod.RivenRollResult.INVALID_RIVEN -> sender.sendMessage("§c[彼端遗纹] §f请主手持有已苏醒的彼端遗纹")
                    com.dongzh1.sourceforge.mod.RivenRollResult.SEALED -> sender.sendMessage("§c[彼端遗纹] §f封缄彼端遗纹不能梦织")
                    com.dongzh1.sourceforge.mod.RivenRollResult.PENDING_SELECTION -> sender.sendMessage("§e[彼端遗纹] §f请先接受候选结果或保留当前遗纹词条")
                    com.dongzh1.sourceforge.mod.RivenRollResult.INSUFFICIENT_DREAM_CORE -> sender.sendMessage("§c[彼端遗纹] §f梦髓晶核不足，本次梦织需要 §d${cost ?: 0}")
                }
            }
            "accept" -> {
                if (plugin.rivenService.acceptPending(item)) sender.sendMessage("§a[彼端遗纹] §f已接受新的遗纹词条")
                else sender.sendMessage("§e[彼端遗纹] §f主手彼端遗纹没有待确认的候选词条")
            }
            "keep" -> {
                if (plugin.rivenService.keepCurrent(item)) sender.sendMessage("§a[彼端遗纹] §f已保留当前遗纹词条")
                else sender.sendMessage("§e[彼端遗纹] §f主手彼端遗纹没有待确认的候选词条")
            }
            else -> sender.sendMessage("§e用法: /$label dreammark <weave|accept|keep> §7(主手持有彼端遗纹)")
        }
    }

    private fun cmdReload(sender: CommandSender) {
        if (!requireAdmin(sender)) return
        plugin.reloadRuntime()
        sender.sendMessage("§a[SourceForge] §f配置/脚本已重载")
        sendValidationSummary(sender)
    }

    private fun cmdValidate(sender: CommandSender) {
        if (!requireAdmin(sender)) return
        sendValidationSummary(sender, verbose = true)
    }

    private fun cmdGiveEquipment(sender: CommandSender, label: String, args: Array<out String>) {
        if (!requireAdmin(sender)) return
        val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
        val equipmentId = args.getOrNull(2)
        val tier = args.getOrNull(3)?.toIntOrNull() ?: 1
        val amount = args.getOrNull(4)?.toIntOrNull() ?: 1
        val affixes = args.getOrNull(5)?.toIntOrNull()
        if (target == null || equipmentId == null) {
            sender.sendMessage("§e用法: /$label giveequipment <玩家> <装备ID> [等级] [数量] [词条数]")
            return
        }
        if (equipmentId !in plugin.forgeConfig.equipment) {
            sender.sendMessage("§c[SourceForge] §f未知装备: $equipmentId")
            return
        }
        var generated = 0
        repeat(amount.coerceAtLeast(1)) {
            val item = plugin.itemService.createDirectEquipment(equipmentId, tier, affixes) ?: return@repeat
            target.inventory.addItem(item).values.forEach { target.world.dropItemNaturally(target.location, it) }
            generated++
        }
        sender.sendMessage("§a[SourceForge] §f已给予 ${target.name} 装备 $equipmentId 等级 $tier x$generated 词条数 ${affixes?.toString() ?: "默认"}")
    }

    private fun cmdGiveRelic(sender: CommandSender, label: String, args: Array<out String>) {
        if (!requireAdmin(sender)) return
        val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
        val relicId = args.getOrNull(2)
        if (target == null || relicId == null) {
            sender.sendMessage("§e用法: /$label giverelic <玩家> <遗物ID> [数量]")
            return
        }
        if (relicId !in plugin.relicService.relicIds()) {
            sender.sendMessage("§c[SourceForge] §f未知遗物: $relicId")
            return
        }
        if (relicId.equals("sourceforge:relic_dreammark", true)) {
            sender.sendMessage("§e[SourceForge] §f梦潮纹理·遗物当前暂不可发放")
            return
        }
        val amount = args.getOrNull(3)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val item = com.dongzh1.sourceforge.item.CraftEngineHook.build(relicId, amount)
        if (item == null) {
            sender.sendMessage("§c[SourceForge] §f无法生成遗物: $relicId")
            return
        }
        target.inventory.addItem(item).values.forEach { target.world.dropItemNaturally(target.location, it) }
        sender.sendMessage("§a[SourceForge] §f已给予 ${target.name} 遗物 $relicId x$amount")
    }

    private fun cmdDebug(sender: CommandSender, label: String, args: Array<out String>) {
        if (!requireAdmin(sender)) return
        when (args.getOrNull(1)?.lowercase()) {
            "forgeinfo" -> cmdDebugForgeInfo(sender)
            "pdc" -> cmdDebugPdc(sender)
            "fulltest" -> cmdDebugFullTest(sender, args)
            "element" -> cmdDebugElement(sender, label, args)
            "combat" -> cmdDebugCombat(sender, label, args)
            "enhancement" -> cmdDebugEnhancement(sender, label, args)
            else -> cmdDebugToggle(sender, label, args)
        }
    }

    /** /sf debug forgeinfo：诊断你准星指向的方块（6格内）的 CE 识别情况 */
    private fun cmdDebugForgeInfo(sender: CommandSender) {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("只有玩家可以使用此命令")
            return
        }
        val block = player.getTargetBlockExact(6)
        if (block == null) {
            player.sendMessage("§c[forgeinfo] §f请把准星对准一个方块（6格内）")
            return
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
    }

    /** /sf debug pdc：输出主手物品全部 PDC 键值(含实际类型)，用来确定 CraftEngine `pdc:` 字段
     * 最终落地到 Bukkit PersistentDataContainer 时到底是什么 key/类型，而不是靠猜。 */
    private fun cmdDebugPdc(sender: CommandSender) {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("只有玩家可以使用此命令")
            return
        }
        val item = player.inventory.itemInMainHand
        if (item.type == org.bukkit.Material.AIR || !item.hasItemMeta()) {
            player.sendMessage("§c[pdc] §f主手没有物品")
            return
        }
        val pdc = item.itemMeta.persistentDataContainer
        val keys = pdc.keys
        val ceId = com.dongzh1.sourceforge.item.CraftEngineHook.itemId(item)
        player.sendMessage("§6==== pdc (${item.type} / CE id=${ceId ?: "null"}) ====")
        if (keys.isEmpty()) {
            player.sendMessage("§e(空，没有任何 PDC key)")
        } else {
            keys.sortedBy { it.toString() }.forEach { key ->
                val desc = describePdcValue(pdc, key)
                player.sendMessage("§7$key §f= $desc")
                plugin.logger.info("[debugpdc] ${player.name}: $key = $desc")
            }
        }
    }

    /** /sf debug fulltest [装备ID]：一次性自检（物品属性写入/属性总计/外部Provider/伤害推演/实打） */
    private fun cmdDebugFullTest(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("只有玩家可以使用此命令")
            return
        }
        runFullTest(player, args.getOrNull(2), args.getOrNull(3))
    }

    /** /sf debug element <on|off>：开启后命中带元素+触发的装备时，打印触发计算与目标层数 */
    private fun cmdDebugElement(sender: CommandSender, label: String, args: Array<out String>) {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("只有玩家可以使用此命令")
            return
        }
        val on = when (args.getOrNull(2)?.lowercase()) {
            "on", "true" -> true
            "off", "false" -> false
            null, "toggle" -> !plugin.statusManager.isDebug(player.uniqueId)
            else -> {
                sender.sendMessage("§e用法: /$label debug element <on|off>")
                return
            }
        }
        plugin.statusManager.setDebug(player.uniqueId, on)
        sender.sendMessage("§a[SourceForge] §f元素触发调试已${if (on) "开启" else "关闭"}")
    }

    private fun cmdDebugCombat(sender: CommandSender, label: String, args: Array<out String>) {
        val targetArg = args.getOrNull(2)?.lowercase()
        if (targetArg in setOf("on", "off", "true", "false")) {
            cmdDebugToggle(sender, label, args)
            return
        }
        val target = Bukkit.getPlayerExact(args.getOrNull(2) ?: "")
        val viewer = sender as? Player
        if (target == null || viewer == null) {
            sender.sendMessage("§e用法: /$label debug combat <on|off> 或 /$label debug combat <玩家> <on|off>")
            return
        }
        val value = args.getOrNull(3)?.lowercase() ?: "toggle"
        if (value !in setOf("on", "off", "true", "false", "toggle")) {
            sender.sendMessage("§e用法: /$label debug combat <玩家> <on|off>")
            return
        }
        val enabled = if (value == "toggle") !plugin.combatDebug.isWatching(viewer.uniqueId, target.uniqueId)
        else value == "on" || value == "true"
        plugin.combatDebug.setWatcher(viewer.uniqueId, target.uniqueId, enabled)
        sender.sendMessage("§a[SourceForge] §f${target.name} 的战斗调试已${if (enabled) "开启" else "关闭"}，日志将发送给你")
    }

    private fun cmdDebugEnhancement(sender: CommandSender, label: String, args: Array<out String>) {
        val operation = args.getOrNull(2)?.lowercase()
        val targetLevel = when (operation) {
            "reset5", "downgrade5" -> 5
            "reset", "downgrade" -> args.getOrNull(3)?.toIntOrNull() ?: 5
            else -> {
                sender.sendMessage("§e用法: /$label debug enhancement reset5  或  /$label debug enhancement downgrade <等级>")
                return
            }
        }
        if (targetLevel !in 0..15) {
            sender.sendMessage("§c[SourceForge] §f目标等级必须在 0 到 15 之间")
            return
        }
        val changed = plugin.downgradeOnlineEnhancements(targetLevel)
        sender.sendMessage("§a[SourceForge] §f已检查在线玩家完整库存，将高于 Lv.$targetLevel 的装备降至 Lv.$targetLevel，重算 $changed 件装备")
    }

    /** /sf debug <combat|betterhud> <on|off> */
    private fun cmdDebugToggle(sender: CommandSender, label: String, args: Array<out String>) {
        val target = args.getOrNull(1)?.lowercase()
        val value = args.getOrNull(2)?.lowercase()
        if (target !in setOf("combat", "betterhud") || value !in setOf("on", "off", "true", "false")) {
            sender.sendMessage("§e用法: /$label debug <combat|betterhud> <on|off>  |  /$label debug forgeinfo")
            return
        }
        val enabled = value == "on" || value == "true"
        val path = if (target == "combat") "debug.combat" else "betterhud.debug"
        plugin.config.set(path, enabled)
        plugin.saveConfig()
        plugin.reloadAll()
        val label2 = if (target == "combat") "战斗" else "BetterHud"
        sender.sendMessage("§a[SourceForge] §f${label2}调试已${if (enabled) "开启" else "关闭"}")
    }

    private fun cmdEnergy(sender: CommandSender, label: String, args: Array<out String>) {
        val sub = args.getOrNull(1)?.lowercase()
        when (sub) {
            "deduct" -> {
                val target = Bukkit.getPlayerExact(args.getOrNull(2) ?: "")
                val amount = args.getOrNull(3)?.toDoubleOrNull()
                if (target == null || amount == null || amount <= 0) {
                    sender.sendMessage("§e用法: /$label energy deduct <玩家> <数量>")
                    return
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
                    return
                }
                val cur = plugin.energyService.getEnergyCurrent(target)
                val max = plugin.energyService.getEnergyMax(target)
                sender.sendMessage("§a[SourceForge] §f${target.name} 能量: ${"%.0f".format(cur)}/${"%.0f".format(max)}")
            }
            "set" -> {
                if (!sender.hasPermission("sourceforge.admin")) { sender.sendMessage("§c你没有权限"); return }
                val target = Bukkit.getPlayerExact(args.getOrNull(2) ?: "")
                val amount = args.getOrNull(3)?.toDoubleOrNull()
                if (target == null || amount == null || amount < 0) {
                    sender.sendMessage("§e用法: /$label energy set <玩家> <数量>")
                    return
                }
                plugin.energyService.setEnergy(target, amount)
                sender.sendMessage("§a[SourceForge] §f已设置 ${target.name} 能量: ${"%.0f".format(amount)}/${"%.0f".format(plugin.energyService.getEnergyMax(target))}")
            }
            else -> sender.sendMessage("§e用法: /$label energy <deduct|get|set> [玩家] [数量]")
        }
    }

    private fun cmdGive(sender: CommandSender, label: String, args: Array<out String>) {
        if (!requireAdmin(sender)) return
        val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
        val expression = args.getOrNull(2)
        if (target == null || expression == null) {
            sender.sendMessage("§e用法: /$label give <玩家> <sf表达式> [数量]")
            return
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
                return
            }
            sender.sendMessage("§a[SourceForge] §f已给予 ${target.name}: $expression x$generated")
        } else {
            val item = plugin.buildItemExpression(expression, target, amount)
            if (item == null) {
                sender.sendMessage("§c[SourceForge] §f无法生成物品: $expression")
                return
            }
            target.inventory.addItem(item).values.forEach { target.world.dropItemNaturally(target.location, it) }
            sender.sendMessage("§a[SourceForge] §f已给予 ${target.name}: $expression")
        }
    }

    private fun cmdTestDamage(sender: CommandSender, label: String, args: Array<out String>) {
        if (!requireAdmin(sender)) return
        val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
        val amount = args.getOrNull(2)?.toDoubleOrNull()
        if (target == null || amount == null || amount <= 0.0) {
            sender.sendMessage("§e用法: /$label testdamage <玩家> <伤害>")
            return
        }
        target.damage(amount)
        sender.sendMessage("§a[SourceForge] §f已对 ${target.name} 施加测试伤害: $amount")
    }

    private fun cmdReroll(sender: CommandSender) {
        if (!requireAdmin(sender)) return
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("只有玩家可以重铸手持装备")
            return
        }
        val item = player.inventory.itemInMainHand
        if (!plugin.itemService.rerollEquipment(item)) {
            sender.sendMessage("§c[SourceForge] §f请手持 SourceForge 装备")
            return
        }
        plugin.itemService.invalidateStatCache(player)
        sender.sendMessage("§a[SourceForge] §f手持装备已重铸")
    }

    private fun cmdUpgrade(sender: CommandSender) {
        if (!requireAdmin(sender)) return
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("只有玩家可以升级手持装备")
            return
        }
        val item = player.inventory.itemInMainHand
        if (!plugin.itemService.upgradeEquipment(item)) {
            sender.sendMessage("§c[SourceForge] §f请手持未满级的 SourceForge 装备")
            return
        }
        plugin.itemService.invalidateStatCache(player)
        sender.sendMessage("§a[SourceForge] §f手持装备已升级")
    }

    private fun cmdStats(sender: CommandSender) {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("只有玩家可以使用此命令")
            return
        }
        showStats(player)
    }

    private fun cmdCd(sender: CommandSender, label: String, args: Array<out String>) {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("只有玩家可以切换 CD 显示")
            return
        }
        val enabled = when (args.getOrNull(1)?.lowercase()) {
            "on", "true" -> true
            "off", "false" -> false
            null, "toggle" -> !plugin.skillListener.isCdDisplayEnabled(player)
            else -> {
                sender.sendMessage("§e用法: /$label cd <on|off>")
                return
            }
        }
        plugin.skillListener.setCdDisplay(player, enabled)
        sender.sendMessage("§a[SourceForge] §f技能 CD 显示已${if (enabled) "开启" else "关闭"}")
    }

    private fun cmdTrack(sender: CommandSender, label: String, args: Array<out String>) {
        if (!requireAdmin(sender)) return
        val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
        if (target == null) {
            sender.sendMessage("§e用法: /$label track <玩家> <目标名> <x> <y> <z> [世界] [颜色]  |  /$label track <玩家> off")
            return
        }
        if (args.getOrNull(2)?.equals("off", true) == true) {
            val had = plugin.navigationManager.stop(target)
            sender.sendMessage(if (had) "§a[SourceForge] §f已清空 ${target.name} 的全部追踪目标" else "§e[SourceForge] §f${target.name} 当前没有追踪目标")
            return
        }
        val name = args.getOrNull(2)
        val x = args.getOrNull(3)?.toDoubleOrNull()
        val y = args.getOrNull(4)?.toDoubleOrNull()
        val z = args.getOrNull(5)?.toDoubleOrNull()
        if (name == null || x == null || y == null || z == null) {
            sender.sendMessage("§e用法: /$label track <玩家> <目标名> <x> <y> <z> [世界] [颜色]  |  /$label track <玩家> off")
            return
        }
        val world = args.getOrNull(6) ?: target.world.name
        val colorArg = args.getOrNull(7)
        val resolved = com.dongzh1.sourceforge.nav.NavigationManager.resolveColor(colorArg) ?: run {
            sender.sendMessage("§c[SourceForge] §f未知颜色: $colorArg，可用命名色: ${com.dongzh1.sourceforge.nav.NavigationManager.COLORS.keys.joinToString(", ")}，或直接写 §b#RRGGBB")
            return
        }
        plugin.navigationManager.track(target, name, x, y, z, world, resolved.hex, resolved.icon)
        sender.sendMessage("§a[SourceForge] §f已为 §e${target.name} §f追踪 §b$name §7(${x.toInt()}, ${y.toInt()}, ${z.toInt()} @ $world) §f颜色 §b${resolved.label}§f，当前共 ${plugin.navigationManager.targetNames(target).size} 个目标")
    }

    private fun cmdUntrack(sender: CommandSender, label: String, args: Array<out String>) {
        if (!requireAdmin(sender)) return
        val target = Bukkit.getPlayerExact(args.getOrNull(1) ?: "")
        val name = args.getOrNull(2)
        if (target == null || name == null) {
            sender.sendMessage("§e用法: /$label untrack <玩家> <目标名>")
            return
        }
        val ok = plugin.navigationManager.untrack(target, name)
        sender.sendMessage(if (ok) "§a[SourceForge] §f已移除 ${target.name} 的追踪目标 §b$name" else "§e[SourceForge] §f${target.name} 没有名为 §b$name §f的追踪目标")
    }

    // ==================== Tab 补全 ====================

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("forge", "mods", "givemod", "givedreammark", "dreammark", "givedreamcore", "giveblankmod", "giverelic", "reload", "validate", "giveequipment", "give", "testdamage", "mmdamage", "reroll", "upgrade", "stats", "track", "untrack", "sect", "cd", "debug").filter { it.startsWith(args[0], true) }
            2 -> when {
                args[0].equals("sect", true) || args[0].equals("cult", true) || args[0].equals("faction", true) -> listOf("list", "join", "buy", "prepare", "finish", "status", "leave", "npc").filter { it.startsWith(args[1], true) }
                args[0].equals("giveequipment", true) || args[0].equals("give", true) || args[0].equals("givemod", true) || args[0].equals("givenightmare", true) || args[0].equals("givedreammark", true) || args[0].equals("giveriven", true) || args[0].equals("givedreamcore", true) || args[0].equals("givekuva", true) || args[0].equals("giveblankmod", true) || args[0].equals("giverelic", true) || args[0].equals("testdamage", true) || args[0].equals("mmdamage", true) || args[0].equals("track", true) || args[0].equals("untrack", true) || args[0].equals("nav", true) || args[0].equals("navigate", true) -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) }
                args[0].equals("dreammark", true) -> listOf("weave", "accept", "keep").filter { it.startsWith(args[1], true) }
                args[0].equals("debug", true) -> listOf("combat", "enhancement", "betterhud", "forgeinfo", "element", "fulltest", "pdc").filter { it.startsWith(args[1], true) }
                args[0].equals("cd", true) -> listOf("on", "off").filter { it.startsWith(args[1], true) }
                else -> emptyList()
            }
            3 -> when {
                (args[0].equals("sect", true) || args[0].equals("cult", true) || args[0].equals("faction", true)) && args[1].equals("join", true) -> plugin.sectService.sects().map { it.id }.filter { it.startsWith(args[2], true) }
                (args[0].equals("sect", true) || args[0].equals("cult", true) || args[0].equals("faction", true)) && args[1].equals("buy", true) ->
                    (sender as? Player)?.let { plugin.sectService.purchasableRewardIds(it) } ?: emptyList()
                (args[0].equals("sect", true) || args[0].equals("cult", true) || args[0].equals("faction", true)) && args[1].equals("npc", true) -> listOf("create", "remove", "open", "list", "prepare", "close").filter { it.startsWith(args[2], true) }
                args[0].equals("giveequipment", true) -> plugin.forgeConfig.equipment.keys.filter { it.startsWith(args[2], true) }
                args[0].equals("give", true) -> expressionSuggestions().filter { it.startsWith(args[2], true) }
                args[0].equals("givemod", true) -> plugin.modService.modSuggestions().filter { it.startsWith(args[2], true) }
                args[0].equals("giveblankmod", true) -> listOf("1", "8", "16", "64").filter { it.startsWith(args[2], true) }
                args[0].equals("giverelic", true) -> plugin.relicService.relicIds().filter { it.startsWith(args[2], true) }
            args[0].equals("givedreammark", true) ->
                (plugin.dreammarkRelics.ids() + plugin.rivenService.groupIds()).filter { it.startsWith(args[2], true) }
                args[0].equals("givedreamcore", true) -> listOf("1", "8", "16", "32", "64").filter { it.startsWith(args[2], true) }
                args[0].equals("debug", true) && args[1].equals("enhancement", true) ->
                    listOf("reset5", "downgrade", "reset").filter { it.startsWith(args[2], true) }
                args[0].equals("debug", true) && args[1].equals("combat", true) ->
                    (listOf("on", "off") + Bukkit.getOnlinePlayers().map { it.name }).filter { it.startsWith(args[2], true) }
                args[0].equals("debug", true) -> listOf("on", "off").filter { it.startsWith(args[2], true) }
                args[0].equals("track", true) || args[0].equals("nav", true) || args[0].equals("navigate", true) -> listOf("off").filter { it.startsWith(args[2], true) }
                args[0].equals("untrack", true) -> (Bukkit.getPlayerExact(args[1])?.let { plugin.navigationManager.targetNames(it) } ?: emptyList()).filter { it.startsWith(args[2], true) }
                else -> emptyList()
            }
            // track 的坐标/世界/颜色补全：默认补全 args[1] 指定玩家的当前坐标与所在世界。
            4 -> when {
                args[0].equals("givemod", true) -> listOf("1", "5", "10").filter { it.startsWith(args[3], true) }
                args[0].equals("debug", true) && args[1].equals("enhancement", true) ->
                    listOf("5").filter { it.startsWith(args[3], true) }
                args[0].equals("debug", true) && args[1].equals("combat", true) ->
                    listOf("on", "off").filter { it.startsWith(args[3], true) }
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
     * 用于快速定位"词条在但属性没加成 / 外部临时属性失效"等回归。
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

    /** 逐个尝试已知 PersistentDataType，返回第一个匹配上的类型+值(调试用，不追求性能)。 */
    private fun describePdcValue(pdc: org.bukkit.persistence.PersistentDataContainer, key: org.bukkit.NamespacedKey): String {
        pdc.get(key, org.bukkit.persistence.PersistentDataType.STRING)?.let { return "STRING(\"$it\")" }
        pdc.get(key, org.bukkit.persistence.PersistentDataType.INTEGER)?.let { return "INT($it)" }
        pdc.get(key, org.bukkit.persistence.PersistentDataType.LONG)?.let { return "LONG($it)" }
        pdc.get(key, org.bukkit.persistence.PersistentDataType.DOUBLE)?.let { return "DOUBLE($it)" }
        pdc.get(key, org.bukkit.persistence.PersistentDataType.FLOAT)?.let { return "FLOAT($it)" }
        pdc.get(key, org.bukkit.persistence.PersistentDataType.BYTE)?.let { return "BYTE($it)" }
        pdc.get(key, org.bukkit.persistence.PersistentDataType.INTEGER_ARRAY)?.let { return "INT_ARRAY(${it.joinToString()})" }
        pdc.get(key, org.bukkit.persistence.PersistentDataType.LONG_ARRAY)?.let { return "LONG_ARRAY(${it.joinToString()})" }
        pdc.get(key, org.bukkit.persistence.PersistentDataType.BYTE_ARRAY)?.let { return "BYTE_ARRAY(${it.joinToString()})" }
        pdc.get(key, org.bukkit.persistence.PersistentDataType.TAG_CONTAINER)?.let { nested ->
            return "CONTAINER{" + nested.keys.joinToString(", ") { k -> "$k=" + describePdcValue(nested, k) } + "}"
        }
        pdc.get(key, org.bukkit.persistence.PersistentDataType.TAG_CONTAINER_ARRAY)?.let { arr -> return "CONTAINER_ARRAY[size=${arr.size}]" }
        return "<unresolved type>"
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
        sendAffixGroup(
            player,
            "召唤属性",
            totals,
            listOf("summon_damage", "summon_max_count")
        )

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
        val percentAffixes = setOf(
            "critical_chance",
            "critical_damage",
            "status_chance",
            "summon_damage",
            "ability_strength",
            "ability_duration",
            "ability_efficiency"
        )
    }
}
