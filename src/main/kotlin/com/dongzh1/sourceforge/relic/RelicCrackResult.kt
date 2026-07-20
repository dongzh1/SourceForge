package com.dongzh1.sourceforge.relic

/**
 * crackRelic() 的结果状态。原先用 sealed class + 嵌套 object/data class 建模，但 SourceForge 的
 * shadowJar 会把 kotlin/kotlinx 包整体 relocate（见 build.gradle.kts），这会连带 relocate 掉
 * `@kotlin.Metadata` 注解本身，导致外部模块(SourceWild/PixelRPG)的 Kotlin 编译器在 compileOnly
 * 编译期读不到这个 jar 里 Kotlin 类型的元数据——引用嵌套 object 单例会报
 * "Classifier 'X' does not have a companion object, and thus must be initialized here"（实测坑）。
 * 枚举 + 扁平 data class（跨模块只做字段读取/枚举比较，不依赖具名参数/companion 语法糖）才是
 * 这条 compileOnly 边界上安全的形状——全项目里跨模块暴露的 SourceForgeActionEvent.Action 同样是枚举。
 */
enum class RelicCrackStatus {
    /** 消耗了 1 个遗物，抽中并发放了蓝图；relicId/blueprintId 非空。 */
    SUCCESS,
    /** 背包(含潜影盒)里没有找到任何"已解锁/无需解锁"的遗物——未消耗任何东西。 */
    NO_ELIGIBLE_RELIC,
    /** 找到了遗物，但 relics.yml 里这个遗物 id 没有配置蓝图权重池(或权重全为0)——未消耗。 */
    NO_BLUEPRINT_CONFIGURED,
    /** 蓝图 CE id 抽出来了，但 CraftEngine 建不出这个物品(配置里 id 写错，或 CraftEngine 未启用)——未消耗。 */
    BLUEPRINT_BUILD_FAILED,
    /** SourceForge 未绑定/CraftEngine 未启用，整个系统不可用。 */
    UNAVAILABLE
}

/**
 * crackRelic() 的返回结果。[status] 为 [RelicCrackStatus.SUCCESS] 之外的任何值都表示遗物未被消耗——
 * 内部实现顺序是"先滚 RNG、建好物品，最后才真正扣背包"，任何一步失败都不碰背包，
 * 这正是"每次调用只消耗一个遗物"这个约束的实现方式。
 */
data class RelicCrackResult(
    val status: RelicCrackStatus,
    /** 仅 SUCCESS 时非空：被消耗的遗物 CE id。 */
    val relicId: String? = null,
    /** SUCCESS 或 BLUEPRINT_BUILD_FAILED 时非空：抽中/试图建造的蓝图 CE id。 */
    val blueprintId: String? = null,
    /** 仅 SUCCESS 时有意义：背包已满、蓝图被掉落在地上。 */
    val overflowed: Boolean = false
)
