package top.colter.dynamic.listener

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.DeliveryConfig
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.MessageDeliveryRequest
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.plugin.PluginCapability
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.plugin.PluginHandle
import top.colter.dynamic.plugin.PluginInfo
import top.colter.dynamic.plugin.PluginState
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.repository.PersistenceManager
import top.colter.dynamic.testTargetAddress

class DeliveryDispatcherTest {
    @Test
    fun `pending outbox should be sent after dispatcher is recreated`() = runBlocking {
        initDb("restart-recovery")
        val sink = RecordingSink()
        val target = testTargetAddress(platformId = "onebot", kind = TargetKind.GROUP, externalId = "10001")
        val message = testMessage("message-restart", target)

        MessageDeliveryRepository.enqueue(message)

        val dispatcherAfterRestart = dispatcher(sink)
        val stats = dispatcherAfterRestart.dispatchDue()

        assertEquals(1, stats.claimed)
        assertEquals(1, stats.sent)
        assertEquals(listOf("message-restart"), sink.sentMessageIds)
        val delivery = MessageDeliveryRepository.findByMessageId(message.id).single()
        assertEquals(DeliveryStatus.SENT, delivery.status)
        assertEquals(1, delivery.attempts)
        assertEquals("receipt-message-restart", delivery.sinkMessageId)
    }

    @Test
    fun `duplicate enqueue should not send repeated deliveries`() = runBlocking {
        initDb("duplicate-delivery")
        val sink = RecordingSink()
        val target = testTargetAddress(platformId = "onebot", kind = TargetKind.GROUP, externalId = "10001")
        val message = testMessage("message-duplicate", target)

        assertEquals(1, MessageDeliveryRepository.enqueue(message).newDeliveries.size)
        assertEquals(0, MessageDeliveryRepository.enqueue(message).newDeliveries.size)

        dispatcher(sink).dispatchDue()

        assertEquals(listOf("message-duplicate"), sink.sentMessageIds)
        assertEquals(1, MessageDeliveryRepository.countByStatus(DeliveryStatus.SENT))
    }

    @Test
    fun `ambiguous sink route should fail delivery without sending`() = runBlocking {
        initDb("ambiguous-sink")
        val first = RecordingSink(pluginId = "onebot-a")
        val second = RecordingSink(pluginId = "onebot-b")
        val target = testTargetAddress(platformId = "onebot", kind = TargetKind.GROUP, externalId = "10001")
        val message = testMessage("message-ambiguous", target)
        MessageDeliveryRepository.enqueue(message)

        val stats = dispatcher(first, second).dispatchDue()

        assertEquals(1, stats.failed)
        assertEquals(emptyList(), first.sentMessageIds)
        assertEquals(emptyList(), second.sentMessageIds)
        val delivery = MessageDeliveryRepository.findByMessageId(message.id).single()
        assertEquals(DeliveryStatus.FAILED, delivery.status)
        assertNotNull(delivery.lastError)
    }

    private fun dispatcher(vararg sinks: RecordingSink): DeliveryDispatcher {
        return DeliveryDispatcher(
            sinkProvider = { sinks.map { it.handle() } },
            configProvider = { DeliveryConfig(maxAttempts = 2, retryDelayMs = 1, dispatchConcurrency = 2, lockTtlMs = 10_000) },
        )
    }

    private fun testMessage(id: String, target: top.colter.dynamic.core.data.TargetAddress): Message {
        return Message(
            id = id,
            time = 1L,
            targets = listOf(target),
            batches = listOf(MessageBatch(listOf(MessageContent.Text("hello")))),
        )
    }

    private fun initDb(suffix: String) {
        val tempDir = createTempDirectory("dynamic-bot-delivery-dispatcher-$suffix").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)
    }

    private class RecordingSink(
        private val pluginId: String = "onebot",
        private val result: (MessageDeliveryRequest) -> MessageSendResult = {
            MessageSendResult.sent("receipt-${it.message.id}")
        },
    ) : MessageSinkPlugin {
        override val platformId: PlatformId = PlatformId.of("onebot")
        override val supportedTargetKinds: Set<TargetKind> = setOf(TargetKind.GROUP)

        val sentMessageIds: MutableList<String> = mutableListOf()

        override suspend fun sendMessage(request: MessageDeliveryRequest): MessageSendResult {
            sentMessageIds += request.message.id
            return result(request)
        }

        fun handle(): PluginHandle<MessageSinkPlugin> = PluginHandle(
            info = PluginInfo(
                descriptor = PluginDescriptor(
                    id = pluginId,
                    name = pluginId,
                    version = "test",
                    mainClass = RecordingSink::class.qualifiedName.orEmpty(),
                ),
                capabilities = setOf(PluginCapability.MESSAGE_SINK),
                state = PluginState.ACTIVE,
                sourceJarPath = "test.jar",
            ),
            instance = this,
        )
    }
}
