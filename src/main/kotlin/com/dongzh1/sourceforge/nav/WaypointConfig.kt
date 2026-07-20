package com.dongzh1.sourceforge.nav

import org.bukkit.configuration.file.FileConfiguration

/**
 * 追踪路标（发包 Text Display）的可调参数，来自 waypoint.yml。见 [PacketWaypointRenderer]。
 */
data class WaypointConfig(
    val enabled: Boolean,
    /**
     * 位置/文字更新周期(tick)，同时也是每次移动的 Display 插值滑动时长（见 [WaypointPacketBridge]
     * 的 teleport_duration 说明）——数值越大发包越少，但移动过程仍然是平滑滑动而非瞬移。
     */
    val updateIntervalTicks: Long,
    /** 图标悬浮在玩家视角前的最大距离(格)；超过这个距离的目标只指方向，不直接把实体摆在真实坐标上。 */
    val maxHoverDistance: Double,
    /** 同时渲染的路标上限（一个追踪目标一个路标）；超过时只渲染最近的这么多个，防止追踪目标过多时刷屏/发包过量。 */
    val maxMarkers: Int,
    /** 图标字符（默认用一个普通 Unicode 符号，可换成资源包自定义字体字形）。 */
    val iconGlyph: String,
    /** 图标字符使用的自定义字体命名空间键；留空用原版内置字体。 */
    val iconFont: String,
    /** 整个路标实体（图标、目标名称、距离三行文字）的缩放。 */
    val scale: Float,
    /** 距离文字格式，`%distance%` 替换成取整后的米数。 */
    val distanceFormat: String,
    /** 文字背景色(ARGB 压缩 int)，默认 0 = 全透明。 */
    val backgroundArgb: Int,
    val hasShadow: Boolean,
    /** 无视方块遮挡显示（Text Display 原生 see_through，不需要发光效果等绕路方案）。 */
    val seeThrough: Boolean
) {
    companion object {
        fun load(config: FileConfiguration): WaypointConfig {
            return WaypointConfig(
                enabled = config.getBoolean("enabled", true),
                updateIntervalTicks = config.getLong("update-interval-ticks", 6L).coerceAtLeast(1L),
                maxHoverDistance = config.getDouble("max-hover-distance", 8.0).coerceIn(1.0, 32.0),
                maxMarkers = config.getInt("max-markers", 5).coerceIn(1, 20),
                iconGlyph = config.getString("icon-glyph", "◆")!!,
                iconFont = config.getString("icon-font", "")!!,
                scale = config.getDouble("scale", 1.6).toFloat().coerceIn(0.1f, 8.0f),
                distanceFormat = config.getString("distance-format", "%distance%m")!!,
                backgroundArgb = config.getInt("background-argb", 0),
                hasShadow = config.getBoolean("has-shadow", true),
                seeThrough = config.getBoolean("see-through", true)
            )
        }
    }
}
