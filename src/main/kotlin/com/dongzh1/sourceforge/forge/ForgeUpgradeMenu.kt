package com.dongzh1.sourceforge.forge

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.config.ForgeRecipe
import com.dongzh1.sourceforge.config.ForgeRecipeMode
import com.dongzh1.sourceforge.item.CraftEngineHook
import com.dongzh1.sourceforge.item.CraftEngineHook.titleComponent
import com.dongzh1.sourceforge.util.AffixFormat
import com.dongzh1.sourceforge.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** 重铸模式独立界面：蓝图槽 + 待重铸武器槽 + 材料展示 + 产出预览 + 动作/模式切换按钮。 */
class ForgeUpgradeMenu(
    plugin: SourceForge,
    structureContext: StructureContext?
) : ForgeMenu(
    plugin, structureContext, Mode.UPGRADE,
    plugin.forgeConfig.forgeUi.upgrade.size,
    upgradeTitle(plugin),
    plugin.forgeConfig.forgeUi.upgrade.actionSlot,
    plugin.forgeConfig.forgeUi.upgrade.outputSlot,
    plugin.forgeConfig.forgeUi.upgrade.modeToggleSlot,
    plugin.forgeConfig.forgeUi.upgrade.materialSlots.toSet()
) {
    private val cfg = plugin.forgeConfig.forgeUi.upgrade
    val blueprintSlot: Int = cfg.blueprintSlot
    val upgradeWeaponSlot: Int = cfg.upgradeWeaponSlot
    override val materialDisplaySlots: List<Int> = cfg.materialSlots

    init {
        initialRender()
    }

    override fun inputSlots(): List<Int> = listOf(blueprintSlot, upgradeWeaponSlot)

    /** 蓝图槽里的物品（空气、或本界面自己放的占位玻璃板，都视为 null）。 */
    fun slotItem(): ItemStack? {
        val item = inventory.getItem(blueprintSlot) ?: return null
        if (item.type == Material.AIR || isPlaceholder(item)) return null
        return item
    }

    /** 待重铸武器槽里的物品（空气、或本界面自己放的占位玻璃板，都视为 null）。 */
    fun upgradeWeaponItem(): ItemStack? {
        val item = inventory.getItem(upgradeWeaponSlot) ?: return null
        if (item.type == Material.AIR || isPlaceholder(item)) return null
        return item
    }

    /** 蓝图槽解析出的重铸配方。 */
    fun currentRecipe(): ForgeRecipe? {
        val item = slotItem() ?: return null
        val ceId = CraftEngineHook.itemId(item) ?: return null
        return plugin.itemService.readBlueprintRecipe(item, ceId)
    }

    override fun fillStatic() {
        val reserved = readonlySlots + blueprintSlot + upgradeWeaponSlot
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
        inventory.setItem(
            upgradeWeaponSlot,
            pane(Material.YELLOW_STAINED_GLASS_PANE, "&e空 待重铸武器槽", listOf("&7先在蓝图槽放入蓝图", "&7以确定所需武器类型"))
        )
        restoreBlueprintPlaceholderIfEmpty()
    }

    private fun updateUpgradeWeaponPlaceholder(name: String, lore: List<String>) {
        if (upgradeWeaponItem() != null) return
        inventory.setItem(upgradeWeaponSlot, pane(Material.YELLOW_STAINED_GLASS_PANE, name, lore))
    }

    private fun restoreBlueprintPlaceholderIfEmpty() {
        val current = inventory.getItem(blueprintSlot)
        if (current != null && current.type != Material.AIR) return
        inventory.setItem(
            blueprintSlot,
            pane(Material.ORANGE_STAINED_GLASS_PANE, "&d空 蓝图槽", listOf("&7放入重铸配方蓝图", "&7武器槽放要重铸的装备"))
        )
    }

    override fun renderContent(viewer: Player?) {
        restoreBlueprintPlaceholderIfEmpty()
        val recipe = currentRecipe()
        if (recipe == null || recipe.mode != ForgeRecipeMode.UPGRADE) {
            updateUpgradeWeaponPlaceholder("&e空 待重铸武器槽", listOf("&7先在蓝图槽放入重铸配方蓝图"))
            renderMaterialSlots(viewer, materialDisplaySlots, emptyList())
            val msg = if (recipe != null) "&c这是锻造配方，请切换到「锻造」模式" else "&c无有效重铸配方蓝图"
            inventory.setItem(outputSlot, label(Material.BARRIER, msg, listOf("&7当前处于「重铸」模式", "&7在蓝图槽放入重铸配方蓝图")))
            return
        }

        val target = plugin.forgeConfig.equipment[recipe.equipmentId]
        val weapon = upgradeWeaponItem()
        val requiredCategory = recipe.requiresWeaponCategory ?: target?.weaponCategory ?: "?"
        updateUpgradeWeaponPlaceholder(
            "&e空 待重铸武器槽",
            listOf(
                "&7放入: &f${target?.material?.name ?: "?"} &7生胚",
                "&7或已锻造的 &f$requiredCategory &7装备 &7(tier &e${recipe.minTier}&7+)",
                "&7重铸后保留附魔与已装MOD，强化等级清零"
            )
        )
        val isForged = weapon != null && plugin.itemService.isSourceEquipment(weapon)

        val preview: ItemStack = when {
            weapon == null -> label(
                Material.BARRIER, "&c请在武器槽放入要重铸的武器",
                listOf("&7类型需求: &e$requiredCategory", "&7等级需求: &etier ${recipe.minTier}+ &7(或对应原版下界合金基材)")
            )
            target == null -> label(Material.BARRIER, "&c配方目标装备不存在: ${recipe.equipmentId}", emptyList())
            isForged && !plugin.itemService.weaponCategory(weapon).equals(requiredCategory, ignoreCase = true) ->
                label(Material.BARRIER, "&c武器类型不符（需要 $requiredCategory）", emptyList())
            isForged && plugin.itemService.equipmentTier(weapon) < recipe.minTier ->
                label(Material.BARRIER, "&c武器等级不足（需要 tier ${recipe.minTier}+，当前 tier ${plugin.itemService.equipmentTier(weapon)}）", emptyList())
            !isForged && weapon.type != target.material ->
                label(Material.BARRIER, "&c基材不符（需要 ${target.material.name} 或对应已锻造装备）", emptyList())
            else -> (plugin.itemService.reforgeEquipment(weapon.clone(), target, recipe.tier) ?: weapon).clone().also {
                val meta = it.itemMeta
                val lore = (meta.lore() ?: mutableListOf()).toMutableList()
                lore.add(Text.comp("&d重铸预览（不可拿取，强化等级将清零）"))
                // 2026-07-17 新增：逐条词条旧值→新值对比，让玩家在点击重铸前就能看到具体的属性提升
                // （此前只展示产出物品自身的lore，看不出跟当前武器比到底提升了多少）。
                for (roll in target.tierAffixes[recipe.tier].orEmpty()) {
                    val affix = plugin.forgeConfig.affixes[roll.affixId] ?: continue
                    val oldVal = plugin.itemService.readAffixValue(weapon, roll.affixId)
                    val newVal = roll.value * affix.scale
                    val newStr = AffixFormat.affixValue(affix.percent, affix.decimals, newVal)
                    lore.add(
                        if (oldVal <= 0.0) {
                            Text.comp("&7${affix.displayName} &a新增: +$newStr")
                        } else {
                            val oldStr = AffixFormat.affixValue(affix.percent, affix.decimals, oldVal)
                            Text.comp("&7${affix.displayName} &f$oldStr &7→ &a$newStr")
                        }
                    )
                }
                meta.lore(lore)
                it.itemMeta = meta
            }
        }
        inventory.setItem(outputSlot, preview)
        renderMaterialSlots(viewer, materialDisplaySlots, recipe.materials)
    }

    override fun renderIdle(viewer: Player?) {
        val recipe = currentRecipe()
        if (recipe == null || recipe.mode != ForgeRecipeMode.UPGRADE) {
            val lore = ui.hammerButton.lore.toMutableList()
            lore += if (recipe != null) "&c这是锻造配方，请切换到「锻造」模式" else "&c蓝图槽放入重铸配方蓝图"
            inventory.setItem(actionSlot, button(Material.BARRIER, ui.hammerButton.name, lore))
            return
        }

        val target = plugin.forgeConfig.equipment[recipe.equipmentId]
        val requiredCategory = recipe.requiresWeaponCategory ?: target?.weaponCategory ?: "?"
        val weapon = upgradeWeaponItem()
        val lore = ui.hammerButton.lore.toMutableList()
        var ready = false
        if (weapon == null || target == null) {
            lore += "&c请在武器槽放入要重铸的武器"
            lore += "&7放已锻造的同类装备，或对应的原版下界合金基材"
        } else {
            val isForged = plugin.itemService.isSourceEquipment(weapon)
            val categoryOk = !isForged || plugin.itemService.weaponCategory(weapon).equals(requiredCategory, ignoreCase = true)
            val tier = if (isForged) plugin.itemService.equipmentTier(weapon) else 0
            val tierOk = !isForged || tier >= recipe.minTier
            val materialOk = isForged || weapon.type == target.material
            ready = categoryOk && tierOk && materialOk && viewer != null && recipe.materials.all { plugin.itemService.countInInventory(viewer, it.ceId) >= it.amount }
            lore += "&7重铸为: &f${plugin.forgeConfig.equipmentDisplayName(recipe.equipmentId)} &7等级 &e${recipe.tier}"
            lore += "&7要求: &e$requiredCategory &7类型 &8· &7至少 tier &e${recipe.minTier} &7(或对应原版下界合金基材)"
            lore += "&7耗时: &e${recipe.timeSeconds.toInt()}s"
            lore += when {
                !materialOk -> "&c基材不符（需要 ${target.material.name} 或对应已锻造装备）"
                !categoryOk -> "&c武器类型不符"
                !tierOk -> "&c武器等级不足（当前 tier $tier）"
                ready -> "&a条件满足，点击开始重铸"
                else -> "&c材料不足"
            }
        }
        inventory.setItem(actionSlot, button(if (ready) ui.hammerButton.material else Material.BARRIER, if (ready) "<light_purple>开始重铸" else ui.hammerButton.name, lore))
    }
}

private fun upgradeTitle(plugin: SourceForge): net.kyori.adventure.text.Component {
    val cfg = plugin.forgeConfig.forgeUi.upgrade
    return if (cfg.hasBackground) titleComponent(cfg.title) else titleComponent(plugin.forgeConfig.guiTitle)
}
