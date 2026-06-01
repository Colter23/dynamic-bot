package top.colter.dynamic.listener

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.PushTemplates
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicMediaCard
import top.colter.dynamic.core.data.DynamicMediaCardKind
import top.colter.dynamic.core.data.DynamicMetric
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.FilterCondition
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.MediaCardStyle
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.SubscriptionEventKind
import top.colter.dynamic.core.data.SubscriptionPolicy
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.data.TextBlock
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.Listener
import top.colter.dynamic.event.MessageEvent
import top.colter.dynamic.core.event.SourceUpdatePublishRequest
import top.colter.dynamic.core.event.SourceUpdatePublishStatus
import top.colter.dynamic.draw.DynamicDrawService
import top.colter.dynamic.link.LINK_PARSE_EVENT_LABEL
import top.colter.dynamic.repository.DynamicFilterRuleRepository
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.repository.PersistenceManager
import top.colter.dynamic.repository.PublisherRepository
import top.colter.dynamic.repository.SubscriberRepository
import top.colter.dynamic.repository.SubscriptionRepository
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testMedia
import top.colter.dynamic.testPublisher
import top.colter.dynamic.testTargetAddress

class SourceUpdateDynamicTest {
    @Test
    fun shouldConvertDynamicUpdateToMessageEventWithGlobalTemplateAndImage() = runBlocking {
        initDb("dynamic-listener-global-template")
        val eventBus = EventBus()
        val publisher = createPublisher()
        val subscriber = createSubscriber()
        SubscriptionRepository.subscribe(subscriber.id, publisher.id)
        val listener = SourceUpdateProcessor(
            config = MainDynamicConfig(templates = PushTemplates(dynamic = "{draw}\nvideo {name} {content}")),
            eventBus = eventBus,
            drawService = DynamicDrawService { _, _ -> MediaRef("D:/tmp/dynamic.png", MediaKind.IMAGE) },
        )

        val received = captureMessageEvent(eventBus)
        listener.process(SourceUpdatePublishRequest(sourcePlugin = "test", update = demoDynamic(publisher)))
        val event = withTimeout(3_000) { received.await() }

        assertEquals(listOf(subscriber.address), event.message.targets)
        val contents = event.message.batches.single().content
        assertTrue(contents.first() is MessageContent.Image)
        assertEquals("D:/tmp/dynamic.png", (contents.first() as MessageContent.Image).image.uri)
        assertEquals("\nvideo Demo UP Demo content", contents.filterIsInstance<MessageContent.Text>().single().fallbackText)
    }

    @Test
    fun shouldKeepDuplicateProtectionForRegularDynamicUpdates() = runBlocking {
        initDb("dynamic-listener-regular-dedupe")
        val eventBus = EventBus()
        val publisher = createPublisher()
        val subscriber = createSubscriber()
        SubscriptionRepository.subscribe(subscriber.id, publisher.id)
        val listener = SourceUpdateProcessor(
            config = MainDynamicConfig(templates = PushTemplates(dynamic = "{name} {link}")),
            eventBus = eventBus,
        )
        val received = captureMessageEvents(eventBus)
        val request = SourceUpdatePublishRequest(sourcePlugin = "test", update = demoDynamic(publisher))

        val first = listener.process(request)
        val firstEvent = withTimeout(3_000) { received.receive() }
        val second = listener.process(request)

        assertEquals(SourceUpdatePublishStatus.ENQUEUED, first.status)
        assertEquals(SourceUpdatePublishStatus.DUPLICATE, second.status)
        assertEquals("${request.update.key.stableValue()}:default", firstEvent.message.id)
        assertEquals(1, MessageDeliveryRepository.countByStatus(DeliveryStatus.PENDING))
        assertNull(withTimeoutOrNull(300) { received.receive() })
    }

    @Test
    fun shouldCreateNewDeliveriesForRepeatedLinkParseRequests() = runBlocking {
        initDb("dynamic-listener-link-parse-repeat")
        val eventBus = EventBus()
        val publisher = createPublisher()
        val subscriber = createSubscriber()
        val listener = SourceUpdateProcessor(
            config = MainDynamicConfig(templates = PushTemplates(dynamic = "{name} {link}")),
            eventBus = eventBus,
        )
        val received = captureMessageEvents(eventBus)
        val request = SourceUpdatePublishRequest(
            sourcePlugin = "test",
            deliveryTarget = subscriber,
            deliveryTag = LINK_PARSE_EVENT_LABEL,
            update = demoDynamic(publisher),
        )

        val first = listener.process(request)
        val firstEvent = withTimeout(3_000) { received.receive() }
        val second = listener.process(request)
        val secondEvent = withTimeout(3_000) { received.receive() }

        assertEquals(SourceUpdatePublishStatus.ENQUEUED, first.status)
        assertEquals(SourceUpdatePublishStatus.ENQUEUED, second.status)
        assertEquals(2, MessageDeliveryRepository.countByStatus(DeliveryStatus.PENDING))
        assertEquals(firstEvent.message.sourceUpdateKey, secondEvent.message.sourceUpdateKey)
        assertNotEquals(firstEvent.message.id, secondEvent.message.id)
        assertTrue(firstEvent.message.id.contains(":default:link-parse:"))
        assertTrue(secondEvent.message.id.contains(":default:link-parse:"))
    }

    @Test
    fun shouldSyncPublisherInfoAndFillMissingBannerFromLocalRecord() = runBlocking {
        initDb("dynamic-listener-sync-publisher")
        val eventBus = EventBus()
        val publisher = createPublisher()
        val subscriber = createSubscriber()
        SubscriptionRepository.subscribe(subscriber.id, publisher.id)
        val storedBanner = testMedia("https://example.com/header.png", MediaKind.COVER)
        PublisherRepository.replace(
            publisher.copy(
                name = "Old UP",
                state = EntityState.DISABLED,
                avatar = testMedia("https://example.com/old-face.png", MediaKind.AVATAR),
                banner = storedBanner,
            ),
        )
        val incoming = assertNotNull(PublisherRepository.findById(publisher.id)).copy(
            name = "New UP",
            avatar = testMedia("https://example.com/new-face.png", MediaKind.AVATAR),
            banner = null,
        )
        var renderedBanner: String? = null
        val listener = SourceUpdateProcessor(
            config = MainDynamicConfig(templates = PushTemplates(dynamic = "{draw}\n{name}")),
            eventBus = eventBus,
            drawService = DynamicDrawService { update, _ ->
                renderedBanner = update.publisher.banner?.uri
                MediaRef("D:/tmp/synced.png", MediaKind.IMAGE)
            },
        )

        val received = captureMessageEvent(eventBus)
        listener.process(SourceUpdatePublishRequest(sourcePlugin = "test", update = demoDynamic(incoming)))
        val event = withTimeout(3_000) { received.await() }

        val updated = assertNotNull(PublisherRepository.findById(publisher.id))
        assertEquals("New UP", updated.name)
        assertEquals(EntityState.DISABLED, updated.state)
        assertEquals("https://example.com/new-face.png", updated.avatar.uri)
        assertEquals(storedBanner.uri, updated.banner?.uri)
        assertEquals(storedBanner.uri, renderedBanner)
        assertEquals("\nNew UP", event.message.batches.single().content.filterIsInstance<MessageContent.Text>().single().fallbackText)
    }

    @Test
    fun shouldAppendMentionAllByEventType() = runBlocking {
        initDb("dynamic-listener-at-all")
        val eventBus = EventBus()
        val publisher = createPublisher()
        val atAllSubscriber = createSubscriber(id = 10, targetId = "100")
        val normalSubscriber = createSubscriber(id = 11, targetId = "200")
        SubscriptionRepository.subscribe(
            atAllSubscriber.id,
            publisher.id,
            SubscriptionPolicy(
                enabledEvents = setOf(SubscriptionEventKind.DYNAMIC),
                mentionAllEvents = setOf(SubscriptionEventKind.DYNAMIC),
            ),
        )
        SubscriptionRepository.subscribe(normalSubscriber.id, publisher.id)
        val listener = SourceUpdateProcessor(
            config = MainDynamicConfig(templates = PushTemplates(dynamic = "tail {name}")),
            eventBus = eventBus,
            drawService = DynamicDrawService { _, _ -> MediaRef("D:/tmp/not-used.png", MediaKind.IMAGE) },
        )

        val received = captureMessageEvents(eventBus)
        listener.process(SourceUpdatePublishRequest(sourcePlugin = "test", update = demoDynamic(publisher, withVideo = true)))

        val events = listOf(
            withTimeout(3_000) { received.receive() },
            withTimeout(3_000) { received.receive() },
        ).associateBy { it.message.targets.single().externalId }

        val atAllContents = events.getValue("100").message.batches.single().content
        assertEquals("tail Demo UP", atAllContents.first().fallbackText)
        assertTrue(atAllContents.last() is MessageContent.MentionAll)
        assertEquals("tail Demo UP", events.getValue("200").message.batches.single().content.single().fallbackText)
    }

    @Test
    fun shouldDeliverOnlyUnfilteredTargetsAndSkipDrawWhenAllFiltered() = runBlocking {
        initDb("dynamic-listener-filter")
        val eventBus = EventBus()
        val publisher = createPublisher()
        val filteredSubscriber = createSubscriber(id = 10, targetId = "100")
        val allowedSubscriber = createSubscriber(id = 11, targetId = "200")
        SubscriptionRepository.subscribe(filteredSubscriber.id, publisher.id)
        SubscriptionRepository.subscribe(allowedSubscriber.id, publisher.id)
        val filteredSubscription = SubscriptionRepository.findBySubscriberAndPublisher(filteredSubscriber.id, publisher.id)!!
        DynamicFilterRuleRepository.addRule(
            filteredSubscription.id,
            FilterCondition.TextContains("Demo content"),
        )
        var renderCalls = 0
        val listener = SourceUpdateProcessor(
            config = MainDynamicConfig(templates = PushTemplates(dynamic = "allowed {name}")),
            eventBus = eventBus,
            drawService = DynamicDrawService { _, _ ->
                renderCalls += 1
                MediaRef("D:/tmp/allowed.png", MediaKind.IMAGE)
            },
        )

        val received = captureMessageEvent(eventBus)
        listener.process(SourceUpdatePublishRequest(sourcePlugin = "test", update = demoDynamic(publisher)))
        val event = withTimeout(3_000) { received.await() }

        assertEquals(listOf(allowedSubscriber.address), event.message.targets)
        assertEquals(0, renderCalls)
        assertEquals(1, MessageDeliveryRepository.countByStatus(DeliveryStatus.PENDING))

        val targetReceived = captureMessageEvent(eventBus)
        listener.process(
            SourceUpdatePublishRequest(
                sourcePlugin = "test",
                deliveryTarget = filteredSubscriber,
                update = demoDynamic(publisher),
            ),
        )
        assertNull(withTimeoutOrNull(300) { targetReceived.await() })
    }

    private fun captureMessageEvent(eventBus: EventBus): CompletableDeferred<MessageEvent> {
        val received = CompletableDeferred<MessageEvent>()
        eventBus.subscribe(
            object : Listener<MessageEvent> {
                override suspend fun onMessage(event: MessageEvent) {
                    if (!received.isCompleted) received.complete(event)
                }
            },
        )
        return received
    }

    private fun captureMessageEvents(eventBus: EventBus): Channel<MessageEvent> {
        val received = Channel<MessageEvent>(Channel.UNLIMITED)
        eventBus.subscribe(
            object : Listener<MessageEvent> {
                override suspend fun onMessage(event: MessageEvent) {
                    received.send(event)
                }
            },
        )
        return received
    }

    private fun initDb(suffix: String) {
        val tempDir = createTempDirectory("dynamic-bot-main-$suffix").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)
    }

    private fun createPublisher(): Publisher {
        PublisherRepository.create(testPublisher(id = 1, name = "Demo UP"))
        return assertNotNull(PublisherRepository.findByKey(testPublisher().key))
    }

    private fun createSubscriber(id: Int = 10, targetId: String = "100"): Subscriber {
        val address = testTargetAddress(kind = TargetKind.GROUP, externalId = targetId)
        SubscriberRepository.create(
            Subscriber(
                id = id,
                address = address,
                name = "group",
                state = EntityState.ACTIVE,
                createTime = 1,
                createUser = 1,
            ),
        )
        return assertNotNull(SubscriberRepository.findByAddress(address))
    }

    private fun demoDynamic(
        publisher: Publisher,
        time: Long = System.currentTimeMillis() / 1000 + 60,
        withVideo: Boolean = false,
    ): SourceUpdate {
        return testDynamicUpdate(
            publisher = publisher.toInfo(),
            externalId = "dynamic-1",
            payload = DynamicPayload(
                blocks = buildList {
                    add(TextBlock(DynamicContent.text("Demo content")))
                    if (withVideo) {
                        add(
                            MediaCardBlock(
                                style = MediaCardStyle.LARGE,
                                card = DynamicMediaCard(
                                    kind = DynamicMediaCardKind.VIDEO,
                                    id = "BV1",
                                    title = "Demo video",
                                    description = "Demo video description",
                                    cover = testMedia("https://example.com/cover.png", MediaKind.COVER),
                                    durationSeconds = 60,
                                    badge = "video",
                                    metrics = listOf(
                                        DynamicMetric(key = "play", display = "1"),
                                        DynamicMetric(key = "danmaku", display = "2"),
                                        DynamicMetric(key = "like", display = "3"),
                                    ),
                                    link = "https://www.bilibili.com/video/BV1",
                                ),
                            ),
                        )
                    }
                },
            ),
        ).copy(
            occurredAtEpochSeconds = time,
            link = "https://t.bilibili.com/dynamic-1",
        )
    }
}
