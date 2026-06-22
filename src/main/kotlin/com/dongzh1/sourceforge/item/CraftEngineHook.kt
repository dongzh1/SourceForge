package com.dongzh1.sourceforge.item

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import net.momirealms.craftengine.bukkit.api.CraftEngineItems
import net.momirealms.craftengine.core.util.Key
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.entity.Player
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
        // 26.6 API：CustomItem.buildBukkitItem() 返回数量 1 的物品，再设数量（镜像 GeneMachinery CeBridge.buildItem）。
        return runCatching {
            CraftEngineItems.byId(key)?.buildBukkitItem()?.apply { this.amount = amount.coerceAtLeast(1) }
        }.getOrNull()
    }

    /**
     * 读取一个方块对应的 CraftEngine 自定义方块 id（如 "sourceforge:forge_core"）。
     * 镜像 GeneMachinery 的做法：CraftEngineBlocks.getCustomBlockState(block).owner().value().id()。
     * 非 CE 方块或 CE 未启用返回 null。
     */
    fun blockId(block: Block): String? {
        if (!enabled) return null
        return runCatching {
            CraftEngineBlocks.getCustomBlockState(block)?.owner()?.value()?.id()?.toString()
        }.getOrNull()
    }

    /**
     * 以玩家身份移除一个 CE 自定义方块（掉落战利品 + 音效粒子 + 清理 CE 方块数据）。
     * 走稳定 api 的 CraftEngineBlocks.remove(block, player, movedByPiston=false, dropLoot=true, sendLevelEvent=true)，
     * 镜像 GeneMachinery 已验证的拆除方式；不要用 block.type=AIR（会留下 CE 幽灵数据且不掉落）。
     */
    fun removeBlock(block: Block, player: Player): Boolean {
        if (!enabled) return false
        return runCatching { CraftEngineBlocks.remove(block, player, false, true, true) }.getOrElse { false }
    }

    /** 稳定 api 判定：该方块是否为 CraftEngine 自定义方块。 */
    fun isCustomBlock(block: Block): Boolean {
        if (!enabled) return false
        return runCatching { CraftEngineBlocks.isCustomBlock(block) }.getOrDefault(false)
    }

    /** 给玩家一个 CE 物品（按 id），背包满则溢出由调用方处理。返回是否成功构建。 */
    fun giveItem(player: Player, id: String, amount: Int = 1): Boolean {
        val stack = build(id, amount) ?: return false
        player.inventory.addItem(stack).values.forEach {
            player.world.dropItemNaturally(player.location, it)
        }
        return true
    }

    private fun parseKey(raw: String): Key? {
        val normalized = raw.removePrefix("ce_")
        // 26.6 API：用稳定工厂 Key.of(...)（旧 0.0.66 的 Key(ns, value) 构造器在 26.6 不可用）。
        return runCatching {
            if (normalized.contains(":")) Key.of(normalized) else Key.of("default", normalized)
        }.getOrNull()
    }
}
