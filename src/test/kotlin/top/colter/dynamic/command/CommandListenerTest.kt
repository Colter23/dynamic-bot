package top.colter.dynamic.command

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import top.colter.dynamic.LinkParseTriggerMode
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.command.CommandRegistry
import top.colter.dynamic.core.command.CommandPermissionRule
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.DynamicBlockKind
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
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.draw.PublisherThemeInitializer
import top.colter.dynamic.repository.DynamicFilterRuleRepository
import top.colter.dynamic.repository.LinkParseTargetConfigRepository
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.repository.PersistenceManager
import top.colter.dynamic.repository.PublisherDrawThemeRepository
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
            config = managerConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
            publisherThemeInitializer = PublisherThemeInitializer { _, _ -> },
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
        assertEquals(0, plugin.queryFollowStateCalls)
        assertEquals(1, plugin.followPublisherCalls)
    }

    @Test
    fun subscribeShouldWarnButSucceedWithoutFollowPlugin() = runBlocking {
        initDb("command-subscribe-no-follow-plugin")
        val eventBus = EventBus()
        val plugin = FakePublisherLookupPlugin()
        val listener = CommandListener(
            publisherLookupResolver = { id -> plugin.takeIf { id == "bilibili" } },
            config = managerConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
            publisherThemeInitializer = PublisherThemeInitializer { _, _ -> },
        )

        val result = dispatch(eventBus, listener, commandEvent("/db subscribe bilibili 123"))

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertTrue(renderMessage(result).contains("自动关注提示=未找到发布者关注插件：bilibili"))
        val subscriber = assertNotNull(
            SubscriberRepository.findByAddress(TargetAddress.of("onebot", TargetKind.GROUP, "100")),
        )
        assertEquals(1, SubscriptionRepository.findPublisherIdsBySubscriberId(subscriber.id).size)
    }

    @Test
    fun filterAddElementShouldUseAttachmentKindCondition() = runBlocking {
        initDb("command-filter")
        seedSubscription()
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = managerConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
            publisherThemeInitializer = PublisherThemeInitializer { _, _ -> },
        )

        val result = dispatch(eventBus, listener, commandEvent("/db filter add element bilibili 123 video"))

        assertEquals(CommandStatus.SUCCESS, result.status)
        val rule = DynamicFilterRuleRepository.findAll().single()
        assertEquals(FilterCondition.HasElement(DynamicBlockKind.VIDEO), rule.condition)
    }

    @Test
    fun unsubscribeShouldRemoveSubscription() = runBlocking {
        initDb("command-unsubscribe")
        seedSubscription()
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = managerConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
            publisherThemeInitializer = PublisherThemeInitializer { _, _ -> },
        )

        val result = dispatch(eventBus, listener, commandEvent("/db unsubscribe bilibili 123"))

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertTrue(renderMessage(result).contains("已取消订阅"))
        val subscriber = assertNotNull(
            SubscriberRepository.findByAddress(TargetAddress.of("onebot", TargetKind.GROUP, "100")),
        )
        assertTrue(SubscriptionRepository.findPublisherIdsBySubscriberId(subscriber.id).isEmpty())
    }

    @Test
    fun themeSetShowAndClearShouldUpdatePublisherTheme() = runBlocking {
        initDb("command-theme")
        seedSubscription()
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = managerConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
            publisherThemeInitializer = PublisherThemeInitializer { _, _ -> },
        )

        val setResult = dispatch(eventBus, listener, commandEvent("/db theme set bilibili 123 #FE65A6;#BFFAFF"))
        val publisher = assertNotNull(PublisherRepository.findByKey(PublisherKey.of("bilibili", externalId = "123")))

        assertEquals(CommandStatus.SUCCESS, setResult.status)
        assertTrue(renderMessage(setResult).contains("发布者主题色已保存"))
        assertNotNull(PublisherDrawThemeRepository.findByPublisherId(publisher.id))
        val showResult = dispatch(eventBus, listener, commandEvent("/db theme show bilibili 123"))
        assertTrue(renderMessage(showResult).contains("背景="))
        val clearResult = dispatch(eventBus, listener, commandEvent("/db theme clear bilibili 123"))
        assertEquals(CommandStatus.SUCCESS, clearResult.status)
        assertTrue(renderMessage(clearResult).contains("发布者主题色已清除"))
        assertTrue(PublisherDrawThemeRepository.findByPublisherId(publisher.id) == null)
    }

    @Test
    fun linkSetStatusAndClearShouldManageCurrentTargetConfig() = runBlocking {
        initDb("command-link")
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = MainDynamicConfig(
                command = top.colter.dynamic.CommandConfig(
                    permissions = listOf(CommandPermissionRule(senderId = "sender", role = CommandRole.ADMIN)),
                ),
            ),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
        )

        val setResult = dispatch(eventBus, listener, commandEvent("/db link set always"))
        val stored = assertNotNull(
            LinkParseTargetConfigRepository.findByAddress(TargetAddress.of("onebot", TargetKind.GROUP, "100")),
        )
        val statusResult = dispatch(eventBus, listener, commandEvent("/db link status"))
        val clearResult = dispatch(eventBus, listener, commandEvent("/db link clear"))

        assertEquals(CommandStatus.SUCCESS, setResult.status)
        assertEquals(LinkParseTriggerMode.ALWAYS, stored.triggerMode)
        assertTrue(renderMessage(statusResult).contains("匹配到链接就解析"))
        assertEquals(CommandStatus.SUCCESS, clearResult.status)
        assertTrue(LinkParseTargetConfigRepository.findByAddress(TargetAddress.of("onebot", TargetKind.GROUP, "100")) == null)
    }

    @Test
    fun commandShouldBeIgnoredWhenReceivedByNonPrimaryBot() = runBlocking {
        initDb("command-receive-primary")
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = MainDynamicConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
            primaryBotAccountResolver = { "42" },
        )

        val result = dispatchOrNull(
            eventBus,
            listener,
            commandEvent("/db help", botAccountId = "24"),
        )

        assertEquals(null, result)
    }

    @Test
    fun commandShouldBeAcceptedWhenCurrentBotIsMentioned() = runBlocking {
        initDb("command-receive-mentioned")
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = publicUserConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
            primaryBotAccountResolver = { "42" },
        )

        val result = dispatchOrNull(
            eventBus,
            listener,
            commandEvent("/db help", botAccountId = "24", mentionedAccountIds = setOf("24")),
        )

        assertEquals(CommandStatus.SUCCESS, result?.status)
    }

    @Test
    fun commandShouldRejectWhenPermissionRuleIsRequiredAndNoRuleMatches() = runBlocking {
        initDb("command-require-permission")
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = MainDynamicConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
        )

        val result = dispatch(eventBus, listener, commandEvent("/db help"))

        assertEquals(CommandStatus.REJECTED, result.status)
    }

    @Test
    fun forwardShouldEnqueueManualMessageForExplicitTargets() = runBlocking {
        initDb("command-forward")
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = adminConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
        )

        val result = dispatch(eventBus, listener, commandEvent("/db forward qq GROUP 10001,10002 维护通知"))

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertTrue(renderMessage(result).contains("新增投递=2"))
        assertEquals(2, MessageDeliveryRepository.findRecent(limit = 10).size)
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

    private suspend fun dispatchOrNull(
        eventBus: EventBus,
        listener: CommandListener,
        event: CommandEvent,
    ): CommandResultEvent? {
        val result = CompletableDeferred<CommandResultEvent>()
        eventBus.subscribe(
            object : Listener<CommandResultEvent> {
                override suspend fun onMessage(event: CommandResultEvent) {
                    result.complete(event)
                }
            },
        )

        listener.onMessage(event)
        return withTimeoutOrNull(300) { result.await() }
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

    private fun commandEvent(
        rawText: String,
        botAccountId: String? = null,
        mentionedAccountIds: Set<String> = emptySet(),
    ): CommandEvent {
        return CommandEvent(
            sourcePlugin = "test",
            context = CommandContext.of(
                platform = "onebot",
                kind = TargetKind.GROUP,
                externalId = "100",
                senderId = "sender",
                botAccountId = botAccountId,
                mentionedAccountIds = mentionedAccountIds,
            ),
            rawText = rawText,
            traceId = "trace",
        )
    }

    private fun managerConfig(): MainDynamicConfig {
        return MainDynamicConfig(
            command = top.colter.dynamic.CommandConfig(
                permissions = listOf(CommandPermissionRule(senderId = "sender", role = CommandRole.MANAGER)),
            ),
        )
    }

    private fun publicUserConfig(): MainDynamicConfig {
        return MainDynamicConfig(
            command = top.colter.dynamic.CommandConfig(requirePermissionRule = false),
        )
    }

    private fun adminConfig(): MainDynamicConfig {
        return MainDynamicConfig(
            command = top.colter.dynamic.CommandConfig(
                permissions = listOf(CommandPermissionRule(senderId = "sender", role = CommandRole.ADMIN)),
            ),
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
        var queryFollowStateCalls: Int = 0
        var followPublisherCalls: Int = 0

        override suspend fun fetchPublisherInfo(userId: String): PublisherInfo? {
            return testPublisherInfo(
                key = PublisherKey.of(platformId = platformId.value, externalId = userId),
                name = "demo-up",
            )
        }

        override suspend fun queryFollowState(userId: String): FollowState {
            queryFollowStateCalls += 1
            return FollowState.FOLLOWING
        }

        override suspend fun followPublisher(userId: String): FollowActionResult {
            followPublisherCalls += 1
            return FollowActionResult(FollowActionStatus.DONE)
        }
    }

    private class FakePublisherLookupPlugin : PublisherLookupPlugin {
        override val platformId: PlatformId = PlatformId.of("bilibili")

        override suspend fun fetchPublisherInfo(userId: String): PublisherInfo {
            return testPublisherInfo(
                key = PublisherKey.of(platformId = platformId.value, externalId = userId),
                name = "demo-up",
            )
        }
    }
}
