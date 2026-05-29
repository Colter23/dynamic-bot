package top.colter.dynamic.command

import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.command.CommandRegistry
import top.colter.dynamic.core.data.ChatType
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.DynamicAttachmentKind
import top.colter.dynamic.core.data.FilterCondition
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.event.CommandEvent
import top.colter.dynamic.core.event.CommandResultEvent
import top.colter.dynamic.core.event.EventManger
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.register
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PlatformPublisherPlugin
import top.colter.dynamic.core.repository.DynamicFilterRuleRepository
import top.colter.dynamic.core.repository.PersistenceManager
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.SubscriberRepository
import top.colter.dynamic.core.repository.SubscriptionRepository
import top.colter.dynamic.testPublisherInfo

class CommandListenerTest {
    @AfterTest
    fun cleanup() {
        EventManger.shutdown()
        CommandRegistry.clear()
    }

    @Test
    fun subscribeShouldCreatePublisherSubscriberAndSubscription() = runBlocking {
        initDb("command-subscribe")
        val plugin = FakePlatformPublisherPlugin()
        val listener = CommandListener(
            platformPluginResolver = { id -> plugin.takeIf { id == "bilibili" } },
            config = MainDynamicConfig(),
        )

        val result = dispatch(listener, commandEvent("/db subscribe bilibili 123"))

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertTrue(renderMessage(result).contains("subscribed: demo-up"))
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
        val listener = CommandListener(platformPluginResolver = { null }, config = MainDynamicConfig())

        val result = dispatch(listener, commandEvent("/db filter add element bilibili 123 video"))

        assertEquals(CommandStatus.SUCCESS, result.status)
        val rule = DynamicFilterRuleRepository.findAll().single()
        assertEquals(FilterCondition.HasAttachmentKind(DynamicAttachmentKind.VIDEO), rule.condition)
    }

    @Test
    fun unsubscribeShouldRemoveSubscription() = runBlocking {
        initDb("command-unsubscribe")
        seedSubscription()
        val listener = CommandListener(
            platformPluginResolver = { null },
            config = MainDynamicConfig(),
        )

        val result = dispatch(listener, commandEvent("/db unsubscribe bilibili 123"))

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertTrue(renderMessage(result).contains("unsubscribed"))
        val subscriber = assertNotNull(
            SubscriberRepository.findByAddress(TargetAddress.of("onebot", TargetKind.GROUP, "100")),
        )
        assertTrue(SubscriptionRepository.findPublisherIdsBySubscriberId(subscriber.id).isEmpty())
    }

    private suspend fun dispatch(listener: CommandListener, event: CommandEvent): CommandResultEvent {
        EventManger.shutdown()
        val result = CompletableDeferred<CommandResultEvent>()
        object : Listener<CommandResultEvent> {
            override suspend fun onMessage(event: CommandResultEvent) {
                result.complete(event)
            }
        }.register<CommandResultEvent>()

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
            context = CommandContext(
                platform = "onebot",
                chatType = ChatType.GROUP,
                chatId = "100",
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

    private class FakePlatformPublisherPlugin : PlatformPublisherPlugin {
        override val platformId: String = "bilibili"

        override suspend fun fetchPublisherInfo(userId: String): PublisherInfo? {
            return testPublisherInfo(
                key = PublisherKey.of(platformId = platformId, externalId = userId),
                name = "demo-up",
            )
        }

        override suspend fun queryFollowState(userId: String): FollowState = FollowState.FOLLOWING

        override suspend fun followPublisher(userId: String): FollowActionResult {
            return FollowActionResult(FollowActionStatus.FOLLOWED)
        }

        override fun init() {}
        override fun start() {}
        override fun stop() {}
        override fun cleanup() {}
    }
}
