package com.dongzh1.sourceforge.forge

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.config.ForgeButtonConfig
import com.dongzh1.sourceforge.config.ForgeUiConfig
import com.dongzh1.sourceforge.config.RecipeMaterial
import com.dongzh1.sourceforge.item.FramedMaterialItems
import com.dongzh1.sourceforge.multiblock.ForgeJob
import com.dongzh1.sourceforge.util.Text
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask

/**
 * 源质锻炉 GUI 公共基类。2026-07-16 重构：锻造/强化/重铸从"一个 Inventory 内按 mode 切槽位含义"
 * 改成三个完全独立的 Inventory 子类（[ForgeCraftMenu]/[ForgeEnhanceMenu]/[ForgeUpgradeMenu]，
 * 见 [ForgeMenus] 工厂）——不同模式的槽位号即使数值相同也互不影响，彻底消除"某个功能槽在另一模式里
 * 被复用/误判"这一类漏洞（历史事故：blueprint 与 mode-toggle 曾共享同一个 Inventory、撞在同一槽位）。
 * 点击模式切换按钮时关闭当前界面、打开另一模式对应的全新 Menu 实例（见 [ForgeMenuListener]），
 * 而不是原地重绘同一个 Inventory；关闭旧界面时走原有的"归还真实物品"逻辑，天然不需要额外处理
 * "切换模式时输入槽里的东西怎么办"——跟玩家手动关闭界面一样对待。
 */
sealed class ForgeMenu protected constructor(
    protected val plugin: SourceForge,
    val structureContext: StructureContext?,
    val mode: Mode,
    size: Int,
    title: Component,
    val actionSlot: Int,
    val outputSlot: Int,
    val modeToggleSlot: Int,
    extraReadonlySlots: Set<Int>
) : InventoryHolder {

    enum class Mode { CRAFT, ENHANCE, UPGRADE }

    /** 结构模式上下文：核心方块坐标 + 外壳层级 + 速度倍率。
     * direction：这次结构校验用的"向内"方向，用于提交作业时算出占用清单(核心+26块外壳)——
     * 复用已有作业时不重新校验结构，direction 为 null(占用早已在提交时登记过，不需要再算)。 */
    data class StructureContext(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val shellTier: String,
        val multiplier: Double,
        val direction: Pair<Int, Int>? = null
    )

    /** 按钮外观/填充材质等三模式共用的配置（槽位布局各自独立，见各子类）。 */
    protected val ui: ForgeUiConfig = plugin.forgeConfig.forgeUi

    /** 标记"这是本界面自己放的占位玻璃板"，跟玩家真实物品区分开——输入槽显示占位板时可以被
     * 真实物品安全顶掉，不会被 [ForgeMenuListener.onClose] 误当成玩家物品还给玩家。 */
    private val placeholderKey = NamespacedKey(plugin, "gui_placeholder")

    /** 材料需求展示槽（只读，仅锻造/重铸模式有；强化模式无材料槽，见 [ForgeEnhanceMenu]）。 */
    open val materialDisplaySlots: List<Int> = emptyList()

    /** 这些槽位永远只读（点击被拒绝）：动作槽、产出预览、模式切换按钮 + 子类各自的材料展示槽。
     * 不含输入槽——输入槽由 [inputSlots] 描述。 */
    val readonlySlots: Set<Int> = setOf(actionSlot, outputSlot, modeToggleSlot) + extraReadonlySlots

    /** 本界面允许玩家放置真实物品的槽位（蓝图/武器槽等）。关闭界面时这些槽里的真实物品会还给玩家。 */
    abstract fun inputSlots(): List<Int>

    // 注意：这个字段不能叫 inventory —— InventoryHolder.getInventory() 是 Java 接口方法，Kotlin 会
    // 为它反向合成一个名为 inventory 的属性(供 Kotlin 侧以属性语法调用)；如果这里再声明一个同名
    // 属性 inventory，会跟合成的 getInventory() 产生 JVM 签名冲突(Platform declaration clash)。
    // 内部统一用 backingInventory，子类需要访问箱子时用继承来的公开 getInventory() / `inventory` 均可。
    private val backingInventory: Inventory = Bukkit.createInventory(this, size, title)
    override fun getInventory(): Inventory = backingInventory

    /** 锻造中实时刷新动作槽的任务；界面关闭时取消。 */
    private var progressTask: BukkitTask? = null

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

    /** 给定槽是否为只读（不可放置/拿取）。输入槽不在内。 */
    fun isReadonlySlot(slot: Int): Boolean = slot in readonlySlots

    /** 这个物品是否是本界面自己放的占位玻璃板（视觉上的"空槽"）。真实物品可以安全顶掉它，
     * 关闭界面时也不会被误当成玩家物品还回去。 */
    fun isPlaceholder(item: ItemStack?): Boolean {
        val meta = item?.itemMeta ?: return false
        return meta.persistentDataContainer.has(placeholderKey, PersistentDataType.BYTE)
    }

    /** 子类在自己 init 末尾调用：填充静态装饰(玻璃板/占位符) + 完整渲染一次。此时子类自身的属性
     * (槽位号/子配置等)均已构造完毕，调用抽象方法不会踩到"基类 init 期间子类属性未初始化"的坑。 */
    protected fun initialRender() {
        fillStatic()
        renderAll(null)
    }

    /** 静态装饰：填充非功能槽、放置输入槽的初始占位板——各模式各自实现。 */
    protected abstract fun fillStatic()

    /** 完整刷新：模式按钮 + 内容(输入槽提示/材料展示/产出预览) + 动作槽。 */
    fun renderAll(viewer: Player?) {
        renderModeButton()
        renderContent(viewer)
        renderAction(viewer)
    }

    /** 内容渲染(输入槽占位提示、材料展示、产出/强化预览)——子类各自实现，蓝图/武器槽内容变化时调用。 */
    abstract fun renderContent(viewer: Player?)

    /** 空闲态动作槽渲染(锻造/强化/重铸按钮及其可用性提示)——子类各自实现。 */
    protected abstract fun renderIdle(viewer: Player?)

    private fun renderModeButton() {
        val (material, name) = when (mode) {
            Mode.CRAFT -> Material.ANVIL to "<gold><bold>模式: 锻造 <gray>(点击切换)"
            Mode.ENHANCE -> Material.EXPERIENCE_BOTTLE to "<aqua><bold>模式: 强化 <gray>(点击切换)"
            Mode.UPGRADE -> Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE to "<light_purple><bold>模式: 重铸 <gray>(点击切换)"
        }
        backingInventory.setItem(
            modeToggleSlot,
            button(
                material, name,
                listOf(
                    "&7锻造 → 强化 → 重铸 循环切换",
                    "&7锻造: 蓝图 + 材料 产出新装备",
                    "&7强化: 消耗金币升级已有装备(最高15级)",
                    "&7重铸: 蓝图 + 材料 原地重铸(强化等级清零)",
                    "&8(每种模式都是独立界面，切换会关闭当前界面)"
                )
            )
        )
    }

    /**
     * 动作槽三态：
     * - 锻造中（结构模式有未完成作业）：进度箭头 + 进度条；启动实时刷新任务。
     * - 完成（结构模式有已完成作业）：收取图标。
     * - 空闲（无作业 / 命令模式）：锻造按钮（材料是否充足影响样式，交给子类 [renderIdle]）。
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
                backingInventory.setItem(actionSlot, button(ui.collectButton))
            }
            else -> {
                stopProgressTask()
                renderIdle(viewer)
            }
        }
    }

    /**
     * 直接读 job 自身持久化的 totalDurationTicks/startedAtMillis(墙钟计时，见 ForgeJob)，
     * 不反查输入槽当前物品——蓝图/武器早已在提交时被消耗，槽位变回占位板。
     */
    private fun renderForging(job: ForgeJob) {
        val now = System.currentTimeMillis()
        val total = job.totalDurationTicks.coerceAtLeast(1L)
        val remaining = job.remainingTicks(now)
        val done = (total - remaining).coerceIn(0L, total)
        val ratio = done.toDouble() / total.toDouble()
        val seconds = Math.ceil(remaining / 20.0).toInt()
        val isEnhance = job.mode == ForgeJob.MODE_ENHANCE
        val lore = ui.progressButton.lore + listOf(
            "&7剩余 &f${seconds}s",
            "&7进度 ${progressBar(ratio)}"
        )
        val name = if (isEnhance) "&b强化中…" else ui.progressButton.name
        backingInventory.setItem(actionSlot, button(ui.progressButton.material, name, lore))
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
            val viewers = backingInventory.viewers
            if (viewers.isEmpty()) {
                stopProgressTask()
                return@Runnable
            }
            val job = currentJob()
            if (job == null) {
                renderIdle(viewers.firstOrNull() as? Player)
                stopProgressTask()
                return@Runnable
            }
            if (job.isDone()) {
                backingInventory.setItem(actionSlot, button(ui.collectButton))
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

    /**
     * 材料展示槽统一渲染（CREATE/重铸共用）：有材料给真实材质+库存对比色，
     * 超出配方长度的槽位统一给"空材料位"占位板。
     */
    protected fun renderMaterialSlots(viewer: Player?, slots: List<Int>, materials: List<RecipeMaterial>) {
        slots.forEachIndexed { index, slot ->
            val material = materials.getOrNull(index)
            if (material == null) {
                backingInventory.setItem(slot, pane(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7－", listOf("&8此配方无需更多材料")))
                return@forEachIndexed
            }
            val have = if (viewer != null) plugin.itemService.countInInventory(viewer, material.ceId) else 0
            val enough = have >= material.amount
            val icon = FramedMaterialItems.build(material.ceId, 1, enough)?.clone()
                ?: Material.matchMaterial(material.ceId)?.let { ItemStack(it) }
                ?: ItemStack(Material.PAPER)
            val meta = icon.itemMeta
            val color = if (enough) NamedTextColor.GREEN else NamedTextColor.RED
            Text.name(meta, plugin.forgeConfig.bareNameComponent(material.ceId).color(color))
            Text.lore(meta, listOf(
                "&7拥有 ${if (enough) "&a" else "&c"}$have&7/&f${material.amount}",
                "&8(自动从背包扣除，无需放入)"
            ))
            icon.itemMeta = meta
            backingInventory.setItem(slot, icon)
        }
    }

    protected fun button(cfg: ForgeButtonConfig): ItemStack = button(cfg.material, cfg.name, cfg.lore)

    protected fun button(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        Text.name(meta, name)
        Text.lore(meta, lore)
        item.itemMeta = meta
        return item
    }

    protected fun label(material: Material, name: String, lore: List<String>): ItemStack = button(material, name, lore)

    protected fun pane(material: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        Text.name(meta, name)
        if (lore.isNotEmpty()) Text.lore(meta, lore)
        meta.persistentDataContainer.set(placeholderKey, PersistentDataType.BYTE, 1)
        item.itemMeta = meta
        return item
    }
}
