package top.colter.dynamic.link

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
import top.colter.dynamic.command.CommandListener
import top.colter.dynamic.core.command.CommandRegistry
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.event.CommandEvent
import top.colter.dynamic.core.event.CommandResultEvent
import top.colter.dynamic.core.event.EventBus
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.SourceUpdateEvent
import top.colter.dynamic.core.event.register
import top.colter.dynamic.core.link.DynamicLinkResolution
import top.colter.dynamic.core.link.DynamicLinkResolver
import top.colter.dynamic.core.link.ParsedDynamicLink
import top.colter.dynamic.core.repository.PersistenceManager
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.SubscriberRepository
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testPublisherInfo
import top.colter.dynamic.testPublisherKey

class DynamicLinkForwarderTest {
    @AfterTest
    fun cleanup() {
        EventBus.global.shutdown()
        CommandRegistry.clear()
    }

    @Test
    fun `parse command should resolve and forward dynamic to current chat`() = runBlocking {
        initDb("parse-command")
        CommandRegistry.clear()
        val resolver = FakeDynamicLinkResolver()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            dynamicLinkForwarder = DynamicLinkForwarder { listOf(resolver) },
        )

        val (commandResult, dynamicEvent) = dispatchCommandAndDynamic(
            listener,
            commandEvent("/db parse https://t.bilibili.com/1"),
        )

        assertEquals(CommandStatus.SUCCESS, commandResult.status)
        assertTrue(renderMessage(commandResult).contains("submitted"))
        assertEquals(LINK_PARSE_EVENT_LABEL, dynamicEvent.label)
        assertEquals("1", dynamicEvent.update.key.externalId)
        assertEquals("100", dynamicEvent.targetOverride?.externalId)
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
        CommandRegistry.clear()
        val listener = CommandListener(
            publisherLookupResolver = { null },
            dynamicLinkForwarder = DynamicLinkForwarder { listOf(FakeDynamicLinkResolver()) },
        )

        val commandResult = dispatchCommand(listener, commandEvent("/db parse https://example.com/post/1"))

        assertEquals(CommandStatus.FAILED, commandResult.status)
    }

    @Test
    fun `auto parser should forward non command dynamic links`() = runBlocking {
        initDb("auto-forward")
        val resolver = FakeDynamicLinkResolver()
        val listener = DynamicLinkAutoParseListener(
            configProvider = { MainDynamicConfig() },
            forwarder = DynamicLinkForwarder { listOf(resolver) },
        )
        val dynamic = CompletableDeferred<SourceUpdateEvent>()
        object : Listener<SourceUpdateEvent> {
            override suspend fun onMessage(event: SourceUpdateEvent) {
                dynamic.complete(event)
            }
        }.register<SourceUpdateEvent>()

        listener.onMessage(commandEvent("look https://t.bilibili.com/1"))

        val event = withTimeout(3_000) { dynamic.await() }
        assertEquals("1", event.update.key.externalId)
        assertEquals(1, resolver.resolveCalls)
    }

    @Test
    fun `auto parser should ignore command messages dedupe and respect max one link`() = runBlocking {
        initDb("auto-dedupe")
        val resolver = FakeDynamicLinkResolver()
        val listener = DynamicLinkAutoParseListener(
            configProvider = { MainDynamicConfig() },
            forwarder = DynamicLinkForwarder { listOf(resolver) },
        )

        listener.onMessage(commandEvent("/db parse https://t.bilibili.com/1"))
        assertEquals(0, resolver.resolveCalls)

        val event = commandEvent("https://t.bilibili.com/1")
        listener.onMessage(event)
        listener.onMessage(event)
        listener.onMessage(commandEvent("https://t.bilibili.com/2 https://t.bilibili.com/3"))

        assertEquals(listOf("1", "2"), resolver.resolvedUpdateIds)
    }

    private suspend fun dispatchCommand(
        listener: CommandListener,
        event: CommandEvent,
    ): CommandResultEvent {
        EventBus.global.shutdown()
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
        EventBus.global.shutdown()
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
        return result.chain.flatMap { it.content }.joinToString("\n") { it.fallbackText }
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
                platformId = platformId.value,
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
