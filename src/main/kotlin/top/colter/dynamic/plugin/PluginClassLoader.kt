package top.colter.dynamic.plugin

import java.net.URL
import java.net.URLClassLoader
import java.util.Enumeration

internal class PluginClassLoader(
    urls: Array<URL>,
    private val hostClassLoader: ClassLoader,
) : URLClassLoader(urls, hostClassLoader) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (isHostProvidedLoggingClass(name)) {
            val loaded = hostClassLoader.loadClass(name)
            if (resolve) resolveClass(loaded)
            return loaded
        }
        return super.loadClass(name, resolve)
    }

    override fun getResource(name: String): URL? {
        return if (isHostProvidedLoggingResource(name)) {
            hostClassLoader.getResource(name)
        } else {
            super.getResource(name)
        }
    }

    override fun getResources(name: String): Enumeration<URL> {
        return if (isHostProvidedLoggingResource(name)) {
            hostClassLoader.getResources(name)
        } else {
            super.getResources(name)
        }
    }

    private fun isHostProvidedLoggingClass(name: String): Boolean {
        return HOST_PROVIDED_LOGGING_PACKAGES.any { name.startsWith(it) }
    }

    private fun isHostProvidedLoggingResource(name: String): Boolean {
        return name in HOST_PROVIDED_LOGGING_RESOURCES
    }

    private companion object {
        val HOST_PROVIDED_LOGGING_PACKAGES: Set<String> = setOf(
            "ch.qos.logback.",
            "io.github.oshai.kotlinlogging.",
            "org.apache.logging.log4j.",
            "org.slf4j.",
        )

        val HOST_PROVIDED_LOGGING_RESOURCES: Set<String> = setOf(
            "logback-test.xml",
            "logback.xml",
            "log4j2-test.xml",
            "log4j2.xml",
            "META-INF/log4j-provider.properties",
            "META-INF/services/ch.qos.logback.classic.spi.Configurator",
            "META-INF/services/org.apache.logging.log4j.spi.Provider",
            "META-INF/services/org.slf4j.spi.SLF4JServiceProvider",
        )
    }
}
