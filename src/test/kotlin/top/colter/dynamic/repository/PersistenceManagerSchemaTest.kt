package top.colter.dynamic.repository

import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
                statement.executeUpdate(
                    """
                    INSERT INTO dynamic_filter_rule (subscription_id, condition_json, created_at)
                    VALUES (1, '{"type":"TEXT_CONTAINS","value":"抽奖","ignoreCase":true}', '2026-01-01T00:00:00Z')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO dynamic_filter_rule (subscription_id, condition_json, created_at)
                    VALUES (1, '{"type":"TEXT_REGEX","pattern":"foo\\s+bar"}', '2026-01-01T00:00:00Z')
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
                    assertTrue(result.next())
                    assertEquals("BLOCK", result.getString("action"))
                    assertEquals(
                        """{"type":"TEXT_MATCH","mode":"CONTAINS","text":"抽奖","ignoreCase":true}""",
                        result.getString("condition_json"),
                    )
                    assertTrue(result.next())
                    assertEquals("BLOCK", result.getString("action"))
                    assertEquals(
                        """{"type":"TEXT_MATCH","mode":"REGEX","ignoreCase":false,"text":"foo\\s+bar"}""",
                        result.getString("condition_json"),
                    )
                }
            }
        }
    }

    @Test
    fun initShouldBackfillMessageDeliveryOutboundMetadata() {
        val tempDir = createTempDirectory("dynamic-bot-delivery-metadata-migration-db").toFile()
        val dbPath = tempDir.resolve("test.db").path
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE message_outbox (
                        message_id VARCHAR(255) PRIMARY KEY,
                        message_json TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE message_delivery (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        message_id VARCHAR(255) NOT NULL,
                        source_update_key_json TEXT NULL,
                        render_variant VARCHAR(80) NULL,
                        platform_id VARCHAR(50) NOT NULL,
                        target_kind VARCHAR(30) NOT NULL,
                        target_id VARCHAR(120) NOT NULL,
                        target_key TEXT NOT NULL,
                        scope_id VARCHAR(120) NULL,
                        thread_id VARCHAR(120) NULL,
                        account_id VARCHAR(120) NULL,
                        status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                        attempts INTEGER NOT NULL DEFAULT 0,
                        sink_message_id VARCHAR(255) NULL,
                        sink_route_id VARCHAR(160) NULL,
                        sink_account_id VARCHAR(120) NULL,
                        last_error VARCHAR(500) NULL,
                        next_attempt_at TEXT NULL,
                        locked_until TEXT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO message_outbox (message_id, message_json, created_at, updated_at)
                    VALUES (
                        'legacy-transient',
                        '{"id":"legacy-transient","time":100,"kind":"PROGRESS","importance":"LOW","visibility":"INTERNAL","recordPolicy":{"type":"TRANSIENT","retentionSeconds":30},"targets":[{"platformId":"qq","kind":"GROUP","externalId":"10001","scopeId":null,"threadId":null,"accountId":null}],"batches":[{"content":[{"type":"TEXT","fallbackText":"hello"}]}]}',
                        '2026-01-01 00:00:00.000',
                        '2026-01-01 00:00:00.000'
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO message_delivery (
                        message_id, render_variant, platform_id, target_kind, target_id, target_key,
                        status, attempts, created_at, updated_at
                    ) VALUES (
                        'legacy-transient', 'default', 'qq', 'GROUP', '10001', 'qqGROUP10001',
                        'SENT', 1, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000'
                    )
                    """.trimIndent(),
                )
            }
        }

        PersistenceManager.init(dbPath)

        val delivery = assertNotNull(MessageDeliveryRepository.findByMessageId("legacy-transient").singleOrNull())
        assertEquals("PROGRESS", delivery.messageKind.name)
        assertEquals("LOW", delivery.messageImportance.name)
        assertEquals("INTERNAL", delivery.messageVisibility.name)
        assertEquals("TRANSIENT", delivery.messageRecordPolicyType.name)
        assertEquals(130L, delivery.transientExpiresAtEpochSeconds)
    }
}
