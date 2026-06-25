package com.dongzh1.sourceforge.forge

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.config.ForgeRecipe
import com.dongzh1.sourceforge.item.CraftEngineHook
import com.dongzh1.sourceforge.multiblock.ForgeJob
import com.dongzh1.sourceforge.util.Text
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask

class ForgeMenu(
    private val plugin: SourceForge,
    /** 结构模式上下文：来自有效核心时非空，命令路径为 null。 */
    val structureContext: StructureContext? = null
) : InventoryHolder {
    /** 结构模式上下文：核心方块坐标 + 外壳层级 + 速度倍率。 */
    data class StructureContext(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val shellTier: String,
        val multiplier: Double
    )

    private val ui = plugin.forgeConfig.forgeUi

    /** 唯一输入槽：放蓝图。 */
    val blueprintSlot = ui.blueprintSlot
    /** 动作槽：空闲为锻造按钮，锻造中为进度箭头，完成为收取。 */
    val actionSlot = ui.actionSlot
    /** 产出预览槽（只读，永不可拿取）。 */
    val outputSlot = ui.outputSlot
    /** 材料需求展示槽（只读绿区）。 */
    val materialDisplaySlots: List<Int> = ui.materialSlots

    /** 这些槽位永远只读（点击被拒绝）：动作槽、产出预览、材料展示。 */
    private val readonlySlots: Set<Int> = (materialDisplaySlots + actionSlot + outputSlot).toSet()

    /**
     * 标题:forge-ui.title 非空白时用它(含 <shift> + <image:sourceforge:forge_gui>,
     * 由 CE 完整 MiniMessage 解析器在代码层解析为 Component,真正渲染 forge.png 背景图,
     * 不再依赖容器封包拦截);否则回退到 gui.title 文字标题。
     * titleComponent 同时支持 CE 标签与传统 §/& 颜色码。
     */
    private val title: net.kyori.adventure.text.Component =
        if (ui.hasBackground) CraftEngineHook.titleComponent(ui.title)
        else CraftEngineHook.titleComponent(plugin.forgeConfig.guiTitle)

    private val inventory = Bukkit.createInventory(this, ui.size, title)

    /** 锻造中实时刷新动作槽的任务；界面关闭时取消。 */
    private var progressTask: BukkitTask? = null

    init {
        fillStatic()
        renderAll(null)
    }

    override fun getInventory(): Inventory = inventory

    /**
     * 静态装饰。
     * - 有背景图(title 非空)时：不填灰玻璃(否则会盖住 forge.png 背景),非功能槽留空(空气);
     *   仅 barrier-slots 列出的槽放屏障锁死(镜像 GeneMachinery grinder 的做法)。
     * - 无背景图(文字标题)时：保留旧行为,除功能槽外全部填灰玻璃。
     */
    private fun fillStatic() {
        val reserved = readonlySlots + blueprintSlot
        if (ui.hasBackground) {
            // 屏障便捷字段：锁死指定非功能槽(防误放),其余空槽保持空气以露出背景图。
            val barrier = pane(Material.BARRIER, "&8 ", emptyList())
            for (slot in ui.barrierSlots) {
                if (slot in 0 until inventory.size && slot !in reserved) inventory.setItem(slot, barrier)
            }
            return
        }
        val filler = pane(ui.fillerMaterial, "&8 ", emptyList())
        for (slot in 0 until inventory.size) {
            if (slot !in reserved) inventory.setItem(slot, filler)
        }
    }

    /** 解析结构模式核心方块（命令模式返回 null）。 */
    fun coreBlock(): Block? {
        val ctx = structureContext ?: return null
        val world = Bukkit.getWorld(ctx.world) ?: return null
        return world.getBlockAt(ctx.x, ctx.y, ctx.z)
    }

    /** 当前核心的活动作业（命令模式恒为 null）。 */
    fun currentJob(): ForgeJob? {
        val core = coreBlock() ?: return null
        return plugin.structureManager.jobAt(core)
    }

    /** 完整刷新：材料展示 + 产出预览 + 动作槽。 */
    fun renderAll(viewer: Player?) {
        renderMaterialsAndPreview(viewer)
        renderAction(viewer)
    }

    /** 蓝图槽里的物品（空气视为 null）。 */
    fun slotItem(): ItemStack? {
        val item = inventory.getItem(blueprintSlot) ?: return null
        if (item.type == Material.AIR) return null
        return item
    }

    /** 蓝图槽里放的是否为可强化的 SF 武器（而非蓝图）。 */
    fun enhanceWeapon(): ItemStack? {
        val item = slotItem() ?: return null
        if (currentRecipe() != null) return null
        return if (plugin.itemService.isSourceEquipment(item)) item else null
    }

    /** 仅刷新材料展示与产出预览（蓝图改变时调用）。 */
    fun renderMaterialsAndPreview(viewer: Player?) {
        val weapon = enhanceWeapon()
        if (weapon != null) {
            renderEnhanceMaterialsAndPreview(viewer, weapon)
            return
        }
        val recipe = currentRecipe()

        // 清空材料展示与产出预览
        for (slot in materialDisplaySlots) inventory.setItem(slot, null)
        inventory.setItem(outputSlot, null)

        if (recipe == null) {
            inventory.setItem(
                materialDisplaySlots.firstOrNull() ?: blueprintSlot,
                label(Material.BARRIER, "&c无有效蓝图", listOf("&7在蓝图槽放入有效蓝图", "&7以查看所需材料与产出"))
            )
            return
        }

        // 产出预览
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

        // 材料展示
        recipe.materials.forEachIndexed { index, material ->
            if (index >= materialDisplaySlots.size) return@forEachIndexed
            val have = if (viewer != null) countInInventory(viewer, material.ceId) else 0
            val enough = have >= material.amount
            val icon = CraftEngineHook.build(material.ceId, 1)?.clone() ?: ItemStack(Material.PAPER)
            val meta = icon.itemMeta
            val name = plugin.forgeConfig.displayName(material.ceId)
            Text.name(meta, if (enough) "&a$name" else "&c$name")
            Text.lore(meta, listOf("&7拥有 ${if (enough) "&a" else "&c"}$have&7/&f${material.amount}"))
            icon.itemMeta = meta
            inventory.setItem(materialDisplaySlots[index], icon)
        }
    }

    /** 强化模式：展示下一段所需材料 + 强化预览。 */
    private fun renderEnhanceMaterialsAndPreview(viewer: Player?, weapon: ItemStack) {
        for (slot in materialDisplaySlots) inventory.setItem(slot, null)
        inventory.setItem(outputSlot, null)

        val category = plugin.itemService.weaponCategory(weapon)
        val level = plugin.itemService.enhanceLevel(weapon)
        val next = plugin.enhancementConfig.nextLevel(category, level)

        if (next == null) {
            inventory.setItem(
                materialDisplaySlots.firstOrNull() ?: blueprintSlot,
                label(Material.BARRIER, "&c已满级", listOf("&7该武器已达最高强化等级"))
            )
            // 预览仍展示武器本体
            val preview = weapon.clone()
            val meta = preview.itemMeta
            Text.name(meta, "&b强化预览 &7(已满级 Lv.$level)")
            preview.itemMeta = meta
            inventory.setItem(outputSlot, preview)
            return
        }

        // 强化预览：武器副本，改名 Lv.<next>
        val preview = weapon.clone()
        run {
            val meta = preview.itemMeta
            Text.name(meta, "&b强化预览 Lv.${level + 1}")
            val lore = (meta.lore() ?: mutableListOf()).toMutableList()
            lore.add(Text.comp("&7基础伤害 &a+${next.baseDamage}"))
            lore.add(Text.comp("&7容量上限 &a+${next.modCapacity}"))
            lore.add(Text.comp("&7（强化预览，不可拿取）"))
            meta.lore(lore)
            preview.itemMeta = meta
        }
        inventory.setItem(outputSlot, preview)

        next.materials.forEachIndexed { index, material ->
            if (index >= materialDisplaySlots.size) return@forEachIndexed
            val have = if (viewer != null) countInInventory(viewer, material.ceId) else 0
            val enough = have >= material.amount
            val icon = CraftEngineHook.build(material.ceId, 1)?.clone() ?: ItemStack(Material.PAPER)
            val meta = icon.itemMeta
            val name = plugin.forgeConfig.displayName(material.ceId)
            Text.name(meta, if (enough) "&a$name" else "&c$name")
            Text.lore(meta, listOf("&7拥有 ${if (enough) "&a" else "&c"}$have&7/&f${material.amount}"))
            icon.itemMeta = meta
            inventory.setItem(materialDisplaySlots[index], icon)
        }
    }

    /**
     * 动作槽三态：
     * - 锻造中（结构模式有未完成作业）：进度箭头 + 进度条；启动实时刷新任务。
     * - 完成（结构模式有已完成作业）：收取图标。
     * - 空闲（无作业 / 命令模式）：锻造按钮（材料是否充足影响样式）。
     */
    fun renderAction(viewer: Player?) {
        val job = currentJob()
        when {
            job != null && !job.isDone() -> {
                renderForging(job)
                startProgressTask()
            }
            job != null && job.isDone() -> {
                stopProgressTask()
                inventory.setItem(actionSlot, button(ui.collectButton))
            }
            else -> {
                stopProgressTask()
                renderIdle(viewer)
            }
        }
    }

    private fun renderForging(job: ForgeJob) {
        val totalTicks = (job.remainingTicks).coerceAtLeast(1L)
        val isEnhance = job.mode == ForgeJob.MODE_ENHANCE
        // 总时长无法直接从 job 取得，用配方时长 / 倍率反算（与提交时一致）。
        val recipeSeconds = if (isEnhance) plugin.enhancementConfig.enhanceTimeSeconds else (currentRecipe()?.timeSeconds ?: 0.0)
        val fullTicks = if (recipeSeconds > 0.0 && job.multiplier > 0.0) {
            Math.round(recipeSeconds * 20.0 / job.multiplier).coerceAtLeast(totalTicks)
        } else {
            totalTicks
        }
        val done = (fullTicks - job.remainingTicks).coerceIn(0, fullTicks)
        val ratio = if (fullTicks > 0) done.toDouble() / fullTicks.toDouble() else 0.0
        val seconds = Math.ceil(job.remainingTicks / 20.0).toInt()
        // 进度态：用配置的 progress 按钮（material + name + 基础 lore），再追加动态进度行。
        val lore = ui.progressButton.lore + listOf(
            "&7剩余 &f${seconds}s",
            "&7进度 ${progressBar(ratio)}"
        )
        val name = if (isEnhance) "&b强化中…" else ui.progressButton.name
        inventory.setItem(actionSlot, button(ui.progressButton.material, name, lore))
    }

    private fun renderIdle(viewer: Player?) {
        val weapon = enhanceWeapon()
        if (weapon != null) {
            renderEnhanceIdle(viewer, weapon)
            return
        }
        val recipe = currentRecipe()
        // 空闲态：配置的 hammer 按钮基础 lore 在前，动态提示行在后。
        val lore = ui.hammerButton.lore.toMutableList()
        var ready = false
        if (recipe != null) {
            ready = viewer != null && recipe.materials.all { countInInventory(viewer, it.ceId) >= it.amount }
            lore += "&7产出: &f${plugin.forgeConfig.equipmentDisplayName(recipe.equipmentId)} &7等级 &e${recipe.tier}"
            lore += "&7耗时: &e${recipe.timeSeconds.toInt()}s"
            lore += if (ready) "&a材料充足，点击开始锻造" else "&c材料不足"
        } else {
            lore += "&7放入有效蓝图后点击锻造"
        }
        inventory.setItem(actionSlot, button(ui.hammerButton.material, ui.hammerButton.name, lore))
    }

    private fun renderEnhanceIdle(viewer: Player?, weapon: ItemStack) {
        val category = plugin.itemService.weaponCategory(weapon)
        val level = plugin.itemService.enhanceLevel(weapon)
        val next = plugin.enhancementConfig.nextLevel(category, level)
        val lore = ui.hammerButton.lore.toMutableList()
        if (next == null) {
            lore += "&c该武器已满级 (Lv.$level)"
            inventory.setItem(actionSlot, button(ui.hammerButton.material, "&c已满级", lore))
            return
        }
        val ready = viewer != null && next.materials.all { countInInventory(viewer, it.ceId) >= it.amount }
        lore += "&7强化 &eLv.$level &7→ &aLv.${level + 1}"
        lore += "&7耗时: &e${plugin.enhancementConfig.enhanceTimeSeconds.toInt()}s"
        lore += if (ready) "&a材料充足，点击开始强化" else "&c材料不足"
        inventory.setItem(actionSlot, button(ui.hammerButton.material, "&b开始强化", lore))
    }

    private fun progressBar(ratio: Double): String {
        val cells = 10
        val filled = (ratio * cells).toInt().coerceIn(0, cells)
        return "&a" + "■".repeat(filled) + "&7" + "■".repeat(cells - filled)
    }

    /** 锻造中每 10 tick 刷新动作槽；无观察者或作业结束/消失时停止。 */
    private fun startProgressTask() {
        if (progressTask != null) return
        progressTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val viewers = inventory.viewers
            if (viewers.isEmpty()) {
                stopProgressTask()
                return@Runnable
            }
            val job = currentJob()
            if (job == null) {
                // 作业被收取/取消：回到空闲态
                renderIdle(viewers.firstOrNull() as? Player)
                stopProgressTask()
                return@Runnable
            }
            if (job.isDone()) {
                inventory.setItem(actionSlot, button(ui.collectButton))
                stopProgressTask()
                return@Runnable
            }
            renderForging(job)
        }, 10L, 10L)
    }

    fun stopProgressTask() {
        progressTask?.cancel()
        progressTask = null
    }

    fun currentRecipe(): ForgeRecipe? {
        val item = inventory.getItem(blueprintSlot) ?: return null
        if (item.type == Material.AIR) return null
        val ceId = CraftEngineHook.itemId(item) ?: return null
        return plugin.forgeConfig.recipes[ceId]
    }

    /** 给定槽是否为只读（不可放置/拿取）。蓝图槽不在内。 */
    fun isReadonlySlot(slot: Int): Boolean = slot in readonlySlots

    private fun countInInventory(player: Player, ceId: String): Int {
        var total = 0
        for (item in player.inventory.storageContents) {
            if (item == null || item.type == Material.AIR) continue
            if (CraftEngineHook.itemId(item) == ceId) total += item.amount
        }
        return total
    }

    /** 用配置的按钮定义直接渲染（material + name + 基础 lore，无动态行）。 */
    private fun button(cfg: com.dongzh1.sourceforge.config.ForgeButtonConfig): ItemStack =
        button(cfg.material, cfg.name, cfg.lore)

    private fun button(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        Text.name(meta, name)
        Text.lore(meta, lore)
        item.itemMeta = meta
        return item
    }

    private fun label(material: Material, name: String, lore: List<String>): ItemStack {
        return button(material, name, lore)
    }

    private fun pane(material: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        Text.name(meta, name)
        if (lore.isNotEmpty()) Text.lore(meta, lore)
        item.itemMeta = meta
        return item
    }
}
