package com.dongzh1.sourceforge.forge

import com.dongzh1.sourceforge.SourceForge
import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 轻甲套装（疾风 gale_ / 织法秘纹 ward_ 系列）常驻速度增益。
 *
 * 数值侧设计（见 数值策划/15_防具与护甲设计.md）：轻甲主词条打七折换取"常驻速度"，
 * 移速走原版 Potion Effect 而非 SF 的 affix 系统，因此不走 ForgeItemService 的词缀读取，
 * 而是照 InventoryAttributeListener 的事件监听 + 延迟一 tick 重算模式，另起一个小监听器。
 *
 * 判定为头/胸/腿/脚四件 SF 装备全部是"轻甲"（equipment id 以 gale_/ward_ 开头）才生效，
 * 按整套而非按件计分——避免"3 件重甲 + 1 件轻甲"零成本蹭速度，重甲/轻甲应是整体取舍。
 * 若之后想改成按件数比例加成，只需改 sync() 里的判定。
 *
 * granted 集合只记录"这份 SPEED 是不是本监听器加的"，脱下轻甲套时清除。但 PotionEffectType.SPEED
 * 是原版共享的单一槽位——玩家满轻甲期间自己喝真实速度药水，会把这份效果覆盖掉，此时 granted 仍记着
 * "我方给过"，脱甲时不能无脑 removePotionEffect，否则会把玩家真实喝下的药水效果也一起摘掉。用
 * "剩余时长是否还很长"来判断当前生效的 SPEED 是不是我们自己的：本监听器每 [REFRESH_PERIOD_TICKS]
 * 就靠 start() 的周期任务重新续满 [DURATION_TICKS]，原版能拿到的速度药水最长(延展喷溅/滞留)也就
 * 几分钟，远小于我们的门槛 [OWN_EFFECT_MIN_DURATION]，两者不会混淆。
 *
 * 周期续期同时解决另一个问题：旧版只在装备变化/上线/重生时刷新，玩家穿满整局挂机不换装备超过
 * DURATION_TICKS(约83分钟)后，效果会自然到期而没有任何事件触发重新赋予，速度buff静默消失。
 */
class LightArmorSpeedListener(
    private val plugin: SourceForge
) : Listener {
    private val granted: MutableSet<UUID> = Collections.newSetFromMap(ConcurrentHashMap())
    private var refreshTask: org.bukkit.scheduler.BukkitTask? = null

    /** 启动周期续期任务：只给当前记录在 granted 里的玩家重新 sync()，续满常驻时长。 */
    fun start() {
        refreshTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            for (player in plugin.server.onlinePlayers) {
                if (granted.contains(player.uniqueId)) sync(player)
            }
        }, REFRESH_PERIOD_TICKS, REFRESH_PERIOD_TICKS)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        syncLater(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        granted.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onArmorChange(event: PlayerArmorChangeEvent) {
        syncLater(event.player)
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        syncLater(event.player)
    }

    private fun syncLater(player: Player) {
        plugin.server.scheduler.runTask(plugin, Runnable { sync(player) })
    }

    private fun sync(player: Player) {
        val inv = player.inventory
        val isFullLightSet = listOf(inv.helmet, inv.chestplate, inv.leggings, inv.boots)
            .all { isLightArmor(it) }
        if (isFullLightSet) {
            player.addPotionEffect(
                PotionEffect(PotionEffectType.SPEED, DURATION_TICKS, AMPLIFIER, true, false, false)
            )
            granted.add(player.uniqueId)
        } else if (granted.remove(player.uniqueId)) {
            // 只清"看起来像我们自己发的"那份 SPEED（剩余时长够长）；剩余时长短说明当前生效的是
            // 玩家自己喝的真实速度药水(或早被覆盖过)，不是我们的，不能碰。
            val active = player.getPotionEffect(PotionEffectType.SPEED)
            if (active != null && active.amplifier == AMPLIFIER && active.duration >= OWN_EFFECT_MIN_DURATION) {
                player.removePotionEffect(PotionEffectType.SPEED)
            }
        }
    }

    private fun isLightArmor(item: ItemStack?): Boolean {
        if (item == null) return false
        val id = plugin.itemService.equipmentConfig(item)?.id ?: return false
        return id.startsWith("gale_") || id.startsWith("ward_")
    }

    private companion object {
        const val DURATION_TICKS = 100000 // 常驻：周期续期 + 每次装备变化事件都会重新触发刷新
        const val AMPLIFIER = 0           // Speed I（+20% 移速）
        const val REFRESH_PERIOD_TICKS = 6000L // 5 分钟续期一次，远小于 DURATION_TICKS，绝不会中途过期
        // 原版可正常获取的速度药水(含延展/升级)最长在一万 tick 以内，这里留足安全边界判定"是不是我们发的"。
        const val OWN_EFFECT_MIN_DURATION = 50000
    }
}
