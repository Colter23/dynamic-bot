package top.colter.dynamic.repository

import java.io.File
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
import top.colter.dynamic.table.LinkParseTargetConfigTable
import top.colter.dynamic.table.MessageDeliveryTable
import top.colter.dynamic.table.MessageOutboxTable
import top.colter.dynamic.table.PublisherDrawThemeTable
import top.colter.dynamic.table.PublisherLiveStatusTable
import top.colter.dynamic.table.PublisherTable
import top.colter.dynamic.table.SubscriberTable
import top.colter.dynamic.table.SourceCursorTable
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
            databaseFile.parentFile?.mkdirs()

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

    private fun JdbcTransaction.executeRequiredSchemaStatements(vararg tables: Table) {
        val statements = MigrationUtils.statementsRequiredForDatabaseMigration(*tables, withLogs = false)
        statements.forEach { statement ->
            exec(statement)
        }
        if (statements.isNotEmpty()) {
            logger.info { "数据库结构已对齐：statements=${statements.size}" }
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
    SubscriberTable,
    SubscriptionTable,
    SourceCursorTable,
    DynamicFilterRuleTable,
    LinkParseTargetConfigTable,
    MessageOutboxTable,
    MessageDeliveryTable,
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
)

private fun quoteIdentifier(identifier: String): String {
    require(SQL_IDENTIFIER_REGEX.matches(identifier)) { "非法 SQL 标识符：$identifier" }
    return "\"$identifier\""
}

private fun quoteLiteral(value: String): String {
    return "'${value.replace("'", "''")}'"
}

private val SQL_IDENTIFIER_REGEX: Regex = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
