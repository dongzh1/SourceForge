package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.config.AffixConfig
import com.dongzh1.sourceforge.config.ForgeConfig
import java.text.DecimalFormat
import kotlin.math.abs

/**
 * MOD 卡统一 lore 排版（参考源灵 PetGui 的分区段式布局）。
 *
 * ```
 * &8属性MOD &8| &7容量 &e9          <- 头部：类别 | 容量（弱化色）
 * &7触发 &f右键 &8技能栏             <- 技能触发栏MOD 才有
 * &7段位 &b◆◆&8◇◇◇ &7(2/5)         <- 可升级MOD 才有；满段位金色 + MAX
 *
 * &d● 属性&7:
 *   &7基础伤害 &f+2 &8→ &a+2.7      <- 当前段位 → 下一段位；比例词条按百分比
 *
 * &b● 技能&7:
 *   &7消耗 &b18 能量                 <- mods yml `mana`
 *   &7冷却 &f7秒                     <- mods yml `cooldown`
 *   &7机制说明…                      <- mods yml `description`
 *
 * &8⚑ 适用: &7近战武器               <- 按 applicable-categories/equipment 推导
 * ```
 *
 * `mana`/`cooldown`/`description` 支持占位符（createModItem 时解析，跟随 /sf reload）：
 *  - `%cfg:path|默认%`  config.yml 数值原样
 *  - `%cfgs:path|默认%` 毫秒 → 秒
 *  - `%cfgt:path|默认%` tick → 秒
 */
class ModLoreBuilder(private val plugin: SourceForge, private val forgeConfig: ForgeConfig) {

    fun build(mod: ModConfig, rank: Int): List<String> {
        val lines = mutableListOf<String>()
        lines += "&8${kindLabel(mod)}MOD &8| &7容量 &e${mod.cost}"
        if (mod.skill) lines += triggerLine(mod)
        if (mod.maxRank > 0) lines += rankLine(mod, rank)

        if (mod.effects.isNotEmpty()) {
            lines += ""
            lines += "&d● 属性&7:"
            for (affixId in mod.effects.keys) lines += effectLine(mod, affixId, rank)
        }

        val skillish = mod.skill || "skill" in mod.tags
        val skillMeta = mutableListOf<String>()
        if (skillish) {
            mod.manaCost?.let { skillMeta += "  &7消耗 &b${resolve(it)}" }
            mod.cooldown?.let { skillMeta += "  &7冷却 &f${resolve(it)}" }
        }
        val desc = mod.description.map { descLine(it) }
        if (skillish && (skillMeta.isNotEmpty() || desc.isNotEmpty())) {
            lines += ""
            lines += "&b● 技能&7:"
            lines += skillMeta
            lines += desc
        } else if (desc.isNotEmpty()) {
            lines += ""
            lines += desc
        }

        lines += ""
        lines += "&8⚑ 适用: &7${scopeLabel(mod)}"
        return lines
    }

    /** 头部类别：属性/元素/技能/测试（yml type-label 可覆盖）。 */
    private fun kindLabel(mod: ModConfig): String = mod.typeLabel ?: when {
        "test" in mod.tags -> "测试"
        mod.skill || "skill" in mod.tags -> "技能"
        "elemental" in mod.tags -> "元素"
        else -> "属性"
    }

    private fun triggerLine(mod: ModConfig): String {
        val labels = if (mod.allowedTriggers.isEmpty()) "任意"
        else TriggerSlot.entries.filter { it.id in mod.allowedTriggers }.joinToString("/") { it.display }
        return "&7触发 &f$labels &8技能栏"
    }

    /** Warframe 式段位菱形：&b◆◆&8◇◇◇ (2/5)；满段位 &6◆◆◆◆◆ (MAX)。 */
    private fun rankLine(mod: ModConfig, rank: Int): String {
        val r = rank.coerceIn(0, mod.maxRank)
        return if (r >= mod.maxRank) "&7段位 &6${"◆".repeat(mod.maxRank)} &7(MAX)"
        else "&7段位 &b${"◆".repeat(r)}&8${"◇".repeat(mod.maxRank - r)} &7($r/${mod.maxRank})"
    }

    /** 词条行：当前段位值 &8→ &a下一段位值；满段/不可升级只显示当前。 */
    private fun effectLine(mod: ModConfig, affixId: String, rank: Int): String {
        val affix = forgeConfig.affixes[affixId]
        val name = affix?.displayName ?: affixId
        val col = affix?.color ?: "&f"
        val cur = signed(affix, mod.effectAtRank(affixId, rank))
        return when {
            mod.maxRank <= 0 -> "  &7$name $col$cur"
            rank >= mod.maxRank -> "  &7$name $col$cur &8(MAX)"
            else -> "  &7$name $col$cur &8→ &a${signed(affix, mod.effectAtRank(affixId, rank + 1))}"
        }
    }

    private fun descLine(raw: String): String {
        val resolved = resolve(raw)
        val prefixed = if (resolved.startsWith('&') || resolved.startsWith('§')) resolved else "&7$resolved"
        return "  $prefixed"
    }

    private fun signed(affix: AffixConfig?, value: Double): String {
        val s = fmtAffix(affix, abs(value))
        return if (value < 0) "-$s" else "+$s"
    }

    /** 比例词条 ×100 加 %，小数位相应减 2；数值统一去掉尾随 0。 */
    private fun fmtAffix(affix: AffixConfig?, value: Double): String {
        val decimals = affix?.decimals ?: 1
        return if (affix?.percent == true) fmt(value * 100.0, (decimals - 2).coerceAtLeast(0)) + "%"
        else fmt(value, decimals)
    }

    private fun fmt(value: Double, decimals: Int): String {
        val s = if (decimals <= 0) DecimalFormat("0").format(value)
        else DecimalFormat("0." + "0".repeat(decimals)).format(value)
        return if ('.' in s) s.trimEnd('0').trimEnd('.') else s
    }

    /** %cfg:path|默认% / %cfgs:…%(ms→s) / %cfgt:…%(tick→s) -> config.yml 实时数值。 */
    private fun resolve(text: String): String {
        if ("%cfg" !in text) return text
        return CFG_PATTERN.replace(text) { m ->
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
    }

    /** 适用范围：优先 applicable-equipment 的装备显示名；否则按类别集合压缩成通用叫法。 */
    private fun scopeLabel(mod: ModConfig): String {
        if (mod.applicableEquipment.isNotEmpty()) {
            return mod.applicableEquipment.joinToString("·") { forgeConfig.equipmentDisplayName(it) }
        }
        val c = mod.applicableCategories
        return when {
            c.isEmpty() -> "全部装备"
            c == WEAPON_CATS -> "武器"
            c == MELEE_CATS -> "近战武器"
            c == ARMOR_CATS -> "护甲"
            else -> c.joinToString("·") { catLabel(it) }
        }
    }

    private fun catLabel(id: String): String =
        plugin.config.getString("mods.category-labels.$id") ?: DEFAULT_CAT_LABELS[id] ?: id

    companion object {
        private val CFG_PATTERN = Regex("%cfg([st]?):([^%|]+)(?:\\|([^%]*))?%")
        private val WEAPON_CATS = setOf("melee_light", "melee_heavy", "polearm", "bow", "crossbow", "firearm")
        private val MELEE_CATS = setOf("melee_light", "melee_heavy", "polearm")
        private val ARMOR_CATS = setOf("armor_physical", "armor_magic")
        private val DEFAULT_CAT_LABELS = mapOf(
            "melee_light" to "轻近战", "melee_heavy" to "重近战", "polearm" to "长柄武器",
            "bow" to "弓", "crossbow" to "弩", "firearm" to "枪械",
            "armor_physical" to "物理护甲", "armor_magic" to "法术护甲",
            "summon" to "召唤武器"
        )
    }
}
