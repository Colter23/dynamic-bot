package top.colter.dynamic.repository

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.data.UpdateKey
import top.colter.dynamic.core.tools.nowInstant
import top.colter.dynamic.table.updateKeyColumn
import top.colter.dynamic.table.mediaRefColumn

class PersistenceManagerSchemaTest {
    @Test
    fun initShouldCreateFreshSchemaTables() {
        val tempDir = createTempDirectory("dynamic-bot-core-schema-db").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)

        assertTrue(PublisherRepository.findAll().isEmpty())
        assertTrue(SubscriptionRepository.findAll().isEmpty())
        assertTrue(DynamicFilterRuleRepository.findAll().isEmpty())
        assertTrue(PublisherLiveStatusRepository.findAll().isEmpty())
        assertEquals(0, MessageDeliveryRepository.countByStatus(DeliveryStatus.PENDING))
    }

    @Test
    fun initShouldMigrateLegacyPublisherOfficialColumn() {
        val tempDir = createTempDirectory("dynamic-bot-core-schema-legacy-db").toFile()
        val dbPath = tempDir.resolve("test.db").path

        Database.connect(url = "jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
        transaction {
            MigrationUtils.statementsRequiredForDatabaseMigration(LegacyPublisherTable, withLogs = false)
                .forEach { exec(it) }
            LegacyPublisherTable.insert {
                it[id] = 1
                it[platformId] = "bilibili"
                it[kind] = PublisherKind.USER
                it[externalId] = "123456"
                it[name] = "legacy-up"
                it[official] = "avatarBadge.official.individual"
                it[state] = EntityState.ACTIVE
                it[avatar] = MediaRef("https://example.com/face.png", MediaKind.AVATAR)
                it[createTime] = nowInstant()
                it[createUser] = 0
            }
        }

        PersistenceManager.init(dbPath)

        val publisher = PublisherRepository.findAll().single()
        assertEquals("avatarBadge.official.individual", publisher.avatarBadgeKey)
    }

    @Test
    fun initShouldMigrateLegacyMessageDeliverySinkAccountColumn() {
        val tempDir = createTempDirectory("dynamic-bot-core-schema-message-delivery-db").toFile()
        val dbPath = tempDir.resolve("test.db").path

        Database.connect(url = "jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
        transaction {
            MigrationUtils.statementsRequiredForDatabaseMigration(
                LegacyMessageDeliveryWithoutSinkAccountTable,
                withLogs = false,
            ).forEach { exec(it) }
            LegacyMessageDeliveryWithoutSinkAccountTable.insert {
                it[id] = 1
                it[messageId] = "message-legacy"
                it[sourceUpdateKey] = null
                it[renderVariant] = null
                it[platformId] = "onebot"
                it[targetKind] = TargetKind.GROUP
                it[targetId] = "100"
                it[targetKey] = "onebot\u001FGROUP\u001F100\u001F\u001F\u001F"
                it[scopeId] = null
                it[threadId] = null
                it[accountId] = "42"
                it[status] = DeliveryStatus.SENT
                it[attempts] = 1
                it[sinkMessageId] = "receipt-1"
                it[lastError] = null
                it[nextAttemptAt] = null
                it[lockedUntil] = null
                it[createdAt] = nowInstant()
                it[updatedAt] = nowInstant()
            }
        }

        PersistenceManager.init(dbPath)

        assertTrue(columnExists("message_delivery", "sink_account_id"))
        val delivery = MessageDeliveryRepository.findByMessageId("message-legacy").single()
        assertEquals(null, delivery.sinkAccountId)
    }
}

private object LegacyPublisherTable : IntIdTable("publisher") {
    val platformId: Column<String> = varchar("platform_id", 50)
    val kind: Column<PublisherKind> = enumerationByName<PublisherKind>("kind", 30).default(PublisherKind.USER)
    val externalId: Column<String> = varchar(name = "external_id", length = 100)
    val name: Column<String> = varchar(name = "name", length = 255)
    val official: Column<String?> = varchar(name = "official", length = 255).nullable()
    val state: Column<EntityState> = enumerationByName<EntityState>("state", 20).default(EntityState.ACTIVE)
    val avatar: Column<MediaRef> = registerColumn("avatar", mediaRefColumn())
    val banner: Column<MediaRef?> = registerColumn("banner", mediaRefColumn()).nullable()
    val pendant: Column<MediaRef?> = registerColumn("pendant", mediaRefColumn()).nullable()
    val createTime = timestamp(name = "create_time").clientDefault { nowInstant() }
    val createUser: Column<Int> = integer(name = "create_user")

    init {
        uniqueIndex(platformId, kind, externalId)
    }
}

private object LegacyMessageDeliveryWithoutSinkAccountTable : IntIdTable("message_delivery") {
    val messageId: Column<String> = varchar(name = "message_id", length = 255)
    val sourceUpdateKey: Column<UpdateKey?> = registerColumn("source_update_key_json", updateKeyColumn()).nullable()
    val renderVariant: Column<String?> = varchar(name = "render_variant", length = 80).nullable()
    val platformId: Column<String> = varchar(name = "platform_id", length = 50)
    val targetKind: Column<TargetKind> = enumerationByName<TargetKind>("target_kind", 30)
    val targetId: Column<String> = varchar(name = "target_id", length = 120)
    val targetKey: Column<String> = text(name = "target_key")
    val scopeId: Column<String?> = varchar(name = "scope_id", length = 120).nullable()
    val threadId: Column<String?> = varchar(name = "thread_id", length = 120).nullable()
    val accountId: Column<String?> = varchar(name = "account_id", length = 120).nullable()
    val status: Column<DeliveryStatus> = enumerationByName<DeliveryStatus>("status", 20).default(DeliveryStatus.PENDING)
    val attempts: Column<Int> = integer(name = "attempts").default(0)
    val sinkMessageId: Column<String?> = varchar(name = "sink_message_id", length = 255).nullable()
    val lastError: Column<String?> = varchar(name = "last_error", length = 500).nullable()
    val nextAttemptAt: Column<kotlin.time.Instant?> = timestamp(name = "next_attempt_at").nullable()
    val lockedUntil: Column<kotlin.time.Instant?> = timestamp(name = "locked_until").nullable()
    val createdAt: Column<kotlin.time.Instant> = timestamp(name = "created_at").clientDefault { nowInstant() }
    val updatedAt: Column<kotlin.time.Instant> = timestamp(name = "updated_at").clientDefault { nowInstant() }

    init {
        uniqueIndex(messageId, targetKey)
    }
}

private fun columnExists(tableName: String, columnName: String): Boolean {
    return transaction {
        exec("PRAGMA table_info(\"$tableName\")") { result ->
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
}
