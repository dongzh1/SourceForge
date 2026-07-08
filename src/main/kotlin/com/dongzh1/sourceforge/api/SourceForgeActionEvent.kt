package com.dongzh1.sourceforge.api

import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * SourceForge 对外公开事件（预留 API）。在关键玩法里程碑触发，供 SourceTasks 等外部插件
 * 用 compileOnly + 真监听消费，避免反射。detail 视 action 而定（如装备/武器类型 id）。
 */
class SourceForgeActionEvent @JvmOverloads constructor(
    val player: Player,
    val action: Action,
    val detail: String = ""
) : Event() {

    enum class Action {
        /** 锻造完成（结构锻炉收取产物 / 命令即时产出） */
        FORGE_COMPLETE,
        /** 武器强化完成 */
        ENHANCE,
        /** 安装一个模组到装备 */
        MOD_INSTALL,
        /** 多方块锻炉搭建成型 */
        STRUCTURE_FORMED
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
