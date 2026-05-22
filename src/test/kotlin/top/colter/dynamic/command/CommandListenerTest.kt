package top.colter.dynamic.command

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.colter.dynamic.core.command.CommandRegistry
import top.colter.dynamic.core.data.ChatType
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.PublisherProfile
import top.colter.dynamic.core.data.PublisherType
import top.colter.dynamic.core.event.CommandEvent
import top.colter.dynamic.core.event.CommandResultEvent
import top.colter.dynamic.core.event.EventManger
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.register
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PlatformPublisherPlugin
import top.colter.dynamic.core.repository.PersistenceManager
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.PublisherTemplateRepository
import top.colter.dynamic.core.repository.SubscriberRepository
import top.colter.dynamic.core.repository.SubscribeRepository
import top.colter.dynamic.MainDynamicConfig
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandListenerTest {

    @AfterTest
    fun cleanup() {
        EventManger.shutdown()
        CommandRegistry.clear()
    }

    @Test
    fun `help should hide admin commands for user role`() = runBlocking {
        initDb("help")
        CommandRegistry.clear()
        val listener = CommandListener(platformPluginResolver = { null })

        val result = dispatch(
            listener = listener,
            event = commandEvent(
                role = CommandRole.USER,
                commandName = "help",
                args = emptyList(),
                commandTokens = listOf("help"),
            ),
        )

        assertEquals(CommandStatus.SUCCESS, result.status)
        val message = renderMessage(result)
        assertTrue(message.contains("/db help"))
        assertTrue(message.contains("/db subscribe <platform> <publisherUserId>"))
        assertTrue(!message.contains("/db status"))
    }

    @Test
    fun `subscribe should create publisher and subscription when already following`() = runBlocking {
        initDb("subscribe-following")
        CommandRegistry.clear()
        val plugin = FakePlatformPublisherPlugin(
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FOLLOWED),
        )
        val listener = CommandListener(platformPluginResolver = { if (it == "bilibili") plugin else null })

        val result = dispatch(
            listener = listener,
            event = commandEvent(
                commandName = "subscribe",
                args = listOf("bilibili", "123"),
                commandTokens = listOf("subscribe", "bilibili", "123"),
            ),
        )

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertEquals(0, plugin.followCalls)
        assertEquals(1, plugin.fetchProfileCalls)
        assertEquals(1, plugin.queryFollowCalls)
        assertEquals("bilibili", PublisherRepository.findByPlatformAndUserId("bilibili", "123")?.platform)
        assertEquals(1, SubscribeRepository.findPublisherIdsBySubscriberId(subscriberId("onebot", "sender")!!).size)
        val message = renderMessage(result)
        assertTrue(message.contains("auto-followed=no"))
        assertTrue(message.contains("publisher=new"))
        assertTrue(message.contains("subscription=new"))
    }

    @Test
    fun `subscribe should auto follow before creating subscription`() = runBlocking {
        initDb("subscribe-auto-follow")
        CommandRegistry.clear()
        val plugin = FakePlatformPublisherPlugin(
            followState = FollowState.NOT_FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FOLLOWED),
        )
        val listener = CommandListener(platformPluginResolver = { if (it == "bilibili") plugin else null })

        val result = dispatch(
            listener = listener,
            event = commandEvent(
                commandName = "subscribe",
                args = listOf("bilibili", "123"),
                commandTokens = listOf("subscribe", "bilibili", "123"),
            ),
        )

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertEquals(1, plugin.followCalls)
        assertTrue(renderMessage(result).contains("auto-followed=yes"))
    }

    @Test
    fun `subscribe should stop when auto follow fails`() = runBlocking {
        initDb("subscribe-follow-fail")
        CommandRegistry.clear()
        val plugin = FakePlatformPublisherPlugin(
            followState = FollowState.NOT_FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FAILED, "follow failed"),
        )
        val listener = CommandListener(platformPluginResolver = { if (it == "bilibili") plugin else null })

        val result = dispatch(
            listener = listener,
            event = commandEvent(
                commandName = "subscribe",
                args = listOf("bilibili", "123"),
                commandTokens = listOf("subscribe", "bilibili", "123"),
            ),
        )

        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(renderMessage(result).contains("follow failed"))
        assertNull(PublisherRepository.findByPlatformAndUserId("bilibili", "123"))
        assertNull(SubscriberRepository.findByPlatformAndUserId("onebot", "sender"))
    }

    @Test
    fun `subscribe should be idempotent for existing subscription`() = runBlocking {
        initDb("subscribe-idempotent")
        CommandRegistry.clear()
        val plugin = FakePlatformPublisherPlugin(
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FOLLOWED),
        )
        val listener = CommandListener(platformPluginResolver = { if (it == "bilibili") plugin else null })

        dispatch(
            listener = listener,
            event = commandEvent(
                commandName = "subscribe",
                args = listOf("bilibili", "123"),
                commandTokens = listOf("subscribe", "bilibili", "123"),
                traceId = "trace-1",
            ),
        )
        val second = dispatch(
            listener = listener,
            event = commandEvent(
                commandName = "subscribe",
                args = listOf("bilibili", "123"),
                commandTokens = listOf("subscribe", "bilibili", "123"),
                traceId = "trace-2",
            ),
        )

        assertEquals(CommandStatus.SUCCESS, second.status)
        val message = renderMessage(second)
        assertTrue(message.contains("publisher=existing"))
        assertTrue(message.contains("subscription=existing"))
    }

    @Test
    fun `subscribe should fail when platform plugin is missing`() = runBlocking {
        initDb("subscribe-missing-plugin")
        CommandRegistry.clear()
        val listener = CommandListener(platformPluginResolver = { null })

        val result = dispatch(
            listener = listener,
            event = commandEvent(
                commandName = "subscribe",
                args = listOf("bilibili", "123"),
                commandTokens = listOf("subscribe", "bilibili", "123"),
            ),
        )

        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(renderMessage(result).contains("platform plugin not found"))
    }

    @Test
    fun `template set should bind publisher to existing template`() = runBlocking {
        initDb("template-set")
        CommandRegistry.clear()
        val publisher = createPublisher()
        val listener = CommandListener(
            platformPluginResolver = { null },
            config = MainDynamicConfig(templates = mapOf("default" to "default", "bili-video" to "video")),
        )

        val result = dispatch(
            listener = listener,
            event = commandEvent(
                role = CommandRole.ADMIN,
                commandName = "set",
                args = listOf("bilibili", "123", "bili-video"),
                commandTokens = listOf("template", "set", "bilibili", "123", "bili-video"),
            ),
        )

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertEquals("bili-video", PublisherTemplateRepository.findTemplateNameByPublisherId(publisher.id.toString()))
        assertTrue(renderMessage(result).contains("template binding updated"))
    }

    @Test
    fun `template set should fail when template is missing`() = runBlocking {
        initDb("template-set-missing")
        CommandRegistry.clear()
        createPublisher()
        val listener = CommandListener(
            platformPluginResolver = { null },
            config = MainDynamicConfig(templates = mapOf("default" to "default")),
        )

        val result = dispatch(
            listener = listener,
            event = commandEvent(
                role = CommandRole.ADMIN,
                commandName = "set",
                args = listOf("bilibili", "123", "missing"),
                commandTokens = listOf("template", "set", "bilibili", "123", "missing"),
            ),
        )

        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(renderMessage(result).contains("template not found"))
    }

    @Test
    fun `template remove should delete binding`() = runBlocking {
        initDb("template-remove")
        CommandRegistry.clear()
        val publisher = createPublisher()
        PublisherTemplateRepository.setTemplate(publisher.id.toString(), "bili-video")
        val listener = CommandListener(platformPluginResolver = { null })

        val result = dispatch(
            listener = listener,
            event = commandEvent(
                role = CommandRole.ADMIN,
                commandName = "remove",
                args = listOf("bilibili", "123"),
                commandTokens = listOf("template", "remove", "bilibili", "123"),
            ),
        )

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertNull(PublisherTemplateRepository.findTemplateNameByPublisherId(publisher.id.toString()))
    }

    @Test
    fun `template list should show configured templates and bindings`() = runBlocking {
        initDb("template-list")
        CommandRegistry.clear()
        val publisher = createPublisher()
        PublisherTemplateRepository.setTemplate(publisher.id.toString(), "bili-video")
        val listener = CommandListener(
            platformPluginResolver = { null },
            config = MainDynamicConfig(templates = mapOf("default" to "default", "bili-video" to "video")),
        )

        val result = dispatch(
            listener = listener,
            event = commandEvent(
                commandName = "list",
                args = emptyList(),
                commandTokens = listOf("template", "list"),
            ),
        )

        assertEquals(CommandStatus.SUCCESS, result.status)
        val message = renderMessage(result)
        assertTrue(message.contains("templates: bili-video, default"))
        assertTrue(message.contains("bilibili:123"))
        assertTrue(message.contains("-> bili-video"))
    }

    private suspend fun dispatch(listener: CommandListener, event: CommandEvent): CommandResultEvent {
        EventManger.shutdown()
        val received = CompletableDeferred<CommandResultEvent>()
        object : Listener<CommandResultEvent> {
            override suspend fun onMessage(event: CommandResultEvent) {
                if (!received.isCompleted) {
                    received.complete(event)
                }
            }
        }.register<CommandResultEvent>()

        listener.onMessage(event)
        return withTimeout(3_000) { received.await() }
    }

    private fun initDb(suffix: String) {
        val tempDir = createTempDirectory("dynamic-bot-main-$suffix").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)
    }

    private fun commandEvent(
        role: CommandRole = CommandRole.USER,
        commandName: String,
        args: List<String>,
        commandTokens: List<String>,
        traceId: String = "trace",
    ): CommandEvent {
        return CommandEvent(
            sourcePlugin = "test",
            context = CommandContext(
                platform = "onebot",
                chatType = ChatType.GROUP,
                chatId = "100",
                senderId = "sender",
                role = role,
            ),
            rawText = "/db ${commandTokens.joinToString(" ")}",
            commandName = commandName,
            args = args,
            traceId = traceId,
            commandTokens = commandTokens,
        )
    }

    private fun renderMessage(result: CommandResultEvent): String {
        return result.chain.flatMap { it.content }.joinToString("\n") { it.text }
    }

    private fun subscriberId(platform: String, userId: String): String? {
        return SubscriberRepository.findByPlatformAndUserId(platform, userId)?.id?.toString()
    }

    private fun createPublisher(): top.colter.dynamic.core.data.Publisher {
        PublisherRepository.create(
            top.colter.dynamic.core.data.Publisher(
                id = 1,
                platform = "bilibili",
                userId = "123",
                name = "demo-up",
                state = 1,
                face = LazyImage("https://example.com/face.png"),
                createTime = 1L,
                createUser = 1,
            )
        )
        return PublisherRepository.findByPlatformAndUserId("bilibili", "123")!!
    }

    private class FakePlatformPublisherPlugin(
        private val profile: PublisherProfile = PublisherProfile(
            platform = "bilibili",
            userId = "123",
            type = PublisherType.USER,
            name = "demo-up",
            official = null,
            state = 1,
            face = LazyImage("https://example.com/face.png"),
            header = null,
            pendant = null,
        ),
        private val followState: FollowState,
        private val followActionResult: FollowActionResult,
    ) : PlatformPublisherPlugin {
        override val platformId: String = "bilibili"

        var fetchProfileCalls: Int = 0
        var queryFollowCalls: Int = 0
        var followCalls: Int = 0

        override suspend fun fetchPublisherProfile(userId: String): PublisherProfile? {
            fetchProfileCalls += 1
            return if (userId == profile.userId) profile else null
        }

        override suspend fun queryFollowState(userId: String): FollowState {
            queryFollowCalls += 1
            return followState
        }

        override suspend fun followPublisher(userId: String): FollowActionResult {
            followCalls += 1
            return followActionResult
        }

        override fun init() {
        }

        override fun start() {
        }

        override fun stop() {
        }

        override fun cleanup() {
        }
    }
}
