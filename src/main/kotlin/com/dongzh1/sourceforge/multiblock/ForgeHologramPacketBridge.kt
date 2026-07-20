package com.dongzh1.sourceforge.multiblock

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3f
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.util.Optional
import java.util.UUID

/**
 * 锻炉作业穿墙悬浮字发包后端。**所有 PacketEvents 类型引用隔离在这里**，隔离约定同
 * [com.dongzh1.sourceforge.nav.WaypointPacketBridge]——服务器没装 PE 时 [ForgeJobHologramRenderer]
 * 不会碰这个 object，不会因为 NoClassDefFoundError 拖崩插件。
 *
 * Text Display 悬浮字固定挂在核心正上方不动，不像路标要追着玩家挪动，所以这里没有 teleport
 * 接口，只有生成/改字/销毁三个操作。metadata 索引与 [com.dongzh1.sourceforge.nav.WaypointPacketBridge]
 * 是同一套已验证过的原版协议布局：8=interpolation_delay 10=teleport_duration 12=scale
 * 15=billboard(3=CENTER) 23=text(Component) 25=background_color(ARGB) 27=style_flags
 * (bit0=阴影 bit1=see_through，无视方块遮挡)。
 */
object ForgeHologramPacketBridge {
    private const val IDX_INTERPOLATION_DELAY = 8
    private const val IDX_TELEPORT_DURATION = 10
    private const val IDX_SCALE = 12
    private const val IDX_BILLBOARD = 15
    private const val IDX_TEXT = 23
    private const val IDX_BACKGROUND = 25
    private const val IDX_STYLE_FLAGS = 27

    /** billboard=CENTER：始终朝向观察者。 */
    private const val BILLBOARD_CENTER: Byte = 3

    private fun playerManager() = PacketEvents.getAPI().playerManager

    /** PE 是否可用（API + 玩家管理器已就绪）。调用方需 runCatching 包住——首次触发类加载。 */
    fun isAvailable(): Boolean {
        val api = PacketEvents.getAPI() ?: return false
        return api.playerManager != null
    }

    fun spawn(player: Player, entityId: Int, uuid: UUID, x: Double, y: Double, z: Double, cfg: ForgeHologramConfig, text: Component) {
        val spawn = WrapperPlayServerSpawnEntity(
            entityId, Optional.of(uuid), EntityTypes.TEXT_DISPLAY,
            Vector3d(x, y, z), 0f, 0f, 0f, 0, Optional.empty()
        )
        val pm = playerManager()
        pm.sendPacket(player, spawn)
        pm.sendPacket(player, WrapperPlayServerEntityMetadata(entityId, baseMetadata(cfg, text)))
    }

    fun updateText(player: Player, entityId: Int, text: Component) {
        playerManager().sendPacket(
            player,
            WrapperPlayServerEntityMetadata(entityId, listOf(EntityData(IDX_TEXT, EntityDataTypes.ADV_COMPONENT, text)))
        )
    }

    fun destroy(player: Player, entityId: Int) {
        playerManager().sendPacket(player, WrapperPlayServerDestroyEntities(entityId))
    }

    private fun baseMetadata(cfg: ForgeHologramConfig, text: Component): List<EntityData<*>> {
        var flags = 0
        if (cfg.hasShadow) flags = flags or 0x01
        if (cfg.seeThrough) flags = flags or 0x02
        return listOf(
            EntityData(IDX_INTERPOLATION_DELAY, EntityDataTypes.INT, 0),
            EntityData(IDX_TELEPORT_DURATION, EntityDataTypes.INT, 0),
            EntityData(IDX_BILLBOARD, EntityDataTypes.BYTE, BILLBOARD_CENTER),
            EntityData(IDX_SCALE, EntityDataTypes.VECTOR3F, Vector3f(cfg.scale, cfg.scale, cfg.scale)),
            EntityData(IDX_BACKGROUND, EntityDataTypes.INT, cfg.backgroundArgb),
            EntityData(IDX_STYLE_FLAGS, EntityDataTypes.BYTE, flags.toByte()),
            EntityData(IDX_TEXT, EntityDataTypes.ADV_COMPONENT, text)
        )
    }
}
