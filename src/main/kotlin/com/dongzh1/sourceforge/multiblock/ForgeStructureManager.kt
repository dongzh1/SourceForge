package com.dongzh1.sourceforge.multiblock

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 源质锻炉作业管理器(2026-07-13 重构：MySQL 后端 + 墙钟计时 + 外壳占用绑定)。
 * - 内存中按世界持有 Map<打包坐标, ForgeJob>，供 tick/查询用，启动时从 ForgeDatabase 整表加载。
 * - tick 不再"递减"，只检查 job.isTimeElapsed()——服务器下线期间的真实流逝时间天然计入，
 *   不再需要旧版那套"脏标记批量存盘"（最大的坑：普通倒计时从不标记脏，崩服会静默丢进度）。
 * - 每次状态变化(提交/完成/收取/取消)直接同步写 MySQL，一行对一个核心。
 * - claimedBlocks：一个作业占用的核心+26块外壳，在作业存在期间(提交→收取)登记在 claims 索引里，
 *   既用于"结构区域不能跟别的作业重叠"(ForgeStructure.validate 的 isOccupied 回调)，
 *   也用于"这些方块在作业存在期间不能被拆"(ForgeStructureListener.dismantle 改成查 claimOwner)。
 */
class ForgeStructureManager(
    private val plugin: SourceForge,
    config: ForgeStructureConfig
) {
    /** 当前结构配置；/sf reload 会经 reloadConfig 刷新。 */
    var config: ForgeStructureConfig = config
        private set

    fun reloadConfig(newConfig: ForgeStructureConfig) {
        config = newConfig
    }

    private val database = ForgeDatabase(plugin)
    private val jobs = ConcurrentHashMap<String, ConcurrentHashMap<Long, ForgeJob>>()

    /** world -> 被占用的方块打包坐标 -> 占用它的核心方块。 */
    private val claims = ConcurrentHashMap<String, ConcurrentHashMap<Long, Block>>()

    /** MySQL 连接是否可用；连不上时整个多方块锻造功能静默禁用，不拖垮插件其余部分。 */
    val enabled: Boolean get() = database.isConnected

    // ==================== 生命周期 ====================

    fun loadAll() {
        if (!database.connect()) return
        for (job in database.loadAll()) {
            jobsOf(job.coreWorld)[ForgeJob.pack(job.coreX, job.coreY, job.coreZ)] = job
            registerClaims(job)
        }
    }

    fun close() {
        database.close()
    }

    private fun jobsOf(world: String): ConcurrentHashMap<Long, ForgeJob> =
        jobs.getOrPut(world) { ConcurrentHashMap() }

    private fun claimsOf(world: String): ConcurrentHashMap<Long, Block> =
        claims.getOrPut(world) { ConcurrentHashMap() }

    private fun registerClaims(job: ForgeJob) {
        val world = Bukkit.getWorld(job.coreWorld) ?: return
        val core = world.getBlockAt(job.coreX, job.coreY, job.coreZ)
        val map = claimsOf(job.coreWorld)
        for (packed in job.claimedBlocks) map[packed] = core
    }

    private fun releaseClaims(job: ForgeJob) {
        val map = claims[job.coreWorld] ?: return
        for (packed in job.claimedBlocks) map.remove(packed)
    }

    // ==================== 占用查询(结构互斥 + 拆除保护共用) ====================

    /** 该方块当前是否被某个作业占用；是则返回占用它的核心方块。 */
    fun claimOwner(block: Block): Block? = claims[block.world.name]?.get(ForgeJob.pack(block.x, block.y, block.z))

    fun isClaimed(block: Block): Boolean = claimOwner(block) != null

    /** 给定校验通过的结构，算出它的完整占用清单：核心自身 + 26 块外壳，打包坐标。 */
    fun claimedBlocksFor(core: Block, direction: Pair<Int, Int>?): List<Long> {
        val list = mutableListOf(ForgeJob.pack(core.x, core.y, core.z))
        if (direction != null) {
            ForgeStructure.shellBlocksFor(core, direction).forEach { list += ForgeJob.pack(it.x, it.y, it.z) }
        }
        return list
    }

    // ==================== tick ====================

    /** 每 tick 调用：墙钟计时，只检查"时间到了没"，不再逐 tick 递减状态。 */
    fun tick() {
        if (!enabled) return
        val now = System.currentTimeMillis()
        for (worldJobs in jobs.values) {
            for (job in worldJobs.values) {
                if (job.state != ForgeJob.STATE_FORGING) continue
                if (job.isTimeElapsed(now)) completeJob(job)
            }
        }
    }

    private fun completeJob(job: ForgeJob) {
        val output = produceOutput(job)
        job.outputItem = output?.let { encodeItem(it) }
        job.state = ForgeJob.STATE_DONE
        persistAsync(job, "保存源质锻炉作业完成状态失败")
    }

    /**
     * 异步落盘：先在主线程拷贝一份不再变化的快照([ForgeJob.copy])，再丢到异步任务里做阻塞 JDBC 写入。
     * tick() 每 tick 在主线程跑、GUI 点击(提交/收取/取消)也在主线程跑，若直接同步 upsert/delete，
     * MySQL 抖一下(网络波动/连接池瞬时耗尽)就会卡住整个服务器的主线程——JDBC 调用本身不能留在主线程。
     */
    private fun persistAsync(job: ForgeJob, errorPrefix: String) {
        val snapshot = job.copy()
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            runCatching { database.upsert(snapshot) }
                .onFailure { plugin.logger.warning("$errorPrefix [${snapshot.coreWorld} ${snapshot.coreX},${snapshot.coreY},${snapshot.coreZ}]: ${it.message}") }
        })
    }

    private fun deleteAsync(world: String, x: Int, y: Int, z: Int, errorPrefix: String) {
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            runCatching { database.delete(world, x, y, z) }
                .onFailure { plugin.logger.warning("$errorPrefix [$world $x,$y,$z]: ${it.message}") }
        })
    }

    private fun produceOutput(job: ForgeJob): ItemStack? {
        return runCatching {
            when (job.mode) {
                ForgeJob.MODE_ENHANCE -> produceEnhanceOutput(job)
                ForgeJob.MODE_UPGRADE -> produceUpgradeOutput(job)
                else -> plugin.itemService.createDirectEquipment(job.equipmentId, job.tier, null)
            }
        }.getOrNull()
    }

    /**
     * enhance 模式：解码输入物品，应用目标段位加成，作为产物。
     * 2026-07-17：输入物品若是 MOD（`plugin.modService.isModItem`），走 MOD 段位升级分支——
     * `job.enhanceTargetLevel` 复用同一字段承载"目标段位"；否则走原有的武器等级强化。
     */
    private fun produceEnhanceOutput(job: ForgeJob): ItemStack? {
        val input = job.inputItem?.let { decodeItem(it) } ?: return null
        if (plugin.rivenService.isRiven(input)) {
            return plugin.rivenService.withRank(input, job.enhanceTargetLevel) ?: input
        }
        if (plugin.modService.isModItem(input)) {
            val id = plugin.modService.modId(input) ?: return input
            return plugin.modService.createModItem(id, input.amount.coerceAtLeast(1), job.enhanceTargetLevel) ?: input
        }
        val category = plugin.itemService.weaponCategory(input)
        val current = job.enhanceTargetLevel - 1
        val level = plugin.enhancementConfig.nextLevel(category, current) ?: return input
        plugin.itemService.applyEnhancement(
            input,
            job.enhanceTargetLevel,
            level.baseDamage,
            level.modCapacity,
            level.shieldCapacity,
            level.health
        )
        return input
    }

    /** upgrade 模式：解码输入武器，原地重铸为 job.equipmentId/job.tier（此处二字段语义=目标装备/目标tier）。 */
    private fun produceUpgradeOutput(job: ForgeJob): ItemStack? {
        val weapon = job.inputItem?.let { decodeItem(it) } ?: return null
        val target = plugin.forgeConfig.equipment[job.equipmentId] ?: return weapon
        return plugin.itemService.reforgeEquipment(weapon, target, job.tier) ?: weapon
    }

    // ==================== 查询 / 提交 / 收取 / 退还 ====================

    fun jobAt(block: Block): ForgeJob? = jobs[block.world.name]?.get(ForgeJob.pack(block.x, block.y, block.z))

    fun hasJob(block: Block): Boolean = jobAt(block) != null

    /** 全部活跃作业(FORGING/DONE 都算，收取/取消后即消失)，供悬浮字渲染等只读查询用。 */
    fun activeJobs(): List<ForgeJob> = jobs.values.flatMap { it.values }

    /**
     * 提交一个锻造作业。totalDurationTicks 由配方时长 + 外壳倍率预先算好传入，
     * claimedBlocks 是这次结构校验时算出的占用清单(核心+26块外壳)，
     * consumedSnapshot 是被消耗的全部输入物品（蓝图 + 材料，用于结构被拆时退还）。
     */
    fun submitJob(
        core: Block,
        blueprintId: String,
        equipmentId: String,
        tier: Int,
        shellTier: String,
        multiplier: Double,
        totalDurationTicks: Long,
        claimedBlocks: List<Long>,
        consumedSnapshot: List<ItemStack>
    ): ForgeJob {
        val job = ForgeJob(
            coreWorld = core.world.name,
            coreX = core.x, coreY = core.y, coreZ = core.z,
            blueprintId = blueprintId,
            equipmentId = equipmentId,
            tier = tier,
            shellTier = shellTier,
            multiplier = multiplier,
            materialsSnapshot = consumedSnapshot.map(::encodeItem).toMutableList(),
            claimedBlocks = claimedBlocks.toMutableList(),
            startedAtMillis = System.currentTimeMillis(),
            totalDurationTicks = totalDurationTicks.coerceAtLeast(1L)
        )
        persistNewJob(job)
        return job
    }

    /**
     * 提交一个武器强化作业（enhance 模式）。inputWeapon 为被强化的武器（会被序列化保存），
     * targetLevel 为强化后的目标段位。consumedSnapshot 为被消耗的材料（结构被拆时退还，含武器本体）。
     */
    fun submitEnhanceJob(
        core: Block,
        inputWeapon: ItemStack,
        targetLevel: Int,
        shellTier: String,
        multiplier: Double,
        totalDurationTicks: Long,
        claimedBlocks: List<Long>,
        consumedSnapshot: List<ItemStack>
    ): ForgeJob {
        val job = ForgeJob(
            coreWorld = core.world.name,
            coreX = core.x, coreY = core.y, coreZ = core.z,
            blueprintId = "",
            equipmentId = plugin.itemService.weaponType(inputWeapon) ?: "",
            tier = plugin.itemService.equipmentTier(inputWeapon),
            shellTier = shellTier,
            multiplier = multiplier,
            materialsSnapshot = consumedSnapshot.map(::encodeItem).toMutableList(),
            claimedBlocks = claimedBlocks.toMutableList(),
            startedAtMillis = System.currentTimeMillis(),
            totalDurationTicks = totalDurationTicks.coerceAtLeast(1L),
            mode = ForgeJob.MODE_ENHANCE,
            inputItem = encodeItem(inputWeapon),
            enhanceTargetLevel = targetLevel
        )
        persistNewJob(job)
        return job
    }

    /**
     * 提交一个蓝图原地重铸作业（upgrade 模式）。inputWeapon 为待重铸的武器（会被序列化保存），
     * targetEquipmentId/targetTier 为重铸目标（与 craft 模式不同，这里 equipmentId/tier 语义是"目标"而非"输入现状"）。
     * consumedSnapshot 为被消耗的材料+蓝图（结构被拆时退还，含武器本体）。
     */
    fun submitUpgradeJob(
        core: Block,
        inputWeapon: ItemStack,
        targetEquipmentId: String,
        targetTier: Int,
        shellTier: String,
        multiplier: Double,
        totalDurationTicks: Long,
        claimedBlocks: List<Long>,
        consumedSnapshot: List<ItemStack>
    ): ForgeJob {
        val job = ForgeJob(
            coreWorld = core.world.name,
            coreX = core.x, coreY = core.y, coreZ = core.z,
            blueprintId = "",
            equipmentId = targetEquipmentId,
            tier = targetTier,
            shellTier = shellTier,
            multiplier = multiplier,
            materialsSnapshot = consumedSnapshot.map(::encodeItem).toMutableList(),
            claimedBlocks = claimedBlocks.toMutableList(),
            startedAtMillis = System.currentTimeMillis(),
            totalDurationTicks = totalDurationTicks.coerceAtLeast(1L),
            mode = ForgeJob.MODE_UPGRADE,
            inputItem = encodeItem(inputWeapon)
        )
        persistNewJob(job)
        return job
    }

    private fun persistNewJob(job: ForgeJob) {
        jobsOf(job.coreWorld)[ForgeJob.pack(job.coreX, job.coreY, job.coreZ)] = job
        registerClaims(job)
        persistAsync(job, "保存新源质锻炉作业失败")
    }

    /** 收取已完成作业的产物，加入玩家背包（溢出掉落）。返回是否收取成功。 */
    fun collect(core: Block, player: Player): Boolean {
        val key = ForgeJob.pack(core.x, core.y, core.z)
        val job = jobs[core.world.name]?.get(key) ?: return false
        if (!job.isDone()) return false
        val output = job.outputItem?.let { decodeItem(it) }
        jobs[core.world.name]?.remove(key)
        releaseClaims(job)
        deleteAsync(job.coreWorld, job.coreX, job.coreY, job.coreZ, "删除已收取的源质锻炉作业记录失败")
        if (output != null) {
            player.inventory.addItem(output).values.forEach {
                player.world.dropItemNaturally(player.location, it)
            }
        }
        val action = when (job.mode) {
            ForgeJob.MODE_ENHANCE -> com.dongzh1.sourceforge.api.SourceForgeActionEvent.Action.ENHANCE
            ForgeJob.MODE_UPGRADE -> com.dongzh1.sourceforge.api.SourceForgeActionEvent.Action.UPGRADE
            else -> com.dongzh1.sourceforge.api.SourceForgeActionEvent.Action.FORGE_COMPLETE
        }
        com.dongzh1.sourceforge.api.SourceForgeActionEvent(player, action, job.equipmentId).callEvent()
        return true
    }

    /** 核心或外壳被拆(见 ForgeStructureListener.dismantle)：取消作业，在核心位置掉落已消耗的输入材料。 */
    fun cancelAndRefund(core: Block) {
        val key = ForgeJob.pack(core.x, core.y, core.z)
        val job = jobs[core.world.name]?.remove(key) ?: return
        releaseClaims(job)
        deleteAsync(job.coreWorld, job.coreX, job.coreY, job.coreZ, "删除被取消的源质锻炉作业记录失败")
        val loc: Location = core.location.add(0.5, 0.5, 0.5)
        for (raw in job.materialsSnapshot) {
            val stack = decodeItem(raw) ?: continue
            core.world.dropItemNaturally(loc, stack)
        }
    }

    // ==================== 物品序列化 (Base64 via BukkitObjectStream) ====================

    private fun encodeItem(item: ItemStack): String {
        ByteArrayOutputStream().use { bytes ->
            BukkitObjectOutputStream(bytes).use { it.writeObject(item) }
            return Base64Coder.encodeLines(bytes.toByteArray())
        }
    }

    private fun decodeItem(data: String): ItemStack? {
        return runCatching {
            val bytes = Base64Coder.decodeLines(data)
            ByteArrayInputStream(bytes).use { input ->
                BukkitObjectInputStream(input).use { it.readObject() as? ItemStack }
            }
        }.getOrNull()
    }
}
