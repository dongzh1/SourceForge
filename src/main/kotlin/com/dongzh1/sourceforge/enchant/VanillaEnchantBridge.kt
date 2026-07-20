package com.dongzh1.sourceforge.enchant

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent.DamageCause

/**
 * 原版附魔战斗桥接，分两类问题：
 *
 * 1. 【技能命中补算】锋利(Sharpness)/横扫之刃(Sweeping Edge)/亡灵杀手(Smite)/截肢杀手
 *    (Bane of Arthropods)/火焰附加(Fire Aspect) 的原版加成只在【真实挥砍】（客户端触发的近战攻击，
 *    NMS 在构造伤害事件前就已算好）时自动生效：额外伤害早已折算进 `EntityDamageByEntityEvent.damage`，
 *    ForgeListener 直接拿这个数字当 `incomingDamage` 参与暴击/元素结算，天然正确，不需要额外代码。
 *    但 SF 的技能命中（[com.dongzh1.sourceforge.particle.ParticleEmitter] 粒子轮子、MythicMobs 技能
 *    amount）都是脚本直接调用 `LivingEntity.damage(amount, caster)`，绕开了那条 NMS 自动结算路径——
 *    这几个附魔对技能命中形同虚设。[meleeBonusDamage]/[igniteIfFireAspect] 只在【那些绕开路径的命中点】
 *    手动补一次，真实挥砍绝不能再调用，否则双重计算。锋利/横扫按设计决策简化为"每次命中固定加成"
 *    （锋利用原版公式，横扫简化为固定每级伤害、不还原范围扫击），Smite/Bane 保留原版"对特定生物分类
 *    才生效"的条件判定。
 *
 * 2. 【防御结算补算】保护(Protection)家族(含烈焰/爆炸/弹射物保护、摔落保护)对玩家完全失效——SF 对
 *    玩家会清空原版全部减伤阶段(含 ARMOR 阶段)换成自研攻防公式，而这些附魔的减伤原版就折算在 ARMOR
 *    阶段。[armorDamageMultiplier] 绕开 Bukkit 的 DamageModifier，直接读护甲附魔等级自己算，在
 *    ForgeListener.applyDefense 算完自研公式后再乘一层，两层独立生效，顺序与原版真实计算顺序一致。
 *    荆棘(Thorns)是主动反伤 proc，不落在增伤/减伤任何一类，走独立的 [ThornsListener]。
 */
object VanillaEnchantBridge {

    /** 原版"不死系"判定（对应 Smite 生效范围）。 */
    private val UNDEAD = setOf(
        EntityType.ZOMBIE, EntityType.ZOMBIE_VILLAGER, EntityType.HUSK, EntityType.DROWNED,
        EntityType.SKELETON, EntityType.STRAY, EntityType.WITHER_SKELETON,
        EntityType.ZOMBIFIED_PIGLIN, EntityType.WITHER, EntityType.ZOGLIN, EntityType.PHANTOM
    )

    /** 原版"节肢系"判定（对应 Bane of Arthropods 生效范围）。 */
    private val ARTHROPOD = setOf(
        EntityType.SPIDER, EntityType.CAVE_SPIDER, EntityType.SILVERFISH, EntityType.ENDERMITE
    )

    /**
     * [attacker] 主手武器上这几个附魔对命中 [target] 的总额外伤害：
     * 锋利(无条件，原版公式 level×0.5+0.5) + 横扫之刃(无条件，配置每级加成) +
     * 亡灵杀手(仅不死系) / 截肢杀手(仅节肢系，与目标生物分类互斥，原版数值口径一致)。
     */
    fun meleeBonusDamage(plugin: SourceForge, attacker: Player, target: LivingEntity): Double {
        val cfg = plugin.forgeConfig.enchantBridge
        val weapon = attacker.inventory.itemInMainHand
        val sharpnessLevel = weapon.getEnchantmentLevel(Enchantment.SHARPNESS)
        val sharpnessBonus = if (sharpnessLevel > 0) sharpnessLevel * 0.5 + 0.5 else 0.0
        val sweepingBonus = weapon.getEnchantmentLevel(Enchantment.SWEEPING_EDGE) * cfg.sweepingEdgeBonusPerLevel
        val categoryBonus = when (target.type) {
            in UNDEAD -> weapon.getEnchantmentLevel(Enchantment.SMITE) * cfg.smiteBonusPerLevel
            in ARTHROPOD -> weapon.getEnchantmentLevel(Enchantment.BANE_OF_ARTHROPODS) * cfg.baneBonusPerLevel
            else -> 0.0
        }
        return sharpnessBonus + sweepingBonus + categoryBonus
    }

    /** 火焰附加：若 [attacker] 主手武器带该附魔，点燃 [target]（时长=等级×配置的每级 tick 数，取更长者不覆盖已有燃烧）。 */
    fun igniteIfFireAspect(plugin: SourceForge, attacker: Player, target: LivingEntity) {
        val cfg = plugin.forgeConfig.enchantBridge
        val level = attacker.inventory.itemInMainHand.getEnchantmentLevel(Enchantment.FIRE_ASPECT)
        if (level <= 0) return
        target.fireTicks = maxOf(target.fireTicks, level * cfg.fireAspectTicksPerLevel)
    }

    /**
     * 护甲防护家族(保护/烈焰保护/爆炸保护/弹射物保护/摔落保护)对 [target] 承受 [cause] 类型伤害的
     * 减伤倍率(0..1，乘算，与原版真实计算顺序一致——先算 SF 自己的攻防公式，再乘这一层)。
     *
     * SF 对玩家会清空原版全部减伤阶段(含 ARMOR 阶段)换成自研攻防公式(见 ForgeListener.applyDefense)，
     * 而原版"保护"类附魔的减伤原版就是折算在 ARMOR 阶段里的——SF 一换公式，这几个附魔就跟着失效了。
     * 这里绕开 Bukkit 的 DamageModifier，直接从 4 件护甲上读附魔等级自己算 EPF(附魔保护等级，保护1/级、
     * 烈焰/爆炸/弹射物保护2/级、摔落保护3/级，权重比例与原版一致)，按【数值策划的下调公式】(每点 EPF
     * 减 [EnchantBridgeConfig.epfReductionPerPoint]，硬顶 [EnchantBridgeConfig.epfMaxReduction]，默认
     * 2%/点、封顶30%——原版是4%/点封顶80%，这一层单独腰斩过一轮再砍顶，2026-07-08 数值策划要求"护甲
     * 叠满只能有30%减免")算出倍率，乘在 SF 公式的结果上。
     *
     * 简化：保护(通用)对所有伤害类型生效(原版对虚空/饥饿等极少数类型不生效，这里不做例外，影响可忽略)。
     */
    fun armorDamageMultiplier(plugin: SourceForge, target: LivingEntity, cause: DamageCause): Double {
        val cfg = plugin.forgeConfig.enchantBridge
        var epf = 0
        for (piece in target.equipment?.armorContents.orEmpty()) {
            val item = piece ?: continue
            epf += item.getEnchantmentLevel(Enchantment.PROTECTION)
            if (cause == DamageCause.FIRE || cause == DamageCause.FIRE_TICK ||
                cause == DamageCause.LAVA || cause == DamageCause.HOT_FLOOR
            ) {
                epf += item.getEnchantmentLevel(Enchantment.FIRE_PROTECTION) * 2
            }
            if (cause == DamageCause.BLOCK_EXPLOSION || cause == DamageCause.ENTITY_EXPLOSION) {
                epf += item.getEnchantmentLevel(Enchantment.BLAST_PROTECTION) * 2
            }
            if (cause == DamageCause.PROJECTILE) {
                epf += item.getEnchantmentLevel(Enchantment.PROJECTILE_PROTECTION) * 2
            }
            if (cause == DamageCause.FALL) {
                epf += item.getEnchantmentLevel(Enchantment.FEATHER_FALLING) * 3
            }
        }
        val reduction = (epf * cfg.epfReductionPerPoint).coerceIn(0.0, cfg.epfMaxReduction)
        return 1.0 - reduction
    }
}
