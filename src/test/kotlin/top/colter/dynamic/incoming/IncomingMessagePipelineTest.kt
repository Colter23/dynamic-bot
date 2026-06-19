package top.colter.dynamic.incoming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import top.colter.dynamic.CommandConfig
import top.colter.dynamic.LinkParsingConfig
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.IncomingMessageSegment
import top.colter.dynamic.core.data.PlatformId
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
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.IncomingMessageEvent
import top.colter.dynamic.event.IncomingTextMessageEvent
import top.colter.dynamic.event.Listener
import top.colter.dynamic.link.LinkParseService

class IncomingMessagePipelineTest {
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

    private fun pipeline(
        eventBus: EventBus = EventBus(),
        linkParseService: LinkParseService = LinkParseService(resolversProvider = { emptyList() }),
        incomingConsumerDispatcher: (IncomingMessageDispatchContext) -> Unit = {},
    ): IncomingMessagePipeline {
        return IncomingMessagePipeline(
            configProvider = {
                MainDynamicConfig(
                    command = CommandConfig(prefix = "/db", requirePermissionRule = false),
                    linkParsing = LinkParsingConfig(maxLinksPerMessage = 3),
                )
            },
            linkParseService = linkParseService,
            eventBus = eventBus,
            incomingConsumerDispatcher = incomingConsumerDispatcher,
        )
    }

    private fun request(
        text: String,
        traceId: String = "trace-1",
        replyToMessageId: String? = null,
    ): IncomingMessagePublishRequest {
        return IncomingMessagePublishRequest(
            message = testMessage(text = text),
            traceId = traceId,
            replyToMessageId = replyToMessageId,
        )
    }

    private fun testMessage(
        text: String,
        messageId: String = "message-1",
        segments: List<IncomingMessageSegment> = listOf(IncomingMessageSegment.Text(text)),
        mentions: Set<String> = setOf("bot-1"),
    ): IncomingMessage {
        return IncomingMessage(
            platformId = PlatformId.of("onebot"),
            target = TargetAddress.of("onebot", TargetKind.GROUP, "10001"),
            senderId = "sender",
            botAccountId = "bot-1",
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
}
