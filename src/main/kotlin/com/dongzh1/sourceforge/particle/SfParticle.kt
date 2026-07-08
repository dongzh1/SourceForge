package com.dongzh1.sourceforge.particle

/**
 * 与后端无关的“粒子笔刷”描述：一次描述好要画什么粒子、什么颜色/大小/数量/扩散，
 * 再交给 [ParticleRenderer] 决定用 PacketEvents 发包还是 Bukkit spawnParticle 兜底。
 *
 * 颜色统一用 ARGB int（和项目现有 [com.dongzh1.sourceforge.status.ElementConfig] 的约定一致，便于互通）。
 * 参数刻意做全、给足自由度：普通粒子、红石染色(DUST)、颜色渐变(DUST_TRANSITION)、方块/物品碎屑都覆盖到。
 *
 * @property type     粒子注册名（小写，如 "flame" / "crit" / "dust" / "dust_color_transition" / "block" / "item" / "soul_fire_flame"）。
 * @property count    每个坐标点客户端生成的粒子数。**0 = 定向单粒子**：此时 [offsetX]/[offsetY]/[offsetZ] 被当作“运动方向”，[speed] 当作初速。
 * @property offsetX  count>0 时为随机扩散盒半径；count==0 时为方向分量 x。
 * @property offsetY  同上，y。
 * @property offsetZ  同上，z。
 * @property speed    粒子“额外速度”参数（原版 extra）。DUST 等类型通常填 0。
 * @property colorArgb    DUST / 渐变起始色（ARGB，A 位忽略，只取 RGB）。
 * @property toColorArgb  DUST_TRANSITION 的目标色。
 * @property size     DUST / 渐变的缩放（1.0 约等于原版红石大小）。
 * @property material BLOCK / ITEM 类粒子的方块或物品材质名（如 "STONE"）；其它类型忽略。
 */
data class SfParticle(
    val type: String,
    val count: Int = 1,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    val offsetZ: Double = 0.0,
    val speed: Double = 0.0,
    val colorArgb: Int = 0xFFFF5555.toInt(),
    val toColorArgb: Int = 0xFF55FFFF.toInt(),
    val size: Float = 1.0f,
    val material: String? = null
) {
    /** 归一化后的粒子名（去空格、小写、去掉 minecraft: 前缀），供两种后端各自解析。 */
    val key: String get() = type.trim().lowercase().removePrefix("minecraft:")

    companion object {
        // ---- ARGB 拆分小工具（0..255）----
        fun red(argb: Int): Int = (argb shr 16) and 0xFF
        fun green(argb: Int): Int = (argb shr 8) and 0xFF
        fun blue(argb: Int): Int = argb and 0xFF

        // ---- ARGB → 0..1 浮点（PacketEvents 的 dust 颜色用浮点）----
        fun redF(argb: Int): Float = red(argb) / 255f
        fun greenF(argb: Int): Float = green(argb) / 255f
        fun blueF(argb: Int): Float = blue(argb) / 255f

        /** 由 RGB(0..255) 拼一个不透明 ARGB。 */
        fun rgb(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

        /** 便捷：普通粒子（火焰/暴击等）。 */
        fun normal(type: String, count: Int = 1, spread: Double = 0.0, speed: Double = 0.0): SfParticle =
            SfParticle(type, count, spread, spread, spread, speed)

        /** 便捷：定向单粒子（count=0，用方向+初速），常用于拖尾/光束粒子。 */
        fun directional(type: String, dirX: Double, dirY: Double, dirZ: Double, speed: Double): SfParticle =
            SfParticle(type, 0, dirX, dirY, dirZ, speed)

        /** 便捷：红石染色粒子。 */
        fun dust(colorArgb: Int, size: Float = 1.0f, count: Int = 1): SfParticle =
            SfParticle("dust", count, 0.0, 0.0, 0.0, 0.0, colorArgb, colorArgb, size)

        /** 便捷：颜色渐变红石粒子。 */
        fun dustTransition(from: Int, to: Int, size: Float = 1.0f, count: Int = 1): SfParticle =
            SfParticle("dust_color_transition", count, 0.0, 0.0, 0.0, 0.0, from, to, size)
    }
}
