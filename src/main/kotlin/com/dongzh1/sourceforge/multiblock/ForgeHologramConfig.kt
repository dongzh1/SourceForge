package com.dongzh1.sourceforge.multiblock

import org.bukkit.configuration.file.FileConfiguration

/**
 * 锻炉作业穿墙悬浮字（发包 Text Display）参数，来自 config.yml 的 `forge-structure.hologram` 段。
 * 见 [ForgeJobHologramRenderer]。
 */
data class ForgeHologramConfig(
    val enabled: Boolean,
    /** 悬浮字刷新周期(tick)。倒计时按整数秒显示，不需要很高频率。 */
    val updateIntervalTicks: Long,
    /** 玩家与核心的距离超过这个值(格)就不渲染，防止全图广播式发包。 */
    val renderRadius: Double,
    /** 悬浮字相对核心方块中心的垂直偏移(格)。 */
    val heightOffset: Double,
    val scale: Float,
    /** 文字背景色(ARGB 压缩 int)，默认 0 = 全透明。 */
    val backgroundArgb: Int,
    val hasShadow: Boolean,
    /** 无视方块遮挡显示（Text Display 原生 see_through，与 PacketWaypointRenderer 同一套协议字段）。 */
    val seeThrough: Boolean,
    /** 锻造中的文字模板，`%seconds%` 替换成剩余整数秒。 */
    val forgingFormat: String,
    /** 完成后(可收取)的文字。 */
    val doneText: String
) {
    companion object {
        fun load(config: FileConfiguration, root: String): ForgeHologramConfig {
            val r = "$root.hologram"
            return ForgeHologramConfig(
                enabled = config.getBoolean("$r.enabled", true),
                updateIntervalTicks = config.getLong("$r.update-interval-ticks", 20L).coerceAtLeast(1L),
                renderRadius = config.getDouble("$r.render-radius", 24.0).coerceIn(4.0, 64.0),
                heightOffset = config.getDouble("$r.height-offset", 1.8),
                scale = config.getDouble("$r.scale", 1.0).toFloat().coerceIn(0.1f, 8.0f),
                backgroundArgb = config.getInt("$r.background-argb", 0),
                hasShadow = config.getBoolean("$r.has-shadow", true),
                seeThrough = config.getBoolean("$r.see-through", true),
                forgingFormat = config.getString("$r.forging-format", "&e⚒ 剩余 &f%seconds%s")!!,
                doneText = config.getString("$r.done-text", "&a✔ 可收取")!!
            )
        }
    }
}
