package com.dongzh1.sourceforge.multiblock

import java.util.ArrayList

/**
 * 绑定到核心方块的锻造作业。后台运行，UI 关闭/玩家离线/区块重载都继续。
 *
 * 必须可被 Kryo 序列化（镜像 GeneMachinery 的 MachineData 风格）：
 * - 无参构造 + 全部 var 字段
 * - 物品用 Base64 字符串（Bukkit 序列化）保存，避免 Kryo 直接序列化 ItemStack 的版本兼容问题
 * - 集合用具体的 ArrayList 类型
 */
class ForgeJob() {
    var coreWorld: String = ""
    var coreX: Int = 0
    var coreY: Int = 0
    var coreZ: Int = 0

    var blueprintId: String = ""
    var equipmentId: String = ""
    var tier: Int = 1
    var shellTier: String = "iron"
    var multiplier: Double = 1.0

    /** 锁定时消耗的输入材料快照（Base64 ItemStack），用于核心被破坏时退还。 */
    var materialsSnapshot: ArrayList<String> = ArrayList()

    var remainingTicks: Long = 0L
    /** "FORGING" / "DONE" */
    var state: String = STATE_FORGING

    /** 作业类型："craft"（锻造，默认）/ "enhance"（武器强化）。 */
    var mode: String = MODE_CRAFT

    /** enhance 模式下被强化的武器（Base64 ItemStack），craft 模式为 null。 */
    var inputItem: String? = null

    /** enhance 模式：强化后达到的目标段位。 */
    var enhanceTargetLevel: Int = 0

    /** 完成后产出的物品（Base64 ItemStack），未完成为 null。 */
    var outputItem: String? = null

    constructor(
        coreWorld: String,
        coreX: Int,
        coreY: Int,
        coreZ: Int,
        blueprintId: String,
        equipmentId: String,
        tier: Int,
        shellTier: String,
        multiplier: Double,
        materialsSnapshot: ArrayList<String>,
        remainingTicks: Long
    ) : this() {
        this.coreWorld = coreWorld
        this.coreX = coreX
        this.coreY = coreY
        this.coreZ = coreZ
        this.blueprintId = blueprintId
        this.equipmentId = equipmentId
        this.tier = tier
        this.shellTier = shellTier
        this.multiplier = multiplier
        this.materialsSnapshot = materialsSnapshot
        this.remainingTicks = remainingTicks
        this.state = STATE_FORGING
    }

    fun isDone(): Boolean = state == STATE_DONE

    companion object {
        const val STATE_FORGING = "FORGING"
        const val STATE_DONE = "DONE"
        const val MODE_CRAFT = "craft"
        const val MODE_ENHANCE = "enhance"
    }
}

/** 一个世界内的全部作业容器（Kryo 顶层对象）。键 = 打包的方块坐标 long。 */
class WorldForgeJobs() {
    var dataVersion: Int = CURRENT_VERSION
    var jobs: HashMap<Long, ForgeJob> = HashMap()

    companion object {
        const val CURRENT_VERSION = 1

        /** 把方块坐标打包成一个 long（与 GeneMachinery BlockPos.pack 同思路）。 */
        fun pack(x: Int, y: Int, z: Int): Long {
            // x/z 取 26 位，y 取 12 位
            return ((x.toLong() and 0x3FFFFFF) shl 38) or
                ((z.toLong() and 0x3FFFFFF) shl 12) or
                (y.toLong() and 0xFFF)
        }
    }
}
