package com.dongzh1.sourceforge.item

import org.bukkit.inventory.ItemStack

/**
 * 实时构建"材料够不够"红/绿边框图标的工具类，从 CraftEngineHook 里单独拆出来，
 * 避免 CraftEngineHook 塞进太多跟"调 CE API"本身无关的业务命名规则。
 *
 * 只有【一个】共享 CE 物品 sourceforge:framed_material(见
 * craftengine/sourceforge/configuration/items/framed_material.yml)，边框颜色和
 * 材料贴图都靠 1.21.4+ 的 CustomModelData 组件在运行时动态设置，不用像上一版那样
 * 给每个材料 x 每种颜色都注册一份完整的 CE 物品：
 * - flags[0]：够不够(true=绿框/false=红框)，对应 yml 里的 minecraft:condition。
 * - strings[0]：材料 id 去掉命名空间，对应 yml 里 minecraft:select 的 case key。
 * 材料还没在 framed_material.yml 的 select.cases 里登记时，客户端会渲染 yml 里配的
 * barrier 兜底模型（不是拿不到图标/崩溃，是能一眼看出"这个材料没配"）。
 */
object FramedMaterialItems {
    private const val BASE_ID = "sourceforge:framed_material"

    /** 按材料 CE/vanilla id 实时构建带"够不够"边框的图标；CE 未启用/共享物品未注册时退回普通材料图标。 */
    fun build(materialCeId: String, amount: Int, sufficient: Boolean): ItemStack? {
        val stack = CraftEngineHook.build(BASE_ID, amount) ?: return CraftEngineHook.build(materialCeId, amount)
        val meta = stack.itemMeta ?: return stack
        val cmd = meta.customModelDataComponent
        cmd.flags = listOf(sufficient)
        cmd.strings = listOf(materialCeId.substringAfter(':'))
        meta.setCustomModelDataComponent(cmd)
        stack.itemMeta = meta
        return stack
    }
}
