package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
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
    private val attackDebounce = ConcurrentHashMap<java.util.UUID, Long>()
    /** 触发栏去抖：key=(玩家, 触发栏)。 */
    private val triggerDebounce = ConcurrentHashMap<Pair<java.util.UUID, TriggerSlot>, Long>()
    private val script get() = plugin.scriptService

    fun start() {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 20L, 20L) // 每秒
    }

    /** 该装备上装的、且存在对应脚本的技能id（仅普通8槽，供旧 onAttack/onToggle 路径用，避免与触发栏双触发）。 */
    private fun skillModsOn(item: org.bukkit.inventory.ItemStack?): Set<String> =
        plugin.modService.installedModIds(item).intersect(script.loadedSkillIds())

    /** 该装备上所有"有对应JS技能"的技能id：普通8槽 ∪ 技能触发栏。供 onDamaged/被动类结算用（含铁壁架势等触发栏技能）。 */
    private fun allSkillIdsOn(item: org.bukkit.inventory.ItemStack?): Set<String> =
        (plugin.modService.installedModIds(item) + plugin.modService.installedSkillModIds(item))
            .intersect(script.loadedSkillIds())

    private fun anyEquipHasMod(player: Player, modId: String): Boolean =
        plugin.itemService.effectiveSourceItems(player).any {
            // 评审#1：普通8槽 ∪ 技能触发栏——否则持续型触发栏技能(如铁壁架势)会在下一 tick 被误判"装备已移除"而关闭
            plugin.modService.installedModIds(it).contains(modId) ||
                plugin.modService.installedSkillModIds(it).contains(modId)
        }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        val main = player.inventory.itemInMainHand
        val now = System.currentTimeMillis()
        val pid = player.uniqueId.toString()

        // 新：触发栏路由（技能槽 skill=true 的MOD，按槽位对应触发 onActivate）。
        // 消歧：地面左键=LEFT，空中左键=MIDAIR（可选覆盖），潜行左键=SHIFT_LEFT；右键/潜行右键同理。
        when (event.action) {
            Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK -> {
                if (player.isSneaking) fireTriggerSlot(player, main, TriggerSlot.SHIFT_LEFT, now, pid)
                else {
                    // 评审#2：MIDAIR 仅作"可选空中覆盖"——须同时【在空中】且【MIDAIR栏已装技能】才走 MIDAIR，
                    // 否则一律 LEFT。这样 isOnGround 抖动/未装空中技能都不会把左键技能静默吞掉。
                    val useMidair = isAirborne(player) &&
                        plugin.modService.skillModAtTrigger(main, TriggerSlot.MIDAIR) != null
                    fireTriggerSlot(player, main, if (useMidair) TriggerSlot.MIDAIR else TriggerSlot.LEFT, now, pid)
                }
            }
            Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK -> {
                // 评审#5：右键交互方块(箱子/门/工作台/按钮等)不触发右键技能，避免开箱/开门即放技能烧蓝烧CD
                val interactBlock = event.action == Action.RIGHT_CLICK_BLOCK &&
                    event.clickedBlock?.type?.isInteractable == true
                if (!interactBlock)
                    fireTriggerSlot(player, main, if (player.isSneaking) TriggerSlot.SHIFT_RIGHT else TriggerSlot.RIGHT, now, pid)
            }
            else -> Unit
        }

        // 旧：普通槽技能(skill=false)保持原触发（右键 onToggle / 左键 onAttack），向后兼容。
        val skillIds = skillModsOn(main)
        if (skillIds.isEmpty()) return
        when (event.action) {
            Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK -> {
                if (now - (toggleDebounce[player.uniqueId] ?: 0L) < 300L) return
                toggleDebounce[player.uniqueId] = now
                for (id in skillIds) script.fireToggle(id, pid)
            }
            Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK -> {
                if (now - (attackDebounce[player.uniqueId] ?: 0L) < 100L) return
                attackDebounce[player.uniqueId] = now
                for (id in skillIds) script.fireAttack(id, pid)
            }
            else -> Unit
        }
    }

    /** 触发栏路由：查该栏上安装的技能MOD(须有对应JS技能)，去抖后 fireActivate。 */
    private fun fireTriggerSlot(player: Player, item: org.bukkit.inventory.ItemStack?, trigger: TriggerSlot, now: Long, pid: String) {
        val id = plugin.modService.skillModAtTrigger(item, trigger) ?: return
        if (id !in script.loadedSkillIds()) return
        val key = player.uniqueId to trigger
        if (now - (triggerDebounce[key] ?: 0L) < 150L) return
        triggerDebounce[key] = now
        script.fireActivate(id, pid)
    }

    /** 稳健的"在空中"判定：isOnGround 是客户端上报值会抖，用脚下短距离内是否有可站立方块二次确认。 */
    private fun isAirborne(player: Player): Boolean {
        if (player.isFlying || player.isOnGround) return false
        val loc = player.location
        // 脚下 0.1~0.5 格内任一处有不可穿过方块 → 视为在地面（抵消 isOnGround 抖动把地面左键误判成空中）
        val hasGroundNear = (1..5).any { i ->
            runCatching { !loc.clone().subtract(0.0, 0.1 * i, 0.0).block.isPassable }.getOrDefault(false)
        }
        return !hasGroundNear
    }

    /** Shift+F(换手键) → SHIFT_F 触发栏。命中则取消换手，避免误触主副手交换。 */
    @EventHandler(priority = EventPriority.NORMAL)
    fun onSwapHand(event: org.bukkit.event.player.PlayerSwapHandItemsEvent) {
        val player = event.player
        if (!player.isSneaking) return
        val main = player.inventory.itemInMainHand
        val id = plugin.modService.skillModAtTrigger(main, TriggerSlot.SHIFT_F) ?: return
        if (id !in script.loadedSkillIds()) return
        event.isCancelled = true
        val now = System.currentTimeMillis()
        val key = player.uniqueId to TriggerSlot.SHIFT_F
        if (now - (triggerDebounce[key] ?: 0L) < 150L) return
        triggerDebounce[key] = now
        script.fireActivate(id, player.uniqueId.toString())
    }

    /**
     * 玩家受击 → 转交技能脚本的 onDamaged 钩子；任一脚本返回 true 则取消本次伤害(免疫)。
     * 在 LOW 触发：早于 ForgeListener(HIGHEST)，配合其 ignoreCancelled=true 让 SF 战斗整段跳过。
     * 用于震刀(parry)这类格挡免疫技能。
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDamaged(event: EntityDamageByEntityEvent) {
        val player = event.entity as? Player ?: return
        // 评审#4：跨【全部有效源装备】取 普通8槽∪技能触发栏，与 tick 保活口径一致——
        // 否则铁壁架势的减伤(onTick 按 effectiveSourceItems 保活)与格挡(onDamaged)口径不一致，
        // 武器换到副手时会出现"减伤还在、格挡失效"的分裂状态。
        val skillIds = plugin.itemService.effectiveSourceItems(player).flatMap { allSkillIdsOn(it) }.toSet()
        if (skillIds.isEmpty()) return
        val pid = player.uniqueId.toString()
        for (id in skillIds) {
            if (script.fireDamaged(id, pid, event.damage, event.cause.name)) {
                event.isCancelled = true
                return
            }
        }
    }

    /** 玩家退出：清理去抖记录与该玩家的全部技能脚本状态（评审 #6）。 */
    @EventHandler
    fun onQuit(event: org.bukkit.event.player.PlayerQuitEvent) {
        val uid = event.player.uniqueId
        toggleDebounce.remove(uid)
        attackDebounce.remove(uid)
        triggerDebounce.keys.removeIf { it.first == uid }
        script.api.clearPlayerState(uid.toString())
    }

    private fun tick() {
        val regen = plugin.config.getDouble("mana.regen-per-second", 1.0)
        for (player in plugin.server.onlinePlayers) plugin.energyService.regenMana(player, regen)

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
