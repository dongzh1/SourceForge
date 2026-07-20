package com.dongzh1.sourceforge.sect

import com.dongzh1.sourceforge.SourceForge
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Types
import java.util.UUID

data class StoredSectState(
    val selectedSect: String?,
    val journeyPrepared: Boolean,
    val wineUntilMillis: Long
)

/** 教派玩家状态 MySQL 存储；连接参数复用 SourceForge 的 database 配置段。 */
class SectDatabase(private val plugin: SourceForge) {
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
        return try {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:mysql://$host:$port/$name?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true"
                username = user
                password = pass
                maximumPoolSize = poolSize
                poolName = "SourceForge-SectDB"
            })
            withConnection { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS sourceforge_sect_players (
                            player_uuid CHAR(36) NOT NULL,
                            selected_sect VARCHAR(64) NULL,
                            journey_prepared BOOLEAN NOT NULL DEFAULT FALSE,
                            wine_until_millis BIGINT NOT NULL DEFAULT 0,
                            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                            PRIMARY KEY (player_uuid)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """.trimIndent()
                    )
                }
            }
            plugin.logger.info("[教派] MySQL 同步已启用 ($host:$port/$name)")
            true
        } catch (exception: Exception) {
            plugin.logger.warning("[教派] MySQL 连接失败，暂使用本地 PDC: ${exception.message}")
            close()
            false
        }
    }

    fun close() {
        dataSource?.close()
        dataSource = null
    }

    fun load(uuid: UUID): StoredSectState? = runCatching {
        withConnection { connection ->
            connection.prepareStatement(
                "SELECT selected_sect, journey_prepared, wine_until_millis FROM sourceforge_sect_players WHERE player_uuid=?"
            ).use { statement ->
                statement.setString(1, uuid.toString())
                statement.executeQuery().use { result ->
                    if (!result.next()) return@withConnection null
                    StoredSectState(
                        selectedSect = result.getString("selected_sect"),
                        journeyPrepared = result.getBoolean("journey_prepared"),
                        wineUntilMillis = result.getLong("wine_until_millis")
                    )
                }
            }
        }
    }.getOrElse {
        plugin.logger.warning("[教派] 读取玩家状态失败 ($uuid): ${it.message}")
        null
    }

    fun save(uuid: UUID, state: StoredSectState) {
        withConnection { connection ->
            connection.prepareStatement(
                """
                INSERT INTO sourceforge_sect_players
                    (player_uuid, selected_sect, journey_prepared, wine_until_millis)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    selected_sect=VALUES(selected_sect),
                    journey_prepared=VALUES(journey_prepared),
                    wine_until_millis=VALUES(wine_until_millis)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, uuid.toString())
                if (state.selectedSect == null) statement.setNull(2, Types.VARCHAR)
                else statement.setString(2, state.selectedSect)
                statement.setBoolean(3, state.journeyPrepared)
                statement.setLong(4, state.wineUntilMillis)
                statement.executeUpdate()
            }
        }
    }

    private fun <T> withConnection(block: (java.sql.Connection) -> T): T {
        val source = dataSource ?: error("SectDatabase 未连接")
        source.connection.use { return block(it) }
    }
}
