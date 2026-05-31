package top.colter.dynamic.repository

import java.io.File
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import top.colter.dynamic.table.DynamicFilterRuleTable
import top.colter.dynamic.table.MessageDeliveryTable
import top.colter.dynamic.table.MessageOutboxTable
import top.colter.dynamic.table.PublisherDrawThemeTable
import top.colter.dynamic.table.PublisherLiveStatusTable
import top.colter.dynamic.table.PublisherTable
import top.colter.dynamic.table.SubscriberTable
import top.colter.dynamic.table.SourceCursorTable
import top.colter.dynamic.table.SubscriptionTable

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
                SchemaUtils.createMissingTablesAndColumns(
                    PublisherTable,
                    PublisherDrawThemeTable,
                    PublisherLiveStatusTable,
                    SubscriberTable,
                    SubscriptionTable,
                    SourceCursorTable,
                    DynamicFilterRuleTable,
                    MessageOutboxTable,
                    MessageDeliveryTable,
                )
            }

            initialized = true
            currentPath = dbPath
        }
    }
}
