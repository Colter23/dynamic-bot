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
import top.colter.dynamic.core.tools.nowInstant
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
