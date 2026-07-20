package com.dongzh1.sourceforge.api

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.item.CraftEngineHook
import com.dongzh1.sourceforge.relic.RelicCrackResult
import com.dongzh1.sourceforge.relic.RelicCrackStatus
import com.dongzh1.sourceforge.relic.RelicEntry
import com.dongzh1.sourceforge.relic.RelicGroupCrackListener
import com.dongzh1.sourceforge.relic.RelicGroupCrackSession
import com.dongzh1.sourceforge.relic.RelicInventoryScan
import com.dongzh1.sourceforge.relic.RelicKeys
import com.dongzh1.sourceforge.relic.TasksBridge
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * SourceForge 对外公开遗物开蓝图 API。供 SourceWild/PixelRPG compileOnly + 真调用消费。
 * SF 未启用前调用静默降级（返回 null）。
 *
 * "遗物解锁任务绑定/查询"收拢说明：谁用哪个SourceTasks任务解锁、任务是否完成，均由 SF 这边
 * 决策（复用 [com.dongzh1.sourceforge.relic.RelicService.rollUnlockTask] 加权抽取 + [TasksBridge]
 * 桥接调用），SourceWild 只需要传 Player+ItemStack 拿结果，不需要认识 SourceTasks/任务id/PDC 细节。
 */
object SourceForgeRelicAPI {

    @Volatile
    private var plugin: SourceForge? = null

    @JvmStatic
    fun bind(instance: SourceForge) {
        plugin = instance
    }

    /** 按遗物 CE id 加权抽取蓝图 CE id；遗物未配置/SF 未启用返回 null。 */
    @JvmStatic
    fun crack(relicCeId: String): String? = plugin?.relicService?.crack(relicCeId)

    /** 这个 CE id 是不是任意一种已配置的遗物；SF 未启用返回 false。给"随便捡到一个遗物"这类
     *  引导任务判定用，不用外部逐个枚举遗物 id 列表。 */
    @JvmStatic
    fun isRelic(ceId: String): Boolean = plugin?.relicService?.isRelic(ceId) ?: false

    /** 这个 CE id 是不是任意一种蓝图（遗物权重池里配置过的产出）；SF 未启用返回 false。 */
    @JvmStatic
    fun isBlueprint(ceId: String): Boolean = plugin?.relicService?.isBlueprint(ceId) ?: false

    /** 按遗物CE id加权抽取一个"解锁用SourceTasks任务id"；该遗物类型没配解锁池(比如武器遗物)/SF未启用返回null。 */
    @JvmStatic
    fun rollUnlockTask(relicCeId: String): String? = plugin?.unlockTaskForRelic(relicCeId)

    /**
     * 如果这个物品是配置了"解锁池"的遗物(relics.yml里有unlock:字段)且这个具体实例还没绑定过，
     * 就按权重抽一个SourceTasks任务id、写进这个物品的PDC、并让玩家接取该任务。
     * 非遗物/已经绑定过/该遗物类型没配解锁池(比如武器遗物)：什么都不做，静默返回。
     *
     * 不单独判断"是不是已知遗物"——rollUnlockTask 对未知 relicId 和已知但 unlock 池为空两种情况
     * 都统一返回 null（见 RelicService.rollUnlockTask 对 config.relics[relicId]?.unlock 的取值链），
     * 直接吃 null 短路即可，不需要额外前置检查。
     */
    @JvmStatic
    fun bindUnlockIfNeeded(player: Player, item: ItemStack) {
        val p = plugin ?: return
        val relicCeId = CraftEngineHook.itemId(item) ?: return
        val meta = item.itemMeta ?: return
        val key = RelicKeys.relicQuest(p)
        if (meta.persistentDataContainer.has(key, PersistentDataType.STRING)) return
        val questId = p.unlockTaskForRelic(relicCeId) ?: return
        meta.persistentDataContainer.set(key, PersistentDataType.STRING, questId)
        item.itemMeta = meta
        TasksBridge.startQuest(player, questId)
    }

    /** 这个物品实例是否绑定了解锁任务(不代表是否已完成)。非遗物/没配解锁池(比如武器遗物)返回false。 */
    @JvmStatic
    fun hasBoundUnlock(item: ItemStack): Boolean {
        val p = plugin ?: return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(RelicKeys.relicQuest(p), PersistentDataType.STRING)
    }

    /** 这个物品实例绑定的解锁任务是否已完成。没绑定任务时返回false(调用方应理解成"这条路不适用"而非"未解锁")。 */
    @JvmStatic
    fun isUnlocked(player: Player, item: ItemStack): Boolean {
        val p = plugin ?: return false
        val meta = item.itemMeta ?: return false
        val questId = meta.persistentDataContainer.get(RelicKeys.relicQuest(p), PersistentDataType.STRING) ?: return false
        return TasksBridge.isCompleted(player, questId)
    }

    /**
     * 玩家背包(含护甲/副手、以及其中潜影盒内部一层)里任意一种已配置遗物(relics.yml)的数量总和。
     * 判定按 relicService.isRelic(ceId) 走(配置驱动)，不是硬编码前缀字符串——SourceWild/PixelRPG
     * 的携带上限检查、SourceForgeRelicAPI 自身都应该经这个口子拿数量，而不是各自扫背包。
     * SF 未启用返回 0。
     */
    @JvmStatic
    fun relicCount(player: Player): Int {
        val p = plugin ?: return 0
        return RelicInventoryScan.countMatching(player) { item ->
            val ceId = CraftEngineHook.itemId(item) ?: return@countMatching false
            p.relicService.isRelic(ceId)
        }
    }

    /**
     * 开一次遗物抽奖(通用路径)：在玩家背包(含潜影盒)里找第一个"已配置遗物 且 配置了解锁池 且 已解锁"
     * 的物品，精确消耗 1 个、抽一张蓝图并发放。没配置解锁池的遗物(比如武器遗物)不会被这条通用路径
     * 选中——它们只能被调用方通过 [crackRelicById] 定向开箱，见该方法与 [Attempt] 的说明。
     * 顺序是 找到 -> 滚RNG -> 建物品 -> 才真正扣背包，RNG或建物品失败都不会碰背包，保证"要么完全
     * 不消耗，要么正好消耗一个"，不会出现半途消耗的中间态。SF 或 CraftEngine 未启用返回
     * status=[RelicCrackStatus.UNAVAILABLE]。
     */
    @JvmStatic
    fun crackRelic(player: Player): RelicCrackResult = soloCrack(player, null)

    /**
     * 开一次遗物抽奖(定向路径)：只在背包(含潜影盒)里找 CE id 精确等于 [relicId] 的物品，不检查
     * 解锁池/解锁状态——信任调用方自己的业务门槛(比如 PixelRPG 副本"打到第10波"才算合格)。
     * 供没有配置解锁池的遗物类型(武器遗物)使用；其余行为(原子性/失败不碰背包)与 [crackRelic] 相同。
     */
    @JvmStatic
    fun crackRelicById(player: Player, relicId: String): RelicCrackResult = soloCrack(player, relicId)

    private fun soloCrack(player: Player, relicId: String?): RelicCrackResult {
        val p = plugin ?: return RelicCrackResult(RelicCrackStatus.UNAVAILABLE, null, null, false)
        if (!CraftEngineHook.enabled) return RelicCrackResult(RelicCrackStatus.UNAVAILABLE, null, null, false)
        return when (val outcome = attempt(p, player, relicId)) {
            is Attempt.NoEligibleRelic -> RelicCrackResult(RelicCrackStatus.NO_ELIGIBLE_RELIC, null, null, false)
            is Attempt.NoBlueprintConfigured ->
                RelicCrackResult(RelicCrackStatus.NO_BLUEPRINT_CONFIGURED, outcome.relicId, null, false)
            is Attempt.BuildFailed ->
                RelicCrackResult(RelicCrackStatus.BLUEPRINT_BUILD_FAILED, outcome.relicId, outcome.rewardId, false)
            is Attempt.Found -> {
                RelicInventoryScan.consumeOne(player, outcome.slot)
                var overflowed = false
                player.inventory.addItem(outcome.built).values.forEach {
                    player.world.dropItemNaturally(player.location, it)
                    overflowed = true
                }
                dreammarkUnveilQuest(p, outcome.relicId, outcome.reward)?.let { TasksBridge.startQuest(player, it) }
                RelicCrackResult(RelicCrackStatus.SUCCESS, outcome.relicId, rewardId(outcome.reward), overflowed)
            }
        }
    }

    /**
     * 组队开箱(通用路径，供 SourceWild 用)：对 [players] 里每个人各跑一次"找到但不消耗"的尝试——
     * 通用可开判定同 [crackRelic]。每个成功的人立刻精确消耗自己背包里的 1 个遗物(不等界面领取时才扣，
     * 开箱那一刻就已经"砸开"了，界面只是选结果，跟星际战甲一致)，收集成结果列表。0 人成功=什么也不做，
     * 返回 0；≥1 人成功=开一个 [RelicGroupCrackListener] 管理的共享选择界面(哪怕只有 1 人成功也照样
     * 开界面，不做"单人直接发"的特殊分支)。返回值=成功参与人数，调用方不需要理会。
     */
    @JvmStatic
    fun openGroupCrack(players: List<Player>): Int = openGroup(players, null)

    /** 组队开箱(定向路径，供 PixelRPG 用)：同 [openGroupCrack]，但按 [relicId] 定向查找，语义同 [crackRelicById]。 */
    @JvmStatic
    fun openGroupCrackById(players: List<Player>, relicId: String): Int = openGroup(players, relicId)

    private fun openGroup(players: List<Player>, relicId: String?): Int {
        val p = plugin ?: return 0
        if (!CraftEngineHook.enabled) return 0
        val outcomes = mutableListOf<RelicGroupCrackSession.Outcome>()
        val participants = mutableListOf<Player>()
        for (player in players) {
            val outcome = attempt(p, player, relicId, groupOnly = true)
            if (outcome !is Attempt.Found) continue
            val blueprintId = outcome.reward.blueprintId ?: continue
            RelicInventoryScan.consumeOne(player, outcome.slot)
            outcomes.add(RelicGroupCrackSession.Outcome(player.uniqueId, blueprintId))
            participants.add(player)
        }
        if (outcomes.isEmpty()) return 0
        RelicGroupCrackListener.open(p, RelicGroupCrackSession(outcomes), participants)
        return outcomes.size
    }

    /** [attempt] 的结果：只在本类内部使用，不跨越 SourceWild/PixelRPG 的 compileOnly 边界。 */
    private sealed class Attempt {
        data class Found(
            val slot: RelicInventoryScan.Slot,
            val relicId: String,
            val reward: RelicEntry,
            val built: ItemStack
        ) : Attempt()
        object NoEligibleRelic : Attempt()
        data class NoBlueprintConfigured(val relicId: String) : Attempt()
        data class BuildFailed(val relicId: String, val rewardId: String?) : Attempt()
    }

    /**
     * "找到但先不消耗"的共用逻辑，solo(soloCrack)/组队(openGroup)两条路都复用，各自决定何时真正
     * consumeOne——原子性保证(先滚RNG、建好物品，最后才扣背包)在这一层统一实现，不用两处各写一遍。
     * [relicId] 为 null 时走通用路径判定(已配置遗物 且 配置了解锁池 且 已解锁)，非 null 时走定向路径
     * (只看 CE id 是否等于 relicId，不检查解锁池)。
     */
    private fun attempt(p: SourceForge, player: Player, relicId: String?, groupOnly: Boolean = false): Attempt {
        val found = RelicInventoryScan.findFirst(player) { item ->
            val ceId = CraftEngineHook.itemId(item) ?: return@findFirst false
            if (relicId != null) {
                ceId == relicId && (!groupOnly || p.relicService.supportsGroupCrack(ceId))
            } else {
                p.relicService.isRelic(ceId) && p.hasUnlockPool(ceId) &&
                    (!groupOnly || p.relicService.supportsGroupCrack(ceId)) && isUnlocked(player, item)
            }
        } ?: return Attempt.NoEligibleRelic
        val (slot, item) = found
        val foundRelicId = CraftEngineHook.itemId(item) ?: return Attempt.NoEligibleRelic
        val reward = p.relicService.rollEntry(foundRelicId) ?: return Attempt.NoBlueprintConfigured(foundRelicId)
        val built = buildReward(p, foundRelicId, reward) ?: return Attempt.BuildFailed(foundRelicId, rewardId(reward))
        return Attempt.Found(slot, foundRelicId, reward, built)
    }

    private fun buildReward(plugin: SourceForge, relicId: String, reward: RelicEntry): ItemStack? = when {
        reward.isDreammark -> {
            val definition = dreammarkDefinition(plugin, relicId, reward)
            definition?.let { plugin.rivenService.createVeiled(it) }
                ?: plugin.rivenService.createVeiled(
                    reward.dreammarkGroup?.takeUnless { it.equals("random", ignoreCase = true) }
                )
        }
        !reward.blueprintId.isNullOrBlank() -> CraftEngineHook.build(reward.blueprintId, 1)
        else -> null
    }

    private fun dreammarkDefinition(plugin: SourceForge, relicId: String, reward: RelicEntry) =
        reward.dreammarkProfileId?.let { plugin.dreammarkRelics.get(it) }
            ?: plugin.dreammarkRelics.get(relicId)
            ?: reward.dreammarkGroup?.let { plugin.dreammarkRelics.get(it) }

    private fun dreammarkUnveilQuest(plugin: SourceForge, relicId: String, reward: RelicEntry): String? =
        dreammarkDefinition(plugin, relicId, reward)?.unveilQuestId ?: reward.dreammarkQuestId

    private fun rewardId(reward: RelicEntry): String? =
        if (reward.isDreammark) "sourceforge:dreammark" else reward.blueprintId

    /**
     * 扫描玩家背包(含潜影盒)里所有已配置遗物，逐个 bindUnlockIfNeeded(已绑定的跳过、非遗物跳过)。
     * 替代旧版"只扫顶层背包"的解锁任务绑定：遗物塞进潜影盒也要能正常绑定解锁任务，否则
     * crackRelic() 会把"从没绑定过"误判成"无需解锁"，等于绕过任务解锁玩法。返回本次实际绑定的数量。
     */
    @JvmStatic
    fun bindAllUnlocksIfNeeded(player: Player): Int {
        val p = plugin ?: return 0
        var count = 0
        RelicInventoryScan.scan(player) { slot, item ->
            val ceId = CraftEngineHook.itemId(item)
            if (ceId != null && p.relicService.isRelic(ceId)) {
                // 只有"绑定前还没绑定、绑定后确实绑上了"才算一次真实绑定——bindUnlockIfNeeded
                // 对已绑定过/该遗物无解锁池(比如武器遗物)是静默 no-op，不能无脑 count++。
                val alreadyBound = hasBoundUnlock(item)
                bindUnlockIfNeeded(player, item)
                if (!alreadyBound && hasBoundUnlock(item)) count++
                RelicInventoryScan.writeBack(player, slot, item)
            }
            true
        }
        return count
    }
}
