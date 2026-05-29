package top.colter.dynamic.listener

import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
import top.colter.dynamic.core.data.DynamicAttachmentKind
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicMetric
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.FilterAction
import top.colter.dynamic.core.data.FilterCondition
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MentionMode
import top.colter.dynamic.core.data.MentionRule
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.SubscriptionPolicy
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.data.UpdateSelector
import top.colter.dynamic.core.data.VideoAttachment
import top.colter.dynamic.core.event.EventBus
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.MessageEvent
import top.colter.dynamic.core.event.SourceUpdateEvent
import top.colter.dynamic.core.event.register
import top.colter.dynamic.core.repository.DynamicFilterRuleRepository
import top.colter.dynamic.core.repository.MessageDeliveryRepository
import top.colter.dynamic.core.repository.PersistenceManager
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.SubscriberRepository
import top.colter.dynamic.core.repository.SubscriptionRepository
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testMedia
import top.colter.dynamic.testPublisher
import top.colter.dynamic.testTargetAddress

class SourceUpdateDynamicTest {

    @AfterTest
    fun cleanup() {
        EventBus.global.shutdown()
    }

    @Test
    fun shouldConvertDynamicUpdateToMessageEventWithGlobalTemplateAndImage() = runBlocking {
        initDb("dynamic-listener-global-template")
        val publisher = createPublisher()
        val subscriber = createSubscriber()
        SubscriptionRepository.subscribe(subscriber.id, publisher.id)
        val listener = SourceUpdateListener(
            config = MainDynamicConfig(templates = PushTemplates(dynamic = "{draw}\nvideo {name} {content}")),
            imageLoader = DynamicImageLoader { },
            imageRenderer = DynamicImageRenderer { Paths.get("D:/tmp/dynamic.png") },
        )

        val received = captureMessageEvent()
        listener.onMessage(SourceUpdateEvent(source = "test", update = demoDynamic(publisher)))
        val event = withTimeout(3_000) { received.await() }

        assertEquals(listOf(subscriber.address), event.message.targets)
        val contents = event.message.batches.single().content
        assertTrue(contents.first() is MessageContent.Image)
        assertEquals("D:\\tmp\\dynamic.png", (contents.first() as MessageContent.Image).image.uri)
        assertEquals("\nvideo Demo UP Demo content", contents.filterIsInstance<MessageContent.Text>().single().fallbackText)
    }

    @Test
    fun shouldSyncPublisherInfoAndFillMissingBannerFromLocalRecord() = runBlocking {
        initDb("dynamic-listener-sync-publisher")
        val publisher = createPublisher()
        val subscriber = createSubscriber()
        SubscriptionRepository.subscribe(subscriber.id, publisher.id)
        val storedBanner = testMedia("https://example.com/header.png", MediaKind.COVER)
        PublisherRepository.replace(
            publisher.copy(
                name = "Old UP",
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
        val listener = SourceUpdateListener(
            config = MainDynamicConfig(templates = PushTemplates(dynamic = "{draw}\n{name}")),
            imageLoader = DynamicImageLoader { },
            imageRenderer = DynamicImageRenderer { update ->
                renderedBanner = update.publisher.banner?.uri
                Paths.get("D:/tmp/synced.png")
            },
        )

        val received = captureMessageEvent()
        listener.onMessage(SourceUpdateEvent(source = "test", update = demoDynamic(incoming)))
        val event = withTimeout(3_000) { received.await() }

        val updated = assertNotNull(PublisherRepository.findById(publisher.id))
        assertEquals("New UP", updated.name)
        assertEquals("https://example.com/new-face.png", updated.avatar.uri)
        assertEquals(storedBanner.uri, updated.banner?.uri)
        assertEquals(storedBanner.uri, renderedBanner)
        assertEquals("\nNew UP", event.message.batches.single().content.filterIsInstance<MessageContent.Text>().single().fallbackText)
    }

    @Test
    fun shouldAppendMentionAllByEventTypeAndAttachmentSelector() = runBlocking {
        initDb("dynamic-listener-at-all")
        val publisher = createPublisher()
        val atAllSubscriber = createSubscriber(id = 10, targetId = "100")
        val normalSubscriber = createSubscriber(id = 11, targetId = "200")
        SubscriptionRepository.subscribe(
            atAllSubscriber.id,
            publisher.id,
            SubscriptionPolicy(
                updateSelectors = listOf(UpdateSelector.default()),
                mentionRules = listOf(
                    MentionRule(
                        selector = UpdateSelector(
                            eventTypes = setOf(SourceEventType.DYNAMIC_CREATED),
                            attachmentKinds = setOf(DynamicAttachmentKind.VIDEO),
                        ),
                        mode = MentionMode.MENTION_ALL,
                    ),
                ),
            ),
        )
        SubscriptionRepository.subscribe(normalSubscriber.id, publisher.id)
        val listener = SourceUpdateListener(
            config = MainDynamicConfig(templates = PushTemplates(dynamic = "tail {name}")),
            imageLoader = DynamicImageLoader { },
            imageRenderer = DynamicImageRenderer { Paths.get("D:/tmp/not-used.png") },
        )

        val received = captureMessageEvents()
        listener.onMessage(SourceUpdateEvent(source = "test", update = demoDynamic(publisher, withVideo = true)))

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
        val publisher = createPublisher()
        val filteredSubscriber = createSubscriber(id = 10, targetId = "100")
        val allowedSubscriber = createSubscriber(id = 11, targetId = "200")
        SubscriptionRepository.subscribe(filteredSubscriber.id, publisher.id)
        SubscriptionRepository.subscribe(allowedSubscriber.id, publisher.id)
        val filteredSubscription = SubscriptionRepository.findBySubscriberAndPublisher(filteredSubscriber.id, publisher.id)!!
        DynamicFilterRuleRepository.addRule(
            filteredSubscription.id,
            FilterAction.BLOCK,
            FilterCondition.TextContains("Demo content"),
        )
        var renderCalls = 0
        val listener = SourceUpdateListener(
            config = MainDynamicConfig(templates = PushTemplates(dynamic = "allowed {name}")),
            imageLoader = DynamicImageLoader { },
            imageRenderer = DynamicImageRenderer {
                renderCalls += 1
                Paths.get("D:/tmp/allowed.png")
            },
        )

        val received = captureMessageEvent()
        listener.onMessage(SourceUpdateEvent(source = "test", update = demoDynamic(publisher)))
        val event = withTimeout(3_000) { received.await() }

        assertEquals(listOf(allowedSubscriber.address), event.message.targets)
        assertEquals(0, renderCalls)
        assertEquals(1, MessageDeliveryRepository.countByStatus(DeliveryStatus.PENDING))

        val targetReceived = captureMessageEvent()
        listener.onMessage(SourceUpdateEvent(source = "test", targetOverride = filteredSubscriber, update = demoDynamic(publisher)))
        assertNull(withTimeoutOrNull(300) { targetReceived.await() })
    }

    private fun captureMessageEvent(): CompletableDeferred<MessageEvent> {
        val received = CompletableDeferred<MessageEvent>()
        object : Listener<MessageEvent> {
            override suspend fun onMessage(event: MessageEvent) {
                if (!received.isCompleted) received.complete(event)
            }
        }.register<MessageEvent>()
        return received
    }

    private fun captureMessageEvents(): Channel<MessageEvent> {
        val received = Channel<MessageEvent>(Channel.UNLIMITED)
        object : Listener<MessageEvent> {
            override suspend fun onMessage(event: MessageEvent) {
                received.send(event)
            }
        }.register<MessageEvent>()
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
                content = DynamicContent.text("Demo content"),
                attachments = if (withVideo) {
                    listOf(
                        VideoAttachment(
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
                    )
                } else {
                    emptyList()
                },
            ),
        ).copy(
            occurredAtEpochSeconds = time,
            link = "https://t.bilibili.com/dynamic-1",
        )
    }
}
