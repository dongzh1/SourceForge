package com.dongzh1.sourceforge.particle

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Vector

/**
 * 目标选择器工具集（纯 Bukkit，主线程调用）：把项目里散落的“取附近实体 + 扇形/半径判定”统一成可复用轮子。
 *
 * 全部方法**只读取实体状态**（位置/边界盒），不做伤害/发包，因此可被技能结算、粒子碰撞、AI 等多处复用。
 * 距离判定优先用平方距离（省一次 sqrt）。凡涉及“朝向夹角”的都用 dot+acos，和现有 slash 结算保持一致，避免行为漂移。
 *
 * ⚠ 线程：Bukkit 实体 API 非线程安全，本类必须在主线程调用。
 */
object SfTargeters {

    /**
     * 目标过滤谓词（可组合）。默认值刻意与项目现有约定一致（见 slash / StatusEffectManager.nearbyOthers）：
     * 排除施法者本人、排除玩家（PvE 向）、排除死亡/失效实体、只要 LivingEntity。
     * PvP 或需要打玩家的技能把 [excludePlayers] 设 false 即可。[custom] 再叠加任意自定义条件。
     */
    class Filter(
        val excludeCaster: Boolean = true,
        val excludePlayers: Boolean = true,
        val requireLiving: Boolean = true,
        val custom: ((LivingEntity) -> Boolean)? = null
    ) {
        /** 判断一个实体是否通过过滤。caster 可空。 */
        fun test(e: Entity, caster: Entity?): Boolean {
            if (excludeCaster && caster != null && e.uniqueId == caster.uniqueId) return false
            if (e !is LivingEntity) return false
            if (e.isDead || !e.isValid) return false
            if (excludePlayers && e is Player) return false
            val c = custom
            if (c != null && !c(e)) return false
            return true
        }

        companion object {
            /** 常用：PvE，打怪不打玩家不打自己。 */
            val ENEMIES = Filter()
            /** 打包括玩家在内的所有活体（PvP/群体增益需要）。 */
            val ALL_LIVING = Filter(excludePlayers = false)
        }
    }

    /** 取 [center] 周围 [radius] 立方范围内、通过 [filter] 的活体。内部走 Bukkit getNearbyEntities（八叉树，高效）。 */
    fun radius(center: Location, radius: Double, caster: Entity?, filter: Filter = Filter.ENEMIES): List<LivingEntity> {
        val world = center.world ?: return emptyList()
        val r = radius.coerceAtLeast(0.0)
        val out = ArrayList<LivingEntity>()
        for (e in world.getNearbyEntities(center, r, r, r)) {
            if (!filter.test(e, caster)) continue
            e as LivingEntity
            // getNearbyEntities 是按立方盒粗筛，这里再用球形精确半径卡一遍
            if (e.location.distanceSquared(center) <= r * r) out.add(e)
        }
        return out
    }

    /**
     * 水平扇形（“半圆弧斩击”命中判定）：以 [origin] 为顶点，水平朝向 [facing]，
     * 命中 [radius] 内且与朝向水平夹角 ≤ [arcDeg]/2 的活体。Y 分量被拍平（和现有 slash 完全一致）。
     * arcDeg>=360 时退化为纯半径圆（全向）。
     */
    fun sector(
        origin: Location, facing: Vector, radius: Double, arcDeg: Double,
        caster: Entity?, filter: Filter = Filter.ENEMIES
    ): List<LivingEntity> {
        val dir = facing.clone().setY(0.0)
        if (dir.lengthSquared() < 1e-8) return emptyList()
        dir.normalize()
        val cands = radius(origin, radius, caster, filter)
        if (arcDeg >= 360.0) return cands
        val half = arcDeg / 2.0
        val out = ArrayList<LivingEntity>(cands.size)
        for (e in cands) {
            val to = e.location.toVector().subtract(origin.toVector()).setY(0.0)
            val toN = if (to.lengthSquared() < 1e-8) dir.clone() else to.normalize()
            val ang = Math.toDegrees(Math.acos(dir.dot(toN).coerceIn(-1.0, 1.0)))
            if (ang <= half) out.add(e)
        }
        return out
    }

    /**
     * 3D 圆锥（可朝天/朝地的锥形，如冲击波、龙息）：与 [sector] 同，但**不拍平 Y**，用完整三维夹角。
     */
    fun cone3d(
        origin: Location, dir: Vector, radius: Double, arcDeg: Double,
        caster: Entity?, filter: Filter = Filter.ENEMIES
    ): List<LivingEntity> {
        val d = dir.clone()
        if (d.lengthSquared() < 1e-8) return emptyList()
        d.normalize()
        val cands = radius(origin, radius, caster, filter)
        if (arcDeg >= 360.0) return cands
        val half = arcDeg / 2.0
        val out = ArrayList<LivingEntity>(cands.size)
        for (e in cands) {
            val to = e.location.toVector().subtract(origin.toVector())
            val toN = if (to.lengthSquared() < 1e-8) d.clone() else to.normalize()
            val ang = Math.toDegrees(Math.acos(d.dot(toN).coerceIn(-1.0, 1.0)))
            if (ang <= half) out.add(e)
        }
        return out
    }

    /**
     * 圆环 / 甜甜圈：命中水平距离 ∈ [[rMin], [rMax]] 的活体（脚下法阵外圈、冲击波环）。[maxY] 卡竖直层高。
     */
    fun ring(
        center: Location, rMin: Double, rMax: Double,
        caster: Entity?, filter: Filter = Filter.ENEMIES, maxY: Double = Double.MAX_VALUE
    ): List<LivingEntity> {
        val lo = rMin.coerceAtLeast(0.0); val hi = rMax.coerceAtLeast(lo)
        val world = center.world ?: return emptyList()
        val vy = if (maxY == Double.MAX_VALUE) hi else maxY
        val out = ArrayList<LivingEntity>()
        for (e in world.getNearbyEntities(center, hi, vy, hi)) {
            if (!filter.test(e, caster)) continue
            e as LivingEntity
            val dx = e.location.x - center.x
            val dz = e.location.z - center.z
            val d2 = dx * dx + dz * dz
            if (d2 in (lo * lo)..(hi * hi) && kotlin.math.abs(e.location.y - center.y) <= vy) out.add(e)
        }
        return out
    }

    /** 立方盒范围（半宽 [dx]/[dy]/[dz]）。用于关卡区域触发、矩形 AoE。 */
    fun box(
        center: Location, dx: Double, dy: Double, dz: Double,
        caster: Entity?, filter: Filter = Filter.ENEMIES
    ): List<LivingEntity> {
        val world = center.world ?: return emptyList()
        val out = ArrayList<LivingEntity>()
        for (e in world.getNearbyEntities(center, dx.coerceAtLeast(0.0), dy.coerceAtLeast(0.0), dz.coerceAtLeast(0.0))) {
            if (!filter.test(e, caster)) continue
            out.add(e as LivingEntity)
        }
        return out
    }

    /**
     * 点碰撞候选：返回 [center] 周围立方盒（半宽 [half]）内、通过 [filter] 的活体，**不做球形半径裁剪**。
     * 专供弹体/光束逐点 bbox 碰撞用——弹头常在眼高，若按脚底球形距离裁剪会漏掉同层站立的怪（评审确认的坑）。
     */
    fun boxCandidates(center: Location, half: Double, caster: Entity?, filter: Filter = Filter.ENEMIES): List<LivingEntity> {
        val world = center.world ?: return emptyList()
        val h = half.coerceAtLeast(0.0)
        val out = ArrayList<LivingEntity>()
        for (e in world.getNearbyEntities(center, h, h, h)) {
            if (filter.test(e, caster)) out.add(e as LivingEntity)
        }
        return out
    }

    /** 取范围内最近的一个（不满足返回 null）。 */
    fun nearest(center: Location, radius: Double, caster: Entity?, filter: Filter = Filter.ENEMIES): LivingEntity? =
        radius(center, radius, caster, filter).minByOrNull { it.location.distanceSquared(center) }

    /**
     * 弹体点碰撞：世界点 [point] 是否击中实体 [e]（用实体**边界盒**+[pad] 膨胀，命中判定更贴合模型，
     * 细弹体也能打中宽身怪）。给 [ParticleEmitter] 的直线弹体/光束逐点碰撞用。
     */
    fun hitsBoundingBox(point: Location, e: LivingEntity, pad: Double = 0.3): Boolean {
        val box = e.boundingBox.clone().expand(pad)
        return box.contains(point.x, point.y, point.z)
    }
}
