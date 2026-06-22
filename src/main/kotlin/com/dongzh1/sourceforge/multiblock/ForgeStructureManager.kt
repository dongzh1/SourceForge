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
 * 源质锻炉作业管理器。镜像 GeneMachinery 的 PerWorldMachineStore + tick：
 * - 内存中按世界持有 WorldForgeJobs（键 = 打包坐标 long）
 * - tick 任务推进 FORGING 作业，归零时产出物品并标记 DONE
 * - submit/collect/cancelAndRefund
 * - 按世界 Kryo 持久化（原子写），enable 时 load，disable + 周期性 save
 */
class ForgeStructureManager(
    private val plugin: SourceForge,
    val config: ForgeStructureConfig
) {
    private val store = ForgeJobStore(java.io.File(plugin.dataFolder, "forge_jobs"))
    private val worlds = ConcurrentHashMap<String, WorldForgeJobs>()
    private val dirty = ConcurrentHashMap.newKeySet<String>()

    // ==================== 生命周期 ====================

    fun loadAll() {
        for (world in Bukkit.getWorlds()) {
            worlds[world.name] = store.load(world.name)
        }
    }

    private fun worldData(world: String): WorldForgeJobs =
        worlds.getOrPut(world) { store.load(world) }

    fun saveAll() {
        for ((world, data) in worlds) {
            runCatching { store.save(world, data) }
                .onFailure { plugin.logger.warning("保存源质锻炉作业失败 [$world]: ${it.message}") }
        }
        dirty.clear()
    }

    fun saveDirty() {
        if (dirty.isEmpty()) return
        val snapshot = dirty.toList()
        dirty.clear()
        for (world in snapshot) {
            val data = worlds[world] ?: continue
            runCatching { store.save(world, data) }
                .onFailure { plugin.logger.warning("保存源质锻炉作业失败 [$world]: ${it.message}") }
        }
    }

    private fun markDirty(world: String) {
        dirty.add(world)
    }

    // ==================== tick ====================

    /** 每 tick 调用，推进 FORGING 作业。 */
    fun tick() {
        for ((world, data) in worlds) {
            if (data.jobs.isEmpty()) continue
            var changed = false
            for (job in data.jobs.values) {
                if (job.state != ForgeJob.STATE_FORGING) continue
                if (job.remainingTicks > 0) {
                    job.remainingTicks--
                    if (job.remainingTicks <= 0) {
                        completeJob(job)
                        changed = true
                    }
                }
            }
            if (changed) markDirty(world)
        }
    }

    private fun completeJob(job: ForgeJob) {
        val output = produceOutput(job)
        job.outputItem = output?.let { encodeItem(it) }
        job.state = ForgeJob.STATE_DONE
        job.remainingTicks = 0
    }

    private fun produceOutput(job: ForgeJob): ItemStack? {
        return runCatching {
            plugin.itemService.createDirectEquipment(job.equipmentId, job.tier, null)
        }.getOrNull()
    }

    // ==================== 查询 / 提交 / 收取 / 退还 ====================

    fun jobAt(block: Block): ForgeJob? {
        val data = worlds[block.world.name] ?: return null
        return data.jobs[WorldForgeJobs.pack(block.x, block.y, block.z)]
    }

    fun hasJob(block: Block): Boolean = jobAt(block) != null

    /**
     * 提交一个锻造作业。remainingTicks 由配方时长 + 外壳倍率预先算好传入，
     * consumedSnapshot 是被消耗的全部输入物品（蓝图 + 材料，用于核心破坏时退还）。
     */
    fun submitJob(
        core: Block,
        blueprintId: String,
        equipmentId: String,
        tier: Int,
        shellTier: String,
        multiplier: Double,
        remainingTicks: Long,
        consumedSnapshot: List<ItemStack>
    ): ForgeJob {
        val snapshot = ArrayList<String>()
        consumedSnapshot.forEach { encodeItem(it).let(snapshot::add) }
        val ticks = remainingTicks.coerceAtLeast(1L)
        val job = ForgeJob(
            coreWorld = core.world.name,
            coreX = core.x,
            coreY = core.y,
            coreZ = core.z,
            blueprintId = blueprintId,
            equipmentId = equipmentId,
            tier = tier,
            shellTier = shellTier,
            multiplier = multiplier,
            materialsSnapshot = snapshot,
            remainingTicks = ticks
        )
        val data = worldData(core.world.name)
        data.jobs[WorldForgeJobs.pack(core.x, core.y, core.z)] = job
        markDirty(core.world.name)
        return job
    }

    /** 收取已完成作业的产物，加入玩家背包（溢出掉落）。返回是否收取成功。 */
    fun collect(core: Block, player: Player): Boolean {
        val data = worlds[core.world.name] ?: return false
        val key = WorldForgeJobs.pack(core.x, core.y, core.z)
        val job = data.jobs[key] ?: return false
        if (!job.isDone()) return false
        val output = job.outputItem?.let { decodeItem(it) }
        data.jobs.remove(key)
        markDirty(core.world.name)
        if (output != null) {
            player.inventory.addItem(output).values.forEach {
                player.world.dropItemNaturally(player.location, it)
            }
        }
        return true
    }

    /** 核心被破坏：取消作业并在核心位置掉落已消耗的输入材料（绝不丢失）。 */
    fun cancelAndRefund(core: Block) {
        val data = worlds[core.world.name] ?: return
        val key = WorldForgeJobs.pack(core.x, core.y, core.z)
        val job = data.jobs.remove(key) ?: return
        markDirty(core.world.name)
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
