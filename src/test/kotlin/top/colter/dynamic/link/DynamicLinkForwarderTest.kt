package top.colter.dynamic.link

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.command.CommandListener
import top.colter.dynamic.core.command.CommandRegistry
import top.colter.dynamic.core.data.ChatType
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.PlatformKind
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherType
import top.colter.dynamic.core.data.SubscriberType
import top.colter.dynamic.core.event.CommandEvent
import top.colter.dynamic.core.event.CommandResultEvent
import top.colter.dynamic.core.event.EventManger
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.SourceUpdateEvent
import top.colter.dynamic.core.event.register
import top.colter.dynamic.core.link.DynamicLinkResolution
import top.colter.dynamic.core.link.DynamicLinkResolver
import top.colter.dynamic.core.link.ParsedDynamicLink
import top.colter.dynamic.core.repository.PersistenceManager
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.SubscriberRepository
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DynamicLinkForwarderTest {
    @AfterTest
    fun cleanup() {
        EventManger.shutdown()
        CommandRegistry.clear()
    }

    @Test
    fun `parse command should resolve and forward dynamic to current chat`() = runBlocking {
        initDb("parse-command")
        CommandRegistry.clear()
        val resolver = FakeDynamicLinkResolver()
        val listener = CommandListener(
            platformPluginResolver = { null },
            dynamicLinkForwarder = DynamicLinkForwarder { listOf(resolver) },
        )

        val (commandResult, dynamicEvent) = dispatchCommandAndDynamic(
            listener,
            commandEvent("/db parse https://t.bilibili.com/1"),
        )

        assertEquals(CommandStatus.SUCCESS, commandResult.status)
        assertTrue(renderMessage(commandResult).contains("已提交转发"))
        assertEquals(LINK_PARSE_EVENT_LABEL, dynamicEvent.label)
        assertEquals("1", (dynamicEvent.update as Dynamic).dynamicId)
        assertEquals("100", dynamicEvent.target?.targetId)
        assertNotNull(PublisherRepository.findByPlatformAndExternalId("bilibili", "123"))
        assertNotNull(SubscriberRepository.findByPlatformAndTarget("onebot", SubscriberType.GROUP, "100"))
    }

    @Test
    fun `parse command should report unsupported links`() = runBlocking {
        initDb("parse-unsupported")
        CommandRegistry.clear()
        val listener = CommandListener(
            platformPluginResolver = { null },
            dynamicLinkForwarder = DynamicLinkForwarder { listOf(FakeDynamicLinkResolver()) },
        )

        val commandResult = dispatchCommand(listener, commandEvent("/db parse https://example.com/post/1"))

        assertEquals(CommandStatus.FAILED, commandResult.status)
        assertTrue(renderMessage(commandResult).contains("未找到支持的动态链接"))
    }

    @Test
    fun `auto parser should forward non command dynamic links`() = runBlocking {
        initDb("auto-forward")
        val resolver = FakeDynamicLinkResolver()
        val listener = DynamicLinkAutoParseListener(
            configProvider = { MainDynamicConfig() },
            forwarder = DynamicLinkForwarder { listOf(resolver) },
        )

        EventManger.shutdown()
        val dynamic = CompletableDeferred<SourceUpdateEvent>()
        object : Listener<SourceUpdateEvent> {
            override suspend fun onMessage(event: SourceUpdateEvent) {
                dynamic.complete(event)
            }
        }.register<SourceUpdateEvent>()

        listener.onMessage(commandEvent("look https://t.bilibili.com/1"))

        val event = withTimeout(3_000) { dynamic.await() }
        assertEquals("1", (event.update as Dynamic).dynamicId)
        assertEquals(1, resolver.resolveCalls)
    }

    @Test
    fun `auto parser should ignore command messages`() = runBlocking {
        initDb("auto-ignore-command")
        val resolver = FakeDynamicLinkResolver()
        val listener = DynamicLinkAutoParseListener(
            configProvider = { MainDynamicConfig() },
            forwarder = DynamicLinkForwarder { listOf(resolver) },
        )

        listener.onMessage(commandEvent("/db parse https://t.bilibili.com/1"))

        assertEquals(0, resolver.resolveCalls)
    }

    @Test
    fun `auto parser should dedupe the same chat and dynamic link`() = runBlocking {
        initDb("auto-dedupe")
        val resolver = FakeDynamicLinkResolver()
        val listener = DynamicLinkAutoParseListener(
            configProvider = { MainDynamicConfig() },
            forwarder = DynamicLinkForwarder { listOf(resolver) },
        )

        val event = commandEvent("https://t.bilibili.com/1")
        listener.onMessage(event)
        listener.onMessage(event)

        assertEquals(1, resolver.resolveCalls)
    }

    @Test
    fun `auto parser should process only one dynamic link by default`() = runBlocking {
        initDb("auto-one-link")
        val resolver = FakeDynamicLinkResolver()
        val listener = DynamicLinkAutoParseListener(
            configProvider = { MainDynamicConfig() },
            forwarder = DynamicLinkForwarder { listOf(resolver) },
        )

        listener.onMessage(commandEvent("https://t.bilibili.com/1 https://t.bilibili.com/2"))

        assertEquals(listOf("1"), resolver.resolvedDynamicIds)
    }

    private suspend fun dispatchCommand(
        listener: CommandListener,
        event: CommandEvent,
    ): CommandResultEvent {
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

    private suspend fun dispatchCommandAndDynamic(
        listener: CommandListener,
        event: CommandEvent,
    ): Pair<CommandResultEvent, SourceUpdateEvent> {
        EventManger.shutdown()
        val commandResult = CompletableDeferred<CommandResultEvent>()
        val dynamic = CompletableDeferred<SourceUpdateEvent>()
        object : Listener<CommandResultEvent> {
            override suspend fun onMessage(event: CommandResultEvent) {
                commandResult.complete(event)
            }
        }.register<CommandResultEvent>()
        object : Listener<SourceUpdateEvent> {
            override suspend fun onMessage(event: SourceUpdateEvent) {
                dynamic.complete(event)
            }
        }.register<SourceUpdateEvent>()

        listener.onMessage(event)
        return Pair(
            withTimeout(3_000) { commandResult.await() },
            withTimeout(3_000) { dynamic.await() },
        )
    }

    private fun initDb(suffix: String) {
        val tempDir = createTempDirectory("dynamic-bot-main-link-$suffix").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)
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

    private class FakeDynamicLinkResolver : DynamicLinkResolver {
        override val platformId: String = "bilibili"
        var resolveCalls: Int = 0
            private set
        val resolvedDynamicIds: MutableList<String> = mutableListOf()

        override suspend fun parseDynamicLink(inputUrl: String): ParsedDynamicLink? {
            val dynamicId = inputUrl.substringAfter("https://t.bilibili.com/", missingDelimiterValue = "")
                .takeWhile { it.isDigit() }
                .takeIf { it.isNotBlank() }
                ?: return null
            return ParsedDynamicLink(
                platformId = platformId,
                dynamicId = dynamicId,
                normalizedUrl = "https://t.bilibili.com/$dynamicId",
                sourceUrl = inputUrl,
            )
        }

        override suspend fun resolveDynamicLink(parsedLink: ParsedDynamicLink): DynamicLinkResolution {
            resolveCalls += 1
            resolvedDynamicIds += parsedLink.dynamicId
            return DynamicLinkResolution.Success(parsedLink, demoDynamic(parsedLink))
        }

        private fun demoDynamic(parsedLink: ParsedDynamicLink): Dynamic {
            return Dynamic(
                platform = PlatformDescriptor(
                    id = platformId,
                    name = "Bilibili",
                    homepage = "https://www.bilibili.com",
                    iconUri = "https://www.bilibili.com/favicon.ico",
                    kind = PlatformKind.PUBLISHER,
                ),
                dynamicId = parsedLink.dynamicId,
                publisher = Publisher(
                    id = 0,
                    platformId = platformId,
                    type = PublisherType.USER,
                    externalId = "123",
                    name = "demo-up",
                    face = LazyImage("https://example.com/face.png"),
                    createTime = 0,
                    createUser = 0,
                ),
                time = 1,
                link = parsedLink.normalizedUrl,
            )
        }
    }
}
