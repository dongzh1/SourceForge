package com.dongzh1.sourceforge.particle

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.status.ElementType
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector

/**
 * 命中回调：粒子/形状“碰到”某个活体时，对它执行的动作。见 [ParticleEmitter.ActionsFactory] 里的现成实现（伤害/元素/击退/药水…）。
 * caster 可能为 null（例如无主的地面法阵）。所有回调都在主线程被调用。
 */
fun interface SkillAction {
    fun onHit(caster: Player?, target: LivingEntity)
}

/**
 * “粒子技能”动画发射器——把 [ParticleGeometry] + [ParticleRenderer] + [SfTargeters] + [SkillAction]
 * 拼成常用技能轮子：半圆弧斩击、直线弹体、扩散冲击环、瞬发光束。
 *
 * 统一约定（严格设计要点）：
 * - 全部通过 [ParticleRenderer] 出粒子（自动 PacketEvents/Bukkit），**一次 render 一批点**，不逐点各发一次。
 * - 命中判定复用 [SfTargeters]，与项目现有 slash 结算口径一致。
 * - 每个动画各跑一个**主线程**定时任务（读实体位置必须主线程）；施法者掉线/死亡/换世界即安全收尾。
 * - 命中去重：同一实体在一次技能生命周期内默认只命中一次（hitOnce）；可配重复命中冷却。
 * - 一切数值入口都夹紧，杜绝负半径/天量点数/无限弹体。
 */
class ParticleEmitter(private val plugin: SourceForge, private val renderer: ParticleRenderer) {

    // ============================ 半圆弧斩击（旗舰） ============================

    /**
     * 身前扇形斩击：在 [caster] 身前画一道 [arcDeg] 度的弧（半圆= 180），[radius] 格，命中扇形内活体执行 [action]。
     *
     * @param points        弧线粒子点数（视觉密度）。
     * @param tiltDeg       扇面上下倾斜角（斜劈；0=水平横扫）。
     * @param heightOffset  弧的离地高度（默认 1.0≈腰部）。
     * @param durationTicks 动画持续 tick 数（视觉扫动时长）。
     * @param sweep         true=弧线从一侧扫到另一侧（挥砍感）；false=整弧常亮。
     * @param maxTargets    最多命中几个（0=不限）。
     * @return 本次挥砍命中的目标数（命中在起手瞬间【同步】结算，挥砍即连；粒子扫动只是视觉）。
     */
    fun arcSlash(
        caster: Player,
        radius: Double = 4.0,
        arcDeg: Double = 180.0,
        points: Int = 24,
        tiltDeg: Double = 0.0,
        heightOffset: Double = 1.0,
        brush: SfParticle,
        durationTicks: Int = 4,
        sweep: Boolean = true,
        filter: SfTargeters.Filter = SfTargeters.Filter.ENEMIES,
        maxTargets: Int = 0,
        action: SkillAction
    ): Int {
        val r = radius.coerceIn(0.0, 64.0)
        val arc = arcDeg.coerceIn(0.0, 360.0)
        val pts = points.coerceIn(1, ParticleGeometry.MAX_POINTS)
        val dur = durationTicks.coerceIn(1, 200)

        // 起手瞬间快照施法者的位置与朝向：斩击是“瞬时挥砍”，动画期间不跟随移动，避免弧跟着人漂。
        val origin = caster.location.clone()
        val facing = caster.location.direction.clone()

        // 命中【同步】结算（挥砍瞬间连击，和现有 slash 口径一致，可直接返回命中数给脚本）。
        var count = 0
        val hitDone = HashSet<java.util.UUID>()
        for (e in SfTargeters.sector(origin, facing, r, arc, caster, filter)) {
            if (!hitDone.add(e.uniqueId)) continue
            runCatching { action.onHit(caster, e) }
            count++
            if (maxTargets in 1..count) break
        }

        // 动画（纯视觉）：sweep 逐帧揭示弧点，否则整弧常亮。
        val fan = ParticleGeometry.horizontalFan(origin, facing, r, arc, pts, tiltDeg, heightOffset)
        if (fan.isNotEmpty()) {
            object : BukkitRunnable() {
                var frame = 0
                override fun run() {
                    val revealed = if (sweep) {
                        val n = (fan.size * (frame + 1) / dur).coerceIn(1, fan.size)
                        fan.subList(0, n)
                    } else fan
                    renderer.render(revealed, brush)
                    if (++frame >= dur) cancel()
                }
            }.runTaskTimer(plugin, 0L, 1L)
        }
        return count
    }

    // ============================ 直线弹体 ============================

    /**
     * 直线飞行弹体：从 [caster] 视线方向发射，逐 tick 前进，碰到活体/方块结算。
     * 内部**子步进**（每步 ≤0.4 格）防高速穿怪穿墙。
     *
     * @param speed       每 tick 前进格数。
     * @param maxRange    最大射程（格）。
     * @param hitRadius   每步在弹头周围搜索目标的半径。
     * @param pad         目标边界盒膨胀量（细弹体也能打中宽身怪）。
     * @param pierce      true=穿透继续飞；false=命中即停。
     * @param maxHits     穿透时最多命中数（0=不限）。
     * @param stopOnBlock 撞到不可通行方块停下。
     * @param startOffset 出生点在眼前多少格（避免糊脸自碰）。
     */
    fun projectile(
        caster: Player,
        speed: Double = 0.8,
        maxRange: Double = 30.0,
        hitRadius: Double = 1.2,
        pad: Double = 0.3,
        pierce: Boolean = false,
        maxHits: Int = 0,
        stopOnBlock: Boolean = true,
        brush: SfParticle,
        startOffset: Double = 0.8,
        filter: SfTargeters.Filter = SfTargeters.Filter.ENEMIES,
        action: SkillAction
    ) {
        val spd = speed.coerceIn(0.05, 8.0)
        val range = maxRange.coerceIn(1.0, 256.0)
        val hitR = hitRadius.coerceIn(0.1, 8.0)
        val dir = caster.location.direction.clone()
        if (dir.lengthSquared() < 1e-8) return
        dir.normalize()
        val pos = caster.eyeLocation.clone().add(dir.clone().multiply(startOffset.coerceIn(0.0, 4.0)))
        val step = minOf(0.4, hitR).coerceAtLeast(0.05)
        val hitDone = HashSet<java.util.UUID>()

        object : BukkitRunnable() {
            var traveled = 0.0
            var hits = 0
            override fun run() {
                if (!caster.isOnline || caster.isDead) { cancel(); return }
                var advanced = 0.0
                while (advanced < spd) {
                    val stepLen = minOf(step, spd - advanced)
                    pos.add(dir.clone().multiply(stepLen))
                    advanced += stepLen
                    traveled += stepLen

                    if (stopOnBlock && runCatching { !pos.block.isPassable }.getOrDefault(false)) { cancel(); return }

                    // 用立方盒候选而非球形半径：弹头在眼高，球形按脚底距离裁剪会漏掉同层站立的怪（评审确认）
                    for (e in SfTargeters.boxCandidates(pos, maxOf(hitR, pad), caster, filter)) {
                        if (e.uniqueId in hitDone) continue
                        if (!SfTargeters.hitsBoundingBox(pos, e, pad)) continue
                        hitDone.add(e.uniqueId)
                        runCatching { action.onHit(caster, e) }
                        hits++
                        if (!pierce) { renderHead(); cancel(); return }
                        if (maxHits in 1..hits) { renderHead(); cancel(); return }
                    }
                    if (traveled >= range) { renderHead(); cancel(); return }
                }
                renderHead()
            }

            private fun renderHead() {
                renderer.render(listOf(pos.clone()), brush)
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    // ============================ 扩散冲击环 ============================

    /**
     * 地面扩散冲击环：以 [origin] 为心，半径从 [rStart] 涨到 [rEnd]，波锋扫过的活体各命中一次。
     * @param points 最外圈粒子点数（内圈按半径比例减少）。
     * @param heightOffset 环离地高度。
     */
    fun waveRing(
        origin: Location,
        caster: Player?,
        rStart: Double = 0.5,
        rEnd: Double = 6.0,
        durationTicks: Int = 10,
        points: Int = 40,
        heightOffset: Double = 0.2,
        brush: SfParticle,
        filter: SfTargeters.Filter = SfTargeters.Filter.ENEMIES,
        maxTargets: Int = 0,
        action: SkillAction
    ) {
        if (origin.world == null) return
        val r0 = rStart.coerceIn(0.0, 64.0)
        val r1 = rEnd.coerceIn(r0, 64.0)
        val dur = durationTicks.coerceIn(1, 200)
        val maxPts = points.coerceIn(4, ParticleGeometry.MAX_POINTS)
        val center = origin.clone().add(0.0, heightOffset, 0.0)
        val hitDone = HashSet<java.util.UUID>()

        object : BukkitRunnable() {
            var frame = 0
            var prevR = r0
            override fun run() {
                // 施法者掉线/死亡即收尾（与 projectile 一致，兑现类契约；null 施法者的地面法阵不受影响）
                if (caster != null && (!caster.isOnline || caster.isDead)) { cancel(); return }
                val t = if (dur == 1) 1.0 else frame.toDouble() / (dur - 1)
                val curR = r0 + (r1 - r0) * t
                // 渲染当前半径的水平环（点数随半径缩放）
                val cnt = (maxPts * (curR / r1).coerceIn(0.05, 1.0)).toInt().coerceAtLeast(6)
                val ringLocal = ParticleGeometry.orientAll(ParticleGeometry.circle(curR, cnt), Vector(0.0, 1.0, 0.0))
                renderer.renderLocal(center, ringLocal, brush)

                // 命中：波锋 [prevR, curR] 内活体
                var count = 0
                for (e in SfTargeters.ring(center, prevR, curR, caster, filter)) {
                    if (!hitDone.add(e.uniqueId)) continue
                    runCatching { action.onHit(caster, e) }
                    count++
                    if (maxTargets in 1..count) break
                }
                prevR = curR
                if (++frame >= dur) cancel()
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    // ============================ 瞬发光束 ============================

    /**
     * 瞬发光束：从 [caster] 眼睛沿视线画一条 [maxRange] 格的线，命中线上活体。撞方块则截断。
     * 光束通常穿透（[pierce]=true）命中沿途所有目标。渲染 [durationTicks] 帧后消失。
     * @param spacing 线上粒子点间距（格）。
     */
    fun beam(
        caster: Player,
        maxRange: Double = 24.0,
        spacing: Double = 0.4,
        pad: Double = 0.4,
        brush: SfParticle,
        stopOnBlock: Boolean = true,
        pierce: Boolean = true,
        maxTargets: Int = 0,
        durationTicks: Int = 3,
        filter: SfTargeters.Filter = SfTargeters.Filter.ENEMIES,
        action: SkillAction
    ): Int {
        val world = caster.world
        val dir = caster.location.direction.clone()
        if (dir.lengthSquared() < 1e-8) return 0
        dir.normalize()
        val start = caster.eyeLocation.clone()

        // 撞墙截断：射线到第一个方块的距离即有效射程
        val range = if (stopOnBlock) {
            val hit = runCatching { world.rayTraceBlocks(start, dir, maxRange.coerceIn(1.0, 256.0)) }.getOrNull()
            hit?.hitPosition?.let { start.toVector().distance(it) } ?: maxRange
        } else maxRange
        val effRange = range.coerceIn(0.0, 256.0)
        val sp = spacing.coerceIn(0.1, 4.0)
        val nPoints = (effRange / sp).toInt().coerceIn(1, ParticleGeometry.MAX_POINTS)

        // 预生成线点（世界坐标），动画期间复用
        val linePts = ArrayList<Location>(nPoints + 1)
        for (i in 0..nPoints) {
            val d = (i * sp).coerceAtMost(effRange)
            linePts.add(start.clone().add(dir.clone().multiply(d)))
        }

        // 命中一次结算：沿途活体（用线点逐个 bbox 判定）
        val searchCenter = start.clone().add(dir.clone().multiply(effRange / 2.0))
        var count = 0
        val done = HashSet<java.util.UUID>()
        for (e in SfTargeters.radius(searchCenter, effRange / 2.0 + 2.0, caster, filter)) {
            if (linePts.none { SfTargeters.hitsBoundingBox(it, e, pad) }) continue
            if (!done.add(e.uniqueId)) continue
            runCatching { action.onHit(caster, e) }
            count++
            if (!pierce) break
            if (maxTargets in 1..count) break
        }

        // 渲染若干帧
        val dur = durationTicks.coerceIn(1, 100)
        object : BukkitRunnable() {
            var frame = 0
            override fun run() {
                renderer.render(linePts, brush)
                if (++frame >= dur) cancel()
            }
        }.runTaskTimer(plugin, 0L, 1L)
        return count
    }

    // ============================ 现成命中动作 ============================

    /** 常用 [SkillAction] 工厂。都走 runCatching 兜底，绝不因单个目标异常打断整批命中。 */
    inner class ActionsFactory {

        /**
         * 造成 [amount] 点伤害（走 SF 战斗结算，damager=施法者）。
         * 命中前补算施法者武器上的锋利/横扫/亡灵杀手/截肢杀手加成 + 火焰附加点燃——技能命中是脚本直接
         * 调用 [LivingEntity.damage]，绕开了原版挥砍的 NMS 自动结算路径，这几个附魔不补算就形同虚设
         * （见 [com.dongzh1.sourceforge.enchant.VanillaEnchantBridge]）。
         */
        fun damage(amount: Double): SkillAction = SkillAction { caster, target ->
            runCatching {
                val bonus = caster?.let {
                    com.dongzh1.sourceforge.enchant.VanillaEnchantBridge.meleeBonusDamage(plugin, it, target)
                } ?: 0.0
                target.damage(amount.coerceAtLeast(0.0) + bonus, caster)
                caster?.let { com.dongzh1.sourceforge.enchant.VanillaEnchantBridge.igniteIfFireAspect(plugin, it, target) }
            }
        }

        /** 给目标叠 [stacks] 层元素 [typeId]（SF 原生“对目标执行技能”）。type 名无效则不做。 */
        fun element(typeId: String, stacks: Int = 1): SkillAction {
            val type = ElementType.fromId(typeId)
            return SkillAction { caster, target ->
                if (type != null) runCatching { plugin.statusManager.applyStacks(target, type, stacks.coerceAtLeast(1), caster) }
            }
        }

        /** 从目标背离施法者方向击退（带一点上抛）。无施法者则忽略。 */
        fun knockback(strength: Double, up: Double = 0.35): SkillAction = SkillAction { caster, target ->
            if (caster == null) return@SkillAction
            runCatching {
                val d = target.location.toVector().subtract(caster.location.toVector())
                d.y = 0.0
                if (d.lengthSquared() < 1e-6) d.x = 1.0
                d.normalize().multiply(strength.coerceAtLeast(0.0))
                target.velocity = Vector(d.x, up, d.z)
            }
        }

        /** 给目标加药水效果。type 名无效则不做。 */
        fun potion(typeName: String, ticks: Int, amplifier: Int): SkillAction = SkillAction { _, target ->
            val t = runCatching { PotionEffectType.getByName(typeName.uppercase()) }.getOrNull() ?: return@SkillAction
            runCatching { target.addPotionEffect(PotionEffect(t, ticks.coerceAtLeast(1), amplifier.coerceAtLeast(0), true, false, false)) }
        }

        /** 点燃目标 [seconds] 秒。 */
        fun ignite(seconds: Double): SkillAction = SkillAction { _, target ->
            runCatching { target.fireTicks = (seconds * 20).toInt().coerceAtLeast(0) }
        }

        /**
         * 命中时从施法者施放一个 MythicMobs 技能。
         * ⚠ 受现有 MythicMobsHook 限制：以【施法者】为 caster 施法（MM 内部按施法者的目标结算，
         * 未必精确指向本次命中的这只怪）；需要“精确对被命中目标施法”请扩展 MythicMobsHook 支持 target 参数。
         */
        fun mmCast(skillId: String): SkillAction = SkillAction { caster, _ ->
            if (caster != null) runCatching { com.dongzh1.sourceforge.enchant.MythicMobsHook().castSkill(caster, skillId) }
        }

        /** 组合多个动作，按序全部执行。 */
        fun all(vararg actions: SkillAction): SkillAction = SkillAction { caster, target ->
            for (a in actions) runCatching { a.onHit(caster, target) }
        }
    }

    /** 现成动作工厂入口：`emitter.actions.damage(6.0)` 等。 */
    val actions = ActionsFactory()
}
