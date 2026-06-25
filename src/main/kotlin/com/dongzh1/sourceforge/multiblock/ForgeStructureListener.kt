package com.dongzh1.sourceforge.multiblock

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.forge.ForgeMenu
import com.dongzh1.sourceforge.item.CraftEngineHook
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * 源质锻炉交互监听。镜像 GeneMachinery 的 MachineListener：用原版 PlayerInteractEvent / BlockBreakEvent，
 * 通过 CraftEngineHook.blockId(block) 把方块识别回 CE id，而不是依赖 CE 的自定义方块事件
 * （GeneMachinery 验证过：CE 在原版事件后处理自定义方块，用 CraftEngineBlocks 反查更稳）。
 */
class ForgeStructureListener(
    private val plugin: SourceForge
) : Listener {
    private val manager get() = plugin.structureManager
    private val config get() = plugin.structureManager.config

    @EventHandler(priority = EventPriority.NORMAL)
    fun onInteract(event: PlayerInteractEvent) {
        if (!config.enabled) return
        if (event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        val ceId = CraftEngineHook.blockId(block) ?: return
        if (ceId !in config.forgeBlockIds) return

        val player = event.player
        val hand = player.inventory.itemInMainHand
        // 锻炉工具已从 CE 重锤迁移到原版重锤(Mace)：手持重锤即可检测/拆除
        val isHammer = hand.type == org.bukkit.Material.MACE
        val isCore = ceId == config.coreBlockId

        when (event.action) {
            // 左键核心：持重锤 = 检测结构（取消事件防止挖掘/破坏，创造模式的瞬间破坏另由 onBreak 拦截）
            Action.LEFT_CLICK_BLOCK -> {
                if (isCore && isHammer) {
                    event.isCancelled = true
                    handleHammer(player, block)
                }
            }
            Action.RIGHT_CLICK_BLOCK -> {
                // 拆除：手持重锤 + 潜行 + 右键 任意锻炉方块（核心 / 外壳）
                if (isHammer && player.isSneaking) {
                    event.isCancelled = true
                    val now = System.currentTimeMillis()
                    if (now - (dismantleCooldown[player.uniqueId] ?: 0L) < dismantleCooldownMs) return
                    dismantleCooldown[player.uniqueId] = now
                    dismantle(player, block, ceId)
                    return
                }
                // 核心非拆除右键：开锻造 UI（进度/收取已并入 ForgeMenu 的动作槽）。
                if (isCore) {
                    event.isCancelled = true
                    val job = manager.jobAt(block)
                    if (job != null) {
                        // 已有作业：直接打开锻造界面，动作槽显示进度/收取。
                        // 用作业自身记录的外壳/倍率构造上下文，避免重新校验结构。
                        openStructureForge(player, block, job.shellTier, job.multiplier)
                        return
                    }
                    val result = ForgeStructure.validate(block, config)
                    if (result.formed && result.tier != null) {
                        openStructureForge(player, block, result.tier, result.multiplier)
                    } else {
                        player.sendMessage("§c结构未完成，请用重锤左键核心检测")
                        playDeny(player)
                    }
                }
                // 外壳非拆除右键：放行（不取消，允许照常贴着外壳放置方块等）
            }
            else -> {}
        }
    }

    /** 程序化拆除时置真：CraftEngineBlocks.remove 内部可能触发 BlockBreakEvent，避免被本类 onBreak 当成徒手破坏拦截。 */
    private var intentionalRemoval = false

    /** 拆除冷却：玩家 UUID -> 上次拆除毫秒。防止按住 Shift+右键一次连拆多个方块。 */
    private val dismantleCooldown = java.util.HashMap<java.util.UUID, Long>()
    private val dismantleCooldownMs = 400L

    /**
     * 拆除一个锻炉方块（核心或外壳）：仅由“手持重锤 + 潜行 + 右键”触发。
     * 用 CE 稳定 api 的 CraftEngineBlocks.remove 移除（掉落方块物品 + 清理 CE 数据）；
     * 若拆的是带作业的核心，先退还已消耗材料。
     */
    private fun dismantle(player: Player, block: Block, ceId: String) {
        if (ceId == config.coreBlockId && manager.hasJob(block)) {
            manager.cancelAndRefund(block)
            player.sendMessage("§e[源质锻炉] §f拆除核心，已退还消耗的材料")
        }
        val world = block.world
        val dropLoc = block.location.add(0.5, 0.5, 0.5)
        intentionalRemoval = true
        val removed = try { CraftEngineHook.removeBlock(block, player) } finally { intentionalRemoval = false }
        if (!removed) {
            // 兜底：CE 移除失败时直接清空并补发方块物品
            block.type = org.bukkit.Material.AIR
            CraftEngineHook.build(ceId, 1)?.let { world.dropItemNaturally(dropLoc, it) }
        }
        world.playSound(dropLoc, Sound.BLOCK_ANVIL_DESTROY, 0.7f, 1.0f)
    }

    private fun handleHammer(player: Player, core: Block) {
        val result = ForgeStructure.validate(core, config)
        when {
            result.formed && result.tier != null -> {
                val tierName = config.tierDisplay(result.tier)
                player.sendMessage("§a[源质锻炉] §f结构完成 ✔ §7(${tierName}壳 ${result.multiplier}× 速度)")
                player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 0.8f, 1.2f)
            }
            result.mixed -> {
                player.sendMessage("§c外壳材质不一致")
                playDeny(player)
            }
            else -> {
                player.sendMessage("§c还缺 §e${result.missing} §c块外壳")
                playDeny(player)
            }
        }
    }

    private fun openStructureForge(player: Player, core: Block, tier: String, multiplier: Double) {
        val ctx = ForgeMenu.StructureContext(
            world = core.world.name,
            x = core.x,
            y = core.y,
            z = core.z,
            shellTier = tier,
            multiplier = multiplier
        )
        player.openInventory(ForgeMenu(plugin, ctx).inventory)
    }

    /**
     * 锻炉方块（核心 + 外壳）禁止普通破坏：任何工具的挖掘/瞬破都拦截，
     * 只能“手持重锤 + 潜行 + 右键”拆除（见 dismantle）。核心作业的材料退还也在拆除时处理。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (!config.enabled) return
        if (intentionalRemoval) return // 程序化拆除（CraftEngineBlocks.remove）放行，不当作徒手破坏
        val block = event.block
        val ceId = CraftEngineHook.blockId(block) ?: return
        if (ceId !in config.forgeBlockIds) return
        event.isCancelled = true
        event.player.sendMessage("§7[源质锻炉] §f需手持重锤 §eShift+右键 §f拆除")
    }

    // ===== 锻炉方块保护(核心+外壳):位置与作业绑定,被移动/破坏会导致结构与作业错乱,故堵住除重锤外的所有途径 =====

    /** 是否锻炉方块(核心或外壳)。 */
    private fun isForgeBlock(b: Block): Boolean {
        if (!config.enabled) return false
        val ceId = CraftEngineHook.blockId(b) ?: return false
        return ceId in config.forgeBlockIds
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (event.blocks.any { isForgeBlock(it) }) event.isCancelled = true   // 锻炉方块不可被推动
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (event.blocks.any { isForgeBlock(it) }) event.isCancelled = true   // 粘性活塞不可拉走锻炉方块
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf { isForgeBlock(it) }   // TNT/苦力怕/凋零/末影水晶等:不炸毁锻炉方块
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf { isForgeBlock(it) }   // 床/重生锚爆炸:不炸毁锻炉方块
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (isForgeBlock(event.block)) event.isCancelled = true   // 末影人搬运/凋零撞击:锻炉方块不可被实体改变
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) {
        if (isForgeBlock(event.block)) event.isCancelled = true   // 着火:不烧毁锻炉方块
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onLiquidFlow(event: BlockFromToEvent) {
        if (isForgeBlock(event.toBlock)) event.isCancelled = true // 液体冲入:不替换锻炉方块位置
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onStructureGrow(event: StructureGrowEvent) {
        event.blocks.removeIf { isForgeBlock(it.block) }  // 树木/巨型结构生长:不覆盖锻炉方块
    }

    private fun playDeny(player: Player) {
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.45f, 0.65f)
    }
}
