package top.colter.dynamic.repository

import top.colter.dynamic.core.data.DeliveryStatus
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
}
