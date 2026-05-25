package top.colter.dynamic

import top.colter.dynamic.core.data.ChatType
import top.colter.dynamic.core.data.CommandRole

public data class MainDynamicConfig(
    val templates: Map<String, String> = mapOf(DEFAULT_TEMPLATE_NAME to DEFAULT_TEMPLATE),
    val command: CommandConfig = CommandConfig(),
    val subscription: SubscriptionConfig = SubscriptionConfig(),
    val imageCache: ImageCacheConfig = ImageCacheConfig(),
) {
    public companion object {
        public const val CONFIG_ID: String = "main"
        public const val DEFAULT_TEMPLATE_NAME: String = "default"
        public const val DEFAULT_TEMPLATE: String = "{publisher.name} 发布了新动态\n{dynamic.text}\n{dynamic.link}"
    }
}

public data class SubscriptionConfig(
    val unfollowWhenNoSubscribers: Boolean = false,
)

public data class ImageCacheConfig(
    val sourceRoot: String = "data/image-cache/source",
    val renderedRoot: String = "data/dynamic-images",
    val downloadTimeoutMs: Long = 10_000,
    val maxConcurrentDownloads: Int = 8,
    val cleanupCron: String = "0 4 * * *",
    val sourceCleanup: ImageCleanupConfig = ImageCleanupConfig(),
    val renderedCleanup: ImageCleanupConfig = ImageCleanupConfig(),
)

public data class ImageCleanupConfig(
    val enabled: Boolean = true,
    val maxIdleDays: Long = 30,
)

public data class CommandConfig(
    val prefix: String = "/db",
    val permissions: List<CommandPermissionRule> = emptyList(),
)

public data class CommandPermissionRule(
    val platform: String = "*",
    val chatType: ChatType? = null,
    val chatId: String = "*",
    val senderId: String = "*",
    val role: CommandRole = CommandRole.ADMIN,
)
