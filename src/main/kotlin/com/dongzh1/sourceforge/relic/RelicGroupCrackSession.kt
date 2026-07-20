package com.dongzh1.sourceforge.relic

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 一次组队开箱的共享会话：[outcomes] 是"每个成功贡献了遗物的人各自独立抽到的一份结果"，
 * 全部结果对所有参与者可见、任意一份都能领(星际战甲式各取所需——不是"选完这一份就没了"，
 * 而是每人都能各自领取自己选中的那一份，同一下标被多人重复选中时各自都会现建一份新物品发放，
 * 互不影响)。[claimedBy] 记录每个参与者已经领取的下标，一人只能领一次。
 */
class RelicGroupCrackSession(val outcomes: List<Outcome>) {
    data class Outcome(val ownerId: UUID, val blueprintId: String)

    /** 参与者 uuid -> 已领取的 outcomes 下标。 */
    val claimedBy = ConcurrentHashMap<UUID, Int>()

    /** 参与者 uuid 集合(=outcomes 的 ownerId 去重)，会话创建时即固定，不随人员在线状态变动。 */
    val participantIds: Set<UUID> = outcomes.map { it.ownerId }.toSet()

    fun allClaimed(): Boolean = claimedBy.size >= participantIds.size
}
