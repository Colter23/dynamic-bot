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
import top.colter.dynamic.command.CommandRegistry
import top.colter.dynamic.core.command.CommandSpec
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.SubscriberState
import top.colter.dynamic.core.data.Subscription
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.core.event.SourceUpdatePublishResult
import top.colter.dynamic.core.event.SourceUpdatePublisher
import top.colter.dynamic.core.event.SubscriptionChangeType
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.event.SystemNotificationPublishRequest
import top.colter.dynamic.core.event.SystemNotificationSeverity
import top.colter.dynamic.core.plugin.CORE_PLUGIN_API_VERSION
import top.colter.dynamic.core.plugin.CommandContributor
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.core.plugin.PluginAdminApiProvider
import top.colter.dynamic.core.plugin.PluginAdminApiRequest
import top.colter.dynamic.core.plugin.PluginAdminApiResponse
import top.colter.dynamic.core.plugin.PluginAdminPageDescriptor
import top.colter.dynamic.core.plugin.PluginAdminPageProvider
import top.colter.dynamic.core.plugin.Plugin
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.core.plugin.PublisherSourcePlugin
import top.colter.dynamic.core.plugin.SourceStateStore
import top.colter.dynamic.core.plugin.SubscriptionQueryService
import top.colter.dynamic.draw.resource.PlatformDrawAssetRegistry
import top.colter.dynamic.event.Listener
import top.colter.dynamic.event.SystemNotificationEvent

class PluginManagerLifecycleTest {

    @AfterTest
    fun cleanup() {
        LifecycleRecordingPlugin.reset()
        StartupNotificationPlugin.reset()
    }

    @Test
    fun loadShouldInferCapabilitiesAndPassPluginContext() {
        val pluginDir = createTempDirectory("plugin-manager-capability").toFile()
        val dataDir = createTempDirectory("plugin-manager-data")
        val eventBus = EventBus()
        val publisher = SourceUpdatePublisher { SourceUpdatePublishResult.ignored("test") }
        val manager = PluginManager(
            pluginDirPath = pluginDir.path,
            eventBus = eventBus,
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
        assertEquals(publisher, LifecycleRecordingPlugin.loadedSourceUpdatePublisher)
        assertEquals(dataDir.resolve("sink-plugin"), LifecycleRecordingPlugin.loadedDataDir)
        assertEquals(RepositorySourceStateStore, LifecycleRecordingPlugin.loadedSourceStateStore)
        assertEquals(RepositorySubscriptionQueryService, LifecycleRecordingPlugin.loadedSubscriptionQueryService)

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
            apiVersion = "3.0.0",
        )

        val result = manager.loadAllPlugins()

        assertTrue(result.loadedPlugins.isEmpty())
        assertTrue(result.failedPlugins.getValue("old-api").contains("不兼容的 apiVersion=3.0.0"))
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
    fun loadShouldExposePluginAdminPagesResourcesAndApiCapability() = runBlocking {
        val pluginDir = createTempDirectory("plugin-manager-admin-page").toFile()
        val manager = PluginManager(pluginDirPath = pluginDir.path)
        createPluginJar(
            pluginDir = pluginDir,
            id = "admin-page",
            mainClass = AdminPagePlugin::class.java.name,
            resourceEntries = mapOf(
                "web/index.html" to """<div data-page-root>admin page</div>""".toByteArray(Charsets.UTF_8),
                "web/index.js" to """export function mount() {}""".toByteArray(Charsets.UTF_8),
            ),
        )

        val result = manager.loadAllPlugins()
        manager.startAllPlugins()

        assertEquals(listOf("admin-page"), result.loadedPlugins)
        val info = manager.getAllPlugins().single()
        assertTrue(PluginCapability.ADMIN_PAGE in info.capabilities)
        assertTrue(PluginCapability.ADMIN_API in info.capabilities)

        val page = manager.getPluginAdminPages().single()
        assertEquals("admin-page:translate", page.pageKey)
        assertTrue(page.htmlPath.startsWith("/admin/plugins/admin-page/pages/translate/index.html?v=0.0.1-"))
        assertTrue(page.scriptPath.startsWith("/admin/plugins/admin-page/pages/translate/index.js?v=0.0.1-"))
        assertEquals("admin page", manager.readPluginAdminResource("admin-page", "translate", "index.html").bytes.toString(Charsets.UTF_8).substringAfter(">").substringBefore("<"))

        val response = manager.handlePluginAdminApi(
            "admin-page",
            PluginAdminApiRequest(method = "GET", path = "rules"),
        )
        assertEquals(PluginAdminApiResponse.ok().status, response.status)

        assertTrue(manager.unloadPlugin("admin-page"))
        assertTrue(manager.getPluginAdminPages().isEmpty())
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
    fun lifecycleFailureNotificationsShouldWaitUntilSystemNotificationsAreEnabled() = runBlocking {
        val eventBus = EventBus()
        eventBus.configureScope(this)
        val received = CompletableDeferred<SystemNotificationEvent>()
        eventBus.subscribe(
            object : Listener<SystemNotificationEvent> {
                override suspend fun onMessage(event: SystemNotificationEvent) {
                    received.complete(event)
                }
            },
        )
        val commandRegistry = CommandRegistry()
        commandRegistry.register(TestCommandHandler("existing"), "existing-owner")
        val manager = PluginManager(
            pluginDirPath = createTempDirectory("plugin-manager-notification-lifecycle").toString(),
            eventBus = eventBus,
            commandRegistry = commandRegistry,
        )

        manager.registerPluginForTest(descriptor("startup-failure", RollbackCommandPlugin()), RollbackCommandPlugin())
        manager.startPlugin("startup-failure")
        delay(100)
        assertFalse(received.isCompleted)

        manager.registerPluginForTest(descriptor("runtime-failure", RollbackCommandPlugin()), RollbackCommandPlugin())
        manager.enableSystemNotifications()
        manager.startPlugin("runtime-failure")

        val event = withTimeout(3_000) { received.await() }
        assertEquals("runtime-failure", event.sourcePlugin)
        assertEquals("plugin.lifecycle_failed", event.notification.type)
        assertEquals("start", event.notification.details["operation"])

        manager.shutdown()
        eventBus.shutdown()
    }

    @Test
    fun pluginPublishedNotificationsShouldBeIgnoredBeforeSystemNotificationsAreEnabled() = runBlocking {
        val pluginDir = createTempDirectory("plugin-manager-notification-publisher").toFile()
        val eventBus = EventBus()
        eventBus.configureScope(this)
        val received = CompletableDeferred<SystemNotificationEvent>()
        eventBus.subscribe(
            object : Listener<SystemNotificationEvent> {
                override suspend fun onMessage(event: SystemNotificationEvent) {
                    received.complete(event)
                }
            },
        )
        val manager = PluginManager(
            pluginDirPath = pluginDir.path,
            eventBus = eventBus,
        )
        createPluginJar(
            pluginDir = pluginDir,
            id = "startup-notifier",
            mainClass = StartupNotificationPlugin::class.java.name,
        )

        val result = manager.loadAllPlugins()

        assertEquals(listOf("startup-notifier"), result.loadedPlugins)
        assertEquals(false, StartupNotificationPlugin.publishResultAccepted)
        assertTrue(StartupNotificationPlugin.publishResultMessage?.contains("启动阶段") == true)
        delay(100)
        assertFalse(received.isCompleted)

        manager.shutdown()
        eventBus.shutdown()
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
    fun stopShouldDrainInFlightSubscriptionCallbacks() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(
            pluginDirPath = createTempDirectory("plugin-manager-drain").toString(),
            scope = scope,
            shutdownDrainTimeoutMs = 3000,
        )
        val plugin = BlockingSourcePlugin()
        manager.registerPluginForTest(
            descriptor = descriptor("blocking-source", plugin),
            instance = plugin,
            state = PluginState.ACTIVE,
        )

        manager.dispatchSubscriptionChangedToSources(demoSubscriptionChangedEvent())
        withTimeout(3_000) { plugin.started.await() }

        val stopJob = launch(Dispatchers.Default) { manager.stopPlugin("blocking-source") }
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

    private fun demoSubscriptionChangedEvent(): SubscriptionChangedEvent {
        return SubscriptionChangedEvent(
            changeType = SubscriptionChangeType.SUBSCRIBED,
            subscription = Subscription(
                id = 1,
                subscriberId = 1,
                publisherId = 1,
                createdAtEpochSeconds = 1,
                updatedAtEpochSeconds = 1,
            ),
            publisher = Publisher(
                id = 1,
                key = PublisherKey.of("bilibili", PublisherKind.USER, "100"),
                name = "publisher",
                avatar = MediaRef("https://example.com/avatar.png", MediaKind.AVATAR),
                createTime = 1,
                createUser = 0,
            ),
            subscriber = Subscriber(
                id = 1,
                address = TargetAddress.of("qq", TargetKind.GROUP, "100"),
                name = "group",
                state = SubscriberState.ACTIVE,
                createTime = 1,
                createUser = 0,
            ),
            changedAtEpochSeconds = 1,
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

    private class BlockingSourcePlugin : PublisherSourcePlugin {
        override val platformId: PlatformId = PlatformId.of("bilibili")
        val started: CompletableDeferred<Unit> = CompletableDeferred()
        val release: CompletableDeferred<Unit> = CompletableDeferred()
        var completed: Boolean = false

        override suspend fun onSubscriptionChanged(event: SubscriptionChangedEvent) {
            started.complete(Unit)
            release.await()
            completed = true
        }
    }

    class AdminPagePlugin : PluginAdminPageProvider, PluginAdminApiProvider {
        override val adminPages: List<PluginAdminPageDescriptor> = listOf(
            PluginAdminPageDescriptor(
                id = "translate",
                title = "Translate",
                subtitle = "Rules",
                navGroup = "Plugin",
                navIcon = "T",
            )
        )

        override suspend fun handleAdminApi(request: PluginAdminApiRequest): PluginAdminApiResponse {
            return PluginAdminApiResponse.ok()
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
        var loadedSourceUpdatePublisher: SourceUpdatePublisher? = null
        var loadedDataDir: java.nio.file.Path? = null
        var loadedSourceStateStore: SourceStateStore? = null
        var loadedSubscriptionQueryService: SubscriptionQueryService? = null

        fun reset() {
            calls.clear()
            loadedContextPluginId = null
            loadedContextApiVersion = null
            loadedSourceUpdatePublisher = null
            loadedDataDir = null
            loadedSourceStateStore = null
            loadedSubscriptionQueryService = null
        }
    }
}

class StartupNotificationPlugin : MessageSinkPlugin {
    override val transportId: String = "startup-notifier"
    override val supportedTargetPlatforms: Set<PlatformId> = setOf(PlatformId.of("qq"))

    override suspend fun onLoad(context: PluginContext) {
        val result = context.notificationPublisher.publish(
            SystemNotificationPublishRequest(
                type = "test.startup",
                severity = SystemNotificationSeverity.ERROR,
                title = "startup notification",
                content = "should be ignored before startup is complete",
            ),
        )
        publishResultAccepted = result.accepted
        publishResultMessage = result.message
    }

    companion object {
        var publishResultAccepted: Boolean? = null
        var publishResultMessage: String? = null

        fun reset() {
            publishResultAccepted = null
            publishResultMessage = null
        }
    }
}
