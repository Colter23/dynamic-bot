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
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.DynamicBlockKind
import top.colter.dynamic.core.data.FilterCondition
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.Subscription
import top.colter.dynamic.core.data.SubscriptionEventKind
import top.colter.dynamic.core.data.SubscriptionPolicy
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.data.UpdateKey
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.core.task.TaskSchedule
import top.colter.dynamic.core.task.TaskSnapshot
import top.colter.dynamic.core.task.TaskStatus
import top.colter.dynamic.event.CommandEvent
import top.colter.dynamic.event.CommandResultEvent
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.Listener
import top.colter.dynamic.incoming.IncomingBotAccountSelector
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PublisherFollowPlugin
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.core.plugin.PublisherLatestUpdateProvider
import top.colter.dynamic.core.plugin.PublisherLatestUpdateRequest
import top.colter.dynamic.core.plugin.PublisherLatestUpdateResult
import top.colter.dynamic.core.event.PublisherPersistenceMode
import top.colter.dynamic.core.event.SourceUpdateDeliveryTags
import top.colter.dynamic.core.event.SourceUpdatePublishResult
import top.colter.dynamic.core.event.SourceUpdatePublisher
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
import top.colter.dynamic.plugin.PluginInfo
import top.colter.dynamic.plugin.PluginState
import top.colter.dynamic.plugin.PluginTaskInfo
import top.colter.dynamic.publisher.PublisherLatestUpdateService

class CommandListenerTest {
    @Test
    fun latestShouldDispatchPlatformUpdateAsManualPreview() = runBlocking {
        initDb("command-latest")
        val eventBus = EventBus()
        val provider = FakeLatestUpdateProvider()
        val requests = mutableListOf<top.colter.dynamic.core.event.SourceUpdatePublishRequest>()
        val service = PublisherLatestUpdateService(
            providerResolver = { platformId -> provider.takeIf { it.platformId.value == platformId } },
            sourceUpdatePublisher = SourceUpdatePublisher { request ->
                requests += request
                SourceUpdatePublishResult.enqueued(1)
            },
        )
        val listener = CommandListener(
            publisherLookupResolver = { null },
            latestPublisherUpdateService = service,
            config = publicUserConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
            publisherThemeInitializer = PublisherThemeInitializer { _, _ -> },
        )

        val result = dispatch(eventBus, listener, commandEvent("/db new bilibili https://space.bilibili.com/123"))

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertTrue(renderMessage(result).contains("已提交 demo-up 的最新动态"))
        assertEquals(listOf("https://space.bilibili.com/123"), provider.inputs)
        val request = requests.single()
        assertEquals(SourceUpdateDeliveryTags.MANUAL_QUERY, request.deliveryTag)
        assertEquals(PublisherPersistenceMode.READ_ONLY, request.publisherPersistenceMode)
        assertEquals("trace", request.correlationId)
        assertEquals("100", request.deliveryTargetAddress?.externalId)
    }

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
            incomingBotAccountSelector = IncomingBotAccountSelector(primaryBotAccountResolver = { "42" }),
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
            incomingBotAccountSelector = IncomingBotAccountSelector(primaryBotAccountResolver = { "42" }),
        )

        val result = dispatchOrNull(
            eventBus,
            listener,
            commandEvent("/db help", botAccountId = "24", mentionedAccountIds = setOf("24")),
        )

        assertEquals(CommandStatus.SUCCESS, result?.status)
    }

    @Test
    fun helpShouldReplyEvenWhenNoPermissionRuleMatches() = runBlocking {
        initDb("command-help-no-permission")
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = MainDynamicConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
        )

        val result = dispatch(eventBus, listener, commandEvent("/db help"))

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertTrue(renderMessage(result).contains("help"))
    }

    @Test
    fun whoamiShouldReplyEvenWhenNoPermissionRuleMatches() = runBlocking {
        initDb("command-whoami-no-permission")
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = MainDynamicConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
        )

        val result = dispatch(eventBus, listener, commandEvent("/db whoami"))

        assertEquals(CommandStatus.SUCCESS, result.status)
        val text = renderMessage(result)
        assertTrue(text.contains("sender"), "应包含发送者 ID：$text")
        assertTrue(text.contains("onebot"), "应包含平台：$text")
    }

    @Test
    fun permissionDeniedShouldReplyOncePerCommand() = runBlocking {
        initDb("command-permission-denied")
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = MainDynamicConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
        )

        val first = dispatch(eventBus, listener, commandEvent("/db status"))
        assertEquals(CommandStatus.FAILED, first.status)
        assertTrue(renderMessage(first).contains("没有该命令的权限"))

        val second = dispatchOrNull(eventBus, listener, commandEvent("/db status"))
        assertEquals(null, second, "同一用户同一命令不应重复提示")
    }

    @Test
    fun stopShouldRequireConfirmation() = runBlocking {
        initDb("command-stop-confirm")
        val eventBus = EventBus()
        var stopReason: String? = null
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = adminConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
            stopRequester = { reason -> stopReason = reason },
        )

        val prompt = dispatch(eventBus, listener, commandEvent("/db stop"))
        assertEquals(CommandStatus.SUCCESS, prompt.status)
        assertTrue(renderMessage(prompt).contains("确认停止"))
        assertEquals(null, stopReason, "首条消息不应直接停机")

        val confirmed = dispatch(eventBus, listener, commandEvent("/db stop confirm"))
        assertEquals(CommandStatus.SUCCESS, confirmed.status)
        assertNotNull(stopReason)
    }

    @Test
    fun unknownCommandShouldNotReplyWhenSenderHasNoPermission() = runBlocking {
        initDb("command-unknown-no-permission")
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = MainDynamicConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
        )

        val result = dispatchOrNull(eventBus, listener, commandEvent("/db unknown"))

        assertEquals(null, result)
    }

    @Test
    fun unknownCommandShouldReplyWhenSenderHasPermission() = runBlocking {
        initDb("command-unknown-with-permission")
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = adminConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
        )

        val result = dispatch(eventBus, listener, commandEvent("/db unknown"))

        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(renderMessage(result).contains("未知命令：unknown"))
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

    @Test
    fun statusShouldReturnProjectHealthSummary() = runBlocking {
        initDb("command-status")
        seedSubscription()
        val target = TargetAddress.of("onebot", TargetKind.GROUP, "100")
        val message = Message(
            id = "status-message",
            time = 1L,
            targets = listOf(target),
            batches = listOf(MessageBatch(listOf(MessageContent.Text("hello")))),
        )
        MessageDeliveryRepository.createDeliveryRecord(message, target, DeliveryStatus.SEND_UNKNOWN, attempts = 1)
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = adminConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
            pluginInfoProvider = {
                listOf(
                    PluginInfo(
                        descriptor = PluginDescriptor("onebot", "OneBot", "1.0.0", "OneBotPlugin"),
                        capabilities = emptySet(),
                        state = PluginState.ACTIVE,
                        sourceJarPath = "plugins/onebot.jar",
                    ),
                    PluginInfo(
                        descriptor = PluginDescriptor("broken", "Broken", "1.0.0", "BrokenPlugin"),
                        capabilities = emptySet(),
                        state = PluginState.FAILED,
                        sourceJarPath = "plugins/broken.jar",
                    ),
                )
            },
            mainTaskSnapshotProvider = {
                listOf(taskSnapshot("main-delivery-dispatch", TaskStatus.RUNNING))
            },
            pluginTaskInfoProvider = {
                listOf(
                    PluginTaskInfo(
                        pluginId = "bilibili",
                        pluginName = "Bilibili",
                        pluginVersion = "1.0.0",
                        pluginState = PluginState.ACTIVE,
                        task = taskSnapshot("bilibili-dynamic", TaskStatus.FAILED),
                    ),
                )
            },
            startedAtEpochMillis = System.currentTimeMillis() - 3_600_000,
        )

        val result = dispatch(eventBus, listener, commandEvent("/db 状态"))
        val text = renderMessage(result)

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertTrue(text.contains("动态 Bot 状态：异常"))
        assertTrue(text.contains("插件：2 个，运行 1，已加载 0，失败 1"))
        assertTrue(text.contains("任务：主任务 1/1 运行，插件任务 0/1 运行，失败 1"))
        assertTrue(text.contains("数据：发布者=1，消息目标=1，订阅=1"))
        assertTrue(text.contains("不确定=1"))
        assertTrue(!text.contains("后台："))
        assertTrue(!text.contains("数据库："))
    }

    @Test
    fun policyShouldShowAndUpdateEvents() = runBlocking {
        initDb("command-policy-events")
        seedSubscription()
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = managerConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
        )

        val shown = dispatch(eventBus, listener, commandEvent("/db policy bilibili 123"))
        assertEquals(CommandStatus.SUCCESS, shown.status)
        assertTrue(renderMessage(shown).contains("事件：动态"))

        val updated = dispatch(eventBus, listener, commandEvent("/db policy bilibili 123 live"))
        assertEquals(CommandStatus.SUCCESS, updated.status)
        assertTrue(renderMessage(updated).contains("开播"))

        assertEquals(setOf(SubscriptionEventKind.LIVE_STARTED), currentPolicy().enabledEvents)
    }

    @Test
    fun policyShouldSetMentionAll() = runBlocking {
        initDb("command-policy-mention")
        seedSubscription()
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = managerConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
        )

        val result = dispatch(eventBus, listener, commandEvent("/db policy bilibili 123 mention all"))

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertTrue(renderMessage(result).contains("@全体：动态、开播、下播"))
        assertEquals(SubscriptionEventKind.entries.toSet(), currentPolicy().mentionAllEvents)
    }

    @Test
    fun policyShouldRejectUnknownEventsAndKeepPolicy() = runBlocking {
        initDb("command-policy-invalid")
        seedSubscription()
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = managerConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
        )

        val result = dispatch(eventBus, listener, commandEvent("/db policy bilibili 123 unknown_event"))

        assertEquals(CommandStatus.FAILED, result.status)
        assertEquals(setOf(SubscriptionEventKind.DYNAMIC), currentPolicy().enabledEvents)
    }

    @Test
    fun filterClearShouldRequireConfirmation() = runBlocking {
        initDb("command-filter-clear-confirm")
        seedSubscription()
        val subscription = currentSubscription()
        DynamicFilterRuleRepository.addRule(subscription.id, FilterCondition.HasElement(DynamicBlockKind.TEXT))
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = managerConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
        )

        val prompt = dispatch(eventBus, listener, commandEvent("/db filter clear bilibili 123"))
        assertEquals(CommandStatus.SUCCESS, prompt.status)
        assertTrue(renderMessage(prompt).contains("确认清空"), "reply=${renderMessage(prompt)}")
        assertEquals(1, DynamicFilterRuleRepository.findBySubscriptionId(subscription.id).size)

        val confirmed = dispatch(eventBus, listener, commandEvent("/db filter clear bilibili 123 confirm"))
        assertEquals(CommandStatus.SUCCESS, confirmed.status)
        assertTrue(DynamicFilterRuleRepository.findBySubscriptionId(subscription.id).isEmpty())
    }

    @Test
    fun filterClearConfirmWithoutPendingRequestFails() = runBlocking {
        initDb("command-filter-clear-no-pending")
        seedSubscription()
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = managerConfig(),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
        )

        val result = dispatch(eventBus, listener, commandEvent("/db filter clear bilibili 123 confirm"))

        assertEquals(CommandStatus.FAILED, result.status)
    }

    private fun currentSubscription(): Subscription {
        val subscriber = SubscriberRepository.findByAddress(TargetAddress.of("onebot", TargetKind.GROUP, "100"))
            ?: error("subscriber missing")
        val publisher = PublisherRepository.findByKey(PublisherKey.of("bilibili", PublisherKind.USER, "123"))
            ?: error("publisher missing")
        return SubscriptionRepository.findBySubscriberAndPublisher(subscriber.id, publisher.id)
            ?: error("subscription missing")
    }

    private fun currentPolicy(): SubscriptionPolicy = currentSubscription().policy

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

    private fun taskSnapshot(id: String, status: TaskStatus): TaskSnapshot {
        return TaskSnapshot(
            id = id,
            status = status,
            schedule = TaskSchedule.Once,
            nextRunAtMillis = null,
            lastRunAtMillis = null,
            lastSuccessAtMillis = null,
            runCount = 1,
            lastErrorSummary = null,
        )
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

    private class FakeLatestUpdateProvider : PublisherLatestUpdateProvider {
        override val platformId: PlatformId = PlatformId.of("bilibili")
        val inputs: MutableList<String> = mutableListOf()

        override suspend fun fetchLatestPublisherUpdate(
            request: PublisherLatestUpdateRequest,
        ): PublisherLatestUpdateResult {
            inputs += request.publisherInput
            val publisher = testPublisherInfo(
                key = PublisherKey.of(platformId = platformId.value, externalId = "123"),
                name = "demo-up",
            )
            return PublisherLatestUpdateResult.Found(
                SourceUpdate(
                    key = UpdateKey(publisher.key, SourceEventType.DYNAMIC_CREATED, "dynamic-1"),
                    publisher = publisher,
                    occurredAtEpochSeconds = 1_000,
                    link = "https://t.bilibili.com/dynamic-1",
                    payload = DynamicPayload(),
                ),
            )
        }
    }
}
