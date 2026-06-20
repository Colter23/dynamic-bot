package top.colter.dynamic.plugin

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.CORE_PLUGIN_API_VERSION
import top.colter.dynamic.core.plugin.IncomingBotDispatchPolicy
import top.colter.dynamic.core.plugin.IncomingMessageDispatchContext
import top.colter.dynamic.core.plugin.IncomingMessageConsumerPlugin
import top.colter.dynamic.core.plugin.IncomingMessageFilter
import top.colter.dynamic.core.plugin.IncomingMessageIntent
import top.colter.dynamic.core.plugin.IncomingMessagePublishRequest
import top.colter.dynamic.core.plugin.Plugin
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.core.plugin.PluginMessagePublishOptions
import top.colter.dynamic.core.plugin.PluginMessagePublishRequest
import top.colter.dynamic.core.plugin.PluginMessagePublishResult
import top.colter.dynamic.core.plugin.PluginMessagePublishSink

class PluginManagerIncomingMessageTest {
    @AfterTest
    fun cleanup() {
        PublishingLoadPlugin.reset()
    }

    @Test
    fun `dispatch incoming message should only reach active matching consumers`() = runBlocking {
        val manager = PluginManager(pluginDirPath = createTempDirectory("plugin-manager-incoming").toString())
        val active = RecordingConsumerPlugin()
        val loaded = RecordingConsumerPlugin()
        val failedState = RecordingConsumerPlugin()
        val filtered = RecordingConsumerPlugin(
            IncomingMessageFilter(platformIds = setOf(PlatformId.of("telegram"))),
        )
        val failing = FailingConsumerPlugin()
        manager.registerPluginForTest(descriptor("active", active), active, state = PluginState.ACTIVE)
        manager.registerPluginForTest(descriptor("loaded", loaded), loaded, state = PluginState.LOADED)
        manager.registerPluginForTest(descriptor("failed-state", failedState), failedState, state = PluginState.FAILED)
        manager.registerPluginForTest(descriptor("filtered", filtered), filtered, state = PluginState.ACTIVE)
        manager.registerPluginForTest(descriptor("failing", failing), failing, state = PluginState.ACTIVE)

        manager.dispatchIncomingMessageToConsumers(testDispatchContext())

        val received = withTimeout(1_000) { active.received.await() }
        assertEquals("hello", received.message.text)
        assertEquals("test", received.sourcePlugin)
        delay(100)
        assertFalse(loaded.received.isCompleted)
        assertFalse(failedState.received.isCompleted)
        assertFalse(filtered.received.isCompleted)
        assertEquals(1, failing.calls)

        manager.shutdown()
    }

    @Test
    fun `dispatch incoming message should respect intent filter flags`() = runBlocking {
        val manager = PluginManager(pluginDirPath = createTempDirectory("plugin-manager-incoming-intent").toString())
        val defaultConsumer = RecordingConsumerPlugin()
        val commandConsumer = RecordingConsumerPlugin(
            IncomingMessageFilter(receiveCommandMessages = true),
        )
        val linkConsumer = RecordingConsumerPlugin(
            IncomingMessageFilter(receiveLinkMessages = true),
        )
        manager.registerPluginForTest(descriptor("default", defaultConsumer), defaultConsumer, state = PluginState.ACTIVE)
        manager.registerPluginForTest(descriptor("command", commandConsumer), commandConsumer, state = PluginState.ACTIVE)
        manager.registerPluginForTest(descriptor("link", linkConsumer), linkConsumer, state = PluginState.ACTIVE)

        manager.dispatchIncomingMessageToConsumers(testDispatchContext("/db help", intent = IncomingMessageIntent.Command))

        withTimeout(1_000) { commandConsumer.received.await() }
        delay(100)
        assertFalse(defaultConsumer.received.isCompleted)
        assertFalse(linkConsumer.received.isCompleted)

        manager.dispatchIncomingMessageToConsumers(testDispatchContext("https://t.bilibili.com/1", intent = IncomingMessageIntent.LinkText))

        withTimeout(1_000) { linkConsumer.received.await() }
        delay(100)
        assertFalse(defaultConsumer.received.isCompleted)

        manager.shutdown()
    }

    @Test
    fun `context incoming publisher should force runtime plugin id as source`() = runBlocking {
        val pluginDir = createTempDirectory("plugin-manager-incoming-publisher").toFile()
        val captured = CompletableDeferred<Pair<String, IncomingMessagePublishRequest>>()
        val manager = PluginManager(
            pluginDirPath = pluginDir.path,
            incomingMessagePublishSink = { pluginId, request ->
                captured.complete(pluginId to request)
            },
        )
        createPluginJar(
            pluginDir = pluginDir,
            id = "incoming-publisher",
            mainClass = IncomingPublishingLoadPlugin::class.java.name,
        )

        val result = manager.loadAllPlugins()

        assertEquals(listOf("incoming-publisher"), result.loadedPlugins)
        assertTrue(result.failedPlugins.isEmpty())
        val (pluginId, request) = withTimeout(1_000) { captured.await() }
        assertEquals("incoming-publisher", pluginId)
        assertEquals("hello", request.message.text)
        assertEquals("trace-from-plugin", request.traceId)
        assertEquals("reply-from-plugin", request.replyToMessageId)

        manager.shutdown()
    }

    @Test
    fun `context message publisher should send through configured sink with plugin render variant`() = runBlocking {
        val pluginDir = createTempDirectory("plugin-manager-message-publisher").toFile()
        val captured = CompletableDeferred<PluginMessagePublishRequest>()
        val manager = PluginManager(
            pluginDirPath = pluginDir.path,
            pluginMessagePublishSink = PluginMessagePublishSink { request ->
                captured.complete(request)
                PluginMessagePublishResult.accepted(
                    messageId = "message-1",
                    targetCount = request.targets.size,
                    newDeliveryCount = request.targets.size,
                    existingDeliveryCount = 0,
                )
            },
        )
        createPluginJar(
            pluginDir = pluginDir,
            id = "publisher",
            mainClass = PublishingLoadPlugin::class.java.name,
        )

        val result = manager.loadAllPlugins()

        assertEquals(listOf("publisher"), result.loadedPlugins)
        assertTrue(result.failedPlugins.isEmpty())
        val request = withTimeout(1_000) { captured.await() }
        assertEquals("publisher", request.sourcePlugin)
        assertEquals("publisher", request.renderVariant)
        assertEquals(PluginMessagePublishOptions(retry = false, expiresInSeconds = 30), request.options)
        assertEquals(TargetAddress.of("qq", TargetKind.GROUP, "10001"), request.targets.single())
        assertEquals("hello", assertIs<MessageContent.Text>(request.batches.single().content.single()).fallbackText)
        assertEquals(true, PublishingLoadPlugin.publishAccepted)

        manager.shutdown()
    }

    @Test
    fun `incoming message dispatch should bound pending messages per consumer plugin`() = runBlocking {
        val manager = PluginManager(
            pluginDirPath = createTempDirectory("plugin-manager-incoming-limit").toString(),
            incomingMessagePendingLimit = 2,
        )
        val slow = SlowConsumerPlugin()
        manager.registerPluginForTest(descriptor("slow", slow), slow, state = PluginState.ACTIVE)

        repeat(5) { index ->
            manager.dispatchIncomingMessageToConsumers(testDispatchContext("message-$index"))
        }

        withTimeout(1_000) { slow.firstStarted.await() }
        delay(100)
        assertEquals(1, slow.calls.get())

        slow.release.complete(Unit)
        withTimeout(1_000) {
            while (slow.calls.get() < 2) {
                delay(10)
            }
        }
        delay(100)
        assertEquals(2, slow.calls.get())

        manager.shutdown()
    }

    @Test
    fun `canonical incoming consumer should only receive primary bot event`() = runBlocking {
        val manager = PluginManager(
            pluginDirPath = createTempDirectory("plugin-manager-incoming-canonical").toString(),
            primaryBotAccountResolver = { "bot-a" },
        )
        val consumer = RecordingConsumerPlugin(
            IncomingMessageFilter(botDispatchPolicy = IncomingBotDispatchPolicy.CANONICAL),
        )
        manager.registerPluginForTest(descriptor("canonical", consumer), consumer, state = PluginState.ACTIVE)

        manager.dispatchIncomingMessageToConsumers(testDispatchContext(botAccountId = "bot-b"))
        delay(100)
        assertFalse(consumer.received.isCompleted)

        manager.dispatchIncomingMessageToConsumers(testDispatchContext(botAccountId = "bot-a"))

        val received = withTimeout(1_000) { consumer.received.await() }
        assertEquals("bot-a", received.message.botAccountId)

        manager.shutdown()
    }

    @Test
    fun `canonical incoming consumer should prefer explicitly mentioned bot`() = runBlocking {
        val manager = PluginManager(
            pluginDirPath = createTempDirectory("plugin-manager-incoming-mentioned").toString(),
            primaryBotAccountResolver = { "bot-a" },
            knownBotAccountIdsResolver = { setOf("bot-a", "bot-b") },
        )
        val primary = RecordingConsumerPlugin()
        val mentioned = RecordingConsumerPlugin()
        manager.registerPluginForTest(descriptor("primary", primary), primary, state = PluginState.ACTIVE)
        manager.registerPluginForTest(descriptor("mentioned", mentioned), mentioned, state = PluginState.ACTIVE)

        manager.dispatchIncomingMessageToConsumers(
            testDispatchContext(botAccountId = "bot-a", mentionedAccountIds = setOf("bot-b")),
        )
        delay(100)
        assertFalse(primary.received.isCompleted)

        manager.dispatchIncomingMessageToConsumers(
            testDispatchContext(botAccountId = "bot-b", mentionedAccountIds = setOf("bot-b")),
        )

        val received = withTimeout(1_000) { primary.received.await() }
        assertEquals("bot-b", received.message.botAccountId)
        assertTrue(mentioned.received.isCompleted)

        manager.shutdown()
    }

    @Test
    fun `all receivers incoming consumer should bypass canonical bot selection`() = runBlocking {
        val manager = PluginManager(
            pluginDirPath = createTempDirectory("plugin-manager-incoming-all").toString(),
            primaryBotAccountResolver = { "bot-a" },
        )
        val consumer = RecordingConsumerPlugin(
            IncomingMessageFilter(botDispatchPolicy = IncomingBotDispatchPolicy.ALL_RECEIVERS),
        )
        manager.registerPluginForTest(descriptor("all", consumer), consumer, state = PluginState.ACTIVE)

        manager.dispatchIncomingMessageToConsumers(testDispatchContext(botAccountId = "bot-b"))

        val received = withTimeout(1_000) { consumer.received.await() }
        assertEquals("bot-b", received.message.botAccountId)

        manager.shutdown()
    }

    private fun descriptor(id: String, plugin: Plugin): PluginDescriptor {
        return PluginDescriptor(
            id = id,
            name = id,
            version = "test",
            mainClass = plugin::class.java.name,
        )
    }

    private fun testIncomingMessage(
        text: String = "hello",
        botAccountId: String? = null,
        mentionedAccountIds: Set<String> = emptySet(),
    ): IncomingMessage {
        return IncomingMessage(
            platformId = PlatformId.of("qq"),
            target = TargetAddress.of("qq", TargetKind.GROUP, "10001"),
            senderId = "20001",
            botAccountId = botAccountId,
            text = text,
            mentions = mentionedAccountIds,
        )
    }

    private fun testDispatchContext(
        text: String = "hello",
        intent: IncomingMessageIntent = IncomingMessageIntent.PlainText,
        botAccountId: String? = null,
        mentionedAccountIds: Set<String> = emptySet(),
    ): IncomingMessageDispatchContext {
        val message = testIncomingMessage(text, botAccountId, mentionedAccountIds)
        return IncomingMessageDispatchContext(
            message = message,
            sourcePlugin = "test",
            traceId = "trace",
            replyToMessageId = "reply",
            commandContext = CommandContext(
                target = message.target,
                senderId = message.senderId,
                botAccountId = message.botAccountId,
                mentionedAccountIds = message.mentions,
            ),
            rawText = text.trim(),
            intent = intent,
        )
    }

    private fun createPluginJar(pluginDir: File, id: String, mainClass: String) {
        val yaml = buildString {
            appendLine("id: $id")
            appendLine("name: Test Plugin $id")
            appendLine("version: 0.0.1")
            appendLine("mainClass: $mainClass")
            appendLine("apiVersion: $CORE_PLUGIN_API_VERSION")
        }
        JarOutputStream(FileOutputStream(pluginDir.resolve("$id.jar"))).use { output ->
            output.putNextEntry(JarEntry("plugin.yml"))
            output.write(yaml.toByteArray(Charsets.UTF_8))
            output.closeEntry()
        }
    }

    private class RecordingConsumerPlugin(
        override val incomingMessageFilter: IncomingMessageFilter = IncomingMessageFilter(),
    ) : IncomingMessageConsumerPlugin {
        val received: CompletableDeferred<IncomingMessageDispatchContext> = CompletableDeferred()

        override suspend fun onIncomingMessage(context: IncomingMessageDispatchContext) {
            received.complete(context)
        }
    }

    private class FailingConsumerPlugin : IncomingMessageConsumerPlugin {
        var calls: Int = 0

        override suspend fun onIncomingMessage(context: IncomingMessageDispatchContext) {
            calls += 1
            error("boom")
        }
    }

    private class SlowConsumerPlugin : IncomingMessageConsumerPlugin {
        val firstStarted: CompletableDeferred<Unit> = CompletableDeferred()
        val release: CompletableDeferred<Unit> = CompletableDeferred()
        val calls: AtomicInteger = AtomicInteger(0)

        override suspend fun onIncomingMessage(context: IncomingMessageDispatchContext) {
            calls.incrementAndGet()
            firstStarted.complete(Unit)
            release.await()
        }
    }
}

class PublishingLoadPlugin : IncomingMessageConsumerPlugin {
    override suspend fun onLoad(context: PluginContext) {
        val result = context.messagePublisher.sendText(
            target = TargetAddress.of("qq", TargetKind.GROUP, "10001"),
            text = "hello",
            options = PluginMessagePublishOptions(retry = false, expiresInSeconds = 30),
        )
        publishAccepted = result.accepted
    }

    override suspend fun onIncomingMessage(context: IncomingMessageDispatchContext) {
    }

    companion object {
        var publishAccepted: Boolean? = null

        fun reset() {
            publishAccepted = null
        }
    }
}

class IncomingPublishingLoadPlugin : IncomingMessageConsumerPlugin {
    override suspend fun onLoad(context: PluginContext) {
        context.incomingMessagePublisher.publish(
            IncomingMessagePublishRequest(
                message = IncomingMessage(
                    platformId = PlatformId.of("qq"),
                    target = TargetAddress.of("qq", TargetKind.GROUP, "10001"),
                    senderId = "20001",
                    text = "hello",
                ),
                traceId = "trace-from-plugin",
                replyToMessageId = "reply-from-plugin",
            ),
        )
    }

    override suspend fun onIncomingMessage(context: IncomingMessageDispatchContext) {
    }
}
