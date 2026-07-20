package com.dongzh1.sourceforge.forge

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.economy.EconomyBridge
import com.dongzh1.sourceforge.item.CraftEngineHook.titleComponent
import com.dongzh1.sourceforge.util.AffixFormat
import com.dongzh1.sourceforge.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 强化模式独立界面。用户明确要求"强化只花钱不消耗材料"——所以只留一个武器槽，
 * 不留材料展示槽/蓝图槽（跟锻造/重铸不共用任何输入槽，是完全独立的 Inventory）。
 * 2026-07-17：同一个槽位也接受 MOD 物品——MOD 段位升级复用这套"钱+时间、走锻炉"的流程，
 * 取代旧的独立 ModUpgradeMenu(升级核心实物消耗)。武器与MOD两条分支互斥判定，互不干扰。
 */
class ForgeEnhanceMenu(
    plugin: SourceForge,
    structureContext: StructureContext?
) : ForgeMenu(
    plugin, structureContext, Mode.ENHANCE,
    plugin.forgeConfig.forgeUi.enhance.size,
    enhanceTitle(plugin),
    plugin.forgeConfig.forgeUi.enhance.actionSlot,
    plugin.forgeConfig.forgeUi.enhance.outputSlot,
    plugin.forgeConfig.forgeUi.enhance.modeToggleSlot,
    emptySet()
) {
    private val cfg = plugin.forgeConfig.forgeUi.enhance
    val weaponSlot: Int = cfg.weaponSlot

    init {
        initialRender()
    }

    override fun inputSlots(): List<Int> = listOf(weaponSlot)

    /** 武器槽里的物品（空气、或本界面自己放的占位玻璃板，都视为 null）。 */
    fun weaponItem(): ItemStack? {
        val item = inventory.getItem(weaponSlot) ?: return null
        if (item.type == Material.AIR || isPlaceholder(item)) return null
        return item
    }

    /** 武器槽里放的是否为可强化的 SF 装备。 */
    fun enhanceWeapon(): ItemStack? {
        val item = weaponItem() ?: return null
        return if (plugin.itemService.isSourceEquipment(item)) item else null
    }

    /** 武器槽里放的是否为可升级段位的 MOD 物品。 */
    fun enhanceMod(): ItemStack? {
        val item = weaponItem() ?: return null
        return if (plugin.modService.isModItem(item)) item else null
    }

    fun enhanceRiven(): ItemStack? {
        val item = weaponItem() ?: return null
        return if (plugin.rivenService.isRiven(item)) item else null
    }

    override fun fillStatic() {
        val reserved = readonlySlots + weaponSlot
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
        restoreWeaponPlaceholderIfEmpty()
    }

    private fun restoreWeaponPlaceholderIfEmpty() {
        val current = inventory.getItem(weaponSlot)
        if (current != null && current.type != Material.AIR) return
        inventory.setItem(
            weaponSlot,
            pane(Material.ORANGE_STAINED_GLASS_PANE, "&b空 武器槽", listOf("&7放入要强化的 SF 装备", "&7强化只花钱不消耗材料"))
        )
    }

    override fun renderContent(viewer: Player?) {
        restoreWeaponPlaceholderIfEmpty()
        val riven = enhanceRiven()
        if (riven != null) {
            renderRivenContent(viewer, riven)
            return
        }
        val mod = enhanceMod()
        if (mod != null) {
            renderModContent(viewer, mod)
            return
        }
        val weapon = enhanceWeapon()
        if (weapon == null) {
            val hasItem = weaponItem() != null
            val msg = if (hasItem) "&c这不是可强化的 SF 装备或 MOD" else "&c请放入要强化的 SF 装备或 MOD"
            inventory.setItem(outputSlot, label(Material.BARRIER, msg, listOf("&7武器槽放入要强化的武器/装备，或要升级段位的 MOD")))
            return
        }

        val category = plugin.itemService.weaponCategory(weapon)
        val level = plugin.itemService.enhanceLevel(weapon)
        val next = plugin.enhancementConfig.nextLevel(category, level)

        if (next == null) {
            val preview = weapon.clone()
            val meta = preview.itemMeta
            Text.name(meta, "&b强化预览 &7(已满级 Lv.$level)")
            preview.itemMeta = meta
            inventory.setItem(outputSlot, preview)
            return
        }

        val cost = next.cost * plugin.forgeConfig.equipmentTiers.multiplierFor(plugin.itemService.weaponType(weapon))
        val balance = viewer?.let { EconomyBridge.balance(it) } ?: 0.0
        val currentCapacity = plugin.modService.readCapacity(weapon)
        val enhancedCapacity = currentCapacity + next.modCapacity
        val currentDamage = plugin.itemService.readBaseDamage(weapon)
        val enhancedDamage = currentDamage + next.baseDamage
        val currentShield = plugin.itemService.readAffixValue(weapon, "shield_capacity")
        val enhancedShield = currentShield + next.shieldCapacity
        val currentHealth = plugin.itemService.readAffixValue(weapon, "health")
        val enhancedHealth = currentHealth + next.health
        val preview = weapon.clone()
        run {
            val meta = preview.itemMeta
            Text.name(meta, "&b强化预览 Lv.${level + 1}")
            val lore = (meta.lore() ?: mutableListOf()).toMutableList()
            if (plugin.itemService.isProtectionEquipment(weapon)) {
                lore.add(Text.comp("&7护盾容量 &f${AffixFormat.number(currentShield, 2)} &7→ &a${AffixFormat.number(enhancedShield, 2)}"))
                lore.add(Text.comp("&7生命值 &f${AffixFormat.number(currentHealth, 2)} &7→ &a${AffixFormat.number(enhancedHealth, 2)}"))
            } else {
                lore.add(Text.comp("&7基础伤害 &f${AffixFormat.number(currentDamage, 2)} &7→ &a${AffixFormat.number(enhancedDamage, 2)}"))
            }
            lore.add(Text.comp("&7容量上限 &f$currentCapacity &7→ &a$enhancedCapacity"))
            lore.add(Text.comp("&7强化花费 &e${cost.toInt()} &7金币"))
            lore.add(Text.comp("&7当前余额 &f${balance.toInt()}"))
            lore.add(Text.comp("&7（强化预览，不可拿取）"))
            meta.lore(lore)
            preview.itemMeta = meta
        }
        inventory.setItem(outputSlot, preview)
    }

    private fun renderRivenContent(viewer: Player?, riven: ItemStack) {
        val service = plugin.rivenService
        val instance = service.parseData(riven)
        if (instance == null || !service.isUnveiled(riven)) {
            inventory.setItem(outputSlot, label(Material.BARRIER, "&c封缄彼端遗纹不可强化", listOf("&7先完成遗纹试炼")))
            return
        }
        if (service.hasPendingRoll(riven)) {
            inventory.setItem(outputSlot, label(Material.BARRIER, "&c请先确认梦织候选词条", listOf("&7使用 /sf dreammark accept 或 /sf dreammark keep")))
            return
        }
        if (instance.rank >= service.maxRank()) {
            val preview = riven.clone()
            val meta = preview.itemMeta
            Text.name(meta, "&b强化预览 &7(彼端遗纹已满段位)")
            preview.itemMeta = meta
            inventory.setItem(outputSlot, preview)
            return
        }
        val cost = service.rankUpgradeCost(instance.rank)
        val balance = viewer?.let { EconomyBridge.balance(it) } ?: 0.0
        val preview = riven.clone()
        val meta = preview.itemMeta
        Text.name(meta, "&b强化预览 遗纹段位 ${instance.rank + 1}/${service.maxRank()}")
        val lore = (meta.lore() ?: mutableListOf()).toMutableList()
        lore.add(Text.comp("&7占用 &e${service.cost(instance)} &7→ &a${service.nextCost(instance)}"))
        lore.add(Text.comp("&7强化花费 &e${cost.toInt()} &7金币"))
        lore.add(Text.comp("&7当前余额 &f${balance.toInt()}"))
        lore.add(Text.comp("&7（强化预览，不可拿取）"))
        meta.lore(lore)
        preview.itemMeta = meta
        inventory.setItem(outputSlot, preview)
    }

    /** MOD 段位升级预览：段位 r→r+1，逐条词条旧值→新值，花费，仿照上面武器强化的预览排版。 */
    private fun renderModContent(viewer: Player?, mod: ItemStack) {
        val config = plugin.modService.modConfig(mod)
        if (config == null || config.maxRank <= 0) {
            inventory.setItem(outputSlot, label(Material.BARRIER, "&c该 MOD 不可升级段位", listOf("&7只有带段位上限的 MOD 才能强化")))
            return
        }
        val rank = plugin.modService.modRank(mod)
        if (rank >= config.maxRank) {
            val preview = mod.clone()
            val meta = preview.itemMeta
            Text.name(meta, "&b强化预览 &7(已满段位 ${config.maxRank}/${config.maxRank})")
            preview.itemMeta = meta
            inventory.setItem(outputSlot, preview)
            return
        }
        val cost = plugin.enhancementConfig.modUpgrade.costFor(config, rank)
        val balance = viewer?.let { EconomyBridge.balance(it) } ?: 0.0
        val preview = mod.clone()
        run {
            val meta = preview.itemMeta
            Text.name(meta, "&b强化预览 段位 ${rank + 1}/${config.maxRank}")
            val lore = (meta.lore() ?: mutableListOf()).toMutableList()
            for (affixId in config.effects.keys) {
                val affix = plugin.forgeConfig.affixes[affixId] ?: continue
                val oldStr = AffixFormat.affixValue(affix.percent, affix.decimals, config.effectAtRank(affixId, rank))
                val newStr = AffixFormat.affixValue(affix.percent, affix.decimals, config.effectAtRank(affixId, rank + 1))
                lore.add(Text.comp("&7${affix.displayName} &f$oldStr &7→ &a$newStr"))
            }
            lore.add(Text.comp("&7强化花费 &e${cost.toInt()} &7金币"))
            lore.add(Text.comp("&7当前余额 &f${balance.toInt()}"))
            lore.add(Text.comp("&7（强化预览，不可拿取）"))
            meta.lore(lore)
            preview.itemMeta = meta
        }
        inventory.setItem(outputSlot, preview)
    }

    override fun renderIdle(viewer: Player?) {
        val riven = enhanceRiven()
        if (riven != null) {
            renderRivenIdle(viewer, riven)
            return
        }
        val mod = enhanceMod()
        if (mod != null) {
            renderModIdle(viewer, mod)
            return
        }
        val weapon = enhanceWeapon()
        if (weapon == null) {
            val lore = ui.hammerButton.lore.toMutableList()
            lore += "&7武器槽放入要强化的 SF 装备，或要升级段位的 MOD"
            inventory.setItem(actionSlot, button(Material.BARRIER, ui.hammerButton.name, lore))
            return
        }
        val category = plugin.itemService.weaponCategory(weapon)
        val level = plugin.itemService.enhanceLevel(weapon)
        val next = plugin.enhancementConfig.nextLevel(category, level)
        val lore = ui.hammerButton.lore.toMutableList()
        if (next == null) {
            lore += "&c该武器已满级 (Lv.$level)"
            inventory.setItem(actionSlot, button(Material.BARRIER, "&c已满级", lore))
            return
        }
        val cost = next.cost * plugin.forgeConfig.equipmentTiers.multiplierFor(plugin.itemService.weaponType(weapon))
        val ready = viewer != null && EconomyBridge.has(viewer, cost)
        val currentCapacity = plugin.modService.readCapacity(weapon)
        val enhancedCapacity = currentCapacity + next.modCapacity
        val currentDamage = plugin.itemService.readBaseDamage(weapon)
        val enhancedDamage = currentDamage + next.baseDamage
        val currentShield = plugin.itemService.readAffixValue(weapon, "shield_capacity")
        val enhancedShield = currentShield + next.shieldCapacity
        val currentHealth = plugin.itemService.readAffixValue(weapon, "health")
        val enhancedHealth = currentHealth + next.health
        lore += "&7强化 &eLv.$level &7→ &aLv.${level + 1}"
        if (plugin.itemService.isProtectionEquipment(weapon)) {
            lore += "&7护盾容量: &f${AffixFormat.number(currentShield, 2)} &7→ &a${AffixFormat.number(enhancedShield, 2)}"
            lore += "&7生命值: &f${AffixFormat.number(currentHealth, 2)} &7→ &a${AffixFormat.number(enhancedHealth, 2)}"
        } else {
            lore += "&7基础伤害: &f${AffixFormat.number(currentDamage, 2)} &7→ &a${AffixFormat.number(enhancedDamage, 2)}"
        }
        lore += "&7容量上限: &f$currentCapacity &7→ &a$enhancedCapacity"
        lore += "&7耗时: &e${plugin.enhancementConfig.enhanceTimeSeconds.toInt()}s"
        lore += "&7花费: &e${cost.toInt()} &7金币"
        lore += if (ready) "&a金币充足，点击开始强化" else "&c金币不足"
        inventory.setItem(actionSlot, button(if (ready) ui.hammerButton.material else Material.BARRIER, "&b开始强化", lore))
    }

    private fun renderRivenIdle(viewer: Player?, riven: ItemStack) {
        val service = plugin.rivenService
        val instance = service.parseData(riven)
        val lore = ui.hammerButton.lore.toMutableList()
        if (instance == null || !service.isUnveiled(riven)) {
            lore += "&c封缄彼端遗纹不可强化"
            inventory.setItem(actionSlot, button(Material.BARRIER, "&c不可升级", lore))
            return
        }
        if (service.hasPendingRoll(riven)) {
            lore += "&c请先确认梦织候选词条"
            inventory.setItem(actionSlot, button(Material.BARRIER, "&c待确认", lore))
            return
        }
        if (instance.rank >= service.maxRank()) {
            lore += "&c该彼端遗纹已满段位 (${service.maxRank()}/${service.maxRank()})"
            inventory.setItem(actionSlot, button(Material.BARRIER, "&c已满级", lore))
            return
        }
        val cost = service.rankUpgradeCost(instance.rank)
        val ready = viewer != null && EconomyBridge.has(viewer, cost)
        lore += "&7强化 &e遗纹段位 ${instance.rank} &7→ &a${instance.rank + 1}"
        lore += "&7占用 &e${service.cost(instance)} &7→ &a${service.nextCost(instance)}"
        lore += "&7耗时: &e${plugin.enhancementConfig.enhanceTimeSeconds.toInt()}s"
        lore += "&7花费: &e${cost.toInt()} &7金币"
        lore += if (ready) "&a金币充足，点击开始强化" else "&c金币不足"
        inventory.setItem(actionSlot, button(if (ready) ui.hammerButton.material else Material.BARRIER, "&b开始强化", lore))
    }

    private fun renderModIdle(viewer: Player?, mod: ItemStack) {
        val config = plugin.modService.modConfig(mod)
        val lore = ui.hammerButton.lore.toMutableList()
        if (config == null || config.maxRank <= 0) {
            lore += "&c该 MOD 不可升级段位"
            inventory.setItem(actionSlot, button(Material.BARRIER, "&c不可升级", lore))
            return
        }
        val rank = plugin.modService.modRank(mod)
        if (rank >= config.maxRank) {
            lore += "&c该 MOD 已满段位 (${config.maxRank}/${config.maxRank})"
            inventory.setItem(actionSlot, button(Material.BARRIER, "&c已满级", lore))
            return
        }
        val cost = plugin.enhancementConfig.modUpgrade.costFor(config, rank)
        val ready = viewer != null && EconomyBridge.has(viewer, cost)
        lore += "&7强化 &e段位$rank &7→ &a段位${rank + 1}"
        lore += "&7耗时: &e${plugin.enhancementConfig.enhanceTimeSeconds.toInt()}s"
        lore += "&7花费: &e${cost.toInt()} &7金币"
        lore += if (ready) "&a金币充足，点击开始强化" else "&c金币不足"
        inventory.setItem(actionSlot, button(if (ready) ui.hammerButton.material else Material.BARRIER, "&b开始强化", lore))
    }
}

private fun enhanceTitle(plugin: SourceForge): net.kyori.adventure.text.Component {
    val cfg = plugin.forgeConfig.forgeUi.enhance
    return if (cfg.hasBackground) titleComponent(cfg.title) else titleComponent(plugin.forgeConfig.guiTitle)
}
