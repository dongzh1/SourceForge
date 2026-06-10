package com.dongzh1.sourceforge.util

import org.bukkit.ChatColor

fun color(text: String): String = ChatColor.translateAlternateColorCodes('&', text)

fun color(lines: List<String>): List<String> = lines.map(::color)
