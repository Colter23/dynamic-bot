package top.colter.dynamic.plugin

import top.colter.dynamic.core.plugin.Plugin
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.core.tools.loggerFor
import java.io.File
import java.net.URLClassLoader

private val logger = loggerFor<PluginLoader>()

public class PluginLoader {

    public fun load(descriptor: PluginDescriptor, jarPath: String): LoadedPlugin {
        val classLoader = PluginClassLoader(
            urls = arrayOf(File(jarPath).toURI().toURL()),
            hostClassLoader = this::class.java.classLoader,
        )

        try {
            val pluginClass = classLoader.loadClass(descriptor.mainClass)
            val instance = pluginClass.getDeclaredConstructor().newInstance() as? Plugin
                ?: throw IllegalArgumentException("${descriptor.mainClass} 未实现 Plugin 接口")

            return LoadedPlugin(instance, classLoader)
        } catch (e: Throwable) {
            runCatching { classLoader.close() }
                .onFailure { logger.warn(it) { "插件类加载器关闭失败：pluginId=${descriptor.id}" } }
            throw e
        }
    }
}

public data class LoadedPlugin(
    val instance: Plugin,
    val classLoader: URLClassLoader,
)
