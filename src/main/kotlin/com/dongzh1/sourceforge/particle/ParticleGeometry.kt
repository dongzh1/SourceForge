package com.dongzh1.sourceforge.particle

import org.bukkit.Location
import org.bukkit.util.Vector
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 纯数学的“粒子形状发生器”——只依赖 Bukkit [Vector]/[Location]，不碰任何发包/实体 API，可单元测试、可复用。
 *
 * 设计约定（务必牢记，参数自由度全靠它）：
 * 1. 所有形状先在【局部坐标系】生成一组相对偏移（[Vector]），局部系里：环/弧/盘位于 XY 平面、法线为 +Z；
 *    线/螺旋沿 +Z 前进。这样“朝向”只需把局部 +Z 对齐到某个世界方向 [dir]（见 [orient]）即可，语义统一。
 * 2. [orient] 用【方向向量】而非 yaw/pitch 直接构造正交基，规避 MC yaw 顺/逆时针、pitch 正负号这类经典符号坑。
 *    - dir = 玩家视线方向 → 形状“正对玩家看的方向”（传送门/护盾正面朝前）。
 *    - dir = (0,1,0)      → 形状躺平在地面（法线朝上），常用于脚下法阵/光环。
 * 3. Bukkit 的 [Vector] 方法（crossProduct/rotateAroundX/Y/Z/add/multiply）都是【就地修改并返回 this】，
 *    因此本类内部一律先 clone 再算，返回的每个点都是全新对象，调用方可安全再平移/缩放。
 *
 * 点数一律夹紧到 [MAX_POINTS]，避免脚本填个天文数字把服务器发包打爆（严格设计：不信任任何入参）。
 */
object ParticleGeometry {

    /** 单个形状最多生成的点数，防脚本/配置误填天量点数导致卡服。 */
    const val MAX_POINTS = 4096

    private val WORLD_UP = Vector(0.0, 1.0, 0.0)
    private val WORLD_FWD = Vector(0.0, 0.0, 1.0)

    private fun clampPoints(n: Int): Int = n.coerceIn(1, MAX_POINTS)

    // ============================ 朝向 / 旋转 ============================

    /**
     * 把一个【局部偏移】映射到世界朝向：局部 +Z 轴对齐到 [dir]，再绕 [dir] 施加 [rollDeg] 翻滚（度）。
     * 返回全新向量（不改入参）。dir 会被规范化；接近零向量时退化为原样返回（即不旋转）。
     *
     * 数学：以 forward=dir 建右手正交基 (right, up, forward)，
     * right = forward × worldUp（视线近乎垂直时退化用 worldForward 兜底，避免叉乘出零向量），
     * up = right × forward，然后 世界点 = x·right + y·up + z·forward。
     */
    fun orient(local: Vector, dir: Vector, rollDeg: Double = 0.0): Vector {
        val forward = dir.clone()
        if (forward.lengthSquared() < 1e-8) return local.clone()
        forward.normalize()

        // right = forward × up；视线几乎与世界 up 平行（抬头/低头到顶）时叉乘退化，改用 worldForward 兜底
        // 右手正交基：right = worldUp × forward，up = forward × right。此顺序 det=+1（dir=+Z 时为单位阵），
        // 局部 +X 正确映射到世界 +X（若写成 forward×worldUp 会得到 det=-1 的镜像左手系，非对称形状会翻手性）。
        var right = WORLD_UP.clone().crossProduct(forward)
        if (right.lengthSquared() < 1e-8) right = WORLD_FWD.clone().crossProduct(forward)
        if (right.lengthSquared() < 1e-8) right = Vector(1.0, 0.0, 0.0)
        right.normalize()
        val up = forward.clone().crossProduct(right).normalize()

        // 先在局部绕 +Z(=forward) 翻滚，再映射到世界基。翻滚等价于在 XY 平面旋转 (x,y)。
        var lx = local.x
        var ly = local.y
        val lz = local.z
        if (rollDeg != 0.0) {
            val r = Math.toRadians(rollDeg)
            val c = cos(r); val s = sin(r)
            val nx = lx * c - ly * s
            val ny = lx * s + ly * c
            lx = nx; ly = ny
        }
        return Vector(
            right.x * lx + up.x * ly + forward.x * lz,
            right.y * lx + up.y * ly + forward.y * lz,
            right.z * lx + up.z * ly + forward.z * lz
        )
    }

    /** 对整组局部偏移统一施加 [orient]。 */
    fun orientAll(locals: List<Vector>, dir: Vector, rollDeg: Double = 0.0): List<Vector> =
        locals.map { orient(it, dir, rollDeg) }

    /**
     * 显式欧拉旋转（备用，给想直接控 yaw/pitch/roll 的人）：先 roll(绕Z)→ pitch(绕X)→ yaw(绕Y)。
     * 角度单位为度。返回全新向量。
     */
    fun rotate(local: Vector, yawDeg: Double, pitchDeg: Double, rollDeg: Double): Vector {
        val v = local.clone()
        if (rollDeg != 0.0) v.rotateAroundZ(Math.toRadians(rollDeg))
        if (pitchDeg != 0.0) v.rotateAroundX(Math.toRadians(pitchDeg))
        if (yawDeg != 0.0) v.rotateAroundY(Math.toRadians(yawDeg))
        return v
    }

    // ============================ 基础形状（局部偏移） ============================

    /**
     * 直线/光束：从局部原点沿 +Z 走 [length] 格，均匀取 [points] 个点。
     * [skipOrigin]=true 时不含起点(第0点)，适合从法杖尖端往外打的光束。
     */
    fun line(length: Double, points: Int, skipOrigin: Boolean = false): List<Vector> {
        val n = clampPoints(points)
        if (n == 1) return listOf(Vector(0.0, 0.0, if (skipOrigin) length else 0.0))
        val out = ArrayList<Vector>(n)
        val start = if (skipOrigin) 1 else 0
        for (i in start until n) {
            val t = i.toDouble() / (n - 1)
            out.add(Vector(0.0, 0.0, t * length))
        }
        return out
    }

    /** 整圆（XY 平面、法线 +Z），半径 [radius]，均匀 [points] 点。等价 arc(0,360)。 */
    fun circle(radius: Double, points: Int): List<Vector> = arc(radius, 0.0, 360.0, points)

    /**
     * 圆弧 / 半圆（XY 平面、法线 +Z）：从 [startDeg] 扫到 [endDeg]，半径 [radius]，取 [points] 点。
     * - 半圆：start=0, end=180。整圆时首尾会重合，故整圆按“环形不含重复末点”处理。
     * - 角从 +X 轴起、朝 +Y 递增（数学正方向）。
     */
    fun arc(radius: Double, startDeg: Double, endDeg: Double, points: Int): List<Vector> {
        val n = clampPoints(points)
        val a0 = Math.toRadians(startDeg)
        val a1 = Math.toRadians(endDeg)
        val full = kotlin.math.abs(endDeg - startDeg) >= 359.999
        val out = ArrayList<Vector>(n)
        // 整圆：n 段闭合、不重复末点；非整圆：n 点含首含尾。
        val denom = if (full) n else (if (n == 1) 1 else n - 1)
        for (i in 0 until n) {
            val t = i.toDouble() / denom
            val ang = a0 + (a1 - a0) * t
            out.add(Vector(cos(ang) * radius, sin(ang) * radius, 0.0))
        }
        return out
    }

    /**
     * 实心圆盘（XY 平面、法线 +Z）：从中心向外 [rings] 圈，最外圈半径 [radius]。
     * 每圈点数随半径线性增长（外圈更密），近似均匀铺面。含一个中心点。
     */
    fun disc(radius: Double, rings: Int, pointsOuter: Int): List<Vector> {
        val r = rings.coerceIn(1, 128)
        val outer = pointsOuter.coerceIn(3, MAX_POINTS)
        val out = ArrayList<Vector>()
        out.add(Vector(0.0, 0.0, 0.0))
        for (ring in 1..r) {
            val rr = radius * ring / r
            val cnt = (outer * ring / r).coerceAtLeast(3)
            for (i in 0 until cnt) {
                if (out.size >= MAX_POINTS) return out
                val ang = 2.0 * PI * i / cnt
                out.add(Vector(cos(ang) * rr, sin(ang) * rr, 0.0))
            }
        }
        return out
    }

    /**
     * 球面壳（斐波那契球，点分布近似均匀），半径 [radius]，[points] 点。中心为局部原点。
     */
    fun sphere(radius: Double, points: Int): List<Vector> {
        val n = clampPoints(points)
        val out = ArrayList<Vector>(n)
        val golden = PI * (3.0 - sqrt(5.0)) // 黄金角
        for (i in 0 until n) {
            val y = if (n == 1) 0.0 else 1.0 - 2.0 * i / (n - 1) // y ∈ [1,-1]
            val rad = sqrt((1.0 - y * y).coerceAtLeast(0.0))
            val theta = golden * i
            out.add(Vector(cos(theta) * rad * radius, y * radius, sin(theta) * rad * radius))
        }
        return out
    }

    /**
     * 螺旋 / 龙卷（绕 +Z 前进）：半径 [radius]、总高 [height]、转 [turns] 圈、共 [points] 点。
     * [taper]=true 时半径从 0 线性张到 [radius]（漏斗/龙卷造型）。
     */
    fun helix(radius: Double, height: Double, turns: Double, points: Int, taper: Boolean = false): List<Vector> {
        val n = clampPoints(points)
        val out = ArrayList<Vector>(n)
        for (i in 0 until n) {
            val t = if (n == 1) 0.0 else i.toDouble() / (n - 1)
            val ang = 2.0 * PI * turns * t
            val rr = if (taper) radius * t else radius
            out.add(Vector(cos(ang) * rr, sin(ang) * rr, t * height))
        }
        return out
    }

    /**
     * 正多边形轮廓（XY 平面、法线 +Z）：[sides] 条边、外接半径 [radius]，每条边插 [perEdge] 个点。
     * [rotationDeg] 整体旋一个角（让顶点朝上/朝前）。sides>=2。
     */
    fun polygon(radius: Double, sides: Int, perEdge: Int, rotationDeg: Double = 0.0): List<Vector> {
        val s = sides.coerceIn(2, 64)
        val pe = perEdge.coerceIn(1, 256)
        val base = Math.toRadians(rotationDeg)
        val out = ArrayList<Vector>(s * pe)
        for (v in 0 until s) {
            val a0 = base + 2.0 * PI * v / s
            val a1 = base + 2.0 * PI * (v + 1) / s
            val p0x = cos(a0) * radius; val p0y = sin(a0) * radius
            val p1x = cos(a1) * radius; val p1y = sin(a1) * radius
            for (i in 0 until pe) {
                if (out.size >= MAX_POINTS) return out
                val t = i.toDouble() / pe
                out.add(Vector(p0x + (p1x - p0x) * t, p0y + (p1y - p0y) * t, 0.0))
            }
        }
        return out
    }

    // ============================ 世界坐标便捷 ============================

    /**
     * 身前水平扇形（“半圆弧斩击”的几何本体）：以 [origin] 为圆心，绕世界 Y 轴，
     * 在水平朝向 [facing]（会被拍平到 XZ 并归一化）两侧各扫 [arcDeg]/2 度，半径 [radius]，取 [points] 点。
     * [tiltDeg] 让扇面整体绕“左右轴”上下倾斜（斜劈）；[heightOffset] 抬高落点。
     * 直接返回世界 [Location]，可喂给渲染器；facing 近零时返回空。
     */
    fun horizontalFan(
        origin: Location,
        facing: Vector,
        radius: Double,
        arcDeg: Double,
        points: Int,
        tiltDeg: Double = 0.0,
        heightOffset: Double = 0.0
    ): List<Location> {
        val flat = facing.clone().setY(0.0)
        if (flat.lengthSquared() < 1e-8) return emptyList()
        flat.normalize()
        val n = clampPoints(points)
        val half = arcDeg / 2.0
        // 左右轴 = facing × up，用于施加“斜劈”倾斜
        val leftRight = flat.clone().crossProduct(WORLD_UP).normalize()
        val out = ArrayList<Location>(n)
        for (i in 0 until n) {
            val t = if (n == 1) 0.5 else i.toDouble() / (n - 1)
            val deg = -half + arcDeg * t
            val dir = flat.clone().rotateAroundY(Math.toRadians(deg))
            if (tiltDeg != 0.0) dir.rotateAroundNonUnitAxis(leftRight, Math.toRadians(tiltDeg))
            out.add(origin.clone().add(0.0, heightOffset, 0.0).add(dir.multiply(radius)))
        }
        return out
    }

    /** 把一组局部偏移平移成世界坐标点（origin + offset）。返回全新 [Location] 列表。 */
    fun toWorld(origin: Location, offsets: List<Vector>): List<Location> =
        offsets.map { origin.clone().add(it) }
}
