package com.dongzh1.sourceforge.status

/**
 * 元素类型（Warframe 简化版）。
 * 基础 4 种：火/冰/毒/电。组合 6 种：两种基础同时在武器上会融合成对应高级元素（消耗两个基础）。
 * 具体数值/效果由 elements.yml 驱动，这里只定义种类、id、以及组合配对表。
 */
enum class ElementType(val id: String) {
    // 基础
    HEAT("heat"),
    COLD("cold"),
    TOXIN("toxin"),
    ELECTRIC("electric"),
    // 组合
    BLAST("blast"),        // 火+冰：击退
    GAS("gas"),            // 火+毒：毒云 AoE
    RADIATION("radiation"),// 火+电：混乱
    VIRAL("viral"),        // 冰+毒：增伤
    MAGNETIC("magnetic"),  // 冰+电：减速+增伤
    CORROSIVE("corrosive");// 毒+电：护甲熔解

    val isBase: Boolean get() = this in BASES

    companion object {
        val BASES = listOf(HEAT, COLD, TOXIN, ELECTRIC)

        /**
         * 组合配对表（组合元素, 基础A, 基础B），按从上到下的优先级贪心配对：
         * 多元素时先满足靠前的组合，配对成功即消耗这两个基础，不再参与后续配对。
         * 病毒/腐蚀（强力增伤/穿甲）优先级最高。
         */
        val COMBOS: List<Triple<ElementType, ElementType, ElementType>> = listOf(
            Triple(VIRAL, COLD, TOXIN),
            Triple(CORROSIVE, TOXIN, ELECTRIC),
            Triple(RADIATION, HEAT, ELECTRIC),
            Triple(GAS, HEAT, TOXIN),
            Triple(MAGNETIC, COLD, ELECTRIC),
            Triple(BLAST, HEAT, COLD)
        )

        fun fromId(s: String?): ElementType? = entries.firstOrNull { it.id.equals(s, true) }
    }
}

/** 元素异常的行为类型，决定 StatusEffectManager 如何结算。 */
enum class ElementEffect(val id: String) {
    DOT("dot"),                 // 每层每秒伤害（火/毒）
    SLOW("slow"),               // 减速（冰/磁力）
    BURST("burst"),             // 叠层瞬时伤害 + 硬直（+电的连锁）
    AMP("amp"),                 // 提升怪受到的伤害（病毒/腐蚀/磁力），在命中结算时放大
    KNOCKBACK("knockback"),     // 击退 + 踉跄（爆裂）
    GAS("gas"),                 // DoT + 波及周围怪（毒气）
    CONFUSE("confuse"),         // 让怪攻击附近其它怪（辐射）
    PULL("pull");               // 把周围怪拉向目标（磁力·聚怪）

    companion object {
        fun fromId(s: String?): ElementEffect? = entries.firstOrNull { it.id.equals(s, true) }
    }
}
