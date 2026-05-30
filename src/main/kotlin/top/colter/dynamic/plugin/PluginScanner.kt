package top.colter.dynamic.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.core.tools.loggerFor
import java.io.File
import java.util.jar.JarFile

private val logger = loggerFor<PluginScanner>()

public class PluginScanner(
    private val pluginDir: File,
    private val objectMapper: ObjectMapper,
) {
    public data class ScanResult(
        val descriptor: PluginDescriptor,
        val jarFile: File,
    )

    public fun scanForPlugins(): List<ScanResult> {
        if (!pluginDir.exists()) {
            pluginDir.mkdirs()
            logger.info { "插件目录已创建：${pluginDir.absolutePath}" }
            return emptyList()
        }

        if (!pluginDir.isDirectory) {
            logger.error { "插件路径不是目录：${pluginDir.absolutePath}" }
            return emptyList()
        }

        return pluginDir
            .listFiles { file -> file.isFile && file.extension.equals("jar", ignoreCase = true) }
            ?.mapNotNull { jarFile ->
                runCatching { parsePluginDescriptor(jarFile) }
                    .onFailure { logger.error(it) { "解析插件描述失败：jar=${jarFile.name}" } }
                    .getOrNull()
                    ?.let { ScanResult(it, jarFile) }
            }
            ?: emptyList()
    }

    private fun parsePluginDescriptor(jarFile: File): PluginDescriptor {
        JarFile(jarFile).use { jar ->
            val entry = jar.getEntry("plugin.yml")
                ?: throw IllegalArgumentException("未找到 plugin.yml：${jarFile.name}")

            jar.getInputStream(entry).use { input ->
                return objectMapper.readValue(input, PluginDescriptor::class.java)
            }
        }
    }
}
