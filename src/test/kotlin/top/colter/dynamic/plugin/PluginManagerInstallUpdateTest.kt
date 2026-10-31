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

    @Test
    fun installShouldReplaceIncompatibleVersionedJarWithoutDuplicate() {
        val pluginDir = createTempDirectory("plugin-api-upgrade").toFile()
        val downloadDir = createTempDirectory("plugin-api-upgrade-download").toFile()
        val oldJar = createPluginJar(pluginDir, "catalog-plugin", "1.0.0",
            fileName = "catalog-plugin-1.0.0-all.jar", apiVersion = "3.0.0")
        val manager = PluginManager(pluginDirPath = pluginDir.path)
        assertTrue(manager.loadAllPlugins().failedPlugins.containsKey("catalog-plugin"))
        val downloaded = createPluginJar(downloadDir, "catalog-plugin", "1.1.0", fileName = "new.tmp")

        val result = manager.installOrUpdatePluginJar(downloaded, "catalog-plugin", "1.1.0")

        assertEquals("1.0.0", result.oldVersion)
        assertEquals(PluginState.ACTIVE, manager.getAllPlugins().single().state)
        assertEquals(oldJar.canonicalPath, File(manager.getAllPlugins().single().sourceJarPath).canonicalPath)
        assertEquals(listOf(oldJar.name), pluginDir.listFiles()!!.map { it.name })
    }

    @Test
    fun failedIncompatibleUpgradeShouldRestoreOriginalBytesWithoutLoadingOldPlugin() {
        val pluginDir = createTempDirectory("plugin-api-upgrade-rollback").toFile()
        val downloadDir = createTempDirectory("plugin-api-upgrade-rollback-download").toFile()
        val oldJar = createPluginJar(pluginDir, "catalog-plugin", "1.0.0",
            fileName = "catalog-plugin-1.0.0-all.jar", apiVersion = "3.0.0")
        val original = oldJar.readBytes()
        val manager = PluginManager(pluginDirPath = pluginDir.path)
        manager.loadAllPlugins()
        val downloaded = createPluginJar(downloadDir, "catalog-plugin", "1.1.0",
            fileName = "broken.tmp", mainClass = "missing.Plugin")

        assertFailsWith<ClassNotFoundException> {
            manager.installOrUpdatePluginJar(downloaded, "catalog-plugin", "1.1.0")
        }

        assertTrue(original.contentEquals(oldJar.readBytes()))
        assertTrue(manager.getAllPlugins().isEmpty())
        assertEquals(listOf(oldJar.name), pluginDir.listFiles()!!.map { it.name })
    }

    @Test
    fun installShouldRejectAmbiguousDuplicateJarsWithoutChangingFiles() {
        val pluginDir = createTempDirectory("plugin-api-duplicate").toFile()
        val downloadDir = createTempDirectory("plugin-api-duplicate-download").toFile()
        val first = createPluginJar(pluginDir, "catalog-plugin", "1.0.0", fileName = "first.jar", apiVersion = "3.0.0")
        val second = createPluginJar(pluginDir, "catalog-plugin", "1.0.0", fileName = "second.jar", apiVersion = "3.0.0")
        val original = listOf(first.readBytes(), second.readBytes())
        val downloaded = createPluginJar(downloadDir, "catalog-plugin", "1.1.0", fileName = "new.tmp")
        val manager = PluginManager(pluginDirPath = pluginDir.path)

        assertTrue(assertFailsWith<IllegalArgumentException> {
            manager.installOrUpdatePluginJar(downloaded, "catalog-plugin", "1.1.0")
        }.message!!.contains("插件 ID 重复"))

        assertTrue(original[0].contentEquals(first.readBytes()))
        assertTrue(original[1].contentEquals(second.readBytes()))
        assertTrue(downloaded.exists())
    }

    private fun createPluginJar(
        pluginDir: File,
        id: String,
        version: String,
        fileName: String = "$id.jar",
        mainClass: String = LifecycleRecordingPlugin::class.java.name,
        apiVersion: String = CORE_PLUGIN_API_VERSION,
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
                    apiVersion: $apiVersion
                """.trimIndent().toByteArray(Charsets.UTF_8),
            )
            output.closeEntry()
        }
        return jarFile
    }
}
