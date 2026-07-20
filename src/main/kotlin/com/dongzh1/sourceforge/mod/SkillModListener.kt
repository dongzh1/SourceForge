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
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
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
    /**
     * 触发栏去抖：key=(玩家, 触发栏, 技能MOD id)。
     * 2026-07-17：id 也编进 key——此前只按(玩家,触发栏)去抖，若同一按键同时对应"主手武器的技能"
     * 和"穿戴护甲的技能"(见下方 fireTriggerSlotForWornArmor，为 hearth_whisper 这类护甲技能新增)，
     * 同一次按键内两次 fireTriggerSlot 调用会被同一个 key 互相吞掉，只有先算的那个能触发。
     */
    private val triggerDebounce = ConcurrentHashMap<Triple<java.util.UUID, TriggerSlot, String>, Long>()
    private val script get() = plugin.scriptService

    fun start() {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 20L, 20L) // 每秒
        plugin.server.onlinePlayers.forEach { migrateLegacySkillSlots(it) }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        plugin.server.scheduler.runTask(plugin, Runnable { migrateLegacySkillSlots(event.player) })
    }

    private fun migrateLegacySkillSlots(player: Player) {
        var migrated = 0
        for (item in player.inventory.contents) {
            if (item == null || !plugin.modService.migrateLegacySkillSlots(item)) continue
            plugin.modService.reapplyModEffects(item)
            migrated++
        }
        if (migrated > 0) {
            player.updateInventory()
            player.sendMessage("§7[技能] 已将 $migrated 张旧版技能MOD迁入对应触发栏")
        }
    }

    /** 该装备上装的、且存在对应脚本的技能id（仅普通8槽，供旧 onAttack/onToggle 路径用，避免与触发栏双触发）。 */
    private fun skillModsOn(item: org.bukkit.inventory.ItemStack?): Set<String> =
        plugin.modService.installedModIds(item).intersect(script.loadedSkillIds())

    /** 该装备上所有"有对应JS技能"的技能id：普通8槽 ∪ 武器技能触发栏 ∪ 护甲被动技能槽。
     *  供 onDamaged/被动类结算用（含铁壁架势等触发栏技能、篝火低语等护甲被动技能）。 */
    private fun allSkillIdsOn(item: org.bukkit.inventory.ItemStack?): Set<String> =
        (plugin.modService.installedModIds(item) + plugin.modService.installedSkillModIds(item) + plugin.modService.installedPassiveSkillIds(item))
            .intersect(script.loadedSkillIds())

    /** 护甲被动技能独立于主手，武器持续技能则只能由当前主手维持。 */
    private fun isPassiveSkillWorn(player: Player, skillId: String): Boolean =
        player.inventory.armorContents.any { piece ->
            piece != null && plugin.modService.readPassiveSkill(piece) == skillId
        }

    private fun isActiveSkillHosted(player: Player, skillId: String): Boolean {
        if (isPassiveSkillWorn(player, skillId)) return true
        val mainHand = player.inventory.itemInMainHand
        return plugin.modService.installedModIds(mainHand).contains(skillId) ||
            plugin.modService.installedSkillModIds(mainHand).contains(skillId)
    }

    /**
     * 武器持续技能不能跨主手保留：无论新武器是否也装了同一张 MOD，切换时都必须关闭旧状态。
     * 护甲被动技能（如篝火低语）由 [isPassiveSkillWorn] 排除，继续正常生效。
     */
    private fun deactivateWeaponActiveSkills(player: Player) {
        val playerId = player.uniqueId.toString()
        for (skillId in script.api.activeSkillIds()) {
            if (!script.api.isActive(skillId, playerId) || isPassiveSkillWorn(player, skillId)) continue
            script.fireDeactivate(skillId, playerId)
            script.api.deactivate(skillId, player.uniqueId)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHeldItemChange(event: PlayerItemHeldEvent) {
        deactivateWeaponActiveSkills(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHandSwapCompleted(event: org.bukkit.event.player.PlayerSwapHandItemsEvent) {
        deactivateWeaponActiveSkills(event.player)
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
                if (player.isSneaking) {
                    fireTriggerSlot(player, main, TriggerSlot.SHIFT_LEFT, now, pid)
                } else {
                    // 评审#2：MIDAIR 仅作"可选空中覆盖"——须同时【在空中】且【MIDAIR栏已装技能】才走 MIDAIR，
                    // 否则一律 LEFT。这样 isOnGround 抖动/未装空中技能都不会把左键技能静默吞掉。
                    val useMidair = isAirborne(player) &&
                        plugin.modService.skillModAtTrigger(main, TriggerSlot.MIDAIR) != null
                    val slot = if (useMidair) TriggerSlot.MIDAIR else TriggerSlot.LEFT
                    fireTriggerSlot(player, main, slot, now, pid)
                }
            }
            Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK -> {
                // 评审#5：右键交互方块(箱子/门/工作台/按钮等)不触发右键技能，避免开箱/开门即放技能烧蓝烧CD
                val interactBlock = event.action == Action.RIGHT_CLICK_BLOCK &&
                    event.clickedBlock?.type?.isInteractable == true
                if (!interactBlock) {
                    val slot = if (player.isSneaking) TriggerSlot.SHIFT_RIGHT else TriggerSlot.RIGHT
                    fireTriggerSlot(player, main, slot, now, pid)
                }
            }
            else -> Unit
        }

        // 旧：普通槽技能(skill=false)保持原触发（右键 onToggle / 左键 onAttack），向后兼容——仅主手武器。
        val skillIds = skillModsOn(main)
        // 护甲被动技能槽：只关心"打了一下"这个动作，没有 onToggle 概念(装备即生效，见架构说明)，
        // 只在左键(onAttack)一路广播——英雄联盟天赋类MOD(电刑/强攻等)基本都是靠这个钩子实现。
        val armorPassiveIds = passiveSkillIdsWorn(player)
        if (skillIds.isEmpty() && armorPassiveIds.isEmpty()) return
        when (event.action) {
            Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK -> {
                if (skillIds.isEmpty()) return
                if (now - (toggleDebounce[player.uniqueId] ?: 0L) < 300L) return
                toggleDebounce[player.uniqueId] = now
                for (id in skillIds) script.fireToggle(id, pid)
            }
            Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK -> {
                if (now - (attackDebounce[player.uniqueId] ?: 0L) < 100L) return
                attackDebounce[player.uniqueId] = now
                for (id in skillIds) script.fireAttack(id, pid)
                for (id in armorPassiveIds) script.fireAttack(id, pid)
            }
            else -> Unit
        }
    }

    /** 穿戴护甲被动技能槽里、且存在对应JS脚本的技能id集合。 */
    private fun passiveSkillIdsWorn(player: Player): Set<String> {
        val loaded = script.loadedSkillIds()
        val result = HashSet<String>()
        for (piece in player.inventory.armorContents) {
            if (piece == null) continue
            result += plugin.modService.installedPassiveSkillIds(piece).intersect(loaded)
        }
        return result
    }

    /** 触发栏路由：查该栏上安装的技能MOD(须有对应JS技能)，去抖后 fireActivate。 */
    private fun fireTriggerSlot(player: Player, item: org.bukkit.inventory.ItemStack?, trigger: TriggerSlot, now: Long, pid: String) {
        val id = plugin.modService.skillModAtTrigger(item, trigger) ?: return
        if (id !in script.loadedSkillIds()) return
        val key = Triple(player.uniqueId, trigger, id)
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

    /** Shift+F(换手键) → SHIFT_F 触发栏。命中则取消换手，避免误触主副手交换。
     *  护甲不再有主动触发栏（改用被动技能槽，装备即生效，见 tick() 的自动激活逻辑），
     *  这里只需要检查主手武器。 */
    @EventHandler(priority = EventPriority.NORMAL)
    fun onSwapHand(event: org.bukkit.event.player.PlayerSwapHandItemsEvent) {
        val player = event.player
        if (!player.isSneaking) return
        val now = System.currentTimeMillis()
        val pid = player.uniqueId.toString()
        val main = player.inventory.itemInMainHand
        val hasMainSkill = plugin.modService.skillModAtTrigger(main, TriggerSlot.SHIFT_F)?.let { it in script.loadedSkillIds() } == true
        if (hasMainSkill) {
            event.isCancelled = true
            fireTriggerSlot(player, main, TriggerSlot.SHIFT_F, now, pid)
        }
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

        autoActivatePassiveSkills()

        for (skillId in script.api.activeSkillIds()) {
            for (uid in script.api.activePlayers(skillId)) {
                val player = plugin.server.getPlayer(uid)
                if (player == null || !player.isOnline) { script.api.deactivate(skillId, uid); continue }
                if (!isActiveSkillHosted(player, skillId)) {
                    script.fireDeactivate(skillId, uid.toString())
                    script.api.deactivate(skillId, uid)
                    player.sendMessage("§7[技能] §f主手已切换或装备已移除，§e$skillId §f自动关闭")
                    continue
                }
                script.fireTick(skillId, uid.toString())
            }
        }
    }

    /**
     * 护甲被动技能槽没有"按键触发"概念——每秒扫一遍在线玩家穿戴的四件护甲，任何一件被动技能槽里
     * 有对应JS脚本且还没进 active 注册表的，直接 setActive(true)，让它接上现成的 onTick 结算/
     * onDamaged 结算通路。关闭走已有逻辑：tick() 下方主循环发现 isActiveSkillHosted 变 false(卸甲)
     * 就会自动 deactivate，被动技能槽不需要额外的关闭代码。
     */
    private fun autoActivatePassiveSkills() {
        for (player in plugin.server.onlinePlayers) {
            for (piece in player.inventory.armorContents) {
                val id = plugin.modService.readPassiveSkill(piece) ?: continue
                if (id !in script.loadedSkillIds()) continue
                val pid = player.uniqueId.toString()
                if (!script.api.isActive(id, pid)) script.api.setActive(id, pid, true)
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
