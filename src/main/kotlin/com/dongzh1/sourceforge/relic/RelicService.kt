package com.dongzh1.sourceforge.relic

import kotlin.random.Random

/**
 * 遗物开蓝图的加权抽取服务。纯数据+RNG，不涉及 CraftEngine 物品构建/发放——
 * 发放交给调用方（SourceWild/PixelRPG）用它们各自已有的 CE 物品构建工具完成。
 */
class RelicService(private val config: RelicConfig) {

    /** 按遗物 CE id 加权抽取一个蓝图 CE id。遗物未配置/权重池为空返回 null。 */
    fun crack(relicId: String): String? {
        return rollEntry(relicId)?.blueprintId
    }

    /** 按遗物 CE id 加权抽取一份完整奖励定义。 */
    fun rollEntry(relicId: String): RelicEntry? {
        val entries = config.relics[relicId]?.entries
            ?.filter { it.weight > 0.0 && (it.isDreammark || !it.blueprintId.isNullOrBlank()) }
            ?: return null
        if (entries.isEmpty()) return null
        return weightedPick(entries.map { it to it.weight })
    }

    /** 按遗物 CE id 加权抽取一个用于解锁的 SourceTasks 任务id。遗物未配置解锁池/权重池为空返回 null。 */
    fun rollUnlockTask(relicId: String): String? {
        val entries = config.relics[relicId]?.unlock?.filter { it.weight > 0.0 } ?: return null
        if (entries.isEmpty()) return null
        return weightedPick(entries.map { it.questId to it.weight })
    }

    /** 这个 CE id 是不是任意一种已配置的遗物（relics.yml 顶层键）。供外部"任意遗物"类判定用
     *  （比如 SourceTasks 的引导任务只要求"随便捡到一个遗物"，不关心具体是哪一种）。 */
    fun isRelic(ceId: String): Boolean = ceId in config.relics

    /** 这个 CE id 是不是任意一种遗物权重池里配置过的蓝图产出（relics.yml 各遗物 entries.blueprint）。
     *  同上，供"任意蓝图"类判定用。 */
    fun isBlueprint(ceId: String): Boolean = config.relics.values.any { def -> def.entries.any { it.blueprintId == ceId } }

    /** relics.yml 里配置过的全部遗物 CE id。供 /sf giverelic 的校验与 tab 补全用。 */
    fun relicIds(): Set<String> = config.relics.keys

    /** 这个遗物是否配置了"解锁池"(relics.yml 里有 unlock: 且非空，比如护甲遗物)。没配置的
     *  遗物(比如武器遗物)不受任务解锁约束，也因此不参与"通用路径"的自动开箱扫描——
     *  通用路径只认"配置了解锁池且已完成"的遗物，没配置解锁池的遗物只能被调用方按 id 定向开箱。 */
    fun hasUnlockPool(relicId: String): Boolean = config.relics[relicId]?.unlock?.isNotEmpty() == true

    /** 只有全部奖励都是静态蓝图的遗物才能进入组队选奖界面；动态彼端遗纹留给回城后的单人兑换。 */
    fun supportsGroupCrack(relicId: String): Boolean {
        val entries = config.relics[relicId]?.entries ?: return false
        return entries.isNotEmpty() && entries.all { !it.isDreammark && !it.blueprintId.isNullOrBlank() }
    }

    /** 组队开箱界面未领取自动保底的等待秒数(relics.yml: group-crack.timeout-seconds)。 */
    val groupCrackTimeoutSeconds: Int get() = config.groupCrackTimeoutSeconds

    /** 通用加权抽取：给定 (值, 权重) 列表，按权重随机返回一个值。crack()/rollUnlockTask() 共用。 */
    private fun <T> weightedPick(entries: List<Pair<T, Double>>): T {
        val total = entries.sumOf { it.second }
        if (total <= 0.0) return entries.random().first
        var roll = Random.nextDouble(total)
        for ((value, weight) in entries) {
            roll -= weight
            if (roll <= 0.0) return value
        }
        return entries.last().first
    }
}
