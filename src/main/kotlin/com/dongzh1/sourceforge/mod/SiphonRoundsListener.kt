package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent

/**
 * 汲取导管(siphon_rounds MOD)——武器造成伤害后，按段位缩放的比例把本次【实际】伤害转化为佩戴者自身回血。
 *
 * 挂在 MONITOR 优先级：ForgeListener.onDamage 已在 HIGHEST 优先级完成暴击/元素/护盾吸收/护甲减伤的
 * 全部结算，此时 event.finalDamage 就是即将真正扣到目标身上的血量——用它而不是原始传入伤害，
 * 既符合"造成伤害的X%"这个描述(护盾吸收掉的部分不算"打到了")，也不需要重新走一遍战斗公式。
 * MONITOR 阶段只读不改 event.damage，纯粹作为旁观者触发回血副作用，不干扰原有伤害结算链路。
 *
 * 武器来源判定：近战直接用 `damager as Player`；弓/弩/枪械等弹射物伤害用 `damager.shooter as Player`，
 * 统一都读该玩家【当前主手】物品上的MOD段位——弹射物本身只在 PDC 里拷贝了 forgeConfig.affixes 登记过的
 * 词条数值(见 ForgeItemService.markProjectile)，不会拷贝 mod_installed 这份安装记录，所以无法从弹射物
 * 反查MOD段位；退而求其次用"命中那一刻玩家主手拿的是什么"近似，多数情况下(尤其瞬时弹道的弩/枪)
 * 玩家在箭矢/弹丸命中前不会中途换手，误差可接受——这是这个实现的一个已知简化，已在报告里注明。
 *
 * 回血只加到 player.health（原版生命值），不经过 SF 自己的 ShieldService 护盾池——效果描述里
 * "不回护盾"就是这个意思：吸血永远只回真实生命值，且用 coerceAtMost(maxHealth) 卡住生命上限。
 */
class SiphonRoundsListener(private val plugin: SourceForge) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val target = event.entity as? LivingEntity ?: return
        val dealt = event.finalDamage
        if (dealt <= 0.0) return

        val player = resolveAttacker(event) ?: return
        if (player === target) return // 理论上不该出现自伤触发吸血，稳妥起见显式排除

        val weapon = player.inventory.itemInMainHand
        val rank = plugin.modService.installedModRank(weapon, MOD_ID) ?: return
        val config = plugin.modService.mods[MOD_ID] ?: return
        val ratio = config.effectAtRank(AFFIX_ID, rank)
        if (ratio <= 0.0) return

        val heal = dealt * ratio
        if (heal <= 0.0) return

        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: player.health
        if (player.health >= maxHealth) return
        player.health = (player.health + heal).coerceAtMost(maxHealth)
    }

    /** 近战：damager 本身就是玩家；弹射物：追溯 shooter。其他伤害源(TNT/雷等)不触发。 */
    private fun resolveAttacker(event: EntityDamageByEntityEvent): Player? {
        return when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        }
    }

    companion object {
        private const val MOD_ID = "siphon_rounds"
        private const val AFFIX_ID = "life_steal"
    }
}
