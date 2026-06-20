package top.colter.dynamic.repository

import java.io.File
import java.nio.file.Files
import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.core.tools.nowInstant
import top.colter.dynamic.table.DynamicFilterRuleTable
import top.colter.dynamic.table.IncomingMessageAuditTable
import top.colter.dynamic.table.IncomingProcessingAuditTable
import top.colter.dynamic.table.LinkParseTargetConfigTable
import top.colter.dynamic.table.MessageDeliveryTable
import top.colter.dynamic.table.MessageOutboxTable
import top.colter.dynamic.table.MessageSinkReceiptTable
import top.colter.dynamic.table.PublisherDrawThemeTable
import top.colter.dynamic.table.PublisherLiveRecordTable
import top.colter.dynamic.table.PublisherLiveStatusTable
import top.colter.dynamic.table.PublisherTable
import top.colter.dynamic.table.SourceCursorTable
import top.colter.dynamic.table.SourceUpdateSnapshotTable
import top.colter.dynamic.table.SubscriberTable
import top.colter.dynamic.table.SubscriptionTable

private val logger = loggerFor<PersistenceManager>()

public object PersistenceManager {
    private const val DEFAULT_DB_PATH: String = "data/dynamic.db"

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    private var currentPath: String? = null

    public fun currentPath(): String? = currentPath

    public fun init(dbPath: String = DEFAULT_DB_PATH) {
        if (initialized && currentPath == dbPath) return

        synchronized(this) {
            if (initialized && currentPath == dbPath) return

            val databaseFile = File(dbPath)
            try {
                databaseFile.parentFile?.toPath()?.let { Files.createDirectories(it) }
            } catch (e: Exception) {
                throw IllegalStateException("无法创建数据库目录：path=${databaseFile.parentFile?.absolutePath}", e)
            }

            val jdbcUrl = "jdbc:sqlite:${databaseFile.path}"
            Database.connect(url = jdbcUrl, driver = "org.sqlite.JDBC")
            transaction {
                executeRequiredSchemaStatements(SchemaMigrationTable)
                runDatabaseMigrations()
                executeRequiredSchemaStatements(*SCHEMA_TABLES)
            }

            initialized = true
            currentPath = dbPath
        }
    }

    private fun JdbcTransaction.executeRequiredSchemaStatements(vararg tables: Table) {
        val statements = MigrationUtils.statementsRequiredForDatabaseMigration(*tables, withLogs = false)
        statements.forEach { statement ->
            exec(statement)
        }
        if (statements.isNotEmpty()) {
            logger.info { "数据库结构已对齐：statements=${statements.size}" }
        }
    }

    private fun JdbcTransaction.runDatabaseMigrations() {
        val appliedIds = SchemaMigrationTable.selectAll()
            .map { it[SchemaMigrationTable.id] }
            .toSet()

        DATABASE_MIGRATIONS
            .filterNot { it.id in appliedIds }
            .forEach { migration ->
                migration.apply(DatabaseMigrationContext(this))
                SchemaMigrationTable.insert {
                    it[id] = migration.id
                    it[description] = migration.description
                    it[appliedAt] = nowInstant()
                }
                logger.info {
                    "数据库迁移已完成：id=${migration.id}，description=${migration.description.ifBlank { "-" }}"
                }
            }
    }
}

private object SchemaMigrationTable : Table("schema_migration") {
    val id: Column<String> = varchar("id", 160)
    val description: Column<String> = text("description").default("")
    val appliedAt: Column<Instant> = timestamp("applied_at").clientDefault { nowInstant() }

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

private data class DatabaseMigration(
    val id: String,
    val description: String = "",
    val action: DatabaseMigrationContext.() -> Unit,
) {
    init {
        require(id.isNotBlank()) { "数据库迁移 ID 不能为空" }
    }

    fun apply(context: DatabaseMigrationContext) {
        context.action()
    }
}

private class DatabaseMigrationContext(
    private val transaction: JdbcTransaction,
) {
    fun tableExists(tableName: String): Boolean {
        val sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ${quoteLiteral(tableName)}"
        return transaction.exec(sql) { result -> result.next() } ?: false
    }

    fun columnExists(tableName: String, columnName: String): Boolean {
        if (!tableExists(tableName)) return false
        val sql = "PRAGMA table_info(${quoteIdentifier(tableName)})"
        return transaction.exec(sql) { result ->
            var found = false
            while (result.next()) {
                if (result.getString("name").equals(columnName, ignoreCase = true)) {
                    found = true
                    break
                }
            }
            found
        } ?: false
    }

    fun exec(sql: String) {
        transaction.exec(sql)
    }
}

private val SCHEMA_TABLES: Array<Table> = arrayOf(
    PublisherTable,
    PublisherDrawThemeTable,
    PublisherLiveStatusTable,
    PublisherLiveRecordTable,
    SubscriberTable,
    SubscriptionTable,
    SourceCursorTable,
    DynamicFilterRuleTable,
    LinkParseTargetConfigTable,
    SourceUpdateSnapshotTable,
    IncomingMessageAuditTable,
    IncomingProcessingAuditTable,
    MessageOutboxTable,
    MessageDeliveryTable,
    MessageSinkReceiptTable,
)

// 字段改名/数据搬运迁移追加到这里；操作必须幂等，剩余建表/补列/删旧列交给 MigrationUtils 对齐。
private val DATABASE_MIGRATIONS: List<DatabaseMigration> = listOf(
    DatabaseMigration(
        id = "publisher-official-to-avatar-badge-key",
        description = "迁移 publisher.official 到 publisher.avatar_badge_key",
    ) {
        if (tableExists("publisher") && columnExists("publisher", "official")) {
            if (!columnExists("publisher", "avatar_badge_key")) {
                exec(
                    "ALTER TABLE ${quoteIdentifier("publisher")} " +
                        "ADD COLUMN ${quoteIdentifier("avatar_badge_key")} VARCHAR(255) NULL",
                )
            }
            exec(
                """
                UPDATE ${quoteIdentifier("publisher")}
                SET ${quoteIdentifier("avatar_badge_key")} = ${quoteIdentifier("official")}
                WHERE ${quoteIdentifier("avatar_badge_key")} IS NULL
                  AND ${quoteIdentifier("official")} IS NOT NULL
                """.trimIndent(),
            )
        }
    },
    DatabaseMigration(
        id = "publisher-live-status-one-row-per-publisher",
        description = "直播状态按发布者保留单条当前快照",
    ) {
        if (tableExists("publisher_live_status")) {
            exec(
                """
                DELETE FROM ${quoteIdentifier("publisher_live_status")}
                WHERE ${quoteIdentifier("id")} NOT IN (
                    SELECT ${quoteIdentifier("id")}
                    FROM (
                        SELECT
                            ${quoteIdentifier("id")},
                            ROW_NUMBER() OVER (
                                PARTITION BY ${quoteIdentifier("publisher_id")}
                                ORDER BY ${quoteIdentifier("last_observed_at")} DESC, ${quoteIdentifier("id")} DESC
                            ) AS row_num
                        FROM ${quoteIdentifier("publisher_live_status")}
                    )
                    WHERE row_num = 1
                )
                """.trimIndent(),
            )
        }
    },
    DatabaseMigration(
        id = "dynamic-filter-rule-action-and-element-condition",
        description = "动态过滤规则迁移为黑白名单动作和内容元素条件",
    ) {
        if (tableExists("dynamic_filter_rule")) {
            if (!columnExists("dynamic_filter_rule", "action")) {
                exec(
                    "ALTER TABLE ${quoteIdentifier("dynamic_filter_rule")} " +
                        "ADD COLUMN ${quoteIdentifier("action")} VARCHAR(20) NOT NULL DEFAULT 'BLOCK'",
                )
            }
            exec(
                """
                UPDATE ${quoteIdentifier("dynamic_filter_rule")}
                SET ${quoteIdentifier("action")} = 'BLOCK'
                WHERE ${quoteIdentifier("action")} IS NULL
                   OR TRIM(${quoteIdentifier("action")}) = ''
                """.trimIndent(),
            )
            exec(
                """
                UPDATE ${quoteIdentifier("dynamic_filter_rule")}
                SET ${quoteIdentifier("condition_json")} =
                    REPLACE(${quoteIdentifier("condition_json")}, '"HAS_BLOCK_KIND"', '"HAS_ELEMENT"')
                WHERE ${quoteIdentifier("condition_json")} LIKE '%HAS_BLOCK_KIND%'
                """.trimIndent(),
            )
            exec(
                """
                UPDATE ${quoteIdentifier("dynamic_filter_rule")}
                SET ${quoteIdentifier("condition_json")} = '{"type":"HAS_ELEMENT","kind":"REPOST"}'
                WHERE ${quoteIdentifier("condition_json")} LIKE '%HAS_REFERENCE%'
                """.trimIndent(),
            )
        }
    },
    DatabaseMigration(
        id = "dynamic-filter-rule-text-match-condition",
        description = "动态过滤文本条件迁移为统一文本匹配条件",
    ) {
        if (tableExists("dynamic_filter_rule")) {
            exec(
                """
                UPDATE ${quoteIdentifier("dynamic_filter_rule")}
                SET ${quoteIdentifier("condition_json")} =
                    REPLACE(
                        REPLACE(
                            REPLACE(
                                ${quoteIdentifier("condition_json")},
                                '"TEXT_CONTAINS"',
                                '"TEXT_MATCH"'
                            ),
                            '{"type":"TEXT_MATCH",',
                            '{"type":"TEXT_MATCH","mode":"CONTAINS",'
                        ),
                        '"value":',
                        '"text":'
                    )
                WHERE ${quoteIdentifier("condition_json")} LIKE '%TEXT_CONTAINS%'
                """.trimIndent(),
            )
            exec(
                """
                UPDATE ${quoteIdentifier("dynamic_filter_rule")}
                SET ${quoteIdentifier("condition_json")} =
                    REPLACE(
                        REPLACE(
                            REPLACE(
                                ${quoteIdentifier("condition_json")},
                                '"TEXT_REGEX"',
                                '"TEXT_MATCH"'
                            ),
                            '{"type":"TEXT_MATCH",',
                            '{"type":"TEXT_MATCH","mode":"REGEX","ignoreCase":false,'
                        ),
                        '"pattern":',
                        '"text":'
                    )
                WHERE ${quoteIdentifier("condition_json")} LIKE '%TEXT_REGEX%'
                """.trimIndent(),
            )
        }
    },
    DatabaseMigration(
        id = "message-sink-receipt-recallable",
        description = "消息发送回执记录是否支持撤回",
    ) {
        if (tableExists("message_sink_receipt") && !columnExists("message_sink_receipt", "recallable")) {
            exec(
                "ALTER TABLE ${quoteIdentifier("message_sink_receipt")} " +
                    "ADD COLUMN ${quoteIdentifier("recallable")} BOOLEAN NOT NULL DEFAULT 1",
            )
        }
    },
    DatabaseMigration(
        id = "message-delivery-outbound-metadata",
        description = "消息投递记录补充出站分类、可见性和临时保留字段",
    ) {
        if (tableExists("message_delivery")) {
            if (!columnExists("message_delivery", "message_kind")) {
                exec(
                    "ALTER TABLE ${quoteIdentifier("message_delivery")} " +
                        "ADD COLUMN ${quoteIdentifier("message_kind")} VARCHAR(40) NOT NULL DEFAULT 'NORMAL'",
                )
            }
            if (!columnExists("message_delivery", "message_importance")) {
                exec(
                    "ALTER TABLE ${quoteIdentifier("message_delivery")} " +
                        "ADD COLUMN ${quoteIdentifier("message_importance")} VARCHAR(20) NOT NULL DEFAULT 'NORMAL'",
                )
            }
            if (!columnExists("message_delivery", "message_visibility")) {
                exec(
                    "ALTER TABLE ${quoteIdentifier("message_delivery")} " +
                        "ADD COLUMN ${quoteIdentifier("message_visibility")} VARCHAR(20) NOT NULL DEFAULT 'DEFAULT'",
                )
            }
            if (!columnExists("message_delivery", "message_record_policy")) {
                exec(
                    "ALTER TABLE ${quoteIdentifier("message_delivery")} " +
                        "ADD COLUMN ${quoteIdentifier("message_record_policy")} VARCHAR(20) NOT NULL DEFAULT 'DURABLE'",
                )
            }
            if (!columnExists("message_delivery", "transient_expires_at_epoch_seconds")) {
                exec(
                    "ALTER TABLE ${quoteIdentifier("message_delivery")} " +
                        "ADD COLUMN ${quoteIdentifier("transient_expires_at_epoch_seconds")} INTEGER NULL",
                )
            }
            if (tableExists("message_outbox")) {
                exec(
                    """
                    UPDATE ${quoteIdentifier("message_delivery")}
                    SET
                        ${quoteIdentifier("message_kind")} = COALESCE(
                            json_extract((
                                SELECT ${quoteIdentifier("message_json")}
                                FROM ${quoteIdentifier("message_outbox")}
                                WHERE ${quoteIdentifier("message_outbox")}.${quoteIdentifier("message_id")} =
                                    ${quoteIdentifier("message_delivery")}.${quoteIdentifier("message_id")}
                            ), '$.kind'),
                            'NORMAL'
                        ),
                        ${quoteIdentifier("message_importance")} = COALESCE(
                            json_extract((
                                SELECT ${quoteIdentifier("message_json")}
                                FROM ${quoteIdentifier("message_outbox")}
                                WHERE ${quoteIdentifier("message_outbox")}.${quoteIdentifier("message_id")} =
                                    ${quoteIdentifier("message_delivery")}.${quoteIdentifier("message_id")}
                            ), '$.importance'),
                            'NORMAL'
                        ),
                        ${quoteIdentifier("message_visibility")} = COALESCE(
                            json_extract((
                                SELECT ${quoteIdentifier("message_json")}
                                FROM ${quoteIdentifier("message_outbox")}
                                WHERE ${quoteIdentifier("message_outbox")}.${quoteIdentifier("message_id")} =
                                    ${quoteIdentifier("message_delivery")}.${quoteIdentifier("message_id")}
                            ), '$.visibility'),
                            'DEFAULT'
                        ),
                        ${quoteIdentifier("message_record_policy")} = COALESCE(
                            json_extract((
                                SELECT ${quoteIdentifier("message_json")}
                                FROM ${quoteIdentifier("message_outbox")}
                                WHERE ${quoteIdentifier("message_outbox")}.${quoteIdentifier("message_id")} =
                                    ${quoteIdentifier("message_delivery")}.${quoteIdentifier("message_id")}
                            ), '$.recordPolicy.type'),
                            'DURABLE'
                        ),
                        ${quoteIdentifier("transient_expires_at_epoch_seconds")} = CASE
                            WHEN COALESCE(
                                json_extract((
                                    SELECT ${quoteIdentifier("message_json")}
                                    FROM ${quoteIdentifier("message_outbox")}
                                    WHERE ${quoteIdentifier("message_outbox")}.${quoteIdentifier("message_id")} =
                                        ${quoteIdentifier("message_delivery")}.${quoteIdentifier("message_id")}
                                ), '$.recordPolicy.type'),
                                'DURABLE'
                            ) = 'TRANSIENT'
                            THEN COALESCE(json_extract((
                                    SELECT ${quoteIdentifier("message_json")}
                                    FROM ${quoteIdentifier("message_outbox")}
                                    WHERE ${quoteIdentifier("message_outbox")}.${quoteIdentifier("message_id")} =
                                        ${quoteIdentifier("message_delivery")}.${quoteIdentifier("message_id")}
                                ), '$.time'), 0) +
                                COALESCE(json_extract((
                                    SELECT ${quoteIdentifier("message_json")}
                                    FROM ${quoteIdentifier("message_outbox")}
                                    WHERE ${quoteIdentifier("message_outbox")}.${quoteIdentifier("message_id")} =
                                        ${quoteIdentifier("message_delivery")}.${quoteIdentifier("message_id")}
                                ), '$.recordPolicy.retentionSeconds'), 3600)
                            ELSE NULL
                        END
                    WHERE ${quoteIdentifier("message_id")} IN (
                        SELECT ${quoteIdentifier("message_id")} FROM ${quoteIdentifier("message_outbox")}
                    )
                    """.trimIndent(),
                )
            }
        }
    }
)

private fun quoteIdentifier(identifier: String): String {
    require(SQL_IDENTIFIER_REGEX.matches(identifier)) { "非法 SQL 标识符：$identifier" }
    return "\"$identifier\""
}

private fun quoteLiteral(value: String): String {
    return "'${value.replace("'", "''")}'"
}

private val SQL_IDENTIFIER_REGEX: Regex = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
