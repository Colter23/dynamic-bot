package top.colter.dynamic.link

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.colter.dynamic.LinkParseProgressReplyConfig
import top.colter.dynamic.LinkParseTriggerMode
import top.colter.dynamic.LinkParsingConfig
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.command.CommandListener
import top.colter.dynamic.command.CommandRegistry
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.event.CommandEvent
import top.colter.dynamic.event.CommandResultEvent
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.Listener
import top.colter.dynamic.core.event.SourceUpdatePublishRequest
import top.colter.dynamic.core.event.SourceUpdatePublishResult
import top.colter.dynamic.core.event.SourceUpdatePublisher
import top.colter.dynamic.core.link.DynamicLinkResolution
import top.colter.dynamic.core.link.DynamicLinkResolver
import top.colter.dynamic.core.link.ParsedDynamicLink
import top.colter.dynamic.repository.PersistenceManager
import top.colter.dynamic.repository.LinkParseTargetConfigRepository
import top.colter.dynamic.repository.PublisherRepository
import top.colter.dynamic.repository.SubscriberRepository
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testPublisherInfo
import top.colter.dynamic.testPublisherKey

class DynamicLinkForwarderTest {
    @Test
    fun `parse command should resolve and forward dynamic to current chat`() = runBlocking {
        initDb("parse-command")
        val eventBus = EventBus()
        val resolver = FakeDynamicLinkResolver()
        val sourceUpdates = RecordingSourceUpdatePublisher()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = MainDynamicConfig(),
            dynamicLinkForwarder = DynamicLinkForwarder(
                resolversProvider = { listOf(resolver) },
                sourceUpdatePublisher = sourceUpdates,
            ),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
        )

        val (commandResult, dynamicEvent) = dispatchCommandAndDynamic(
            eventBus,
            listener,
            commandEvent("/db parse https://t.bilibili.com/1"),
            sourceUpdates,
        )

        assertEquals(CommandStatus.SUCCESS, commandResult.status)
        assertTrue(renderMessage(commandResult).contains("已提交转发"))
        assertEquals(LINK_PARSE_EVENT_LABEL, dynamicEvent.deliveryTag)
        assertEquals("1", dynamicEvent.update.key.externalId)
        assertEquals("100", dynamicEvent.deliveryTarget?.externalId)
        assertNotNull(PublisherRepository.findByKey(PublisherKey.of("bilibili", externalId = "123")))
        assertNotNull(
            SubscriberRepository.findByAddress(
                TargetAddress.of("onebot", TargetKind.GROUP, "100"),
            ),
        )
    }

    @Test
    fun `parse command should report unsupported links`() = runBlocking {
        initDb("parse-unsupported")
        val eventBus = EventBus()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            config = MainDynamicConfig(),
            dynamicLinkForwarder = DynamicLinkForwarder(
                resolversProvider = { listOf(FakeDynamicLinkResolver()) },
                sourceUpdatePublisher = RecordingSourceUpdatePublisher(),
            ),
            commandRegistry = CommandRegistry(),
            eventBus = eventBus,
        )

        val commandResult = dispatchCommand(eventBus, listener, commandEvent("/db parse https://example.com/post/1"))

        assertEquals(CommandStatus.FAILED, commandResult.status)
    }

    @Test
    fun `auto parser should forward non command dynamic links`() = runBlocking {
        initDb("auto-forward")
        val eventBus = EventBus()
        val resolver = FakeDynamicLinkResolver()
        val sourceUpdates = RecordingSourceUpdatePublisher()
        val listener = DynamicLinkAutoParseListener(
            configProvider = { autoParseConfig(LinkParseTriggerMode.ALWAYS) },
            forwarder = DynamicLinkForwarder(
                resolversProvider = { listOf(resolver) },
                sourceUpdatePublisher = sourceUpdates,
            ),
            eventBus = eventBus,
        )

        listener.onMessage(commandEvent("look https://t.bilibili.com/1"))

        val event = withTimeout(3_000) { sourceUpdates.receive() }
        assertEquals("1", event.update.key.externalId)
        assertEquals(1, resolver.resolveCalls)
    }

    @Test
    fun `auto parser should ignore command messages dedupe and respect max one link`() = runBlocking {
        initDb("auto-dedupe")
        val eventBus = EventBus()
        val resolver = FakeDynamicLinkResolver()
        val sourceUpdates = RecordingSourceUpdatePublisher()
        val listener = DynamicLinkAutoParseListener(
            configProvider = { autoParseConfig(LinkParseTriggerMode.ALWAYS) },
            forwarder = DynamicLinkForwarder(
                resolversProvider = { listOf(resolver) },
                sourceUpdatePublisher = sourceUpdates,
            ),
            eventBus = eventBus,
        )

        listener.onMessage(commandEvent("/db parse https://t.bilibili.com/1"))
        assertEquals(0, resolver.resolveCalls)

        val event = commandEvent("https://t.bilibili.com/1")
        listener.onMessage(event)
        listener.onMessage(event)
        listener.onMessage(commandEvent("https://t.bilibili.com/2 https://t.bilibili.com/3"))

        assertEquals(listOf("1", "2"), resolver.resolvedUpdateIds)
    }

    @Test
    fun `auto parser should ignore links when global auto parsing is disabled`() = runBlocking {
        initDb("auto-disabled")
        val resolver = FakeDynamicLinkResolver()
        val listener = DynamicLinkAutoParseListener(
            configProvider = {
                MainDynamicConfig(
                    linkParsing = LinkParsingConfig(
                        autoParseEnabled = false,
                        fallbackTriggerMode = LinkParseTriggerMode.ALWAYS,
                    ),
                )
            },
            forwarder = DynamicLinkForwarder(
                resolversProvider = { listOf(resolver) },
                sourceUpdatePublisher = RecordingSourceUpdatePublisher(),
            ),
        )

        listener.onMessage(commandEvent("https://t.bilibili.com/1"))

        assertEquals(0, resolver.resolveCalls)
    }

    @Test
    fun `auto parser should require bot mention for mention fallback`() = runBlocking {
        initDb("auto-mention-fallback")
        val resolver = FakeDynamicLinkResolver()
        val sourceUpdates = RecordingSourceUpdatePublisher()
        val listener = DynamicLinkAutoParseListener(
            configProvider = { MainDynamicConfig() },
            forwarder = DynamicLinkForwarder(
                resolversProvider = { listOf(resolver) },
                sourceUpdatePublisher = sourceUpdates,
            ),
        )

        listener.onMessage(commandEvent("https://t.bilibili.com/1", botAccountId = "42"))
        assertEquals(0, resolver.resolveCalls)

        listener.onMessage(commandEvent("@42 https://t.bilibili.com/1", botAccountId = "42", mentionedAccountIds = setOf("42")))

        val event = withTimeout(3_000) { sourceUpdates.receive() }
        assertEquals("1", event.update.key.externalId)
        assertEquals(1, resolver.resolveCalls)
    }

    @Test
    fun `auto parser should use target trigger config before fallback`() = runBlocking {
        initDb("auto-target-trigger")
        val resolver = FakeDynamicLinkResolver()
        val sourceUpdates = RecordingSourceUpdatePublisher()
        val target = TargetAddress.of("onebot", TargetKind.GROUP, "100")
        LinkParseTargetConfigRepository.upsert(target, LinkParseTriggerMode.ALWAYS, updatedBy = "admin")
        val listener = DynamicLinkAutoParseListener(
            configProvider = { MainDynamicConfig() },
            forwarder = DynamicLinkForwarder(
                resolversProvider = { listOf(resolver) },
                sourceUpdatePublisher = sourceUpdates,
            ),
        )

        listener.onMessage(commandEvent("https://t.bilibili.com/1"))

        val event = withTimeout(3_000) { sourceUpdates.receive() }
        assertEquals("1", event.update.key.externalId)
    }

    @Test
    fun `auto parser should allow target config to disable fallback parsing`() = runBlocking {
        initDb("auto-target-disabled")
        val resolver = FakeDynamicLinkResolver()
        val target = TargetAddress.of("onebot", TargetKind.GROUP, "100")
        LinkParseTargetConfigRepository.upsert(target, LinkParseTriggerMode.DISABLED, updatedBy = "admin")
        val listener = DynamicLinkAutoParseListener(
            configProvider = { autoParseConfig(LinkParseTriggerMode.ALWAYS) },
            forwarder = DynamicLinkForwarder(
                resolversProvider = { listOf(resolver) },
                sourceUpdatePublisher = RecordingSourceUpdatePublisher(),
            ),
        )

        listener.onMessage(commandEvent("https://t.bilibili.com/1"))

        assertEquals(0, resolver.resolveCalls)
    }

    @Test
    fun `auto parser should send and recall progress prompt`() = runBlocking {
        initDb("auto-progress")
        val resolver = FakeDynamicLinkResolver()
        val sourceUpdates = RecordingSourceUpdatePublisher()
        val messenger = RecordingProgressMessenger()
        val listener = DynamicLinkAutoParseListener(
            configProvider = { autoParseConfig(LinkParseTriggerMode.ALWAYS) },
            forwarder = DynamicLinkForwarder(
                resolversProvider = { listOf(resolver) },
                sourceUpdatePublisher = sourceUpdates,
            ),
            progressMessenger = messenger,
        )

        listener.onMessage(commandEvent("https://t.bilibili.com/1"))
        withTimeout(3_000) { sourceUpdates.receive() }

        assertEquals(listOf("链接解析中，请稍候..."), messenger.sentTexts)
        assertEquals(listOf("progress-1"), messenger.recalledIds)
    }

    private suspend fun dispatchCommand(
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

    private suspend fun dispatchCommandAndDynamic(
        eventBus: EventBus,
        listener: CommandListener,
        event: CommandEvent,
        sourceUpdates: RecordingSourceUpdatePublisher,
    ): Pair<CommandResultEvent, SourceUpdatePublishRequest> {
        val commandResult = CompletableDeferred<CommandResultEvent>()
        eventBus.subscribe(
            object : Listener<CommandResultEvent> {
                override suspend fun onMessage(event: CommandResultEvent) {
                    commandResult.complete(event)
                }
            },
        )

        listener.onMessage(event)
        return Pair(
            withTimeout(3_000) { commandResult.await() },
            withTimeout(3_000) { sourceUpdates.receive() },
        )
    }

    private fun initDb(suffix: String) {
        val tempDir = createTempDirectory("dynamic-bot-main-link-$suffix").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)
    }

    private fun autoParseConfig(triggerMode: LinkParseTriggerMode): MainDynamicConfig {
        return MainDynamicConfig(
            linkParsing = LinkParsingConfig(
                fallbackTriggerMode = triggerMode,
                progressReply = LinkParseProgressReplyConfig(),
            ),
        )
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

    private fun renderMessage(result: CommandResultEvent): String {
        return result.chain.flatMap { it.content }.joinToString("\n") { it.fallbackText }
    }

    private class RecordingProgressMessenger : LinkParseProgressMessenger {
        val sentTexts: MutableList<String> = mutableListOf()
        val recalledIds: MutableList<String> = mutableListOf()

        override suspend fun send(
            event: CommandEvent,
            config: LinkParseProgressReplyConfig,
        ): LinkParseProgressReceipt? {
            sentTexts += config.text
            return LinkParseProgressReceipt(event.context.target, "progress-${sentTexts.size}")
        }

        override suspend fun recall(receipt: LinkParseProgressReceipt) {
            recalledIds += receipt.sinkMessageId
        }
    }

    private class RecordingSourceUpdatePublisher : SourceUpdatePublisher {
        private val received = CompletableDeferred<SourceUpdatePublishRequest>()

        override suspend fun publish(request: SourceUpdatePublishRequest): SourceUpdatePublishResult {
            if (!received.isCompleted) received.complete(request)
            return SourceUpdatePublishResult.enqueued(1)
        }

        suspend fun receive(): SourceUpdatePublishRequest = received.await()
    }

    private class FakeDynamicLinkResolver : DynamicLinkResolver {
        override val platformId: PlatformId = PlatformId.of("bilibili")
        var resolveCalls: Int = 0
            private set
        val resolvedUpdateIds: MutableList<String> = mutableListOf()

        override suspend fun parseDynamicLink(inputUrl: String): ParsedDynamicLink? {
            val updateId = inputUrl.substringAfter("https://t.bilibili.com/", missingDelimiterValue = "")
                .takeWhile { it.isDigit() }
                .takeIf { it.isNotBlank() }
                ?: return null
            return ParsedDynamicLink(
                platformId = platformId,
                updateId = updateId,
                normalizedUrl = "https://t.bilibili.com/$updateId",
                sourceUrl = inputUrl,
            )
        }

        override suspend fun resolveDynamicLink(parsedLink: ParsedDynamicLink): DynamicLinkResolution {
            resolveCalls += 1
            resolvedUpdateIds += parsedLink.updateId
            return DynamicLinkResolution.Success(parsedLink, demoUpdate(parsedLink))
        }

        private fun demoUpdate(parsedLink: ParsedDynamicLink) = testDynamicUpdate(
            publisher = testPublisherInfo(
                key = testPublisherKey(platformId = platformId.value, externalId = "123"),
                name = "demo-up",
            ),
            externalId = parsedLink.updateId,
        ).copy(link = parsedLink.normalizedUrl)
    }
}
