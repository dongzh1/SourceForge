package com.dongzh1.sourceforge.particle

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.particle.Particle
import com.github.retrooper.packetevents.protocol.particle.data.ParticleBlockStateData
import com.github.retrooper.packetevents.protocol.particle.data.ParticleData
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustColorTransitionData
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustData
import com.github.retrooper.packetevents.protocol.particle.data.ParticleItemStackData
import com.github.retrooper.packetevents.protocol.particle.type.ParticleType
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3f
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.Location
import org.bukkit.entity.Player

/**
 * PacketEvents 发包后端。**本类的所有 PE 类型引用都被隔离在这里**——只有当 [tryCreate] 被
 * [ParticleRenderer.pickBackend] 在“确认 PacketEvents 插件在线”后调用时才会加载，
 * 从而保证服务器没装 PacketEvents 时不会因为 NoClassDefFoundError 把整个渲染门面拖崩（对应严格设计要求）。
 *
 * 发包策略（对应“一次性打包发送、不要多次发送”）：
 * - 每次 [send] 只把 [SfParticle] 解析成**一个** PE Particle 对象，全批点/全部观察者复用；
 * - **每个坐标点只 new 一个 wrapper**，然后把这同一个 wrapper 发给所有观察者（跨观察者复用同一包对象，减少分配）；
 * - 不再走 world.spawnParticle 的全世界可见性广播，观察者由门面提前圈定。
 */
class PacketParticleBackend private constructor(
    private val playerManager: com.github.retrooper.packetevents.manager.player.PlayerManager
) : ParticleRenderer.Backend {

    override val name = "packetevents"

    override fun send(points: List<Location>, brush: SfParticle, viewers: Collection<Player>) {
        val particle = buildParticle(brush) ?: return
        val count = brush.count.coerceAtLeast(0)
        val speed = brush.speed.toFloat()
        val offset = Vector3f(brush.offsetX.toFloat(), brush.offsetY.toFloat(), brush.offsetZ.toFloat())
        for (pt in points) {
            // 每个点建一个 wrapper，发给所有观察者（同一 wrapper 复用）
            val wrapper = WrapperPlayServerParticle(
                particle,
                LONG_DISTANCE,
                Vector3d(pt.x, pt.y, pt.z),
                offset,
                speed,
                count
            )
            for (viewer in viewers) {
                runCatching { playerManager.sendPacket(viewer, wrapper) }
            }
        }
    }

    /** 把笔刷解析成 PE Particle；未知粒子名或材质无效时返回 null（整批跳过，不抛）。 */
    private fun buildParticle(brush: SfParticle): Particle<*>? {
        val scale = brush.size.coerceIn(0.01f, 4.0f)
        return when (brush.key) {
            "dust" -> Particle(
                ParticleTypes.DUST,
                ParticleDustData(scale, SfParticle.redF(brush.colorArgb), SfParticle.greenF(brush.colorArgb), SfParticle.blueF(brush.colorArgb))
            )
            "dust_color_transition" -> Particle(
                ParticleTypes.DUST_COLOR_TRANSITION,
                ParticleDustColorTransitionData(
                    scale,
                    SfParticle.redF(brush.colorArgb), SfParticle.greenF(brush.colorArgb), SfParticle.blueF(brush.colorArgb),
                    SfParticle.redF(brush.toColorArgb), SfParticle.greenF(brush.toColorArgb), SfParticle.blueF(brush.toColorArgb)
                )
            )
            "block", "block_marker", "falling_dust", "dust_pillar" -> {
                val wbs = brush.material?.let { m ->
                    runCatching {
                        SpigotConversionUtil.fromBukkitBlockData(
                            org.bukkit.Material.valueOf(m.uppercase()).createBlockData()
                        )
                    }.getOrNull()
                } ?: runCatching {
                    SpigotConversionUtil.fromBukkitBlockData(org.bukkit.Material.STONE.createBlockData())
                }.getOrNull() ?: return plain(brush.key)
                val type = ParticleTypes.getByName(brush.key) as? ParticleType<ParticleBlockStateData> ?: return null
                Particle(type, ParticleBlockStateData(wbs))
            }
            "item" -> {
                // material 无效/缺省时回落 STONE（与 block 分支对称）：绝不产出无数据的 ITEM 粒子，
                // 否则 PE 序列化时把 EMPTY 当 ParticleItemStackData 会抛 ClassCastException 被吞、粒子静默消失。
                val peItem = (brush.material?.let { m ->
                    runCatching {
                        SpigotConversionUtil.fromBukkitItemStack(
                            org.bukkit.inventory.ItemStack(org.bukkit.Material.valueOf(m.uppercase()))
                        )
                    }.getOrNull()
                } ?: runCatching {
                    SpigotConversionUtil.fromBukkitItemStack(org.bukkit.inventory.ItemStack(org.bukkit.Material.STONE))
                }.getOrNull()) ?: return plain(brush.key)
                Particle(ParticleTypes.ITEM, ParticleItemStackData(peItem))
            }
            else -> plain(brush.key)
        }
    }

    /** 无数据的普通粒子。名字查不到返回 null。 */
    private fun plain(key: String): Particle<*>? {
        @Suppress("UNCHECKED_CAST")
        val type = (ParticleTypes.getByName(key) ?: return null) as ParticleType<ParticleData>
        return Particle(type)
    }

    companion object {
        /** longDistance=true：技能特效允许在 >32 格外仍下发，保证远处也看得见。 */
        private const val LONG_DISTANCE = true

        /**
         * 仅在 PacketEvents 可用时返回后端实例，否则返回 null（让门面降级 Bukkit）。
         * 调用方必须用 runCatching 包住：本方法首次被调用即触发 PE 类加载，PE 缺失会在此抛 NoClassDefFoundError。
         */
        fun tryCreate(): ParticleRenderer.Backend? {
            val api = PacketEvents.getAPI() ?: return null
            val pm = api.playerManager ?: return null
            return PacketParticleBackend(pm)
        }
    }
}
