package top.colter.dynamic.listener

import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.PushTemplates
import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.DynamicElementType
import top.colter.dynamic.core.data.DynamicMedia
import top.colter.dynamic.core.data.DynamicMediaVideo
import top.colter.dynamic.core.data.DynamicMediaVideoStats
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.PlatformKind
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherType
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.SubscriberType
import top.colter.dynamic.core.data.SubscriptionAtAllType
import top.colter.dynamic.core.event.EventManger
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
import top.colter.skiko.FontUtils
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceUpdateDynamicTest {

    @AfterTest
    fun cleanup() {
        EventManger.shutdown()
    }

    @Test
    fun shouldConvertDynamicUpdateToMessageEventWithGlobalTemplateAndImage() = runBlocking {
        initDb("dynamic-listener-global-template")
        val publisher = createPublisher()
        val subscriber = createSubscriber()
        SubscriptionRepository.subscribe(subscriber.id, publisher.id)

        val listener = SourceUpdateListener(
            config = MainDynamicConfig(
                templates = PushTemplates(dynamic = "{draw}\nvideo {name} {content}"),
            ),
            imageLoader = DynamicImageLoader { },
            imageRenderer = DynamicImageRenderer { Paths.get("D:/tmp/dynamic.png") },
        )

        val received = captureMessageEvent()
        listener.onMessage(SourceUpdateEvent(source = "test", update = demoDynamic(publisher)))
        val event = withTimeout(3_000) { received.await() }

        assertEquals(listOf(subscriber.toMessageTarget()), event.message.targets)
        val contents = event.message.chain.single().content
        assertTrue(contents.first() is MessageContent.Image)
        assertEquals("D:\\tmp\\dynamic.png", (contents.first() as MessageContent.Image).image.uri)
        assertEquals("\nvideo Demo UP Demo content", contents.filterIsInstance<MessageContent.Text>().single().fallbackText)
    }

    @Test
    fun shouldSyncPublisherInfoAndFillMissingHeaderFromLocalRecord() = runBlocking {
        initDb("dynamic-listener-sync-publisher")
        val publisher = createPublisher()
        val subscriber = createSubscriber()
        SubscriptionRepository.subscribe(subscriber.id, publisher.id)

        val storedHeader = LazyImage("https://example.com/header.png")
        PublisherRepository.replace(
            publisher.copy(
                name = "Old UP",
                face = LazyImage("https://example.com/old-face.png"),
                header = storedHeader,
            )
        )
        val incomingPublisher = assertNotNull(PublisherRepository.findById(publisher.id)).copy(
            name = "New UP",
            face = LazyImage("https://example.com/new-face.png"),
            header = null,
        )
        var renderedHeader: String? = null

        val listener = SourceUpdateListener(
            config = MainDynamicConfig(templates = PushTemplates(dynamic = "{draw}\n{name}")),
            imageLoader = DynamicImageLoader { },
            imageRenderer = DynamicImageRenderer { dynamic ->
                renderedHeader = dynamic.publisher.header?.uri
                Paths.get("D:/tmp/synced.png")
            },
        )

        val received = captureMessageEvent()
        listener.onMessage(SourceUpdateEvent(source = "test", update = demoDynamic(incomingPublisher)))
        val event = withTimeout(3_000) { received.await() }

        val updated = assertNotNull(PublisherRepository.findById(publisher.id))
        assertEquals("New UP", updated.name)
        assertEquals("https://example.com/new-face.png", updated.face.uri)
        assertEquals(storedHeader.uri, updated.header?.uri)
        assertEquals(storedHeader.uri, renderedHeader)
        assertEquals("\nNew UP", event.message.chain.single().content.filterIsInstance<MessageContent.Text>().single().fallbackText)
    }

    @Test
    fun shouldFallbackToTextWhenDrawFails() = runBlocking {
        initDb("dynamic-listener-draw-fail")
        val publisher = createPublisher()
        val subscriber = createSubscriber()

        val listener = SourceUpdateListener(
            config = MainDynamicConfig(templates = PushTemplates(dynamic = "{draw}\nfallback {name}")),
            imageLoader = DynamicImageLoader { },
            imageRenderer = DynamicImageRenderer { error("绘制失败") },
        )

        val received = captureMessageEvent()
        listener.onMessage(SourceUpdateEvent(source = "test", target = subscriber, update = demoDynamic(publisher)))
        val event = withTimeout(3_000) { received.await() }

        val contents = event.message.chain.single().content
        assertEquals(1, contents.size)
        assertEquals("fallback Demo UP", contents.single().fallbackText)
    }

    @Test
    fun shouldRenderDrawWhenDefaultFontIsNotPreloaded() = runBlocking {
        initDb("dynamic-listener-font-fallback")
        val publisher = createPublisher()
        val subscriber = createSubscriber()
        val previousDefaultFont = FontUtils.defaultFont
        val previousEmojiFont = FontUtils.emojiFont

        FontUtils.defaultFont = null
        FontUtils.emojiFont = null
        try {
            val outputDir = createTempDirectory("dynamic-bot-rendered")
            val listener = SourceUpdateListener(
                config = MainDynamicConfig(templates = PushTemplates(dynamic = "{draw}\n{name}")),
                imageLoader = DynamicImageLoader { },
                imageRenderer = FileDynamicImageRenderer(outputDir),
            )

            val received = captureMessageEvent()
            listener.onMessage(SourceUpdateEvent(source = "test", target = subscriber, update = demoDynamic(publisher)))
            val event = withTimeout(3_000) { received.await() }

            val image = event.message.chain.single().content.first() as MessageContent.Image
            assertTrue(Files.isRegularFile(Paths.get(image.image.uri)))
        } finally {
            FontUtils.defaultFont = previousDefaultFont
            FontUtils.emojiFont = previousEmojiFont
        }
    }

    @Test
    fun shouldNotRenderDrawWhenTemplateDoesNotContainDrawPlaceholder() = runBlocking {
        initDb("dynamic-listener-no-draw")
        val publisher = createPublisher()
        val subscriber = createSubscriber()
        var renderCalls = 0

        val listener = SourceUpdateListener(
            config = MainDynamicConfig(templates = PushTemplates(dynamic = "text only {name}")),
            imageLoader = DynamicImageLoader { },
            imageRenderer = DynamicImageRenderer {
                renderCalls += 1
                Paths.get("D:/tmp/not-used.png")
            },
        )

        val received = captureMessageEvent()
        listener.onMessage(SourceUpdateEvent(source = "test", target = subscriber, update = demoDynamic(publisher)))
        val event = withTimeout(3_000) { received.await() }

        assertEquals(0, renderCalls)
        assertEquals("text only Demo UP", event.message.chain.single().content.single().fallbackText)
    }

    @Test
    fun shouldSplitTemplateIntoMultipleMessageChains() = runBlocking {
        initDb("dynamic-listener-split-chain")
        val publisher = createPublisher()
        val subscriber = createSubscriber()

        val listener = SourceUpdateListener(
            config = MainDynamicConfig(templates = PushTemplates(dynamic = "first {name}\\rsecond {link}")),
            imageLoader = DynamicImageLoader { },
            imageRenderer = DynamicImageRenderer { Paths.get("D:/tmp/not-used.png") },
        )

        val received = captureMessageEvent()
        listener.onMessage(SourceUpdateEvent(source = "test", target = subscriber, update = demoDynamic(publisher)))
        val event = withTimeout(3_000) { received.await() }

        assertEquals(2, event.message.chain.size)
        assertEquals("first Demo UP", event.message.chain[0].content.single().fallbackText)
        assertEquals("second https://t.bilibili.com/dynamic-1", event.message.chain[1].content.single().fallbackText)
    }

    @Test
    fun shouldAppendMentionAllAtTailOnlyForEnabledDynamicSubscriptions() = runBlocking {
        initDb("dynamic-listener-at-all-dynamic")
        val publisher = createPublisher()
        val atAllSubscriber = createSubscriber(id = 10, targetId = "100")
        val normalSubscriber = createSubscriber(id = 11, targetId = "200")
        SubscriptionRepository.subscribe(
            subscriberId = atAllSubscriber.id,
            publisherId = publisher.id,
            atAllTypes = setOf(SubscriptionAtAllType.DYNAMIC),
        )
        SubscriptionRepository.subscribe(normalSubscriber.id, publisher.id)

        val listener = SourceUpdateListener(
            config = MainDynamicConfig(templates = PushTemplates(dynamic = "tail {name}")),
            imageLoader = DynamicImageLoader { },
            imageRenderer = DynamicImageRenderer { Paths.get("D:/tmp/not-used.png") },
        )

        val received = captureMessageEvents()
        listener.onMessage(SourceUpdateEvent(source = "test", update = demoDynamic(publisher)))

        val events = listOf(
            withTimeout(3_000) { received.receive() },
            withTimeout(3_000) { received.receive() },
        ).associateBy { it.message.targets.single().targetId }

        val atAllContents = events.getValue("100").message.chain.single().content
        assertEquals("tail Demo UP", atAllContents.first().fallbackText)
        assertTrue(atAllContents.last() is MessageContent.MentionAll)
        assertEquals(
            "tail Demo UP",
            events.getValue("200").message.chain.single().content.single().fallbackText,
        )
    }

    @Test
    fun shouldAppendMentionAllForVideoOnlySubscriptionWhenDynamicHasVideo() = runBlocking {
        initDb("dynamic-listener-at-all-video")
        val publisher = createPublisher()
        val subscriber = createSubscriber()
        SubscriptionRepository.subscribe(
            subscriberId = subscriber.id,
            publisherId = publisher.id,
            atAllTypes = setOf(SubscriptionAtAllType.VIDEO),
        )

        val listener = SourceUpdateListener(
            config = MainDynamicConfig(templates = PushTemplates(dynamic = "video {name}")),
            imageLoader = DynamicImageLoader { },
            imageRenderer = DynamicImageRenderer { Paths.get("D:/tmp/not-used.png") },
        )

        val received = captureMessageEvent()
        listener.onMessage(SourceUpdateEvent(source = "test", update = demoDynamic(publisher).withVideo()))
        val event = withTimeout(3_000) { received.await() }

        val contents = event.message.chain.single().content
        assertEquals("video Demo UP", contents.first().fallbackText)
        assertTrue(contents.last() is MessageContent.MentionAll)
    }

    @Test
    fun shouldSkipRenderingAndDeliveryWhenAllTargetsAreFiltered() = runBlocking {
        initDb("dynamic-listener-filter-all")
        val publisher = createPublisher()
        val subscriber = createSubscriber()
        SubscriptionRepository.subscribe(subscriber.id, publisher.id)
        val subscription = SubscriptionRepository.findBySubscriberAndPublisher(subscriber.id, publisher.id)!!
        DynamicFilterRuleRepository.addElementRule(subscription.id, DynamicElementType.TEXT)

        var loadCalls = 0
        var renderCalls = 0
        val listener = SourceUpdateListener(
            config = MainDynamicConfig(templates = PushTemplates(dynamic = "filtered {name}")),
            imageLoader = DynamicImageLoader { loadCalls += 1 },
            imageRenderer = DynamicImageRenderer {
                renderCalls += 1
                Paths.get("D:/tmp/filtered.png")
            },
        )

        val received = captureMessageEvent()
        listener.onMessage(SourceUpdateEvent(source = "test", update = demoDynamic(publisher)))
        val event = withTimeoutOrNull(300) { received.await() }

        assertNull(event)
        assertEquals(0, loadCalls)
        assertEquals(0, renderCalls)
        assertEquals(0, MessageDeliveryRepository.countByStatus(DeliveryStatus.PENDING))
    }

    @Test
    fun shouldDeliverOnlyUnfilteredTargets() = runBlocking {
        initDb("dynamic-listener-filter-partial")
        val publisher = createPublisher()
        val filteredSubscriber = createSubscriber(id = 10, targetId = "100")
        val allowedSubscriber = createSubscriber(id = 11, targetId = "200")
        SubscriptionRepository.subscribe(filteredSubscriber.id, publisher.id)
        SubscriptionRepository.subscribe(allowedSubscriber.id, publisher.id)
        val filteredSubscription = SubscriptionRepository.findBySubscriberAndPublisher(filteredSubscriber.id, publisher.id)!!
        DynamicFilterRuleRepository.addElementRule(filteredSubscription.id, DynamicElementType.TEXT)

        val listener = SourceUpdateListener(
            config = MainDynamicConfig(templates = PushTemplates(dynamic = "allowed {name}")),
            imageLoader = DynamicImageLoader { },
            imageRenderer = DynamicImageRenderer { Paths.get("D:/tmp/allowed.png") },
        )

        val received = captureMessageEvent()
        listener.onMessage(SourceUpdateEvent(source = "test", update = demoDynamic(publisher)))
        val event = withTimeout(3_000) { received.await() }

        assertEquals(listOf(allowedSubscriber.toMessageTarget()), event.message.targets)
        assertEquals(1, MessageDeliveryRepository.countByStatus(DeliveryStatus.PENDING))
    }

    @Test
    fun shouldApplyFiltersToTargetEventsOnlyWhenSubscriptionExists() = runBlocking {
        initDb("dynamic-listener-filter-target")
        val publisher = createPublisher()
        val filteredSubscriber = createSubscriber(id = 10, targetId = "100")
        val directSubscriber = createSubscriber(id = 11, targetId = "200")
        SubscriptionRepository.subscribe(filteredSubscriber.id, publisher.id)
        val filteredSubscription = SubscriptionRepository.findBySubscriberAndPublisher(filteredSubscriber.id, publisher.id)!!
        DynamicFilterRuleRepository.addElementRule(filteredSubscription.id, DynamicElementType.TEXT)

        val listener = SourceUpdateListener(
            config = MainDynamicConfig(templates = PushTemplates(dynamic = "direct {name}")),
            imageLoader = DynamicImageLoader { },
            imageRenderer = DynamicImageRenderer { Paths.get("D:/tmp/direct.png") },
        )

        val received = captureMessageEvent()
        listener.onMessage(SourceUpdateEvent(source = "test", target = filteredSubscriber, update = demoDynamic(publisher)))
        assertNull(withTimeoutOrNull(300) { received.await() })

        listener.onMessage(SourceUpdateEvent(source = "test", target = directSubscriber, update = demoDynamic(publisher)))
        val event = withTimeout(3_000) { received.await() }

        assertEquals(listOf(directSubscriber.toMessageTarget()), event.message.targets)
    }

    @Test
    fun shouldSkipOrdinaryDynamicBeforeSubscriptionCreatedAt() = runBlocking {
        initDb("dynamic-listener-subscription-created-at")
        val publisher = createPublisher()
        val subscriber = createSubscriber()
        SubscriptionRepository.subscribe(subscriber.id, publisher.id)
        val subscription = SubscriptionRepository.findBySubscriberAndPublisher(subscriber.id, publisher.id)!!

        val listener = SourceUpdateListener(
            config = MainDynamicConfig(templates = PushTemplates(dynamic = "old {name}")),
            imageLoader = DynamicImageLoader { },
            imageRenderer = DynamicImageRenderer { Paths.get("D:/tmp/old.png") },
        )

        val received = captureMessageEvent()
        listener.onMessage(
            SourceUpdateEvent(
                source = "test",
                update = demoDynamic(publisher, time = subscription.createdAtEpochSeconds - 1),
            )
        )

        assertNull(withTimeoutOrNull(300) { received.await() })
        assertEquals(0, MessageDeliveryRepository.countByStatus(DeliveryStatus.PENDING))
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
        PublisherRepository.create(
            Publisher(
                id = 1,
                platformId = "bilibili",
                type = PublisherType.USER,
                externalId = "123",
                name = "Demo UP",
                state = EntityState.ACTIVE,
                face = LazyImage("https://example.com/face.png"),
                createTime = 1,
                createUser = 1,
            )
        )
        return assertNotNull(PublisherRepository.findByPlatformAndExternalId("bilibili", "123"))
    }

    private fun createSubscriber(id: Int = 10, targetId: String = "100"): Subscriber {
        SubscriberRepository.create(
            Subscriber(
                id = id,
                platformId = "onebot",
                type = SubscriberType.GROUP,
                targetId = targetId,
                name = "group",
                state = EntityState.ACTIVE,
                createTime = 1,
                createUser = 1,
            )
        )
        return assertNotNull(SubscriberRepository.findByPlatformAndTarget("onebot", SubscriberType.GROUP, targetId))
    }

    private fun demoDynamic(
        publisher: Publisher,
        time: Long = System.currentTimeMillis() / 1000 + 60,
    ): Dynamic {
        return Dynamic(
            platform = PlatformDescriptor(
                id = "bilibili",
                name = "BiliBili",
                homepage = "https://www.bilibili.com",
                iconUri = "",
                kind = PlatformKind.PUBLISHER,
            ),
            dynamicId = "dynamic-1",
            publisher = publisher,
            time = time,
            link = "https://t.bilibili.com/dynamic-1",
            content = DynamicContent("Demo content", listOf(DynamicContentNodeText("Demo content"))),
        )
    }

    private fun Dynamic.withVideo(): Dynamic {
        return copy(
            media = DynamicMedia(
                video = DynamicMediaVideo(
                    id = "BV1",
                    title = "Demo video",
                    description = "Demo video description",
                    cover = LazyImage("https://example.com/cover.png"),
                    duration = "01:00",
                    badge = "video",
                    stats = DynamicMediaVideoStats(play = "1", danmaku = "2", like = "3"),
                    link = "https://www.bilibili.com/video/BV1",
                )
            )
        )
    }
}
