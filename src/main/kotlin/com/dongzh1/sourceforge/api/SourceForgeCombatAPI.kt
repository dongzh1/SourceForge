package com.dongzh1.sourceforge.api

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.item.ExternalAffixProvider
import com.dongzh1.sourceforge.status.ElementType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

/**
 * SourceForge 对外公开战斗 API（词缀临时加成 + 元素异常）。供 SourceWild 等外部插件
 * compileOnly + 真调用消费。SF 未启用前调用静默降级（false/0）。
 *
 * bind 持有主类实例而非服务实例——/sf reload 会重建 ForgeItemService，
 * 经主类实时取用可保 reload 后不失效（外部 Provider 由 SF 侧迁移机制保留）。
 */
object SourceForgeCombatAPI {

    @Volatile
    private var plugin: SourceForge? = null

    /** 外部插件按 key 注册的 Provider 包装（同 key 重复注册 = 替换）。 */
    private val keyed = ConcurrentHashMap<String, ExternalAffixProvider>()

    @JvmStatic
    fun bind(instance: SourceForge) {
        plugin = instance
    }

    /**
     * 注册临时词缀 Provider（key 唯一，重复注册替换旧的）。
     * 回调实时返回 affixId->数值，从不持久化；不满足条件返回空 Map 即失效。
     */
    @JvmStatic
    fun registerAffixProvider(key: String, provider: java.util.function.Function<Player, Map<String, Double>>): Boolean {
        val p = plugin ?: return false
        keyed.remove(key)?.let { p.itemService.unregisterExternalAffixProvider(it) }
        val wrapped = ExternalAffixProvider { player -> provider.apply(player) }
        keyed[key] = wrapped
        p.itemService.registerExternalAffixProvider(wrapped)
        return true
    }

    @JvmStatic
    fun unregisterAffixProvider(key: String): Boolean {
        val p = plugin ?: return false
        val wrapped = keyed.remove(key) ?: return false
        p.itemService.unregisterExternalAffixProvider(wrapped)
        return true
    }

    /** 给怪叠元素层（typeId 如 heat/cold/corrosive...）。玩家目标/未知元素返回 false。 */
    @JvmStatic
    fun applyElementStacks(entity: LivingEntity, typeId: String, stacks: Int, source: Player?): Boolean {
        val p = plugin ?: return false
        val type = ElementType.fromId(typeId) ?: return false
        p.statusManager.applyStacks(entity, type, stacks, source, cause = "api")
        return true
    }

    /** 该怪某元素当前层数（未知元素/未启用返回 0）。 */
    @JvmStatic
    fun stacksOf(entity: LivingEntity, typeId: String): Int {
        val p = plugin ?: return 0
        val type = ElementType.fromId(typeId) ?: return 0
        return p.statusManager.stacksOf(entity, type)
    }

    /** 玩家某词缀全身汇总值（含外部 Provider 贡献）。 */
    @JvmStatic
    fun stat(player: Player, affixId: String): Double =
        plugin?.itemService?.readTotalAffix(player, affixId) ?: 0.0
}
