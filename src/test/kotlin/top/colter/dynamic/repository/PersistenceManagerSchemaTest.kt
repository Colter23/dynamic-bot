package top.colter.dynamic.repository

import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.DeliveryStatus

class PersistenceManagerSchemaTest {
    @Test
    fun initShouldCreateFreshSchemaTables() {
        val tempDir = createTempDirectory("dynamic-bot-core-schema-db").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)

        assertTrue(PublisherRepository.findAll().isEmpty())
        assertTrue(SubscriptionRepository.findAll().isEmpty())
        assertTrue(DynamicFilterRuleRepository.findAll().isEmpty())
        assertTrue(PublisherLiveStatusRepository.findAll().isEmpty())
        assertTrue(PublisherLiveRecordRepository.findAll().isEmpty())
        assertEquals(0, MessageDeliveryRepository.countByStatus(DeliveryStatus.PENDING))
    }

    @Test
    fun initShouldMigrateLegacyDynamicFilterRules() {
        val tempDir = createTempDirectory("dynamic-bot-core-schema-migration-db").toFile()
        val dbPath = tempDir.resolve("test.db").path
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE dynamic_filter_rule (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        subscription_id INTEGER NOT NULL,
                        condition_json TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO dynamic_filter_rule (subscription_id, condition_json, created_at)
                    VALUES (1, '{"type":"HAS_BLOCK_KIND","kind":"VIDEO"}', '2026-01-01T00:00:00Z')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO dynamic_filter_rule (subscription_id, condition_json, created_at)
                    VALUES (1, '{"type":"HAS_REFERENCE","kind":"ORIGIN"}', '2026-01-01T00:00:00Z')
                    """.trimIndent(),
                )
            }
        }

        PersistenceManager.init(dbPath)

        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT action, condition_json FROM dynamic_filter_rule ORDER BY id",
                ).use { result ->
                    assertTrue(result.next())
                    assertEquals("BLOCK", result.getString("action"))
                    assertEquals("""{"type":"HAS_ELEMENT","kind":"VIDEO"}""", result.getString("condition_json"))
                    assertTrue(result.next())
                    assertEquals("BLOCK", result.getString("action"))
                    assertEquals("""{"type":"HAS_ELEMENT","kind":"REPOST"}""", result.getString("condition_json"))
                }
            }
        }
    }
}
