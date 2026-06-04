package top.colter.dynamic.listener

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.DeliveryConfig
import top.colter.dynamic.MessageRoutingConfig
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.AccountRoutedMessageSinkPlugin
import top.colter.dynamic.core.plugin.MessageDeliveryRequest
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.core.plugin.MessageSinkRoute
import top.colter.dynamic.core.plugin.MessageSinkRoutingPolicy
import top.colter.dynamic.core.plugin.MessageSinkRoutingStrategy
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.plugin.PluginCapability
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
        val target = testTargetAddress(platformId = "qq", kind = TargetKind.GROUP, externalId = "10001")
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
        val target = testTargetAddress(platformId = "qq", kind = TargetKind.GROUP, externalId = "10001")
        val message = testMessage("message-duplicate", target)

        assertEquals(1, MessageDeliveryRepository.enqueue(message).newDeliveries.size)
        assertEquals(0, MessageDeliveryRepository.enqueue(message).newDeliveries.size)

        dispatcher(sink).dispatchDue()

        assertEquals(listOf("message-duplicate"), sink.sentMessageIds)
        assertEquals(1, MessageDeliveryRepository.countByStatus(DeliveryStatus.SENT))
    }

    @Test
    fun `ambiguous direct sink should fail delivery without sending`() = runBlocking {
        initDb("ambiguous-sink")
        val first = RecordingSink()
        val second = RecordingSink()
        val target = testTargetAddress(platformId = "qq", kind = TargetKind.GROUP, externalId = "10001")
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

    @Test
    fun `routed sink should round robin routes and persist actual route`() = runBlocking {
        initDb("round-robin-routes")
        val sink = RoutedRecordingSink(accountIds = listOf("bot-a", "bot-b"))
        val target = testTargetAddress(platformId = "qq", kind = TargetKind.GROUP, externalId = "10001")
        MessageDeliveryRepository.enqueue(testMessage("message-rr-1", target))
        MessageDeliveryRepository.enqueue(testMessage("message-rr-2", target))

        val stats = dispatcher(
            sink,
            config = defaultDeliveryConfig().copy(dispatchConcurrency = 1),
        ).dispatchDue()

        assertEquals(2, stats.sent)
        assertEquals(listOf("bot-a:message-rr-1", "bot-b:message-rr-2"), sink.sent)
        val first = MessageDeliveryRepository.findByMessageId("message-rr-1").single()
        val second = MessageDeliveryRepository.findByMessageId("message-rr-2").single()
        assertEquals("bot-a", first.sinkAccountId)
        assertEquals("onebot:qq:bot-a", first.sinkRouteId)
        assertEquals("bot-b", second.sinkAccountId)
        assertEquals("onebot:qq:bot-b", second.sinkRouteId)
    }

    @Test
    fun `routed sink should treat target account as preferred and fail over`() = runBlocking {
        initDb("preferred-account-failover")
        val target = testTargetAddress(
            platformId = "qq",
            kind = TargetKind.GROUP,
            externalId = "10001",
            accountId = "bot-a",
        )
        val sink = RoutedRecordingSink(
            accountIds = listOf("bot-a", "bot-b"),
            result = { accountId, request ->
                if (accountId == "bot-a") {
                    MessageSendResult.failed("主账号离线", retryable = true)
                } else {
                    MessageSendResult.sent("receipt-${request.message.id}")
                }
            },
        )
        val message = testMessage("message-preferred", target)
        MessageDeliveryRepository.enqueue(message)

        val stats = dispatcher(
            sink,
            routing = primaryBackupRouting(),
        ).dispatchDue()

        assertEquals(1, stats.sent)
        assertEquals(listOf("bot-a:message-preferred", "bot-b:message-preferred"), sink.sent)
        val delivery = MessageDeliveryRepository.findByMessageId(message.id).single()
        assertEquals(DeliveryStatus.SENT, delivery.status)
        assertEquals("bot-b", delivery.sinkAccountId)
    }

    @Test
    fun `routed sink should cooldown failed primary route`() = runBlocking {
        initDb("route-cooldown")
        val sink = RoutedRecordingSink(
            accountIds = listOf("bot-a", "bot-b"),
            result = { accountId, request ->
                if (accountId == "bot-a") {
                    MessageSendResult.failed("主账号离线", retryable = true)
                } else {
                    MessageSendResult.sent("receipt-${request.message.id}")
                }
            },
        )
        val target = testTargetAddress(platformId = "qq", kind = TargetKind.GROUP, externalId = "10001")
        val dispatcher = dispatcher(sink, routing = primaryBackupRouting())
        MessageDeliveryRepository.enqueue(testMessage("message-cooldown-1", target))
        dispatcher.dispatchDue()

        MessageDeliveryRepository.enqueue(testMessage("message-cooldown-2", target))
        dispatcher.dispatchDue()

        assertEquals(
            listOf("bot-a:message-cooldown-1", "bot-b:message-cooldown-1", "bot-b:message-cooldown-2"),
            sink.sent,
        )
    }

    @Test
    fun `routed sink should not retry or switch route after partial send`() = runBlocking {
        initDb("partial-send")
        val sink = RoutedRecordingSink(
            accountIds = listOf("bot-a", "bot-b"),
            result = { _, _ -> MessageSendResult.failed("第二段消息失败", retryable = true, partialSent = true) },
        )
        val target = testTargetAddress(platformId = "qq", kind = TargetKind.GROUP, externalId = "10001")
        val message = testMessage("message-partial", target)
        MessageDeliveryRepository.enqueue(message)

        val stats = dispatcher(sink).dispatchDue()

        assertEquals(1, stats.failed)
        assertEquals(listOf("bot-a:message-partial"), sink.sent)
        val delivery = MessageDeliveryRepository.findByMessageId(message.id).single()
        assertEquals(DeliveryStatus.FAILED, delivery.status)
        assertEquals(1, delivery.attempts)
    }

    private fun dispatcher(
        vararg sinks: MessageSinkPlugin,
        config: DeliveryConfig = defaultDeliveryConfig(),
        routing: MessageRoutingConfig = MessageRoutingConfig(),
    ): DeliveryDispatcher {
        return DeliveryDispatcher(
            sinkProvider = { sinks.mapIndexed { index, sink -> sink.handleForTest(index) } },
            configProvider = { config },
            routingConfigProvider = { routing },
        )
    }

    private fun defaultDeliveryConfig(): DeliveryConfig = DeliveryConfig(
        maxAttempts = 2,
        retryDelaySeconds = 0.001,
        dispatchConcurrency = 2,
        lockTtlSeconds = 10.0,
    )

    private fun primaryBackupRouting(): MessageRoutingConfig = MessageRoutingConfig(
        defaultPolicy = MessageSinkRoutingPolicy(
            strategy = MessageSinkRoutingStrategy.PRIMARY_BACKUP,
            primaryAccountId = "bot-a",
            failureCooldownSeconds = 60,
        ),
    )

    private fun testMessage(id: String, target: TargetAddress): Message {
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
        private val result: (MessageDeliveryRequest) -> MessageSendResult = {
            MessageSendResult.sent("receipt-${it.message.id}")
        },
    ) : MessageSinkPlugin {
        override val transportId: String = "direct"
        override val supportedTargetPlatforms: Set<PlatformId> = setOf(PlatformId.of("qq"))
        override val supportedTargetKinds: Set<TargetKind> = setOf(TargetKind.GROUP)

        val sentMessageIds: MutableList<String> = mutableListOf()

        override suspend fun sendMessage(request: MessageDeliveryRequest): MessageSendResult {
            sentMessageIds += request.message.id
            return result(request)
        }
    }

    private class RoutedRecordingSink(
        private val accountIds: List<String>,
        private val result: (String, MessageDeliveryRequest) -> MessageSendResult = { accountId, request ->
            MessageSendResult.sent("receipt-${request.message.id}", sinkAccountId = accountId)
        },
    ) : AccountRoutedMessageSinkPlugin {
        override val transportId: String = "onebot"
        override val supportedTargetPlatforms: Set<PlatformId> = setOf(PlatformId.of("qq"))
        override val supportedTargetKinds: Set<TargetKind> = setOf(TargetKind.GROUP)

        val sent: MutableList<String> = mutableListOf()

        override suspend fun listMessageSinkRoutes(target: TargetAddress?): List<MessageSinkRoute> {
            return accountIds.map { accountId ->
                MessageSinkRoute(
                    routeId = "onebot:qq:$accountId",
                    transportId = transportId,
                    targetPlatformId = PlatformId.of("qq"),
                    accountId = accountId,
                    accountName = accountId,
                )
            }
        }

        override suspend fun sendMessage(
            request: MessageDeliveryRequest,
            routeId: String,
        ): MessageSendResult {
            val accountId = routeId.substringAfterLast(":")
            sent += "$accountId:${request.message.id}"
            return result(accountId, request)
        }
    }
}

private fun MessageSinkPlugin.handleForTest(index: Int): PluginHandle<MessageSinkPlugin> {
    val pluginId = "$transportId-$index"
    return PluginHandle(
        info = PluginInfo(
            descriptor = PluginDescriptor(
                id = pluginId,
                name = pluginId,
                version = "test",
                mainClass = this::class.qualifiedName.orEmpty(),
            ),
            capabilities = setOf(PluginCapability.MESSAGE_SINK),
            state = PluginState.ACTIVE,
            sourceJarPath = "test.jar",
        ),
        instance = this,
    )
}
