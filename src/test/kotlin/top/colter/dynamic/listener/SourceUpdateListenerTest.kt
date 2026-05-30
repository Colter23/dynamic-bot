package top.colter.dynamic.listener

import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.PushTemplates
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.LiveStatus
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
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.Listener
import top.colter.dynamic.event.MessageEvent
import top.colter.dynamic.core.event.SourceUpdatePublishRequest
import top.colter.dynamic.repository.PersistenceManager
import top.colter.dynamic.repository.PublisherRepository
import top.colter.dynamic.repository.SubscriberRepository
import top.colter.dynamic.repository.SubscriptionRepository
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testMedia
import top.colter.dynamic.testPublisher
import top.colter.dynamic.testTargetAddress

class SourceUpdateProcessorTest {
    @Test
    fun shouldRenderLiveStartedTemplateWithDrawCoverAndSplitBatches() = runBlocking {
        val eventBus = EventBus()
        val (publisher, subscriber) = seededSubscription("live-started")
        val listener = SourceUpdateProcessor(
            config = MainDynamicConfig(
                templates = PushTemplates(
                    liveStarted = "{draw}\\n{name}|{uid}|{rid}|{title}|{area}\\n{cover}\\n{link}\\rnext",
                ),
            ),
            eventBus = eventBus,
            imageLoader = DynamicImageLoader { },
            imageRenderer = DynamicImageRenderer { Paths.get("D:/tmp/live-started.png") },
        )
        val received = captureMessageEvent(eventBus)
        val startedAt = System.currentTimeMillis() / 1000 + 60

        listener.process(
            SourceUpdatePublishRequest(
                sourcePlugin = "test",
                update = liveUpdate(publisher, SourceEventType.LIVE_STARTED, startedAt),
            ),
        )
        val event = withTimeout(3_000) { received.await() }

        assertEquals(listOf(subscriber.address), event.message.targets)
        assertEquals(2, event.message.batches.size)
        val first = event.message.batches.first().content
        assertEquals(2, first.filterIsInstance<MessageContent.Image>().size)
        assertEquals("D:\\tmp\\live-started.png", first.filterIsInstance<MessageContent.Image>().first().image.uri)
        val text = first.filterIsInstance<MessageContent.Text>().joinToString("") { it.fallbackText }
        assertTrue(text.contains("Demo UP|123|456|Live title|Games"))
        assertTrue(text.contains("https://live.bilibili.com/456"))
        assertEquals("next", event.message.batches[1].content.single().fallbackText)
    }

    @Test
    fun shouldRenderLiveEndedDurationPlaceholders() = runBlocking {
        val eventBus = EventBus()
        val (publisher, subscriber) = seededSubscription("live-ended")
        val listener = SourceUpdateProcessor(
            config = MainDynamicConfig(
                templates = PushTemplates(
                    liveEnded = "{name}|{uid}|{rid}|{title}|{area}|{startTime}|{endTime}|{duration}|{link}",
                ),
            ),
            eventBus = eventBus,
        )
        val received = captureMessageEvent(eventBus)
        val start = System.currentTimeMillis() / 1000 + 60
        val end = start + 3_661

        listener.process(
            SourceUpdatePublishRequest(
                sourcePlugin = "test",
                update = liveUpdate(publisher, SourceEventType.LIVE_ENDED, end, start),
            ),
        )
        val event = withTimeout(3_000) { received.await() }

        assertEquals(listOf(subscriber.address), event.message.targets)
        val text = event.message.batches.single().content.filterIsInstance<MessageContent.Text>().single().fallbackText
        assertTrue(text.contains("Demo UP|123|456|Live title|Games"))
        assertTrue(text.contains("1h 1m 1s"))
        assertTrue(text.endsWith("https://live.bilibili.com/456"))
    }

    @Test
    fun shouldAppendMentionAllForLiveStartedButNotLiveEnded() = runBlocking {
        val eventBus = EventBus()
        val (publisher, _) = seededSubscription(
            "live-at-all",
            policy = SubscriptionPolicy(
                updateSelectors = listOf(UpdateSelector.any()),
                mentionRules = listOf(
                    MentionRule(
                        selector = UpdateSelector(eventTypes = setOf(SourceEventType.LIVE_STARTED)),
                        mode = MentionMode.MENTION_ALL,
                    ),
                ),
            ),
        )
        val listener = SourceUpdateProcessor(
            config = MainDynamicConfig(
                templates = PushTemplates(
                    liveStarted = "started {title}",
                    liveEnded = "ended {title}",
                ),
            ),
            eventBus = eventBus,
        )
        val startedAt = System.currentTimeMillis() / 1000 + 60

        val startedReceived = captureMessageEvent(eventBus)
        listener.process(
            SourceUpdatePublishRequest(
                sourcePlugin = "test",
                update = liveUpdate(publisher, SourceEventType.LIVE_STARTED, startedAt),
            ),
        )
        val started = withTimeout(3_000) { startedReceived.await() }
        val startedContents = started.message.batches.single().content
        assertEquals("started Live title", startedContents.first().fallbackText)
        assertTrue(startedContents.last() is MessageContent.MentionAll)

        val endedReceived = captureMessageEvent(eventBus)
        listener.process(
            SourceUpdatePublishRequest(
                sourcePlugin = "test",
                update = liveUpdate(publisher, SourceEventType.LIVE_ENDED, startedAt + 60, startedAt),
            ),
        )
        val ended = withTimeout(3_000) { endedReceived.await() }
        assertEquals("ended Live title", ended.message.batches.single().content.single().fallbackText)
    }

    private fun seededSubscription(
        suffix: String,
        policy: SubscriptionPolicy = SubscriptionPolicy(updateSelectors = listOf(UpdateSelector.any())),
    ): Pair<Publisher, Subscriber> {
        val tempDir = createTempDirectory("dynamic-bot-main-$suffix").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)
        PublisherRepository.create(testPublisher(id = 1, name = "Demo UP"))
        SubscriberRepository.create(
            Subscriber(
                id = 10,
                address = testTargetAddress(kind = TargetKind.GROUP, externalId = "100"),
                name = "group",
                state = EntityState.ACTIVE,
                createTime = 1,
                createUser = 1,
            ),
        )
        val publisher = assertNotNull(PublisherRepository.findByKey(testPublisher().key))
        val subscriber = assertNotNull(SubscriberRepository.findByAddress(testTargetAddress(kind = TargetKind.GROUP, externalId = "100")))
        SubscriptionRepository.subscribe(subscriber.id, publisher.id, policy)
        return publisher to subscriber
    }

    private fun liveUpdate(
        publisher: Publisher,
        eventType: SourceEventType,
        time: Long,
        startedAt: Long? = null,
    ): SourceUpdate {
        return testDynamicUpdate(
            publisher = publisher.toInfo(),
            eventType = eventType,
            externalId = "live-456-${eventType.value}",
            payload = LivePayload(
                roomId = "456",
                title = "Live title",
                area = "Games",
                cover = testMedia("https://example.com/cover.png", MediaKind.COVER),
                status = if (eventType == SourceEventType.LIVE_STARTED) LiveStatus.OPEN else LiveStatus.CLOSE,
                previousStatus = if (eventType == SourceEventType.LIVE_STARTED) LiveStatus.CLOSE else LiveStatus.OPEN,
                startedAtEpochSeconds = startedAt ?: time,
                endedAtEpochSeconds = if (eventType == SourceEventType.LIVE_ENDED) time else null,
            ),
        ).copy(
            occurredAtEpochSeconds = time,
            link = "https://live.bilibili.com/456",
        )
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
}
