package com.dongzh1.sourceforge.status

import org.bukkit.Particle
import org.bukkit.configuration.file.FileConfiguration

/** 单个元素的全部可调参数（来自 elements.yml）。 */
data class ElementDef(
    val type: ElementType,
    /** 对应的装备/MOD 属性 id（仅基础元素有意义，组合元素由基础融合得到）。 */
    val affix: String,
    val durationMs: Long,
    /** 行为类型，决定结算方式。 */
    val effect: ElementEffect,
    /** DOT/GAS：每层每秒伤害。 */
    val dotPerStack: Double,
    /** BURST：叠层瞬时伤害/层。 */
    val burstPerStack: Double,
    /** BURST/KNOCKBACK：触发时硬直时长（tick）。 */
    val stunTicks: Int,
    /** AMP：每层提升怪受到的伤害比例（病毒/腐蚀/磁力）。 */
    val ampPerStack: Double,
    /** GAS/CONFUSE/BURST(连锁)：作用半径（格）。 */
    val radius: Double,
    /** KNOCKBACK：击退强度。 */
    val knockback: Double,
    /** SLOW：每多少层 +1 级缓慢。 */
    val slowStacksPerLevel: Int,
    /** SLOW：缓慢最高等级。 */
    val slowMaxAmp: Int,
    /** 粒子（视觉辨识，原版 spawnParticle，低带宽）。 */
    val particle: Particle?,
    /** 悬浮符号（Phase 2 发包用）。 */
    val symbol: String,
    /** 符号颜色 ARGB（Phase 2 发包用）。 */
    val colorArgb: Int
)

/**
 * 元素异常系统配置。reloadAll() 每次重建，StatusEffectManager 每 tick 读 plugin.elementConfig，故热重载安全。
 */
class ElementConfig(
    val enabled: Boolean,
    val stackCap: Int,
    val tickPeriod: Long,
    val directDamageFactor: Double,
    val visualEnabled: Boolean,
    val symbolRadius: Double,
    val particleCount: Int,
    /** 内置触发 CD（毫秒）：同一(攻击者→怪)在此时间内只触发一次元素，防双重触发/高频多段刷爆。 */
    val triggerCooldownMs: Long,
    private val defs: Map<ElementType, ElementDef>
) {
    fun element(t: ElementType): ElementDef? = defs[t]

    /** 当前生效（有定义）的元素列表，按枚举顺序。 */
    val active: List<ElementDef> get() = ElementType.entries.mapNotNull { defs[it] }

    /**
     * 把武器上的基础元素值融合成最终要触发的元素列表（Warframe 式）：
     * 按 COMBOS 优先级贪心配对，成对的两个基础消耗为组合元素（值=两者之和），剩余基础保持原样。
     * 未在配置中启用的组合不会形成。
     */
    fun combine(baseValues: Map<ElementType, Double>): List<Pair<ElementType, Double>> {
        val avail = HashMap<ElementType, Double>()
        for ((t, v) in baseValues) if (t.isBase && v > 0.0) avail[t] = v
        val result = ArrayList<Pair<ElementType, Double>>()
        for ((combo, a, b) in ElementType.COMBOS) {
            if (defs[combo] == null) continue
            val va = avail[a]
            val vb = avail[b]
            if (va != null && vb != null) {
                result.add(combo to (va + vb))
                avail.remove(a)
                avail.remove(b)
            }
        }
        for ((t, v) in avail) result.add(t to v)
        return result
    }

    companion object {
        fun load(cfg: FileConfiguration): ElementConfig {
            val enabled = cfg.getBoolean("enabled", true)
            val stackCap = cfg.getInt("stack-cap", 10).coerceAtLeast(1)
            val tickPeriod = cfg.getLong("tick-period", 10L).coerceAtLeast(1L)
            val directFactor = cfg.getDouble("direct-damage-factor", 1.0)
            val vis = cfg.getConfigurationSection("visual")
            val visEnabled = vis?.getBoolean("enabled", true) ?: true
            val radius = vis?.getDouble("symbol-radius", 24.0) ?: 24.0
            val pCount = (vis?.getInt("particle-count", 4) ?: 4).coerceAtLeast(0)
            val triggerCdMs = cfg.getLong("trigger-cooldown-ms", 150L).coerceAtLeast(0L)

            val defs = LinkedHashMap<ElementType, ElementDef>()
            val sec = cfg.getConfigurationSection("elements")
            if (sec != null) {
                for (type in ElementType.entries) {
                    val e = sec.getConfigurationSection(type.id) ?: continue
                    val affix = e.getString("affix", "${type.id}_damage")!!
                    val duration = (e.getDouble("duration", 6.0) * 1000.0).toLong().coerceAtLeast(0L)
                    val effect = ElementEffect.fromId(e.getString("effect")) ?: ElementEffect.DOT
                    val dot = e.getDouble("dot-per-stack", 0.0)
                    val burst = e.getDouble("burst-per-stack", 0.0)
                    val stun = e.getInt("stun-ticks", 0).coerceAtLeast(0)
                    val amp = e.getDouble("amp-per-stack", 0.0)
                    val rad = e.getDouble("radius", 0.0)
                    val knock = e.getDouble("knockback", 0.0)
                    val slowPer = e.getInt("slow-stacks-per-level", 2).coerceAtLeast(1)
                    val slowMax = e.getInt("slow-max-amplifier", 4).coerceAtLeast(0)
                    val particle = e.getString("particle")?.let {
                        runCatching { Particle.valueOf(it.uppercase()) }.getOrNull()
                    }
                    val symbol = e.getString("symbol", "*")!!
                    val colorArgb = parseColor(e.getString("color"))
                    defs[type] = ElementDef(
                        type, affix, duration, effect, dot, burst, stun, amp, rad, knock,
                        slowPer, slowMax, particle, symbol, colorArgb
                    )
                }
            }
            return ElementConfig(enabled, stackCap, tickPeriod, directFactor, visEnabled, radius, pCount, triggerCdMs, defs)
        }

        fun disabled(): ElementConfig =
            ElementConfig(false, 10, 10L, 1.0, false, 24.0, 4, 150L, emptyMap())

        private fun parseColor(raw: String?): Int {
            if (raw.isNullOrBlank()) return 0xFFFFFFFF.toInt()
            val s = raw.trim()
            if (s.startsWith("#")) {
                val rgb = s.substring(1).toIntOrNull(16) ?: return 0xFFFFFFFF.toInt()
                return (0xFF000000.toInt()) or (rgb and 0xFFFFFF)
            }
            val rgb = when (s.uppercase()) {
                "RED" -> 0xFF5555
                "GREEN" -> 0x55FF55
                "AQUA", "CYAN" -> 0x55FFFF
                "YELLOW" -> 0xFFFF55
                "BLUE" -> 0x5555FF
                "LIGHT_PURPLE", "PINK", "MAGENTA" -> 0xFF55FF
                "GOLD", "ORANGE" -> 0xFFAA00
                "WHITE" -> 0xFFFFFF
                else -> 0xFFFFFF
            }
            return (0xFF000000.toInt()) or rgb
        }
    }
}
