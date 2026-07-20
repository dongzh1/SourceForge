package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.config.AffixConfig
import com.dongzh1.sourceforge.config.ForgeConfig
import kotlin.math.abs

/** MOD 卡统一 lore：顶部装配信息、中部效果分区、底部适用装备。 */
class ModLoreBuilder(private val plugin: SourceForge, private val forgeConfig: ForgeConfig) {

    fun build(mod: ModConfig, rank: Int): List<String> {
        val lines = mutableListOf<String>()
        lines += "&8◆ &7${kindLabel(mod)} MOD  &8│  &7占用 &e${mod.cost}"
        if (mod.skill) lines += triggerLine(mod)
        if (mod.passiveSkill) lines += "&8◆ &7生效方式  &f装备后生效 &8· &7被动技能槽"
        if (mod.maxRank > 0) lines += rankLine(mod, rank)

        if (mod.effects.isNotEmpty()) {
            lines += ""
            lines += "&d✦ 属性增幅"
            for (affixId in mod.effects.keys) lines += effectLine(mod, affixId, rank)
        }

        val skillish = mod.skill || mod.passiveSkill || "skill" in mod.tags
        val skillMeta = mutableListOf<String>()
        if (skillish) {
            mod.manaCost?.let { skillMeta += "  &8▪ &7能量消耗  &b${resolve(it)}" }
            mod.cooldown?.let { skillMeta += "  &8▪ &7冷却时间  &f${resolve(it)}" }
        }
        val desc = mod.description.map { descLine(it) }
        if (skillish && (skillMeta.isNotEmpty() || desc.isNotEmpty())) {
            lines += ""
            lines += "&b✦ 技能效果"
            lines += skillMeta
            lines += desc
        } else if (desc.isNotEmpty()) {
            lines += ""
            lines += "&6✦ 效果说明"
            lines += desc
        }

        lines += ""
        lines += "&7适用装备  &f${scopeLabel(mod)}"
        return lines
    }

    /** 头部类别：属性/元素/技能/护甲被动/测试（yml type-label 可覆盖）。 */
    private fun kindLabel(mod: ModConfig): String = mod.typeLabel ?: when {
        "test" in mod.tags -> "测试"
        mod.passiveSkill -> "护甲被动"
        mod.skill || "skill" in mod.tags -> "技能"
        "elemental" in mod.tags -> "元素"
        else -> "属性"
    }

    private fun triggerLine(mod: ModConfig): String {
        val labels = if (mod.allowedTriggers.isEmpty()) "任意"
        else TriggerSlot.entries.filter { it.id in mod.allowedTriggers }.joinToString("/") { it.display }
        return "&8◆ &7触发方式  &f$labels &8· &7技能栏"
    }

    /** Warframe 式段位菱形：&b◆◆&8◇◇◇ (2/5)；满段位 &6◆◆◆◆◆ (MAX)。 */
    private fun rankLine(mod: ModConfig, rank: Int): String {
        val r = rank.coerceIn(0, mod.maxRank)
        return if (r >= mod.maxRank) "&8◆ &7段位  &6${"◆".repeat(mod.maxRank)} &7MAX"
        else "&8◆ &7段位  &b${"◆".repeat(r)}&8${"◇".repeat(mod.maxRank - r)} &7$r/${mod.maxRank}"
    }

    /** 词条行：当前段位值 &8→ &a下一段位值；满段/不可升级只显示当前。 */
    private fun effectLine(mod: ModConfig, affixId: String, rank: Int): String {
        val affix = forgeConfig.affixes[affixId]
        val name = affix?.displayName ?: affixId
        val col = affix?.color ?: "&f"
        val cur = signed(affix, mod.effectAtRank(affixId, rank))
        return when {
            mod.maxRank <= 0 -> "  &8▪ &7$name  $col$cur"
            rank >= mod.maxRank -> "  &8▪ &7$name  $col$cur &8MAX"
            else -> "  &8▪ &7$name  $col$cur &8→ &a${signed(affix, mod.effectAtRank(affixId, rank + 1))}"
        }
    }

    private fun descLine(raw: String): String {
        val resolved = resolve(raw)
        val readable = when {
            resolved.startsWith("&8") || resolved.startsWith("§8") -> "&7${resolved.substring(2)}"
            else -> resolved
        }
        val prefixed = if (readable.startsWith('&') || readable.startsWith('§')) readable else "&7$readable"
        return "  &8▪ $prefixed"
    }

    private fun signed(affix: AffixConfig?, value: Double): String {
        val s = fmtAffix(affix, abs(value))
        return if (value < 0) "-$s" else "+$s"
    }

    /** 比例词条 ×100 加 %，小数位相应减 2；数值统一去掉尾随 0。实现共用
     * [com.dongzh1.sourceforge.util.AffixFormat]，与 ForgeItemService 的装备lore数值口径保持一致。 */
    private fun fmtAffix(affix: AffixConfig?, value: Double): String =
        com.dongzh1.sourceforge.util.AffixFormat.affixValue(affix?.percent == true, affix?.decimals ?: 1, value)

    private fun fmt(value: Double, decimals: Int): String =
        com.dongzh1.sourceforge.util.AffixFormat.number(value, decimals)

    /** %cfg:path|默认% / %cfgs:…%(ms→s) / %cfgt:…%(tick→s) -> config.yml 实时数值。 */
    private fun resolve(text: String): String {
        val resolved = if ("%cfg" !in text) text else CFG_PATTERN.replace(text) { m ->
            val unit = m.groupValues[1]
            val path = m.groupValues[2].trim()
            val def = m.groupValues[3].toDoubleOrNull()
            val raw = if (plugin.config.contains(path)) plugin.config.getDouble(path) else (def ?: 0.0)
            val v = when (unit) {
                "s" -> raw / 1000.0
                "t" -> raw / 20.0
                else -> raw
            }
            fmt(v, 2)
        }
        return PERCENT_RUN.replace(resolved, "%")
    }

    /** 适用范围：优先具体装备，其次限定部位，最后将类别集合压缩成玩家易读的通用叫法。 */
    private fun scopeLabel(mod: ModConfig): String {
        if (mod.applicableEquipment.isNotEmpty()) {
            return mod.applicableEquipment.joinToString(" · ") { forgeConfig.equipmentDisplayName(it) }
        }
        if (mod.applicableSlots.isNotEmpty() && mod.applicableCategories == ARMOR_CATS) {
            return mod.applicableSlots.joinToString(" · ") { slotLabel(it) }
        }
        val c = mod.applicableCategories
        val base = when {
            c.isEmpty() -> "通用"
            c == WEAPON_CATS -> "武器通用"
            c == MELEE_CATS -> "近战武器"
            c == RANGED_CATS -> "远程武器"
            c == ARMOR_CATS -> "护甲通用"
            else -> c.joinToString(" · ") { catLabel(it) }
        }
        return if (mod.applicableSlots.isEmpty()) base
        else "$base &8（仅${mod.applicableSlots.joinToString(" · ") { slotLabel(it) }}）"
    }

    private fun slotLabel(slot: String): String = when (slot) {
        "head" -> "头盔"; "chest" -> "胸甲"; "legs" -> "护腿"; "feet" -> "靴子"; else -> slot
    }

    private fun catLabel(id: String): String =
        plugin.config.getString("mods.category-labels.$id") ?: DEFAULT_CAT_LABELS[id] ?: id

    companion object {
        private val CFG_PATTERN = Regex("%cfg([st]?):([^%|]+)(?:\\|([^%]*))?%")
        private val PERCENT_RUN = Regex("%{2,}")
        private val WEAPON_CATS = setOf("melee_light", "melee_heavy", "polearm", "bow", "crossbow", "firearm")
        private val MELEE_CATS = setOf("melee_light", "melee_heavy", "polearm")
        private val RANGED_CATS = setOf("bow", "crossbow", "firearm")
        private val ARMOR_CATS = setOf("armor_physical", "armor_magic")
        private val DEFAULT_CAT_LABELS = mapOf(
            "melee_light" to "轻近战", "melee_heavy" to "重近战", "polearm" to "长柄武器",
            "bow" to "弓", "crossbow" to "弩", "firearm" to "枪械",
            "armor_physical" to "物理护甲", "armor_magic" to "法术护甲",
            "summon" to "召唤武器", "pickaxe" to "镐"
        )
    }
}
