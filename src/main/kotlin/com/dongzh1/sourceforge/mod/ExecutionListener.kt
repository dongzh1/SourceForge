package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.persistence.PersistentDataType

/**
 * 处决本能(execution MOD)——硬编码而非"简单叠加到 SF 内部战斗数值"，理由见 mods/execution.yml
 * 头部注释：效果是条件触发的伤害倍率(目标当前生命值 ≤ 25% 时，本次伤害额外 ×(1+X))，不是恒定加成，
 * ModService.reapplyModEffects 的通用词条聚合模型(无条件叠加进 mod_delta_<affixId>)表达不了
 * "取决于目标此刻状态"这种条件逻辑，只能挂在伤害结算的钩子上现算。
 *
 * 段位缩放沿用项目统一公式 ModConfig.effectAtRank((rank+1)/(maxRank+1) 线性缩放)，
 * mods/execution.yml 的 effects.execute_bonus=0.50 是满段位(rank 4 / 共5级)的封顶值，
 * 5级实际分别为 10%/20%/30%/40%/50%——与 lore 显示(ModLoreBuilder 同样调用 effectAtRank)、
 * 升级预览(ForgeEnhanceMenu)完全一致，不会出现"卡面写的数值和实际生效数值对不上"。
 *
 * 远程武器（applicable-categories 含 bow/crossbow/firearm）：命中判定发生时玩家主手很可能
 * 已经切走弓/弩(箭矢飞行有延迟)，不能像近战那样直接读 `damager.inventory.itemInMainHand`。
 * 这里用自己的 PDC key 在 ProjectileLaunchEvent(开火瞬间)把射手武器上的处决段位快照进弹射物
 * 自身——不复用/不污染 ForgeItemService.markProjectile 的公共词条快照（那份快照只收录已登记
 * 进 affixes.yml 的 affix，execute_bonus 目前未登记，见最终报告），自成一路，独立自洽。
 *
 * 精英/Boss 减半（设计稿要求，防止长血条 Boss 被单卡碾压）：未实现。已检索全代码库
 * (MythicMobsHook / MythicItemHook / MythicDamageAmpListener / SourceForgeMMPlaceholders 等)
 * 与服务器现有 MythicMobs 怪物配置(Beta_Server/plugins/MythicMobs/Mobs 等目录)，均没有找到
 * 任何统一的"精英/Boss"标记约定——既没有专门的 API 判定函数，怪物 yml 里也没有一致的命名/
 * 自定义变量规范可供匹配。贸然猜一个匹配规则（比如按内部类型名含"boss"/"elite"关键字）风险是
 * "看起来做了防护、实际匹配不到服务器上任何一只真实怪物"，比明确不做更容易造成误判——
 * 因此按任务说明的兜底方案，先不做这个特殊处理，全额加成对任何目标(含 Boss)一视同仁生效。
 * 如果以后要补：可以用 io.lumine.mythic.bukkit.MythicBukkit.inst().getAPIHelper()
 * .getMythicMobInstance(entity).getMobType() 反射取内部类型名，按关键字/白名单表判断后对
 * bonus 做 *0.5，写法可参考 [MythicDamageAmpListener] 的反射接入方式。
 */
class ExecutionListener(private val plugin: SourceForge) : Listener {

    /** 弹射物自身 PDC：开火瞬间快照的处决段位(Int)。 */
    private val rankKey = NamespacedKey(plugin, "sf_execution_rank")

    @EventHandler(ignoreCancelled = true)
    fun onLaunch(event: ProjectileLaunchEvent) {
        val projectile = event.entity
        val player = projectile.shooter as? Player ?: return
        val rank = plugin.modService.installedModRank(player.inventory.itemInMainHand, MOD_ID) ?: return
        projectile.persistentDataContainer.set(rankKey, PersistentDataType.INTEGER, rank)
    }

    /**
     * HIGHEST 优先级：必须晚于 ForgeListener.onDamage 把 SF 武器伤害/护甲减免结算完，
     * 乘的才是"这一下真正要扣的血量"，而不是被 ForgeListener 覆盖前的原始事件伤害。
     * 与 ElementDamageListener 谁先谁后不影响最终结果(纯乘法满足交换律)，但本类必须在
     * SourceForge.enable 里注册在 ForgeListener 之后(见最终报告的注册顺序要求)。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? LivingEntity ?: return
        if (event.damage <= 0.0) return
        val rank = resolveRank(event.damager) ?: return
        val config = plugin.modService.mods[MOD_ID] ?: return
        val bonus = config.effectAtRank(AFFIX_ID, rank)
        if (bonus <= 0.0) return

        val maxHealth = victim.getAttribute(Attribute.MAX_HEALTH)?.value ?: return
        if (maxHealth <= 0.0) return
        // event 触发时伤害尚未结算到 health 上，victim.health 就是"这一击落地前"的当前生命值。
        val ratio = (victim.health / maxHealth).coerceIn(0.0, 1.0)
        if (ratio > EXECUTE_THRESHOLD) return

        event.damage *= (1.0 + bonus)
    }

    private fun resolveRank(damager: Entity): Int? = when (damager) {
        is Player -> plugin.modService.installedModRank(damager.inventory.itemInMainHand, MOD_ID)
        is Projectile -> damager.persistentDataContainer.get(rankKey, PersistentDataType.INTEGER)
        else -> null
    }

    companion object {
        private const val MOD_ID = "execution"
        private const val AFFIX_ID = "execute_bonus"
        private const val EXECUTE_THRESHOLD = 0.25
    }
}
