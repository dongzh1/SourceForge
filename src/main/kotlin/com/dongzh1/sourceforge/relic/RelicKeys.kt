package com.dongzh1.sourceforge.relic

import org.bukkit.NamespacedKey
import org.bukkit.plugin.Plugin

/** 遗物系统用的 PDC key 工厂，风格同 mod/ModKeys.kt。 */
object RelicKeys {
    /** 记录"这个具体遗物物品实例绑定的解锁用SourceTasks任务id"的PDC key。 */
    fun relicQuest(plugin: Plugin): NamespacedKey = NamespacedKey(plugin, "relic_quest")
}
