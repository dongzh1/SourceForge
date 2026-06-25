package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.concurrent.ConcurrentHashMap

/**
 * 技能MOD 的宿主桥：把 Bukkit 事件转给脚本钩子，逻辑全在 skills/<id>.js 里。
 * 约定：技能脚本 id == 对应 MOD 的 id（装了该 MOD 的装备才能用该技能）。
 *  - 右键持有装了某技能MOD的武器 → onToggle
 *  - 每秒 → 对开启该技能的玩家 onTick（顺带回 MANA）
 *  - 生物死亡 → onKillNearby 返回掉落倍率，取最高者翻倍
 */
class SkillModListener(private val plugin: SourceForge) : Listener {

    private val toggleDebounce = ConcurrentHashMap<java.util.UUID, Long>()
    private val script get() = plugin.scriptService

    fun start() {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 20L, 20L) // 每秒
    }

    /** 该装备上装的、且存在对应脚本的技能id。 */
    private fun skillModsOn(item: org.bukkit.inventory.ItemStack?): Set<String> =
        plugin.modService.installedModIds(item).intersect(script.loadedSkillIds())

    private fun anyEquipHasMod(player: Player, modId: String): Boolean =
        plugin.itemService.effectiveSourceItems(player).any { plugin.modService.installedModIds(it).contains(modId) }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val player = event.player
        val skillIds = skillModsOn(player.inventory.itemInMainHand)
        if (skillIds.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - (toggleDebounce[player.uniqueId] ?: 0L) < 300L) return
        toggleDebounce[player.uniqueId] = now
        for (id in skillIds) script.fireToggle(id, player.uniqueId.toString())
    }

    private fun tick() {
        val regen = plugin.config.getDouble("mana.regen-per-second", 1.0)
        for (player in plugin.server.onlinePlayers) plugin.forgeListener.regenMana(player, regen)

        for (skillId in script.api.activeSkillIds()) {
            for (uid in script.api.activePlayers(skillId)) {
                val player = plugin.server.getPlayer(uid)
                if (player == null || !player.isOnline) { script.api.deactivate(skillId, uid); continue }
                if (!anyEquipHasMod(player, skillId)) {
                    script.api.deactivate(skillId, uid)
                    player.sendMessage("§7[技能] §f装备已移除，§e$skillId §f自动关闭")
                    continue
                }
                script.fireTick(skillId, uid.toString())
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDeath(event: EntityDeathEvent) {
        val dead = event.entity
        if (dead is Player || event.drops.isEmpty()) return
        val activeSkills = script.api.activeSkillIds()
        if (activeSkills.isEmpty()) return

        var maxMult = 1
        for (skillId in activeSkills) {
            for (uid in script.api.activePlayers(skillId)) {
                val player = plugin.server.getPlayer(uid) ?: continue
                if (player.world != dead.world) continue
                val dist = player.location.distance(dead.location)
                val mult = script.fireKillNearby(skillId, uid.toString(), dist)
                if (mult > maxMult) maxMult = mult
            }
        }
        if (maxMult <= 1) return
        val original = event.drops.map { it.clone() }
        repeat(maxMult - 1) { event.drops.addAll(original.map { it.clone() }) }
    }
}
