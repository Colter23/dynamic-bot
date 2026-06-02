package top.colter.dynamic.plugin

import top.colter.dynamic.core.plugin.CORE_PLUGIN_API_VERSION
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginManagerInstallUpdateTest {

    @BeforeTest
    fun setup() {
        LifecycleRecordingPlugin.reset()
    }

    @AfterTest
    fun cleanup() {
        LifecycleRecordingPlugin.reset()
    }

    @Test
    fun installOrUpdatePluginJarShouldInstallAndStartNewPlugin() {
        val pluginDir = createTempDirectory("plugin-manager-install").toFile()
        val downloadDir = createTempDirectory("plugin-manager-download").toFile()
        val manager = PluginManager(pluginDirPath = pluginDir.path)
        val downloaded = createPluginJar(
            pluginDir = downloadDir,
            id = "catalog-plugin",
            version = "1.0.0",
            fileName = "catalog-plugin.tmp",
        )

        val result = manager.installOrUpdatePluginJar(
            downloadedJar = downloaded,
            expectedPluginId = "catalog-plugin",
            expectedVersion = "1.0.0",
        )

        assertTrue(result.success)
        assertEquals("1.0.0", result.newVersion)
        assertEquals(PluginState.ACTIVE, manager.getAllPlugins().single().state)
        assertEquals("1.0.0", manager.getAllPlugins().single().descriptor.version)
        assertTrue(pluginDir.resolve("catalog-plugin.jar").exists())
        assertFalse(downloaded.exists())
        assertEquals(listOf("load:catalog-plugin", "start"), LifecycleRecordingPlugin.calls)
    }

    @Test
    fun installOrUpdatePluginJarShouldKeepActivePluginActiveAfterUpdate() {
        val pluginDir = createTempDirectory("plugin-manager-update-active").toFile()
        val downloadDir = createTempDirectory("plugin-manager-update-download").toFile()
        val manager = PluginManager(pluginDirPath = pluginDir.path)
        createPluginJar(
            pluginDir = pluginDir,
            id = "catalog-plugin",
            version = "1.0.0",
        )
        manager.loadAllPlugins()
        manager.startAllPlugins()
        LifecycleRecordingPlugin.reset()

        val downloaded = createPluginJar(
            pluginDir = downloadDir,
            id = "catalog-plugin",
            version = "1.1.0",
            fileName = "catalog-plugin-new.tmp",
        )

        val result = manager.installOrUpdatePluginJar(
            downloadedJar = downloaded,
            expectedPluginId = "catalog-plugin",
            expectedVersion = "1.1.0",
            requireInstalled = true,
        )

        val info = manager.getAllPlugins().single()
        assertTrue(result.success)
        assertEquals("1.0.0", result.oldVersion)
        assertEquals("1.1.0", result.newVersion)
        assertEquals("1.1.0", info.descriptor.version)
        assertEquals(PluginState.ACTIVE, info.state)
        assertEquals(
            listOf("stop", "unload", "load:catalog-plugin", "start"),
            LifecycleRecordingPlugin.calls,
        )
    }

    @Test
    fun installOrUpdatePluginJarShouldKeepLoadedPluginLoadedAfterUpdate() {
        val pluginDir = createTempDirectory("plugin-manager-update-loaded").toFile()
        val downloadDir = createTempDirectory("plugin-manager-update-loaded-download").toFile()
        val manager = PluginManager(pluginDirPath = pluginDir.path)
        createPluginJar(
            pluginDir = pluginDir,
            id = "catalog-plugin",
            version = "1.0.0",
        )
        manager.loadAllPlugins()
        LifecycleRecordingPlugin.reset()

        val downloaded = createPluginJar(
            pluginDir = downloadDir,
            id = "catalog-plugin",
            version = "1.1.0",
            fileName = "catalog-plugin-new.tmp",
        )

        manager.installOrUpdatePluginJar(
            downloadedJar = downloaded,
            expectedPluginId = "catalog-plugin",
            expectedVersion = "1.1.0",
            requireInstalled = true,
        )

        val info = manager.getAllPlugins().single()
        assertEquals("1.1.0", info.descriptor.version)
        assertEquals(PluginState.LOADED, info.state)
        assertEquals(listOf("unload", "load:catalog-plugin"), LifecycleRecordingPlugin.calls)
    }

    @Test
    fun installOrUpdatePluginJarShouldRestoreOldActivePluginWhenUpdateFails() {
        val pluginDir = createTempDirectory("plugin-manager-update-rollback").toFile()
        val downloadDir = createTempDirectory("plugin-manager-update-rollback-download").toFile()
        val manager = PluginManager(pluginDirPath = pluginDir.path)
        createPluginJar(
            pluginDir = pluginDir,
            id = "catalog-plugin",
            version = "1.0.0",
        )
        manager.loadAllPlugins()
        manager.startAllPlugins()
        LifecycleRecordingPlugin.reset()

        val downloaded = createPluginJar(
            pluginDir = downloadDir,
            id = "catalog-plugin",
            version = "1.1.0",
            fileName = "catalog-plugin-broken.tmp",
            mainClass = "missing.Plugin",
        )

        assertFailsWith<ClassNotFoundException> {
            manager.installOrUpdatePluginJar(
                downloadedJar = downloaded,
                expectedPluginId = "catalog-plugin",
                expectedVersion = "1.1.0",
                requireInstalled = true,
            )
        }

        val info = manager.getAllPlugins().single()
        assertEquals("1.0.0", info.descriptor.version)
        assertEquals(PluginState.ACTIVE, info.state)
        assertEquals(
            listOf("stop", "unload", "load:catalog-plugin", "start"),
            LifecycleRecordingPlugin.calls,
        )
    }

    private fun createPluginJar(
        pluginDir: File,
        id: String,
        version: String,
        fileName: String = "$id.jar",
        mainClass: String = LifecycleRecordingPlugin::class.java.name,
    ): File {
        val jarFile = pluginDir.resolve(fileName)
        JarOutputStream(FileOutputStream(jarFile)).use { output ->
            output.putNextEntry(JarEntry("plugin.yml"))
            output.write(
                """
                    id: $id
                    name: Test Plugin $id
                    version: $version
                    mainClass: $mainClass
                    apiVersion: $CORE_PLUGIN_API_VERSION
                """.trimIndent().toByteArray(Charsets.UTF_8),
            )
            output.closeEntry()
        }
        return jarFile
    }
}
