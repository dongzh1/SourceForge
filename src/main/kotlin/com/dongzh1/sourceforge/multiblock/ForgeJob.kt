package com.dongzh1.sourceforge.multiblock

/**
 * 源质锻炉作业(2026-07-13 重构：墙钟计时 + MySQL 持久化)。
 * 时间模型：只存 startedAtMillis(提交时的真实时间戳) + totalDurationTicks(提交时算好、此后不再变的
 * 总时长)，剩余/已过时间都是即时算出来的，没有"每 tick 递减一个计数器"这种需要频繁存盘的可变状态——
 * 服务器下线期间作业照常按真实时间推进，重启后用当前时间重新算一遍就好，不存在"崩服丢进度"的问题
 * (这是旧版 remainingTicks 倒计时模型 + 文件"脏标记"批量存盘机制的已知缺陷)。
 * 持久化后端见 ForgeDatabase(MySQL)，不再是本地 Kryo 文件(ForgeJobStore 已删除)。
 */
class ForgeJob(
    var coreWorld: String = "",
    var coreX: Int = 0,
    var coreY: Int = 0,
    var coreZ: Int = 0,

    var blueprintId: String = "",
    var equipmentId: String = "",
    var tier: Int = 1,
    var shellTier: String = "iron",
    var multiplier: Double = 1.0,

    /** 锁定时消耗的输入材料快照（Base64 ItemStack），用于核心/外壳被拆时退还。 */
    var materialsSnapshot: MutableList<String> = mutableListOf(),

    /** 该作业占用的全部锻炉方块(核心 + 26 块外壳)的打包坐标；占用期间(提交→收取)既不能被拆除，
     * 也不能被另一个结构重复占用——见 ForgeStructureManager 的 claim 索引与 ForgeStructure.validate。 */
    var claimedBlocks: MutableList<Long> = mutableListOf(),

    /** 提交时刻的真实时间戳(epoch millis)。 */
    var startedAtMillis: Long = 0L,
    /** 提交时算好的总时长(tick)，此后不再变化——即使外壳倍率之后有调整也不影响已提交的作业。 */
    var totalDurationTicks: Long = 0L,

    /** "FORGING" / "DONE" */
    var state: String = STATE_FORGING,

    /** 作业类型："craft"（锻造，默认）/ "enhance"（武器强化）/ "upgrade"（蓝图原地重铸）。 */
    var mode: String = MODE_CRAFT,

    /** enhance/upgrade 模式下被操作的武器（Base64 ItemStack），craft 模式为 null。 */
    var inputItem: String? = null,

    /** enhance 模式：强化后达到的目标段位。 */
    var enhanceTargetLevel: Int = 0,

    /** 完成后产出的物品（Base64 ItemStack），未完成为 null。 */
    var outputItem: String? = null
) {
    /** 已经过去的 tick 数，按真实时间算(不依赖服务器是否在线/在运行)。 */
    fun elapsedTicks(nowMillis: Long = System.currentTimeMillis()): Long =
        ((nowMillis - startedAtMillis) / 50L).coerceAtLeast(0L)

    /** 剩余 tick 数，钳制在 [0, totalDurationTicks]。 */
    fun remainingTicks(nowMillis: Long = System.currentTimeMillis()): Long {
        val total = totalDurationTicks.coerceAtLeast(0L)
        return (total - elapsedTicks(nowMillis)).coerceIn(0L, total)
    }

    fun isTimeElapsed(nowMillis: Long = System.currentTimeMillis()): Boolean =
        elapsedTicks(nowMillis) >= totalDurationTicks

    fun isDone(): Boolean = state == STATE_DONE

    /** 不可变快照拷贝：落盘要甩去异步线程时用，避免后台线程和主线程并发读写同一个可变 ForgeJob。 */
    fun copy(): ForgeJob = ForgeJob(
        coreWorld, coreX, coreY, coreZ,
        blueprintId, equipmentId, tier, shellTier, multiplier,
        materialsSnapshot.toMutableList(), claimedBlocks.toMutableList(),
        startedAtMillis, totalDurationTicks, state, mode, inputItem, enhanceTargetLevel, outputItem
    )

    companion object {
        const val STATE_FORGING = "FORGING"
        const val STATE_DONE = "DONE"
        const val MODE_CRAFT = "craft"
        const val MODE_ENHANCE = "enhance"
        const val MODE_UPGRADE = "upgrade"

        /** 把方块坐标打包成一个 long(与旧版 WorldForgeJobs.pack 算法相同，坐标索引格式不变)。 */
        fun pack(x: Int, y: Int, z: Int): Long {
            return ((x.toLong() and 0x3FFFFFF) shl 38) or
                ((z.toLong() and 0x3FFFFFF) shl 12) or
                (y.toLong() and 0xFFF)
        }
    }
}
