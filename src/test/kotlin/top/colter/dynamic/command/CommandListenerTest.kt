package top.colter.dynamic.command

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.command.CommandRegistry
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.DynamicAttachmentKind
import top.colter.dynamic.core.data.FilterCondition
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.event.CommandEvent
import top.colter.dynamic.event.CommandResultEvent
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.Listener
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PublisherFollowPlugin
import top.colter.dynamic.repository.DynamicFilterRuleRepository
import top.colter.dynamic.repository.PersistenceManager
import top.colter.dynamic.repository.PublisherRepository
import top.colter.dynamic.repository.SubscriberRepository
import top.colter.dynamic.repository.SubscriptionRepository
import top.colter.dynamic.testPublisherInfo

class CommandListenerTest {
    @Test
    fun subscribeShouldCreatePublisherSubscriberAndSubscription() = runBlocking {
        initDb("command-subscribe")
        val eventBus = EventBus()
        val plugin = FakePublisherFollowPlugin()
        val listener = CommandListener(
            publisherLookupResolver = { id -> plugin.takeIf { id == "bilibili" } },
            config = MainDynamicConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
        )

        val result = dispatch(eventBus, listener, commandEvent("/db subscribe bilibili 123"))

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertTrue(renderMessage(result).contains("已订阅：demo-up"))
        val publisher = assertNotNull(PublisherRepository.findByKey(PublisherKey.of("bilibili", externalId = "123")))
        val subscriber = assertNotNull(
            SubscriberRepository.findByAddress(TargetAddress.of("onebot", TargetKind.GROUP, "100")),
        )
        assertEquals(1, SubscriptionRepository.findPublisherIdsBySubscriberId(subscriber.id).size)
        assertEquals("123", publisher.externalId)
    }

    @Test
    fun filterAddElementShouldUseAttachmentKindCondition() = runBlocking {
        initDb("command-filter")
        seedSubscription()
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = MainDynamicConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
        )

        val result = dispatch(eventBus, listener, commandEvent("/db filter add element bilibili 123 video"))

        assertEquals(CommandStatus.SUCCESS, result.status)
        val rule = DynamicFilterRuleRepository.findAll().single()
        assertEquals(FilterCondition.HasAttachmentKind(DynamicAttachmentKind.VIDEO), rule.condition)
    }

    @Test
    fun unsubscribeShouldRemoveSubscription() = runBlocking {
        initDb("command-unsubscribe")
        seedSubscription()
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = MainDynamicConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
        )

        val result = dispatch(eventBus, listener, commandEvent("/db unsubscribe bilibili 123"))

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertTrue(renderMessage(result).contains("已取消订阅"))
        val subscriber = assertNotNull(
            SubscriberRepository.findByAddress(TargetAddress.of("onebot", TargetKind.GROUP, "100")),
        )
        assertTrue(SubscriptionRepository.findPublisherIdsBySubscriberId(subscriber.id).isEmpty())
    }

    private suspend fun dispatch(
        eventBus: EventBus,
        listener: CommandListener,
        event: CommandEvent,
    ): CommandResultEvent {
        val result = CompletableDeferred<CommandResultEvent>()
        eventBus.subscribe(
            object : Listener<CommandResultEvent> {
                override suspend fun onMessage(event: CommandResultEvent) {
                    result.complete(event)
                }
            },
        )

        listener.onMessage(event)
        return withTimeout(3_000) { result.await() }
    }

    private fun initDb(suffix: String) {
        val tempDir = createTempDirectory("dynamic-bot-command-$suffix").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)
    }

    private fun seedSubscription() {
        val publisher = PublisherRepository.upsertInfo(testPublisherInfo(name = "demo-up")).value
        val subscriber = SubscriberRepository.ensure(
            address = TargetAddress.of("onebot", TargetKind.GROUP, "100"),
            name = "100",
        )
        SubscriptionRepository.subscribe(subscriber.id, publisher.id)
    }

    private fun commandEvent(rawText: String): CommandEvent {
        return CommandEvent(
            sourcePlugin = "test",
            context = CommandContext.of(
                platform = "onebot",
                kind = TargetKind.GROUP,
                externalId = "100",
                senderId = "sender",
            ),
            rawText = rawText,
            traceId = "trace",
        )
    }

    private fun renderMessage(result: CommandResultEvent): String {
        return result.chain.flatMap { it.content }.joinToString("\n") { content ->
            when (content) {
                is MessageContent.Text -> content.fallbackText
                else -> content.fallbackText
            }
        }
    }

    private class FakePublisherFollowPlugin : PublisherFollowPlugin {
        override val platformId: PlatformId = PlatformId.of("bilibili")

        override suspend fun fetchPublisherInfo(userId: String): PublisherInfo? {
            return testPublisherInfo(
                key = PublisherKey.of(platformId = platformId.value, externalId = userId),
                name = "demo-up",
            )
        }

        override suspend fun queryFollowState(userId: String): FollowState = FollowState.FOLLOWING

        override suspend fun followPublisher(userId: String): FollowActionResult {
            return FollowActionResult(FollowActionStatus.FOLLOWED)
        }
    }
}
