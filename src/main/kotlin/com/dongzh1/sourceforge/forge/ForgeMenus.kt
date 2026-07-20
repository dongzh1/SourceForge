package com.dongzh1.sourceforge.forge

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.entity.Player

/**
 * 三态锻炉 GUI 的构造/打开入口。锻造/强化/重铸各自是完全独立的 [ForgeMenu] 子类实例
 * （见 [ForgeCraftMenu]/[ForgeEnhanceMenu]/[ForgeUpgradeMenu]）——命令路径与结构核心右键
 * 路径都只需要打开默认的锻造模式；模式切换按钮点击时（见 [ForgeMenuListener]）用 [create]
 * 现开一个新模式的实例并 [Player.openInventory]，旧界面按普通关闭流程处理。
 */
object ForgeMenus {
    fun create(plugin: SourceForge, structureContext: ForgeMenu.StructureContext?, mode: ForgeMenu.Mode): ForgeMenu =
        when (mode) {
            ForgeMenu.Mode.CRAFT -> ForgeCraftMenu(plugin, structureContext)
            ForgeMenu.Mode.ENHANCE -> ForgeEnhanceMenu(plugin, structureContext)
            ForgeMenu.Mode.UPGRADE -> ForgeUpgradeMenu(plugin, structureContext)
        }

    fun open(
        plugin: SourceForge,
        player: Player,
        structureContext: ForgeMenu.StructureContext?,
        mode: ForgeMenu.Mode = ForgeMenu.Mode.CRAFT
    ) {
        player.openInventory(create(plugin, structureContext, mode).inventory)
    }
}
