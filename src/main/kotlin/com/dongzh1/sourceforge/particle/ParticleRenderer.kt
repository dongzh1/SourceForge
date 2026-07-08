package com.dongzh1.sourceforge.particle

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player

/**
 * 粒子渲染门面：一次 [render] 把“一堆世界坐标点 + 一个笔刷 + 一批观察者”交给后端渲染。
 *
 * 关键性能取舍（对应需求“利用 packetevents 发包、一次性打包发送、不要多次发送”）：
 * - **观察者只解析一次**：默认取形状中心 [viewRadius] 格内的玩家，避免像 world.spawnParticle 那样对全世界玩家做可见性判定；
 *   也不会把几十个弧线点分别广播给 500 格外根本看不见的人。
 * - **笔刷只解析一次**：PacketEvents 后端把 [SfParticle] 解析成一个 Particle 对象后，对所有点/所有观察者复用，逐点建包发送。
 * - PE 不可用（服务器没装 PacketEvents 插件）时**自动降级**到 Bukkit `player.spawnParticle`，功能不缺，只是少了发包层的精细控制。
 *
 * ⚠ 线程：默认在主线程调用（读玩家/实体位置需要）。PacketEvents 的 sendPacket 本身线程安全，
 *   但本门面解析观察者要读 world.players / location，故整体按主线程用。
 */
class ParticleRenderer(private val plugin: SourceForge) {

    /** 后端抽象：真正把点画出去。两种实现——PacketEvents 发包 / Bukkit 兜底。 */
    interface Backend {
        val name: String
        /** 把 [points]（同一世界）以 [brush] 画给 [viewers]。points 已是世界坐标。 */
        fun send(points: List<Location>, brush: SfParticle, viewers: Collection<Player>)
    }

    /** 默认观察者半径（格）。可被 config `particle.view-radius` 覆盖。 */
    private val viewRadius: Double =
        plugin.config.getDouble("particle.view-radius", 48.0).coerceIn(8.0, 256.0)

    /** 是否强制只用 Bukkit 后端（config `particle.force-bukkit`，调试/兼容用）。 */
    private val forceBukkit: Boolean = plugin.config.getBoolean("particle.force-bukkit", false)

    val backend: Backend = pickBackend()

    private fun pickBackend(): Backend {
        if (forceBukkit) {
            plugin.logger.info("[particle] 强制使用 Bukkit 粒子后端(particle.force-bukkit=true)")
            return BukkitBackend
        }
        // 只有在 PacketEvents 插件确实在线时，才去碰引用了 PE 类的 PacketParticleBackend，
        // 用 runCatching 兜住任何 NoClassDefFound / 版本不匹配，失败即降级 Bukkit（严格设计：软依赖不可硬崩）。
        val pePlugin = plugin.server.pluginManager.getPlugin("packetevents")
        if (pePlugin != null && pePlugin.isEnabled) {
            val be = runCatching { PacketParticleBackend.tryCreate() }
                .onFailure { plugin.logger.warning("[particle] PacketEvents 后端初始化失败,降级 Bukkit: ${it.message}") }
                .getOrNull()
            if (be != null) {
                plugin.logger.info("[particle] 使用 PacketEvents 粒子后端")
                return be
            }
        } else {
            plugin.logger.info("[particle] 未检测到 PacketEvents 插件,使用 Bukkit 粒子后端")
        }
        return BukkitBackend
    }

    /**
     * 渲染一组世界坐标点。[viewers] 为空时自动解析（形状中心 [viewRadius] 内玩家）。
     * points 为空直接返回。所有点应在同一世界（取第一个点的世界；跨世界的点会被丢弃）。
     */
    fun render(points: List<Location>, brush: SfParticle, viewers: Collection<Player>? = null) {
        if (points.isEmpty()) return
        val world = points.first().world ?: return
        // 只保留同世界的点，防跨世界坐标混入导致观察者/距离判定错乱
        val same = if (points.all { it.world === world }) points else points.filter { it.world === world }
        if (same.isEmpty()) return

        val vs = viewers ?: resolveViewers(same, world)
        if (vs.isEmpty()) return
        runCatching { backend.send(same, brush, vs) }
            .onFailure { plugin.logger.warning("[particle] 渲染失败: ${it.message}") }
    }

    /** 便捷：把局部偏移 [offsets] 平移到 [origin] 后渲染。 */
    fun renderLocal(origin: Location, offsets: List<org.bukkit.util.Vector>, brush: SfParticle, viewers: Collection<Player>? = null) {
        render(ParticleGeometry.toWorld(origin, offsets), brush, viewers)
    }

    /** 取形状包围中心 [viewRadius] 内的在线玩家作为观察者。 */
    private fun resolveViewers(points: List<Location>, world: org.bukkit.World): List<Player> {
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        for (p in points) { cx += p.x; cy += p.y; cz += p.z }
        val n = points.size
        val center = Location(world, cx / n, cy / n, cz / n)
        val r2 = viewRadius * viewRadius
        return world.players.filter { it.location.distanceSquared(center) <= r2 }
    }

    // ============================ Bukkit 兜底后端 ============================

    /**
     * 用 `player.spawnParticle` 逐观察者、逐点发。数据类型按解析出的 [Particle.getDataType] 动态构造，
     * 覆盖 DUST / DUST_COLOR_TRANSITION / BLOCK / ITEM / 普通粒子。粒子名解析失败则整批跳过（不抛）。
     */
    object BukkitBackend : Backend {
        override val name = "bukkit"

        override fun send(points: List<Location>, brush: SfParticle, viewers: Collection<Player>) {
            val particle = resolveParticle(brush.key) ?: return
            val data = resolveData(particle, brush)
            val count = brush.count.coerceAtLeast(0)
            for (viewer in viewers) {
                for (pt in points) {
                    runCatching {
                        viewer.spawnParticle(
                            particle, pt, count,
                            brush.offsetX, brush.offsetY, brush.offsetZ, brush.speed, data
                        )
                    }
                }
            }
        }

        private fun resolveParticle(key: String): Particle? =
            runCatching { Particle.valueOf(key.uppercase()) }.getOrNull()

        /** 按粒子实际 dataType 构造数据对象；类型不匹配或材质无效时返回 null（无数据）。 */
        private fun resolveData(particle: Particle, brush: SfParticle): Any? {
            val dt = runCatching { particle.dataType }.getOrNull() ?: return null
            return when (dt) {
                Particle.DustOptions::class.java -> Particle.DustOptions(
                    org.bukkit.Color.fromRGB(SfParticle.red(brush.colorArgb), SfParticle.green(brush.colorArgb), SfParticle.blue(brush.colorArgb)),
                    brush.size.coerceIn(0.01f, 4.0f)
                )
                Particle.DustTransition::class.java -> Particle.DustTransition(
                    org.bukkit.Color.fromRGB(SfParticle.red(brush.colorArgb), SfParticle.green(brush.colorArgb), SfParticle.blue(brush.colorArgb)),
                    org.bukkit.Color.fromRGB(SfParticle.red(brush.toColorArgb), SfParticle.green(brush.toColorArgb), SfParticle.blue(brush.toColorArgb)),
                    brush.size.coerceIn(0.01f, 4.0f)
                )
                org.bukkit.block.data.BlockData::class.java -> brush.material?.let { m ->
                    runCatching { org.bukkit.Material.valueOf(m.uppercase()).createBlockData() }.getOrNull()
                }
                org.bukkit.inventory.ItemStack::class.java -> brush.material?.let { m ->
                    runCatching { org.bukkit.inventory.ItemStack(org.bukkit.Material.valueOf(m.uppercase())) }.getOrNull()
                }
                else -> null
            }
        }
    }
}
