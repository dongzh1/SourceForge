package com.dongzh1.sourceforge.nav

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3f
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import org.bukkit.entity.Player
import java.util.Optional
import java.util.UUID

/**
 * PacketEvents Text Display 发包后端。**本类的所有 PE 类型引用都被隔离在这里**（与
 * [com.dongzh1.sourceforge.particle.PacketParticleBackend] 同一套隔离约定）——只有确认
 * PacketEvents 插件在线后 [PacketWaypointRenderer] 才会碰这个 object，服务器没装 PE 时不会
 * 因为 NoClassDefFoundError 拖崩整个插件。
 *
 * Text Display 实体 metadata 索引（原版 1.20.2+ 协议布局，Entity 基础字段 0-7 之后）：
 * 8=interpolation_delay(Int) 10=teleport_duration(Int) 12=scale(Vector3f)
 * 15=billboard(Byte,3=CENTER朝向摄像机) 23=text(Component) 25=background_color(Int,ARGB)
 * 27=style_flags(Byte,bit0=阴影,bit1=see_through无视方块遮挡)。这几个索引没有被 PacketEvents
 * 封装成具名常量（纯协议数字），如果未来 Mojang 改协议布局，只需要改这一个文件。
 *
 * 平滑移动（用户 2026-07-08 反馈"反复发包会卡顿"）：Display 实体独有 teleport_duration
 * metadata——只要在生成时设一次(非0)，之后每次 [teleport] 发的新坐标，客户端会在这几个 tick 内
 * 自己平滑滑过去，不是瞬移。[PacketWaypointRenderer] 完全不用变，该发 Teleport 包还是照发，
 * 只是现在客户端表现从"每次跳一下"变成"持续滑动"——顺带还能把发包频率调得更低而不掉视觉平滑度，
 * 两个问题（卡顿观感 + 包量）一起解决。
 */
object WaypointPacketBridge {

    private const val IDX_INTERPOLATION_DELAY = 8
    private const val IDX_TELEPORT_DURATION = 10
    private const val IDX_SCALE = 12
    private const val IDX_BILLBOARD = 15
    private const val IDX_TEXT = 23
    private const val IDX_BACKGROUND = 25
    private const val IDX_STYLE_FLAGS = 27

    /** billboard=CENTER：始终朝向观察者，跟 GUI 元素一样"贴脸"，不受实体朝向影响。 */
    private const val BILLBOARD_CENTER: Byte = 3

    private fun playerManager() = PacketEvents.getAPI().playerManager

    /** PE 是否可用（API + 玩家管理器已就绪）。调用方需 runCatching 包住——首次触发类加载。 */
    fun isAvailable(): Boolean {
        val api = PacketEvents.getAPI() ?: return false
        return api.playerManager != null
    }

    fun spawn(player: Player, entityId: Int, uuid: UUID, x: Double, y: Double, z: Double, cfg: WaypointConfig, hex: String, targetName: String, distanceText: String) {
        val spawn = WrapperPlayServerSpawnEntity(
            entityId, Optional.of(uuid), EntityTypes.TEXT_DISPLAY,
            Vector3d(x, y, z), 0f, 0f, 0f, 0, Optional.empty()
        )
        val pm = playerManager()
        pm.sendPacket(player, spawn)
        pm.sendPacket(player, WrapperPlayServerEntityMetadata(entityId, baseMetadata(cfg, hex, targetName, distanceText)))
    }

    fun teleport(player: Player, entityId: Int, x: Double, y: Double, z: Double) {
        playerManager().sendPacket(
            player,
            WrapperPlayServerEntityTeleport(entityId, Vector3d(x, y, z), 0f, 0f, false)
        )
    }

    fun updateText(player: Player, entityId: Int, cfg: WaypointConfig, hex: String, targetName: String, distanceText: String) {
        playerManager().sendPacket(
            player,
            WrapperPlayServerEntityMetadata(entityId, listOf(textEntry(cfg, hex, targetName, distanceText)))
        )
    }

    fun destroy(player: Player, entityId: Int) {
        playerManager().sendPacket(player, WrapperPlayServerDestroyEntities(entityId))
    }

    /** [hex] 是追踪目标自己的颜色（NavTarget.hex，6位不带#，与 BetterHud 指南针同色）。 */
    private fun textEntry(cfg: WaypointConfig, hex: String, targetName: String, distanceText: String): EntityData<*> {
        val textStyle = targetStyle(hex)
        var iconStyle = textStyle
        if (cfg.iconFont.isNotBlank()) {
            runCatching { iconStyle = iconStyle.font(Key.key(cfg.iconFont)) }
        }
        val text = Component.text(cfg.iconGlyph)
            .style(iconStyle)
            .appendNewline()
            .append(Component.text(targetName).style(textStyle))
            .appendNewline()
            .append(Component.text(distanceText).style(textStyle))
        return EntityData(IDX_TEXT, EntityDataTypes.ADV_COMPONENT, text)
    }

    private fun targetStyle(hex: String): Style {
        var style = Style.style()
        runCatching { TextColor.fromHexString("#$hex") }.getOrNull()?.let { style = style.color(it) }
        return style.build()
    }

    private fun baseMetadata(cfg: WaypointConfig, hex: String, targetName: String, initialDistanceText: String): List<EntityData<*>> {
        var flags = 0
        if (cfg.hasShadow) flags = flags or 0x01
        if (cfg.seeThrough) flags = flags or 0x02
        return listOf(
            // 位置插值：0延迟、按更新周期设置滑动时长——下一次坐标更新预计在这么多 tick 后到达，
            // 滑动刚好在那之前走完，不会出现"提前停住等下一次"或"还没滑完就被打断"的割裂感。
            EntityData(IDX_INTERPOLATION_DELAY, EntityDataTypes.INT, 0),
            EntityData(IDX_TELEPORT_DURATION, EntityDataTypes.INT, cfg.updateIntervalTicks.toInt().coerceAtLeast(1)),
            EntityData(IDX_BILLBOARD, EntityDataTypes.BYTE, BILLBOARD_CENTER),
            EntityData(IDX_SCALE, EntityDataTypes.VECTOR3F, Vector3f(cfg.scale, cfg.scale, cfg.scale)),
            EntityData(IDX_BACKGROUND, EntityDataTypes.INT, cfg.backgroundArgb),
            EntityData(IDX_STYLE_FLAGS, EntityDataTypes.BYTE, flags.toByte()),
            textEntry(cfg, hex, targetName, initialDistanceText)
        )
    }
}
