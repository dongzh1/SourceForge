package com.dongzh1.sourceforge.nav

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.hud.BetterHudHook
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 多目标坐标追踪导航。用 /sf track 为玩家累加目标，方向交给 BetterHud 原生指南针——每个目标
 * 一个 pointer，颜色由指令显式指定（默认白），对应不同颜色三角(custom-icon)。
 *
 * 不直接引用 kr.toxicity.* 类型——所有 BetterHud 交互经 [BetterHudHook]，
 * 缺少 BetterHud 时安全降级（仅不显示）。
 */
class NavigationManager(private val plugin: SourceForge) : Listener {

    /**
     * 一个追踪目标。坐标不再是固定 val——[entityUuid] 非空时，坐标由 [refreshFromEntity] 在
     * 主线程周期性刷新自 Bukkit 实体真实位置（追踪会移动的实体，如玩家）；为 null 时坐标恒为
     * 构造时传入的静态值（原有的坐标追踪）。两种目标共用同一份列表/排序/弹窗/路标渲染逻辑。
     *
     * 不用 data class：引入可变状态后 `equals/hashCode/copy` 的默认语义会随时间漂移，且全项目
     * 未使用过这几个方法（已确认零 `.copy()` 调用）；改为普通 class，与同文件
     * [PacketWaypointRenderer.MarkerState] 的"可变状态用裸 class"写法保持一致。
     *
     * 线程安全：[BetterHudHook] 的指针 provider 可能在非主线程以渲染频率读取 [x]/[y]/[z]/[world]
     * ——这几个属性只读 [position] 这个 `@Volatile` 引用（一次引用读，任何线程安全；整体替换保证
     * 不会读到"x 是新值、y 还是旧值"的撕裂状态）。真正触碰 Bukkit API 的 [refreshFromEntity] 只允许
     * 在主线程调用（由 [NavigationManager] 已有的 2-tick 定时任务负责）。
     */
    class NavTarget(
        val name: String,
        initialPosition: Position,
        /** 弹窗行/箭头文字色（hex 去#）。 */
        val hex: String,
        /** 指南针三角 custom-icon 名（任意 hex 时取最接近的预设）。 */
        val icon: String,
        /** 跨世界弹窗行展示的世界友好名，由调用方传入（SourceForge 不认识调用方的世界命名体系）。 */
        val worldLabel: String,
        /** 非空 = 该目标绑定一个会移动的实体；持有 UUID 而非 Entity 引用——玩家重连后 Bukkit
         *  会发一个新的 Player 实例，旧引用会失效，每次现查更稳妥（与
         *  [com.dongzh1.sourceforge.status.StatusEffectManager] 现查 mob 的既有写法一致）。 */
        val entityUuid: UUID? = null
    ) {
        /** 不可变坐标快照，整体替换以保证跨线程读取不撕裂。 */
        data class Position(val x: Double, val y: Double, val z: Double, val world: String)

        @Volatile
        var position: Position = initialPosition
            private set

        val x: Double get() = position.x
        val y: Double get() = position.y
        val z: Double get() = position.z
        val world: String get() = position.world

        /**
         * 仅供 [NavigationManager] 的主线程 2-tick 循环调用，有副作用（成功时原子替换 [position]）。
         * [entityUuid] 为空（静态坐标目标）恒返回 true，无需刷新。
         *
         * 防卡顿：优先查"上次已知世界"（[Bukkit.getWorld] + [org.bukkit.World.getEntity]，O(1) 哈希查找），
         * 只有实体真的跨世界了才退化到 [Bukkit.getEntity]（内部线性扫描全部已加载世界）——本服务器由
         * ChunkWorld 驱动，同时存在的动态世界数可能很多，绝大多数 tick 命中快路径可以避免这个线性扫描。
         *
         * @return false = 实体已下线/死亡/被移除/世界不可解析，调用方据此把这条目标从追踪列表摘除。
         */
        internal fun refreshFromEntity(): Boolean {
            val uuid = entityUuid ?: return true
            val entity: Entity = Bukkit.getWorld(position.world)?.getEntity(uuid)
                ?: Bukkit.getEntity(uuid)
                ?: return false
            if (entity.isDead || !entity.isValid) return false
            val loc = entity.location
            val w = loc.world?.name ?: return false
            position = Position(loc.x, loc.y, loc.z, w)
            return true
        }
    }

    /**
     * uuid -> 该玩家的目标列表。用 CopyOnWriteArrayList：BetterHud 的指针 provider 可能
     * 在非主线程以渲染频率读取本列表，CoW 保证迭代不会抛 ConcurrentModificationException。
     */
    private val targets = ConcurrentHashMap<UUID, CopyOnWriteArrayList<NavTarget>>()

    fun start() {
        // 注册原生指南针指针 provider：BetterHud 主动来拉，reload 安全。
        BetterHudHook.registerPointerProvider(plugin) { uuid ->
            targets[uuid]?.map {
                BetterHudHook.SfPointer(BetterHudHook.pointerName(it.name), it.icon, it.world, it.x, it.y, it.z)
            } ?: emptyList()
        }
        // 监听 BetterHud 重载，确保指针 provider 在重载后的生命周期内保持可用。
        BetterHudHook.registerReloadListener(plugin)
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (targets.isEmpty()) return@Runnable
            for ((uuid, list) in targets) {
                if (list.isEmpty()) continue
                if (list.none { it.entityUuid != null }) continue
                val player = Bukkit.getPlayer(uuid) ?: continue
                if (!player.isOnline) continue
                val removedSome = list.removeAll { !it.refreshFromEntity() }
                if (removedSome && list.isEmpty()) {
                    targets.remove(uuid)
                    BetterHudHook.hideNavigator(player)
                }
            }
        }, 2L, 2L)
    }

    /** 累加/更新一个追踪目标（同名则覆盖坐标与颜色）。指针由 provider 自动反映。 */
    fun track(player: Player, name: String, x: Double, y: Double, z: Double, world: String, hex: String, icon: String, worldLabel: String = world) {
        val list = targets.getOrPut(player.uniqueId) { CopyOnWriteArrayList() }
        list.removeAll { it.name.equals(name, true) }
        list.add(NavTarget(name, NavTarget.Position(x, y, z, world), hex, icon, worldLabel))
    }

    /**
     * 追踪一个会移动的实体（如玩家/NPC/坐骑），与坐标追踪目标共用同一份列表——同一个弹窗、同一套
     * 按距离排序/上限逻辑，无需调用方额外处理。目标位置由 [NavTarget.refreshFromEntity] 在主线程
     * 每 2 tick 刷新一次；实体消失（下线/死亡/被移除）后下一次刷新会自动把这条目标摘除，调用方
     * 不需要自行监听实体消失事件来清理。
     *
     * @return false = entity 在调用时已死亡/失效，或所在世界不可解析——本次调用不生效。
     */
    fun trackEntity(player: Player, name: String, entity: Entity, hex: String, icon: String, worldLabel: String? = null): Boolean {
        if (entity.isDead || !entity.isValid) return false
        val loc = entity.location
        val world = loc.world?.name ?: return false
        val list = targets.getOrPut(player.uniqueId) { CopyOnWriteArrayList() }
        list.removeAll { it.name.equals(name, true) }
        list.add(NavTarget(name, NavTarget.Position(loc.x, loc.y, loc.z, world), hex, icon, worldLabel ?: world, entity.uniqueId))
        return true
    }

    /** 移除一个目标。返回是否确实存在并移除。指针由 provider 自动反映。 */
    fun untrack(player: Player, name: String): Boolean {
        val list = targets[player.uniqueId] ?: return false
        val removed = list.removeAll { it.name.equals(name, true) }
        if (!removed) return false
        if (list.isEmpty()) {
            targets.remove(player.uniqueId)
            BetterHudHook.hideNavigator(player)
        }
        return true
    }

    /** 清空该玩家所有目标。返回是否原本有目标。指针由 provider 自动反映。 */
    fun stop(player: Player): Boolean {
        val had = targets.remove(player.uniqueId) != null
        BetterHudHook.hideNavigator(player)
        return had
    }

    fun isTracking(player: Player): Boolean = targets[player.uniqueId]?.isNotEmpty() == true

    /** 当前目标名列表（用于 Tab 补全/反馈）。 */
    fun targetNames(player: Player): List<String> = targets[player.uniqueId]?.map { it.name } ?: emptyList()

    /**
     * 与玩家同世界的全部追踪目标，按距离升序（供 [PacketWaypointRenderer] 渲染多路标用——
     * 一个追踪目标对应一个路标，颜色取 [NavTarget.hex]，与 BetterHud 弹窗/指南针同色）。
     * 跨世界目标不参与——没法对着另一个维度指方向。
     */
    fun sameWorldTargets(player: Player): List<NavTarget> {
        val list = targets[player.uniqueId] ?: return emptyList()
        val loc = player.location
        val world = loc.world?.name ?: return emptyList()
        return list.filter { it.world == world }.sortedBy { t ->
            val dx = t.x - loc.x; val dy = t.y - loc.y; val dz = t.z - loc.z
            dx * dx + dy * dy + dz * dz
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        // 保留 targets（重连后由 provider 自动恢复指针与显示），仅清掉弹窗句柄。
        // provider 只对在线/被追踪的 HudPlayer 调用，离线玩家不会产生孤儿指针。
        BetterHudHook.hideNavigator(event.player)
    }

    companion object {
        /** 默认颜色（未指定时）。 */
        const val DEFAULT_COLOR = "white"

        data class NavColor(val hex: String, val icon: String)

        /**
         * 命名颜色 -> (弹窗行文字 hex 去#, 指南针 custom-icon 名)。
         * icon 名需与 compass 配置里的 custom-icon、以及 assets 里的三角 PNG 一一对应。
         */
        val COLORS: Map<String, NavColor> = linkedMapOf(
            "white"  to NavColor("FFFFFF", "sf_nav_white"),
            "red"    to NavColor("FF5555", "sf_nav_red"),
            "orange" to NavColor("FFAA00", "sf_nav_orange"),
            "yellow" to NavColor("FFFF55", "sf_nav_yellow"),
            "green"  to NavColor("55FF55", "sf_nav_green"),
            "aqua"   to NavColor("55FFFF", "sf_nav_aqua"),
            "blue"   to NavColor("5555FF", "sf_nav_blue"),
            "pink"   to NavColor("FF55FF", "sf_nav_pink")
        )

        /** 解析结果：弹窗 hex（精确，可任意）+ 指南针三角 icon（命名预设/最接近）+ 显示标签。 */
        data class ResolvedColor(val hex: String, val icon: String, val label: String)

        private val HEX6 = Regex("[0-9a-fA-F]{6}")

        /**
         * 解析颜色输入：命名色 / #RRGGBB / RRGGBB。null 输入用默认白；非法返回 null。
         * 任意 hex 时弹窗用精确色，指南针三角取最接近的预设色。
         */
        fun resolveColor(input: String?): ResolvedColor? {
            if (input == null) {
                val c = COLORS.getValue(DEFAULT_COLOR)
                return ResolvedColor(c.hex, c.icon, DEFAULT_COLOR)
            }
            COLORS[input.lowercase()]?.let { return ResolvedColor(it.hex, it.icon, input.lowercase()) }
            val hex = input.removePrefix("#")
            if (HEX6.matches(hex)) {
                val up = hex.uppercase()
                return ResolvedColor(up, nearestPresetIcon(up), "#$up")
            }
            return null
        }

        /** 在预设调色板里取与给定 hex 最接近的三角 icon（RGB 欧氏距离）。 */
        private fun nearestPresetIcon(hex: String): String {
            val r = hex.substring(0, 2).toInt(16)
            val g = hex.substring(2, 4).toInt(16)
            val b = hex.substring(4, 6).toInt(16)
            return COLORS.values.minByOrNull {
                val pr = it.hex.substring(0, 2).toInt(16)
                val pg = it.hex.substring(2, 4).toInt(16)
                val pb = it.hex.substring(4, 6).toInt(16)
                val dr = r - pr; val dg = g - pg; val db = b - pb
                dr * dr + dg * dg + db * db
            }!!.icon
        }
    }
}
