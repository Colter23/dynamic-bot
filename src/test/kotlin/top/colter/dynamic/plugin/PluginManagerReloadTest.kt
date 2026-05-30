package top.colter.dynamic.plugin

import java.net.URL
import java.net.URLClassLoader
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.colter.dynamic.core.plugin.Plugin
import top.colter.dynamic.core.plugin.PluginDescriptor

class PluginManagerReloadTest {

    @Test
    fun reloadPluginShouldReturnNotFoundForUnknownPlugin() {
        val manager = PluginManager(pluginDirPath = createTempDirectory("plugin-manager-reload-missing").toString())

        val result = manager.reloadPlugin("missing")

        assertFalse(result.success)
        assertFalse(result.changed)
        assertEquals("missing", result.pluginId)
        assertEquals(null, result.pluginState)
    }

    @Test
    fun unloadPluginShouldReleaseFailedPluginResources() {
        val manager = PluginManager(pluginDirPath = createTempDirectory("plugin-manager-reload-failed").toString())
        val plugin = TrackingPlugin()
        val classLoader = TrackingClassLoader()
        manager.registerPluginForTest(
            descriptor = PluginDescriptor(
                id = "failed-plugin",
                name = "Failed Plugin",
                version = "0.0.1",
                mainClass = TrackingPlugin::class.java.name,
            ),
            instance = plugin,
            capabilities = setOf(PluginCapability.COMMAND_CONTRIBUTOR),
            state = PluginState.FAILED,
            classLoader = classLoader,
        )

        assertTrue(manager.unloadPlugin("failed-plugin"))

        assertEquals(0, plugin.stopCalls)
        assertEquals(1, plugin.unloadCalls)
        assertTrue(classLoader.closed)
    }

    private class TrackingPlugin : Plugin {
        var stopCalls = 0
        var unloadCalls = 0

        override suspend fun onStop() {
            stopCalls += 1
        }

        override suspend fun onUnload() {
            unloadCalls += 1
        }
    }

    private class TrackingClassLoader : URLClassLoader(emptyArray<URL>()) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
