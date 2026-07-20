package com.dongzh1.sourceforge.relic

import org.bukkit.block.ShulkerBox
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta

/**
 * 遗物相关的背包扫描原语：玩家背包(含护甲/副手，0 until inv.size 共41格，同 util.SfItems.enhanceLevelSum
 * 的既有扫描范围) + 其中潜影盒内部一层(不递归嵌套潜影盒)。数量检测(relicCount)、开抽奖找目标(crackRelic)、
 * 解锁任务批量绑定(bindAllUnlocksIfNeeded)三处共用同一套扫描/读写逻辑，避免各自重复实现。
 *
 * 潜影盒展开写法照抄 SourceWild DeathChestManager.extractProtectedFromContainer 的既有约定：
 * BlockStateMeta -> ShulkerBox -> inventory，写回时要重新读出外层物品、改内部 inventory、
 * 再把改完的 BlockStateMeta 整体写回外层槽位，直接改 state.inventory 不重新赋回 meta/item 会静默丢失。
 */
object RelicInventoryScan {

    sealed class Slot {
        data class TopLevel(val index: Int) : Slot()
        data class InShulker(val topIndex: Int, val innerIndex: Int) : Slot()
    }

    /** 遍历玩家背包与其中潜影盒内部的所有非空物品；visit 返回 false 时立即停止遍历(供"找第一个"用)。 */
    fun scan(player: Player, visit: (Slot, ItemStack) -> Boolean) {
        val inv = player.inventory
        for (i in 0 until inv.size) {
            val item = inv.getItem(i) ?: continue
            if (item.type.isAir) continue
            if (!visit(Slot.TopLevel(i), item)) return
            val shulker = shulkerInventoryOf(item) ?: continue
            for (j in 0 until shulker.size) {
                val inner = shulker.getItem(j) ?: continue
                if (inner.type.isAir) continue
                if (!visit(Slot.InShulker(i, j), inner)) return
            }
        }
    }

    /** 按 item.amount 累加所有满足条件的物品数量(不是格子数——一叠里有多个也要算够数)。 */
    fun countMatching(player: Player, predicate: (ItemStack) -> Boolean): Int {
        var sum = 0
        scan(player) { _, item -> if (predicate(item)) sum += item.amount; true }
        return sum
    }

    /** 找到第一个满足条件的物品，连同它的槽位信息一起返回(供 consumeOne/writeBack 使用)。 */
    fun findFirst(player: Player, predicate: (ItemStack) -> Boolean): Pair<Slot, ItemStack>? {
        var found: Pair<Slot, ItemStack>? = null
        scan(player) { slot, item ->
            if (predicate(item)) {
                found = slot to item
                false
            } else true
        }
        return found
    }

    /** 把 item 写回指定槽位；item 为 null 表示清空该槽位。 */
    fun writeBack(player: Player, slot: Slot, item: ItemStack?) {
        val inv = player.inventory
        when (slot) {
            is Slot.TopLevel -> inv.setItem(slot.index, item)
            is Slot.InShulker -> {
                val outer = inv.getItem(slot.topIndex) ?: return
                val meta = outer.itemMeta as? BlockStateMeta ?: return
                val state = meta.blockState as? ShulkerBox ?: return
                state.inventory.setItem(slot.innerIndex, item)
                meta.blockState = state
                outer.itemMeta = meta
                inv.setItem(slot.topIndex, outer)
            }
        }
    }

    /** 从指定槽位精确扣 1 个：amount>1 则减量保留剩余，否则清空槽位。 */
    fun consumeOne(player: Player, slot: Slot) {
        val item = readSlot(player, slot) ?: return
        if (item.amount > 1) {
            item.amount -= 1
            writeBack(player, slot, item)
        } else {
            writeBack(player, slot, null)
        }
    }

    private fun readSlot(player: Player, slot: Slot): ItemStack? {
        val inv = player.inventory
        return when (slot) {
            is Slot.TopLevel -> inv.getItem(slot.index)
            is Slot.InShulker -> shulkerInventoryOf(inv.getItem(slot.topIndex) ?: return null)
                ?.getItem(slot.innerIndex)
        }
    }

    private fun shulkerInventoryOf(item: ItemStack): Inventory? {
        val meta = item.itemMeta as? BlockStateMeta ?: return null
        val state = meta.blockState as? ShulkerBox ?: return null
        return state.inventory
    }
}
