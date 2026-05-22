package top.colter.dynamic.listener

import java.nio.file.Paths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherPlatform
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.SubscriberType
import top.colter.dynamic.core.event.DynamicEvent
import top.colter.dynamic.core.event.EventManger
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.MessageEvent
import top.colter.dynamic.core.event.register
import top.colter.dynamic.core.repository.PersistenceManager
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.PublisherTemplateRepository
import top.colter.dynamic.core.repository.SubscriberRepository
import top.colter.dynamic.core.repository.SubscribeRepository
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DynamicListenerTest {

    @AfterTest
    fun cleanup() {
        EventManger.shutdown()
    }

    @Test
    fun shouldConvertDynamicEventToMessageEventWithBoundTemplateAndImage() = runBlocking {
        initDb("dynamic-listener-bound-template")
        val publisher = createPublisher()
        val subscriber = createSubscriber()
        SubscribeRepository.subscribe(subscriber.id.toString(), publisher.id.toString())
        PublisherTemplateRepository.setTemplate(publisher.id.toString(), "bili-video")

        val listener = DynamicListener(
            config = MainDynamicConfig(
                templates = mapOf(
                    "default" to "default {publisher.name}",
                    "bili-video" to "video {publisher.name} {dynamic.text}",
                ),
            ),
            imageLoader = DynamicImageLoader { },
            imageRenderer = DynamicImageRenderer { Paths.get("D:/tmp/dynamic.png") },
        )

        val received = captureMessageEvent()
        listener.onMessage(DynamicEvent(source = "test", dynamic = demoDynamic(publisher)))
        val event = withTimeout(3_000) { received.await() }

        assertEquals(listOf(subscriber), event.message.subscriber)
        val contents = event.message.chain.single().content
        assertTrue(contents.first() is MessageContent.Image)
        assertEquals("D:\\tmp\\dynamic.png", (contents.first() as MessageContent.Image).image.url)
        assertEquals("video Demo UP Demo content", contents.filterIsInstance<MessageContent.Text>().single().text)
    }

    @Test
    fun shouldFallbackToTextWhenDrawFails() = runBlocking {
        initDb("dynamic-listener-draw-fail")
        val publisher = createPublisher()
        val subscriber = createSubscriber()

        val listener = DynamicListener(
            config = MainDynamicConfig(templates = mapOf("default" to "fallback {publisher.name}")),
            imageLoader = DynamicImageLoader { },
            imageRenderer = DynamicImageRenderer { error("draw failed") },
        )

        val received = captureMessageEvent()
        listener.onMessage(DynamicEvent(source = "test", target = subscriber, dynamic = demoDynamic(publisher)))
        val event = withTimeout(3_000) { received.await() }

        val contents = event.message.chain.single().content
        assertEquals(1, contents.size)
        assertEquals("fallback Demo UP", contents.single().text)
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

    private fun initDb(suffix: String) {
        val tempDir = createTempDirectory("dynamic-bot-main-$suffix").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)
    }

    private fun createPublisher(): Publisher {
        PublisherRepository.create(
            Publisher(
                id = 1,
                platform = "bilibili",
                userId = "123",
                name = "Demo UP",
                state = 1,
                face = LazyImage("https://example.com/face.png"),
                createTime = 1,
                createUser = 1,
            )
        )
        return assertNotNull(PublisherRepository.findByPlatformAndUserId("bilibili", "123"))
    }

    private fun createSubscriber(): Subscriber {
        SubscriberRepository.create(
            Subscriber(
                id = 10,
                platform = "onebot",
                type = SubscriberType.GROUP,
                userId = "100",
                name = "group",
                state = 1,
                createTime = 1,
                createUser = 1,
            )
        )
        return assertNotNull(SubscriberRepository.findByPlatformAndUserId("onebot", "100"))
    }

    private fun demoDynamic(publisher: Publisher): Dynamic {
        return Dynamic(
            platform = PublisherPlatform("bilibili", "BiliBili", "https://www.bilibili.com", ""),
            dynamicId = "dynamic-1",
            publisher = publisher,
            time = 1_710_000_000,
            link = "https://t.bilibili.com/dynamic-1",
            content = DynamicContent("Demo content", listOf(DynamicContentNodeText("Demo content"))),
        )
    }
}
