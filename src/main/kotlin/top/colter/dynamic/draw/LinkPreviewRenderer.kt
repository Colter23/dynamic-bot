package top.colter.dynamic.draw

import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.DynamicBlock
import top.colter.dynamic.core.data.DynamicBlockRole
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicMediaCard
import top.colter.dynamic.core.data.DynamicMediaCardKind
import top.colter.dynamic.core.data.DynamicMetric
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.MediaCardStyle
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourcePayload
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.TextBlock
import top.colter.dynamic.core.data.UpdateKey
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkPreview
import top.colter.dynamic.repository.PublisherRepository
import top.colter.skiko.data.Ratio

public fun interface LinkPreviewRenderer {
    public suspend fun render(preview: LinkPreview): MediaRef
}

public class DefaultLinkPreviewRenderer(
    private val configProvider: () -> MainDynamicConfig = { MainDynamicConfig() },
    drawService: DynamicDrawService? = null,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : LinkPreviewRenderer {
    private val runtimeDrawService: DynamicDrawService by lazy {
        drawService ?: DefaultDynamicDrawService(configProvider = configProvider)
    }

    override suspend fun render(preview: LinkPreview): MediaRef {
        val incomingPublisher = preview.publisher
        val storedPublisher = incomingPublisher?.let { PublisherRepository.findByKey(it.key) }
        val publisher = resolvePreviewPublisher(preview, incomingPublisher, storedPublisher)
        val update = buildLinkPreviewSourceUpdate(
            preview = preview,
            publisher = publisher,
            occurredAtEpochSeconds = nowEpochSeconds(),
        )
        return runtimeDrawService.render(update, storedPublisher)
    }
}

internal fun buildLinkPreviewSourceUpdate(
    preview: LinkPreview,
    publisher: PublisherInfo = preview.publisher ?: defaultLinkPreviewPublisher(preview),
    occurredAtEpochSeconds: Long = System.currentTimeMillis() / 1000,
): SourceUpdate {
    return SourceUpdate(
        key = UpdateKey(
            publisherKey = publisher.key,
            eventType = SourceEventType.DYNAMIC_CREATED,
            externalId = preview.linkPreviewExternalId(),
        ),
        publisher = publisher,
        occurredAtEpochSeconds = occurredAtEpochSeconds,
        observedAtEpochSeconds = occurredAtEpochSeconds,
        link = preview.url,
        payload = buildLinkPreviewSourcePayload(preview, occurredAtEpochSeconds),
    )
}

private fun resolvePreviewPublisher(
    preview: LinkPreview,
    incomingPublisher: PublisherInfo?,
    storedPublisher: Publisher?,
): PublisherInfo {
    val fallback = defaultLinkPreviewPublisher(preview)
    return when {
        incomingPublisher != null && storedPublisher != null -> {
            mergePublisherInfo(mergePublisherInfo(incomingPublisher, storedPublisher.toInfo()), fallback)
        }
        incomingPublisher != null -> mergePublisherInfo(incomingPublisher, fallback)
        else -> fallback
    }
}

private fun buildLinkPreviewSourcePayload(preview: LinkPreview, occurredAtEpochSeconds: Long): SourcePayload {
    return if (preview.kind == LinkKinds.LIVE) {
        buildLivePreviewPayload(preview, occurredAtEpochSeconds)
    } else {
        buildLinkPreviewPayload(preview)
    }
}

private fun buildLivePreviewPayload(preview: LinkPreview, occurredAtEpochSeconds: Long): LivePayload {
    val status = preview.liveStatus ?: preview.badge.toLiveStatus() ?: LiveStatus.OPEN
    val startedAt = when (status) {
        LiveStatus.OPEN -> preview.liveStartedAtEpochSeconds ?: occurredAtEpochSeconds
        LiveStatus.ROUND,
        LiveStatus.CLOSE -> preview.liveStartedAtEpochSeconds
    }
    return LivePayload(
        roomId = preview.id.trim().takeIf { it.isNotBlank() } ?: preview.url.stableFallbackId(),
        title = preview.title.trim().takeIf { it.isNotBlank() } ?: "直播间",
        area = preview.normalizedDescription().takeIf { it.isNotBlank() },
        cover = preview.cover?.takeIf { it.uri.isNotBlank() },
        status = status,
        statusText = preview.badge?.trim()?.takeIf { it.isNotBlank() },
        startedAtEpochSeconds = startedAt,
        metrics = preview.metrics,
    )
}

private fun buildLinkPreviewPayload(preview: LinkPreview): DynamicPayload {
    val description = preview.normalizedDescription()
    val longNoCover = preview.cover == null && description.length > LONG_NO_COVER_DESCRIPTION_CHARS
    return DynamicPayload(
        title = preview.title.trim().takeIf { longNoCover && it.isNotBlank() },
        blocks = buildLinkPreviewBlocks(
            preview = preview,
            description = description,
            longNoCover = longNoCover,
        ),
        metrics = preview.metrics,
    )
}

private fun buildLinkPreviewBlocks(
    preview: LinkPreview,
    description: String,
    longNoCover: Boolean,
): List<DynamicBlock> {
    return buildList {
        if (longNoCover && description.isNotBlank()) {
            add(TextBlock(DynamicContent.text(description)))
        }
        add(
            MediaCardBlock(
                style = preview.mediaCardStyle(longNoCover),
                role = if (longNoCover) DynamicBlockRole.ADDITIONAL else DynamicBlockRole.BODY,
                card = DynamicMediaCard(
                    kind = preview.kind.toMediaCardKind(),
                    sourceKind = "${preview.platformId.value}.${preview.kind.ifBlank { "link" }}",
                    id = preview.id.takeIf { it.isNotBlank() },
                    title = preview.cardTitle(longNoCover),
                    description = preview.cardDescription(description, longNoCover),
                    badge = preview.badge ?: preview.kind.label(),
                    cover = preview.cover?.takeIf { it.uri.isNotBlank() },
                    coverRatio = preview.coverRatio(),
                    durationSeconds = preview.durationSeconds,
                    info = preview.metrics.infoText().takeIf { it.isNotBlank() },
                    metrics = preview.metrics,
                    link = preview.url.takeIf { it.isNotBlank() },
                ),
            ),
        )
    }
}

private fun defaultLinkPreviewPublisher(preview: LinkPreview): PublisherInfo {
    val platformId = preview.platformId
    val externalId = preview.id
        .trim()
        .takeIf { it.isNotBlank() }
        ?: preview.url.stableFallbackId()
    return PublisherInfo(
        key = PublisherKey.of(
            platformId = platformId.value,
            kind = if (preview.kind == LinkKinds.USER) PublisherKind.USER else PublisherKind.OTHER,
            externalId = externalId,
        ),
        name = platformId.value.ifBlank { "链接预览" },
        avatar = preview.cover
            ?.takeIf { it.uri.isNotBlank() }
            ?.copy(kind = MediaKind.AVATAR)
            ?: MediaRef("", MediaKind.AVATAR),
    )
}

private fun LinkPreview.normalizedDescription(): String {
    return description.trim()
        .takeIf { it.isNotBlank() && it != title.trim() }
        .orEmpty()
}

private fun LinkPreview.mediaCardStyle(longNoCover: Boolean): MediaCardStyle {
    if (longNoCover) return MediaCardStyle.MINI
    return when {
        cover != null && kind in setOf(LinkKinds.VIDEO, LinkKinds.LIVE, LinkKinds.USER) -> MediaCardStyle.LARGE
        cover != null -> MediaCardStyle.SMALL
        else -> MediaCardStyle.SMALL
    }
}

private fun LinkPreview.cardTitle(longNoCover: Boolean): String {
    if (longNoCover) return "打开${kind.label()}"
    return title.trim().takeIf { it.isNotBlank() } ?: url.ifBlank { kind.label() }
}

private fun LinkPreview.cardDescription(
    description: String,
    longNoCover: Boolean,
): String {
    if (!longNoCover) return description
    return listOfNotNull(
        metrics.infoText().takeIf { it.isNotBlank() },
        url.hostLabel(),
        url.takeIf { it.isNotBlank() },
    ).distinct().joinToString(" / ")
}

private fun LinkPreview.coverRatio(): Float? {
    return when (kind) {
        LinkKinds.USER -> Ratio.BANNER
        else -> null
    }
}

private fun LinkPreview.linkPreviewExternalId(): String {
    val kindPart = kind.ifBlank { "link" }
    val idPart = id.trim().takeIf { it.isNotBlank() } ?: url.stableFallbackId()
    return listOf("link-preview", kindPart, idPart).joinToString(":")
}

private fun String.toMediaCardKind(): DynamicMediaCardKind {
    return when (this) {
        LinkKinds.VIDEO -> DynamicMediaCardKind.VIDEO
        LinkKinds.LIVE -> DynamicMediaCardKind.LIVE
        LinkKinds.USER -> DynamicMediaCardKind.LINK
        else -> DynamicMediaCardKind.LINK
    }
}

private fun String.label(): String {
    return when (this) {
        LinkKinds.VIDEO -> "视频"
        LinkKinds.LIVE -> "直播"
        LinkKinds.USER -> "用户"
        LinkKinds.DYNAMIC -> "动态"
        else -> "链接"
    }
}

private fun String?.toLiveStatus(): LiveStatus? {
    val value = this?.trim().orEmpty()
    if (value.isBlank()) return null
    return when {
        "轮播" in value -> LiveStatus.ROUND
        "未开播" in value || "已结束" in value || "下播" in value -> LiveStatus.CLOSE
        "直播中" in value || "开播" in value -> LiveStatus.OPEN
        else -> null
    }
}

private fun List<DynamicMetric>.infoText(): String {
    return mapNotNull { metric ->
        metric.display?.takeIf { it.isNotBlank() }?.let { display ->
            when (metric.key) {
                "play" -> "${display}播放"
                "danmaku" -> "${display}弹幕"
                "like" -> "${display}点赞"
                "coin" -> "${display}投币"
                "favorite" -> "${display}收藏"
                "comment" -> "${display}评论"
                "forward", "share" -> "${display}转发"
                "online" -> "${display}在线"
                "follow", "attention" -> "${display}关注"
                else -> display
            }
        }
    }.joinToString(" / ")
}

private fun mergePublisherInfo(primary: PublisherInfo, fallback: PublisherInfo): PublisherInfo {
    return primary.copy(
        name = primary.name.ifBlank { fallback.name },
        avatarBadgeKey = primary.avatarBadgeKey ?: fallback.avatarBadgeKey,
        avatar = primary.avatar.takeIf { it.uri.isNotBlank() } ?: fallback.avatar,
        banner = primary.banner ?: fallback.banner,
        pendant = primary.pendant ?: fallback.pendant,
    )
}

private fun String.stableFallbackId(): String {
    val value = trim().takeIf { it.isNotBlank() } ?: "link-preview"
    return Integer.toUnsignedString(value.hashCode(), 16)
}

private fun String.hostLabel(): String? {
    val host = runCatching { java.net.URI(this).host }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return host.removePrefix("www.")
}

private const val LONG_NO_COVER_DESCRIPTION_CHARS = 120
