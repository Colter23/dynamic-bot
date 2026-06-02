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
    val autoFollowPublisherOnSubscribe: Boolean = true,
    val unfollowWhenNoSubscribers: Boolean = false,
)

public data class LinkParsingConfig(
    val autoParseEnabled: Boolean = true,
    val fallbackTriggerMode: LinkParseTriggerMode = LinkParseTriggerMode.MENTION_ONLY,
    val maxLinksPerMessage: Int = 1,
    val replyOnFailure: Boolean = false,
    val autoDedupeTtlSeconds: Double = 60.0,
    val progressReply: LinkParseProgressReplyConfig = LinkParseProgressReplyConfig(),
)

public enum class LinkParseTriggerMode {
    DISABLED,
    MENTION_ONLY,
    ALWAYS,
}

public data class LinkParseProgressReplyConfig(
    val enabled: Boolean = true,
    val text: String = "链接解析中，请稍候...",
    val recallOnComplete: Boolean = true,
)

public data class ImageCacheConfig(
    val sourceRoot: String = "data/images/source",
    val renderedRoot: String = "data/images/draw",
    val downloadTimeoutSeconds: Double = 10.0,
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
    val retryDelaySeconds: Double = 30.0,
    val dispatchConcurrency: Int = 4,
    val lockTtlSeconds: Double = 120.0,
)

public data class DrawSettings(
    val layout: String = "default",
    val themeColors: String = "#FE65A6",
    val autoTheme: Boolean = true,
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
