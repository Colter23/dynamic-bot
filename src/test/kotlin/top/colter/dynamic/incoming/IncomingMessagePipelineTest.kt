package top.colter.dynamic.incoming

import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import top.colter.dynamic.CommandConfig
import top.colter.dynamic.CommandReceiveMode
import top.colter.dynamic.LinkParsingConfig
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.command.CommandBootstrapService
import top.colter.dynamic.command.CommandListener
import top.colter.dynamic.command.PendingSubscriptionSelection
import top.colter.dynamic.command.SubscriptionSelectionComponent
import top.colter.dynamic.command.SubscriptionService
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.IncomingProcessingResult
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.draw.PublisherThemeInitializer
import top.colter.dynamic.core.command.CommandPermissionRule
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.IncomingMessageAuditMode
import top.colter.dynamic.core.data.IncomingMessageRecordPolicy
import top.colter.dynamic.core.data.IncomingMessageSegment
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.SubscriberState
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkPreview
import top.colter.dynamic.core.link.LinkResolution
import top.colter.dynamic.core.link.LinkResolver
import top.colter.dynamic.core.link.ParsedLink
import top.colter.dynamic.core.plugin.IncomingMessageDispatchContext
import top.colter.dynamic.core.plugin.IncomingMessageIntent
import top.colter.dynamic.core.plugin.IncomingMessagePublishRequest
import top.colter.dynamic.event.CommandEvent
import top.colter.dynamic.event.CommandResultEvent
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.IncomingMessageEvent
import top.colter.dynamic.event.IncomingTextMessageEvent
import top.colter.dynamic.event.Listener
import top.colter.dynamic.link.LinkParseService
import top.colter.dynamic.repository.IncomingAuditWriteRequest
import top.colter.dynamic.repository.IncomingProcessingWriteRequest
import top.colter.dynamic.repository.PersistenceManager
import top.colter.dynamic.repository.SubscriberStateCache
import top.colter.dynamic.testPublisherInfo

class IncomingMessagePipelineTest {
    @Test
    fun `带前缀的认领成功错误群聊和已初始化请求都不会泄漏凭据`() = runBlocking {
        for ((text, kind, initialized) in listOf(
            Triple("/db claim 123456", TargetKind.USER, false),
            Triple("/db CLAIM 000000", TargetKind.USER, false),
            Triple("/db claim 无效凭据", TargetKind.USER, false),
            Triple("/db claim 123456", TargetKind.GROUP, false),
            Triple("/db claim 123456", TargetKind.USER, true),
        )) {
            val bus = EventBus(Dispatchers.Unconfined)
            var config = MainDynamicConfig(command = CommandConfig(
                prefix = "/db",
                permissions = if (initialized) listOf(CommandPermissionRule(senderId = "admin", role = CommandRole.ADMIN)) else emptyList(),
            ))
            val service = CommandBootstrapService(
                configProvider = { config },
                configUpdater = { transform -> config = transform(config); ConfigApplyResult(changed = true) },
                codeGenerator = { "123456" },
            )
            val recorder = RecordingAuditRecorder()
            var observations = 0
            var commands = 0
            var consumers = 0
            bus.subscribe(object : Listener<IncomingMessageEvent> {
                override suspend fun onMessage(event: IncomingMessageEvent) { observations++ }
            })
            bus.subscribe(object : Listener<CommandEvent> {
                override suspend fun onMessage(event: CommandEvent) { commands++ }
            })
            val pipeline = pipeline(
                eventBus = bus,
                configProvider = { config },
                commandBootstrapService = service,
                auditRecorder = recorder,
                incomingConsumerDispatcher = { consumers++ },
            )

            pipeline.handle("onebot", IncomingMessagePublishRequest(
                message = testMessage(text, targetKind = kind), traceId = "claim-test",
            ))

            assertEquals(0, observations, text)
            assertEquals(0, commands, text)
            assertEquals(0, consumers, text)
            assertTrue(recorder.records.isEmpty(), text)
            val expectedClaim = text == "/db claim 123456" && kind == TargetKind.USER && !initialized
            assertEquals(expectedClaim, config.command.permissions.any { it.senderId == "sender" }, text)
            bus.shutdown()
        }
    }

    @Test
    fun `候选确认走订阅权限审计和主 Bot 仲裁`() = runBlocking {
        PersistenceManager.init(createTempDirectory("selection-pipeline").resolve("test.db").toString())
        val bus = EventBus(Dispatchers.Unconfined)
        var config = MainDynamicConfig(command = CommandConfig(prefix = "/db", requirePermissionRule = true))
        var fetches = 0
        val lookup = object : PublisherLookupPlugin {
            override val platformId = PlatformId.of("bilibili")
            override suspend fun fetchPublisherInfo(userId: String) = testPublisherInfo(
                key = PublisherKey.of("bilibili", externalId = userId), name = "发布者",
            ).also { fetches++ }
        }
        val component = SubscriptionSelectionComponent(SubscriptionService(
            publisherLookupResolver = { lookup }, publisherFollowResolver = { null },
            configProvider = { config }, eventBus = bus, themeInitializer = PublisherThemeInitializer { _, _ -> },
        ))
        val selector = IncomingBotAccountSelector(primaryBotAccountResolver = { "bot-a" })
        val processing = mutableListOf<IncomingProcessingWriteRequest>()
        bus.subscribe(CommandListener(
            publisherLookupResolver = { lookup }, configProvider = { config }, subscriptionComponent = component,
            eventBus = bus, incomingBotAccountSelector = selector,
            publisherThemeInitializer = PublisherThemeInitializer { _, _ -> },
            incomingProcessingRecorder = { processing += it; true },
        ))
        val message = testMessage("#1", botAccountId = "bot-a", mentions = emptySet())
        val context = CommandContext(target = message.target, senderId = message.senderId, botAccountId = "bot-a")
        component.offerCandidates(context, "bilibili", listOf(testPublisherInfo(
            key = PublisherKey.of("bilibili", externalId = "111"), name = "发布者",
        )))
        val recorder = RecordingAuditRecorder()
        val pipeline = pipeline(
            eventBus = bus, configProvider = { config }, incomingBotAccountSelector = selector,
            auditRecorder = recorder, pendingSelectionResolver = component::resolveSelection,
        )

        pipeline.handle("onebot", IncomingMessagePublishRequest(message, traceId = "denied"))
        assertEquals(0, fetches)
        assertTrue(processing.any { it.result == IncomingProcessingResult.REJECTED && it.commandPath == "subscribe" })
        assertEquals(1, component.resolveSelection(context, "#1")?.index)

        config = config.copy(command = config.command.copy(permissions = listOf(
            CommandPermissionRule(commandPath = "subscribe", senderId = "sender", role = CommandRole.MANAGER),
        )))
        val secondary = message.copy(botAccountId = "bot-b", target = message.target.copy(accountId = "bot-b"))
        pipeline.handle("onebot", IncomingMessagePublishRequest(secondary, traceId = "secondary"))
        assertEquals(0, fetches)
        assertEquals(1, component.resolveSelection(context, "#1")?.index)

        pipeline.handle("onebot", IncomingMessagePublishRequest(message, traceId = "accepted"))
        assertEquals(1, fetches)
        assertNull(component.resolveSelection(context, "#1"))
        assertTrue(processing.any { it.traceId == "accepted" && it.result == IncomingProcessingResult.SUCCEEDED })
        assertEquals(3, recorder.records.size)
        assertTrue(recorder.records.all { it.intent == IncomingMessageIntent.Command && it.recordPolicy == IncomingMessageRecordPolicy.Audit })
        bus.shutdown()
    }

    @Test
    fun `重复认领事件不消耗额外次数且正确验证码仍可认领`() = runBlocking {
        for (prefix in listOf("/db claim ", "")) {
            val bus = EventBus(Dispatchers.Unconfined)
            var config = MainDynamicConfig(command = CommandConfig(prefix = "/db"))
            val service = CommandBootstrapService(
                configProvider = { config },
                configUpdater = { transform -> config = transform(config); ConfigApplyResult(changed = true) },
                codeGenerator = { "123456" },
            )
            val recorder = RecordingAuditRecorder()
            var observations = 0
            var consumers = 0
            var replies = 0
            bus.subscribe(object : Listener<IncomingMessageEvent> {
                override suspend fun onMessage(event: IncomingMessageEvent) { observations++ }
            })
            bus.subscribe(object : Listener<CommandResultEvent> {
                override suspend fun onMessage(event: CommandResultEvent) { replies++ }
            })
            val pipeline = pipeline(
                eventBus = bus, configProvider = { config }, commandBootstrapService = service,
                auditRecorder = recorder, incomingConsumerDispatcher = { consumers++ },
            )
            val wrong = IncomingMessagePublishRequest(
                message = testMessage(prefix + "000000", targetKind = TargetKind.USER),
                traceId = "wrong", dedupeKey = "wrong-event",
            )
            repeat(3) { pipeline.handle("onebot", wrong.copy(traceId = "repeated-$it")) }
            assertEquals(1, service.snapshot().recentAttempts, prefix)

            val correct = IncomingMessagePublishRequest(
                message = testMessage(prefix + "123456", messageId = "correct", targetKind = TargetKind.USER),
                traceId = "correct", dedupeKey = "correct-event",
            )
            pipeline.handle("onebot", correct)
            assertTrue(config.command.permissions.any { it.senderId == "sender" }, prefix)
            val repliesAfterClaim = replies
            pipeline.handle("onebot", correct)
            assertEquals(repliesAfterClaim, replies, "重复成功事件不应再发送已初始化提示")
            assertEquals(2, service.snapshot().recentAttempts, prefix)
            assertEquals(0, observations, prefix)
            assertEquals(0, consumers, prefix)
            assertTrue(recorder.records.isEmpty(), prefix)
            bus.shutdown()
        }
    }

    @Test
    fun `认领消息被仲裁忽略后同一事件仍可处理`() = runBlocking {
        val bus = EventBus(Dispatchers.Unconfined)
        var primaryBot = "bot-a"
        var replies = 0
        var config = MainDynamicConfig(command = CommandConfig(prefix = "/db"))
        val service = CommandBootstrapService(
            configProvider = { config },
            configUpdater = { transform -> config = transform(config); ConfigApplyResult(changed = true) },
            codeGenerator = { "123456" },
        )
        val pipeline = pipeline(
            eventBus = bus, configProvider = { config }, commandBootstrapService = service,
            incomingBotAccountSelector = IncomingBotAccountSelector(primaryBotAccountResolver = { primaryBot }),
        )
        bus.subscribe(object : Listener<CommandResultEvent> {
            override suspend fun onMessage(event: CommandResultEvent) { replies++ }
        })
        val request = IncomingMessagePublishRequest(
            message = testMessage("/db claim", botAccountId = "bot-b", mentions = emptySet()),
            traceId = "claim-event", dedupeKey = "event",
        )
        pipeline.handle("onebot", request)
        assertEquals(0, replies)
        primaryBot = "bot-b"
        pipeline.handle("onebot", request)
        assertEquals(1, replies, "被仲裁忽略的事件不应提前占用去重键")
        pipeline.handle("onebot", request)
        assertEquals(1, replies, "处理后重复上报仍应去重")
        bus.shutdown()
    }

    @Test
    fun `候选确认绑定后更换候选不得订阅新批次`() = runBlocking {
        for (confirmationText in listOf("#1", "/db subscribe #1", "/db 订阅 #1")) {
            PersistenceManager.init(createTempDirectory("selection-batch").resolve("test.db").toString())
            val bus = EventBus(Dispatchers.Unconfined)
            val config = MainDynamicConfig(command = CommandConfig(prefix = "/db", permissions = listOf(
                CommandPermissionRule(senderId = "sender", role = CommandRole.MANAGER),
            )))
            val fetched = mutableListOf<String>()
            val processing = mutableListOf<IncomingProcessingWriteRequest>()
            val lookup = object : PublisherLookupPlugin {
                override val platformId = PlatformId.of("bilibili")
                override suspend fun fetchPublisherInfo(userId: String) = testPublisherInfo(
                    key = PublisherKey.of("bilibili", externalId = userId), name = userId,
                ).also { fetched += userId }
            }
            val component = SubscriptionSelectionComponent(SubscriptionService(
                publisherLookupResolver = { lookup }, publisherFollowResolver = { null },
                configProvider = { config }, eventBus = bus, themeInitializer = PublisherThemeInitializer { _, _ -> },
            ))
            bus.subscribe(CommandListener(
                publisherLookupResolver = { lookup }, configProvider = { config }, subscriptionComponent = component,
                eventBus = bus, publisherThemeInitializer = PublisherThemeInitializer { _, _ -> },
                incomingProcessingRecorder = { processing += it; true },
            ))
            val pipeline = pipeline(
                eventBus = bus, configProvider = { config }, pendingSelectionResolver = component::resolveSelection,
            )
            val message = testMessage(confirmationText)
            val context = CommandContext(target = message.target, senderId = message.senderId, botAccountId = message.botAccountId)
            fun offer(id: String) {
                component.offerCandidates(context, "bilibili", listOf(testPublisherInfo(
                    key = PublisherKey.of("bilibili", externalId = id), name = id,
                )))
            }
            offer("111")
            // 绑定候选后、执行前产生新搜索，仍不能使用新候选。
            val token = bus.subscribe(object : Listener<IncomingMessageEvent> {
                override suspend fun onMessage(event: IncomingMessageEvent) {
                    if (event.traceId == "old-confirmation") offer("222")
                }
            })
            pipeline.handle("onebot", IncomingMessagePublishRequest(message, traceId = "old-confirmation"))

            assertTrue(fetched.isEmpty(), confirmationText)
            assertTrue(processing.any { it.traceId == "old-confirmation" && it.result == IncomingProcessingResult.FAILED }, confirmationText)
            assertEquals(1, component.resolveSelection(context, "#1")?.index, "新候选仍然可用")
            bus.unsubscribe(token)

            pipeline.handle("onebot", IncomingMessagePublishRequest(message.copy(messageId = "new"), traceId = "new-confirmation"))
            assertEquals(listOf("222"), fetched, confirmationText)
            assertNull(component.resolveSelection(context, "#1"))
            assertTrue(processing.any { it.traceId == "new-confirmation" && it.result == IncomingProcessingResult.SUCCEEDED }, confirmationText)
            bus.shutdown()
        }
    }

    @Test
    fun `handle should broadcast incoming observation event with normalized ids`() = runBlocking {
        val eventBus = EventBus()
        val received = CompletableDeferred<IncomingMessageEvent>()
        eventBus.subscribe(
            object : Listener<IncomingMessageEvent> {
                override suspend fun onMessage(event: IncomingMessageEvent) {
                    received.complete(event)
                }
            },
        )
        val pipeline = pipeline(eventBus = eventBus)
        val message = testMessage(text = "hello", messageId = "message-1")

        pipeline.handle("onebot", IncomingMessagePublishRequest(message = message, traceId = " "))
        val event = withTimeout(1_000) { received.await() }

        assertEquals("onebot", event.sourcePlugin)
        assertEquals(message, event.message)
        assertTrue(event.traceId.isNotBlank())
        assertEquals("message-1", event.replyToMessageId)
        eventBus.shutdown()
    }

    @Test
    fun `handle should claim admin from private verification code and stop dispatch`() = runBlocking {
        val eventBus = EventBus()
        var config = MainDynamicConfig(command = CommandConfig(prefix = "/db", requirePermissionRule = true))
        val service = CommandBootstrapService(
            configProvider = { config },
            configUpdater = { transform ->
                config = transform(config)
                ConfigApplyResult(changed = true)
            },
            codeGenerator = { "123456" },
        )
        val dispatched = mutableListOf<IncomingMessageDispatchContext>()
        val audited = mutableListOf<IncomingAuditWriteRequest>()
        val reply = CompletableDeferred<String>()
        eventBus.subscribe(
            object : Listener<CommandResultEvent> {
                override suspend fun onMessage(event: CommandResultEvent) {
                    reply.complete(
                        event.chain.flatMap { it.content }
                            .filterIsInstance<MessageContent.Text>()
                            .joinToString("") { it.fallbackText },
                    )
                }
            },
        )
        val pipeline = pipeline(
            eventBus = eventBus,
            configProvider = { config },
            commandBootstrapService = service,
            incomingConsumerDispatcher = { dispatched += it },
            auditRecorder = IncomingMessageAuditRecorder { request ->
                audited += request
                true
            },
        )

        pipeline.handle(
            "onebot",
            IncomingMessagePublishRequest(
                message = testMessage(text = "123456", targetKind = TargetKind.USER),
                traceId = "trace-claim",
            ),
        )

        val rule = config.command.permissions.singleOrNull()
        assertEquals(CommandRole.ADMIN, rule?.role)
        assertEquals("sender", rule?.senderId)
        val replyText = withTimeout(1_000) { reply.await() }
        assertTrue(replyText.contains("已认领"), "reply=$replyText")
        assertTrue(dispatched.isEmpty(), "认领消息不应继续分发")
        assertTrue(audited.isEmpty(), "验证码消息不应写入入站审计，避免长期留存")
        eventBus.shutdown()
    }

    @Test
    fun `handle should swallow wrong private verification code without granting admin`() = runBlocking {
        val eventBus = EventBus()
        var config = MainDynamicConfig(command = CommandConfig(prefix = "/db", requirePermissionRule = true))
        val service = CommandBootstrapService(
            configProvider = { config },
            configUpdater = { transform ->
                config = transform(config)
                ConfigApplyResult(changed = true)
            },
            codeGenerator = { "123456" },
        )
        val dispatched = mutableListOf<IncomingMessageDispatchContext>()
        val pipeline = pipeline(
            eventBus = eventBus,
            configProvider = { config },
            commandBootstrapService = service,
            incomingConsumerDispatcher = { dispatched += it },
        )

        pipeline.handle(
            "onebot",
            IncomingMessagePublishRequest(
                message = testMessage(text = "000000", targetKind = TargetKind.USER),
                traceId = "trace-wrong-code",
            ),
        )

        assertTrue(config.command.permissions.isEmpty(), "错误验证码不应授予权限")
        assertTrue(dispatched.isEmpty(), "错误验证码应静默丢弃")
        eventBus.shutdown()
    }

    @Test
    fun `handle should not intercept verification code sent in group`() = runBlocking {
        val eventBus = EventBus()
        var config = MainDynamicConfig(command = CommandConfig(prefix = "/db", requirePermissionRule = true))
        val service = CommandBootstrapService(
            configProvider = { config },
            configUpdater = { transform ->
                config = transform(config)
                ConfigApplyResult(changed = true)
            },
            codeGenerator = { "123456" },
        )
        val dispatched = mutableListOf<IncomingMessageDispatchContext>()
        val pipeline = pipeline(
            eventBus = eventBus,
            configProvider = { config },
            commandBootstrapService = service,
            incomingConsumerDispatcher = { dispatched += it },
        )

        pipeline.handle(
            "onebot",
            IncomingMessagePublishRequest(
                message = testMessage(text = "123456", targetKind = TargetKind.GROUP),
                traceId = "trace-group-code",
            ),
        )

        assertTrue(config.command.permissions.isEmpty(), "群聊不应当被认领")
        assertEquals(1, dispatched.size, "群里的数字消息应继续作为普通消息分发")
        eventBus.shutdown()
    }

    @Test
    fun `handle should dispatch numeric private message once command system initialized`() = runBlocking {
        val eventBus = EventBus()
        var config = MainDynamicConfig(
            command = CommandConfig(
                prefix = "/db",
                requirePermissionRule = true,
                permissions = listOf(
                    CommandPermissionRule(platformId = "onebot", senderId = "other", role = CommandRole.ADMIN),
                ),
            ),
        )
        val service = CommandBootstrapService(
            configProvider = { config },
            configUpdater = { transform ->
                config = transform(config)
                ConfigApplyResult(changed = true)
            },
            codeGenerator = { "123456" },
        )
        val dispatched = mutableListOf<IncomingMessageDispatchContext>()
        val pipeline = pipeline(
            eventBus = eventBus,
            configProvider = { config },
            commandBootstrapService = service,
            incomingConsumerDispatcher = { dispatched += it },
        )

        pipeline.handle(
            "onebot",
            IncomingMessagePublishRequest(
                message = testMessage(text = "123456", targetKind = TargetKind.USER),
                traceId = "trace-after-claimed",
            ),
        )

        assertEquals(1, dispatched.size, "已初始化后数字消息按普通消息处理")
        eventBus.shutdown()
    }

    @Test
    fun `handle should not use trace id as reply target when platform message id is missing`() = runBlocking {
        val eventBus = EventBus()
        val received = CompletableDeferred<IncomingMessageEvent>()
        eventBus.subscribe(
            object : Listener<IncomingMessageEvent> {
                override suspend fun onMessage(event: IncomingMessageEvent) {
                    received.complete(event)
                }
            },
        )
        val pipeline = pipeline(eventBus = eventBus)
        val message = testMessage(text = "hello", messageId = "")

        pipeline.handle("onebot", IncomingMessagePublishRequest(message = message, traceId = "trace-1"))
        val event = withTimeout(1_000) { received.await() }

        assertEquals("trace-1", event.traceId)
        assertEquals("", event.replyToMessageId)
        eventBus.shutdown()
    }

    @Test
    fun `handle should dispatch duplicated trace id only once`() = runBlocking {
        val eventBus = EventBus()
        val recordedTraceIds = mutableSetOf<String>()
        var recordCalls = 0
        var dispatched = 0
        val pipeline = pipeline(
            eventBus = eventBus,
            incomingConsumerDispatcher = { dispatched += 1 },
            auditRecorder = IncomingMessageAuditRecorder { request ->
                recordCalls += 1
                synchronized(recordedTraceIds) { recordedTraceIds.add(request.traceId) }
            },
        )
        val request = IncomingMessagePublishRequest(
            message = testMessage(text = "/db help", messageId = "message-dup"),
            traceId = "trace-dup",
        )

        pipeline.handle("onebot", request)
        pipeline.handle("onebot", request)

        assertEquals(1, recordCalls, "同进程重复消息应直接命中内存去重")
        assertEquals(1, dispatched, "重复消息不应再次分发，否则命令会执行两遍")
        eventBus.shutdown()
    }

    @Test
    fun `critical command and reply should not be dropped when observation queue is full`() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val bus = EventBus(dispatcher, maxInFlightEvents = 1)
        var commands = 0
        var replies = 0
        bus.subscribe(object : Listener<IncomingMessageEvent> {
            override suspend fun onMessage(event: IncomingMessageEvent) {}
        })
        bus.subscribe(object : Listener<CommandEvent> {
            override suspend fun onMessage(event: CommandEvent) {
                commands++
                bus.dispatch(CommandResultEvent(
                    sourcePlugin = "main",
                    target = top.colter.dynamic.core.data.CommandTarget(event.context.target, event.context.senderId),
                    chain = emptyList(), inReplyTo = event.replyToMessageId, traceId = event.traceId,
                    status = top.colter.dynamic.core.data.CommandStatus.SUCCESS,
                ))
            }
        })
        bus.subscribe(object : Listener<CommandResultEvent> {
            override suspend fun onMessage(event: CommandResultEvent) { replies++ }
        })
        val pipeline = pipeline(eventBus = bus)
        val request = request("/db help", traceId = "queue-full")

        pipeline.handle("onebot", request)
        pipeline.handle("onebot", request)

        assertEquals(1, commands)
        assertEquals(1, replies)
        assertEquals(0, bus.droppedEventCount())
        dispatcher.drain()
        bus.shutdown()
    }

    @Test
    fun `critical link should not be dropped when observation queue is full`() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val bus = EventBus(dispatcher, maxInFlightEvents = 1)
        var links = 0
        bus.subscribe(object : Listener<IncomingMessageEvent> {
            override suspend fun onMessage(event: IncomingMessageEvent) {}
        })
        bus.subscribe(object : Listener<IncomingTextMessageEvent> {
            override suspend fun onMessage(event: IncomingTextMessageEvent) { links++ }
        })
        val pipeline = pipeline(
            eventBus = bus,
            linkParseService = LinkParseService(resolversProvider = { listOf(MatchingLinkResolver("https://t.bilibili.com/")) }),
        )

        pipeline.handle("onebot", request("https://t.bilibili.com/123", traceId = "link-queue-full"))

        assertEquals(1, links)
        assertEquals(0, bus.droppedEventCount())
        dispatcher.drain()
        bus.shutdown()
    }

    @Test
    fun `persisted receipt without processing should be handled after pipeline restart`() = runBlocking {
        PersistenceManager.init(createTempDirectory("incoming-received-recovery").resolve("test.db").toString())
        val bus = EventBus(Dispatchers.Unconfined)
        var commands = 0
        bus.subscribe(object : Listener<CommandEvent> {
            override suspend fun onMessage(event: CommandEvent) { commands++ }
        })
        val request = request("/db help", traceId = "received-before-restart")
        top.colter.dynamic.repository.IncomingMessageAuditRepository.recordMessage(IncomingAuditWriteRequest(
            sourcePlugin = "onebot", message = request.message, traceId = request.traceId,
            replyToMessageId = request.message.messageId, intent = IncomingMessageIntent.Command,
            recordPolicy = IncomingMessageRecordPolicy.Audit, receivedAtEpochSeconds = 1,
        ))
        fun restarted() = IncomingMessagePipeline(
            configProvider = { MainDynamicConfig() }, linkParseService = LinkParseService(resolversProvider = { emptyList() }),
            eventBus = bus, incomingConsumerDispatcher = {},
        )

        restarted().handle("onebot", request)
        restarted().handle("onebot", request)

        assertEquals(1, commands)
        bus.shutdown()
    }

    @Test
    fun `cancellation after audit insert but before processing should allow redelivery`() = runBlocking {
        val bus = EventBus(Dispatchers.Unconfined)
        val recorder = RecordingAuditRecorder()
        var childJob: Job? = null
        var cancelBeforeStart = true
        var commands = 0
        val cancellingRecorder = object : IncomingMessageAuditRecorder {
            override fun recordMessage(request: IncomingAuditWriteRequest): Boolean {
                recorder.recordMessage(request)
                if (cancelBeforeStart) childJob!!.cancel()
                return true
            }
            override fun tryBeginDispatch(traceId: String): Boolean = recorder.tryBeginDispatch(traceId)
            override fun markDispatchCompleted(traceId: String) = recorder.markDispatchCompleted(traceId)
        }
        bus.subscribe(object : Listener<CommandEvent> {
            override suspend fun onMessage(event: CommandEvent) { commands++ }
        })
        val pipeline = pipeline(eventBus = bus, auditRecorder = cancellingRecorder)
        val request = request("/db help", traceId = "cancel-before-start")
        childJob = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { pipeline.handle("onebot", request) }
        childJob!!.start()
        childJob!!.join()
        assertTrue(childJob!!.isCancelled)
        assertEquals(0, commands)
        assertEquals(0, recorder.dispatchCalls)

        cancelBeforeStart = false
        pipeline.handle("onebot", request)

        assertEquals(1, commands)
        assertEquals(2, recorder.dispatchCalls)
        bus.shutdown()
    }

    @Test
    fun `cancelled processing must not replay possible side effects on redelivery`() = runBlocking {
        PersistenceManager.init(createTempDirectory("incoming-started-no-replay").resolve("test.db").toString())
        val bus = EventBus(Dispatchers.Unconfined)
        var commands = 0
        bus.subscribe(object : Listener<CommandEvent> {
            override suspend fun onMessage(event: CommandEvent) {
                commands++
                throw CancellationException("stopped after side effect")
            }
        })
        fun restarted() = IncomingMessagePipeline(
            configProvider = { MainDynamicConfig() }, linkParseService = LinkParseService(resolversProvider = { emptyList() }),
            eventBus = bus, incomingConsumerDispatcher = {},
        )
        val request = request("/db help", traceId = "started-before-restart")

        assertFailsWith<CancellationException> { restarted().handle("onebot", request) }
        restarted().handle("onebot", request)

        assertEquals(1, commands)
        bus.shutdown()
    }

    @Test
    fun `handle should dispatch messages with different trace ids`() = runBlocking {
        val eventBus = EventBus()
        val recordedTraceIds = mutableSetOf<String>()
        var dispatched = 0
        val pipeline = pipeline(
            eventBus = eventBus,
            incomingConsumerDispatcher = { dispatched += 1 },
            auditRecorder = IncomingMessageAuditRecorder { request ->
                synchronized(recordedTraceIds) { recordedTraceIds.add(request.traceId) }
            },
        )

        pipeline.handle(
            "onebot",
            IncomingMessagePublishRequest(
                message = testMessage(text = "/db help", messageId = "message-a"),
                traceId = "trace-a",
            ),
        )
        pipeline.handle(
            "onebot",
            IncomingMessagePublishRequest(
                message = testMessage(text = "/db help", messageId = "message-b"),
                traceId = "trace-b",
            ),
        )

        assertEquals(2, dispatched)
        eventBus.shutdown()
    }

    @Test
    fun `handle should route command messages only to command event and mark dispatch context`() = runBlocking {
        val eventBus = EventBus()
        val commandEvent = CompletableDeferred<CommandEvent>()
        val textEvent = CompletableDeferred<IncomingTextMessageEvent>()
        eventBus.subscribe(
            object : Listener<CommandEvent> {
                override suspend fun onMessage(event: CommandEvent) {
                    commandEvent.complete(event)
                }
            },
        )
        eventBus.subscribe(
            object : Listener<IncomingTextMessageEvent> {
                override suspend fun onMessage(event: IncomingTextMessageEvent) {
                    textEvent.complete(event)
                }
            },
        )
        val dispatch = CompletableDeferred<IncomingMessageDispatchContext>()
        val pipeline = pipeline(
            eventBus = eventBus,
            incomingConsumerDispatcher = { dispatch.complete(it) },
        )

        pipeline.handle("onebot", request(" /db help ", traceId = "trace-1", replyToMessageId = "reply-1"))

        val command = withTimeout(1_000) { commandEvent.await() }
        val context = withTimeout(1_000) { dispatch.await() }
        assertEquals("/db help", command.rawText)
        assertEquals("trace-1", command.traceId)
        assertEquals("reply-1", command.replyToMessageId)
        assertEquals("onebot", command.context.target.platformId.value)
        assertEquals(TargetKind.GROUP, command.context.target.kind)
        assertEquals("10001", command.context.target.externalId)
        assertEquals("sender", command.context.senderId)
        assertEquals(IncomingMessageIntent.Command, context.intent)
        assertEquals("reply-1", context.replyToMessageId)
        assertNull(withTimeoutOrNull(200) { textEvent.await() })
        eventBus.shutdown()
    }

    @Test
    fun `command should only dispatch on primary bot by default`() = runBlocking {
        val eventBus = EventBus()
        val commandEvent = CompletableDeferred<CommandEvent>()
        eventBus.subscribe(
            object : Listener<CommandEvent> {
                override suspend fun onMessage(event: CommandEvent) {
                    commandEvent.complete(event)
                }
            },
        )
        val dispatch = CompletableDeferred<IncomingMessageDispatchContext>()
        var primaryResolverSawAccountId: String? = "not-called"
        val pipeline = pipeline(
            eventBus = eventBus,
            incomingConsumerDispatcher = { dispatch.complete(it) },
            incomingBotAccountSelector = IncomingBotAccountSelector(
                primaryBotAccountResolver = { target ->
                    primaryResolverSawAccountId = target.accountId
                    "bot-a"
                },
            ),
        )

        pipeline.handle("onebot", request("/db help", botAccountId = "bot-b"))

        val context = withTimeout(1_000) { dispatch.await() }
        assertEquals(IncomingMessageIntent.Command, context.intent)
        assertEquals("bot-b", context.commandContext.botAccountId)
        assertEquals("bot-b", context.commandContext.target.accountId)
        assertNull(primaryResolverSawAccountId)
        assertNull(withTimeoutOrNull(200) { commandEvent.await() })
        eventBus.shutdown()
    }

    @Test
    fun `command should dispatch when current bot is primary`() = runBlocking {
        val eventBus = EventBus()
        val commandEvent = CompletableDeferred<CommandEvent>()
        eventBus.subscribe(
            object : Listener<CommandEvent> {
                override suspend fun onMessage(event: CommandEvent) {
                    commandEvent.complete(event)
                }
            },
        )
        val pipeline = pipeline(
            eventBus = eventBus,
            incomingBotAccountSelector = IncomingBotAccountSelector(
                primaryBotAccountResolver = { "bot-a" },
            ),
        )

        pipeline.handle("onebot", request("/db help", botAccountId = "bot-a"))

        val command = withTimeout(1_000) { commandEvent.await() }
        assertEquals("bot-a", command.context.botAccountId)
        assertEquals("bot-a", command.context.target.accountId)
        eventBus.shutdown()
    }

    @Test
    fun `command should dispatch when non primary bot is explicitly mentioned`() = runBlocking {
        val eventBus = EventBus()
        val commandEvent = CompletableDeferred<CommandEvent>()
        eventBus.subscribe(
            object : Listener<CommandEvent> {
                override suspend fun onMessage(event: CommandEvent) {
                    commandEvent.complete(event)
                }
            },
        )
        val pipeline = pipeline(
            eventBus = eventBus,
            incomingBotAccountSelector = IncomingBotAccountSelector(
                primaryBotAccountResolver = { "bot-a" },
                knownBotAccountIdsResolver = { setOf("bot-a", "bot-b") },
            ),
        )
        val message = testMessage(
            text = "@bot-b /db help",
            botAccountId = "bot-b",
            segments = listOf(
                IncomingMessageSegment.Mention("bot-b"),
                IncomingMessageSegment.Text(" /db help"),
            ),
            mentions = setOf("bot-b"),
        )

        pipeline.handle("onebot", IncomingMessagePublishRequest(message = message, traceId = "trace-mentioned"))

        val command = withTimeout(1_000) { commandEvent.await() }
        assertEquals("/db help", command.rawText)
        assertEquals("bot-b", command.context.botAccountId)
        eventBus.shutdown()
    }

    @Test
    fun `command should follow mentioned only mode`() = runBlocking {
        val eventBus = EventBus()
        val commandEvent = CompletableDeferred<CommandEvent>()
        eventBus.subscribe(
            object : Listener<CommandEvent> {
                override suspend fun onMessage(event: CommandEvent) {
                    commandEvent.complete(event)
                }
            },
        )
        val pipeline = pipeline(
            eventBus = eventBus,
            configProvider = {
                MainDynamicConfig(
                    command = CommandConfig(
                        prefix = "/db",
                        receiveMode = CommandReceiveMode.MENTIONED_ONLY,
                        requirePermissionRule = false,
                    ),
                    linkParsing = LinkParsingConfig(maxLinksPerMessage = 3),
                )
            },
            incomingBotAccountSelector = IncomingBotAccountSelector(
                primaryBotAccountResolver = { "bot-a" },
            ),
        )

        pipeline.handle("onebot", request("/db help", botAccountId = "bot-a"))

        assertNull(withTimeoutOrNull(200) { commandEvent.await() })
        eventBus.shutdown()
    }

    @Test
    fun `handle should strip leading bot mention before command parsing`() = runBlocking {
        val eventBus = EventBus()
        val commandEvent = CompletableDeferred<CommandEvent>()
        eventBus.subscribe(
            object : Listener<CommandEvent> {
                override suspend fun onMessage(event: CommandEvent) {
                    commandEvent.complete(event)
                }
            },
        )
        val pipeline = pipeline(eventBus = eventBus)
        val message = testMessage(
            text = "<@bot-member-openid> /db help",
            segments = listOf(
                IncomingMessageSegment.Mention("bot-1"),
                IncomingMessageSegment.Text(" /db help"),
            ),
            mentions = setOf("bot-1"),
        )

        pipeline.handle("qqbot", IncomingMessagePublishRequest(message = message, traceId = "trace-1"))

        val command = withTimeout(1_000) { commandEvent.await() }
        assertEquals("/db help", command.rawText)
        assertEquals(setOf("bot-1"), command.context.mentionedAccountIds)
        eventBus.shutdown()
    }

    @Test
    fun `handle should strip leading bot mention after whitespace segment`() = runBlocking {
        val eventBus = EventBus()
        val commandEvent = CompletableDeferred<CommandEvent>()
        eventBus.subscribe(
            object : Listener<CommandEvent> {
                override suspend fun onMessage(event: CommandEvent) {
                    commandEvent.complete(event)
                }
            },
        )
        val pipeline = pipeline(eventBus = eventBus)
        val message = testMessage(
            text = " <@bot-member-openid> /db help",
            segments = listOf(
                IncomingMessageSegment.Text(" "),
                IncomingMessageSegment.Mention("bot-1"),
                IncomingMessageSegment.Text(" /db help"),
            ),
            mentions = setOf("bot-1"),
        )

        pipeline.handle("qqbot", IncomingMessagePublishRequest(message = message, traceId = "trace-1"))

        val command = withTimeout(1_000) { commandEvent.await() }
        assertEquals("/db help", command.rawText)
        eventBus.shutdown()
    }

    @Test
    fun `blocked target should skip incoming events dispatch and audit`() = runBlocking {
        val eventBus = EventBus()
        val incomingEvent = CompletableDeferred<IncomingMessageEvent>()
        val commandEvent = CompletableDeferred<CommandEvent>()
        eventBus.subscribe(
            object : Listener<IncomingMessageEvent> {
                override suspend fun onMessage(event: IncomingMessageEvent) {
                    incomingEvent.complete(event)
                }
            },
        )
        eventBus.subscribe(
            object : Listener<CommandEvent> {
                override suspend fun onMessage(event: CommandEvent) {
                    commandEvent.complete(event)
                }
            },
        )
        var dispatchCalls = 0
        val recorder = RecordingAuditRecorder()
        val pipeline = pipeline(
            eventBus = eventBus,
            incomingConsumerDispatcher = { dispatchCalls += 1 },
            auditRecorder = recorder,
        )
        val target = TargetAddress.of("onebot", TargetKind.GROUP, "10001")
        SubscriberStateCache.update(target, SubscriberState.BLOCKED)

        try {
            pipeline.handle("onebot", request("/db help", traceId = "trace-blocked"))

            assertNull(withTimeoutOrNull(200) { incomingEvent.await() })
            assertNull(withTimeoutOrNull(200) { commandEvent.await() })
            assertEquals(0, dispatchCalls)
            assertEquals(0, recorder.records.size)
        } finally {
            SubscriberStateCache.remove(target)
            eventBus.shutdown()
        }
    }

    @Test
    fun `handle should route supported links as incoming text and mark link intent`() = runBlocking {
        val eventBus = EventBus()
        val textEvent = CompletableDeferred<IncomingTextMessageEvent>()
        eventBus.subscribe(
            object : Listener<IncomingTextMessageEvent> {
                override suspend fun onMessage(event: IncomingTextMessageEvent) {
                    textEvent.complete(event)
                }
            },
        )
        val dispatch = CompletableDeferred<IncomingMessageDispatchContext>()
        val resolver = MatchingLinkResolver(prefix = "https://t.bilibili.com/")
        val pipeline = pipeline(
            eventBus = eventBus,
            linkParseService = LinkParseService(resolversProvider = { listOf(resolver) }),
            incomingConsumerDispatcher = { dispatch.complete(it) },
        )

        pipeline.handle("onebot", request("  https://t.bilibili.com/123  "))

        val text = withTimeout(1_000) { textEvent.await() }
        val context = withTimeout(1_000) { dispatch.await() }
        assertEquals("https://t.bilibili.com/123", text.rawText)
        assertTrue(text.hasSupportedLinks)
        assertEquals(IncomingMessageIntent.LinkText, context.intent)
        assertEquals(1, resolver.matchesCalls)
        eventBus.shutdown()
    }

    @Test
    fun `platform card link should remain non text while disabled`() = runBlocking {
        val eventBus = EventBus()
        val textEvent = CompletableDeferred<IncomingTextMessageEvent>()
        eventBus.subscribe(
            object : Listener<IncomingTextMessageEvent> {
                override suspend fun onMessage(event: IncomingTextMessageEvent) {
                    textEvent.complete(event)
                }
            },
        )
        val dispatch = CompletableDeferred<IncomingMessageDispatchContext>()
        val recorder = RecordingAuditRecorder()
        val resolver = MatchingLinkResolver(prefix = "https://b23.tv/")
        val pipeline = pipeline(
            eventBus = eventBus,
            linkParseService = LinkParseService(resolversProvider = { listOf(resolver) }),
            incomingConsumerDispatcher = { dispatch.complete(it) },
            auditRecorder = recorder,
        )
        val message = testMessage(
            text = "",
            segments = listOf(IncomingMessageSegment.Link("https://b23.tv/demo", title = "测试视频")),
        )

        pipeline.handle("onebot", IncomingMessagePublishRequest(message = message, traceId = "trace-card-off"))

        assertEquals(IncomingMessageIntent.NonText, withTimeout(1_000) { dispatch.await() }.intent)
        assertEquals(0, recorder.records.size)
        assertEquals(0, recorder.dispatchCalls)
        assertNull(withTimeoutOrNull(200) { textEvent.await() })
        eventBus.shutdown()
    }

    @Test
    fun `enabled platform card link should become a supported link candidate`() = runBlocking {
        val eventBus = EventBus()
        val textEvent = CompletableDeferred<IncomingTextMessageEvent>()
        eventBus.subscribe(
            object : Listener<IncomingTextMessageEvent> {
                override suspend fun onMessage(event: IncomingTextMessageEvent) {
                    textEvent.complete(event)
                }
            },
        )
        val dispatch = CompletableDeferred<IncomingMessageDispatchContext>()
        val recorder = RecordingAuditRecorder()
        val resolver = MatchingLinkResolver(prefix = "https://b23.tv/")
        val pipeline = pipeline(
            eventBus = eventBus,
            configProvider = {
                MainDynamicConfig(
                    command = CommandConfig(prefix = "/db", requirePermissionRule = false),
                    linkParsing = LinkParsingConfig(
                        platformCardLinksEnabled = true,
                        maxLinksPerMessage = 3,
                    ),
                )
            },
            linkParseService = LinkParseService(resolversProvider = { listOf(resolver) }),
            incomingConsumerDispatcher = { dispatch.complete(it) },
            auditRecorder = recorder,
        )
        val message = testMessage(
            text = "",
            segments = listOf(IncomingMessageSegment.Link("https://b23.tv/demo", title = "测试视频")),
        )

        pipeline.handle("onebot", IncomingMessagePublishRequest(message = message, traceId = "trace-card-on"))

        val text = withTimeout(1_000) { textEvent.await() }
        assertEquals("", text.rawText)
        assertEquals(listOf("https://b23.tv/demo"), text.linkUrls)
        assertTrue(text.hasSupportedLinks)
        assertEquals(IncomingMessageIntent.LinkText, withTimeout(1_000) { dispatch.await() }.intent)
        assertEquals(IncomingMessageIntent.LinkText, recorder.records.single().intent)
        eventBus.shutdown()
    }

    @Test
    fun `handle should route ordinary text without command or link intent`() = runBlocking {
        val eventBus = EventBus()
        val textEvent = CompletableDeferred<IncomingTextMessageEvent>()
        eventBus.subscribe(
            object : Listener<IncomingTextMessageEvent> {
                override suspend fun onMessage(event: IncomingTextMessageEvent) {
                    textEvent.complete(event)
                }
            },
        )
        val dispatch = CompletableDeferred<IncomingMessageDispatchContext>()
        val pipeline = pipeline(
            eventBus = eventBus,
            incomingConsumerDispatcher = { dispatch.complete(it) },
        )

        pipeline.handle("onebot", request("hello"))

        val text = withTimeout(1_000) { textEvent.await() }
        val context = withTimeout(1_000) { dispatch.await() }
        assertEquals("hello", text.rawText)
        assertFalse(text.hasSupportedLinks)
        assertEquals(IncomingMessageIntent.PlainText, context.intent)
        eventBus.shutdown()
    }

    @Test
    fun `plain text should not record incoming audit by default`() = runBlocking {
        val recorder = RecordingAuditRecorder()
        val pipeline = pipeline(auditRecorder = recorder)

        pipeline.handle("onebot", request("hello"))

        assertEquals(0, recorder.records.size)
        assertEquals(0, recorder.dispatchCalls)
    }

    @Test
    fun `non text should not record incoming audit by default`() = runBlocking {
        val recorder = RecordingAuditRecorder()
        val pipeline = pipeline(auditRecorder = recorder)
        val request = IncomingMessagePublishRequest(
            message = testMessage(text = "", segments = listOf(IncomingMessageSegment.Image(file = "image.jpg"))),
            traceId = "trace-non-text",
        )

        pipeline.handle("onebot", request)

        assertEquals(0, recorder.records.size)
        assertEquals(0, recorder.dispatchCalls)
    }

    @Test
    fun `command should record audit policy`() = runBlocking {
        val recorder = RecordingAuditRecorder()
        val pipeline = pipeline(auditRecorder = recorder)

        pipeline.handle("onebot", request("/db help", traceId = "trace-command"))

        val record = recorder.records.single()
        assertEquals("trace-command", record.traceId)
        assertEquals(IncomingMessageIntent.Command, record.intent)
        assertEquals(IncomingMessageRecordPolicy.Audit, record.recordPolicy)
    }

    @Test
    fun `link text should record trace policy`() = runBlocking {
        val recorder = RecordingAuditRecorder()
        val resolver = MatchingLinkResolver(prefix = "https://t.bilibili.com/")
        val pipeline = pipeline(
            linkParseService = LinkParseService(resolversProvider = { listOf(resolver) }),
            auditRecorder = recorder,
        )

        pipeline.handle("onebot", request("https://t.bilibili.com/123", traceId = "trace-link"))

        val record = recorder.records.single()
        assertEquals("trace-link", record.traceId)
        assertEquals(IncomingMessageIntent.LinkText, record.intent)
        assertTrue(record.recordPolicy is IncomingMessageRecordPolicy.Trace)
    }

    @Test
    fun `plugin audit mode should elevate ordinary text to trace`() = runBlocking {
        val recorder = RecordingAuditRecorder()
        val pipeline = pipeline(
            auditModeResolver = { IncomingMessageAuditMode.TRACE },
            auditRecorder = recorder,
        )

        pipeline.handle("onebot", request("hello", traceId = "trace-plugin"))

        val record = recorder.records.single()
        assertEquals("trace-plugin", record.traceId)
        assertEquals(IncomingMessageIntent.PlainText, record.intent)
        assertTrue(record.recordPolicy is IncomingMessageRecordPolicy.Trace)
    }

    private fun pipeline(
        eventBus: EventBus = EventBus(),
        configProvider: () -> MainDynamicConfig = {
            MainDynamicConfig(
                command = CommandConfig(prefix = "/db", requirePermissionRule = false),
                linkParsing = LinkParsingConfig(maxLinksPerMessage = 3),
            )
        },
        linkParseService: LinkParseService = LinkParseService(resolversProvider = { emptyList() }),
        incomingConsumerDispatcher: suspend (IncomingMessageDispatchContext) -> Unit = {},
        auditModeResolver: suspend (IncomingMessageDispatchContext) -> IncomingMessageAuditMode = { IncomingMessageAuditMode.NONE },
        incomingBotAccountSelector: IncomingBotAccountSelector = IncomingBotAccountSelector(),
        auditRecorder: IncomingMessageAuditRecorder = IncomingMessageAuditRecorder { true },
        commandBootstrapService: CommandBootstrapService? = null,
        pendingSelectionResolver: ((CommandContext, String) -> PendingSubscriptionSelection?)? = null,
    ): IncomingMessagePipeline {
        return IncomingMessagePipeline(
            configProvider = configProvider,
            linkParseService = linkParseService,
            eventBus = eventBus,
            incomingConsumerDispatcher = incomingConsumerDispatcher,
            auditModeResolver = auditModeResolver,
            incomingBotAccountSelector = incomingBotAccountSelector,
            auditRecorder = auditRecorder,
            commandBootstrapService = commandBootstrapService,
            pendingSelectionResolver = pendingSelectionResolver,
        )
    }

    private fun request(
        text: String,
        traceId: String = "trace-1",
        replyToMessageId: String? = null,
        botAccountId: String? = "bot-1",
    ): IncomingMessagePublishRequest {
        return IncomingMessagePublishRequest(
            message = testMessage(text = text, botAccountId = botAccountId),
            traceId = traceId,
            replyToMessageId = replyToMessageId,
        )
    }

    private fun testMessage(
        text: String,
        messageId: String = "message-1",
        segments: List<IncomingMessageSegment> = listOf(IncomingMessageSegment.Text(text)),
        mentions: Set<String> = setOf("bot-1"),
        botAccountId: String? = "bot-1",
        targetKind: TargetKind = TargetKind.GROUP,
    ): IncomingMessage {
        return IncomingMessage(
            platformId = PlatformId.of("onebot"),
            target = TargetAddress.of("onebot", targetKind, "10001", accountId = botAccountId),
            senderId = "sender",
            botAccountId = botAccountId,
            messageId = messageId,
            text = text,
            segments = segments,
            mentions = mentions,
        )
    }

    private class MatchingLinkResolver(
        private val prefix: String,
    ) : LinkResolver {
        override val platformId: PlatformId = PlatformId.of("bilibili")
        var matchesCalls: Int = 0
            private set

        override fun matchesLink(inputUrl: String): Boolean {
            matchesCalls += 1
            return inputUrl.startsWith(prefix)
        }

        override suspend fun parseLink(inputUrl: String): ParsedLink {
            return ParsedLink(
                platformId = platformId,
                kind = LinkKinds.DYNAMIC,
                targetId = inputUrl.substringAfterLast('/'),
                normalizedUrl = inputUrl,
            )
        }

        override suspend fun resolveLink(parsedLink: ParsedLink): LinkResolution {
            return LinkResolution.Preview(
                parsedLink = parsedLink,
                preview = LinkPreview(
                    platformId = platformId,
                    kind = parsedLink.kind,
                    id = parsedLink.targetId,
                    url = parsedLink.normalizedUrl,
                    title = parsedLink.targetId,
                ),
            )
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        fun drain() { while (queued.isNotEmpty()) queued.removeFirst().run() }
    }

    private class RecordingAuditRecorder : IncomingMessageAuditRecorder {
        val records: MutableList<IncomingAuditWriteRequest> = mutableListOf()
        var dispatchCalls: Int = 0

        override fun tryBeginDispatch(traceId: String): Boolean {
            dispatchCalls++
            return true
        }

        override fun markDispatchCompleted(traceId: String) { dispatchCalls++ }

        override fun recordMessage(request: IncomingAuditWriteRequest): Boolean {
            records += request
            return true
        }
    }
}
