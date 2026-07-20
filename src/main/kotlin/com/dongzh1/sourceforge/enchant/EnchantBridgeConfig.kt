package com.dongzh1.sourceforge.enchant

import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.enchantments.Enchantment

/**
 * 原版附魔白名单桥接（enchants.yml）。
 *
 * SF 装备可以正常附魔——获取渠道是【村民交易(附魔书) + 铁砧】，附魔台对 SF 装备维持禁用（见
 * [SourceEnchantListener]）。默认放行除"两个诅咒"外的全部原版附魔：诅咒对锻造出来的装备只有负面
 * 效果（消失诅咒烧毁装备、绑定诅咒纯粹添堵），其余附魔和 SF 自身的词条/MOD/技能系统不冲突——
 * SF 有自己的暴击/元素/护盾/技能体系，原版附魔是叠加在其上的"原版增益层"，两者独立生效。
 *
 * 战斗系统对这些附魔效果的识别方式（见 [VanillaEnchantBridge] / [LootingListener]）：
 * - 输出向(锋利/横扫/亡灵杀手/截肢杀手/火焰附加/抢夺)：真实挥砍原版自动结算，SF 只补技能命中那部分。
 * - 防御向(保护/烈焰保护/爆炸保护/弹射物保护/摔落保护)：SF 对玩家会清空原版全部减伤阶段换成自研攻防
 *   公式，这里在公式算完后再叠一层原版 EPF(附魔保护等级)减伤，两层独立乘算，与原版真实计算顺序一致。
 * - 荆棘：反伤是主动 proc 效果，不落在"减伤"或"增伤"任何一类里，走独立的 ThornsListener。
 * - 其余(水下速掘/深海探索/丝绸之手/精准采集/经验修补等)：不碰 SF 的伤害管线，放行后原版自动生效。
 */
data class EnchantBridgeConfig(
    /** 允许的附魔 -> 允许的最高等级；不在此表中的原版附魔一律被清除（见 ForgeItemService.stripVanillaEnchantments）。 */
    val allowed: Map<Enchantment, Int>,
    /** 抢夺：每层为死亡掉落列表中的每一条独立判定 +1 份额外掉落的概率（贴近原版"额外判定"机制，非整体翻倍）。 */
    val lootingExtraRollChancePerLevel: Double,
    /** 亡灵杀手：对不死系目标每级额外伤害（与原版数值一致）。 */
    val smiteBonusPerLevel: Double,
    /** 截肢杀手：对节肢系目标每级额外伤害（与原版数值一致）。 */
    val baneBonusPerLevel: Double,
    /** 火焰附加：每级点燃时长(tick, 20=1秒)，与原版一致。 */
    val fireAspectTicksPerLevel: Int,
    /**
     * 横扫之刃：简化为固定"每级额外伤害"（不还原原版的横扫范围AoE机制）。原版横扫的真实扫击效果
     * 对普通挥砍会自然生效(NMS 自动结算，见 VanillaEnchantBridge 顶部说明)；这里只补技能命中那部分，
     * 按用户设计决策(2026-07-08：锋利/横扫简单视为基础伤害增加属性)统一走"每级加成"公式。
     */
    val sweepingEdgeBonusPerLevel: Double,
    /**
     * 护甲防护家族(保护/烈焰/爆炸/弹射物保护、摔落保护)每点 EPF(附魔保护等级) 的减伤比例。
     * 原版是固定 4%/点、封顶20点=最多减80%；数值策划要求下调(2026-07-08：护甲叠满只能有30%减免)，
     * 改为可配置的"每点减伤%" + 独立的硬顶(见 [epfMaxReduction])，两者共同决定曲线。
     */
    val epfReductionPerPoint: Double,
    /**
     * 护甲防护层的减伤硬顶（不管 EPF 堆多高、堆几种类型混合，这一层最多减免这么多）。
     * 默认 0.30：标准"满配"(4件保护IV=16 EPF ×2%/点=32%)刚好卡在这条线附近，任何附魔组合都不可能超过。
     */
    val epfMaxReduction: Double
) {
    fun isAllowed(enchant: Enchantment): Boolean = enchant in allowed

    fun capLevel(enchant: Enchantment, level: Int): Int = level.coerceIn(0, allowed[enchant] ?: 0)

    companion object {
        /** 默认拒绝：只有这两个诅咒——对锻造装备纯负面，其余原版附魔一律放行到各自的原版最高等级。 */
        private val DEFAULT_DENIED = setOf(Enchantment.VANISHING_CURSE, Enchantment.BINDING_CURSE)

        private fun defaultAllowed(): Map<Enchantment, Int> {
            val map = linkedMapOf<Enchantment, Int>()
            @Suppress("DEPRECATION")
            for (enchant in Registry.ENCHANTMENT) {
                if (enchant in DEFAULT_DENIED) continue
                map[enchant] = enchant.maxLevel
            }
            return map
        }

        fun load(config: FileConfiguration): EnchantBridgeConfig {
            val allowed = linkedMapOf<Enchantment, Int>()
            val section = config.getConfigurationSection("allowed")
            if (section == null) {
                allowed.putAll(defaultAllowed())
            } else {
                for (key in section.getKeys(false)) {
                    @Suppress("DEPRECATION")
                    val enchant = Enchantment.getByKey(NamespacedKey.minecraft(key.trim().lowercase())) ?: continue
                    val maxLevel = config.getInt("allowed.$key", enchant.maxLevel)
                    allowed[enchant] = maxLevel.coerceIn(1, enchant.maxLevel)
                }
                if (allowed.isEmpty()) allowed.putAll(defaultAllowed())
            }
            return EnchantBridgeConfig(
                allowed = allowed,
                lootingExtraRollChancePerLevel = config.getDouble("looting.extra-roll-chance-per-level", 0.25),
                smiteBonusPerLevel = config.getDouble("smite-bonus-per-level", 2.5),
                baneBonusPerLevel = config.getDouble("bane-bonus-per-level", 2.5),
                fireAspectTicksPerLevel = config.getInt("fire-aspect-ticks-per-level", 80),
                sweepingEdgeBonusPerLevel = config.getDouble("sweeping-edge-bonus-per-level", 1.0),
                epfReductionPerPoint = config.getDouble("armor-protection.reduction-per-epf-point", 0.02),
                epfMaxReduction = config.getDouble("armor-protection.max-reduction", 0.30).coerceIn(0.0, 1.0)
            )
        }
    }
}
