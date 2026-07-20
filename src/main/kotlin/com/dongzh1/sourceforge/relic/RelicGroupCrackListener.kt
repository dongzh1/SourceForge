package com.dongzh1.sourceforge.relic

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.item.CraftEngineHook
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent

/**
 * 组队开箱选择界面的点击处理 + 会话生命周期(开界面/超时保底)。每个参与者各自打开自己的
 * RelicGroupCrackMenu 实例，点击任意一格即可领取该下标对应的结果——结果不互斥，同一下标可以被
 * 多个参与者各自领取(各自都会现建一份新物品，不影响其他人)。[open] 由 SourceForgeRelicAPI 调用。
 */
object RelicGroupCrackListener : Listener {

    /** 开一次组队开箱：给每个贡献者各开一份自己的界面，发提示消息，并排一个超时保底任务。 */
    fun open(plugin: SourceForge, session: RelicGroupCrackSession, participants: List<Player>) {
        if (participants.isEmpty()) return
        participants.forEach { player -> RelicGroupCrackMenu(session, player.uniqueId).open(player) }
        val count = session.outcomes.size
        participants.forEach { player ->
            player.sendMessage(
                if (count == 1) "§d[遗物] 你的遗物开出了一张蓝图，请在界面中点击领取"
                else "§d[遗物] 队伍开出了 $count 份战利品，任选其中一份领取（重复选中互不影响）"
            )
        }
        val seconds = plugin.relicService.groupCrackTimeoutSeconds.toLong()
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (session.allClaimed()) return@Runnable
            session.outcomes.forEachIndexed { index, outcome ->
                if (session.claimedBy.containsKey(outcome.ownerId)) return@forEachIndexed
                val owner = Bukkit.getPlayer(outcome.ownerId) ?: return@forEachIndexed
                claim(plugin, session, owner, index, auto = true)
            }
        }, seconds * 20L)
    }

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val holder = e.inventory.holder as? RelicGroupCrackMenu ?: return
        e.isCancelled = true
        val player = e.whoClicked as? Player ?: return
        val index = e.rawSlot
        if (index < 0 || index >= holder.session.outcomes.size) return
        claim(SourceForge.inst, holder.session, player, index, auto = false)
    }

    /** 领取一份结果：一人只能领一次，领取的一定是自己点的那个下标(auto=true 时是超时保底，强制领自己那一份)。 */
    private fun claim(plugin: SourceForge, session: RelicGroupCrackSession, player: Player, index: Int, auto: Boolean) {
        if (player.uniqueId !in session.participantIds) return
        if (session.claimedBy.containsKey(player.uniqueId)) return
        val outcome = session.outcomes.getOrNull(index) ?: return
        val built = CraftEngineHook.build(outcome.blueprintId, 1) ?: return
        session.claimedBy[player.uniqueId] = index
        var overflowed = false
        player.inventory.addItem(built).values.forEach {
            player.world.dropItemNaturally(player.location, it)
            overflowed = true
        }
        player.sendMessage(
            if (auto) "§e[遗物] 超时未选择，已自动为你领取你的那一份蓝图" else "§a[遗物] 你领取了一张蓝图"
        )
        if (overflowed) player.sendMessage("§e[遗物] 背包已满，蓝图已掉落在地上，请及时拾取")
        // 延迟一tick关闭：不能在事件处理的同一tick里直接关闭正在处理点击的界面(照抄现有GUI惯例)。
        Bukkit.getScheduler().runTask(plugin, Runnable { player.closeInventory() })
    }
}
