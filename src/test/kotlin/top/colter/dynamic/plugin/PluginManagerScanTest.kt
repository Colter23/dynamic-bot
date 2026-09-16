package top.colter.dynamic.plugin

import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.event.SourceUpdatePublishResult
import top.colter.dynamic.core.event.SourceUpdatePublisher
import top.colter.dynamic.core.plugin.CORE_PLUGIN_API_VERSION
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.event.EventBus

/**
 * 运行中热加载新插件（对应后台插件页的"扫描新插件"）。
 *
 * 核心约束：`loadPlugin` 内部有 `require(!plugins.containsKey(id))`，对已加载的插件会抛
 * "插件 ID 重复"，因此扫描**不能**直接重跑 `loadAllPlugins`，必须跳过已加载的插件。
 */
class PluginManagerScanTest {

    @AfterTest
    fun cleanup() {
        LifecycleRecordingPlugin.reset()
    }

    @Test
    fun scanShouldLoadAndStartPluginAddedAfterStartup() {
        val pluginDir = createTempDirectory("plugin-scan-late").toFile()
        val eventBus = EventBus()
        val manager = newManager(pluginDir, eventBus)

        // 启动时目录为空
        assertEquals(emptyList(), manager.loadAllPlugins().loadedPlugins)
        manager.startAllPlugins()

        // 运行中才把 jar 放进插件目录
        createPluginJar(pluginDir, id = "late-plugin", mainClass = LifecycleRecordingPlugin::class.java.name)

        val result = manager.scanAndLoadNewPlugins()

        assertEquals(listOf("late-plugin"), result.loadedPlugins)
        assertTrue(result.failedPlugins.isEmpty(), "不应有失败：${result.failedPlugins}")
        assertTrue(result.skippedPlugins.isEmpty())
        assertEquals(PluginState.ACTIVE, manager.getAllPlugins().single().state)
        assertEquals(listOf("load:late-plugin", "start"), LifecycleRecordingPlugin.calls)

        manager.shutdown()
        eventBus.shutdown()
    }

    @Test
    fun scanShouldSkipAlreadyLoadedPluginsInsteadOfReportingDuplicate() {
        val pluginDir = createTempDirectory("plugin-scan-skip").toFile()
        val eventBus = EventBus()
        val manager = newManager(pluginDir, eventBus)
        createPluginJar(pluginDir, id = "existing", mainClass = LifecycleRecordingPlugin::class.java.name)

        assertEquals(listOf("existing"), manager.loadAllPlugins().loadedPlugins)
        manager.startAllPlugins()
        LifecycleRecordingPlugin.reset()

        val result = manager.scanAndLoadNewPlugins()

        assertEquals(emptyList(), result.loadedPlugins)
        assertTrue(result.failedPlugins.isEmpty(), "已加载的插件不应被记为失败：${result.failedPlugins}")
        assertEquals(listOf("existing"), result.skippedPlugins)
        assertEquals(PluginState.ACTIVE, manager.getAllPlugins().single().state)
        assertEquals(emptyList(), LifecycleRecordingPlugin.calls, "被跳过的插件不应重新加载或启动")

        manager.shutdown()
        eventBus.shutdown()
    }

    @Test
    fun scanShouldReturnEmptyResultWhenNothingNew() {
        val pluginDir = createTempDirectory("plugin-scan-empty").toFile()
        val eventBus = EventBus()
        val manager = newManager(pluginDir, eventBus)

        val result = manager.scanAndLoadNewPlugins()

        assertEquals(emptyList(), result.loadedPlugins)
        assertTrue(result.failedPlugins.isEmpty())
        assertTrue(result.skippedPlugins.isEmpty())

        manager.shutdown()
        eventBus.shutdown()
    }

    @Test
    fun scanShouldReportDuplicatedPluginIdsWithoutLoading() {
        val pluginDir = createTempDirectory("plugin-scan-duplicate").toFile()
        val eventBus = EventBus()
        val manager = newManager(pluginDir, eventBus)
        val mainClass = LifecycleRecordingPlugin::class.java.name
        createPluginJar(pluginDir, id = "dup", fileName = "dup-a.jar", mainClass = mainClass)
        createPluginJar(pluginDir, id = "dup", fileName = "dup-b.jar", mainClass = mainClass)

        val result = manager.scanAndLoadNewPlugins()

        assertEquals(emptyList(), result.loadedPlugins)
        assertTrue(result.failedPlugins.containsKey("dup"), "重复 ID 应记为失败：${result.failedPlugins}")
        assertTrue(manager.getAllPlugins().isEmpty())
        assertEquals(emptyList(), LifecycleRecordingPlugin.calls)

        manager.shutdown()
        eventBus.shutdown()
    }

    @Test
    fun scanShouldReportPluginWhoseStartFailed() {
        val pluginDir = createTempDirectory("plugin-scan-start-failed").toFile()
        val eventBus = EventBus()
        val manager = newManager(pluginDir, eventBus)
        createPluginJar(pluginDir, id = "bad-start", mainClass = ScanFailingStartPlugin::class.java.name)

        val result = manager.scanAndLoadNewPlugins()

        // startPlugin 内部会捕获异常并置为 FAILED、不会向外抛，所以必须按最终状态判定成败
        assertEquals(emptyList(), result.loadedPlugins)
        assertTrue(result.failedPlugins.containsKey("bad-start"), "启动失败应记为失败：${result.failedPlugins}")
        assertEquals(PluginState.FAILED, manager.getAllPlugins().single().state)

        manager.shutdown()
        eventBus.shutdown()
    }

    private fun newManager(pluginDir: File, eventBus: EventBus): PluginManager {
        return PluginManager(
            pluginDirPath = pluginDir.path,
            eventBus = eventBus,
            sourceUpdatePublisher = SourceUpdatePublisher { SourceUpdatePublishResult.ignored("test") },
            pluginDataDirPath = createTempDirectory("plugin-scan-data").toString(),
            sourceStateStore = RepositorySourceStateStore,
            subscriptionQueryService = RepositorySubscriptionQueryService,
        )
    }

    private fun createPluginJar(
        pluginDir: File,
        id: String,
        mainClass: String,
        fileName: String = "$id.jar",
        apiVersion: String = CORE_PLUGIN_API_VERSION,
    ) {
        val yaml = buildString {
            appendLine("id: $id")
            appendLine("name: Test Plugin $id")
            appendLine("version: 0.0.1")
            appendLine("mainClass: $mainClass")
            appendLine("apiVersion: $apiVersion")
        }
        JarOutputStream(FileOutputStream(pluginDir.resolve(fileName))).use { output ->
            output.putNextEntry(JarEntry("plugin.yml"))
            output.write(yaml.toByteArray(Charsets.UTF_8))
            output.closeEntry()
        }
    }
}

/** `onStart` 抛异常的插件，用于验证扫描按最终状态判定成败。 */
class ScanFailingStartPlugin : MessageSinkPlugin {
    override val transportId: String = "scan-failing"
    override val supportedTargetPlatforms: Set<PlatformId> = setOf(PlatformId.of("qq"))

    override suspend fun onStart() {
        throw IllegalStateException("模拟启动失败")
    }
}
