package top.colter.dynamic

import com.fasterxml.jackson.annotation.JsonIgnore
import kotlin.math.roundToLong
import top.colter.dynamic.core.command.CommandPermissionRule
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.event.SystemNotificationSeverity
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkVideoQuality
import top.colter.dynamic.core.plugin.MessageSinkRoutingPolicy

public data class MainDynamicConfig(
    val templates: PushTemplates = PushTemplates(),
    val command: CommandConfig = CommandConfig(),
    val subscription: SubscriptionConfig = SubscriptionConfig(),
    val linkParsing: LinkParsingConfig = LinkParsingConfig(),
    val imageCache: ImageCacheConfig = ImageCacheConfig(),
    val outboundMedia: OutboundMediaConfig = OutboundMediaConfig(),
    val notifications: NotificationConfig = NotificationConfig(),
    val messageRouting: MessageRoutingConfig = MessageRoutingConfig(),
    val delivery: DeliveryConfig = DeliveryConfig(),
    val draw: DrawSettings = DrawSettings(),
    val pluginCatalog: PluginCatalogConfig = PluginCatalogConfig(),
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
        public const val DEFAULT_DYNAMIC_TEMPLATE: String = "{draw}\n{name}@动态\n{link}"
        public const val DEFAULT_LIVE_STARTED_TEMPLATE: String = "{draw}\n{name}@直播\n{title}\n{link}"
        public const val DEFAULT_LIVE_ENDED_TEMPLATE: String = "{name} 直播结束啦!\n直播时长：{duration}"
    }
}

public data class WebAdminConfig(
    val enabled: Boolean = true,
    val host: String = "127.0.0.1",
    val port: Int = 2233,
    val token: String = "",
    val logBufferCapacity: Int = 1_000,
)

public data class PluginCatalogConfig(
    val url: String = DEFAULT_URL,
    val cacheSeconds: Long = 600,
    val downloadTimeoutSeconds: Double = 60.0,
    val maxDownloadMegabytes: Double = 200.0,
) {
    @get:JsonIgnore
    public val maxDownloadBytes: Long
        get() = megabytesToBytes(maxDownloadMegabytes)

    public companion object {
        public const val DEFAULT_URL: String =
            "https://raw.githubusercontent.com/Colter23/dynamic-bot/main/plugins/catalog.json"
    }
}

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
    val templates: LinkParseTemplates = LinkParseTemplates(),
    val videoDownload: LinkVideoDownloadConfig = LinkVideoDownloadConfig(),
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

public data class LinkParseTemplates(
    val video: String = DEFAULT_PREVIEW_TEMPLATE,
    val live: String = DEFAULT_PREVIEW_TEMPLATE,
    val user: String = DEFAULT_PREVIEW_TEMPLATE,
    val fallback: String = DEFAULT_PREVIEW_TEMPLATE,
    val videoFile: String = DEFAULT_VIDEO_FILE_TEMPLATE,
) {
    public fun forKind(kind: String): String {
        return when (kind) {
            LinkKinds.VIDEO -> video
            LinkKinds.LIVE -> live
            LinkKinds.USER -> user
            else -> fallback
        }
    }

    public companion object {
        public const val DEFAULT_PREVIEW_TEMPLATE: String = "{draw}"
        public const val DEFAULT_VIDEO_FILE_TEMPLATE: String = "{video}"
    }
}

public data class LinkVideoDownloadConfig(
    val enabled: Boolean = false,
    val maxDurationSeconds: Long = 300,
    val maxFileMegabytes: Double = 200.0,
    val quality: LinkVideoQuality = LinkVideoQuality.P720,
    val ffmpegPath: String = "",
    val cacheRoot: String = "data/videos/link-parse",
    val timeoutSeconds: Double = 600.0,
    val maxConcurrentDownloads: Int = 1,
    val cleanupMaxIdleDays: Long = 7,
) {
    @get:JsonIgnore
    public val maxFileBytes: Long
        get() = megabytesToBytes(maxFileMegabytes)
}

public data class ImageCacheConfig(
    val sourceRoot: String = "data/images/source",
    val renderedRoot: String = "data/images/draw",
    val downloadTimeoutSeconds: Double = 10.0,
    val maxImageMegabytes: Double = 20.0,
    val maxConcurrentDownloads: Int = 8,
    val memoryMaxMegabytes: Double = 128.0,
    val memoryMaxEntries: Int = 512,
    val cleanupCron: String = "0 4 * * *",
    val sourceCleanup: ImageCleanupConfig = ImageCleanupConfig(),
    val renderedCleanup: ImageCleanupConfig = ImageCleanupConfig(),
) {
    @get:JsonIgnore
    public val maxImageBytes: Long
        get() = megabytesToBytes(maxImageMegabytes)

    @get:JsonIgnore
    public val memoryMaxBytes: Long
        get() = megabytesToBytes(memoryMaxMegabytes)
}

public data class ImageCleanupConfig(
    val enabled: Boolean = true,
    val maxIdleDays: Long = 30,
)

public data class OutboundMediaConfig(
    val enabled: Boolean = true,
    val publicBaseUrl: String = "",
    val urlTtlSeconds: Int = 1_800,
    val signingSecret: String = "",
)

public data class NotificationConfig(
    val enabled: Boolean = true,
    val minSeverity: SystemNotificationSeverity = SystemNotificationSeverity.WARN,
    val dedupeSeconds: Int = 300,
    val routeMonitorIntervalSeconds: Int = 30,
    val adminTargets: List<NotificationTargetConfig> = emptyList(),
)

public data class NotificationTargetConfig(
    val platformId: String = "",
    val targetKind: TargetKind = TargetKind.USER,
    val externalId: String = "",
    val scopeId: String? = null,
    val threadId: String? = null,
    val accountId: String? = null,
    val name: String = "",
    val enabled: Boolean = true,
)

public data class DeliveryConfig(
    val maxAttempts: Int = 3,
    val retryDelaySeconds: Double = 30.0,
    val dispatchConcurrency: Int = 4,
    val lockTtlSeconds: Double = 120.0,
    val historyRetentionDays: Long = 30,
    val cleanupCron: String = "30 4 * * *",
)

public data class MessageRoutingConfig(
    val defaultPolicy: MessageSinkRoutingPolicy = MessageSinkRoutingPolicy(),
    val platformPolicies: List<MessagePlatformRoutingPolicy> = emptyList(),
) {
    public fun policyFor(platformId: String): MessageSinkRoutingPolicy {
        val normalized = platformId.trim().lowercase()
        return platformPolicies
            .firstOrNull { it.platformId.trim().lowercase() == normalized }
            ?.policy
            ?: defaultPolicy
    }
}

public data class MessagePlatformRoutingPolicy(
    val platformId: String,
    val policy: MessageSinkRoutingPolicy = MessageSinkRoutingPolicy(),
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
    val receiveMode: CommandReceiveMode = CommandReceiveMode.PRIMARY_OR_MENTIONED,
    val requirePermissionRule: Boolean = true,
    val permissions: List<CommandPermissionRule> = emptyList(),
)

public enum class CommandReceiveMode {
    ANY,
    PRIMARY_OR_MENTIONED,
    MENTIONED_ONLY,
}

private const val BYTES_PER_MEGABYTE: Long = 1024L * 1024L

private fun megabytesToBytes(megabytes: Double): Long {
    if (megabytes <= 0.0) return 0L
    return (megabytes * BYTES_PER_MEGABYTE.toDouble())
        .roundToLong()
        .coerceAtLeast(1L)
}
