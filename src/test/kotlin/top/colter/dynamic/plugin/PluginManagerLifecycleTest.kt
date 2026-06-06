package top.colter.dynamic.plugin

import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.colter.dynamic.core.command.CommandExecutionResult
import top.colter.dynamic.core.command.CommandHandler
import top.colter.dynamic.core.command.CommandInvocation
import top.colter.dynamic.core.command.CommandPublisher
import top.colter.dynamic.command.CommandRegistry
import top.colter.dynamic.core.command.CommandSpec
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.MessageEvent
import top.colter.dynamic.core.event.SourceUpdatePublishResult
import top.colter.dynamic.core.event.SourceUpdatePublisher
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.plugin.CORE_PLUGIN_API_VERSION
import top.colter.dynamic.core.plugin.CommandContributor
import top.colter.dynamic.core.plugin.MessageDeliveryRequest
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.core.plugin.Plugin
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.core.plugin.PublisherSourcePlugin
import top.colter.dynamic.core.plugin.SourceStateStore
import top.colter.dynamic.core.plugin.SubscriptionQueryService
import top.colter.dynamic.draw.resource.PlatformDrawAssetRegistry

class PluginManagerLifecycleTest {

    @AfterTest
    fun cleanup() {
        LifecycleRecordingPlugin.reset()
    }

    @Test
    fun loadShouldInferCapabilitiesAndPassPluginContext() {
        val pluginDir = createTempDirectory("plugin-manager-capability").toFile()
        val dataDir = createTempDirectory("plugin-manager-data")
        val eventBus = EventBus()
        val commandPublisher = CommandPublisher { }
        val publisher = SourceUpdatePublisher { SourceUpdatePublishResult.ignored("test") }
        val manager = PluginManager(
            pluginDirPath = pluginDir.path,
            eventBus = eventBus,
            commandPublisher = commandPublisher,
            sourceUpdatePublisher = publisher,
            pluginDataDirPath = dataDir.toString(),
            sourceStateStore = RepositorySourceStateStore,
            subscriptionQueryService = RepositorySubscriptionQueryService,
        )
        createPluginJar(
            pluginDir = pluginDir,
            id = "sink-plugin",
            mainClass = LifecycleRecordingPlugin::class.java.name,
        )

        val result = manager.loadAllPlugins()

        assertEquals(listOf("sink-plugin"), result.loadedPlugins)
        assertTrue(result.failedPlugins.isEmpty())
        assertEquals(listOf("load:sink-plugin"), LifecycleRecordingPlugin.calls)
        assertEquals("sink-plugin", LifecycleRecordingPlugin.loadedContextPluginId)
        assertEquals(CORE_PLUGIN_API_VERSION, LifecycleRecordingPlugin.loadedContextApiVersion)
        assertSame(commandPublisher, LifecycleRecordingPlugin.loadedCommandPublisher)
        assertSame(publisher, LifecycleRecordingPlugin.loadedSourceUpdatePublisher)
        assertEquals(dataDir.resolve("sink-plugin"), LifecycleRecordingPlugin.loadedDataDir)
        assertSame(RepositorySourceStateStore, LifecycleRecordingPlugin.loadedSourceStateStore)
        assertSame(RepositorySubscriptionQueryService, LifecycleRecordingPlugin.loadedSubscriptionQueryService)

        val loaded = manager.getAllPlugins().single()
        assertEquals(setOf(PluginCapability.MESSAGE_SINK), loaded.capabilities)
        assertEquals(PluginState.LOADED, loaded.state)

        manager.startAllPlugins()
        assertEquals(PluginState.ACTIVE, manager.getAllPlugins().single().state)
        assertTrue(manager.unloadPlugin("sink-plugin"))
        assertEquals(listOf("load:sink-plugin", "start", "stop", "unload"), LifecycleRecordingPlugin.calls)

        manager.shutdown()
        eventBus.shutdown()
    }

    @Test
    fun loadShouldRejectNonExactApiVersion() {
        val pluginDir = createTempDirectory("plugin-manager-api-version").toFile()
        val manager = PluginManager(pluginDirPath = pluginDir.path)
        createPluginJar(
            pluginDir = pluginDir,
            id = "old-api",
            mainClass = LifecycleRecordingPlugin::class.java.name,
            apiVersion = "2.3.0",
        )

        val result = manager.loadAllPlugins()

        assertTrue(result.loadedPlugins.isEmpty())
        assertTrue(result.failedPlugins.getValue("old-api").contains("不支持的 apiVersion=2.3.0"))
    }

    @Test
    fun loadShouldRejectDuplicatedPluginIdBeforeLoading() {
        val pluginDir = createTempDirectory("plugin-manager-duplicate").toFile()
        val manager = PluginManager(pluginDirPath = pluginDir.path)
        createPluginJar(
            pluginDir = pluginDir,
            id = "duplicate",
            mainClass = LifecycleRecordingPlugin::class.java.name,
            fileName = "a.jar",
        )
        createPluginJar(
            pluginDir = pluginDir,
            id = "duplicate",
            mainClass = LifecycleRecordingPlugin::class.java.name,
            fileName = "b.jar",
        )

        val result = manager.loadAllPlugins()

        assertTrue(result.loadedPlugins.isEmpty())
        assertEquals("插件 ID 重复：duplicate", result.failedPlugins["duplicate"])
        assertTrue(LifecycleRecordingPlugin.calls.isEmpty())
    }

    @Test
    fun loadShouldRegisterAndUnloadPluginDrawAssets() {
        val pluginDir = createTempDirectory("plugin-manager-draw-assets").toFile()
        val drawAssetRegistry = PlatformDrawAssetRegistry()
        val manager = PluginManager(
            pluginDirPath = pluginDir.path,
            drawAssetRegistry = drawAssetRegistry,
        )
        createPluginJar(
            pluginDir = pluginDir,
            id = "draw-assets",
            mainClass = LifecycleRecordingPlugin::class.java.name,
            extraYaml = """
                drawAssets:
                  - platformId: bilibili
                    key: avatarBadge.official.individual
                    kind: AVATAR_BADGE
                    resourcePath: draw/test/badge.svg
                    mimeType: image/svg+xml
            """.trimIndent(),
            resourceEntries = mapOf("draw/test/badge.svg" to TEST_BADGE_SVG.toByteArray(Charsets.UTF_8)),
        )

        val result = manager.loadAllPlugins()

        assertEquals(listOf("draw-assets"), result.loadedPlugins)
        assertNotNull(drawAssetRegistry.image(PlatformId.of("bilibili"), "avatarBadge.official.individual", 24, 24))
        assertTrue(manager.unloadPlugin("draw-assets"))
        assertNull(drawAssetRegistry.image(PlatformId.of("bilibili"), "avatarBadge.official.individual", 24, 24))
        manager.shutdown()
    }

    @Test
    fun constructorShouldNotRegisterSubscriptionListenerUntilStart() {
        val eventBus = EventBus()
        val manager = PluginManager(
            pluginDirPath = createTempDirectory("plugin-manager-listener").toString(),
            eventBus = eventBus,
        )

        assertEquals(0, eventBus.listenerCount<SubscriptionChangedEvent>())

        val source = TestSourcePlugin()
        manager.registerPluginForTest(descriptor("source", source), source)
        assertEquals(0, eventBus.listenerCount<SubscriptionChangedEvent>())

        manager.startAllPlugins()
        assertEquals(1, eventBus.listenerCount<SubscriptionChangedEvent>())

        manager.shutdown()
        assertEquals(0, eventBus.listenerCount<SubscriptionChangedEvent>())
        eventBus.shutdown()
    }

    @Test
    fun commandRegistrationFailureShouldRollbackPluginCommandsAndStopPlugin() {
        val commandRegistry = CommandRegistry()
        val manager = PluginManager(
            pluginDirPath = createTempDirectory("plugin-manager-command").toString(),
            commandRegistry = commandRegistry,
        )
        val existingHandler = TestCommandHandler("existing")
        commandRegistry.register(existingHandler, "existing-owner")
        val plugin = RollbackCommandPlugin()
        manager.registerPluginForTest(descriptor("rollback", plugin), plugin)

        manager.startPlugin("rollback")

        val info = manager.getAllPlugins().single { it.descriptor.id == "rollback" }
        assertEquals(PluginState.FAILED, info.state)
        assertEquals(1, plugin.startCalls)
        assertEquals(1, plugin.stopCalls)
        assertEquals(null, commandRegistry.match(listOf("unique")))
        assertNotNull(commandRegistry.match(listOf("existing")))
    }

    @Test
    fun startShouldFailWhenPluginHookTimesOut() {
        val manager = PluginManager(
            pluginDirPath = createTempDirectory("plugin-manager-hook-timeout").toString(),
            pluginHookTimeoutMs = 50,
        )
        val plugin = HangingStartPlugin()
        manager.registerPluginForTest(descriptor("hanging", plugin), plugin)

        manager.startPlugin("hanging")

        val info = manager.getAllPlugins().single()
        assertEquals(PluginState.FAILED, info.state)
        assertTrue(info.error?.message?.contains("超时") == true)

        manager.shutdown()
    }

    @Test
    fun stopShouldDrainInFlightSinkCallbacks() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(
            pluginDirPath = createTempDirectory("plugin-manager-drain").toString(),
            scope = scope,
            sinkMaxConcurrency = 1,
            shutdownDrainTimeoutMs = 3000,
        )
        val plugin = BlockingSinkPlugin()
        manager.registerPluginForTest(
            descriptor = descriptor("blocking-sink", plugin),
            instance = plugin,
            state = PluginState.ACTIVE,
        )

        manager.dispatchMessageToSinks(demoMessageEvent())
        withTimeout(3_000) { plugin.started.await() }

        val stopJob = launch(Dispatchers.Default) { manager.stopPlugin("blocking-sink") }
        delay(100)
        assertFalse(stopJob.isCompleted)

        plugin.release.complete(Unit)
        withTimeout(3_000) { stopJob.join() }

        assertTrue(plugin.completed)
        assertEquals(PluginState.LOADED, manager.getAllPlugins().single().state)

        manager.shutdown()
        scope.cancel()
    }

    private fun createPluginJar(
        pluginDir: File,
        id: String,
        mainClass: String,
        apiVersion: String = CORE_PLUGIN_API_VERSION,
        fileName: String = "$id.jar",
        extraYaml: String = "",
        resourceEntries: Map<String, ByteArray> = emptyMap(),
    ) {
        val yaml = buildString {
            appendLine("id: $id")
            appendLine("name: Test Plugin $id")
            appendLine("version: 0.0.1")
            appendLine("mainClass: $mainClass")
            appendLine("apiVersion: $apiVersion")
            if (extraYaml.isNotBlank()) {
                appendLine(extraYaml)
            }
        }

        JarOutputStream(FileOutputStream(pluginDir.resolve(fileName))).use { output ->
            output.putNextEntry(JarEntry("plugin.yml"))
            output.write(yaml.toByteArray(Charsets.UTF_8))
            output.closeEntry()
            resourceEntries.forEach { (path, bytes) ->
                output.putNextEntry(JarEntry(path))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private fun descriptor(id: String, plugin: Plugin): PluginDescriptor {
        return PluginDescriptor(
            id = id,
            name = id,
            version = "test",
            mainClass = plugin::class.java.name,
        )
    }

    private fun demoMessageEvent(): MessageEvent {
        return MessageEvent(
            sourcePlugin = "test",
            message = Message(
                id = "message-1",
                time = 1,
                targets = listOf(TargetAddress.of("qq", TargetKind.GROUP, "100")),
                batches = listOf(MessageBatch(listOf(MessageContent.Text("hello")))),
            ),
        )
    }

    private companion object {
        private const val TEST_BADGE_SVG: String =
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><circle cx="50" cy="50" r="48" fill="#00a1d6"/></svg>"""
    }

    private class TestSourcePlugin : PublisherSourcePlugin {
        override val platformId: PlatformId = PlatformId.of("bilibili")
    }

    private class HangingStartPlugin : Plugin {
        override suspend fun onStart() {
            CompletableDeferred<Unit>().await()
        }
    }

    private class RollbackCommandPlugin : CommandContributor {
        var startCalls = 0
        var stopCalls = 0

        override suspend fun onStart() {
            startCalls += 1
        }

        override suspend fun onStop() {
            stopCalls += 1
        }

        override fun commandHandlers(): Collection<CommandHandler> {
            return listOf(TestCommandHandler("unique"), TestCommandHandler("existing"))
        }
    }

    private class TestCommandHandler(path: String) : CommandHandler {
        override val spec: CommandSpec = CommandSpec(path = listOf(path))

        override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
            return CommandExecutionResult.success("ok")
        }
    }

    private class BlockingSinkPlugin : MessageSinkPlugin {
        override val transportId: String = "onebot"
        override val supportedTargetPlatforms: Set<PlatformId> = setOf(PlatformId.of("qq"))
        val started: CompletableDeferred<Unit> = CompletableDeferred()
        val release: CompletableDeferred<Unit> = CompletableDeferred()
        var completed: Boolean = false

        override suspend fun sendMessage(request: MessageDeliveryRequest): MessageSendResult {
            started.complete(Unit)
            release.await()
            completed = true
            return MessageSendResult.sent()
        }
    }
}

class LifecycleRecordingPlugin : MessageSinkPlugin {
    override val transportId: String = "onebot"
    override val supportedTargetPlatforms: Set<PlatformId> = setOf(PlatformId.of("qq"))

    override suspend fun onLoad(context: PluginContext) {
        calls += "load:${context.pluginId}"
        loadedContextPluginId = context.pluginId
        loadedContextApiVersion = context.descriptor.apiVersion
        loadedCommandPublisher = context.commandPublisher
        loadedSourceUpdatePublisher = context.sourceUpdatePublisher
        loadedDataDir = context.dataStore.dataDir
        loadedSourceStateStore = context.sourceStateStore
        loadedSubscriptionQueryService = context.subscriptionQueryService
    }

    override suspend fun onStart() {
        calls += "start"
    }

    override suspend fun onStop() {
        calls += "stop"
    }

    override suspend fun onUnload() {
        calls += "unload"
    }

    companion object {
        val calls: MutableList<String> = mutableListOf()
        var loadedContextPluginId: String? = null
        var loadedContextApiVersion: String? = null
        var loadedCommandPublisher: CommandPublisher? = null
        var loadedSourceUpdatePublisher: SourceUpdatePublisher? = null
        var loadedDataDir: java.nio.file.Path? = null
        var loadedSourceStateStore: SourceStateStore? = null
        var loadedSubscriptionQueryService: SubscriptionQueryService? = null

        fun reset() {
            calls.clear()
            loadedContextPluginId = null
            loadedContextApiVersion = null
            loadedCommandPublisher = null
            loadedSourceUpdatePublisher = null
            loadedDataDir = null
            loadedSourceStateStore = null
            loadedSubscriptionQueryService = null
        }
    }
}
