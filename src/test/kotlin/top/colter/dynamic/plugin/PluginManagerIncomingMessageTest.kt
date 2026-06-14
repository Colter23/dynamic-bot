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
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.CORE_PLUGIN_API_VERSION
import top.colter.dynamic.core.plugin.IncomingMessageConsumerPlugin
import top.colter.dynamic.core.plugin.IncomingMessageFilter
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

        manager.dispatchIncomingMessageToConsumers(testIncomingMessage())

        val received = withTimeout(1_000) { active.received.await() }
        assertEquals("hello", received.text)
        delay(100)
        assertFalse(loaded.received.isCompleted)
        assertFalse(failedState.received.isCompleted)
        assertFalse(filtered.received.isCompleted)
        assertEquals(1, failing.calls)

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
            manager.dispatchIncomingMessageToConsumers(testIncomingMessage("message-$index"))
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

    private fun descriptor(id: String, plugin: Plugin): PluginDescriptor {
        return PluginDescriptor(
            id = id,
            name = id,
            version = "test",
            mainClass = plugin::class.java.name,
        )
    }

    private fun testIncomingMessage(text: String = "hello"): IncomingMessage {
        return IncomingMessage(
            platformId = PlatformId.of("qq"),
            target = TargetAddress.of("qq", TargetKind.GROUP, "10001"),
            senderId = "20001",
            text = text,
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
        val received: CompletableDeferred<IncomingMessage> = CompletableDeferred()

        override suspend fun onIncomingMessage(message: IncomingMessage) {
            received.complete(message)
        }
    }

    private class FailingConsumerPlugin : IncomingMessageConsumerPlugin {
        var calls: Int = 0

        override suspend fun onIncomingMessage(message: IncomingMessage) {
            calls += 1
            error("boom")
        }
    }

    private class SlowConsumerPlugin : IncomingMessageConsumerPlugin {
        val firstStarted: CompletableDeferred<Unit> = CompletableDeferred()
        val release: CompletableDeferred<Unit> = CompletableDeferred()
        val calls: AtomicInteger = AtomicInteger(0)

        override suspend fun onIncomingMessage(message: IncomingMessage) {
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

    override suspend fun onIncomingMessage(message: IncomingMessage) {
    }

    companion object {
        var publishAccepted: Boolean? = null

        fun reset() {
            publishAccepted = null
        }
    }
}
