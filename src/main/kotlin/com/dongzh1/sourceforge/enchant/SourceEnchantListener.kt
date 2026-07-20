package com.dongzh1.sourceforge.enchant

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.inventory.meta.EnchantmentStorageMeta

/**
 * 附魔监听器 v6 — 附魔台平时被 LotteryListener 劫持成抽奖界面，但那边只在玩家【非潜行】右键时拦截
 * （潜行右键会直接穿透给原版附魔台，见 LotteryListener.onInteract），所以附魔台并没有真正对玩家彻底
 * 关闭；这里补一道硬拦截：SF 装备不能通过附魔台获得附魔，获取渠道仍只有【村民交易(附魔书) + 铁砧】。
 * SF 装备可以正常带原版附魔（见 [EnchantBridgeConfig]，默认放行除诅咒外的全部原版附魔）。
 * CE 战斗装备分类下的装备(free-anvil-edit: true)铁砧完全自由，其余 SF 装备(如果以后有)维持旧版
 * "SF装备(左槽)+附魔书(右槽)"白名单过滤模式，其他组合（改名/修复/SF+SF）完全禁用。
 */
class SourceEnchantListener(
    private val plugin: SourceForge
) : Listener {

    private fun bridge() = plugin.forgeConfig.enchantBridge

    /** 硬拦截附魔台：SF 装备无论通过什么途径(含潜行穿透 LotteryListener)碰到附魔台都不允许附魔。 */
    @EventHandler
    fun onEnchantItem(event: EnchantItemEvent) {
        if (!plugin.itemService.isSourceEquipment(event.item)) return
        event.isCancelled = true
    }

    @EventHandler
    fun onPrepareAnvil(event: PrepareAnvilEvent) {
        val first = event.inventory.firstItem
        val second = event.inventory.secondItem
        val sfFirst = plugin.itemService.isSourceEquipment(first)
        val sfSecond = plugin.itemService.isSourceEquipment(second)
        if (!sfFirst && !sfSecond) return
        val player = event.viewers.firstOrNull() as? org.bukkit.entity.Player

        // CE 战斗装备分类下的装备(equipment/*.yml 里 free-anvil-edit: true)允许铁砧完全自由操作——
        // 改名/修复/合并/附魔书附魔都走原版流程，不受下面这套白名单限制。非白名单附魔/诅咒仍会被
        // ForgeItemService.stripVanillaEnchantments 在战斗结算前清掉，铁砧本身放开是安全的。
        if (isFreeAnvilEdit(first) && isFreeAnvilEdit(second)) {
            val (merged, forced) = forceMergeEnchants(event.result, first, second)
            if (forced) {
                // 强制合并生效(原版本来不认这个附魔+装备组合)时，原版自己算的repairCost可能是0，
                // 结果预览能看到但点不出来(2026-07-13 实测反馈)——之前这里无脑地板价floor到1，
                // 导致同一件装备可以无限次以1级经验反复"越权"合并(无限刷铁砧，2026-07-16 修复)。
                // forceMergeEnchants 在真正走"原版拒绝"这条路径时，已经把原版"预付工作惩罚"
                // 升级值(cost*2+1)写回了 merged 物品自己的 repairCost 上；这里直接取那个值做本次
                // 操作的经验花费下限，让重复越权合并跟原版一样逐次滚雪球涨价，最终撞上原版
                // "Too Expensive!"防刷上限。
                event.inventory.repairCost = maxOf(event.inventory.repairCost, repairCostOf(merged), 1)
            }
            event.result = applyColorCodeRename(merged, event.inventory.renameText, player)
            return
        }

        val bookMeta = second?.itemMeta as? EnchantmentStorageMeta
        if (!sfFirst || bookMeta == null) {
            event.result = null
            return
        }
        val cfg = bridge()
        val filtered = bookMeta.storedEnchants.filterKeys { cfg.isAllowed(it) }
        if (filtered.isEmpty()) {
            event.result = null
            return
        }
        val result = first!!.clone()
        val meta = result.itemMeta
        var appliedAny = false
        for ((enchant, level) in filtered) {
            if (!enchant.canEnchantItem(result)) continue
            val cap = cfg.allowed[enchant] ?: continue
            meta.addEnchant(enchant, mergedLevel(meta.getEnchantLevel(enchant), level, cap), true)
            appliedAny = true
        }
        if (appliedAny && meta is org.bukkit.inventory.meta.Repairable) {
            // 这条(目前无装备命中的)白名单分支跟上面"完全自由"分支一样是手动重算结果、
            // 没有走原版自己的合并逻辑——同样要手动滚一次原版"预付工作惩罚"升级(cost*2+1)
            // 并写回物品自身，否则将来一旦有装备关掉 free-anvil-edit 落到这条分支，会重现
            // 同样的无限刷铁砧漏洞(2026-07-16 一并修复)。
            meta.repairCost = increasedRepairCost(maxOf(repairCostOf(first), repairCostOf(second)))
        }
        result.itemMeta = meta
        if (appliedAny) {
            event.inventory.repairCost = maxOf(event.inventory.repairCost, repairCostOf(result), 1)
        }
        event.result = applyColorCodeRename(result, event.inventory.renameText, player)
    }

    /**
     * 铁砧改名支持颜色码(& 传统码，复用 Text.name 的解析逻辑)——原版铁砧改名只存纯文本，
     * 玩家打 "&c血刃" 物品上只会字面显示这几个字符，不会变红。只在确实打了 &/§ 时才介入替换
     * 结果物品的名字，没打颜色码的普通改名维持原版行为，不强制改动。
     * 权限 sourceforge.color（plugin.yml 默认 true，全员放行）——没这个权限的玩家打了颜色码
     * 也只落回原版纯文本改名，不报错、不拦下整个改名操作，安静降级。
     */
    private fun applyColorCodeRename(
        item: org.bukkit.inventory.ItemStack?,
        renameText: String?,
        player: org.bukkit.entity.Player?
    ): org.bukkit.inventory.ItemStack? {
        if (item == null || renameText.isNullOrBlank()) return item
        if ('&' !in renameText && '§' !in renameText) return item
        if (player?.hasPermission("sourceforge.color") != true) return item
        val result = item.clone()
        val meta = result.itemMeta
        com.dongzh1.sourceforge.util.Text.name(meta, renameText)
        result.itemMeta = meta
        return result
    }

    /**
     * "完全自由"装备铁砧附魔书时，强制把书里所有附魔合并进结果——不依赖原版自己算的 result。
     * 原因(2026-07-13 用户实测反馈)：原版铁砧对某些"附魔-装备材质"组合有硬编码限制
     * (比如无限附魔 Enchantment.canEnchantItem 不认弩，只认弓)，"完全自由"的装备应该绕开这层限制，
     * 用 addEnchant(force=true) 跳过材质检查。等级仍按原版"同级+1/取较高"规则合并，封顶各附魔自身
     * 的原版最高等级(不做白名单裁剪，因为这条路径本来就不受白名单管)。
     *
     * 返回值第二项 forced：是否有任意一条附魔是原版自己压根算不出来/算错的(vanillaResult 为
     * null，或某条附魔在 vanillaResult 里的等级跟我们这里合并出的目标等级对不上)——只有这种情况
     * 才代表"真被迫强制"，用来决定是否要给修复费保底(见调用处)。单纯"合并出来的等级比原来高"
     * 不代表强制，因为原版铁砧本来就会做同级+1/取较高这种合并。
     */
    private fun forceMergeEnchants(
        vanillaResult: org.bukkit.inventory.ItemStack?,
        first: org.bukkit.inventory.ItemStack?,
        second: org.bukkit.inventory.ItemStack?
    ): Pair<org.bukkit.inventory.ItemStack?, Boolean> {
        val bookMeta = second?.itemMeta as? EnchantmentStorageMeta ?: return vanillaResult to false
        val base = (vanillaResult ?: first)?.clone() ?: return vanillaResult to false
        val meta = base.itemMeta
        var forced = false
        for ((enchant, level) in bookMeta.storedEnchants) {
            val existing = meta.getEnchantLevel(enchant)
            val target = mergedLevel(existing, level, enchant.maxLevel)
            val vanillaLevel = vanillaResult?.itemMeta?.getEnchantLevel(enchant) ?: 0
            if (vanillaResult == null || vanillaLevel != target) forced = true
            meta.addEnchant(enchant, target, true)
        }
        // 2026-07-16 复审修复：原来只在 vanillaResult==null(原版整体拒绝)时才滚 repairCost，
        // 漏了"原版部分接受(混合书一半合法一半不合法)、forceMergeEnchants 仍强行塞入不合法那条"
        // 的情况——那种情况 forced 同样是 true，却因 vanillaResult 非空而不升级花费，等于拿合法
        // 附魔当"车头"夹带越权附魔、只付合法附魔的原版价钱。改成直接看 forced。
        if (forced && meta is org.bukkit.inventory.meta.Repairable) {
            // 原版没算出合法结果(材质/附魔硬编码拒绝)，base 只是 first 的克隆，身上背的还是
            // "这次操作之前"的旧 repairCost——如果不在这里手动补一次，这件装备的"已使用次数"
            // 计数就永远停在原地，可以无限次以地板价反复"越权"合并(无限刷铁砧漏洞的根因，
            // 2026-07-16 修复)。这里手动按原版公式(cost*2+1，等价于原版 AnvilMenu
            // #calculateIncreasedRepairCost)把 first/second 两边已有的 repairCost 取较高者
            // 升级后写回，物品自身的"预付工作惩罚"计数才会真正滚起来。
            meta.repairCost = increasedRepairCost(maxOf(repairCostOf(first), repairCostOf(second)))
        }
        base.itemMeta = meta
        return base to forced
    }

    /** 读取物品身上原版铁砧自己维护的"预付工作惩罚"计数(Repairable#repairCost)——
     * 空槽/没有这个组件的物品(附魔书等)记 0。 */
    private fun repairCostOf(item: org.bukkit.inventory.ItemStack?): Int {
        val repairable = item?.itemMeta as? org.bukkit.inventory.meta.Repairable ?: return 0
        return if (repairable.hasRepairCost()) repairable.repairCost else 0
    }

    /** 原版铁砧"预付工作惩罚"升级公式，等价于原版
     * net.minecraft.world.inventory.AnvilMenu#calculateIncreasedRepairCost：cost -> cost*2+1。
     * 每次铁砧操作后新物品的 repairCost 都按这个公式相对两个输入槽的旧值翻倍上涨，逐次滚雪球
     * 最终撞上原版"Too Expensive!"上限(默认 39)——这正是原版防止无限刷铁砧的核心机制。 */
    private fun increasedRepairCost(cost: Int): Int = cost * 2 + 1

    /** 该槽位是否不受铁砧白名单限制：空槽/非SF物品(附魔书等)天然不受限；SF装备需要
     * 其 equipment 配置显式开启 free-anvil-edit 才算(见 equipment 目录下的 yml 文件)。 */
    private fun isFreeAnvilEdit(item: org.bukkit.inventory.ItemStack?): Boolean {
        if (item == null) return true
        if (!plugin.itemService.isSourceEquipment(item)) return true
        val type = plugin.itemService.weaponType(item) ?: return false
        return plugin.forgeConfig.equipment[type]?.freeAnvilEdit == true
    }

    /** 铁砧合并等级：与原版一致——两边等级相同则+1，否则取较高者；最终按白名单上限夹紧。 */
    private fun mergedLevel(existing: Int, incoming: Int, cap: Int): Int {
        val merged = if (existing > 0 && existing == incoming) existing + 1 else maxOf(existing, incoming)
        return merged.coerceIn(1, cap)
    }
}
