package com.dongzh1.sourceforge.multiblock

import com.dongzh1.sourceforge.item.CraftEngineHook
import org.bukkit.block.Block

/**
 * 源质锻炉结构定义：3×3×3 实心立方，核心方块裸露在某一竖直侧面的正中心。
 *
 * 核心不再埋在几何中心（否则 26 块外壳摆满后核心被包住、无法点击），而是位于一个
 * 竖直侧面的中心，立方体向内（背离裸露面的水平方向）延伸三格。校验时自动尝试 4 个
 * 水平方向，只要某个方向使其余 26 格全为同层级外壳即判定结构成立——因此玩家可以把
 * 核心放在任意一个侧面中心，无需关心朝向。
 */
data class StructureResult(
    val formed: Boolean,
    val tier: String?,
    val multiplier: Double,
    val missing: Int,
    val mixed: Boolean
)

object ForgeStructure {
    /** 4 个水平“向内”方向：(ux, uz)。核心裸露面背向该方向。 */
    private val INWARD: List<Pair<Int, Int>> = listOf(
        0 to -1, // 向北
        0 to 1,  // 向南
        1 to 0,  // 向东
        -1 to 0  // 向西
    )

    /**
     * 给定“向内”方向，返回核心周围 26 个外壳偏移。
     * 立方体：d∈0..2(沿向内方向)、p∈-1..1(水平垂直方向)、y∈-1..1；核心在 (d=0,p=0,y=0)，即裸露侧面中心。
     */
    private fun offsetsFor(ux: Int, uz: Int): List<Triple<Int, Int, Int>> {
        val px = -uz // 水平垂直方向 = 向内方向逆时针旋转 90°
        val pz = ux
        return buildList {
            for (d in 0..2) for (p in -1..1) for (y in -1..1) {
                if (d == 0 && p == 0 && y == 0) continue // 跳过核心自身
                add(Triple(d * ux + p * px, y, d * uz + p * pz))
            }
        }
    }

    /**
     * 以 coreBlock 为某一侧面中心校验整个立方。需要 coreBlock 本身已确认为核心方块（调用方负责）。
     * 自动尝试 4 个水平方向：任一方向完整且层级一致则成立；否则返回“缺口最少”的方向用于提示。
     */
    fun validate(coreBlock: Block, config: ForgeStructureConfig): StructureResult {
        var best: StructureResult? = null
        for ((ux, uz) in INWARD) {
            val result = validateDirection(coreBlock, config, ux, uz)
            if (result.formed) return result
            if (best == null || result.missing < best.missing) best = result
        }
        return best ?: StructureResult(false, null, 1.0, 26, false)
    }

    private fun validateDirection(
        coreBlock: Block,
        config: ForgeStructureConfig,
        ux: Int,
        uz: Int
    ): StructureResult {
        val world = coreBlock.world
        val cx = coreBlock.x
        val cy = coreBlock.y
        val cz = coreBlock.z

        val tierCounts = HashMap<String, Int>()
        var missing = 0

        for ((dx, dy, dz) in offsetsFor(ux, uz)) {
            val block = world.getBlockAt(cx + dx, cy + dy, cz + dz)
            val ceId = CraftEngineHook.blockId(block)
            val tier = ceId?.let { config.shellIdToTier[it] }
            if (tier == null) {
                missing++
            } else {
                tierCounts[tier] = (tierCounts[tier] ?: 0) + 1
            }
        }

        // 有缺口
        if (missing > 0) {
            return StructureResult(
                formed = false,
                tier = null,
                multiplier = 1.0,
                missing = missing,
                mixed = tierCounts.size > 1
            )
        }
        // 无缺口但层级不一致
        if (tierCounts.size > 1) {
            return StructureResult(formed = false, tier = null, multiplier = 1.0, missing = 0, mixed = true)
        }
        // 完整且一致
        val tier = tierCounts.keys.first()
        return StructureResult(
            formed = true,
            tier = tier,
            multiplier = config.multiplierOf(tier),
            missing = 0,
            mixed = false
        )
    }
}
