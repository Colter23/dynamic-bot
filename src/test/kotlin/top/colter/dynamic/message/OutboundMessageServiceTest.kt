package top.colter.dynamic.message

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.repository.PersistenceManager

class OutboundMessageServiceTest {
    @Test
    fun `enqueue text should create outbox message and trigger dispatch callback`() = runBlocking {
        initDb("outbound-text")
        var triggered = 0
        val service = OutboundMessageService(
            onMessagesQueued = { triggered += 1 },
            nowEpochSeconds = { 1L },
            uuid = { "uuid" },
        )

        val result = service.enqueueText(
            source = "web-admin",
            targets = listOf(TargetAddress.of("qq", TargetKind.GROUP, "10001")),
            text = " hello ",
            renderVariant = RENDER_VARIANT_MANUAL_FORWARD,
        )

        assertEquals("manual-forward:web-admin:1:uuid", result.message.id)
        assertEquals(1, result.newDeliveryCount)
        assertEquals(1, triggered)
        val stored = assertNotNull(MessageDeliveryRepository.findMessage(result.message.id))
        assertEquals(RENDER_VARIANT_MANUAL_FORWARD, stored.renderVariant)
        assertEquals("hello", (stored.batches.single().content.single() as MessageContent.Text).fallbackText)
    }

    private fun initDb(suffix: String) {
        val tempDir = createTempDirectory("dynamic-bot-outbound-$suffix").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)
    }
}
