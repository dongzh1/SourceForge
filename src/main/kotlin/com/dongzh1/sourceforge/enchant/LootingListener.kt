package com.dongzh1.sourceforge.enchant

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import kotlin.random.Random

/**
 * 抢夺(Looting) 掉落桥接。
 *
 * 服务器怪物全靠 MythicMobs 生成，MM 的自定义 Drops 表不读原版抢夺附魔——原版"死亡时按抢夺等级
 * 自动多判定几次"的机制对这类怪完全不生效，附魔挂了也是摆设。这里在死亡结算里手动补算：每层抢夺，
 * 对死亡掉落列表里的每一条独立判定一次"+1 份额外掉落"的概率（默认 25%/层），贴近原版"额外判定
 * 次数"的直觉，而不是粗暴地整体翻倍。不检查击杀者是否手持 SF 装备——普通原版武器上的抢夺同样吃得到
 * 这个补算，因为问题根源(MM 掉落表不读原版附魔)与武器来源无关。
 *
 * 优先级 NORMAL，早于 [com.dongzh1.sourceforge.mod.SkillModListener] 的摸尸判定(HIGH)：抢夺先把
 * 基础掉落表"卷"出来，摸尸的范围死亡翻倍再对这份已经含抢夺加成的列表生效——两者是相乘关系，不互斥。
 */
class LootingListener(private val plugin: SourceForge) : Listener {

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onDeath(event: EntityDeathEvent) {
        if (event.entity is Player || event.drops.isEmpty()) return
        val killer = event.entity.killer ?: return
        val level = killer.inventory.itemInMainHand.getEnchantmentLevel(Enchantment.LOOTING)
        if (level <= 0) return
        val chance = (plugin.forgeConfig.enchantBridge.lootingExtraRollChancePerLevel * level).coerceIn(0.0, 1.0)
        val bonus = event.drops.filter { Random.nextDouble() < chance }.map { it.clone() }
        if (bonus.isNotEmpty()) event.drops.addAll(bonus)
    }
}
