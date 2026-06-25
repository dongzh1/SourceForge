package com.dongzh1.sourceforge.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.ChatColor
import org.bukkit.inventory.meta.ItemMeta

/** 传统 §/&-颜色码转换(向后兼容,返回 §-字符串)。保留供未迁移路径使用。 */
fun color(text: String): String = ChatColor.translateAlternateColorCodes('&', text)

fun color(lines: List<String>): List<String> = lines.map(::color)

/**
 * UI 文本解析:同时支持 MiniMessage 标签与传统 §/& 颜色码。
 *
 * 配置与代码里的名称、lore、标题都可以自由混用两种写法:
 *   - MiniMessage:`<red>`、`<bold>`、`<gradient:#ff0000:#00ff00>文字</gradient>`、`<rainbow>` 等
 *   - 传统码:`§a`、`&c`(会先被 preprocess 转换成等价的 MiniMessage 标签,再统一解析)
 *
 * MiniMessage 由 Paper 自带(net.kyori.adventure),无需额外依赖。
 * 注意:CE 自定义标签(<shift>/<image>/<font>/<i18n>)Paper 的 MiniMessage 不识别,
 * 需要 CE 的完整解析器,见 CraftEngineHook.miniMessage/titleComponent。
 */
object Text {
    private val mm = MiniMessage.miniMessage()

    /** 传统单字符颜色/样式码 -> MiniMessage 标签名。 */
    private val LEGACY = mapOf(
        '0' to "black", '1' to "dark_blue", '2' to "dark_green", '3' to "dark_aqua",
        '4' to "dark_red", '5' to "dark_purple", '6' to "gold", '7' to "gray",
        '8' to "dark_gray", '9' to "blue", 'a' to "green", 'b' to "aqua",
        'c' to "red", 'd' to "light_purple", 'e' to "yellow", 'f' to "white",
        'k' to "obfuscated", 'l' to "bold", 'm' to "strikethrough",
        'n' to "underlined", 'o' to "italic", 'r' to "reset"
    )

    /** 把字符串解析成 Component;物品名/lore 默认关掉斜体(未显式指定时)。 */
    fun comp(raw: String): Component =
        mm.deserialize(preprocess(raw))
            .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)

    /** 批量解析。 */
    fun comps(lines: List<String>): List<Component> = lines.map(::comp)

    /** 给物品 meta 设置名称(支持 MiniMessage)。 */
    fun name(meta: ItemMeta, raw: String) {
        meta.displayName(comp(raw))
    }

    /** 给物品 meta 设置 lore(每行各自支持 MiniMessage)。 */
    fun lore(meta: ItemMeta, lines: List<String>) {
        meta.lore(comps(lines))
    }

    /** 一次性设置名称 + lore。 */
    fun apply(meta: ItemMeta, name: String, lore: List<String>) {
        name(meta, name)
        lore(meta, lore)
    }

    /**
     * 传统 §/& 颜色码 -> MiniMessage 标签;并支持 `&#RRGGBB` / `§#RRGGBB` 十六进制色。
     * 不含传统码时原样返回(让纯 MiniMessage 字符串零开销通过)。
     */
    fun preprocess(raw: String): String {
        if (raw.indexOf('§') < 0 && raw.indexOf('&') < 0) return raw
        val sb = StringBuilder(raw.length + 16)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if ((c == '§' || c == '&') && i + 1 < raw.length) {
                // &#RRGGBB 十六进制色
                if (raw[i + 1] == '#' && i + 7 < raw.length) {
                    val hex = raw.substring(i + 2, i + 8)
                    if (hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                        sb.append("<#").append(hex).append('>')
                        i += 8
                        continue
                    }
                }
                val tag = LEGACY[raw[i + 1].lowercaseChar()]
                if (tag != null) {
                    sb.append('<').append(tag).append('>')
                    i += 2
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
