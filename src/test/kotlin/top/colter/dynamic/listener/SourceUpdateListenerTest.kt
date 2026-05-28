package top.colter.dynamic.listener

import java.nio.file.Paths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.PushTemplates
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.LiveChange
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.LiveStatusUpdate
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
import top.colter.dynamic.core.repository.PersistenceManager
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.SubscriberRepository
import top.colter.dynamic.core.repository.SubscriptionRepository
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceUpdateListenerTest {
    @AfterTest
    fun cleanup() {
        EventManger.shutdown()
    }

    @Test
    fun shouldRenderLiveStartedTemplateWithDrawCoverAndSplitChains() = runBlocking {
        val (publisher, subscriber) = seededSubscription("live-started")
        val listener = SourceUpdateListener(
            config = MainDynamicConfig(
                templates = PushTemplates(
                    liveStarted = "{draw}\\n{name}|{uid}|{rid}|{title}|{area}\\n{cover}\\n{link}\\rnext",
                ),
            ),
            imageLoader = DynamicImageLoader { },
            imageRenderer = DynamicImageRenderer { Paths.get("D:/tmp/live-started.png") },
        )
        val received = captureMessageEvent()
        val startedAt = System.currentTimeMillis() / 1000 + 60

        listener.onMessage(
            SourceUpdateEvent(
                source = "test",
                update = liveUpdate(publisher, LiveChange.STARTED, startedAt, null),
            )
        )
        val event = withTimeout(3_000) { received.await() }

        assertEquals(listOf(subscriber.toMessageTarget()), event.message.targets)
        assertEquals(2, event.message.chain.size)
        val first = event.message.chain.first().content
        assertEquals(2, first.filterIsInstance<MessageContent.Image>().size)
        assertEquals(
            "D:\\tmp\\live-started.png",
            first.filterIsInstance<MessageContent.Image>().first().image.uri,
        )
        val text = first.filterIsInstance<MessageContent.Text>().joinToString("") { it.fallbackText }
        assertTrue(text.contains("Demo UP|123|456|Live title|Games"))
        assertTrue(text.contains("https://live.bilibili.com/456"))
        assertEquals("next", event.message.chain[1].content.single().fallbackText)
    }

    @Test
    fun shouldRenderLiveEndedDurationPlaceholders() = runBlocking {
        val (publisher, subscriber) = seededSubscription("live-ended")
        val listener = SourceUpdateListener(
            config = MainDynamicConfig(
                templates = PushTemplates(
                    liveEnded = "{name}|{uid}|{rid}|{title}|{area}|{startTime}|{endTime}|{duration}|{link}",
                ),
            ),
        )
        val received = captureMessageEvent()
        val start = System.currentTimeMillis() / 1000 + 60
        val end = start + 3_661

        listener.onMessage(
            SourceUpdateEvent(
                source = "test",
                update = liveUpdate(publisher, LiveChange.ENDED, end, start),
            )
        )
        val event = withTimeout(3_000) { received.await() }

        assertEquals(listOf(subscriber.toMessageTarget()), event.message.targets)
        val text = event.message.chain.single().content.filterIsInstance<MessageContent.Text>().single().fallbackText
        assertTrue(text.contains("Demo UP|123|456|Live title|Games"))
        assertTrue(text.contains("1小时 1分 1秒"))
        assertTrue(text.endsWith("https://live.bilibili.com/456"))
    }

    @Test
    fun shouldAppendMentionAllForLiveStartedButNotLiveEnded() = runBlocking {
        val (publisher, subscriber) = seededSubscription("live-at-all")
        val subscription = SubscriptionRepository.findBySubscriberAndPublisher(subscriber.id, publisher.id)!!
        SubscriptionRepository.updateAtAllTypes(subscription.id, setOf(SubscriptionAtAllType.LIVE))
        val listener = SourceUpdateListener(
            config = MainDynamicConfig(
                templates = PushTemplates(
                    liveStarted = "started {title}",
                    liveEnded = "ended {title}",
                ),
            ),
        )
        val startedAt = System.currentTimeMillis() / 1000 + 60

        val startedReceived = captureMessageEvent()
        listener.onMessage(
            SourceUpdateEvent(
                source = "test",
                update = liveUpdate(publisher, LiveChange.STARTED, startedAt, null),
            )
        )
        val started = withTimeout(3_000) { startedReceived.await() }
        val startedContents = started.message.chain.single().content
        assertEquals("started Live title", startedContents.first().fallbackText)
        assertTrue(startedContents.last() is MessageContent.MentionAll)

        val endedReceived = captureMessageEvent()
        listener.onMessage(
            SourceUpdateEvent(
                source = "test",
                update = liveUpdate(publisher, LiveChange.ENDED, startedAt + 60, startedAt),
            )
        )
        val ended = withTimeout(3_000) { endedReceived.await() }
        assertEquals("ended Live title", ended.message.chain.single().content.single().fallbackText)
    }

    private fun seededSubscription(suffix: String): Pair<Publisher, Subscriber> {
        val tempDir = createTempDirectory("dynamic-bot-main-$suffix").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)
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
        SubscriberRepository.create(
            Subscriber(
                id = 10,
                platformId = "onebot",
                type = SubscriberType.GROUP,
                targetId = "100",
                name = "group",
                state = EntityState.ACTIVE,
                createTime = 1,
                createUser = 1,
            )
        )
        val publisher = assertNotNull(PublisherRepository.findByPlatformAndExternalId("bilibili", "123"))
        val subscriber = assertNotNull(
            SubscriberRepository.findByPlatformAndTarget("onebot", SubscriberType.GROUP, "100")
        )
        SubscriptionRepository.subscribe(subscriber.id, publisher.id)
        return publisher to subscriber
    }

    private fun liveUpdate(
        publisher: Publisher,
        change: LiveChange,
        time: Long,
        startedAt: Long?,
    ): LiveStatusUpdate {
        return LiveStatusUpdate(
            platform = PlatformDescriptor(
                id = "bilibili",
                name = "Bilibili",
                homepage = "https://www.bilibili.com",
                iconUri = "",
                kind = PlatformKind.PUBLISHER,
            ),
            publisher = publisher,
            roomId = "456",
            time = time,
            title = "Live title",
            area = "Games",
            cover = LazyImage("https://example.com/cover.png"),
            link = "https://live.bilibili.com/456",
            status = if (change == LiveChange.STARTED) LiveStatus.OPEN else LiveStatus.CLOSE,
            previousStatus = if (change == LiveChange.STARTED) LiveStatus.CLOSE else LiveStatus.OPEN,
            change = change,
            startedAt = startedAt ?: time,
            endedAt = if (change == LiveChange.ENDED) time else null,
        )
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
}
