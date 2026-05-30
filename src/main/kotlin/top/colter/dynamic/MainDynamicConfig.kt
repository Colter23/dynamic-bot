package top.colter.dynamic

import top.colter.dynamic.core.command.CommandPermissionRule

public data class MainDynamicConfig(
    val templates: PushTemplates = PushTemplates(),
    val command: CommandConfig = CommandConfig(),
    val subscription: SubscriptionConfig = SubscriptionConfig(),
    val linkParsing: LinkParsingConfig = LinkParsingConfig(),
    val imageCache: ImageCacheConfig = ImageCacheConfig(),
    val delivery: DeliveryConfig = DeliveryConfig(),
    val draw: DrawSettings = DrawSettings(),
    val webAdmin: WebAdminConfig = WebAdminConfig(),
) {
    public companion object {
        public const val CONFIG_ID: String = "main"
    }
}

public data class PushTemplates(
    val dynamic: String = DEFAULT_DYNAMIC_TEMPLATE,
    val liveStarted: String = DEFAULT_LIVE_STARTED_TEMPLATE,
    val liveEnded: String = DEFAULT_LIVE_ENDED_TEMPLATE,
) {
    public companion object {
        public const val DEFAULT_DYNAMIC_TEMPLATE: String = "{draw}\n{name} 发布了新动态\n{content}\n{link}"
        public const val DEFAULT_LIVE_STARTED_TEMPLATE: String = "{draw}\n{name} 开播了\n{title}\n{link}"
        public const val DEFAULT_LIVE_ENDED_TEMPLATE: String = "{name} 下播了\n{title}\n直播时长：{duration}\n{link}"
    }
}

public data class WebAdminConfig(
    val enabled: Boolean = true,
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    val token: String = "",
)

public data class SubscriptionConfig(
    val unfollowWhenNoSubscribers: Boolean = false,
)

public data class LinkParsingConfig(
    val autoParseEnabled: Boolean = true,
    val maxLinksPerMessage: Int = 1,
    val autoReplyOnFailure: Boolean = false,
    val autoDedupeTtlMs: Long = 60_000,
)

public data class ImageCacheConfig(
    val sourceRoot: String = "data/images/source",
    val renderedRoot: String = "data/images/draw",
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

public data class DeliveryConfig(
    val maxAttempts: Int = 3,
    val retryDelayMs: Long = 30_000,
    val dispatchConcurrency: Int = 4,
    val lockTtlMs: Long = 120_000,
)

public data class DrawSettings(
    val layout: String = "default",
    val themeColor: String = "#FE65A6",
    val backgroundStartColor: String = "#C3C0FF",
    val backgroundEndColor: String = "#BFFAFF",
    val ornament: DrawOrnament = DrawOrnament.LOGO,
    val width: Int = 1000,
    val font: DrawFontSettings = DrawFontSettings(),
)

public data class DrawFontSettings(
    val text: String = "",
    val emoji: String = "",
)

public enum class DrawOrnament {
    LOGO,
    QRCODE,
    NONE,
}

public data class CommandConfig(
    val prefix: String = "/db",
    val permissions: List<CommandPermissionRule> = emptyList(),
)
