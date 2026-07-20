package com.dongzh1.sourceforge.forge

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.mod.TriggerSlot
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Arrow
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.concurrent.ConcurrentHashMap

/**
 * 2026-07-13：弓弩攻击方式分化(用户要求)——弓保留原版拉弓蓄力，SF锻造的弩改成"右键直接射箭"，
 * 不再需要蓄力两段式。冷却 0.2s(config: combat.crossbow-instant-cooldown-seconds)。
 *
 * 必须可被"右键MOD"顶替：这里只在该武器的 TriggerSlot.RIGHT 触发栏没有装技能MOD 时才生效，
 * 装了就整个让位给 SkillModListener.fireTriggerSlot 处理——以后出的弓弩适配MOD都走那条路，
 * 这里的默认直射永远只是"没装右键MOD时的兜底行为"，不写死。
 */
class CrossbowInstantFireListener(private val plugin: SourceForge) : Listener {

    private val cooldown = ConcurrentHashMap<java.util.UUID, Long>()

    private fun cooldownMs(): Long =
        (plugin.config.getDouble("combat.crossbow-instant-cooldown-seconds", 0.2) * 1000.0).toLong().coerceAtLeast(1L)

    @EventHandler(priority = EventPriority.NORMAL)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val player = event.player
        if (player.isSneaking) return // Shift+右键留给别的触发栏(SHIFT_RIGHT)，不在这里拦
        val item = player.inventory.itemInMainHand
        if (item.type != Material.CROSSBOW) return
        if (!plugin.itemService.isSourceEquipment(item)) return
        if (plugin.itemService.weaponCategory(item) != "crossbow") return

        // 右键交互方块(箱子/门/工作台等)不触发，理由同 SkillModListener：避免开箱子顺带打空一箭
        val interactBlock = event.action == Action.RIGHT_CLICK_BLOCK && event.clickedBlock?.type?.isInteractable == true
        if (interactBlock) return

        // 右键触发栏被技能MOD占用：整个让位，不重复触发(以后弩类适配MOD从这里接管)
        if (plugin.modService.skillModAtTrigger(item, TriggerSlot.RIGHT) != null) return

        event.isCancelled = true

        val now = System.currentTimeMillis()
        val last = cooldown[player.uniqueId] ?: 0L
        if (now - last < cooldownMs()) return

        // 2026-07-13 改回原版消耗逻辑(用户明确要求)：正常消耗背包箭矢，无限只走"附魔无限箭矢"——
        // 创造模式/Infinity附魔=不消耗但要求背包至少有1支箭，否则消耗1支；都没有就打不出去(原版同款静默)。
        if (!consumeArrowIfNeeded(player, item)) return
        cooldown[player.uniqueId] = now

        fireArrow(player, item)
    }

    /** 返回是否可以真的发射(背包有箭/创造/无限附魔)；能发射的话顺带扣掉该扣的箭。 */
    private fun consumeArrowIfNeeded(player: Player, weapon: ItemStack): Boolean {
        if (player.gameMode == org.bukkit.GameMode.CREATIVE) return true
        val hasInfinity = weapon.enchantments.keys.any { it == org.bukkit.enchantments.Enchantment.INFINITY }
        val storage = player.inventory.storageContents
        // 原版弩能吃普通箭/附魔箭/光灵箭三种，不能只认 Material.ARROW——否则玩家包里只有药水箭时弩会静默打不出去。
        val slot = storage.indexOfFirst { it != null && it.type in ARROW_MATERIALS }
        if (slot < 0) return false
        if (!hasInfinity) {
            val stack = storage[slot]!!
            stack.amount -= 1
            player.inventory.setItem(slot, if (stack.amount <= 0) null else stack)
        }
        return true
    }

    private fun fireArrow(player: Player, weapon: ItemStack) {
        // 2026-07-13 修复：之前手动 world.spawn()+设置velocity 再拿 Location.setDirection 反推朝向，
        // 实际是把方向向量转了一圈又转回 player 自己原来的 yaw/pitch，等于没变，箭头视觉依旧不对。
        // 改用 Bukkit 官方 ProjectileSource.launchProjectile(class, velocity, consumer)——它是原版
        // 发射体统一入口(NMS shoot 路径)，天然把朝向和速度耦合好，不用自己算旋转。
        val direction = player.eyeLocation.direction.normalize()
        val arrow = player.launchProjectile(Arrow::class.java, direction.multiply(3.0)) { a ->
            // 禁止捡起：允许捡起会让玩家把射出去的箭再捡回来，等于没消耗——变相无限刷箭(用户实测反馈)。
            a.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
        }
        plugin.itemService.markProjectile(arrow, weapon)
        player.world.playSound(player.location, Sound.ITEM_CROSSBOW_SHOOT, 1.0f, 1.0f)
    }

    @EventHandler
    fun onQuit(event: org.bukkit.event.player.PlayerQuitEvent) {
        cooldown.remove(event.player.uniqueId)
    }

    private companion object {
        val ARROW_MATERIALS = setOf(Material.ARROW, Material.TIPPED_ARROW, Material.SPECTRAL_ARROW)
    }
}
