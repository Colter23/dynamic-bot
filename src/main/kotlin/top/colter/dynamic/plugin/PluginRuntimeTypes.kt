package top.colter.dynamic.plugin

import top.colter.dynamic.core.plugin.PluginDescriptor

public enum class PluginCapability {
    PUBLISHER_SOURCE,
    PUBLISHER_LOOKUP,
    PUBLISHER_FOLLOW,
    PUBLISHER_LOGIN,
    MESSAGE_SINK,
    INCOMING_MESSAGE_CONSUMER,
    COMMAND_CONTRIBUTOR,
    LINK_RESOLVER,
    LINK_VIDEO_DOWNLOADER,
    ADMIN_PAGE,
    ADMIN_API,
    CONFIGURABLE,
}

public enum class PluginState {
    LOADED,
    ACTIVE,
    FAILED,
}

public data class PluginInfo(
    val descriptor: PluginDescriptor,
    val capabilities: Set<PluginCapability>,
    val state: PluginState,
    val sourceJarPath: String,
    val loadTime: Long = System.currentTimeMillis(),
    val error: Throwable? = null,
)

public data class PluginHandle<T : Any>(
    val info: PluginInfo,
    val instance: T,
)
