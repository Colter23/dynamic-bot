package top.colter.dynamic.listener

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.DeliveryConfig
import top.colter.dynamic.ImageCacheConfig
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.MediaDeliveryBase64FallbackConfig
import top.colter.dynamic.MediaDeliveryConfig
import top.colter.dynamic.MediaDeliveryProfile
import top.colter.dynamic.MediaDeliveryType
import top.colter.dynamic.MessageRoutingConfig
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.ForwardNode
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.AccountRoutedMessageSinkPlugin
import top.colter.dynamic.core.plugin.MessageDeliveryRequest
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.MessageSinkFeature
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.core.plugin.MessageSinkRoute
import top.colter.dynamic.core.plugin.MessageSinkRoutingPolicy
import top.colter.dynamic.core.plugin.MessageSinkRoutingStrategy
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.media.OutboundMediaService
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
            routing = roundRobinRouting(),
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

    @Test
    fun `routed sink should rewrite media for each selected route profile`() = runBlocking {
        initDb("route-media-profile")
        val renderedRoot = createTempDirectory("delivery-route-media-profile")
        val image = renderedRoot.resolve("demo.png")
        image.writeBytes(byteArrayOf(1, 2, 3))
        val target = testTargetAddress(
            platformId = "qq",
            kind = TargetKind.GROUP,
            externalId = "10001",
            accountId = "bot-a",
        )
        val sink = RoutedRecordingSink(
            accountIds = listOf("bot-a", "bot-b"),
            routeProfileIds = mapOf("bot-a" to "base64", "bot-b" to "local"),
            result = { accountId, request ->
                if (accountId == "bot-a") {
                    MessageSendResult.failed("主账号离线", retryable = true)
                } else {
                    MessageSendResult.sent("receipt-${request.message.id}")
                }
            },
        )
        MessageDeliveryRepository.enqueue(imageMessage("message-route-media", target, image.toString()))

        val stats = dispatcher(
            sink,
            routing = primaryBackupRouting(),
            outboundMediaService = OutboundMediaService(
                configProvider = {
                    MainDynamicConfig(
                        imageCache = ImageCacheConfig(renderedRoot = renderedRoot.toString()),
                        mediaDelivery = MediaDeliveryConfig(
                            defaultProfileId = "local",
                            profiles = listOf(
                                MediaDeliveryProfile(
                                    id = "base64",
                                    type = MediaDeliveryType.BASE64,
                                    base64Fallback = MediaDeliveryBase64FallbackConfig(maxMegabytes = 0.001),
                                ),
                                MediaDeliveryProfile(
                                    id = "local",
                                    type = MediaDeliveryType.LOCAL_FILE,
                                ),
                            ),
                        ),
                    )
                },
            ),
        ).dispatchDue()

        assertEquals(1, stats.sent)
        assertEquals(listOf("bot-a:message-route-media", "bot-b:message-route-media"), sink.sent)
        assertEquals("base64://AQID", sink.sentMessages[0].firstImageUri())
        assertEquals(image.toUri().toString(), sink.sentMessages[1].firstImageUri())
    }

    @Test
    fun `direct sink without merged forward support should receive fallback batches`() = runBlocking {
        initDb("direct-forward-fallback")
        val sink = RecordingSink()
        val target = testTargetAddress(platformId = "qq", kind = TargetKind.GROUP, externalId = "10001")
        val message = mergedForwardMessage("message-forward-fallback", target)
        MessageDeliveryRepository.enqueue(message)

        val stats = dispatcher(sink).dispatchDue()

        assertEquals(1, stats.sent)
        val sent = sink.sentMessages.single()
        assertEquals(3, sent.batches.size)
        assertEquals("before", sent.batches[0].content.single().fallbackText)
        assertEquals("Demo UP:\n", sent.batches[1].content[0].fallbackText)
        assertEquals("node text", sent.batches[1].content[1].fallbackText)
        assertEquals("after", sent.batches[2].content.single().fallbackText)
        assertEquals(false, sent.batches.any { batch -> batch.content.any { it is MessageContent.Forward } })
    }

    @Test
    fun `routed sink with merged forward support should keep forward content`() = runBlocking {
        initDb("routed-forward-supported")
        val sink = RoutedRecordingSink(
            accountIds = listOf("bot-a"),
            features = setOf(MessageSinkFeature.MERGED_FORWARD),
        )
        val target = testTargetAddress(platformId = "qq", kind = TargetKind.GROUP, externalId = "10001")
        val message = mergedForwardMessage("message-forward-supported", target)
        MessageDeliveryRepository.enqueue(message)

        val stats = dispatcher(sink).dispatchDue()

        assertEquals(1, stats.sent)
        val sent = sink.sentMessages.single()
        assertEquals(true, sent.batches.single().content.any { it is MessageContent.Forward })
    }

    @Test
    fun `overlapping dispatch calls should still respect configured concurrency`() = runBlocking {
        initDb("overlapping-dispatch")
        val sink = DelayingSink(delayMillis = 50)
        val target = testTargetAddress(platformId = "qq", kind = TargetKind.GROUP, externalId = "10001")
        repeat(16) { index ->
            MessageDeliveryRepository.enqueue(testMessage("message-overlap-$index", target))
        }
        val dispatcher = dispatcher(
            sink,
            config = defaultDeliveryConfig().copy(dispatchConcurrency = 2),
        )

        val results = listOf(
            async { dispatcher.dispatchDue() },
            async { dispatcher.dispatchDue() },
        ).awaitAll()

        assertEquals(16, results.sumOf { it.sent })
        assertTrue(sink.maxInFlight <= 2)
        assertEquals(16, MessageDeliveryRepository.countByStatus(DeliveryStatus.SENT))
    }

    private fun dispatcher(
        vararg sinks: MessageSinkPlugin,
        config: DeliveryConfig = defaultDeliveryConfig(),
        routing: MessageRoutingConfig = MessageRoutingConfig(),
        outboundMediaService: OutboundMediaService? = null,
    ): DeliveryDispatcher {
        return DeliveryDispatcher(
            sinkProvider = { sinks.mapIndexed { index, sink -> sink.handleForTest(index) } },
            configProvider = { config },
            routingConfigProvider = { routing },
            outboundMediaService = outboundMediaService,
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

    private fun roundRobinRouting(): MessageRoutingConfig = MessageRoutingConfig(
        defaultPolicy = MessageSinkRoutingPolicy(
            strategy = MessageSinkRoutingStrategy.ROUND_ROBIN,
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

    private fun imageMessage(id: String, target: TargetAddress, uri: String): Message {
        return Message(
            id = id,
            time = 1L,
            targets = listOf(target),
            batches = listOf(
                MessageBatch(
                    listOf(
                        MessageContent.Image(
                            fallbackText = "",
                            image = MediaRef(uri, MediaKind.IMAGE),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun mergedForwardMessage(id: String, target: TargetAddress): Message {
        return Message(
            id = id,
            time = 1L,
            targets = listOf(target),
            batches = listOf(
                MessageBatch(
                    listOf(
                        MessageContent.Text("before"),
                        MessageContent.Forward(
                            fallbackText = "[合并转发] Demo UP",
                            title = "Demo UP 的原始内容",
                            sourceName = "Demo UP",
                            nodes = listOf(
                                ForwardNode(
                                    senderId = "123",
                                    senderName = "Demo UP",
                                    time = 1L,
                                    batches = listOf(MessageBatch(listOf(MessageContent.Text("node text")))),
                                ),
                            ),
                        ),
                        MessageContent.Text("after"),
                    ),
                ),
            ),
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
        val sentMessages: MutableList<Message> = mutableListOf()

        override suspend fun sendMessage(request: MessageDeliveryRequest): MessageSendResult {
            sentMessageIds += request.message.id
            sentMessages += request.message
            return result(request)
        }
    }

    private class DelayingSink(
        private val delayMillis: Long,
    ) : MessageSinkPlugin {
        override val transportId: String = "direct"
        override val supportedTargetPlatforms: Set<PlatformId> = setOf(PlatformId.of("qq"))
        override val supportedTargetKinds: Set<TargetKind> = setOf(TargetKind.GROUP)

        private val lock = Any()
        private var inFlight: Int = 0
        var maxInFlight: Int = 0
            private set

        override suspend fun sendMessage(request: MessageDeliveryRequest): MessageSendResult {
            synchronized(lock) {
                inFlight += 1
                maxInFlight = maxOf(maxInFlight, inFlight)
            }
            delay(delayMillis)
            synchronized(lock) {
                inFlight -= 1
            }
            return MessageSendResult.sent("receipt-${request.message.id}")
        }
    }

    private class RoutedRecordingSink(
        private val accountIds: List<String>,
        private val features: Set<MessageSinkFeature> = emptySet(),
        private val routeProfileIds: Map<String, String> = emptyMap(),
        private val result: (String, MessageDeliveryRequest) -> MessageSendResult = { accountId, request ->
            MessageSendResult.sent("receipt-${request.message.id}", sinkAccountId = accountId)
        },
    ) : AccountRoutedMessageSinkPlugin {
        override val transportId: String = "onebot"
        override val supportedMessageFeatures: Set<MessageSinkFeature> = features
        override val supportedTargetPlatforms: Set<PlatformId> = setOf(PlatformId.of("qq"))
        override val supportedTargetKinds: Set<TargetKind> = setOf(TargetKind.GROUP)

        val sent: MutableList<String> = mutableListOf()
        val sentMessages: MutableList<Message> = mutableListOf()

        override suspend fun listMessageSinkRoutes(target: TargetAddress?): List<MessageSinkRoute> {
            return accountIds.map { accountId ->
                MessageSinkRoute(
                    routeId = "onebot:qq:$accountId",
                    transportId = transportId,
                    targetPlatformId = PlatformId.of("qq"),
                    accountId = accountId,
                    accountName = accountId,
                    mediaDeliveryProfileId = routeProfileIds[accountId].orEmpty(),
                )
            }
        }

        override suspend fun sendMessage(
            request: MessageDeliveryRequest,
            routeId: String,
        ): MessageSendResult {
            val accountId = routeId.substringAfterLast(":")
            sent += "$accountId:${request.message.id}"
            sentMessages += request.message
            return result(accountId, request)
        }
    }
}

private fun Message.firstImageUri(): String {
    return batches
        .asSequence()
        .flatMap { it.content.asSequence() }
        .filterIsInstance<MessageContent.Image>()
        .first()
        .image
        .uri
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
