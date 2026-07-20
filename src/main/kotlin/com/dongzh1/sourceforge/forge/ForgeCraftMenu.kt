package com.dongzh1.sourceforge.forge

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.config.ForgeRecipe
import com.dongzh1.sourceforge.config.ForgeRecipeMode
import com.dongzh1.sourceforge.item.CraftEngineHook
import com.dongzh1.sourceforge.item.CraftEngineHook.titleComponent
import com.dongzh1.sourceforge.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** 锻造模式独立界面：蓝图槽 + 材料展示 + 产出预览 + 动作/模式切换按钮。 */
class ForgeCraftMenu(
    plugin: SourceForge,
    structureContext: StructureContext?
) : ForgeMenu(
    plugin, structureContext, Mode.CRAFT,
    plugin.forgeConfig.forgeUi.craft.size,
    craftTitle(plugin),
    plugin.forgeConfig.forgeUi.craft.actionSlot,
    plugin.forgeConfig.forgeUi.craft.outputSlot,
    plugin.forgeConfig.forgeUi.craft.modeToggleSlot,
    plugin.forgeConfig.forgeUi.craft.materialSlots.toSet()
) {
    private val cfg = plugin.forgeConfig.forgeUi.craft
    val blueprintSlot: Int = cfg.blueprintSlot
    override val materialDisplaySlots: List<Int> = cfg.materialSlots

    init {
        initialRender()
    }

    override fun inputSlots(): List<Int> = listOf(blueprintSlot)

    /** 蓝图槽里的物品（空气、或本界面自己放的占位玻璃板，都视为 null）。 */
    fun slotItem(): ItemStack? {
        val item = inventory.getItem(blueprintSlot) ?: return null
        if (item.type == Material.AIR || isPlaceholder(item)) return null
        return item
    }

    /** 蓝图槽解析出的配方。 */
    fun currentRecipe(): ForgeRecipe? {
        val item = slotItem() ?: return null
        val ceId = CraftEngineHook.itemId(item) ?: return null
        return plugin.itemService.readBlueprintRecipe(item, ceId)
    }

    override fun fillStatic() {
        val reserved = readonlySlots + blueprintSlot
        if (cfg.hasBackground) {
            val barrier = pane(Material.BARRIER, "&8 ", emptyList())
            for (slot in cfg.barrierSlots) {
                if (slot in 0 until inventory.size && slot !in reserved) inventory.setItem(slot, barrier)
            }
            return
        }
        val filler = pane(ui.fillerMaterial, "&8 ", emptyList())
        for (slot in 0 until inventory.size) {
            if (slot !in reserved) inventory.setItem(slot, filler)
        }
        restoreBlueprintPlaceholderIfEmpty()
    }

    private fun restoreBlueprintPlaceholderIfEmpty() {
        val current = inventory.getItem(blueprintSlot)
        if (current != null && current.type != Material.AIR) return
        inventory.setItem(
            blueprintSlot,
            pane(Material.ORANGE_STAINED_GLASS_PANE, "&6空 蓝图槽", listOf("&7放入锻造配方蓝图", "&7放入后下方自动显示所需材料"))
        )
    }

    override fun renderContent(viewer: Player?) {
        restoreBlueprintPlaceholderIfEmpty()
        val recipe = currentRecipe()
        if (recipe == null || recipe.mode != ForgeRecipeMode.CREATE) {
            renderMaterialSlots(viewer, materialDisplaySlots, emptyList())
            val msg = if (recipe != null) "&c这是重铸配方，请切换到「重铸」模式" else "&c无有效蓝图"
            inventory.setItem(outputSlot, label(Material.BARRIER, msg, listOf("&7在蓝图槽放入有效蓝图", "&7以查看所需材料与产出")))
            return
        }

        val preview = (plugin.itemService.createDirectEquipment(recipe.equipmentId, recipe.tier, null)
            ?: CraftEngineHook.build(recipe.equipmentId, 1))?.clone()
            ?: ItemStack(Material.PAPER)
        run {
            val meta = preview.itemMeta
            val lore = (meta.lore() ?: mutableListOf()).toMutableList()
            lore.add(Text.comp("&7产出预览（不可拿取）"))
            meta.lore(lore)
            preview.itemMeta = meta
        }
        inventory.setItem(outputSlot, preview)
        renderMaterialSlots(viewer, materialDisplaySlots, recipe.materials)
    }

    override fun renderIdle(viewer: Player?) {
        val recipe = currentRecipe()
        val lore = ui.hammerButton.lore.toMutableList()
        var ready = false
        when {
            recipe != null && recipe.mode == ForgeRecipeMode.CREATE -> {
                ready = viewer != null && recipe.materials.all { plugin.itemService.countInInventory(viewer, it.ceId) >= it.amount }
                lore += "&7产出: &f${plugin.forgeConfig.equipmentDisplayName(recipe.equipmentId)} &7等级 &e${recipe.tier}"
                lore += "&7耗时: &e${recipe.timeSeconds.toInt()}s"
                lore += if (ready) "&a材料充足，点击开始锻造" else "&c材料不足"
            }
            recipe != null -> lore += "&c这是重铸配方，请切换到「重铸」模式"
            else -> lore += "&7放入有效蓝图后点击锻造"
        }
        inventory.setItem(actionSlot, button(if (ready) ui.hammerButton.material else Material.BARRIER, ui.hammerButton.name, lore))
    }
}

private fun craftTitle(plugin: SourceForge): net.kyori.adventure.text.Component {
    val cfg = plugin.forgeConfig.forgeUi.craft
    return if (cfg.hasBackground) titleComponent(cfg.title) else titleComponent(plugin.forgeConfig.guiTitle)
}
