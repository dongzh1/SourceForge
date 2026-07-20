package com.dongzh1.sourceforge.multiblock

import com.dongzh1.sourceforge.SourceForge
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection

/**
 * 源质锻炉作业的 MySQL 持久化(2026-07-13 从本地 Kryo 文件迁移而来)。
 *
 * 审查结论：旧模型(remainingTicks 倒计时 + 文件"脏标记"批量存盘)有崩服静默丢进度的风险，
 * 且没法做跨服/后台查询。换成墙钟计时(见 ForgeJob) + MySQL 后，每次状态变化(提交/完成/收取/取消)
 * 直接同步落一行 UPSERT/DELETE，不再需要"脏"批量存盘那一套——没有中间态需要额外保存。
 *
 * 表 forge_jobs：(world, x, y, z) 联合主键，每个核心同一时刻最多一行(跟旧版"一个核心一个作业"的
 * 不变量一致)。materials_snapshot/claimed_blocks 拼接存成 TEXT，不做范式化——这张表只服务于
 * "这个核心当前的作业状态"，没有查询历史记录的需求。
 *
 * 连接信息读取 config.yml 的 `database:` 段，需要用户自行配置真实的 MySQL 地址/账号(见部署说明)。
 * 连不上时 connect() 返回 false，调用方(ForgeStructureManager)据此让多方块锻造功能整体禁用，
 * 但不影响插件其余功能正常加载。
 */
class ForgeDatabase(private val plugin: SourceForge) {
    private var dataSource: HikariDataSource? = null

    val isConnected: Boolean get() = dataSource != null

    fun connect(): Boolean {
        val cfg = plugin.config
        val host = cfg.getString("database.host", "localhost")!!
        val port = cfg.getInt("database.port", 3306)
        val name = cfg.getString("database.name", "sourceforge")!!
        val user = cfg.getString("database.username", "root")!!
        val pass = cfg.getString("database.password", "")!!
        val poolSize = cfg.getInt("database.pool-size", 5).coerceAtLeast(1)

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = "jdbc:mysql://$host:$port/$name?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true"
            username = user
            password = pass
            maximumPoolSize = poolSize
            poolName = "SourceForge-ForgeDB"
        }
        return try {
            val ds = HikariDataSource(hikariConfig)
            dataSource = ds
            createSchema()
            plugin.logger.info("[源质锻炉] MySQL 连接成功 ($host:$port/$name)")
            true
        } catch (ex: Exception) {
            plugin.logger.severe("[源质锻炉] MySQL 连接失败，多方块锻造功能已禁用，请检查 config.yml 的 database 段: ${ex.message}")
            dataSource?.close()
            dataSource = null
            false
        }
    }

    fun close() {
        dataSource?.close()
        dataSource = null
    }

    private fun <T> withConnection(block: (Connection) -> T): T {
        val ds = dataSource ?: error("ForgeDatabase 未连接")
        ds.connection.use { return block(it) }
    }

    private fun createSchema() {
        withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS forge_jobs (
                        world VARCHAR(64) NOT NULL,
                        x INT NOT NULL,
                        y INT NOT NULL,
                        z INT NOT NULL,
                        blueprint_id VARCHAR(191) NOT NULL DEFAULT '',
                        equipment_id VARCHAR(191) NOT NULL DEFAULT '',
                        tier INT NOT NULL DEFAULT 1,
                        shell_tier VARCHAR(32) NOT NULL DEFAULT 'iron',
                        multiplier DOUBLE NOT NULL DEFAULT 1.0,
                        materials_snapshot MEDIUMTEXT,
                        claimed_blocks MEDIUMTEXT,
                        started_at_millis BIGINT NOT NULL DEFAULT 0,
                        total_duration_ticks BIGINT NOT NULL DEFAULT 0,
                        state VARCHAR(16) NOT NULL DEFAULT 'FORGING',
                        mode VARCHAR(16) NOT NULL DEFAULT 'craft',
                        input_item MEDIUMTEXT,
                        enhance_target_level INT NOT NULL DEFAULT 0,
                        output_item MEDIUMTEXT,
                        PRIMARY KEY (world, x, y, z)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
            }
        }
    }

    /**
     * 启动时整表加载一次；量级是"每个存在中的核心一行"，不会大。
     * 跟 connect()/upsert()/delete() 一样兜底异常——查询失败(表结构不对/权限不足/连接瞬断)不该让
     * 异常冒穿 SourceForge.registerListeners()，那样会导致它之后还没注册的监听器全部漏注册。
     */
    fun loadAll(): List<ForgeJob> {
        return try {
            loadAllUnsafe()
        } catch (ex: Exception) {
            plugin.logger.severe("[源质锻炉] 读取作业表失败，多方块锻造功能本次启动按空表处理，请检查 forge_jobs 表结构/权限: ${ex.message}")
            emptyList()
        }
    }

    private fun loadAllUnsafe(): List<ForgeJob> {
        return withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT * FROM forge_jobs").use { rs ->
                    val list = mutableListOf<ForgeJob>()
                    while (rs.next()) {
                        list += ForgeJob(
                            coreWorld = rs.getString("world"),
                            coreX = rs.getInt("x"),
                            coreY = rs.getInt("y"),
                            coreZ = rs.getInt("z"),
                            blueprintId = rs.getString("blueprint_id") ?: "",
                            equipmentId = rs.getString("equipment_id") ?: "",
                            tier = rs.getInt("tier"),
                            shellTier = rs.getString("shell_tier") ?: "iron",
                            multiplier = rs.getDouble("multiplier"),
                            materialsSnapshot = splitField(rs.getString("materials_snapshot")).toMutableList(),
                            claimedBlocks = splitField(rs.getString("claimed_blocks")).mapNotNull { it.toLongOrNull() }.toMutableList(),
                            startedAtMillis = rs.getLong("started_at_millis"),
                            totalDurationTicks = rs.getLong("total_duration_ticks"),
                            state = rs.getString("state") ?: ForgeJob.STATE_FORGING,
                            mode = rs.getString("mode") ?: ForgeJob.MODE_CRAFT,
                            inputItem = rs.getString("input_item"),
                            enhanceTargetLevel = rs.getInt("enhance_target_level"),
                            outputItem = rs.getString("output_item")
                        )
                    }
                    list
                }
            }
        }
    }

    /** 新建或整行覆盖一个作业(提交/完成 都走这个)。 */
    fun upsert(job: ForgeJob) {
        withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO forge_jobs
                    (world, x, y, z, blueprint_id, equipment_id, tier, shell_tier, multiplier,
                     materials_snapshot, claimed_blocks, started_at_millis, total_duration_ticks,
                     state, mode, input_item, enhance_target_level, output_item)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    blueprint_id=VALUES(blueprint_id), equipment_id=VALUES(equipment_id), tier=VALUES(tier),
                    shell_tier=VALUES(shell_tier), multiplier=VALUES(multiplier),
                    materials_snapshot=VALUES(materials_snapshot), claimed_blocks=VALUES(claimed_blocks),
                    started_at_millis=VALUES(started_at_millis), total_duration_ticks=VALUES(total_duration_ticks),
                    state=VALUES(state), mode=VALUES(mode), input_item=VALUES(input_item),
                    enhance_target_level=VALUES(enhance_target_level), output_item=VALUES(output_item)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, job.coreWorld)
                ps.setInt(2, job.coreX)
                ps.setInt(3, job.coreY)
                ps.setInt(4, job.coreZ)
                ps.setString(5, job.blueprintId)
                ps.setString(6, job.equipmentId)
                ps.setInt(7, job.tier)
                ps.setString(8, job.shellTier)
                ps.setDouble(9, job.multiplier)
                ps.setString(10, joinField(job.materialsSnapshot))
                ps.setString(11, joinField(job.claimedBlocks.map { it.toString() }))
                ps.setLong(12, job.startedAtMillis)
                ps.setLong(13, job.totalDurationTicks)
                ps.setString(14, job.state)
                ps.setString(15, job.mode)
                ps.setString(16, job.inputItem)
                ps.setInt(17, job.enhanceTargetLevel)
                ps.setString(18, job.outputItem)
                ps.executeUpdate()
            }
        }
    }

    fun delete(world: String, x: Int, y: Int, z: Int) {
        withConnection { conn ->
            conn.prepareStatement("DELETE FROM forge_jobs WHERE world=? AND x=? AND y=? AND z=?").use { ps ->
                ps.setString(1, world)
                ps.setInt(2, x)
                ps.setInt(3, y)
                ps.setInt(4, z)
                ps.executeUpdate()
            }
        }
    }

    /** materials_snapshot/claimed_blocks 的字段内拼接：用一个 Base64/数字都不可能出现的分隔串。 */
    private fun joinField(items: List<String>): String = items.joinToString(FIELD_SEP)

    private fun splitField(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(FIELD_SEP).filter { it.isNotEmpty() }
    }

    private companion object {
        const val FIELD_SEP = ":SF-SEP:"
    }
}
