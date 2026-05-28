package top.colter.dynamic.command

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.colter.dynamic.CommandConfig
import top.colter.dynamic.CommandPermissionRule
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.PushTemplates
import top.colter.dynamic.core.command.CommandRegistry
import top.colter.dynamic.core.data.ChatType
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.DynamicElementType
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PublisherProfile
import top.colter.dynamic.core.data.PublisherType
import top.colter.dynamic.core.data.SubscriberType
import top.colter.dynamic.core.event.CommandEvent
import top.colter.dynamic.core.event.CommandResultEvent
import top.colter.dynamic.core.event.EventManger
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.register
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PlatformPublisherPlugin
import top.colter.dynamic.core.plugin.PublisherLoginAccount
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.repository.DynamicFilterRuleRepository
import top.colter.dynamic.core.repository.PersistenceManager
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.SubscriberRepository
import top.colter.dynamic.core.repository.SubscriptionRepository
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
    fun `stop command should require admin and request application stop after reply`() = runBlocking {
        initDb("stop-command")
        CommandRegistry.clear()
        var stopReason: String? = null
        val userListener = CommandListener(
            platformPluginResolver = { null },
            stopRequester = { reason -> stopReason = reason },
        )

        val rejected = dispatch(
            listener = userListener,
            event = commandEvent(
                commandTokens = listOf("stop"),
            ),
        )

        assertEquals(CommandStatus.REJECTED, rejected.status)
        assertNull(stopReason)

        CommandRegistry.clear()
        val listener = CommandListener(
            platformPluginResolver = { null },
            config = adminConfig(),
            stopRequester = { reason -> stopReason = reason },
        )

        val result = dispatch(
            listener = listener,
            event = commandEvent(
                commandTokens = listOf("stop"),
            ),
        )

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertTrue(renderMessage(result).contains("stop requested"))
        assertEquals("command:onebot:100", stopReason)
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
                commandTokens = listOf("subscribe", "bilibili", "123"),
            ),
        )

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertEquals(0, plugin.followCalls)
        assertEquals(1, plugin.fetchProfileCalls)
        assertEquals(1, plugin.queryFollowCalls)
        assertEquals("bilibili", PublisherRepository.findByPlatformAndExternalId("bilibili", "123")?.platformId)
        assertEquals(1, SubscriptionRepository.findPublisherIdsBySubscriberId(subscriberId("onebot", SubscriberType.GROUP, "100")!!).size)
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
                commandTokens = listOf("subscribe", "bilibili", "123"),
            ),
        )

        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(renderMessage(result).contains("follow failed"))
        assertNull(PublisherRepository.findByPlatformAndExternalId("bilibili", "123"))
        assertNull(SubscriberRepository.findByPlatformAndTarget("onebot", SubscriberType.GROUP, "100"))
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
                commandTokens = listOf("subscribe", "bilibili", "123"),
                traceId = "trace-1",
            ),
        )
        val second = dispatch(
            listener = listener,
            event = commandEvent(
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
                commandTokens = listOf("subscribe", "bilibili", "123"),
            ),
        )

        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(renderMessage(result).contains("platform plugin not found"))
    }

    @Test
    fun `login qr should send QR image and final result automatically`() = runBlocking {
        initDb("login-qr-success")
        CommandRegistry.clear()
        val plugin = FakePlatformPublisherPlugin(
            supportedLoginMethods = setOf(PublisherLoginMethod.QR_CODE),
            qrLoginResult = PublisherLoginResult(
                status = PublisherLoginStatus.SUCCESS,
                message = "login success",
                account = PublisherLoginAccount(userId = "123", name = "demo-up"),
            ),
        )
        val listener = CommandListener(
            platformPluginResolver = { if (it == "bilibili") plugin else null },
            config = adminConfig(),
        )

        val results = dispatchMany(
            listener = listener,
            event = commandEvent(
                commandTokens = listOf("login", "bilibili", "qr"),
            ),
            expectedCount = 2,
        )

        val qrResult = results.first { renderMessage(it).contains("QR login started") }
        val finalResult = results.first { renderMessage(it).contains("bilibili login success") }
        val qrMessage = renderMessage(qrResult)

        assertEquals(CommandStatus.SUCCESS, qrResult.status)
        assertTrue(qrResult.chain.flatMap { it.content }.any { it is MessageContent.Image })
        assertTrue(!qrMessage.contains("sessionId"))
        assertTrue(!qrMessage.contains("login bilibili qr "))
        assertEquals(CommandStatus.SUCCESS, finalResult.status)
        assertTrue(renderMessage(finalResult).contains("demo-up"))
        assertEquals(1, plugin.qrLoginCalls)
    }

    @Test
    fun `login qr should send final failure automatically`() = runBlocking {
        initDb("login-qr-fail")
        CommandRegistry.clear()
        val plugin = FakePlatformPublisherPlugin(
            supportedLoginMethods = setOf(PublisherLoginMethod.QR_CODE),
            qrLoginResult = PublisherLoginResult(PublisherLoginStatus.FAILED, "QR code expired"),
        )
        val listener = CommandListener(
            platformPluginResolver = { if (it == "bilibili") plugin else null },
            config = adminConfig(),
        )

        val results = dispatchMany(
            listener = listener,
            event = commandEvent(
                commandTokens = listOf("login", "bilibili", "qr"),
            ),
            expectedCount = 2,
        )

        val finalResult = results.first { renderMessage(it).contains("QR code expired") }
        assertEquals(CommandStatus.FAILED, finalResult.status)
        assertEquals(1, plugin.qrLoginCalls)
    }

    @Test
    fun `login should reject non-admin before starting qr login`() = runBlocking {
        initDb("login-user-rejected")
        CommandRegistry.clear()
        val plugin = FakePlatformPublisherPlugin(
            supportedLoginMethods = setOf(PublisherLoginMethod.QR_CODE),
        )
        val listener = CommandListener(platformPluginResolver = { if (it == "bilibili") plugin else null })

        val result = dispatch(
            listener = listener,
            event = commandEvent(
                commandTokens = listOf("login", "bilibili", "qr"),
            ),
        )

        assertEquals(CommandStatus.REJECTED, result.status)
        assertEquals(0, plugin.qrLoginCalls)
    }

    @Test
    fun `template list should show global templates`() = runBlocking {
        initDb("template-list")
        CommandRegistry.clear()
        val listener = CommandListener(
            platformPluginResolver = { null },
            config = MainDynamicConfig(
                templates = PushTemplates(
                    dynamic = "dynamic template",
                    liveStarted = "live started template",
                    liveEnded = "live ended template",
                ),
            ),
        )

        val result = dispatch(
            listener = listener,
            event = commandEvent(
                commandTokens = listOf("template", "list"),
            ),
        )

        assertEquals(CommandStatus.SUCCESS, result.status)
        val message = renderMessage(result)
        assertTrue(message.contains("dynamic:\ndynamic template"))
        assertTrue(message.contains("liveStarted:\nlive started template"))
        assertTrue(message.contains("liveEnded:\nlive ended template"))
    }

    @Test
    fun `filter commands should add list remove and clear rules`() = runBlocking {
        initDb("filter-manage")
        CommandRegistry.clear()
        val subscriptionId = createCurrentSubscription()
        val listener = CommandListener(platformPluginResolver = { null })

        val addElement = dispatch(
            listener = listener,
            event = commandEvent(listOf("filter", "add", "element", "bilibili", "123", "image")),
        )
        val addContent = dispatch(
            listener = listener,
            event = commandEvent(listOf("filter", "add", "content", "bilibili", "123", "keyword", "spoiler", "phrase")),
        )
        val list = dispatch(
            listener = listener,
            event = commandEvent(listOf("filter", "list", "bilibili", "123")),
        )

        assertEquals(CommandStatus.SUCCESS, addElement.status)
        assertTrue(renderMessage(addElement).contains("filter rule created"))
        assertEquals(CommandStatus.SUCCESS, addContent.status)
        assertTrue(renderMessage(addContent).contains("spoiler phrase"))
        assertEquals(2, DynamicFilterRuleRepository.findBySubscriptionId(subscriptionId).size)
        assertTrue(renderMessage(list).contains("element has_element image"))
        assertTrue(renderMessage(list).contains("content keyword spoiler phrase"))

        val imageRule = DynamicFilterRuleRepository.findBySubscriptionId(subscriptionId).single { it.value == "IMAGE" }
        val remove = dispatch(
            listener = listener,
            event = commandEvent(listOf("filter", "remove", imageRule.id.toString())),
        )
        val clear = dispatch(
            listener = listener,
            event = commandEvent(listOf("filter", "clear", "bilibili", "123")),
        )

        assertEquals(CommandStatus.SUCCESS, remove.status)
        assertEquals(CommandStatus.SUCCESS, clear.status)
        assertTrue(renderMessage(clear).contains("count=1"))
        assertTrue(DynamicFilterRuleRepository.findBySubscriptionId(subscriptionId).isEmpty())
    }

    @Test
    fun `filter add should reject invalid element regex and unsubscribed publisher`() = runBlocking {
        initDb("filter-invalid")
        CommandRegistry.clear()
        createPublisher()
        val listener = CommandListener(platformPluginResolver = { null })

        val notSubscribed = dispatch(
            listener = listener,
            event = commandEvent(listOf("filter", "add", "element", "bilibili", "123", "image")),
        )
        createCurrentSubscription()
        val unknownElement = dispatch(
            listener = listener,
            event = commandEvent(listOf("filter", "add", "element", "bilibili", "123", "unknown")),
        )
        val invalidRegex = dispatch(
            listener = listener,
            event = commandEvent(listOf("filter", "add", "content", "bilibili", "123", "regex", "[")),
        )

        assertEquals(CommandStatus.FAILED, notSubscribed.status)
        assertTrue(renderMessage(notSubscribed).contains("not subscribed"))
        assertEquals(CommandStatus.FAILED, unknownElement.status)
        assertTrue(renderMessage(unknownElement).contains("unknown dynamic element"))
        assertEquals(CommandStatus.FAILED, invalidRegex.status)
        assertTrue(renderMessage(invalidRegex).contains("filter rule rejected"))
    }

    @Test
    fun `filter remove should only remove rules for current target`() = runBlocking {
        initDb("filter-remove-owner")
        CommandRegistry.clear()
        val publisher = createPublisher()
        val currentSubscriber = SubscriberRepository.upsert(
            platformId = "onebot",
            targetId = "100",
            name = "current",
            type = SubscriberType.GROUP,
        ).value
        val otherSubscriber = SubscriberRepository.upsert(
            platformId = "onebot",
            targetId = "200",
            name = "other",
            type = SubscriberType.GROUP,
        ).value
        SubscriptionRepository.subscribe(currentSubscriber.id, publisher.id)
        SubscriptionRepository.subscribe(otherSubscriber.id, publisher.id)
        val otherSubscription = SubscriptionRepository.findBySubscriberAndPublisher(otherSubscriber.id, publisher.id)!!
        val otherRule = DynamicFilterRuleRepository.addElementRule(otherSubscription.id, DynamicElementType.TEXT).value
        val listener = CommandListener(platformPluginResolver = { null })

        val result = dispatch(
            listener = listener,
            event = commandEvent(listOf("filter", "remove", otherRule.id.toString())),
        )

        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(renderMessage(result).contains("filter rule not found"))
        assertEquals(otherRule, DynamicFilterRuleRepository.findById(otherRule.id))
    }

    private suspend fun dispatch(listener: CommandListener, event: CommandEvent): CommandResultEvent {
        return dispatchMany(listener, event, expectedCount = 1).single()
    }

    private suspend fun dispatchMany(
        listener: CommandListener,
        event: CommandEvent,
        expectedCount: Int,
    ): List<CommandResultEvent> {
        EventManger.shutdown()
        val received = CompletableDeferred<List<CommandResultEvent>>()
        val results = mutableListOf<CommandResultEvent>()
        object : Listener<CommandResultEvent> {
            override suspend fun onMessage(event: CommandResultEvent) {
                results += event
                if (results.size >= expectedCount && !received.isCompleted) {
                    received.complete(results.toList())
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
            ),
            rawText = "/db ${commandTokens.joinToString(" ")}",
            traceId = traceId,
        )
    }

    private fun adminConfig(): MainDynamicConfig {
        return MainDynamicConfig(
            command = CommandConfig(
                permissions = listOf(
                    CommandPermissionRule(
                        platform = "onebot",
                        senderId = "sender",
                        role = CommandRole.ADMIN,
                    )
                )
            ),
        )
    }

    private fun renderMessage(result: CommandResultEvent): String {
        return result.chain.flatMap { it.content }.joinToString("\n") { it.fallbackText }
    }

    private fun subscriberId(platformId: String, type: SubscriberType, targetId: String): Int? {
        return SubscriberRepository.findByPlatformAndTarget(platformId, type, targetId)?.id
    }

    private fun createPublisher(): top.colter.dynamic.core.data.Publisher {
        PublisherRepository.create(
            top.colter.dynamic.core.data.Publisher(
                id = 1,
                platformId = "bilibili",
                type = PublisherType.USER,
                externalId = "123",
                name = "demo-up",
                state = EntityState.ACTIVE,
                face = LazyImage("https://example.com/face.png"),
                createTime = 1L,
                createUser = 1,
            )
        )
        return PublisherRepository.findByPlatformAndExternalId("bilibili", "123")!!
    }

    private fun createCurrentSubscription(): Int {
        val publisher = PublisherRepository.findByPlatformAndExternalId("bilibili", "123") ?: createPublisher()
        val subscriber = SubscriberRepository.upsert(
            platformId = "onebot",
            targetId = "100",
            name = "group",
            type = SubscriberType.GROUP,
        ).value
        SubscriptionRepository.subscribe(subscriber.id, publisher.id)
        return SubscriptionRepository.findBySubscriberAndPublisher(subscriber.id, publisher.id)!!.id
    }

    private class FakePlatformPublisherPlugin(
        private val profile: PublisherProfile = PublisherProfile(
            platformId = "bilibili",
            externalId = "123",
            type = PublisherType.USER,
            name = "demo-up",
            official = null,
            state = EntityState.ACTIVE,
            face = LazyImage("https://example.com/face.png"),
            header = null,
            pendant = null,
        ),
        private val followState: FollowState = FollowState.FOLLOWING,
        private val followActionResult: FollowActionResult = FollowActionResult(FollowActionStatus.FOLLOWED),
        override val supportedLoginMethods: Set<PublisherLoginMethod> = emptySet(),
        private val qrLoginResult: PublisherLoginResult = PublisherLoginResult(
            PublisherLoginStatus.SUCCESS,
            "login success",
        ),
    ) : PlatformPublisherPlugin {
        override val platformId: String = "bilibili"

        var fetchProfileCalls: Int = 0
        var queryFollowCalls: Int = 0
        var followCalls: Int = 0
        var qrLoginCalls: Int = 0

        override suspend fun fetchPublisherProfile(userId: String): PublisherProfile? {
            fetchProfileCalls += 1
            return if (userId == profile.externalId) profile else null
        }

        override suspend fun queryFollowState(userId: String): FollowState {
            queryFollowCalls += 1
            return followState
        }

        override suspend fun followPublisher(userId: String): FollowActionResult {
            followCalls += 1
            return followActionResult
        }

        override suspend fun loginByQrCode(
            onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
            onStatusChanged: suspend (PublisherLoginResult) -> Unit,
        ): PublisherLoginResult {
            qrLoginCalls += 1
            onQrCode(
                PublisherQrLoginChallenge(
                    qrContent = "https://example.com/login-qr",
                    expiresAtEpochSeconds = 1710000000,
                    message = "scan me",
                )
            )
            onStatusChanged(PublisherLoginResult(PublisherLoginStatus.PENDING, "waiting for scan"))
            return qrLoginResult
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
