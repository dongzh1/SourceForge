package com.dongzh1.sourceforge.item

import com.dongzh1.sourceforge.util.Text
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import net.momirealms.craftengine.bukkit.api.CraftEngineItems
import net.momirealms.craftengine.core.util.Key
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

object CraftEngineHook {
    val enabled: Boolean
        get() = Bukkit.getPluginManager().isPluginEnabled("CraftEngine")

    fun itemId(item: ItemStack): String? {
        if (!enabled) return null
        return runCatching { CraftEngineItems.getCustomItemId(item)?.asString() }.getOrNull()
    }

    fun build(id: String, amount: Int = 1): ItemStack? {
        if (!enabled) return null
        val key = parseKey(id) ?: return null
        // 26.6 API：CustomItem.buildBukkitItem() 返回数量 1 的物品，再设数量（镜像 GeneMachinery CeBridge.buildItem）。
        return runCatching {
            CraftEngineItems.byId(key)?.buildBukkitItem()?.apply { this.amount = amount.coerceAtLeast(1) }
        }.getOrNull()
    }

    /**
     * 读取一个方块对应的 CraftEngine 自定义方块 id（如 "sourceforge:forge_core"）。
     * 镜像 GeneMachinery 的做法：CraftEngineBlocks.getCustomBlockState(block).owner().value().id()。
     * 非 CE 方块或 CE 未启用返回 null。
     */
    fun blockId(block: Block): String? {
        if (!enabled) return null
        return runCatching {
            CraftEngineBlocks.getCustomBlockState(block)?.owner()?.value()?.id()?.toString()
        }.getOrNull()
    }

    /**
     * 以玩家身份移除一个 CE 自定义方块（掉落战利品 + 音效粒子 + 清理 CE 方块数据）。
     * 走稳定 api 的 CraftEngineBlocks.remove(block, player, movedByPiston=false, dropLoot=true, sendLevelEvent=true)，
     * 镜像 GeneMachinery 已验证的拆除方式；不要用 block.type=AIR（会留下 CE 幽灵数据且不掉落）。
     */
    fun removeBlock(block: Block, player: Player): Boolean {
        if (!enabled) return false
        return runCatching { CraftEngineBlocks.remove(block, player, false, true, true) }.getOrElse { false }
    }

    /** 稳定 api 判定：该方块是否为 CraftEngine 自定义方块。 */
    fun isCustomBlock(block: Block): Boolean {
        if (!enabled) return false
        return runCatching { CraftEngineBlocks.isCustomBlock(block) }.getOrDefault(false)
    }

    /** 给玩家一个 CE 物品（按 id），背包满则溢出由调用方处理。返回是否成功构建。 */
    fun giveItem(player: Player, id: String, amount: Int = 1): Boolean {
        val stack = build(id, amount) ?: return false
        player.inventory.addItem(stack).values.forEach {
            player.world.dropItemNaturally(player.location, it)
        }
        return true
    }

    /**
     * 用 CraftEngine 的【完整】MiniMessage 解析器把字符串解析成 Paper Component。
     * customMiniMessage() 同时支持标准标签(<font>/<gradient>/颜色/装饰)与 CE 自定义标签
     * (<shift>/<image>/<i18n> 等)。在代码层解析成成品 Component(而非把字符串丢给 CE 封包再解析),
     * 这样 GUI 标题里的 <image:sourceforge:forge_gui> 等格式真正生效,不再依赖容器封包拦截。
     *
     * CE 的 adventure 是重定位的(net.momirealms.craftengine.libraries.adventure),与 Paper 的
     * net.kyori 不是同一套类,故走 JSON 桥接:CE 解析 -> componentToJson -> Paper 端 Gson 反序列化。
     * 全程反射,避免编译期依赖重定位类。CE 不可用或解析失败返回 null(调用方回退到 Paper MiniMessage)。
     */
    fun miniMessage(raw: String): Component? {
        if (!enabled) return null
        return runCatching {
            val helper = Class.forName("net.momirealms.craftengine.core.util.AdventureHelper")
            // 先把传统 §/& 颜色码转成 MiniMessage 标签,再交给完整解析器
            val mmStr = helper.getMethod("legacyToMiniMessage", String::class.java).invoke(null, raw) as String
            val customMM = helper.getMethod("customMiniMessage").invoke(null)
            val deserialize = customMM.javaClass.methods.first {
                it.name == "deserialize" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
            }
            val ceComp = deserialize.invoke(customMM, mmStr)
            val compClass = Class.forName("net.momirealms.craftengine.libraries.adventure.text.Component")
            val json = helper.getMethod("componentToJson", compClass).invoke(null, ceComp) as String
            GsonComponentSerializer.gson().deserialize(json)
        }.getOrNull()
    }

    /**
     * GUI 标题用 Component:优先 CE 完整解析器(支持 <image>/<shift>/<font>/<i18n> 等 CE 标签),
     * CE 不可用或解析失败时回退到 Paper 自带 MiniMessage(标准标签 + 传统 §/& 颜色码)。
     */
    fun titleComponent(raw: String): Component = miniMessage(raw) ?: Text.comp(raw)

    private fun parseKey(raw: String): Key? {
        val normalized = raw.removePrefix("ce_")
        // 26.6 API：用稳定工厂 Key.of(...)（旧 0.0.66 的 Key(ns, value) 构造器在 26.6 不可用）。
        return runCatching {
            if (normalized.contains(":")) Key.of(normalized) else Key.of("default", normalized)
        }.getOrNull()
    }
}
